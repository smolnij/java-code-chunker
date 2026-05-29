package com.smolnij.chunker.refactor.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Class-level (cross-method) structural diff between the original code chunks
 * in the graph and the methods the LLM proposed.
 *
 * <p>Complements the per-method {@link MethodDiff} / {@link ScoredDiff} with
 * invariants that can only be detected by comparing the full set of methods:
 * <ul>
 *   <li><b>Deleted methods</b> — originals not present in the proposal</li>
 *   <li><b>Added public/protected members</b> — new externally-visible methods
 *       the user did not request</li>
 *   <li><b>Removed {@code @Override} annotations</b> — polymorphism break risk</li>
 *   <li><b>Signature changes on public members</b> — surfaced by inspecting the
 *       per-method diffs</li>
 * </ul>
 *
 * <p>This type is produced by
 * {@link AstDiffEngine#analyze(java.util.List, String)}. Per-method diffs
 * inside it are unscored; callers should run {@link DiffScorer#score(MethodDiff)}
 * on each to get caller-impact-aware safety scores.
 */
public class CrossMethodDiff {

    /**
     * A proposed method that did not match any original in scope.
     * Only public/protected additions are tracked — private additions
     * cannot break external callers.
     */
    public static final class AddedMember {
        private final String className;
        private final String methodName;
        private final String visibility;
        private final String signature;

        public AddedMember(String className, String methodName, String visibility, String signature) {
            this.className = className;
            this.methodName = methodName;
            this.visibility = visibility;
            this.signature = signature;
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public String getVisibility() { return visibility; }
        public String getSignature() { return signature; }

        @Override
        public String toString() {
            String where = className == null || className.isEmpty() ? "" : className + ".";
            return visibility + " " + where + methodName + (signature == null ? "" : signature);
        }
    }

    /** One original method that exists in scope but is missing from the proposal. */
    public static final class DeletedMember {
        private final String chunkId;
        private final String className;
        private final String methodName;
        private final String visibility;

        public DeletedMember(String chunkId, String className, String methodName, String visibility) {
            this.chunkId = chunkId;
            this.className = className;
            this.methodName = methodName;
            this.visibility = visibility;
        }

        public String getChunkId() { return chunkId; }
        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public String getVisibility() { return visibility; }

        public boolean isExternallyVisible() {
            return "public".equals(visibility) || "protected".equals(visibility);
        }

        @Override
        public String toString() {
            return visibility + " " + className + "." + methodName;
        }
    }

    private final List<MethodDiff> methodDiffs;
    private final List<DeletedMember> deletedMethods;
    private final List<AddedMember> addedPublicMembers;
    private final List<String> removedOverrides;

    public CrossMethodDiff(List<MethodDiff> methodDiffs,
                           List<DeletedMember> deletedMethods,
                           List<AddedMember> addedPublicMembers,
                           List<String> removedOverrides) {
        this.methodDiffs = List.copyOf(methodDiffs);
        this.deletedMethods = List.copyOf(deletedMethods);
        this.addedPublicMembers = List.copyOf(addedPublicMembers);
        this.removedOverrides = List.copyOf(removedOverrides);
    }

    public static CrossMethodDiff empty() {
        return new CrossMethodDiff(List.of(), List.of(), List.of(), List.of());
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters & predicates
    // ═══════════════════════════════════════════════════════════════

    public List<MethodDiff> getMethodDiffs() { return methodDiffs; }
    public List<DeletedMember> getDeletedMethods() { return deletedMethods; }
    public List<AddedMember> getAddedPublicMembers() { return addedPublicMembers; }
    public List<String> getRemovedOverrides() { return removedOverrides; }

    /**
     * Per-method diffs that violate signature invariants on originally-public members.
     */
    public List<MethodDiff> getPublicSignatureBreaks() {
        List<MethodDiff> out = new ArrayList<>();
        for (MethodDiff d : methodDiffs) {
            if (!d.isSignatureChanged()) continue;
            String vis = d.getOldVisibility();
            if ("public".equals(vis) || "protected".equals(vis)) {
                out.add(d);
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return methodDiffs.isEmpty()
                && deletedMethods.isEmpty()
                && addedPublicMembers.isEmpty()
                && removedOverrides.isEmpty();
    }

    /**
     * True when at least one invariant is violated (deletion, unrequested public addition,
     * removed override, or public signature break).
     */
    public boolean hasInvariantViolations() {
        if (!removedOverrides.isEmpty()) return true;
        if (!addedPublicMembers.isEmpty()) return true;
        for (DeletedMember d : deletedMethods) {
            if (d.isExternallyVisible()) return true;
        }
        return !getPublicSignatureBreaks().isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format the cross-method findings for LLM consumption.
     * Returns an empty string when nothing is noteworthy.
     */
    public String toDisplayString() {
        if (!hasInvariantViolations()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Cross-Method Invariants ===\n");

        List<DeletedMember> externallyVisibleDeletes = new ArrayList<>();
        for (DeletedMember d : deletedMethods) {
            if (d.isExternallyVisible()) externallyVisibleDeletes.add(d);
        }
        if (!externallyVisibleDeletes.isEmpty()) {
            sb.append("⛔ Deleted public/protected methods (callers will break):\n");
            for (DeletedMember d : externallyVisibleDeletes) {
                sb.append("  - ").append(d).append("\n");
            }
        }

        if (!addedPublicMembers.isEmpty()) {
            sb.append("⚠ Added public/protected members (not in the original request):\n");
            for (AddedMember a : addedPublicMembers) {
                sb.append("  + ").append(a).append("\n");
            }
        }

        if (!removedOverrides.isEmpty()) {
            sb.append("⚠ Removed @Override annotations (polymorphism break risk):\n");
            for (String m : removedOverrides) {
                sb.append("  - ").append(m).append("\n");
            }
        }

        List<MethodDiff> publicBreaks = getPublicSignatureBreaks();
        if (!publicBreaks.isEmpty()) {
            sb.append("⛔ Signature changes on originally-public/protected members:\n");
            for (MethodDiff d : publicBreaks) {
                sb.append("  - ").append(d.getChunkId()).append(": ");
                List<String> parts = new ArrayList<>();
                if (d.isReturnTypeChanged()) {
                    parts.add("return " + d.getOldReturnType() + "→" + d.getNewReturnType());
                }
                if (d.isParamsChanged()) {
                    parts.add("params (" + d.getOldParams() + ")→(" + d.getNewParams() + ")");
                }
                if (d.isVisibilityChanged()) {
                    parts.add("visibility " + d.getOldVisibility() + "→" + d.getNewVisibility());
                }
                if (d.isThrowsChanged()) {
                    parts.add("throws " + d.getOldThrows() + "→" + d.getNewThrows());
                }
                sb.append(String.join("; ", parts)).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(
                "CrossMethodDiff{methods=%d, deleted=%d, addedPublic=%d, removedOverrides=%d, publicSigBreaks=%d}",
                methodDiffs.size(), deletedMethods.size(), addedPublicMembers.size(),
                removedOverrides.size(), getPublicSignatureBreaks().size());
    }
}
