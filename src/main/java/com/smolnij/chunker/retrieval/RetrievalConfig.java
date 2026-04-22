package com.smolnij.chunker.retrieval;

/**
 * Configuration for the hybrid Graph-RAG retrieval pipeline.
 *
 * <p>All parameters have sensible defaults and can be overridden via
 * environment variables or system properties.
 *
 * <h3>Scoring formula:</h3>
 * <pre>
 *   finalScore = semanticWeight * cosineSimilarity
 *              + graphWeight    * (1.0 / (1 + hopDistance))
 *              + structuralWeight * structuralBonus
 * </pre>
 *
 * <h3>Structural bonus breakdown:</h3>
 * <pre>
 *   sameClass  → 1.0
 *   samePackage → 0.5
 *   fanIn      → 0.3  (min(calledBy / fanInThreshold, 1.0))
 *   fanOut     → 0.2  (min(calls / fanInThreshold, 1.0))
 * </pre>
 */
public class RetrievalConfig {

    // ── Graph expansion ──
    private int maxDepth = 2;
    private int topK = 10;

    // ── Scoring weights (must sum to 1.0) ──
    private double semanticWeight = 0.6;
    private double graphWeight = 0.3;
    private double structuralWeight = 0.1;

    // ── Structural bonus factors ──
    private double sameClassBonus = 1.0;
    private double samePackageBonus = 0.5;
    private double fanInBonus = 0.3;
    private double fanOutBonus = 0.2;
    private int fanInThreshold = 3;

    // ── Embedding endpoint ──
    private String embeddingUrl = "http://localhost:1234/v1/embeddings";
    private String embeddingModel = "text-embedding-nomic-embed-text-v1.5";
    private int embeddingDimensions = 768;

    // ── Neo4j vector index ──
    private String vectorIndexName = "method_embeddings";
    private int vectorSearchK = 20;

    // ── Path / topology rendering ──
    private int maxPathsReturned = 3;
    private int maxTopologyEdges = 200;

    // ── Budget allocator percentages (0.0 - 1.0). Remaining fraction becomes topology-only fallback.
    // Defaults leave a small leftover for topology-only entries.
    private double anchorPct = 0.15;
    private double callersPct = 0.30;
    private double calleesPct = 0.25;
    private double typeNeighborsPct = 0.20;

    // ═══════════════════════════════════════════════════════════════
    // Factory — load from env / system properties
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a config from environment variables and system properties.
     * System properties take precedence over env vars.
     */
    public static RetrievalConfig fromEnvironment() {
        RetrievalConfig cfg = new RetrievalConfig();

        cfg.maxDepth = intVal("RETRIEVAL_MAX_DEPTH", "retrieval.maxDepth", cfg.maxDepth);
        cfg.topK = intVal("RETRIEVAL_TOP_K", "retrieval.topK", cfg.topK);

        cfg.semanticWeight = doubleVal("RETRIEVAL_SEMANTIC_WEIGHT", "retrieval.semanticWeight", cfg.semanticWeight);
        cfg.graphWeight = doubleVal("RETRIEVAL_GRAPH_WEIGHT", "retrieval.graphWeight", cfg.graphWeight);
        cfg.structuralWeight = doubleVal("RETRIEVAL_STRUCTURAL_WEIGHT", "retrieval.structuralWeight", cfg.structuralWeight);

        cfg.sameClassBonus = doubleVal("RETRIEVAL_SAME_CLASS_BONUS", "retrieval.sameClassBonus", cfg.sameClassBonus);
        cfg.samePackageBonus = doubleVal("RETRIEVAL_SAME_PACKAGE_BONUS", "retrieval.samePackageBonus", cfg.samePackageBonus);
        cfg.fanInBonus = doubleVal("RETRIEVAL_FAN_IN_BONUS", "retrieval.fanInBonus", cfg.fanInBonus);
        cfg.fanOutBonus = doubleVal("RETRIEVAL_FAN_OUT_BONUS", "retrieval.fanOutBonus", cfg.fanOutBonus);
        cfg.fanInThreshold = intVal("RETRIEVAL_FAN_IN_THRESHOLD", "retrieval.fanInThreshold", cfg.fanInThreshold);

        cfg.embeddingUrl = strVal("EMBEDDING_URL", "embedding.url", cfg.embeddingUrl);
        cfg.embeddingModel = strVal("EMBEDDING_MODEL", "embedding.model", cfg.embeddingModel);
        cfg.embeddingDimensions = intVal("EMBEDDING_DIMENSIONS", "embedding.dimensions", cfg.embeddingDimensions);

        cfg.vectorIndexName = strVal("VECTOR_INDEX_NAME", "vector.indexName", cfg.vectorIndexName);
        cfg.vectorSearchK = intVal("VECTOR_SEARCH_K", "vector.searchK", cfg.vectorSearchK);

        cfg.maxPathsReturned = intVal("RETRIEVAL_MAX_PATHS_RETURNED", "retrieval.maxPathsReturned", cfg.maxPathsReturned);
        cfg.maxTopologyEdges = intVal("RETRIEVAL_MAX_TOPOLOGY_EDGES", "retrieval.maxTopologyEdges", cfg.maxTopologyEdges);

        cfg.anchorPct = doubleVal("RETRIEVAL_ANCHOR_PCT", "retrieval.anchorPct", cfg.anchorPct);
        cfg.callersPct = doubleVal("RETRIEVAL_CALLERS_PCT", "retrieval.callersPct", cfg.callersPct);
        cfg.calleesPct = doubleVal("RETRIEVAL_CALLEES_PCT", "retrieval.calleesPct", cfg.calleesPct);
        cfg.typeNeighborsPct = doubleVal("RETRIEVAL_TYPE_NEIGHBORS_PCT", "retrieval.typeNeighborsPct", cfg.typeNeighborsPct);

        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public int getMaxDepth() { return maxDepth; }
    public int getTopK() { return topK; }

    public double getAnchorPct() { return anchorPct; }
    public double getCallersPct() { return callersPct; }
    public double getCalleesPct() { return calleesPct; }
    public double getTypeNeighborsPct() { return typeNeighborsPct; }

    /** The fraction reserved for topology-only fallback (remaining fraction after other buckets). */
    public double getTopologyFallbackPct() {
        double sum = anchorPct + callersPct + calleesPct + typeNeighborsPct;
        return Math.max(0.0, 1.0 - sum);
    }

    public double getSemanticWeight() { return semanticWeight; }
    public double getGraphWeight() { return graphWeight; }
    public double getStructuralWeight() { return structuralWeight; }

    public double getSameClassBonus() { return sameClassBonus; }
    public double getSamePackageBonus() { return samePackageBonus; }
    public double getFanInBonus() { return fanInBonus; }
    public double getFanOutBonus() { return fanOutBonus; }
    public int getFanInThreshold() { return fanInThreshold; }

    public String getEmbeddingUrl() { return embeddingUrl; }
    public String getEmbeddingModel() { return embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }

    public String getVectorIndexName() { return vectorIndexName; }
    public int getVectorSearchK() { return vectorSearchK; }

    public int getMaxPathsReturned() { return maxPathsReturned; }
    public int getMaxTopologyEdges() { return maxTopologyEdges; }

    // ═══════════════════════════════════════════════════════════════
    // Setters (for programmatic / builder-style use)
    // ═══════════════════════════════════════════════════════════════

    public RetrievalConfig withMaxDepth(int v) { this.maxDepth = v; return this; }
    public RetrievalConfig withTopK(int v) { this.topK = v; return this; }
    public RetrievalConfig withSemanticWeight(double v) { this.semanticWeight = v; return this; }
    public RetrievalConfig withGraphWeight(double v) { this.graphWeight = v; return this; }
    public RetrievalConfig withStructuralWeight(double v) { this.structuralWeight = v; return this; }
    public RetrievalConfig withEmbeddingUrl(String v) { this.embeddingUrl = v; return this; }
    public RetrievalConfig withEmbeddingModel(String v) { this.embeddingModel = v; return this; }
    public RetrievalConfig withEmbeddingDimensions(int v) { this.embeddingDimensions = v; return this; }
    public RetrievalConfig withVectorIndexName(String v) { this.vectorIndexName = v; return this; }
    public RetrievalConfig withVectorSearchK(int v) { this.vectorSearchK = v; return this; }
    public RetrievalConfig withMaxPathsReturned(int v) { this.maxPathsReturned = v; return this; }
    public RetrievalConfig withMaxTopologyEdges(int v) { this.maxTopologyEdges = v; return this; }
    public RetrievalConfig withAnchorPct(double v) { this.anchorPct = v; return this; }
    public RetrievalConfig withCallersPct(double v) { this.callersPct = v; return this; }
    public RetrievalConfig withCalleesPct(double v) { this.calleesPct = v; return this; }
    public RetrievalConfig withTypeNeighborsPct(double v) { this.typeNeighborsPct = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "RetrievalConfig { depth=%d, topK=%d, weights=[%.2f/%.2f/%.2f], " +
            "embeddingUrl=%s, model=%s, dims=%d, vectorIndex=%s, maxPaths=%d, maxTopoEdges=%d }",
            maxDepth, topK, semanticWeight, graphWeight, structuralWeight,
            embeddingUrl, embeddingModel, embeddingDimensions, vectorIndexName,
            maxPathsReturned, maxTopologyEdges
        );
    }

    // ── Helpers ──

    private static String strVal(String envKey, String sysPropKey, String defaultValue) {
        String v = System.getProperty(sysPropKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return defaultValue;
    }

    private static int intVal(String envKey, String sysPropKey, int defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static double doubleVal(String envKey, String sysPropKey, double defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return defaultValue; }
    }
}

