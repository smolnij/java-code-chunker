package com.smolnij.chunker.apply;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.StringReader;
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
        Map<Path, String> overlay = stageInMemory(plan, result);
        if (overlay == null) {
            return result.failure();
        }

        // ── Commit ──
        List<Path> written = new ArrayList<>();
        if (!dryRun) {
            for (Map.Entry<Path, String> e : overlay.entrySet()) {
                try {
                    Path target = e.getKey();
                    boolean targetExists = Files.exists(target);
                    if (backup && targetExists) {
                        Path bak = target.resolveSibling(target.getFileName() + ".bak");
                        Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (!targetExists && target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, e.getValue());
                    written.add(target);
                } catch (IOException ioe) {
                    result.error("write failed for " + e.getKey() + ": " + ioe.getMessage());
                    return result.failure();
                }
            }
        } else {
            written.addAll(overlay.keySet());
        }

        return result.changed(written).success();
    }

    /**
     * Stage a {@link PatchPlan} entirely in memory and return the resulting
     * {@code path → content} overlay <em>without writing to disk</em>.
     *
     * <p>Useful for compile-time verification: callers can compose this overlay
     * with the worktree-on-disk to feed a Java compiler before committing the
     * edits via {@link #apply(PatchPlan)}. The returned {@link ApplyResult} has
     * {@code dryRun=true} (nothing was written) and {@code success=true} when
     * staging succeeded; on failure, {@code success=false} and the errors
     * mirror what {@link #apply(PatchPlan)} would have reported.
     */
    public ApplyResult previewEdits(PatchPlan plan) {
        ApplyResult.Builder result = new ApplyResult.Builder().dryRun(true);
        // Empty plan = "verify the worktree as-is"; success with empty overlay.
        if (plan == null || plan.isEmpty()) {
            return result.staged(Map.of()).changed(new ArrayList<>()).success();
        }
        Map<Path, String> overlay = stageInMemory(plan, result);
        if (overlay == null) {
            return result.failure();
        }
        return result.changed(new ArrayList<>(overlay.keySet())).success();
    }

    /**
     * Run every op against in-memory CompilationUnit / text maps and return
     * the rendered overlay. Returns {@code null} on any failure (the builder
     * already carries the error context).
     */
    private Map<Path, String> stageInMemory(PatchPlan plan, ApplyResult.Builder result) {
        if (plan == null || plan.isEmpty()) {
            result.error("empty patch plan — nothing to apply");
            return null;
        }

        // Stage all edits in memory, keyed by absolute path. The same file
        // may be touched by multiple ops; we thread a single CompilationUnit
        // through all of them so rewrites compose. A parallel text-only map
        // holds non-Java files (pom.xml etc.) edited by ops like
        // {@link EditOp.AddMavenDependency} and net-new files staged by
        // {@link EditOp.CreateFile}; these don't go through JavaParser at
        // commit time.
        Map<Path, CompilationUnit> staged = new LinkedHashMap<>();
        Map<Path, String> stagedText = new LinkedHashMap<>();

        for (EditOp op : plan.ops()) {
            try {
                boolean ok = applyOp(op, staged, stagedText, result);
                if (!ok) {
                    result.error("aborting — op failed, no files written");
                    return null;
                }
                result.committed(op);
            } catch (Exception e) {
                logOpFailure(op, e);
                result.op(new ApplyResult.OpStatus(
                    opKind(op), describe(op), false, e.getClass().getSimpleName() + ": " + e.getMessage()));
                result.error("exception applying " + opKind(op) + ": " + e.getMessage());
                return null;
            }
        }

        // ── Post-edit parse check: every staged Java file must still parse ──
        Map<Path, String> overlay = new LinkedHashMap<>();
        for (Map.Entry<Path, CompilationUnit> e : staged.entrySet()) {
            String rendered = LexicalPreservingPrinter.print(e.getValue());
            overlay.put(e.getKey(), rendered);
            ParseResult<CompilationUnit> reparse = parser.parse(rendered);
            if (!reparse.isSuccessful()) {
                result.error("post-edit parse failed for " + e.getKey()
                    + ": " + reparse.getProblems());
                return null;
            }
        }

        // ── Post-edit XML well-formedness check for staged non-Java files ──
        for (Map.Entry<Path, String> e : stagedText.entrySet()) {
            String name = e.getKey().getFileName().toString().toLowerCase();
            if (name.endsWith(".xml") || name.equals("pom.xml")) {
                try {
                    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                    dbf.setNamespaceAware(true);
                    dbf.newDocumentBuilder().parse(new InputSource(new StringReader(e.getValue())));
                } catch (Exception xmlErr) {
                    result.error("post-edit XML parse failed for " + e.getKey()
                        + ": " + xmlErr.getMessage());
                    return null;
                }
            }
            overlay.put(e.getKey(), e.getValue());
        }

        result.staged(overlay);
        return overlay;
    }

    // ═══════════════════════════════════════════════════════════════
    // Per-op handlers
    // ═══════════════════════════════════════════════════════════════

    private boolean applyOp(EditOp op,
                            Map<Path, CompilationUnit> staged,
                            Map<Path, String> stagedText,
                            ApplyResult.Builder result) throws IOException {
        if (op instanceof EditOp.ReplaceMethod r) return applyReplaceMethod(r, staged, result);
        if (op instanceof EditOp.AddMethod a) return applyAddMethod(a, staged, result);
        if (op instanceof EditOp.DeleteMethod d) return applyDeleteMethod(d, staged, result);
        if (op instanceof EditOp.AddImport i) return applyAddImport(i, staged, result);
        if (op instanceof EditOp.CreateFile c) return applyCreateFile(c, stagedText, result);
        if (op instanceof EditOp.AddMavenDependency m) return applyAddMavenDependency(m, stagedText, result);
        if (op instanceof EditOp.RenameMethod r) return applyRenameMethod(r, staged, result);
        if (op instanceof EditOp.RenameClass r) return applyRenameClass(r, staged, result);
        if (op instanceof EditOp.RenameField r) return applyRenameField(r, staged, result);
        throw new IllegalStateException("unknown op type: " + op.getClass());
    }

    // ═══════════════════════════════════════════════════════════════
    // Rename handlers
    // ═══════════════════════════════════════════════════════════════

    private boolean applyRenameMethod(EditOp.RenameMethod op,
                                      Map<Path, CompilationUnit> staged,
                                      ApplyResult.Builder result) throws IOException {
        Path target = resolveClassFile(op.fqClassName());
        if (target == null) {
            result.op(new ApplyResult.OpStatus("rename_method", describe(op), false,
                "class not found in graph: " + op.fqClassName()));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        MethodDeclaration existing = findMethod(cu, op.fqClassName(), op.oldMethodName(), op.paramSignature());
        if (existing == null) {
            result.op(new ApplyResult.OpStatus("rename_method", describe(op), false,
                "method not found: " + op.oldMethodName() + " [signature=" + op.paramSignature() + "]"));
            return false;
        }
        String oldName = op.oldMethodName();
        String newName = op.newMethodName();
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            result.op(new ApplyResult.OpStatus("rename_method", describe(op), false,
                "oldMethodName and newMethodName are required"));
            return false;
        }

        // Rename the declaration.
        existing.setName(newName);

        // Rewrite intra-file call sites: foo()/this.foo()/SimpleClass.foo() that
        // most plausibly resolve to the renamed method. We don't rewrite calls
        // qualified by an unrelated receiver expression — those couldn't refer
        // to this method anyway.
        String classSimple = simpleNameOf(op.fqClassName());
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            if (!call.getNameAsString().equals(oldName)) continue;
            if (!isCallToOwningClass(call, classSimple)) continue;
            call.setName(newName);
        }
        for (MethodReferenceExpr mref : cu.findAll(MethodReferenceExpr.class)) {
            if (!mref.getIdentifier().equals(oldName)) continue;
            // Method references look like Receiver::name. If the receiver is the
            // owning class (or "this"), update.
            String scopeRepr = mref.getScope().toString();
            if (scopeRepr.equals(classSimple) || scopeRepr.equals("this")
                    || scopeRepr.equals(op.fqClassName())) {
                mref.setIdentifier(newName);
            }
        }

        result.op(new ApplyResult.OpStatus("rename_method", describe(op), true));
        return true;
    }

    private boolean applyRenameClass(EditOp.RenameClass op,
                                     Map<Path, CompilationUnit> staged,
                                     ApplyResult.Builder result) throws IOException {
        String oldFq = op.oldFqName();
        String newFq = op.newFqName();
        if (oldFq == null || oldFq.isBlank() || newFq == null || newFq.isBlank()) {
            result.op(new ApplyResult.OpStatus("rename_class", describe(op), false,
                "oldFqName and newFqName are required"));
            return false;
        }
        String oldPkg = packageOf(oldFq);
        String newPkg = packageOf(newFq);
        if (!oldPkg.equals(newPkg)) {
            result.op(new ApplyResult.OpStatus("rename_class", describe(op), false,
                "package change in rename_class is not supported in v2 (oldPkg=" + oldPkg + ", newPkg=" + newPkg + ")"));
            return false;
        }
        Path target = resolveClassFile(oldFq);
        if (target == null) {
            result.op(new ApplyResult.OpStatus("rename_class", describe(op), false,
                "class not found in graph: " + oldFq));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        TypeDeclaration<?> type = findType(cu, oldFq);
        if (type == null) {
            result.op(new ApplyResult.OpStatus("rename_class", describe(op), false,
                "type declaration not found: " + oldFq));
            return false;
        }
        String oldSimple = simpleNameOf(oldFq);
        String newSimple = simpleNameOf(newFq);

        type.setName(newSimple);

        // Rewrite intra-file references to the simple name. JavaParser's
        // SimpleName is shared by NameExpr, ClassOrInterfaceType, etc., so
        // walking SimpleName covers most reference sites.
        for (SimpleName sn : cu.findAll(SimpleName.class)) {
            if (sn.getIdentifier().equals(oldSimple)) {
                // Skip the type's own name node (already updated via setName).
                if (sn == type.getName()) continue;
                sn.setIdentifier(newSimple);
            }
        }
        result.op(new ApplyResult.OpStatus("rename_class", describe(op), true));
        return true;
    }

    private boolean applyRenameField(EditOp.RenameField op,
                                     Map<Path, CompilationUnit> staged,
                                     ApplyResult.Builder result) throws IOException {
        String oldName = op.oldFieldName();
        String newName = op.newFieldName();
        if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
            result.op(new ApplyResult.OpStatus("rename_field", describe(op), false,
                "oldFieldName and newFieldName are required"));
            return false;
        }
        Path target = resolveClassFile(op.owningClassFqn());
        if (target == null) {
            result.op(new ApplyResult.OpStatus("rename_field", describe(op), false,
                "class not found in graph: " + op.owningClassFqn()));
            return false;
        }
        CompilationUnit cu = cuFor(target, staged);
        TypeDeclaration<?> type = findType(cu, op.owningClassFqn());
        if (type == null) {
            result.op(new ApplyResult.OpStatus("rename_field", describe(op), false,
                "type declaration not found: " + op.owningClassFqn()));
            return false;
        }

        // Locate the VariableDeclarator carrying the field name.
        VariableDeclarator decl = null;
        FieldDeclaration owningField = null;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (!(member instanceof FieldDeclaration fd)) continue;
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(oldName)) {
                    decl = vd;
                    owningField = fd;
                    break;
                }
            }
            if (decl != null) break;
        }
        if (decl == null) {
            result.op(new ApplyResult.OpStatus("rename_field", describe(op), false,
                "field not found: " + oldName));
            return false;
        }
        decl.setName(newName);

        // Rewrite intra-file references plausibly pointing at this field:
        //   - bare NameExpr {oldName}, when no local variable shadows it (best effort: rewrite
        //     only inside the owning class)
        //   - FieldAccessExpr where field == oldName and scope is this/SimpleClass
        String classSimple = simpleNameOf(op.owningClassFqn());
        // Rewrite only within the owning class body to limit blast radius.
        for (NameExpr ne : type.findAll(NameExpr.class)) {
            if (ne.getNameAsString().equals(oldName)) {
                ne.setName(newName);
            }
        }
        for (FieldAccessExpr fa : cu.findAll(FieldAccessExpr.class)) {
            if (!fa.getNameAsString().equals(oldName)) continue;
            String scope = fa.getScope().toString();
            if (scope.equals("this") || scope.equals(classSimple) || scope.equals(op.owningClassFqn())) {
                fa.setName(newName);
            }
        }
        // Avoid unused-warning by silencing owningField when we don't need it.
        if (owningField == null) { /* unreachable */ }

        result.op(new ApplyResult.OpStatus("rename_field", describe(op), true));
        return true;
    }

    private static boolean isCallToOwningClass(MethodCallExpr call, String classSimple) {
        if (call.getScope().isEmpty()) return true;
        String scope = call.getScope().get().toString();
        return scope.equals("this") || scope.equals(classSimple);
    }

    private static String simpleNameOf(String fq) {
        if (fq == null) return "";
        int dot = fq.lastIndexOf('.');
        return dot < 0 ? fq : fq.substring(dot + 1);
    }

    private static String packageOf(String fq) {
        if (fq == null) return "";
        int dot = fq.lastIndexOf('.');
        return dot < 0 ? "" : fq.substring(0, dot);
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

    private boolean applyCreateFile(EditOp.CreateFile op,
                                    Map<Path, String> stagedText,
                                    ApplyResult.Builder result) {
        Path target = repoRoot.resolve(op.relPath()).normalize();
        if (!target.startsWith(repoRoot)) {
            result.op(new ApplyResult.OpStatus("create_file", describe(op), false,
                "target escapes repoRoot: " + target));
            return false;
        }
        if (Files.exists(target) || stagedText.containsKey(target)) {
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
        // Route the new file's content through the text overlay so the
        // post-edit pipeline (XML check + commit) handles it uniformly with
        // pom.xml-style edits. Disk writing is deferred to apply()'s commit
        // block; previewEdits() leaves disk untouched.
        stagedText.put(target, op.content());
        result.op(new ApplyResult.OpStatus("create_file", describe(op), true));
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Maven dependency handler
    // ═══════════════════════════════════════════════════════════════

    /**
     * Insert a {@code <dependency>} block into the project's {@code pom.xml}.
     *
     * <p>v1 scope: only touches {@code repoRoot/pom.xml} (single-module).
     * Idempotent — a matching groupId+artifactId existing dependency is a no-op.
     * Fails with a clear error when the file or its {@code <dependencies>}
     * section is missing; we don't auto-create the section in v1 so the LLM
     * has a chance to fix its plan.
     *
     * <p>The implementation does targeted text insertion rather than DOM
     * round-tripping so comments, ordering, and indentation are preserved
     * exactly. Well-formedness is checked at commit time in {@link #apply}.
     */
    private boolean applyAddMavenDependency(EditOp.AddMavenDependency op,
                                            Map<Path, String> stagedText,
                                            ApplyResult.Builder result) throws IOException {
        if (op.groupId() == null || op.groupId().isBlank()
                || op.artifactId() == null || op.artifactId().isBlank()) {
            result.op(new ApplyResult.OpStatus("add_maven_dependency", describe(op), false,
                "groupId and artifactId are required"));
            return false;
        }

        Path target = repoRoot.resolve("pom.xml").normalize();
        if (!Files.exists(target)) {
            result.op(new ApplyResult.OpStatus("add_maven_dependency", describe(op), false,
                "pom.xml not found at " + target));
            return false;
        }

        String current = stagedText.get(target);
        if (current == null) current = Files.readString(target);

        // Idempotency: skip if a <dependency> with this groupId+artifactId
        // already exists. Cheap pattern match — the well-formedness check at
        // commit time catches anything weirder.
        if (hasExistingDependency(current, op.groupId(), op.artifactId())) {
            result.op(new ApplyResult.OpStatus("add_maven_dependency", describe(op), true,
                "already present"));
            return true;
        }

        int closeIdx = current.lastIndexOf("</dependencies>");
        if (closeIdx < 0) {
            result.op(new ApplyResult.OpStatus("add_maven_dependency", describe(op), false,
                "no <dependencies> section found in pom.xml — add one before staging dependency edits"));
            return false;
        }

        String depIndent = inferDependencyIndent(current, closeIdx);
        String childIndent = depIndent + "    ";
        StringBuilder dep = new StringBuilder();
        dep.append(depIndent).append("<dependency>\n");
        dep.append(childIndent).append("<groupId>").append(op.groupId()).append("</groupId>\n");
        dep.append(childIndent).append("<artifactId>").append(op.artifactId()).append("</artifactId>\n");
        if (op.version() != null && !op.version().isBlank()) {
            dep.append(childIndent).append("<version>").append(op.version()).append("</version>\n");
        }
        if (op.scope() != null && !op.scope().isBlank()) {
            dep.append(childIndent).append("<scope>").append(op.scope()).append("</scope>\n");
        }
        dep.append(depIndent).append("</dependency>\n");

        // Insert before </dependencies>, preserving the indentation in front
        // of the closing tag (the leading whitespace on that line).
        int lineStart = current.lastIndexOf('\n', closeIdx - 1) + 1;
        String updated = current.substring(0, lineStart)
                + dep.toString()
                + current.substring(lineStart);

        stagedText.put(target, updated);
        result.op(new ApplyResult.OpStatus("add_maven_dependency", describe(op), true));
        return true;
    }

    /**
     * Detect whether a {@code <dependency>} entry with the given coordinates
     * already exists. We pattern-match on text since this runs before the
     * commit-time XML validation. Whitespace-tolerant within a block.
     */
    private static boolean hasExistingDependency(String pomText, String groupId, String artifactId) {
        // Find all <dependency>...</dependency> blocks and check coords.
        int from = 0;
        while (true) {
            int open = pomText.indexOf("<dependency>", from);
            if (open < 0) return false;
            int close = pomText.indexOf("</dependency>", open);
            if (close < 0) return false;
            String block = pomText.substring(open, close);
            if (containsTagValue(block, "groupId", groupId)
                    && containsTagValue(block, "artifactId", artifactId)) {
                return true;
            }
            from = close + "</dependency>".length();
        }
    }

    private static boolean containsTagValue(String block, String tag, String value) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int o = block.indexOf(open);
        if (o < 0) return false;
        int c = block.indexOf(close, o);
        if (c < 0) return false;
        return block.substring(o + open.length(), c).trim().equals(value);
    }

    /**
     * Infer the indentation used for {@code <dependency>} blocks by looking
     * at the line before {@code </dependencies>} or at an existing nested
     * {@code <dependency>}. Falls back to 8 spaces (the convention used in
     * this project's own pom.xml).
     */
    private static String inferDependencyIndent(String pomText, int closeIdx) {
        // Look backward for the most recent <dependency> opening tag and
        // take whatever whitespace precedes it on its line.
        int prevDep = pomText.lastIndexOf("<dependency>", closeIdx);
        if (prevDep > 0) {
            int lineStart = pomText.lastIndexOf('\n', prevDep - 1) + 1;
            String prefix = pomText.substring(lineStart, prevDep);
            if (prefix.chars().allMatch(Character::isWhitespace) && !prefix.isEmpty()) {
                return prefix;
            }
        }
        return "        ";
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

    /**
     * Parse a single method declaration. Uses JavaParser's dedicated
     * {@code parseMethodDeclaration} entry point rather than wrapping in a
     * synthetic {@code class _S { … }} and cloning the result — the latter
     * leaves the cloned subtree carrying token ranges relative to the
     * wrapper source (e.g. "public" at line 1 col 12 because the wrapper
     * prefix is 11 chars), which causes
     * {@link LexicalPreservingPrinter#setup} to fail with
     * "Token without node owning it" once the node is spliced into the
     * destination CU. {@code setup} is called here on the standalone node
     * so the splice site sees a properly initialised replacement.
     */
    private MethodDeclaration parseSingleMethod(String code) {
        if (code == null || code.isBlank()) return null;
        String trimmed = code.trim();
        try {
            ParseResult<MethodDeclaration> direct = parser.parseMethodDeclaration(trimmed);
            if (direct.isSuccessful() && direct.getResult().isPresent()) {
                MethodDeclaration md = direct.getResult().get();
                LexicalPreservingPrinter.setup(md);
                return md;
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
        if (op instanceof EditOp.AddMavenDependency) return "add_maven_dependency";
        if (op instanceof EditOp.RenameMethod) return "rename_method";
        if (op instanceof EditOp.RenameClass) return "rename_class";
        if (op instanceof EditOp.RenameField) return "rename_field";
        return "unknown";
    }

    private static String describe(EditOp op) {
        if (op instanceof EditOp.ReplaceMethod r) return r.fqClassName() + "#" + r.methodName();
        if (op instanceof EditOp.AddMethod a) return a.fqClassName() + " (+method)";
        if (op instanceof EditOp.DeleteMethod d) return d.fqClassName() + "#" + d.methodName() + " (delete)";
        if (op instanceof EditOp.AddImport i) return i.filePath() + " (+import " + i.importDecl() + ")";
        if (op instanceof EditOp.CreateFile c) return c.relPath() + " (new)";
        if (op instanceof EditOp.AddMavenDependency m) {
            String coord = m.groupId() + ":" + m.artifactId();
            if (m.version() != null && !m.version().isBlank()) coord += ":" + m.version();
            return "pom.xml (+dep " + coord + ")";
        }
        if (op instanceof EditOp.RenameMethod r) {
            return r.fqClassName() + "#" + r.oldMethodName() + " → " + r.newMethodName();
        }
        if (op instanceof EditOp.RenameClass r) return r.oldFqName() + " → " + r.newFqName();
        if (op instanceof EditOp.RenameField r) {
            return r.owningClassFqn() + "." + r.oldFieldName() + " → " + r.newFieldName();
        }
        return op.toString();
    }

    /**
     * Print diagnostic detail for a failing op: target, file, and first lines of the
     * offending code payload. The summary printed elsewhere only carries the exception
     * type and message, which is often insufficient to debug malformed snippets (e.g.
     * "Token without node owning it" when the LLM omitted the class context).
     */
    private void logOpFailure(EditOp op, Exception e) {
        System.err.println("  [patch-fail] " + opKind(op) + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
        System.err.println("  [patch-fail] target: " + describe(op));
        String payload = null;
        if (op instanceof EditOp.AddMethod a) payload = a.newCode();
        else if (op instanceof EditOp.ReplaceMethod r) payload = r.newCode();
        else if (op instanceof EditOp.CreateFile c) payload = c.content();
        else if (op instanceof EditOp.AddImport i) payload = i.importDecl();
        if (payload != null) {
            String[] lines = payload.split("\\R", 4);
            int show = Math.min(2, lines.length);
            for (int j = 0; j < show; j++) {
                System.err.println("  [patch-fail] payload[" + j + "]: " + lines[j]);
            }
            if (lines.length > show) {
                System.err.println("  [patch-fail] payload: (… " + (payload.length()) + " chars total)");
            }
        }
    }
}
