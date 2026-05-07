package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.config.PropertiesLoader;

import java.util.Properties;

/**
 * Configuration for the hybrid Graph-RAG retrieval pipeline.
 *
 * <h3>Scoring formula:</h3>
 * <pre>
 *   finalScore = semanticWeight * cosineSimilarity
 *              + graphWeight    * (1.0 / (1 + hopDistance))
 *              + structuralWeight * structuralBonus
 * </pre>
 */
public class RetrievalConfig {

    private int maxDepth = 2;
    private int topK = 10;

    private double semanticWeight = 0.6;
    private double graphWeight = 0.3;
    private double structuralWeight = 0.1;

    private double sameClassBonus = 1.0;
    private double samePackageBonus = 0.5;
    private double fanInBonus = 0.3;
    private double fanOutBonus = 0.2;
    private int fanInThreshold = 3;

    private String embeddingUrl = "http://localhost:1234/v1/embeddings";
    private String embeddingModel = "text-embedding-nomic-embed-text-v1.5";
    private int embeddingDimensions = 768;

    private String vectorIndexName = "method_embeddings";
    private int vectorSearchK = 20;

    private int maxPathsReturned = 3;
    private int maxTopologyEdges = 200;

    private double anchorPct = 0.15;
    private double callersPct = 0.30;
    private double calleesPct = 0.25;
    private double typeNeighborsPct = 0.20;

    // Verbose resolver / scoring traces (e.g. ranked candidate table at the
    // CONTAINS-fallback @class-boundary site). Default true to preserve the
    // existing diagnostic output.
    private boolean trace = true;

    // Phase 2 — CONTAINS-fallback structural rerank. When multiple candidates
    // fall within `tiebreakEpsilon` cosine of the top semSim, re-rank by a
    // composite score that adds structural priors: `+ extFanInBonus` for
    // candidates with at least one cross-class caller (top-level entry point),
    // `- tinyMethodPenalty` for trivially small methods (helpers/resets),
    // `- partSuffixPenalty` for #partN body-fragment chunks. The α ≤ ε bound
    // (extFanInBonus ≤ tiebreakEpsilon) guarantees the rerank can only flip
    // candidates already inside the window — strong-cosine wins are protected.
    // Set tiebreakEpsilon=0 to disable.
    private double fallbackTiebreakEpsilon = 0.08;
    private double fallbackExtFanInBonus = 0.05;
    private double fallbackTinyMethodPenalty = 0.03;
    private int fallbackTinyMethodTokenThreshold = 20;
    private double fallbackPartSuffixPenalty = 0.02;

    public static RetrievalConfig fromProperties(Properties p) {
        RetrievalConfig cfg = new RetrievalConfig();

        cfg.maxDepth = PropertiesLoader.getInt(p, "retrieval.maxDepth", cfg.maxDepth);
        cfg.topK = PropertiesLoader.getInt(p, "retrieval.topK", cfg.topK);

        cfg.semanticWeight = PropertiesLoader.getDouble(p, "retrieval.semanticWeight", cfg.semanticWeight);
        cfg.graphWeight = PropertiesLoader.getDouble(p, "retrieval.graphWeight", cfg.graphWeight);
        cfg.structuralWeight = PropertiesLoader.getDouble(p, "retrieval.structuralWeight", cfg.structuralWeight);

        cfg.sameClassBonus = PropertiesLoader.getDouble(p, "retrieval.sameClassBonus", cfg.sameClassBonus);
        cfg.samePackageBonus = PropertiesLoader.getDouble(p, "retrieval.samePackageBonus", cfg.samePackageBonus);
        cfg.fanInBonus = PropertiesLoader.getDouble(p, "retrieval.fanInBonus", cfg.fanInBonus);
        cfg.fanOutBonus = PropertiesLoader.getDouble(p, "retrieval.fanOutBonus", cfg.fanOutBonus);
        cfg.fanInThreshold = PropertiesLoader.getInt(p, "retrieval.fanInThreshold", cfg.fanInThreshold);

        cfg.embeddingUrl = PropertiesLoader.getString(p, "embedding.url", cfg.embeddingUrl);
        cfg.embeddingModel = PropertiesLoader.getString(p, "embedding.model", cfg.embeddingModel);
        cfg.embeddingDimensions = PropertiesLoader.getInt(p, "embedding.dimensions", cfg.embeddingDimensions);

        cfg.vectorIndexName = PropertiesLoader.getString(p, "vector.indexName", cfg.vectorIndexName);
        cfg.vectorSearchK = PropertiesLoader.getInt(p, "vector.searchK", cfg.vectorSearchK);

        cfg.maxPathsReturned = PropertiesLoader.getInt(p, "retrieval.maxPathsReturned", cfg.maxPathsReturned);
        cfg.maxTopologyEdges = PropertiesLoader.getInt(p, "retrieval.maxTopologyEdges", cfg.maxTopologyEdges);

        cfg.anchorPct = PropertiesLoader.getDouble(p, "retrieval.anchorPct", cfg.anchorPct);
        cfg.callersPct = PropertiesLoader.getDouble(p, "retrieval.callersPct", cfg.callersPct);
        cfg.calleesPct = PropertiesLoader.getDouble(p, "retrieval.calleesPct", cfg.calleesPct);
        cfg.typeNeighborsPct = PropertiesLoader.getDouble(p, "retrieval.typeNeighborsPct", cfg.typeNeighborsPct);

        cfg.trace = PropertiesLoader.getBoolean(p, "retrieval.trace", cfg.trace);

        cfg.fallbackTiebreakEpsilon = PropertiesLoader.getDouble(p,
                "retrieval.fallback.tiebreakEpsilon", cfg.fallbackTiebreakEpsilon);
        cfg.fallbackExtFanInBonus = PropertiesLoader.getDouble(p,
                "retrieval.fallback.extFanInBonus", cfg.fallbackExtFanInBonus);
        cfg.fallbackTinyMethodPenalty = PropertiesLoader.getDouble(p,
                "retrieval.fallback.tinyMethodPenalty", cfg.fallbackTinyMethodPenalty);
        cfg.fallbackTinyMethodTokenThreshold = PropertiesLoader.getInt(p,
                "retrieval.fallback.tinyMethodTokenThreshold", cfg.fallbackTinyMethodTokenThreshold);
        cfg.fallbackPartSuffixPenalty = PropertiesLoader.getDouble(p,
                "retrieval.fallback.partSuffixPenalty", cfg.fallbackPartSuffixPenalty);

        return cfg;
    }

    public int getMaxDepth() { return maxDepth; }
    public int getTopK() { return topK; }

    public double getAnchorPct() { return anchorPct; }
    public double getCallersPct() { return callersPct; }
    public double getCalleesPct() { return calleesPct; }
    public double getTypeNeighborsPct() { return typeNeighborsPct; }

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

    public boolean isTrace() { return trace; }
    public RetrievalConfig withTrace(boolean v) { this.trace = v; return this; }

    public double getFallbackTiebreakEpsilon() { return fallbackTiebreakEpsilon; }
    public double getFallbackExtFanInBonus() { return fallbackExtFanInBonus; }
    public double getFallbackTinyMethodPenalty() { return fallbackTinyMethodPenalty; }
    public int getFallbackTinyMethodTokenThreshold() { return fallbackTinyMethodTokenThreshold; }
    public double getFallbackPartSuffixPenalty() { return fallbackPartSuffixPenalty; }

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
}
