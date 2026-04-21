package com.smolnij.chunker.apply;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of running a {@link PatchPlan} through {@link PatchApplier}.
 *
 * <p>On success, {@link #changedFiles} lists every file written to disk (or
 * staged, for dry-run). On failure, {@link #success} is {@code false},
 * {@link #errors} explains why, and nothing was written — the applier aborts
 * atomically.
 *
 * <p>{@link #opStatuses} tracks per-op outcomes so callers can surface
 * a granular status line per edit ("replaced Util.lmStudioHttpClientBuilder").
 * {@link #stagedContents} gives the final (post-edit) content for every
 * touched file, useful for dry-run diffs.
 */
public class ApplyResult {

    private final boolean success;
    private final List<Path> changedFiles;
    private final List<String> errors;
    private final List<OpStatus> opStatuses;
    private final Map<Path, String> stagedContents;
    private final boolean dryRun;

    ApplyResult(boolean success,
                List<Path> changedFiles,
                List<String> errors,
                List<OpStatus> opStatuses,
                Map<Path, String> stagedContents,
                boolean dryRun) {
        this.success = success;
        this.changedFiles = List.copyOf(changedFiles);
        this.errors = List.copyOf(errors);
        this.opStatuses = List.copyOf(opStatuses);
        this.stagedContents = Collections.unmodifiableMap(new LinkedHashMap<>(stagedContents));
        this.dryRun = dryRun;
    }

    public boolean isSuccess() { return success; }
    public List<Path> getChangedFiles() { return changedFiles; }
    public List<String> getErrors() { return errors; }
    public List<OpStatus> getOpStatuses() { return opStatuses; }
    public Map<Path, String> getStagedContents() { return stagedContents; }
    public boolean isDryRun() { return dryRun; }

    /**
     * One line per op describing its outcome; suitable for inclusion in a
     * {@code SafeLoopResult.applyReport} or for direct console logging.
     */
    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("PatchApplier: ").append(success ? "✓ success" : "✗ failed")
          .append(dryRun ? " (dry-run)" : "")
          .append(" — ")
          .append(changedFiles.size()).append(" file(s), ")
          .append(opStatuses.size()).append(" op(s)\n");

        for (OpStatus os : opStatuses) {
            sb.append("  ")
              .append(os.ok() ? "✓" : "✗")
              .append(" [")
              .append(os.opKind())
              .append("] ")
              .append(os.summary());
            if (!os.ok() && !os.detail().isEmpty()) {
                sb.append(" — ").append(os.detail());
            }
            sb.append('\n');
        }
        if (!errors.isEmpty()) {
            sb.append("  Errors:\n");
            for (String e : errors) {
                sb.append("    • ").append(e).append('\n');
            }
        }
        if (!changedFiles.isEmpty()) {
            sb.append("  Files:\n");
            for (Path p : changedFiles) {
                sb.append("    • ").append(p).append('\n');
            }
        }
        return sb.toString();
    }

    /** Per-op outcome: kind (e.g. {@code replace_method}), one-line summary, detail on failure. */
    public record OpStatus(String opKind, String summary, boolean ok, String detail) {
        public OpStatus(String opKind, String summary, boolean ok) {
            this(opKind, summary, ok, "");
        }
    }

    // ── Builder used by PatchApplier ──

    static class Builder {
        private boolean dryRun;
        private final List<String> errors = new ArrayList<>();
        private final List<OpStatus> opStatuses = new ArrayList<>();
        private final Map<Path, String> staged = new LinkedHashMap<>();
        private final List<Path> changed = new ArrayList<>();

        Builder dryRun(boolean v) { this.dryRun = v; return this; }
        Builder error(String e) { this.errors.add(e); return this; }
        Builder op(OpStatus os) { this.opStatuses.add(os); return this; }
        Builder staged(Map<Path, String> m) { this.staged.putAll(m); return this; }
        Builder changed(List<Path> files) { this.changed.addAll(files); return this; }

        ApplyResult success() {
            return new ApplyResult(true, changed, errors, opStatuses, staged, dryRun);
        }

        ApplyResult failure() {
            return new ApplyResult(false, List.of(), errors, opStatuses, staged, dryRun);
        }
    }
}
