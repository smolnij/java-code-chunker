package com.smolnij.chunker.apply.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the compile classpath for a target Maven project, caching the
 * result under {@code <repoRoot>/target/.chunker-classpath} so subsequent
 * verifier runs don't pay the {@code mvn dependency:build-classpath} cost.
 *
 * <p>Cache invalidates when {@code pom.xml}'s mtime is newer than the cache
 * file. {@code <repoRoot>/target/classes} is always appended (when it exists)
 * so already-compiled prerequisite classes are visible to javac even if the
 * Maven dependency closure was resolved before they were built.
 *
 * <p>Supports a single-module Maven layout. Multi-module projects fall through
 * to {@code MavenVerifier} (callers are expected to detect this and skip the
 * fast path).
 */
public final class ClasspathResolver {

    private final Path repoRoot;
    private final Path cacheFile;

    public ClasspathResolver(Path repoRoot, Path cacheDirOverride) {
        this.repoRoot = repoRoot;
        Path cacheDir = (cacheDirOverride != null && !cacheDirOverride.toString().isBlank())
            ? cacheDirOverride
            : repoRoot.resolve("target");
        this.cacheFile = cacheDir.resolve(".chunker-classpath");
    }

    /**
     * Return the classpath string (entries separated by {@link java.io.File#pathSeparator}).
     * On a stale cache, attempts to rebuild via {@code mvn -q -o dependency:build-classpath};
     * if mvn fails, returns the stale value (empty string if there is none).
     *
     * @throws IOException if I/O around the cache fails (mvn process failures are swallowed)
     */
    public String resolve() throws IOException {
        Path pom = repoRoot.resolve("pom.xml");
        if (!Files.exists(pom)) {
            // Nothing we can resolve; classpath defaults to JDK only.
            return appendTargetClasses("");
        }

        if (cacheValid(pom)) {
            return appendTargetClasses(Files.readString(cacheFile));
        }

        // Rebuild cache; if rebuild fails, fall back to whatever was cached.
        boolean rebuilt = rebuildCache(/*offline*/ true);
        if (!rebuilt) rebuilt = rebuildCache(/*offline*/ false);

        // Race guard: if pom changed during rebuild, do one more pass.
        if (rebuilt && !cacheValid(pom)) {
            rebuildCache(/*offline*/ false);
        }

        if (Files.exists(cacheFile)) {
            return appendTargetClasses(Files.readString(cacheFile));
        }
        return appendTargetClasses("");
    }

    private boolean cacheValid(Path pom) throws IOException {
        if (!Files.exists(cacheFile)) return false;
        long pomMtime = Files.getLastModifiedTime(pom).toMillis();
        long cacheMtime = Files.getLastModifiedTime(cacheFile).toMillis();
        return cacheMtime >= pomMtime;
    }

    private boolean rebuildCache(boolean offline) {
        try {
            Files.createDirectories(cacheFile.getParent());
            ProcessBuilder pb = new ProcessBuilder(
                "mvn", "-q",
                offline ? "-o" : "-U",
                "dependency:build-classpath",
                "-DincludeScope=compile",
                "-Dmdep.outputFile=" + cacheFile.toAbsolutePath());
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output so the process doesn't block on a full pipe.
            try (var is = p.getInputStream()) { is.readAllBytes(); }
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.exists(cacheFile);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String appendTargetClasses(String base) {
        Path classes = repoRoot.resolve("target").resolve("classes");
        if (!Files.exists(classes)) return base;
        if (base == null || base.isBlank()) return classes.toString();
        return base + java.io.File.pathSeparator + classes;
    }
}
