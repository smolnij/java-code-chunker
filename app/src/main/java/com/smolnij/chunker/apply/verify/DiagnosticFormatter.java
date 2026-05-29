package com.smolnij.chunker.apply.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders a {@link CompilationResult} into the line-oriented form the LLM
 * sees. Format mirrors {@code javac} / {@code [ERROR]} mvn output for
 * familiarity:
 * <pre>
 * &lt;repo-rel-path&gt;:&lt;line&gt;:&lt;col&gt;: &lt;severity&gt;: &lt;message&gt;
 * </pre>
 *
 * Diagnostics are grouped by file (alphabetical) then sorted by line. Warnings
 * are hidden when errors are present so the LLM focuses on what gates the
 * commit. Output is capped at {@code maxErrors} entries and ~8KB total.
 */
public final class DiagnosticFormatter {

    private static final int OUTPUT_BYTE_BUDGET = 8 * 1024;
    public static final int DEFAULT_MAX_ERRORS = 25;
    public static final int HARD_CAP_ERRORS = 100;

    private DiagnosticFormatter() {}

    public static String format(CompilationResult result, Path repoRoot, int maxErrors) {
        if (result.success() && result.diagnostics().isEmpty()) {
            String backendNote = "OK: no errors (backend=" + result.backend() + ")";
            if (result.note() != null && !result.note().isBlank()) {
                backendNote += "\nNote: " + result.note();
            }
            return backendNote;
        }

        int cap = Math.min(Math.max(maxErrors, 1), HARD_CAP_ERRORS);
        List<CompilationDiagnostic> all = new ArrayList<>(result.diagnostics());

        boolean hasErrors = all.stream().anyMatch(CompilationDiagnostic::isError);
        if (hasErrors) {
            all.removeIf(d -> d.severity() != CompilationDiagnostic.Severity.ERROR);
        }
        all.sort(byFileThenLine(repoRoot));

        StringBuilder sb = new StringBuilder();
        long errorCount = result.errorCount();
        sb.append(result.success() ? "WARN" : "FAIL")
          .append(" (backend=").append(result.backend())
          .append(", ").append(errorCount).append(" error(s)");
        if (result.duration() != null) {
            sb.append(", ").append(result.duration().toMillis()).append("ms");
        }
        sb.append("):\n");

        int rendered = 0;
        for (CompilationDiagnostic d : all) {
            if (rendered >= cap) break;
            String line = renderLine(d, repoRoot);
            if (sb.length() + line.length() + 1 > OUTPUT_BYTE_BUDGET) break;
            sb.append(line).append('\n');
            rendered++;
        }
        if (rendered < all.size()) {
            sb.append("... (").append(all.size() - rendered)
              .append(" more diagnostic(s) omitted; fix the listed ones first and re-run)\n");
        }
        if (result.note() != null && !result.note().isBlank()) {
            sb.append("Note: ").append(result.note()).append('\n');
        }
        return sb.toString();
    }

    private static String renderLine(CompilationDiagnostic d, Path repoRoot) {
        String path;
        if (d.file() == null) {
            path = "<global>";
        } else {
            try {
                path = repoRoot.relativize(d.file()).toString();
            } catch (IllegalArgumentException ex) {
                path = d.file().toString();
            }
        }
        String loc = (d.line() > 0 ? d.line() : "?") + ":" + (d.column() > 0 ? d.column() : "?");
        return path + ":" + loc + ": " + severityToken(d.severity()) + ": " + safe(d.message());
    }

    private static String severityToken(CompilationDiagnostic.Severity s) {
        return switch (s) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case NOTE -> "note";
        };
    }

    private static String safe(String s) {
        if (s == null) return "";
        // Collapse newlines to keep diagnostics one-per-line for the LLM.
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static Comparator<CompilationDiagnostic> byFileThenLine(Path repoRoot) {
        return Comparator
            .<CompilationDiagnostic, String>comparing(d -> {
                if (d.file() == null) return "";
                try { return repoRoot.relativize(d.file()).toString(); }
                catch (IllegalArgumentException ex) { return d.file().toString(); }
            })
            .thenComparingLong(CompilationDiagnostic::line)
            .thenComparingLong(CompilationDiagnostic::column);
    }
}
