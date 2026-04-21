package com.smolnij.chunker.refactor.verify;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal test verifier. Runs `mvn -q -o test` in the repository and
 * captures failing test output. The module to test may be provided via
 * the system property `safeLoop.testModule` or env `SAFELOOP_TEST_MODULE`.
 */
public class TestVerifier {

    public Optional<String> runTests() throws IOException, InterruptedException {
        Path repoRoot = discoverRepoRoot();
        String module = System.getProperty("safeLoop.testModule");
        if (module == null || module.isBlank()) module = System.getenv("SAFELOOP_TEST_MODULE");

        List<String> cmd = new ArrayList<>();
        cmd.add("mvn");
        cmd.add("-q");
        cmd.add("-o");
        if (module != null && !module.isBlank()) {
            cmd.add("-pl"); cmd.add(module);
        }
        cmd.add("test");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoRoot.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output;
        try (InputStream is = proc.getInputStream()) {
            output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int rc = proc.waitFor();
        if (rc == 0) return Optional.empty();

        // Try to extract failing test names heuristically
        List<String> fails = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("Failed tests:" ) || line.startsWith("There are test failures")) {
                fails.add(line);
            }
            // Surefire summary lines like "Tests run: 5, Failures: 1, Errors: 0, Skipped: 0"
            if (line.matches("Tests run:.*Failures:.*[1-9].*")) {
                fails.add(line);
            }
        }
        String summary = String.join("\n", fails);
        if (summary.isBlank()) summary = truncate(output, 2000);
        return Optional.of(summary);
    }

    private Path discoverRepoRoot() {
        String configured = System.getProperty("safeLoop.repoRoot");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath();
        String env = System.getenv("REPO_ROOT");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath();
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n...(" + s.length() + " chars)";
    }
}

