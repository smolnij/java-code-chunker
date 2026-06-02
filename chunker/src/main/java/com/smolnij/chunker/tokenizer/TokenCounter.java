package com.smolnij.chunker.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * Token counting using cl100k_base (GPT-4 / LLaMA-compatible).
 *
 * <p>The cl100k_base tokenizer is a good proxy for most LLM tokenizers used by
 * LM-Studio models (LLaMA, Mistral, CodeLlama, etc.).
 *
 * <p>Methods are never split below method granularity — each method is a single
 * whole chunk. {@link #getMaxTokensPerChunk()} is only the threshold above which
 * a method is flagged {@code oversized} (it is still kept whole).
 */
public class TokenCounter {

    private final Encoding encoding;
    private final int maxTokensPerChunk;

    /**
     * @param maxTokensPerChunk token count above which a method is flagged oversized
     */
    public TokenCounter(int maxTokensPerChunk) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.maxTokensPerChunk = maxTokensPerChunk;
    }

    /**
     * Count the number of tokens in the given text.
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * @return the configured maximum tokens per chunk (oversized threshold)
     */
    public int getMaxTokensPerChunk() {
        return maxTokensPerChunk;
    }
}
