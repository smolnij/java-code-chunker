package com.smolnij.chunker.apply;

import com.smolnij.chunker.apply.verify.CompilationRequest;
import com.smolnij.chunker.apply.verify.CompilationResult;
import com.smolnij.chunker.apply.verify.CompilationVerifier;
import com.smolnij.chunker.apply.verify.DiagnosticFormatter;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j tool wrapper around {@link PatchApplier}. Exposes a single
 * {@code applyPatch} tool so agentic models can commit (or dry-run) edits
 * as a terminal step in the refactoring loop.
 *
 * <p>The tool accepts one op at a time to keep the function schema flat;
 * the agent can invoke it multiple times before a final call to
 * {@link #commitPlan(String)}. Each call appends to an internal draft plan
 * that {@link #commitPlan(String)} then hands to {@link PatchApplier}.
 */
public class ApplyTools {

    private final Path repoRoot;
    private final Neo4jGraphReader graphReader;
    private final boolean dryRun;
    private final boolean backup;
    private final SafetyGate safetyGate;
    /** Optional. When set, runs a Neo4j delta re-index after a successful commit so subsequent retrievals see fresh code. */
    private final GraphReindexer reindexer;
    /** Optional. When set, {@link #commitPlan} runs the verifier between the safety gate and the disk write; compile failures keep the draft staged. */
    private final CompilationVerifier compileVerifier;
    private final int verifyMaxErrors;
    private final CompilationRequest.Mode verifyMode;

    /** Accumulated ops until {@link #commitPlan(String)} flushes them. */
    private final List<EditOp> draftOps = new ArrayList<>();

    /** Last ApplyResult; exposed so the loop can read applied files after the agent returns. */
    private ApplyResult lastResult;

    /** Per-instance counter so apply-tool calls are visible in the worklog_eval trace. */
    private int applyCallCount = 0;

    /**
     * Counter of ops successfully committed since the last
     * {@link #resetIterationStats()} call. Used by the safeloop quick-analyzer
     * to refuse a SAFE verdict on a no-op iteration that should have produced edits.
     */
    private int opsCommittedThisIteration = 0;

    /**
     * Tracks the number of {@code commitPlan} calls in the current iteration,
     * regardless of whether they staged anything. A non-zero call count with
     * zero committed ops indicates the agent invoked commit on an empty draft.
     */
    private int commitAttemptsThisIteration = 0;

    /**
     * Counter for consecutive calls to commitPlan when draftOps is empty.
     * Reset when a successful commit, discard, or any stage* call happens.
     * Used to detect and break out of tool-call loops early.
     */
    private int consecutiveEmptyCommitAttempts = 0;

    /**
     * Threshold at which {@link #commitPlan} treats repeated empty calls as a
     * livelocked agent and aborts the iteration by throwing. Counts 1..(N-1)
     * still return escalating error strings so a healthy agent can recover; the
     * Nth empty call sets {@link #aborted} and raises {@link RuntimeException}.
     * Catches the failure mode seen in worklog_eval where the agent emitted
     * 200 consecutive {@code commitPlan(0 staged op(s))} calls over ~4.5 hours.
     */
    public static final int MAX_CONSECUTIVE_EMPTY_COMMITS = 4;

    /**
     * Sticky flag set once the no-op loop guard has fired. Every subsequent
     * tool entry throws so LangChain4j cannot keep dispatching to a doomed
     * iteration. Cleared by {@link #resetIterationStats()}.
     */
    private boolean aborted = false;

    private void traceCall(String toolName, String args) {
        if (aborted) {
            throw new RuntimeException("ApplyTools aborted (no-op loop guard tripped); refusing "
                + toolName);
        }
        applyCallCount++;
        System.out.println("  🔨 Apply tool #" + applyCallCount + ": " + toolName + "(" + args + ")");
    }

    private String traceReturn(String result) {
        int chars = result == null ? 0 : result.length();
        String status;
        if (result == null || result.isEmpty()) {
            status = "[empty]";
        } else if (result.startsWith("UNSAFE")) {
            status = "[unsafe]";
        } else if (result.startsWith("commitPlan: no staged edits")) {
            status = "[noop]";
        } else if (result.contains("✗") || result.contains("failed")) {
            status = "[failed]";
            System.out.println(result);
        } else {
            status = "[ok]";
        }
        System.out.println("    └─ " + status + " (" + chars + " chars)");
        return result;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "null";
        String collapsed = s.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= max ? collapsed : collapsed.substring(0, max) + "…";
    }

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup) {
        this(repoRoot, graphReader, dryRun, backup, SafetyGate.ALLOW_ALL, null, null,
             CompilationRequest.Mode.AUTO, DiagnosticFormatter.DEFAULT_MAX_ERRORS);
    }

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup, SafetyGate safetyGate) {
        this(repoRoot, graphReader, dryRun, backup, safetyGate, null, null,
             CompilationRequest.Mode.AUTO, DiagnosticFormatter.DEFAULT_MAX_ERRORS);
    }

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup, SafetyGate safetyGate,
                      GraphReindexer reindexer) {
        this(repoRoot, graphReader, dryRun, backup, safetyGate, reindexer, null,
             CompilationRequest.Mode.AUTO, DiagnosticFormatter.DEFAULT_MAX_ERRORS);
    }

    /**
     * Full constructor including the optional compile verifier (auto-gate inside
     * {@link #commitPlan}).
     */
    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup, SafetyGate safetyGate,
                      GraphReindexer reindexer,
                      CompilationVerifier compileVerifier,
                      CompilationRequest.Mode verifyMode,
                      int verifyMaxErrors) {
        this.repoRoot = repoRoot;
        this.graphReader = graphReader;
        this.dryRun = dryRun;
        this.backup = backup;
        this.safetyGate = safetyGate == null ? SafetyGate.ALLOW_ALL : safetyGate;
        this.reindexer = reindexer;
        this.compileVerifier = compileVerifier;
        this.verifyMode = verifyMode == null ? CompilationRequest.Mode.AUTO : verifyMode;
        this.verifyMaxErrors = verifyMaxErrors > 0 ? verifyMaxErrors : DiagnosticFormatter.DEFAULT_MAX_ERRORS;
    }

    public ApplyResult getLastResult() {
        return lastResult;
    }

    public List<EditOp> getDraftOps() {
        return List.copyOf(draftOps);
    }

    public Path getRepoRoot() {
        return repoRoot;
    }

    /**
     * Run the currently-staged draft through {@link PatchApplier#previewEdits} and
     * return the resulting in-memory overlay (path → proposed content) without
     * touching disk. Useful for compile-time verification before {@link #commitPlan}.
     *
     * <p>Returns an {@link ApplyResult} with {@code dryRun=true}; on staging failure
     * {@code success=false} and the errors mirror what {@link #commitPlan} would
     * have surfaced.
     */
    public ApplyResult previewDraft() {
        PatchPlan plan = new PatchPlan(List.copyOf(draftOps), "preview", "verify");
        return new PatchApplier(repoRoot, graphReader, true, false).previewEdits(plan);
    }

    /**
     * Reset per-iteration counters. Called by {@code SafeRefactorLoop} at the start
     * of each iteration so {@link #getOpsCommittedThisIteration()} reflects only
     * what happened during that iteration's tool-calling phase.
     */
    public void resetIterationStats() {
        opsCommittedThisIteration = 0;
        commitAttemptsThisIteration = 0;
        consecutiveEmptyCommitAttempts = 0;
        aborted = false;
    }

    /** True once the no-op loop guard has fired and the iteration must end. */
    public boolean isAborted() {
        return aborted;
    }

    /** Number of ops successfully committed since the last {@link #resetIterationStats()}. */
    public int getOpsCommittedThisIteration() {
        return opsCommittedThisIteration;
    }

    /** Number of {@code commitPlan} calls (any outcome) since the last {@link #resetIterationStats()}. */
    public int getCommitAttemptsThisIteration() {
        return commitAttemptsThisIteration;
    }

    /** Consecutive calls to commitPlan when draftOps was empty. Used to detect tool-call loops. */
    public int getConsecutiveEmptyCommitAttempts() {
        return consecutiveEmptyCommitAttempts;
    }

    // ═══════════════════════════════════════════════════════════════
    // Op-staging tools
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Stage a replace-method edit: the entire body of the named method is replaced by new_code.
        Pass the fully-qualified class name (e.g. com.example.UserService), the method name,
        the ORIGINAL signature of the method (so overloads can be disambiguated), and the full
        new method declaration as Java source (including modifiers, return type, name, params).
        The edit is buffered; call commitPlan to actually write changes.
        """)
    public String stageReplaceMethod(@P("Fully-qualified class name") String fqClassName,
                                     @P("Method name") String methodName,
                                     @P("Original method signature") String originalSignature,
                                     @P("Full new method declaration (Java source)") String newCode) {
        traceCall("stageReplaceMethod", fqClassName + "#" + methodName);
        consecutiveEmptyCommitAttempts = 0;
        draftOps.add(new EditOp.ReplaceMethod(fqClassName, methodName, originalSignature, newCode));
        return traceReturn("staged replace_method " + fqClassName + "#" + methodName
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage an add-method edit: append a new method declaration to the named class.
        Pass the FQN of the target class and the full method declaration as Java source.
        """)
    public String stageAddMethod(@P("Fully-qualified class name") String fqClassName,
                                 @P("Full new method declaration (Java source)") String newCode) {
        traceCall("stageAddMethod", fqClassName);
        draftOps.add(new EditOp.AddMethod(fqClassName, newCode));
        return traceReturn("staged add_method on " + fqClassName
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a delete-method edit: remove a method from the named class.
        Pass the FQN, method name, and original signature to disambiguate overloads.
        """)
    public String stageDeleteMethod(@P("Fully-qualified class name") String fqClassName,
                                    @P("Method name") String methodName,
                                    @P("Original method signature") String originalSignature) {
        traceCall("stageDeleteMethod", fqClassName + "#" + methodName);
        draftOps.add(new EditOp.DeleteMethod(fqClassName, methodName, originalSignature));
        return traceReturn("staged delete_method " + fqClassName + "#" + methodName
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage an add-import edit on a specific file.
        Pass the repo-relative path of the Java file and the import declaration
        (e.g. 'import java.util.concurrent.CompletableFuture;').
        """)
    public String stageAddImport(@P("Repo-relative file path") String filePath,
                                 @P("Import declaration (full line)") String importDecl) {
        traceCall("stageAddImport", filePath + " (+ " + shorten(importDecl, 60) + ")");
        draftOps.add(new EditOp.AddImport(filePath, importDecl));
        return traceReturn("staged add_import to " + filePath
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a create-file edit: write a brand-new Java file at a repo-relative path.
        Use this only for genuinely new classes — prefer replace_method / add_method for
        edits to existing files.
        """)
    public String stageCreateFile(@P("Repo-relative path for the new file") String relPath,
                                  @P("Full file content") String content) {
        traceCall("stageCreateFile", relPath + " (" + (content == null ? 0 : content.length()) + " chars)");
        draftOps.add(new EditOp.CreateFile(relPath, content));
        return traceReturn("staged create_file " + relPath
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a Maven dependency addition to the project's pom.xml.
        Pass groupId and artifactId (both required). Leave version empty when a BOM or
        dependencyManagement section supplies the version; otherwise pass an explicit version.
        Leave scope empty for the default compile scope, or pass test/provided/runtime as needed.
        Idempotent: if a dependency with the same groupId+artifactId already exists the op is a no-op.
        Use this when introducing a new library import (e.g. Jackson, Guava) — staging the dependency
        and then add_import / replace_method commits the change atomically.
        """)
    public String stageAddMavenDependency(@P("Maven groupId, e.g. com.fasterxml.jackson.core") String groupId,
                                          @P("Maven artifactId, e.g. jackson-databind") String artifactId,
                                          @P("Version string; pass empty when supplied by a BOM") String version,
                                          @P("Maven scope; pass empty for default compile scope") String scope) {
        traceCall("stageAddMavenDependency", groupId + ":" + artifactId
            + (version == null || version.isBlank() ? "" : ":" + version));
        draftOps.add(new EditOp.AddMavenDependency(
            groupId == null ? "" : groupId,
            artifactId == null ? "" : artifactId,
            version == null ? "" : version,
            scope == null ? "" : scope));
        return traceReturn("staged add_maven_dependency " + groupId + ":" + artifactId
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a method-rename edit. PREFER THIS over stageReplaceMethod when the change is
        purely a rename — the post-apply Neo4j re-indexer uses this op as an authoritative
        signal to update CALLS edges and refresh source text in unchanged caller files,
        which keeps retrievals accurate without a heuristic re-detection pass.
        Pass the FQN of the owning class, the current method name, the new method name,
        and the parameter signature (e.g. "(java.lang.String, int)") for overload disambiguation.
        Pass an empty paramSignature to match a method by name when no overload exists.
        """)
    public String stageRenameMethod(@P("Fully-qualified class name") String fqClassName,
                                    @P("Current method name") String oldMethodName,
                                    @P("Replacement method name") String newMethodName,
                                    @P("Parameter signature for overload disambiguation; empty if no overload") String paramSignature) {
        traceCall("stageRenameMethod", fqClassName + "#" + oldMethodName + " → " + newMethodName);
        draftOps.add(new EditOp.RenameMethod(fqClassName, oldMethodName, newMethodName,
            paramSignature == null ? "" : paramSignature));
        return traceReturn("staged rename_method " + fqClassName + "#" + oldMethodName
            + " → " + newMethodName + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a class-rename edit (within the same package). PREFER THIS over a delete+create
        sequence when the change is purely a rename — the post-apply Neo4j re-indexer uses
        this op to repair USES_TYPE / IMPORTS / EXTENDS / IMPLEMENTS edges from unchanged files
        and to refresh their source text. Cross-package moves are not supported by this op.
        """)
    public String stageRenameClass(@P("Current fully-qualified class name") String oldFqName,
                                   @P("Replacement fully-qualified class name (same package)") String newFqName) {
        traceCall("stageRenameClass", oldFqName + " → " + newFqName);
        draftOps.add(new EditOp.RenameClass(oldFqName, newFqName));
        return traceReturn("staged rename_class " + oldFqName + " → " + newFqName
            + " (ops so far: " + draftOps.size() + ")");
    }

    @Tool("""
        Stage a field-rename edit. PREFER THIS over delete+add when the change is purely a
        rename — the post-apply Neo4j re-indexer uses this op to repair READS_FIELD /
        WRITES_FIELD edges from unchanged files.
        """)
    public String stageRenameField(@P("Fully-qualified owning class name") String owningClassFqn,
                                   @P("Current field name") String oldFieldName,
                                   @P("Replacement field name") String newFieldName) {
        traceCall("stageRenameField", owningClassFqn + "." + oldFieldName + " → " + newFieldName);
        draftOps.add(new EditOp.RenameField(owningClassFqn, oldFieldName, newFieldName));
        return traceReturn("staged rename_field " + owningClassFqn + "." + oldFieldName
            + " → " + newFieldName + " (ops so far: " + draftOps.size() + ")");
    }

    // ═══════════════════════════════════════════════════════════════
    // Commit tool
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Commit all staged edits as a single atomic PatchApplier run.
        Pass a short rationale describing why these edits were chosen.
        Returns a human-readable report of the outcome (success/failure, files changed).
        The safety analyzer runs FIRST; if it returns UNSAFE the edits are NOT written
        and draft ops stay buffered so you can revise them or call discardDraft.
        On SAFE verdict the draft ops are cleared after apply.
        """)
    public String commitPlan(@P("One-paragraph rationale for the change set") String rationale) {
        traceCall("commitPlan", draftOps.size() + " staged op(s)");
        commitAttemptsThisIteration++;
        if (draftOps.isEmpty()) {
            consecutiveEmptyCommitAttempts++;
            if (consecutiveEmptyCommitAttempts >= MAX_CONSECUTIVE_EMPTY_COMMITS) {
                aborted = true;
                String abortMsg = "ABORT: commitPlan called " + consecutiveEmptyCommitAttempts
                    + " consecutive times with 0 staged ops — agent is in a no-op loop.";
                traceReturn(abortMsg);
                System.out.println("  ‼ " + abortMsg + " Aborting iteration.");
                throw new RuntimeException(abortMsg);
            }
            String errorMsg;
            if (consecutiveEmptyCommitAttempts == 1) {
                errorMsg = "commitPlan: no staged edits to apply.";
            } else if (consecutiveEmptyCommitAttempts == 2) {
                errorMsg = "STOP: commitPlan called twice with 0 staged ops. Stage an edit first "
                    + "(stageReplaceMethod / stageAddMethod) or call discardDraft if task is complete.";
            } else {
                errorMsg = "FINAL WARNING: commitPlan called " + consecutiveEmptyCommitAttempts
                    + " times with 0 staged ops. The next empty call will abort this iteration. "
                    + "Use discardDraft to signal completion, or stage actual edits.";
            }
            return traceReturn(errorMsg);
        }
        consecutiveEmptyCommitAttempts = 0;

        int opsBeingCommitted = draftOps.size();
        PatchPlan plan = new PatchPlan(
            List.copyOf(draftOps),
            rationale == null ? "" : rationale,
            "agent");

        SafetyGate.Verdict verdict = safetyGate.evaluate(plan);
        if (!verdict.safe()) {
            return traceReturn("UNSAFE (confidence=" + verdict.confidence() + "): " + verdict.reason()
                + "\nDraft kept (" + draftOps.size() + " op(s)). Revise and retry, or call discardDraft.");
        }

        PatchApplier applier = new PatchApplier(repoRoot, graphReader, dryRun, backup);

        // Compile auto-gate: run before mutating disk so a compile-failed plan
        // leaves draftOps intact. Only active when a verifier was wired in.
        if (compileVerifier != null) {
            ApplyResult preview = applier.previewEdits(plan);
            if (!preview.isSuccess()) {
                // Staging itself failed (parse error, missing file, etc.) — surface
                // the same diagnostics the eventual apply() would have produced and
                // keep draftOps so the agent can fix.
                String errs = String.join("; ", preview.getErrors());
                return traceReturn("STAGING FAIL: " + errs
                    + "\nDraft kept (" + draftOps.size() + " op(s)). Revise and retry, or call discardDraft.");
            }
            Map<Path, String> overlay = preview.getStagedContents() == null
                ? Map.of() : new LinkedHashMap<>(preview.getStagedContents());
            CompilationRequest req = new CompilationRequest(
                repoRoot, overlay, verifyMode, overlay.keySet(), verifyMaxErrors);
            CompilationResult cr = compileVerifier.verify(req);
            if (!cr.success()) {
                String diagnostics = DiagnosticFormatter.format(cr, repoRoot, verifyMaxErrors);
                return traceReturn("COMPILE FAIL (backend=" + cr.backend() + ", "
                    + cr.errorCount() + " error(s)):\n" + diagnostics
                    + "\nDraft kept (" + draftOps.size() + " op(s)). Fix and retry, or call discardDraft.");
            }
        }

        lastResult = applier.apply(plan);
        draftOps.clear();
        if (lastResult.isSuccess()) {
            opsCommittedThisIteration += opsBeingCommitted;
        }

        // Refresh Neo4j so subsequent retrievals see the just-applied code.
        // Skipped on dry-run (no files were actually written) and on apply failure.
        String reindexLine = "";
        if (reindexer != null && lastResult.isSuccess() && !dryRun) {
            try {
                GraphReindexer.ReindexResult rr = reindexer.reindex(
                    lastResult.getChangedFiles(),
                    lastResult.getCommittedOps());
                reindexLine = "\n" + rr.toReport();
            } catch (Exception e) {
                reindexLine = "\nReindex: ✗ " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }

        return traceReturn("SAFE (confidence=" + verdict.confidence() + "): " + verdict.reason()
            + "\n" + lastResult.toReport() + reindexLine);
    }

    @Tool("""
        Discard all currently staged edits without applying them.
        Use this to reset the draft plan when you realize the approach was wrong.
        """)
    public String discardDraft() {
        traceCall("discardDraft", draftOps.size() + " staged op(s)");
        int n = draftOps.size();
        draftOps.clear();
        consecutiveEmptyCommitAttempts = 0;
        return traceReturn("discarded " + n + " staged op(s).");
    }
}
