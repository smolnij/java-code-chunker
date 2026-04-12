package com.example.chunker.retrieval;

import java.util.List;

/**
 * Abstraction for text embedding.
 *
 * <p>Implementations may call a local LM-Studio endpoint, OpenAI API,
 * or any other OpenAI-compatible {@code /v1/embeddings} service.
 */
public interface EmbeddingService {

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
}

