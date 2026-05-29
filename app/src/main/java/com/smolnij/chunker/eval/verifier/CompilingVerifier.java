package com.smolnij.chunker.eval.verifier;

import com.smolnij.chunker.apply.verify.ClasspathResolver;
import com.smolnij.chunker.apply.verify.CompilationRequest;
import com.smolnij.chunker.apply.verify.CompilationResult;
import com.smolnij.chunker.apply.verify.CompilationVerifier;
import com.smolnij.chunker.apply.verify.JavacVerifier;
import com.smolnij.chunker.apply.verify.LayeredCompilationVerifier;
import com.smolnij.chunker.apply.verify.MavenVerifier;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Real verifier (roadmap N-1): compiles the post-edit worktree using the
 * shared {@link LayeredCompilationVerifier}. Uses {@link CompilationRequest.Mode#FULL}
 * by default so eval results are authoritative (Maven, not focused javac).
 *
 * <p>Repo root is resolved from {@code safeLoop.repoRoot} / {@code REPO_ROOT}
 * (the same convention as {@code refactor/verify/CompileVerifier}); test
 * verification is still stubbed pending a follow-up PR.
 */
public final class CompilingVerifier implements Verifier {

    private final CompilationVerifier verifier;
    private final CompilationRequest.Mode mode;

    public CompilingVerifier() {
        this(buildLayered(), CompilationRequest.Mode.FULL);
    }

    public CompilingVerifier(CompilationVerifier verifier, CompilationRequest.Mode mode) {
        this.verifier = verifier;
        this.mode = mode;
    }

    @Override
    public VerifierResult verifyCompile(Fixture fixture, RunResult result) {
        Path repoRoot = discoverRepoRoot();
        if (repoRoot == null) {
            return VerifierResult.notRun("repoRoot unresolved (set safeLoop.repoRoot or REPO_ROOT)");
        }
        try {
            CompilationRequest req = new CompilationRequest(
                repoRoot, Map.of(), mode, Set.of(), 25);
            CompilationResult cr = verifier.verify(req);
            if ("unavailable".equals(cr.backend())) {
                return VerifierResult.notRun("verifier unavailable: " + cr.note());
            }
            if (cr.success()) {
                return VerifierResult.pass("compiled cleanly via " + cr.backend()
                    + " in " + (cr.duration() == null ? "?" : cr.duration().toMillis()) + "ms");
            }
            String note = cr.errorCount() + " error(s) via " + cr.backend()
                + (cr.diagnostics().isEmpty() ? "" : ": " + cr.diagnostics().get(0).message());
            return VerifierResult.fail(note);
        } catch (Exception e) {
            return new VerifierResult(VerifierResult.Status.ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public VerifierResult verifyTests(Fixture fixture, RunResult result) {
        // Test execution is still pending; surface NOT_RUN distinctly from FAIL.
        return VerifierResult.notRun("test verification not yet implemented");
    }

    private static Path discoverRepoRoot() {
        String configured = System.getProperty("safeLoop.repoRoot");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath();
        String env = System.getenv("REPO_ROOT");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath();
        return null;
    }

    private static CompilationVerifier buildLayered() {
        Path repoRoot = discoverRepoRoot();
        ClasspathResolver resolver = new ClasspathResolver(
            repoRoot == null ? Paths.get(".").toAbsolutePath() : repoRoot, null);
        return new LayeredCompilationVerifier(new JavacVerifier(resolver), new MavenVerifier());
    }
}
