package com.smolnij.chunker.refactor.diff;

import java.util.*;

/**
 * Structured representation of what changed between two versions
 * of a method at the AST level.
 *
 * <p>Unlike text-based diffs, this captures Java-structural changes:
 * <ul>
 *   <li>Signature changes: return type, parameters, visibility, throws clause</li>
 *   <li>Call graph delta: added/removed method calls in the body</li>
 *   <li>Annotation changes: added/removed method annotations</li>
 *   <li>Body-only changes: internal logic changed but no external-facing change</li>
 * </ul>
 *
 * <p>The {@link #isSafeChange()} predicate returns true when the change
 * cannot break any caller — i.e. no signature change, no removed calls,
 * and the proposed code parses cleanly.
 */
public class MethodDiff {

    private final String chunkId;

    // ── Signature changes ──
    private boolean returnTypeChanged;
    private String oldReturnType = "";
    private String newReturnType = "";

    private boolean paramsChanged;
    private String oldParams = "";
    private String newParams = "";

    private boolean visibilityChanged;
    private String oldVisibility = "";
    private String newVisibility = "";

    private boolean throwsChanged;
    private String oldThrows = "";
    private String newThrows = "";

    // ── Call graph delta ──
    private Set<String> addedCalls = Set.of();
    private Set<String> removedCalls = Set.of();

    // ── Annotation changes ──
    private Set<String> addedAnnotations = Set.of();
    private Set<String> removedAnnotations = Set.of();

    // ── Classification ──
    private boolean bodyOnlyChange;
    private boolean parseError;
    private String parseErrorDetail = "";

    public MethodDiff(String chunkId) {
        this.chunkId = chunkId;
    }

    /**
     * Create a MethodDiff representing a parse failure.
     */
    public static MethodDiff parseError(String chunkId, String whichFailed) {
        MethodDiff diff = new MethodDiff(chunkId);
        diff.parseError = true;
        diff.parseErrorDetail = "Failed to parse " + whichFailed + " code";
        return diff;
    }

    // ═══════════════════════════════════════════════════════════════
    // Derived predicates
    // ═══════════════════════════════════════════════════════════════

    /**
     * True if the method's external contract changed (return type, params,
     * visibility, or throws clause).
     */
    public boolean isSignatureChanged() {
        return returnTypeChanged || paramsChanged || visibilityChanged || throwsChanged;
    }

    /**
     * True if the set of method calls in the body changed.
     */
    public boolean isCallGraphChanged() {
        return !addedCalls.isEmpty() || !removedCalls.isEmpty();
    }

    /**
     * True if this change cannot break any caller:
     * no signature change, no removed calls, and no parse error.
     */
    public boolean isSafeChange() {
        return !isSignatureChanged() && removedCalls.isEmpty() && !parseError;
    }

    /**
     * True if only the method body changed (no signature, no annotation changes).
     */
    public boolean isBodyOnlyChange() {
        return bodyOnlyChange;
    }

    /**
     * Count of all structural changes (for ranking).
     */
    public int getChangeCount() {
        int count = 0;
        if (returnTypeChanged) count++;
        if (paramsChanged) count++;
        if (visibilityChanged) count++;
        if (throwsChanged) count++;
        count += addedCalls.size();
        count += removedCalls.size();
        count += addedAnnotations.size();
        count += removedAnnotations.size();
        return count;
    }

    // ═══════════════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format the diff as human-readable text for LLM consumption.
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== AST Diff: ").append(chunkId).append(" ===\n");

        if (parseError) {
            sb.append("⛔ PARSE ERROR: ").append(parseErrorDetail).append("\n");
            return sb.toString();
        }

        if (!isSignatureChanged() && !isCallGraphChanged()
                && addedAnnotations.isEmpty() && removedAnnotations.isEmpty()) {
            if (bodyOnlyChange) {
                sb.append("✓ Body-only change — no external impact\n");
            } else {
                sb.append("✓ No structural changes detected\n");
            }
            return sb.toString();
        }

        // Signature changes
        if (returnTypeChanged) {
            sb.append("⚠ Return type: ").append(oldReturnType)
                    .append(" → ").append(newReturnType).append("\n");
        }
        if (paramsChanged) {
            sb.append("⚠ Parameters: ").append(oldParams)
                    .append(" → ").append(newParams).append("\n");
        }
        if (visibilityChanged) {
            sb.append("⚠ Visibility: ").append(oldVisibility)
                    .append(" → ").append(newVisibility).append("\n");
        }
        if (throwsChanged) {
            sb.append("⚠ Throws: ").append(oldThrows)
                    .append(" → ").append(newThrows).append("\n");
        }

        // Call graph delta
        if (!addedCalls.isEmpty()) {
            sb.append("+ Added calls: ").append(addedCalls).append("\n");
        }
        if (!removedCalls.isEmpty()) {
            sb.append("- Removed calls: ").append(removedCalls).append("\n");
        }

        // Annotation delta
        if (!addedAnnotations.isEmpty()) {
            sb.append("+ Added annotations: ").append(addedAnnotations).append("\n");
        }
        if (!removedAnnotations.isEmpty()) {
            sb.append("- Removed annotations: ").append(removedAnnotations).append("\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        if (parseError) {
            return "MethodDiff{" + chunkId + ", PARSE_ERROR}";
        }
        return String.format(
                "MethodDiff{%s, sig=%s, calls=+%d/-%d, ann=+%d/-%d, bodyOnly=%s, safe=%s}",
                chunkId,
                isSignatureChanged() ? "CHANGED" : "same",
                addedCalls.size(), removedCalls.size(),
                addedAnnotations.size(), removedAnnotations.size(),
                bodyOnlyChange, isSafeChange()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters & Setters
    // ═══════════════════════════════════════════════════════════════

    public String getChunkId() { return chunkId; }

    public boolean isReturnTypeChanged() { return returnTypeChanged; }
    public void setReturnTypeChanged(boolean v) { this.returnTypeChanged = v; }
    public String getOldReturnType() { return oldReturnType; }
    public void setOldReturnType(String v) { this.oldReturnType = v; }
    public String getNewReturnType() { return newReturnType; }
    public void setNewReturnType(String v) { this.newReturnType = v; }

    public boolean isParamsChanged() { return paramsChanged; }
    public void setParamsChanged(boolean v) { this.paramsChanged = v; }
    public String getOldParams() { return oldParams; }
    public void setOldParams(String v) { this.oldParams = v; }
    public String getNewParams() { return newParams; }
    public void setNewParams(String v) { this.newParams = v; }

    public boolean isVisibilityChanged() { return visibilityChanged; }
    public void setVisibilityChanged(boolean v) { this.visibilityChanged = v; }
    public String getOldVisibility() { return oldVisibility; }
    public void setOldVisibility(String v) { this.oldVisibility = v; }
    public String getNewVisibility() { return newVisibility; }
    public void setNewVisibility(String v) { this.newVisibility = v; }

    public boolean isThrowsChanged() { return throwsChanged; }
    public void setThrowsChanged(boolean v) { this.throwsChanged = v; }
    public String getOldThrows() { return oldThrows; }
    public void setOldThrows(String v) { this.oldThrows = v; }
    public String getNewThrows() { return newThrows; }
    public void setNewThrows(String v) { this.newThrows = v; }

    public Set<String> getAddedCalls() { return addedCalls; }
    public void setAddedCalls(Set<String> v) { this.addedCalls = v; }
    public Set<String> getRemovedCalls() { return removedCalls; }
    public void setRemovedCalls(Set<String> v) { this.removedCalls = v; }

    public Set<String> getAddedAnnotations() { return addedAnnotations; }
    public void setAddedAnnotations(Set<String> v) { this.addedAnnotations = v; }
    public Set<String> getRemovedAnnotations() { return removedAnnotations; }
    public void setRemovedAnnotations(Set<String> v) { this.removedAnnotations = v; }

    public void setBodyOnlyChange(boolean v) { this.bodyOnlyChange = v; }
    public boolean isParseError() { return parseError; }
    public void setParseError(boolean v) { this.parseError = v; }
    public String getParseErrorDetail() { return parseErrorDetail; }
    public void setParseErrorDetail(String v) { this.parseErrorDetail = v; }
}

