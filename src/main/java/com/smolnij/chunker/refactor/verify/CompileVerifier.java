package com.smolnij.chunker.refactor.verify;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal compile verifier: attempts to apply Java file(s) embedded in an
 * agent response to the repository worktree (in-place, but backing up
 * originals) and runs `mvn -q -o compile` to detect compile failures.
 *
 * Behaviour is conservative: files are only written when a code block
 * appears to contain a Java compilation unit (package + class). Backups
 * are restored after the run.
 */
public class CompileVerifier {

    /** Run compile verification. Returns empty when no compile errors; otherwise a string describing the errors. */
    public Optional<String> verifyWithMaven(String agentResponse) throws IOException, InterruptedException {
        if (agentResponse == null || agentResponse.isBlank()) return Optional.empty();

        Path repoRoot = discoverRepoRoot();
        List<FilePatch> patches = extractFilePatches(agentResponse, repoRoot);
        if (patches.isEmpty()) return Optional.empty();

        Map<Path, Path> backups = new HashMap<>();
        try {
            // Write patches and backup originals
            for (FilePatch p : patches) {
                Path target = p.targetPath;
                if (Files.exists(target)) {
                    Path bak = target.resolveSibling(target.getFileName().toString() + ".refactor.bak");
                    Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    backups.put(target, bak);
                } else {
                    // ensure parent dirs
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, p.content, java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            // Run mvn compile
            ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "-o", "compile");
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output;
            try (InputStream is = proc.getInputStream()) {
                output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            int rc = proc.waitFor();
            if (rc == 0) return Optional.empty();
            return Optional.of(output);
        } finally {
            // Restore backups
            for (Map.Entry<Path, Path> e : backups.entrySet()) {
                try {
                    Files.copy(e.getValue(), e.getKey(), StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(e.getValue());
                } catch (Exception ignored) { }
            }
        }
    }

    private Path discoverRepoRoot() {
        String configured = System.getProperty("safeLoop.repoRoot");
        if (configured != null && !configured.isBlank()) return Paths.get(configured).toAbsolutePath();
        String env = System.getenv("REPO_ROOT");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath();
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /** Simple representation of a file patch extracted from a reply. */
    private static class FilePatch { Path targetPath; String content; FilePatch(Path p, String c) { targetPath = p; content = c; } }

    /** Extract code blocks that look like full compilation units and map to file paths. */
    private List<FilePatch> extractFilePatches(String resp, Path repoRoot) {
        List<FilePatch> out = new ArrayList<>();
        List<String> blocks = extractCodeBlocks(resp);
        for (String block : blocks) {
            // If the block contains an explicit File: header, use it
            String fileLine = findFileLine(block);
            if (fileLine != null) {
                Path target = repoRoot.resolve(fileLine).normalize();
                out.add(new FilePatch(target, block));
                continue;
            }

            // Otherwise, try to infer from package + class name
            String pkg = extractPackageName(block);
            String cls = extractClassName(block);
            if (cls != null) {
                Path rel = pkg == null || pkg.isBlank()
                        ? Paths.get("src/main/java", cls + ".java")
                        : Paths.get("src/main/java", pkg.replace('.', '/'), cls + ".java");
                Path target = repoRoot.resolve(rel).normalize();
                out.add(new FilePatch(target, block));
            }
        }
        return out;
    }

    private String findFileLine(String block) {
        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.startsWith("File:") || line.startsWith("file:")) {
                String v = line.substring(line.indexOf(':') + 1).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private String extractPackageName(String block) {
        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.startsWith("package ")) {
                String t = line.substring("package ".length()).trim();
                if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
                return t;
            }
        }
        return null;
    }

    private String extractClassName(String block) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:public\s+)?(?:class|interface|enum)\s+(\\w+)");
        java.util.regex.Matcher m = p.matcher(block);
        if (m.find()) return m.group(1);
        return null;
    }

    private List<String> extractCodeBlocks(String response) {
        List<String> blocks = new ArrayList<>();
        if (response == null) return blocks;
        int searchFrom = 0;
        while (true) {
            int start = response.indexOf("```java", searchFrom);
            if (start < 0) break;
            int codeStart = response.indexOf('\n', start);
            if (codeStart < 0) break;
            codeStart++;
            int end = response.indexOf("```", codeStart);
            if (end < 0) break;
            String block = response.substring(codeStart, end).trim();
            if (!block.isEmpty()) blocks.add(block);
            searchFrom = end + 3;
        }
        return blocks;
    }
}

