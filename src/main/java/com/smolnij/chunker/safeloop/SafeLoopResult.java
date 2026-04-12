package com.smolnij.chunker.safeloop;

import java.util.List;

/**
 * Immutable result from a complete safe refactoring loop execution.
 *
 * <p>Contains the final refactoring output, whether it was deemed safe,
 * the confidence score, full verdict history, and diagnostic metrics
 * (iterations, tool calls, nodes retrieved).
 *
 * <h3>Terminal states:</h3>
 * <ul>
 *   <li><b>SAFE</b> — analyzer approved with confidence ≥ threshold, no HIGH risks</li>
 *   <li><b>UNSAFE (best-effort)</b> — max iterations reached or convergence detected</li>
 *   <li><b>CONVERGED</b> — loop stopped because no new graph nodes were discovered</li>
 *   <li><b>STAGNANT</b> — loop stopped because analyzer returned same risks twice</li>
 * </ul>
 */
public class SafeLoopResult {

    public enum TerminalReason {
        /** Analyzer declared the refactoring safe. */
        SAFE,
        /** Maximum iteration count reached without achieving safety. */
        MAX_ITERATIONS,
        /** No new graph nodes discovered — cannot improve further. */
        CONVERGED,
        /** Analyzer returned same risks two iterations in a row. */
        STAGNANT,
        /** An error occurred during the loop. */
        ERROR
    }

    private final String query;
    private final String finalCode;
    private final String explanation;
    private final boolean safe;
    private final double finalConfidence;
    private final TerminalReason terminalReason;
    private final int iterationsUsed;
    private final List<SafetyVerdict> verdictHistory;
    private final int totalToolCalls;
    private final int totalNodesRetrieved;
    private final List<SafetyVerdict.Risk> finalRisks;
    private final String rawAgentResponse;

    public SafeLoopResult(String query, String finalCode, String explanation,
                          boolean safe, double finalConfidence,
                          TerminalReason terminalReason, int iterationsUsed,
                          List<SafetyVerdict> verdictHistory,
                          int totalToolCalls, int totalNodesRetrieved,
                          List<SafetyVerdict.Risk> finalRisks,
                          String rawAgentResponse) {
        this.query = query;
        this.finalCode = finalCode;
        this.explanation = explanation;
        this.safe = safe;
        this.finalConfidence = finalConfidence;
        this.terminalReason = terminalReason;
        this.iterationsUsed = iterationsUsed;
        this.verdictHistory = List.copyOf(verdictHistory);
        this.totalToolCalls = totalToolCalls;
        this.totalNodesRetrieved = totalNodesRetrieved;
        this.finalRisks = List.copyOf(finalRisks);
        this.rawAgentResponse = rawAgentResponse;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getQuery() { return query; }
    public String getFinalCode() { return finalCode; }
    public String getExplanation() { return explanation; }
    public boolean isSafe() { return safe; }
    public double getFinalConfidence() { return finalConfidence; }
    public TerminalReason getTerminalReason() { return terminalReason; }
    public int getIterationsUsed() { return iterationsUsed; }
    public List<SafetyVerdict> getVerdictHistory() { return verdictHistory; }
    public int getTotalToolCalls() { return totalToolCalls; }
    public int getTotalNodesRetrieved() { return totalNodesRetrieved; }
    public List<SafetyVerdict.Risk> getFinalRisks() { return finalRisks; }
    public String getRawAgentResponse() { return rawAgentResponse; }

    /**
     * Get the last safety verdict (the one that ended the loop).
     */
    public SafetyVerdict getLastVerdict() {
        return verdictHistory.isEmpty() ? null : verdictHistory.get(verdictHistory.size() - 1);
    }

    public boolean hasCode() {
        return finalCode != null && !finalCode.isBlank();
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
        sb.append("  SAFE REFACTORING LOOP — ");
        sb.append(safe ? "✓ SAFE" : "✗ UNSAFE");
        sb.append(" (").append(terminalReason).append(")");
        sb.append("\n");
        sb.append("═".repeat(72)).append("\n\n");

        sb.append("Query: ").append(query).append("\n");
        sb.append("Final confidence: ").append(String.format("%.2f", finalConfidence)).append("\n");
        sb.append("Iterations: ").append(iterationsUsed).append("\n");
        sb.append("Tool calls: ").append(totalToolCalls).append("\n");
        sb.append("Graph nodes retrieved: ").append(totalNodesRetrieved).append("\n\n");

        // ── Iteration history ──
        sb.append("── Iteration History ───────────────────────────────────\n");
        for (int i = 0; i < verdictHistory.size(); i++) {
            SafetyVerdict v = verdictHistory.get(i);
            sb.append(String.format("  Round %d: %s (confidence: %.2f, risks: %d, needs: %d)\n",
                i + 1,
                v.isVerdictSafe() ? "SAFE" : "UNSAFE",
                v.getConfidence(),
                v.getRisks().size(),
                v.getMissingContext().size()
            ));
        }
        sb.append("\n");

        // ── Final code ──
        if (hasCode()) {
            sb.append("── Refactored Code ─────────────────────────────────────\n");
            sb.append(finalCode).append("\n\n");
        }

        // ── Explanation ──
        if (explanation != null && !explanation.isEmpty()) {
            sb.append("── Explanation ──────────────────────────────────────────\n");
            sb.append(explanation).append("\n\n");
        }

        // ── Risks ──
        if (!finalRisks.isEmpty()) {
            sb.append("── Remaining Risks ─────────────────────────────────────\n");
            for (SafetyVerdict.Risk risk : finalRisks) {
                String icon = switch (risk.getSeverity()) {
                    case HIGH -> "🔴";
                    case MEDIUM -> "🟡";
                    case LOW -> "🟢";
                };
                sb.append("  ").append(icon).append(" [").append(risk.getSeverity()).append("] ");
                sb.append(risk.getDescription());
                if (!risk.getMitigation().isEmpty()) {
                    sb.append("\n      → ").append(risk.getMitigation());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // ── Final safety analysis ──
        SafetyVerdict lastVerdict = getLastVerdict();
        if (lastVerdict != null && !lastVerdict.getFeedback().isEmpty()) {
            sb.append("── Safety Analysis (final) ─────────────────────────────\n");
            sb.append(lastVerdict.getFeedback()).append("\n\n");
        }

        sb.append("═".repeat(72)).append("\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(
            "SafeLoopResult { %s (%s), confidence=%.2f, iterations=%d, toolCalls=%d, nodes=%d, risks=%d }",
            safe ? "SAFE" : "UNSAFE", terminalReason,
            finalConfidence, iterationsUsed, totalToolCalls, totalNodesRetrieved, finalRisks.size()
        );
    }
}

