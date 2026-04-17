package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.model.CodeChunk;

/**
 * A single candidate in the retrieval result set.
 *
 * <p>Carries the original {@link CodeChunk} together with all scoring
 * signals so that callers (or a debug/explain mode) can inspect how
 * each chunk was ranked.
 *
 * <h3>Scoring breakdown:</h3>
 * <pre>
 *   finalScore = semanticWeight * semanticSimilarity
 *              + graphWeight    * graphDistanceScore
 *              + structuralWeight * structuralBonus
 * </pre>
 */
public class RetrievalResult implements Comparable<RetrievalResult> {

    private final CodeChunk chunk;
    private final String chunkId;

    // ── Scoring signals ──
    private double semanticSimilarity;   // cosine similarity in [0, 1]
    private int hopDistance;             // graph hops from anchor (0 = anchor itself)
    private double graphDistanceScore;   // 1.0 / (1 + hopDistance)
    private double structuralBonus;      // blended same-class / same-package / fan-in
    private double finalScore;

    // ── Flags ──
    private boolean isAnchor;            // true if this is the original entry point

    // ── Path context (optional) ──
    private GraphPath pathFromAnchor;    // shortest CALLS path from anchor to this chunk (null if unreachable)

    public RetrievalResult(CodeChunk chunk) {
        this.chunk = chunk;
        this.chunkId = chunk.getChunkId();
    }

    // ═══════════════════════════════════════════════════════════════
    // Scoring computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute the final blended score using the given weights.
     */
    public void computeFinalScore(double semanticWeight, double graphWeight, double structuralWeight) {
        this.graphDistanceScore = 1.0 / (1.0 + hopDistance);
        this.finalScore = semanticWeight * semanticSimilarity
                        + graphWeight * graphDistanceScore
                        + structuralWeight * structuralBonus;
    }

    /**
     * Compute the structural bonus from individual signals.
     *
     * @param sameClass       true if chunk belongs to the same class as the anchor
     * @param samePackage     true if chunk is in the same package as the anchor
     * @param highFanIn       true if this method has many callers (≥ threshold)
     * @param sameClassBonus  config weight for same-class signal
     * @param samePackageBonus config weight for same-package signal
     * @param highFanInBonus  config weight for high-fan-in signal
     */
    public void computeStructuralBonus(boolean sameClass, boolean samePackage, boolean highFanIn,
                                        double sameClassBonus, double samePackageBonus, double highFanInBonus) {
        double bonus = 0.0;
        if (sameClass) bonus += sameClassBonus;
        if (samePackage) bonus += samePackageBonus;
        if (highFanIn) bonus += highFanInBonus;
        // Normalize to [0, 1] — max possible is sum of all three
        double maxPossible = sameClassBonus + samePackageBonus + highFanInBonus;
        this.structuralBonus = maxPossible > 0 ? bonus / maxPossible : 0.0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Comparable — sort by finalScore descending
    // ═══════════════════════════════════════════════════════════════

    @Override
    public int compareTo(RetrievalResult other) {
        return Double.compare(other.finalScore, this.finalScore); // descending
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters & Setters
    // ═══════════════════════════════════════════════════════════════

    public CodeChunk getChunk() { return chunk; }
    public String getChunkId() { return chunkId; }

    public double getSemanticSimilarity() { return semanticSimilarity; }
    public void setSemanticSimilarity(double v) { this.semanticSimilarity = v; }

    public int getHopDistance() { return hopDistance; }
    public void setHopDistance(int v) { this.hopDistance = v; }

    public double getGraphDistanceScore() { return graphDistanceScore; }
    public double getStructuralBonus() { return structuralBonus; }
    public double getFinalScore() { return finalScore; }

    public boolean isAnchor() { return isAnchor; }
    public void setAnchor(boolean v) { this.isAnchor = v; }

    public GraphPath getPathFromAnchor() { return pathFromAnchor; }
    public void setPathFromAnchor(GraphPath p) { this.pathFromAnchor = p; }

    /**
     * Render the path from the anchor to this chunk for LLM consumption.
     * Returns a stable placeholder for the anchor itself or when no path exists.
     */
    public String formatPathFromAnchor() {
        if (isAnchor) return "(this is the anchor)";
        if (pathFromAnchor == null) return "(no graph path to anchor)";
        return pathFromAnchor.render();
    }

    // ═══════════════════════════════════════════════════════════════
    // Display
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return String.format(
            "RetrievalResult { chunkId=%s, finalScore=%.4f, semantic=%.4f, graphDist=%d (%.4f), structural=%.4f, anchor=%s }",
            chunkId, finalScore, semanticSimilarity, hopDistance, graphDistanceScore, structuralBonus, isAnchor
        );
    }

    /**
     * Render this result in a compact debug format showing the ranking breakdown.
     */
    public String toDebugString() {
        return String.format(
            "[%.4f] %s%s  (sem=%.3f  graph=%.3f@%dhop  struct=%.3f)",
            finalScore,
            isAnchor ? "⚓ " : "  ",
            chunkId,
            semanticSimilarity,
            graphDistanceScore,
            hopDistance,
            structuralBonus
        );
    }
}

