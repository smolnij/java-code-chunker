package com.smolnij.chunker.eval.runner;

import com.smolnij.chunker.retrieval.EmbeddingService;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.LmStudioEmbeddingService;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalConfig;
import com.smolnij.chunker.safeloop.SafeLoopConfig;

/**
 * Long-lived wiring shared across all fixtures in a single EvalMain
 * invocation. Owns the Neo4j reader + embedding service so we connect
 * once and reuse across the suite. Per-fixture LLM clients (agent +
 * analyzer chat) are constructed inside the individual runners.
 *
 * <p>Fixtures are always executed sequentially by the harness — do not
 * share this context across threads.
 */
public final class RunContext implements AutoCloseable {

    private final Neo4jGraphReader reader;
    private final EmbeddingService embeddings;
    private final HybridRetriever retriever;
    private final RetrievalConfig retrievalConfig;
    private final SafeLoopConfig safeLoopConfig;

    public RunContext(String neo4jUri, String neo4jUser, String neo4jPassword,
                      RetrievalConfig retrievalConfig, SafeLoopConfig safeLoopConfig) {
        this.retrievalConfig = retrievalConfig;
        this.safeLoopConfig = safeLoopConfig;
        this.reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
        this.reader.ensureVectorIndex();
        this.embeddings = new LmStudioEmbeddingService(retrievalConfig);
        this.retriever = new HybridRetriever(reader, embeddings, retrievalConfig);
    }

    public Neo4jGraphReader reader() { return reader; }
    public EmbeddingService embeddings() { return embeddings; }
    public HybridRetriever retriever() { return retriever; }
    public RetrievalConfig retrievalConfig() { return retrievalConfig; }
    public SafeLoopConfig safeLoopConfig() { return safeLoopConfig; }

    @Override
    public void close() {
        try { embeddings.close(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
    }
}
