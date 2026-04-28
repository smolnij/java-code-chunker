package com.smolnij.chunker.apply;

import com.smolnij.chunker.model.CodeChunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only index over a snapshot of {@link EditOp staged ops} that lets the
 * SafeLoop analyzer resolve FQN references for methods the refactor itself
 * is about to introduce.
 *
 * <p>The safety analyzer routinely asks for graph context on FQNs like
 * {@code RalphLoop#loadPromptFromFile} — methods the agent has just staged
 * but that aren't in the pre-indexed Neo4j graph yet. Without this index
 * those requests come back as {@code [analyzer-expand] unresolved (not in
 * graph)} and the verdict stays under the safety threshold forever
 * (see {@code worklog_eval} root cause #1).
 *
 * <p>This class:
 * <ul>
 *   <li>Walks {@link EditOp.AddMethod}, {@link EditOp.ReplaceMethod} and
 *       {@link EditOp.CreateFile} ops in a snapshot of the draft plan</li>
 *   <li>Parses each {@code newCode}/file content with JavaParser to recover
 *       method name, parameter types and call expressions</li>
 *   <li>Emits one synthetic {@link CodeChunk} per discovered method, keyed
 *       by a chunkId in the same format the chunker produces
 *       ({@code fqClassName#methodName(paramType, …)})</li>
 *   <li>Resolves analyzer-supplied identifiers against those synthetic
 *       chunks via exact match, {@code class#method} match, then simple-name
 *       match (mirrors {@code SafeLoopTools.resolveMethodId})</li>
 * </ul>
 *
 * <p>Synthetic chunks have empty {@code calledBy} (no graph data yet) and
 * the file path is set to the staged op's target path when available so the
 * analyzer can see they are proposed-but-not-applied code.
 */
public final class StagedPlanIndex {

    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    /** chunkId → synthetic CodeChunk. */
    private final Map<String, CodeChunk> chunks;

    public StagedPlanIndex(List<EditOp> stagedOps) {
        this.chunks = build(stagedOps);
    }

    /**
     * Resolve a requested method identifier against the staged plan.
     *
     * @param requestedId may be a full chunkId, {@code Class#method},
     *                    {@code Class.method}, {@code fq.Class#method}, or a
     *                    bare method name
     * @return the synthetic chunk if a matching staged method is found,
     *         else {@code null}
     */
    public CodeChunk resolveSynthetic(String requestedId) {
        if (requestedId == null || requestedId.isBlank() || chunks.isEmpty()) return null;
        String id = requestedId.trim();

        // 1) exact chunkId
        CodeChunk direct = chunks.get(id);
        if (direct != null) return direct;

        // 2) "class#method" → match suffix of any chunkId
        //    (handles both simple class and FQ class on either side)
        String afterHash = id.contains("#") ? id.substring(id.indexOf('#') + 1) : id;
        // strip any param list the caller appended
        int parenIdx = afterHash.indexOf('(');
        String requestedMethod = parenIdx >= 0 ? afterHash.substring(0, parenIdx) : afterHash;
        String requestedClass = id.contains("#") ? id.substring(0, id.indexOf('#')) : null;

        // Try class#method (any param list) match
        if (requestedClass != null && !requestedClass.isBlank()) {
            String simpleRequestedClass = requestedClass.contains(".")
                    ? requestedClass.substring(requestedClass.lastIndexOf('.') + 1)
                    : requestedClass;
            for (Map.Entry<String, CodeChunk> e : chunks.entrySet()) {
                CodeChunk c = e.getValue();
                if (!c.getMethodName().equals(requestedMethod)) continue;
                if (c.getFullyQualifiedClassName().equals(requestedClass)
                        || c.getClassName().equals(simpleRequestedClass)) {
                    return c;
                }
            }
        }

        // 3) bare method-name fallback
        for (CodeChunk c : chunks.values()) {
            if (c.getMethodName().equals(requestedMethod)) return c;
        }

        return null;
    }

    /** Diagnostic accessor: all synthetic chunkIds this index can produce. */
    public Set<String> stagedChunkIds() {
        return Collections.unmodifiableSet(chunks.keySet());
    }

    /** True when no staged ops produced a parseable method. */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────
    // Build
    // ─────────────────────────────────────────────────────────────────

    private static Map<String, CodeChunk> build(List<EditOp> stagedOps) {
        if (stagedOps == null || stagedOps.isEmpty()) return Map.of();
        Map<String, CodeChunk> out = new LinkedHashMap<>();

        for (EditOp op : stagedOps) {
            if (op instanceof EditOp.AddMethod add) {
                addMethodChunk(out, add.fqClassName(), add.newCode(),
                        /*replacing*/ false, /*filePath*/ null);
            } else if (op instanceof EditOp.ReplaceMethod rep) {
                addMethodChunk(out, rep.fqClassName(), rep.newCode(),
                        /*replacing*/ true, /*filePath*/ null);
            } else if (op instanceof EditOp.CreateFile cf) {
                if (cf.relPath() != null && cf.relPath().endsWith(".java")) {
                    addAllMethodsFromFile(out, cf.relPath(), cf.content());
                }
            }
            // AddImport / DeleteMethod don't introduce new methods
        }

        return out;
    }

    private static void addMethodChunk(Map<String, CodeChunk> out,
                                       String fqClassName,
                                       String newCode,
                                       boolean replacing,
                                       String filePath) {
        if (fqClassName == null || fqClassName.isBlank() || newCode == null || newCode.isBlank()) return;
        MethodDeclaration md = parseSingleMethod(newCode);
        if (md == null) return;
        CodeChunk chunk = synthesize(fqClassName, md, filePath, replacing);
        if (chunk != null) out.put(chunk.getChunkId(), chunk);
    }

    private static void addAllMethodsFromFile(Map<String, CodeChunk> out, String relPath, String content) {
        if (content == null || content.isBlank()) return;
        ParseResult<CompilationUnit> result;
        try {
            result = PARSER.parse(content);
        } catch (Exception e) {
            return;
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) return;
        CompilationUnit cu = result.getResult().get();

        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString())
                .orElse("");

        for (com.github.javaparser.ast.body.TypeDeclaration<?> type : cu.getTypes()) {
            String simpleClass = type.getNameAsString();
            String fqClass = packageName.isEmpty() ? simpleClass : packageName + "." + simpleClass;

            for (MethodDeclaration md : type.findAll(MethodDeclaration.class)) {
                CodeChunk chunk = synthesize(fqClass, md, relPath, /*replacing*/ false);
                if (chunk != null) out.put(chunk.getChunkId(), chunk);
            }
        }
    }

    private static CodeChunk synthesize(String fqClassName,
                                        MethodDeclaration md,
                                        String filePath,
                                        boolean replacing) {
        String methodName = md.getNameAsString();
        String paramTypes = md.getParameters().stream()
                .map(p -> p.getTypeAsString())
                .collect(Collectors.joining(", "));
        String chunkId = fqClassName + "#" + methodName + "(" + paramTypes + ")";

        String simpleClass = fqClassName.contains(".")
                ? fqClassName.substring(fqClassName.lastIndexOf('.') + 1)
                : fqClassName;
        String packageName = fqClassName.contains(".")
                ? fqClassName.substring(0, fqClassName.lastIndexOf('.'))
                : "";

        String signature;
        try {
            signature = md.getDeclarationAsString(true, true, true);
        } catch (Exception e) {
            signature = methodName + "(" + paramTypes + ")";
        }

        Set<String> calls = new LinkedHashSet<>();
        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            StringBuilder sb = new StringBuilder();
            call.getScope().ifPresent(s -> sb.append(s.toString()).append("."));
            sb.append(call.getNameAsString());
            calls.add(sb.toString());
        }

        List<String> annotations = md.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .collect(Collectors.toList());

        CodeChunk c = new CodeChunk();
        c.setChunkId(chunkId);
        c.setFilePath(filePath != null ? filePath : "(staged, not yet written)");
        c.setPackageName(packageName);
        c.setClassName(simpleClass);
        c.setFullyQualifiedClassName(fqClassName);
        c.setClassSignature(""); // unknown for staged ops
        c.setMethodName(methodName);
        c.setMethodSignature(signature);
        c.setMethodAnnotations(annotations);
        c.setStartLine(md.getBegin().map(p -> p.line).orElse(0));
        c.setEndLine(md.getEnd().map(p -> p.line).orElse(0));
        c.setCode(md.toString());
        c.setCalls(new ArrayList<>(calls));
        c.setCalledBy(new ArrayList<>()); // no graph data for new methods
        c.setParentClass(fqClassName);
        c.setParentPackage(packageName);
        return c;
    }

    private static MethodDeclaration parseSingleMethod(String code) {
        String trimmed = code.trim();
        // Wrap in a synthetic class — most reliable path for a bare method body
        try {
            ParseResult<CompilationUnit> wrapped = PARSER.parse(
                    "class _Synthetic { " + trimmed + " }");
            if (wrapped.isSuccessful() && wrapped.getResult().isPresent()) {
                List<MethodDeclaration> methods = wrapped.getResult().get().findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) return methods.get(0);
            }
        } catch (Exception ignored) { }

        // Fallback: maybe the LLM produced a full file
        try {
            ParseResult<CompilationUnit> direct = PARSER.parse(trimmed);
            if (direct.isSuccessful() && direct.getResult().isPresent()) {
                List<MethodDeclaration> methods = direct.getResult().get().findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) return methods.get(0);
            }
        } catch (Exception ignored) { }

        return null;
    }
}
