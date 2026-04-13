package com.smolnij.chunker.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link ChatService} implementation that calls an OpenAI-compatible
 * {@code /v1/chat/completions} endpoint with SSE streaming support.
 *
 * <p>Uses Apache HttpClient 5 — auto-closeable for proper resource management.
 * Designed for LM-Studio but works with any compatible backend
 * (Ollama, vLLM, OpenAI, etc.).
 *
 * <h3>SSE wire format (LM-Studio):</h3>
 * <pre>
 * data: {"id":"...","choices":[{"delta":{"content":"token"},"index":0}]}
 * data: {"id":"...","choices":[{"delta":{"content":" more"},"index":0}]}
 * data: [DONE]
 * </pre>
 *
 * <h3>Sampling defaults:</h3>
 * <pre>
 *   temperature = 0.1
 *   top_p       = 0.9
 * </pre>
 */
public class LmStudioChatService implements ChatService {

    private final String url;
    private final String model;
    private final double temperature;
    private final double topP;
    private final int maxTokens;
    private final CloseableHttpClient httpClient;
    private final Gson gson;

    public LmStudioChatService(RefactorConfig config) {
        this(config.getChatUrl(), config.getChatModel(),
             config.getTemperature(), config.getTopP(), config.getMaxTokens());
    }

    public LmStudioChatService(String url, String model, double temperature, double topP, int maxTokens) {
        this.url = url;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.gson = new Gson();

        var connManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(30, TimeUnit.SECONDS)
                .setSocketTimeout(5, TimeUnit.MINUTES)
                .build())
            .build();

        RequestConfig requestConfig = RequestConfig.custom()
            .setResponseTimeout(Timeout.ofMinutes(5))
            .build();

        this.httpClient = HttpClients.custom()
            .setConnectionManager(connManager)
            .setDefaultRequestConfig(requestConfig)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Non-streaming chat
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String requestBody = buildRequestBody(systemPrompt, userPrompt, false);

        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

        String responseBody;
        try {
            responseBody = httpClient.execute(httpPost, response -> {
                int code = response.getCode();
                String entity = EntityUtils.toString(response.getEntity());
                if (code != 200) {
                    throw new IOException(
                        "Chat endpoint returned HTTP " + code + ": " + entity
                    );
                }
                return entity;
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to call chat endpoint at " + url + ": " + e.getMessage(), e);
        }

        // Parse non-streaming response
        JsonObject json = gson.fromJson(responseBody, JsonObject.class);
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in chat response: " + responseBody);
        }

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return message.get("content").getAsString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Streaming chat (SSE)
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        String requestBody = buildRequestBody(systemPrompt, userPrompt, true);

        HttpPost httpPost = new HttpPost(url);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept", "text/event-stream");
        httpPost.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

        try {
            return httpClient.execute(httpPost, response -> {
                int code = response.getCode();

                if (code != 200) {
                    String errorBody = EntityUtils.toString(response.getEntity());
                    throw new IOException(
                        "Chat endpoint returned HTTP " + code + ": " + errorBody
                    );
                }

                // Read SSE stream line by line
                StringBuilder fullResponse = new StringBuilder();

                try (InputStream is = response.getEntity().getContent();
                     BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // SSE lines are prefixed with "data: "
                        if (!line.startsWith("data: ")) {
                            continue; // skip empty lines, comments, event: lines
                        }

                        String data = line.substring(6).trim();

                        // Terminal signal
                        if ("[DONE]".equals(data)) {
                            break;
                        }

                        // Parse the JSON delta
                        try {
                            JsonObject chunk = gson.fromJson(data, JsonObject.class);
                            JsonArray choices = chunk.getAsJsonArray("choices");
                            if (choices == null || choices.isEmpty()) continue;

                            JsonObject choice = choices.get(0).getAsJsonObject();
                            JsonObject delta = choice.getAsJsonObject("delta");
                            if (delta == null) continue;

                            JsonElement contentEl = delta.get("content");
                            if (contentEl != null && !contentEl.isJsonNull()) {
                                String token = contentEl.getAsString();
                                fullResponse.append(token);
                                if (onToken != null) {
                                    onToken.accept(token);
                                }
                            }
                        } catch (Exception e) {
                            // Skip malformed SSE lines
                            System.err.println("WARN: Skipping malformed SSE chunk: " + data);
                        }
                    }
                }

                return fullResponse.toString();
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to call streaming chat endpoint at " + url + ": " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Request body builder
    // ═══════════════════════════════════════════════════════════════

    private String buildRequestBody(String systemPrompt, String userPrompt, boolean stream) {
        JsonObject body = new JsonObject();

        if (model != null && !model.isEmpty()) {
            body.addProperty("model", model);
        }

        // Messages array: [system, user]
        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        body.add("messages", messages);
        body.addProperty("temperature", temperature);
        body.addProperty("top_p", topP);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", stream);

        return gson.toJson(body);
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }
}

