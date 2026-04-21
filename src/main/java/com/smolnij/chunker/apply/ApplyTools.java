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

    /** Accumulated ops until {@link #commitPlan(String)} flushes them. */
    private final List<EditOp> draftOps = new ArrayList<>();

    /** Last ApplyResult; exposed so the loop can read applied files after the agent returns. */
    private ApplyResult lastResult;

    public ApplyTools(Path repoRoot, Neo4jGraphReader graphReader,
                      boolean dryRun, boolean backup) {
        this.repoRoot = repoRoot;
        this.graphReader = graphReader;
        this.dryRun = dryRun;
        this.backup = backup;
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
        draftOps.add(new EditOp.ReplaceMethod(fqClassName, methodName, originalSignature, newCode));
        return "staged replace_method " + fqClassName + "#" + methodName
            + " (ops so far: " + draftOps.size() + ")";
    }

    @Tool("""
        Stage an add-method edit: append a new method declaration to the named class.
        Pass the FQN of the target class and the full method declaration as Java source.
        """)
    public String stageAddMethod(@P("Fully-qualified class name") String fqClassName,
                                 @P("Full new method declaration (Java source)") String newCode) {
        draftOps.add(new EditOp.AddMethod(fqClassName, newCode));
        return "staged add_method on " + fqClassName
            + " (ops so far: " + draftOps.size() + ")";
    }

    @Tool("""
        Stage a delete-method edit: remove a method from the named class.
        Pass the FQN, method name, and original signature to disambiguate overloads.
        """)
    public String stageDeleteMethod(@P("Fully-qualified class name") String fqClassName,
                                    @P("Method name") String methodName,
                                    @P("Original method signature") String originalSignature) {
        draftOps.add(new EditOp.DeleteMethod(fqClassName, methodName, originalSignature));
        return "staged delete_method " + fqClassName + "#" + methodName
            + " (ops so far: " + draftOps.size() + ")";
    }

    @Tool("""
        Stage an add-import edit on a specific file.
        Pass the repo-relative path of the Java file and the import declaration
        (e.g. 'import java.util.concurrent.CompletableFuture;').
        """)
    public String stageAddImport(@P("Repo-relative file path") String filePath,
                                 @P("Import declaration (full line)") String importDecl) {
        draftOps.add(new EditOp.AddImport(filePath, importDecl));
        return "staged add_import to " + filePath
            + " (ops so far: " + draftOps.size() + ")";
    }

    @Tool("""
        Stage a create-file edit: write a brand-new Java file at a repo-relative path.
        Use this only for genuinely new classes — prefer replace_method / add_method for
        edits to existing files.
        """)
    public String stageCreateFile(@P("Repo-relative path for the new file") String relPath,
                                  @P("Full file content") String content) {
        draftOps.add(new EditOp.CreateFile(relPath, content));
        return "staged create_file " + relPath
            + " (ops so far: " + draftOps.size() + ")";
    }

    // ═══════════════════════════════════════════════════════════════
    // Commit tool
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Commit all staged edits as a single atomic PatchApplier run.
        Pass a short rationale describing why these edits were chosen.
        Returns a human-readable report of the outcome (success/failure, files changed).
        After this call, the draft ops are cleared.
        """)
    public String commitPlan(@P("One-paragraph rationale for the change set") String rationale) {
        if (draftOps.isEmpty()) {
            return "commitPlan: no staged edits to apply.";
        }

        PatchPlan plan = new PatchPlan(
            List.copyOf(draftOps),
            rationale == null ? "" : rationale,
            "agent");

        PatchApplier applier = new PatchApplier(repoRoot, graphReader, dryRun, backup);
        lastResult = applier.apply(plan);
        draftOps.clear();
        return lastResult.toReport();
    }

    @Tool("""
        Discard all currently staged edits without applying them.
        Use this to reset the draft plan when you realize the approach was wrong.
        """)
    public String discardDraft() {
        int n = draftOps.size();
        draftOps.clear();
        return "discarded " + n + " staged op(s).";
    }
}
