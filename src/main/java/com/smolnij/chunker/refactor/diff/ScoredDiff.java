package com.smolnij.chunker.refactor.diff;

import java.util.*;

/**
 * A {@link MethodDiff} enriched with a graph-aware safety score and impact analysis.
 *
 * <p>Produced by {@link DiffScorer}, which queries the Neo4j call graph to
 * compute blast radius (how many callers would break) and weights each
 * structural change by its risk level.
 *
 * <h3>Safety score semantics:</h3>
 * <ul>
 *   <li>{@code 1.0} — completely safe (body-only change, no caller impact)</li>
 *   <li>{@code 0.7–0.99} — low risk (minor call graph changes, no signature break)</li>
 *   <li>{@code 0.3–0.7} — medium risk (signature change with few callers)</li>
 *   <li>{@code 0.0–0.3} — high risk (signature change with many callers, or parse error)</li>
 * </ul>
 */
public class ScoredDiff {

    private final MethodDiff diff;
    private final double safetyScore;           // 0.0 = max risk, 1.0 = safe
    private final List<String> riskReasons;
    private final int affectedCallerCount;
    private final Set<String> affectedCallers;  // chunkIds of methods that would break

    public ScoredDiff(MethodDiff diff, double safetyScore, List<String> riskReasons,
                      int affectedCallerCount, Set<String> affectedCallers) {
        this.diff = diff;
        this.safetyScore = safetyScore;
        this.riskReasons = List.copyOf(riskReasons);
        this.affectedCallerCount = affectedCallerCount;
        this.affectedCallers = Set.copyOf(affectedCallers);
    }

    // ═══════════════════════════════════════════════════════════════
    // Safety queries
    // ═══════════════════════════════════════════════════════════════

    /**
     * Is this diff safe according to the given threshold?
     */
    public boolean isSafe(double threshold) {
        return safetyScore >= threshold;
    }

    /**
     * Is this diff completely safe (body-only, no impact)?
     */
    public boolean isCompletelySafe() {
        return safetyScore >= 0.99;
    }

    // ═══════════════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════════════

    /**
     * Format for LLM consumption — includes AST diff details + score + impact.
     */
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();

        sb.append("╔══════════════════════════════════════════════════════╗\n");
        sb.append("║  AST Diff Score: ").append(diff.getChunkId()).append("\n");
        sb.append("╚══════════════════════════════════════════════════════╝\n\n");

        // Safety score
        String icon = safetyScore >= 0.9 ? "✅" : safetyScore >= 0.5 ? "⚠️" : "🔴";
        sb.append(icon).append(" Safety Score: ")
                .append(String.format("%.2f", safetyScore)).append(" / 1.00\n");
        sb.append("   Affected callers: ").append(affectedCallerCount).append("\n\n");

        // AST diff details
        sb.append(diff.toDisplayString()).append("\n");

        // Risk reasons
        if (!riskReasons.isEmpty()) {
            sb.append("── Risk Analysis ──\n");
            for (String reason : riskReasons) {
                sb.append("  • ").append(reason).append("\n");
            }
            sb.append("\n");
        }

        // Affected callers
        if (!affectedCallers.isEmpty()) {
            sb.append("── Affected Callers (would need updating) ──\n");
            for (String caller : affectedCallers) {
                sb.append("  → ").append(caller).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format(
                "ScoredDiff{%s, score=%.2f, callers=%d, reasons=%d}",
                diff.getChunkId(), safetyScore, affectedCallerCount, riskReasons.size()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public MethodDiff getDiff() { return diff; }
    public double getSafetyScore() { return safetyScore; }
    public List<String> getRiskReasons() { return riskReasons; }
    public int getAffectedCallerCount() { return affectedCallerCount; }
    public Set<String> getAffectedCallers() { return affectedCallers; }
}

