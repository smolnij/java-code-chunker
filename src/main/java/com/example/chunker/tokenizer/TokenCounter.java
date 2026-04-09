package com.example.chunker.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-aware chunking: counts tokens using cl100k_base (GPT-4 / LLaMA-compatible)
 * and splits large code blocks at line boundaries when they exceed the configured
 * maximum token limit.
 *
 * <p>The cl100k_base tokenizer is a good proxy for most LLM tokenizers used by
 * LM-Studio models (LLaMA, Mistral, CodeLlama, etc.).
 */
public class TokenCounter {

    private final Encoding encoding;
    private final int maxTokensPerChunk;

    /**
     * @param maxTokensPerChunk maximum tokens allowed per chunk before splitting
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
     * Split a code string into sub-chunks of at most {@code maxTokensPerChunk} tokens.
     * Splits at line boundaries to preserve readability.
     *
     * @param code the source code string to potentially split
     * @return list of code fragments (single element if no split needed)
     */
    public List<String> splitIfNeeded(String code) {
        if (code == null || code.isEmpty()) {
            return List.of("");
        }

        int totalTokens = countTokens(code);
        if (totalTokens <= maxTokensPerChunk) {
            return List.of(code);
        }

        List<String> chunks = new ArrayList<>();
        String[] lines = code.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String line : lines) {
            String lineWithNewline = line + "\n";
            int lineTokens = countTokens(lineWithNewline);

            // If adding this line would exceed the limit, flush the current chunk
            if (currentTokens + lineTokens > maxTokensPerChunk && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            currentChunk.append(lineWithNewline);
            currentTokens += lineTokens;
        }

        // Flush remaining content
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * @return the configured maximum tokens per chunk
     */
    public int getMaxTokensPerChunk() {
        return maxTokensPerChunk;
    }
}

