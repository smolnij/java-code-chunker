package com.smolnij.chunker.apply.verify;

import com.smolnij.chunker.apply.ApplyResult;
import com.smolnij.chunker.apply.ApplyTools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * LangChain4j tool wrapper exposing compile-time verification to the LLM.
 *
 * <p>Two surface-level tools are exposed:
 * <ul>
 *   <li>{@link #getCompilationErrors(String)} — compile the project with
 *       currently-staged {@link ApplyTools} edits overlaid in memory; return
 *       diagnostics or "OK". The natural pre-{@code commitPlan} sanity check.</li>
 *   <li>{@link #verifyJavaSnippet(String, String, String)} — compile a
 *       self-contained source string in project context, useful before staging
 *       the snippet via {@code stageReplaceMethod} / {@code stageCreateFile}.</li>
 * </ul>
 *
 * <p>The verifier instance is provided by the caller (typically a
 * {@link LayeredCompilationVerifier}). When unavailable on the current runtime,
 * verification returns a "verifier unavailable" string so the LLM can degrade
 * gracefully rather than retry forever.
 */
public class VerifyTools {

    private static final int DEFAULT_MAX_ERRORS = DiagnosticFormatter.DEFAULT_MAX_ERRORS;

    private final ApplyTools applyTools;
    private final CompilationVerifier verifier;
    private final int maxErrors;
    private int verifyCallCount = 0;

    public VerifyTools(ApplyTools applyTools, CompilationVerifier verifier) {
        this(applyTools, verifier, DEFAULT_MAX_ERRORS);
    }

    public VerifyTools(ApplyTools applyTools, CompilationVerifier verifier, int maxErrors) {
        this.applyTools = applyTools;
        this.verifier = verifier;
        this.maxErrors = maxErrors;
    }

    @Tool("""
        Compile the project with all currently staged edits applied in-memory and return any
        compilation errors as `path:line:col: error: message` lines. Use this BEFORE commitPlan
        to verify your refactor compiles — catches missing imports, unhandled checked
        exceptions, undeclared types, and signature mismatches that the JavaParser-only check
        misses.

        Returns "OK: no errors" on success.

        mode:
          - "fast" (default): in-process javac; <2s; may miss multi-module / annotation-processor
            issues.
          - "full": shells `mvn -o compile`; ~10–30s; authoritative.
          - "auto": fast unless the staged edits touch pom.xml or the project is multi-module.

        This only verifies what is currently staged — call discardDraft to reset, or commitPlan
        to apply once verification passes.
        """)
    public String getCompilationErrors(@P("verification mode: fast | full | auto") String mode) {
        verifyCallCount++;
        traceCall("getCompilationErrors", "mode=" + mode + ", staged=" + applyTools.getDraftOps().size());

        ApplyResult preview = applyTools.previewDraft();
        if (!preview.isSuccess()) {
            // Staging itself failed (e.g. parse error) — report directly so the LLM
            // doesn't waste time on a bogus compile.
            String errs = String.join("; ", preview.getErrors());
            return traceReturn("STAGING FAIL: " + errs);
        }

        Map<Path, String> overlay = preview.getStagedContents() == null
            ? Map.of() : new LinkedHashMap<>(preview.getStagedContents());

        CompilationRequest req = new CompilationRequest(
            applyTools.getRepoRoot(),
            overlay,
            parseMode(mode),
            overlay.keySet(),
            maxErrors);

        CompilationResult result = verifier.verify(req);
        return traceReturn(DiagnosticFormatter.format(result, applyTools.getRepoRoot(), maxErrors));
    }

    @Tool("""
        Compile a self-contained Java source string in the context of the project (classpath +
        source path) and return diagnostics. Use this to sanity-check a snippet before staging
        it (stageReplaceMethod / stageCreateFile).

        Pass the full Java source including `package` and class declaration. fqn is optional;
        when supplied (e.g. "com.example.UserService") it overrides the package/class derived
        from the source for binary-name resolution.
        """)
    public String verifyJavaSnippet(@P("Full Java source") String code,
                                    @P("Optional fully-qualified class name") String fqn,
                                    @P("verification mode: fast | full | auto") String mode) {
        verifyCallCount++;
        int chars = code == null ? 0 : code.length();
        traceCall("verifyJavaSnippet", "fqn=" + fqn + ", mode=" + mode + ", " + chars + " chars");

        if (code == null || code.isBlank()) {
            return traceReturn("FAIL: empty snippet");
        }

        Path repoRoot = applyTools.getRepoRoot();
        Path syntheticPath = synthesisePath(repoRoot, code, fqn);
        Map<Path, String> overlay = Map.of(syntheticPath, code);

        CompilationRequest req = new CompilationRequest(
            repoRoot,
            overlay,
            parseMode(mode),
            Set.of(syntheticPath),
            maxErrors);

        CompilationResult result = verifier.verify(req);
        return traceReturn(DiagnosticFormatter.format(result, repoRoot, maxErrors));
    }

    public int getVerifyCallCount() { return verifyCallCount; }

    public void resetCallCount() { verifyCallCount = 0; }

    private static CompilationRequest.Mode parseMode(String mode) {
        if (mode == null) return CompilationRequest.Mode.AUTO;
        return switch (mode.trim().toLowerCase()) {
            case "fast" -> CompilationRequest.Mode.FAST;
            case "full" -> CompilationRequest.Mode.FULL;
            case "auto", "" -> CompilationRequest.Mode.AUTO;
            default -> CompilationRequest.Mode.AUTO;
        };
    }

    private static Path synthesisePath(Path repoRoot, String code, String fqn) {
        String pkg;
        String className;
        if (fqn != null && !fqn.isBlank() && fqn.contains(".")) {
            int last = fqn.lastIndexOf('.');
            pkg = fqn.substring(0, last);
            className = fqn.substring(last + 1);
        } else {
            pkg = extractPackage(code);
            className = extractClassName(code);
            if (className == null) className = "Snippet";
            if (pkg == null) pkg = "_chunker_snippet";
        }
        Path p = repoRoot.resolve("src").resolve("main").resolve("java");
        for (String seg : pkg.split("\\.")) p = p.resolve(seg);
        return p.resolve(className + ".java");
    }

    private static String extractPackage(String src) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", java.util.regex.Pattern.MULTILINE).matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private static String extractClassName(String src) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "(?:class|interface|enum|record)\\s+(\\w+)").matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private void traceCall(String tool, String args) {
        System.out.println("  🔍 Verify tool #" + verifyCallCount + ": " + tool + "(" + args + ")");
    }

    private String traceReturn(String result) {
        int chars = result == null ? 0 : result.length();
        String status;
        if (result == null || result.isEmpty()) status = "[empty]";
        else if (result.startsWith("OK")) status = "[ok]";
        else if (result.startsWith("FAIL") || result.startsWith("STAGING FAIL")) status = "[fail]";
        else status = "[warn]";
        System.out.println("    └─ " + status + " (" + chars + " chars)");
        return result;
    }
}
