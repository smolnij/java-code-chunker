package com.smolnij.chunker.apply.verify;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Input to a {@link CompilationVerifier}.
 *
 * <p>{@link #overlay} is a {@code path → proposed-content} map of edits to
 * compose with the worktree at {@link #repoRoot} before compiling — typically
 * the result of {@code PatchApplier.previewEdits(plan).getStagedContents()}.
 * An empty overlay means "verify the worktree as-is on disk".
 *
 * <p>{@link #focusedFiles} is an optional hint to the backend that it can
 * limit semantic analysis to those files (and their direct dependents) rather
 * than the entire project. Usually equal to {@code overlay.keySet()}. Empty
 * means "compile everything reachable".
 */
public record CompilationRequest(
        Path repoRoot,
        Map<Path, String> overlay,
        Mode mode,
        Set<Path> focusedFiles,
        int maxErrors
) {
    public enum Mode {
        /** In-process javac only; never falls through to mvn. */
        FAST,
        /** Shelled {@code mvn -q -o compile}; authoritative but slow. */
        FULL,
        /** Backend picks: javac unless overlay touches pom.xml or fast path is unavailable. */
        AUTO
    }

    public boolean touchesPom() {
        if (overlay == null) return false;
        for (Path p : overlay.keySet()) {
            if ("pom.xml".equals(p.getFileName().toString())) return true;
        }
        return false;
    }
}
