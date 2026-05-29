package com.smolnij.chunker.apply.verify;

import com.smolnij.chunker.refactor.verify.CompileVerifier;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compilation backend that shells out to {@code mvn -q -o compile} via the
 * legacy {@link CompileVerifier#verifyOverlay} entry point. Authoritative
 * (uses the project's real Maven configuration, including annotation
 * processors and the full classpath) but slow — typically 10–30s per call.
 *
 * <p>Used by {@link LayeredCompilationVerifier} as the fallback / mode=full
 * backend, and as the primary backend when the overlay touches {@code pom.xml}
 * (since {@code JavacVerifier}'s cached classpath would be stale).
 */
public final class MavenVerifier implements CompilationVerifier {

    /** Lines like: {@code [ERROR] /abs/path/to/Foo.java:[42,9] cannot find symbol} */
    private static final Pattern ERROR_LINE_BRACKETED = Pattern.compile(
        "\\[ERROR\\]\\s+(.+?\\.java):\\[(\\d+),(\\d+)\\]\\s*(.*)");

    /** Lines like: {@code /abs/path/to/Foo.java:42: error: cannot find symbol} */
    private static final Pattern JAVAC_PLAIN = Pattern.compile(
        "(.+?\\.java):(\\d+):\\s*error:\\s*(.*)");

    private final CompileVerifier compileVerifier;

    public MavenVerifier() {
        this(new CompileVerifier());
    }

    public MavenVerifier(CompileVerifier compileVerifier) {
        this.compileVerifier = compileVerifier;
    }

    @Override
    public boolean isAvailable() {
        // We assume `mvn` is on PATH; running detection (e.g. `mvn -v`) on
        // every isAvailable() call would be wasteful, so callers should treat
        // a Maven failure as a runtime, not pre-flight, concern.
        return true;
    }

    @Override
    public String backendName() {
        return "maven";
    }

    @Override
    public CompilationResult verify(CompilationRequest req) {
        Instant start = Instant.now();
        try {
            CompileVerifier.MvnRunOutput out = compileVerifier.verifyOverlay(
                req.repoRoot(),
                req.overlay() == null ? java.util.Map.of() : req.overlay());
            Duration took = Duration.between(start, Instant.now());

            if (out.exitCode() == 0) {
                return CompilationResult.ok(backendName(), took);
            }
            List<CompilationDiagnostic> diags = parseDiagnostics(out.combinedOutput());
            if (diags.isEmpty()) {
                // Build failed but no per-file diagnostic was extractable —
                // surface the trailing output as a single global error so the
                // LLM at least sees something actionable.
                String tail = tail(out.combinedOutput(), 1500);
                diags = List.of(new CompilationDiagnostic(null, -1, -1,
                    CompilationDiagnostic.Severity.ERROR, null,
                    "mvn build failed (exit=" + out.exitCode() + "): " + tail));
            }
            return new CompilationResult(false, diags, backendName(), took, null);
        } catch (Exception e) {
            return new CompilationResult(false,
                List.of(new CompilationDiagnostic(null, -1, -1,
                    CompilationDiagnostic.Severity.ERROR, null,
                    "mvn invocation failed: " + e.getClass().getSimpleName() + ": " + e.getMessage())),
                backendName(), Duration.between(start, Instant.now()), null);
        }
    }

    static List<CompilationDiagnostic> parseDiagnostics(String output) {
        List<CompilationDiagnostic> out = new ArrayList<>();
        if (output == null) return out;
        for (String line : output.split("\\R")) {
            Matcher m = ERROR_LINE_BRACKETED.matcher(line);
            if (m.find()) {
                out.add(new CompilationDiagnostic(
                    Path.of(m.group(1)).toAbsolutePath().normalize(),
                    parseLong(m.group(2)),
                    parseLong(m.group(3)),
                    CompilationDiagnostic.Severity.ERROR,
                    null,
                    m.group(4).trim()));
                continue;
            }
            Matcher pm = JAVAC_PLAIN.matcher(line);
            if (pm.find()) {
                out.add(new CompilationDiagnostic(
                    Path.of(pm.group(1)).toAbsolutePath().normalize(),
                    parseLong(pm.group(2)),
                    -1,
                    CompilationDiagnostic.Severity.ERROR,
                    null,
                    pm.group(3).trim()));
            }
        }
        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return -1; }
    }

    private static String tail(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : "..." + t.substring(t.length() - max);
    }
}
