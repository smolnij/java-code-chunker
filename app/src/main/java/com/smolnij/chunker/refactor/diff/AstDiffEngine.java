package com.smolnij.chunker.refactor.diff;

import com.smolnij.chunker.model.CodeChunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Computes AST-level structural diffs between original code (from the Neo4j graph)
 * and LLM-proposed code using JavaParser.
 *
 * <p>Unlike text-based diffs, this understands Java structure:
 * <ul>
 *   <li>Signature changes: return type, parameters, visibility, throws clause</li>
 *   <li>Body changes: new/removed method calls (call graph delta)</li>
 *   <li>Annotation changes: added/removed method annotations</li>
 *   <li>Classification: body-only vs. signature-breaking</li>
 * </ul>
 *
 * <p>Method call extraction uses unresolved names (no symbol solver) since
 * proposed code is parsed in isolation. This is sufficient for delta detection
 * — we compare the set of call names, not fully-qualified signatures.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   AstDiffEngine engine = new AstDiffEngine();
 *   MethodDiff diff = engine.diff(originalChunk, proposedCode);
 *   System.out.println(diff.isSignatureChanged());
 *   System.out.println(diff.getAddedCalls());
 * </pre>
 */
public class AstDiffEngine {

    private final JavaParser parser;

    public AstDiffEngine() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(config);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main diff entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute a structured diff between the original chunk and proposed code.
     *
     * @param original     the original CodeChunk from the Neo4j graph
     * @param proposedCode the LLM's proposed replacement code (a method body or full method)
     * @return the structured MethodDiff, or a parse-error diff if either side fails
     */
    public MethodDiff diff(CodeChunk original, String proposedCode) {
        MethodDeclaration originalAst = parseMethod(original.getCode(), original.getImports());
        if (originalAst == null) {
            return MethodDiff.parseError(original.getChunkId(), "original");
        }

        MethodDeclaration proposedAst = parseMethod(proposedCode, original.getImports());
        if (proposedAst == null) {
            return MethodDiff.parseError(original.getChunkId(), "proposed");
        }

        MethodDiff diff = new MethodDiff(original.getChunkId());

        // ── 1. Return type ──
        String oldReturn = originalAst.getTypeAsString();
        String newReturn = proposedAst.getTypeAsString();
        diff.setReturnTypeChanged(!oldReturn.equals(newReturn));
        diff.setOldReturnType(oldReturn);
        diff.setNewReturnType(newReturn);

        // ── 2. Parameters ──
        String oldParams = originalAst.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        String newParams = proposedAst.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        diff.setParamsChanged(!oldParams.equals(newParams));
        diff.setOldParams(oldParams);
        diff.setNewParams(newParams);

        // ── 3. Visibility ──
        String oldVis = getVisibility(originalAst);
        String newVis = getVisibility(proposedAst);
        diff.setVisibilityChanged(!oldVis.equals(newVis));
        diff.setOldVisibility(oldVis);
        diff.setNewVisibility(newVis);

        // ── 4. Throws clause ──
        String oldThrows = originalAst.getThrownExceptions().stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
        String newThrows = proposedAst.getThrownExceptions().stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
        diff.setThrowsChanged(!oldThrows.equals(newThrows));
        diff.setOldThrows(oldThrows.isEmpty() ? "(none)" : oldThrows);
        diff.setNewThrows(newThrows.isEmpty() ? "(none)" : newThrows);

        // ── 5. Call graph delta ──
        Set<String> originalCalls = extractMethodCalls(originalAst);
        Set<String> proposedCalls = extractMethodCalls(proposedAst);

        Set<String> addedCalls = new LinkedHashSet<>(proposedCalls);
        addedCalls.removeAll(originalCalls);

        Set<String> removedCalls = new LinkedHashSet<>(originalCalls);
        removedCalls.removeAll(proposedCalls);

        diff.setAddedCalls(addedCalls);
        diff.setRemovedCalls(removedCalls);

        // ── 6. Annotation delta ──
        Set<String> origAnnotations = extractAnnotations(originalAst);
        Set<String> propAnnotations = extractAnnotations(proposedAst);

        Set<String> addedAnnotations = new LinkedHashSet<>(propAnnotations);
        addedAnnotations.removeAll(origAnnotations);

        Set<String> removedAnnotations = new LinkedHashSet<>(origAnnotations);
        removedAnnotations.removeAll(propAnnotations);

        diff.setAddedAnnotations(addedAnnotations);
        diff.setRemovedAnnotations(removedAnnotations);

        // ── 7. Body-only change detection ──
        boolean signatureUnchanged = !diff.isSignatureChanged();
        boolean annotationsUnchanged = addedAnnotations.isEmpty() && removedAnnotations.isEmpty();
        boolean bodyDiffers = !getBodyText(originalAst).equals(getBodyText(proposedAst));
        diff.setBodyOnlyChange(signatureUnchanged && annotationsUnchanged && bodyDiffers);

        return diff;
    }

    // ═══════════════════════════════════════════════════════════════
    // Cross-method analysis
    // ═══════════════════════════════════════════════════════════════

    private static final Pattern JAVA_FENCE_PATTERN = Pattern.compile(
            "```\\s*java\\s*\\r?\\n(.*?)```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * Compare a set of original methods (in scope) against the methods the LLM
     * proposed in its response. Surfaces cross-method invariants:
     * <ul>
     *   <li>originals missing from the proposal</li>
     *   <li>new public/protected methods the caller didn't ask for</li>
     *   <li>methods that silently lost {@code @Override}</li>
     * </ul>
     *
     * <p>Matching is by simple method name within each class. When a class is
     * not identifiable for a proposed method (e.g. a bare method block outside
     * a class wrapper), the method is matched across the scope by name only.
     *
     * <p>Per-method {@link MethodDiff}s inside the returned {@link CrossMethodDiff}
     * are unscored — callers should wrap each with {@link DiffScorer#score(MethodDiff)}
     * for blast-radius analysis.
     *
     * @param originals the original code chunks that should be preserved
     * @param agentResponse the full LLM response (may contain multiple ```java blocks)
     */
    public CrossMethodDiff analyze(List<CodeChunk> originals, String agentResponse) {
        if (originals == null || originals.isEmpty() || agentResponse == null || agentResponse.isBlank()) {
            return CrossMethodDiff.empty();
        }

        List<ProposedMethod> proposed = extractProposedMethods(agentResponse);
        if (proposed.isEmpty()) {
            return CrossMethodDiff.empty();
        }

        // Index originals: "ClassName#methodName" and bare "methodName" (fallback).
        Map<String, CodeChunk> originalByQualified = new LinkedHashMap<>();
        Map<String, List<CodeChunk>> originalByName = new LinkedHashMap<>();
        for (CodeChunk c : originals) {
            String key = qualifiedKey(c.getClassName(), c.getMethodName());
            originalByQualified.put(key, c);
            originalByName.computeIfAbsent(c.getMethodName(), k -> new ArrayList<>()).add(c);
        }

        Set<String> matchedOriginals = new LinkedHashSet<>();
        List<MethodDiff> methodDiffs = new ArrayList<>();
        List<CrossMethodDiff.AddedMember> addedPublic = new ArrayList<>();
        List<String> removedOverrides = new ArrayList<>();

        for (ProposedMethod pm : proposed) {
            CodeChunk match = findOriginalFor(pm, originalByQualified, originalByName, matchedOriginals);
            if (match == null) {
                if ("public".equals(pm.visibility()) || "protected".equals(pm.visibility())) {
                    addedPublic.add(new CrossMethodDiff.AddedMember(
                            pm.className(), pm.methodName(), pm.visibility(), pm.signature()));
                }
                continue;
            }
            matchedOriginals.add(match.getChunkId());
            MethodDiff diff = diff(match, pm.code());
            methodDiffs.add(diff);

            if (hadOverride(match) && !pm.hasOverride() && !diff.isParseError()) {
                removedOverrides.add(match.getClassName() + "." + match.getMethodName());
            }
        }

        List<CrossMethodDiff.DeletedMember> deleted = new ArrayList<>();
        for (CodeChunk c : originals) {
            if (matchedOriginals.contains(c.getChunkId())) continue;
            String vis = originalVisibility(c);
            deleted.add(new CrossMethodDiff.DeletedMember(
                    c.getChunkId(), c.getClassName(), c.getMethodName(), vis));
        }

        return new CrossMethodDiff(methodDiffs, deleted, addedPublic, removedOverrides);
    }

    private CodeChunk findOriginalFor(ProposedMethod pm,
                                       Map<String, CodeChunk> byQualified,
                                       Map<String, List<CodeChunk>> byName,
                                       Set<String> alreadyMatched) {
        if (pm.className() != null && !pm.className().isEmpty()) {
            CodeChunk direct = byQualified.get(qualifiedKey(pm.className(), pm.methodName()));
            if (direct != null && !alreadyMatched.contains(direct.getChunkId())) {
                return direct;
            }
        }
        List<CodeChunk> candidates = byName.get(pm.methodName());
        if (candidates == null || candidates.isEmpty()) return null;
        for (CodeChunk c : candidates) {
            if (!alreadyMatched.contains(c.getChunkId())) {
                return c;
            }
        }
        return null;
    }

    private static String qualifiedKey(String className, String methodName) {
        return (className == null ? "" : className) + "#" + methodName;
    }

    private static boolean hadOverride(CodeChunk chunk) {
        List<String> ann = chunk.getMethodAnnotations();
        if (ann == null) return false;
        for (String a : ann) {
            if (a == null) continue;
            String stripped = a.trim();
            if (stripped.startsWith("@")) stripped = stripped.substring(1);
            int paren = stripped.indexOf('(');
            if (paren >= 0) stripped = stripped.substring(0, paren);
            if ("Override".equalsIgnoreCase(stripped)) return true;
        }
        return false;
    }

    /**
     * Best-effort visibility extractor for an original {@link CodeChunk}, based
     * on its recorded method signature text.
     */
    static String originalVisibility(CodeChunk chunk) {
        String sig = chunk.getMethodSignature();
        if (sig == null) return "package-private";
        String s = sig.trim();
        if (s.startsWith("public ")) return "public";
        if (s.startsWith("protected ")) return "protected";
        if (s.startsWith("private ")) return "private";
        // Signature may start with annotations or modifiers in any order
        if (s.contains(" public ")) return "public";
        if (s.contains(" protected ")) return "protected";
        if (s.contains(" private ")) return "private";
        return "package-private";
    }

    /** Extract all methods from all ```java fences in the agent response. */
    private List<ProposedMethod> extractProposedMethods(String response) {
        List<ProposedMethod> out = new ArrayList<>();
        Matcher m = JAVA_FENCE_PATTERN.matcher(response);
        while (m.find()) {
            String block = m.group(1);
            collectMethodsFromBlock(block, out);
        }
        return out;
    }

    private void collectMethodsFromBlock(String block, List<ProposedMethod> sink) {
        CompilationUnit cu = parseToCompilationUnit(block);
        if (cu == null) return;

        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            String name = md.getNameAsString();
            String visibility;
            if (md.isPublic()) visibility = "public";
            else if (md.isProtected()) visibility = "protected";
            else if (md.isPrivate()) visibility = "private";
            else visibility = "package-private";

            String signature;
            try {
                signature = md.getDeclarationAsString(true, true, true);
            } catch (Exception e) {
                signature = md.getNameAsString() + "(" + md.getParameters() + ")";
            }

            String className = md.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                    .map(t -> t.getNameAsString())
                    .orElse("");
            // Ignore the synthetic wrapper class the engine uses internally.
            if ("_Synthetic".equals(className)) className = "";

            boolean hasOverride = md.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .anyMatch(n -> n.equalsIgnoreCase("Override"));

            sink.add(new ProposedMethod(className, name, visibility, signature, md.toString(), hasOverride));
        }
    }

    private CompilationUnit parseToCompilationUnit(String block) {
        String trimmed = block.trim();
        try {
            ParseResult<CompilationUnit> direct = parser.parse(trimmed);
            if (direct.isSuccessful() && direct.getResult().isPresent()) {
                CompilationUnit cu = direct.getResult().get();
                if (!cu.findAll(MethodDeclaration.class).isEmpty()) return cu;
            }
        } catch (Exception ignored) { }

        try {
            ParseResult<CompilationUnit> wrapped = parser.parse(
                    "class _Synthetic { " + trimmed + " }");
            if (wrapped.isSuccessful() && wrapped.getResult().isPresent()) {
                return wrapped.getResult().get();
            }
        } catch (Exception ignored) { }

        return null;
    }

    private record ProposedMethod(String className,
                                  String methodName,
                                  String visibility,
                                  String signature,
                                  String code,
                                  boolean hasOverride) { }

    // ═══════════════════════════════════════════════════════════════
    // Parsing helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a code string into a MethodDeclaration.
     *
     * <p>Tries multiple strategies:
     * <ol>
     *   <li>Parse as a standalone method declaration</li>
     *   <li>Wrap in a synthetic class and parse the compilation unit</li>
     *   <li>Wrap in a synthetic class with imports and parse</li>
     * </ol>
     */
    MethodDeclaration parseMethod(String code, List<String> imports) {
        if (code == null || code.isBlank()) return null;

        String trimmed = code.trim();

        // Strategy 1: Try parsing as a standalone method body statement
        // (JavaParser can parse a MethodDeclaration directly in some cases)
        try {
            ParseResult<CompilationUnit> result = parser.parse(
                    "class _Synthetic { " + trimmed + " }");
            if (result.isSuccessful() && result.getResult().isPresent()) {
                List<MethodDeclaration> methods = result.getResult().get()
                        .findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) {
                    return methods.get(0);
                }
            }
        } catch (Exception ignored) { }

        // Strategy 2: Wrap with imports for better type resolution
        if (imports != null && !imports.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String imp : imports) {
                    String importLine = imp.trim();
                    if (!importLine.startsWith("import ")) {
                        importLine = "import " + importLine;
                    }
                    if (!importLine.endsWith(";")) {
                        importLine += ";";
                    }
                    sb.append(importLine).append("\n");
                }
                sb.append("class _Synthetic { ").append(trimmed).append(" }");

                ParseResult<CompilationUnit> result = parser.parse(sb.toString());
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    List<MethodDeclaration> methods = result.getResult().get()
                            .findAll(MethodDeclaration.class);
                    if (!methods.isEmpty()) {
                        return methods.get(0);
                    }
                }
            } catch (Exception ignored) { }
        }

        // Strategy 3: The code might already be a full class — try parsing as-is
        try {
            ParseResult<CompilationUnit> result = parser.parse(trimmed);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                List<MethodDeclaration> methods = result.getResult().get()
                        .findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) {
                    return methods.get(0);
                }
            }
        } catch (Exception ignored) { }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Extraction helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract all method call names from a method body (unresolved).
     *
     * <p>Returns call names in the form "scope.method" or just "method"
     * when there is no explicit scope. This matches the pattern used in
     * {@link com.smolnij.chunker.callgraph.CallGraphExtractor} but without
     * symbol resolution (since proposed code has no type context).
     */
    private Set<String> extractMethodCalls(MethodDeclaration method) {
        Set<String> calls = new LinkedHashSet<>();
        List<MethodCallExpr> callExprs = method.findAll(MethodCallExpr.class);

        for (MethodCallExpr call : callExprs) {
            StringBuilder sb = new StringBuilder();
            call.getScope().ifPresent(scope -> sb.append(scope.toString()).append("."));
            sb.append(call.getNameAsString());
            calls.add(sb.toString());
        }
        return calls;
    }

    /**
     * Extract annotation names from a method declaration.
     */
    private Set<String> extractAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Get the effective visibility modifier of a method.
     */
    private String getVisibility(MethodDeclaration method) {
        if (method.isPublic()) return "public";
        if (method.isProtected()) return "protected";
        if (method.isPrivate()) return "private";
        return "package-private";
    }

    /**
     * Get the body text for comparison (normalized).
     */
    private String getBodyText(MethodDeclaration method) {
        return method.getBody()
                .map(body -> body.toString().replaceAll("\\s+", " ").trim())
                .orElse("");
    }
}

