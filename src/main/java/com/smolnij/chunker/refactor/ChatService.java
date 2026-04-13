package com.smolnij.chunker.refactor;

import java.util.function.Consumer;

/**
 * Abstraction for LLM chat completions.
 *
 * <p>Implementations call an OpenAI-compatible
 * {@code /v1/chat/completions} endpoint (LM-Studio, Ollama, vLLM, OpenAI).
 */
public interface ChatService extends AutoCloseable {

    /**
     * Send a chat completion request and return the full response.
     *
     * @param systemPrompt the system/persona message
     * @param userPrompt   the user message
     * @return the assistant's complete response text
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * Send a chat completion request with SSE streaming.
     * Each content token is forwarded to {@code onToken} as it arrives.
     *
     * @param systemPrompt the system/persona message
     * @param userPrompt   the user message
     * @param onToken      callback invoked for each streamed content token
     * @return the full accumulated response text
     */
    String chatStream(String systemPrompt, String userPrompt, Consumer<String> onToken);

    /**
     * Release underlying resources (e.g. HTTP connections).
     * Default implementation is a no-op for backward compatibility.
     */
    @Override
    default void close() throws Exception {
        // no-op by default
    }
}

