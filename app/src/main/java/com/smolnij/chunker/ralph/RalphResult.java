package com.smolnij.chunker.ralph;

import java.util.List;

/**
 * Immutable result from a complete Ralph Wiggum Loop execution.
 *
 * <p>Contains the final worker output, whether the judge approved it,
 * how many iterations were needed, and the full feedback history from
 * all judge evaluations.
 */
public class RalphResult {

    private final String finalOutput;
    private final boolean approved;
    private final int iterationsUsed;
    private final List<JudgeVerdict> verdictHistory;
    private final String taskLabel;

    public RalphResult(String finalOutput, boolean approved, int iterationsUsed,
                       List<JudgeVerdict> verdictHistory, String taskLabel) {
        this.finalOutput = finalOutput;
        this.approved = approved;
        this.iterationsUsed = iterationsUsed;
        this.verdictHistory = List.copyOf(verdictHistory);
        this.taskLabel = taskLabel;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getFinalOutput() { return finalOutput; }
    public boolean isApproved() { return approved; }
    public int getIterationsUsed() { return iterationsUsed; }
    public List<JudgeVerdict> getVerdictHistory() { return verdictHistory; }
    public String getTaskLabel() { return taskLabel; }

    /**
     * Get the last judge verdict (the one that ended the loop).
     */
    public JudgeVerdict getLastVerdict() {
        return verdictHistory.isEmpty() ? null : verdictHistory.get(verdictHistory.size() - 1);
    }

    // ═══════════════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format the result for display or file output.
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();

        sb.append("═".repeat(72)).append("\n");
        sb.append("  RALPH WIGGUM LOOP — ").append(approved ? "✓ APPROVED" : "✗ NOT APPROVED").append("\n");
        sb.append("═".repeat(72)).append("\n\n");

        sb.append("Task: ").append(taskLabel).append("\n");
        sb.append("Iterations: ").append(iterationsUsed).append("\n");
        sb.append("Final verdict: ").append(approved ? "PASS" : "FAIL").append("\n\n");

        // ── Iteration history ──
        sb.append("── Iteration History ───────────────────────────────────\n");
        for (int i = 0; i < verdictHistory.size(); i++) {
            JudgeVerdict v = verdictHistory.get(i);
            sb.append(String.format("  Round %d: %s (score: %.2f)",
                i + 1, v.isPassed() ? "PASS" : "FAIL", v.getScore()));
            if (!v.getIssues().isEmpty()) {
                sb.append(" — ").append(v.getIssues().size()).append(" issue(s)");
            }
            sb.append("\n");
        }
        sb.append("\n");

        // ── Final output ──
        sb.append("── Final Output ────────────────────────────────────────\n");
        sb.append(finalOutput).append("\n\n");

        // ── Last judge feedback ──
        JudgeVerdict last = getLastVerdict();
        if (last != null && !last.getFeedback().isEmpty()) {
            sb.append("── Judge Feedback (final) ──────────────────────────────\n");
            sb.append(last.getFeedback()).append("\n\n");
        }

        sb.append("═".repeat(72)).append("\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(
            "RalphResult { %s, iterations=%d, task=%s }",
            approved ? "APPROVED" : "NOT APPROVED", iterationsUsed, taskLabel
        );
    }
}

