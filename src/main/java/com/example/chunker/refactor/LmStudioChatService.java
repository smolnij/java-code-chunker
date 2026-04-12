package com.example.chunker.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * {@link ChatService} implementation that calls an OpenAI-compatible
 * {@code /v1/chat/completions} endpoint with SSE streaming support.
 *
 * <p>Designed for LM-Studio but works with any compatible backend
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
    private final HttpClient httpClient;
    private final Gson gson;

    public LmStudioChatService(RefactorConfig config) {
        this.url = config.getChatUrl();
        this.model = config.getChatModel();
        this.temperature = config.getTemperature();
        this.topP = config.getTopP();
        this.maxTokens = config.getMaxTokens();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
    }

    public LmStudioChatService(String url, String model, double temperature, double topP, int maxTokens) {
        this.url = url;
        this.model = model;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
    }

    // ═══════════════════════════════════════════════════════════════
    // Non-streaming chat
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String requestBody = buildRequestBody(systemPrompt, userPrompt, false);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call chat endpoint at " + url + ": " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Chat endpoint returned HTTP " + response.statusCode() + ": " + response.body()
            );
        }

        // Parse non-streaming response
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in chat response: " + response.body());
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

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofMinutes(5))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        // Use InputStream handler so we can read SSE lines incrementally
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call streaming chat endpoint at " + url + ": " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            // Read error body
            try (InputStream is = response.body()) {
                String errorBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException(
                    "Chat endpoint returned HTTP " + response.statusCode() + ": " + errorBody
                );
            } catch (IOException e) {
                throw new RuntimeException("Chat endpoint returned HTTP " + response.statusCode(), e);
            }
        }

        // Read SSE stream line by line
        StringBuilder fullResponse = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

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
        } catch (IOException e) {
            throw new RuntimeException("Error reading SSE stream: " + e.getMessage(), e);
        }

        return fullResponse.toString();
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
}

