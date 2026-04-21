package com.smolnij.chunker.apply;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic engine that turns a {@link PatchPlan} into actual file edits.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>For each op, resolve the target file (via {@link Neo4jGraphReader} for
 *       class-scoped ops, or directly from the op for import / create-file ops).</li>
 *   <li>Parse the target file with JavaParser, wrap in
 *       {@link LexicalPreservingPrinter#setup(com.github.javaparser.ast.Node)}
 *       so comments / whitespace are preserved.</li>
 *   <li>Locate the {@link MethodDeclaration} by name (and original signature
 *       if provided to disambiguate overloads) and mutate the AST
 *       (replace / add / delete).</li>
 *   <li>Re-print via {@link LexicalPreservingPrinter#print} and stage the
 *       result in an in-memory {@code Path → content} map.</li>
 *   <li>After all ops are staged, re-parse every staged file to catch any
 *       op that produced invalid Java — abort atomically if so.</li>
 *   <li>Commit to disk (optionally writing {@code .bak} copies alongside).</li>
 * </ol>
 *
 * <p>Raw text diffs are never produced or applied. The only string-level
 * concession is {@link EditOp.AddImport}, which modifies import declarations
 * through the AST (not by string insertion). Raw text diffs are never in the loop.
 */
public class PatchApplier {

    private final Path repoRoot;
    private final Neo4jGraphReader graphReader;
    private final boolean dryRun;
    private final boolean backup;
    private final JavaParser parser;

    public PatchApplier(Path repoRoot,
                        Neo4jGraphReader graphReader,
                        boolean dryRun,
                        boolean backup) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.graphReader = graphReader;
        this.dryRun = dryRun;
        this.backup = backup;
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(config);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    public ApplyResult apply(PatchPlan plan) {
        ApplyResult.Builder result = new ApplyResult.Builder().dryRun(dryRun);

        if (plan == null || plan.isEmpty()) {
            return result.error("empty patch plan — nothing to apply").failure();
        }

        // Stage all edits in memory, keyed by absolute path. The same file
        // may be touched by multiple ops; we thread a single CompilationUnit
        // through all of them so rewrites compose.
        Map<Path, CompilationUnit> staged = new LinkedHashMap<>();

        for (EditOp op : plan.ops()) {
            try {
                boolean ok = applyOp(op, staged, result);
                if (!ok) {
                    return result.error("aborting — op failed, no files written").failure();
                }
            } catch (Exception e) {
                result.op(new ApplyResult.OpStatus(
                    opKind(op), describe(op), false, e.getClass().getSimpleName() + ": " + e.getMessage()));
                return result
                    .error("exception applying " + opKind(op) + ": " + e.getMessage())
                    .failure();
            }
        }

        // ── Post-edit parse check: every staged file must still parse as Java ──
        Map<Path, String> staged_text = new LinkedHashMap<>();
        for (Map.Entry<Path, CompilationUnit> e : staged.entrySet()) {
            String rendered = LexicalPreservingPrinter.print(e.getValue());
            staged_text.put(e.getKey(), rendered);
            ParseResult<CompilationUnit> reparse = parser.parse(rendered);
            if (!reparse.isSuccessful()) {
                result.error("post-edit parse failed for " + e.getKey()
                    + ": " + reparse.getProblems());
                return result.failure();
            }
        }

        result.staged(staged_text);

        // ── Commit ──
        List<Path> written = new ArrayList<>();
        if (!dryRun) {
            for (Map.Entry<Path, String> e : staged_text.entrySet()) {
                try {
                    if (backup && Files.exists(e.getKey())) {
                        Path bak = e.getKey().resolveSibling(e.getKey().getFileName() + ".bak");
                        Files.copy(e.getKey(), bak, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.writeString(e.getKey(), e.getValue());
                    written.add(e.getKey());
                } catch (IOException ioe) {
                    result.error("write failed for " + e.getKey() + ": " + ioe.getMessage());
                    return result.failure();
                }
            }
        } else {
            written.addAll(staged_text.keySet());
        }

        return result.changed(written).success();
    }

    // ═══════════════════════════════════════════════════════════════
    // Per-op handlers
    // ═══════════════════════════════════════════════════════════════

    private boolean applyOp(EditOp op,
                            Map<Path, CompilationUnit> staged,
                            ApplyResult.Builder result) throws IOException {
        if (op instanceof EditOp.ReplaceMethod r) return applyReplaceMethod(r, staged, result);
        if (op instanceof EditOp.AddMethod a) return applyAddMethod(a, staged, result);
        if (op instanceof EditOp.DeleteMethod d) return applyDeleteMethod(d, staged, result);
        if (op instanceof EditOp.AddImport i) return applyAddImport(i, staged, result);
        if (op instanceof EditOp.CreateFile c) return applyCreateFile(c, result);
        throw new IllegalStateException("unknown op type: " + op.getClass());
    }

    private boolean applyReplaceMethod(EditOp.ReplaceMethod op,
                                       Map<Path, CompilationUnit> staged,
                                       ApplyResult.Builder result) throws IOException {
        Path target = resolveClassFile(op.fqClassName());
        if (target == null) {
            result.op(new ApplyResult.OpStatus("replace_method", describe(op), false,
                "class not found in graph: " + op.fqClassName()));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        MethodDeclaration existing = findMethod(cu, op.fqClassName(), op.methodName(), op.originalSignature());
        if (existing == null) {
            result.op(new ApplyResult.OpStatus("replace_method", describe(op), false,
                "method not found in " + target.getFileName() + ": "
                    + op.methodName() + "  [signature=" + op.originalSignature() + "]"));
            return false;
        }
        MethodDeclaration replacement = parseSingleMethod(op.newCode());
        if (replacement == null) {
            result.op(new ApplyResult.OpStatus("replace_method", describe(op), false,
                "proposed code did not parse as a method declaration"));
            return false;
        }
        existing.replace(replacement);
        LexicalPreservingPrinter.setup(replacement);
        result.op(new ApplyResult.OpStatus("replace_method", describe(op), true));
        return true;
    }

    private boolean applyAddMethod(EditOp.AddMethod op,
                                   Map<Path, CompilationUnit> staged,
                                   ApplyResult.Builder result) throws IOException {
        Path target = resolveClassFile(op.fqClassName());
        if (target == null) {
            result.op(new ApplyResult.OpStatus("add_method", describe(op), false,
                "class not found in graph: " + op.fqClassName()));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        TypeDeclaration<?> type = findType(cu, op.fqClassName());
        if (type == null) {
            result.op(new ApplyResult.OpStatus("add_method", describe(op), false,
                "type declaration not found: " + op.fqClassName()));
            return false;
        }
        MethodDeclaration md = parseSingleMethod(op.newCode());
        if (md == null) {
            result.op(new ApplyResult.OpStatus("add_method", describe(op), false,
                "proposed code did not parse as a method declaration"));
            return false;
        }
        type.addMember(md);
        LexicalPreservingPrinter.setup(md);
        result.op(new ApplyResult.OpStatus("add_method", describe(op), true));
        return true;
    }

    private boolean applyDeleteMethod(EditOp.DeleteMethod op,
                                      Map<Path, CompilationUnit> staged,
                                      ApplyResult.Builder result) throws IOException {
        Path target = resolveClassFile(op.fqClassName());
        if (target == null) {
            result.op(new ApplyResult.OpStatus("delete_method", describe(op), false,
                "class not found in graph: " + op.fqClassName()));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        MethodDeclaration existing = findMethod(cu, op.fqClassName(), op.methodName(), op.originalSignature());
        if (existing == null) {
            result.op(new ApplyResult.OpStatus("delete_method", describe(op), false,
                "method not found: " + op.methodName()));
            return false;
        }
        existing.remove();
        result.op(new ApplyResult.OpStatus("delete_method", describe(op), true));
        return true;
    }

    private boolean applyAddImport(EditOp.AddImport op,
                                   Map<Path, CompilationUnit> staged,
                                   ApplyResult.Builder result) throws IOException {
        Path target = repoRoot.resolve(op.filePath()).normalize();
        if (!Files.exists(target)) {
            result.op(new ApplyResult.OpStatus("add_import", describe(op), false,
                "file not found: " + target));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        String normalized = op.importDecl().trim();
        if (normalized.startsWith("import ")) normalized = normalized.substring("import ".length());
        if (normalized.endsWith(";")) normalized = normalized.substring(0, normalized.length() - 1);
        boolean isStatic = normalized.startsWith("static ");
        if (isStatic) normalized = normalized.substring("static ".length());
        boolean asterisk = normalized.endsWith(".*");
        String name = asterisk ? normalized.substring(0, normalized.length() - 2) : normalized;

        for (ImportDeclaration existing : cu.getImports()) {
            if (existing.getNameAsString().equals(name)
                    && existing.isStatic() == isStatic
                    && existing.isAsterisk() == asterisk) {
                result.op(new ApplyResult.OpStatus("add_import", describe(op), true,
                    "already present"));
                return true;
            }
        }
        ImportDeclaration added = new ImportDeclaration(name, isStatic, asterisk);
        cu.addImport(added);
        LexicalPreservingPrinter.setup(added);
        result.op(new ApplyResult.OpStatus("add_import", describe(op), true));
        return true;
    }

    private boolean applyCreateFile(EditOp.CreateFile op, ApplyResult.Builder result) throws IOException {
        Path target = repoRoot.resolve(op.relPath()).normalize();
        if (!target.startsWith(repoRoot)) {
            result.op(new ApplyResult.OpStatus("create_file", describe(op), false,
                "target escapes repoRoot: " + target));
            return false;
        }
        if (Files.exists(target)) {
            result.op(new ApplyResult.OpStatus("create_file", describe(op), false,
                "file already exists: " + target));
            return false;
        }
        // Validate the new file parses as Java (if it's a .java file).
        if (op.relPath().endsWith(".java")) {
            ParseResult<CompilationUnit> parsed = parser.parse(op.content());
            if (!parsed.isSuccessful()) {
                result.op(new ApplyResult.OpStatus("create_file", describe(op), false,
                    "content does not parse: " + parsed.getProblems()));
                return false;
            }
        }
        if (!dryRun) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, op.content());
        }
        result.op(new ApplyResult.OpStatus("create_file", describe(op), true));
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Resolution + AST helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve the repo-relative path of the file owning {@code fqClassName}
     * by asking the Neo4j graph. Returns {@code null} if no such class is
     * indexed.
     */
    private Path resolveClassFile(String fqClassName) {
        if (fqClassName == null || fqClassName.isBlank()) return null;
        String chunkId = graphReader.findMethodExact(fqClassName);
        if (chunkId == null) {
            // Try the FQ class name as a fragment — any method of that class will do;
            // they share a file.
            chunkId = graphReader.findMethodExact(fqClassName + "#");
            if (chunkId == null) return null;
        }
        Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(List.of(chunkId));
        CodeChunk chunk = chunks.get(chunkId);
        if (chunk == null || chunk.getFilePath() == null || chunk.getFilePath().isBlank()) {
            return null;
        }
        return repoRoot.resolve(chunk.getFilePath()).normalize();
    }

    private CompilationUnit cuFor(Path absolutePath, Map<Path, CompilationUnit> staged) throws IOException {
        CompilationUnit existing = staged.get(absolutePath);
        if (existing != null) return existing;
        String source = Files.readString(absolutePath);
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            throw new IOException("failed to parse " + absolutePath + ": " + parsed.getProblems());
        }
        CompilationUnit cu = parsed.getResult().get();
        LexicalPreservingPrinter.setup(cu);
        staged.put(absolutePath, cu);
        return cu;
    }

    private MethodDeclaration findMethod(CompilationUnit cu,
                                         String fqClassName,
                                         String methodName,
                                         String originalSignature) {
        TypeDeclaration<?> type = findType(cu, fqClassName);
        if (type == null) return null;

        List<MethodDeclaration> matches = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof MethodDeclaration md && md.getNameAsString().equals(methodName)) {
                matches.add(md);
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);
        if (originalSignature == null || originalSignature.isBlank()) return null;

        // Disambiguate overloads by matching on signature. The graph records
        // {@code methodSignature} like "public void process(Record r) throws IOException";
        // we compare against JavaParser's declaration-as-string form loosely.
        String needle = normalizeSignature(originalSignature);
        for (MethodDeclaration md : matches) {
            try {
                String declared = normalizeSignature(md.getDeclarationAsString(true, true, true));
                if (declared.equals(needle) || declared.contains(needle) || needle.contains(declared)) {
                    return md;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static String normalizeSignature(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private TypeDeclaration<?> findType(CompilationUnit cu, String fqClassName) {
        int dot = fqClassName.lastIndexOf('.');
        final String simpleName = dot >= 0 ? fqClassName.substring(dot + 1) : fqClassName;

        // Walk top-level + nested types.
        for (TypeDeclaration<?> top : cu.getTypes()) {
            if (top.getNameAsString().equals(simpleName)) return top;
            Optional<ClassOrInterfaceDeclaration> nested =
                top.findFirst(ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(simpleName));
            if (nested.isPresent()) return nested.get();
        }
        // Fallback: any type with the matching simple name.
        Optional<ClassOrInterfaceDeclaration> any =
            cu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(simpleName));
        return any.orElse(null);
    }

    private MethodDeclaration parseSingleMethod(String code) {
        if (code == null || code.isBlank()) return null;
        String trimmed = code.trim();
        try {
            ParseResult<CompilationUnit> wrapped = parser.parse("class _S { " + trimmed + " }");
            if (wrapped.isSuccessful() && wrapped.getResult().isPresent()) {
                List<MethodDeclaration> methods = wrapped.getResult().get().findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) return methods.get(0).clone();
            }
        } catch (Exception ignored) { }
        try {
            ParseResult<CompilationUnit> direct = parser.parse(trimmed);
            if (direct.isSuccessful() && direct.getResult().isPresent()) {
                List<MethodDeclaration> methods = direct.getResult().get().findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) return methods.get(0).clone();
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Formatting helpers
    // ═══════════════════════════════════════════════════════════════

    private static String opKind(EditOp op) {
        if (op instanceof EditOp.ReplaceMethod) return "replace_method";
        if (op instanceof EditOp.AddMethod) return "add_method";
        if (op instanceof EditOp.DeleteMethod) return "delete_method";
        if (op instanceof EditOp.AddImport) return "add_import";
        if (op instanceof EditOp.CreateFile) return "create_file";
        return "unknown";
    }

    private static String describe(EditOp op) {
        if (op instanceof EditOp.ReplaceMethod r) return r.fqClassName() + "#" + r.methodName();
        if (op instanceof EditOp.AddMethod a) return a.fqClassName() + " (+method)";
        if (op instanceof EditOp.DeleteMethod d) return d.fqClassName() + "#" + d.methodName() + " (delete)";
        if (op instanceof EditOp.AddImport i) return i.filePath() + " (+import " + i.importDecl() + ")";
        if (op instanceof EditOp.CreateFile c) return c.relPath() + " (new)";
        return op.toString();
    }
}
