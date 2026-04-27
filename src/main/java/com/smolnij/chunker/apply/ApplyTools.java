package com.smolnij.chunker.apply;

import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Accumulated ops until {@link #commitPlan(String)} flushes them. */
    private final List<EditOp> draftOps = new ArrayList<>();

    /** Last ApplyResult; exposed so the loop can read applied files after the agent returns. */
    private ApplyResult lastResult;

    /** Per-instance counter so apply-tool calls are visible in the worklog trace. */
    private int applyCallCount = 0;

    private void traceCall(String toolName, String args) {
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
        this(repoRoot, graphReader, dryRun, backup, SafetyGate.ALLOW_ALL, null);
    }

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup, SafetyGate safetyGate) {
        this(repoRoot, graphReader, dryRun, backup, safetyGate, null);
    }

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup, SafetyGate safetyGate,
                      GraphReindexer reindexer) {
        this.repoRoot = repoRoot;
        this.graphReader = graphReader;
        this.dryRun = dryRun;
        this.backup = backup;
        this.safetyGate = safetyGate == null ? SafetyGate.ALLOW_ALL : safetyGate;
        this.reindexer = reindexer;
    }

    public ApplyResult getLastResult() {
        return lastResult;
    }

    public List<EditOp> getDraftOps() {
        return List.copyOf(draftOps);
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
        if (draftOps.isEmpty()) {
            return traceReturn("commitPlan: no staged edits to apply.");
        }

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
        lastResult = applier.apply(plan);
        draftOps.clear();

        // Refresh Neo4j so subsequent retrievals see the just-applied code.
        // Skipped on dry-run (no files were actually written) and on apply failure.
        String reindexLine = "";
        if (reindexer != null && lastResult.isSuccess() && !dryRun) {
            try {
                GraphReindexer.ReindexResult rr = reindexer.reindex(lastResult.getChangedFiles());
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
        return traceReturn("discarded " + n + " staged op(s).");
    }
}
