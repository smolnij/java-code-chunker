package com.smolnij.chunker.retrieval;

import java.util.List;

/**
 * Abstraction for text embedding.
 *
 * <p>Implementations may call a local LM-Studio endpoint, OpenAI API,
 * or any other OpenAI-compatible {@code /v1/embeddings} service.
 */
public interface EmbeddingService extends AutoCloseable {

    /**
     * Embed a single text string into a dense vector.
     *
     * @param text the input text (query, code, etc.)
     * @return the embedding vector
     */
    float[] embed(String text);

    /**
     * Embed multiple texts in a single batch call.
     *
     * @param texts list of input texts
     * @return list of embedding vectors (same order as input)
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Release underlying resources (e.g. HTTP connections).
     * Default implementation is a no-op for backward compatibility.
     */
    @Override
    default void close() throws Exception {
        // no-op by default
    }
}

