package com.example.chunker.retrieval;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmbeddingService} implementation that calls an OpenAI-compatible
 * {@code /v1/embeddings} REST endpoint (e.g. LM-Studio, Ollama, vLLM, OpenAI).
 *
 * <p>Uses JDK 17 {@link HttpClient} — no extra dependencies needed.
 *
 * <h3>Expected endpoint contract:</h3>
 * <pre>
 * POST /v1/embeddings
 * {
 *   "model": "text-embedding-nomic-embed-text-v1.5",
 *   "input": ["text1", "text2"]
 * }
 *
 * Response:
 * {
 *   "data": [
 *     { "embedding": [0.1, 0.2, ...], "index": 0 },
 *     { "embedding": [0.3, 0.4, ...], "index": 1 }
 *   ]
 * }
 * </pre>
 */
public class LmStudioEmbeddingService implements EmbeddingService {

    private static final int MAX_BATCH_SIZE = 64;

    private final String url;
    private final String model;
    private final HttpClient httpClient;
    private final Gson gson;

    /**
     * @param url   the embedding endpoint URL, e.g. "http://localhost:1234/v1/embeddings"
     * @param model the model identifier to pass in the request body
     */
    public LmStudioEmbeddingService(String url, String model) {
        this.url = url;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.gson = new Gson();
    }

    /**
     * Convenience constructor using {@link RetrievalConfig}.
     */
    public LmStudioEmbeddingService(RetrievalConfig config) {
        this(config.getEmbeddingUrl(), config.getEmbeddingModel());
    }

    @Override
    public float[] embed(String text) {
        List<float[]> results = embedBatch(List.of(text));
        return results.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> allEmbeddings = new ArrayList<>();

        // Process in sub-batches to avoid request-size limits
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, texts.size());
            List<String> subBatch = texts.subList(i, end);
            allEmbeddings.addAll(callEndpoint(subBatch));
        }

        return allEmbeddings;
    }

    /**
     * Call the embedding endpoint for a single sub-batch.
     */
    private List<float[]> callEndpoint(List<String> texts) {
        // ── Build request body ──
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray inputArray = new JsonArray();
        for (String text : texts) {
            inputArray.add(text);
        }
        body.add("input", inputArray);

        String requestBody = gson.toJson(body);

        // ── Send HTTP request ──
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call embedding endpoint at " + url + ": " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Embedding endpoint returned HTTP " + response.statusCode() + ": " + response.body()
            );
        }

        // ── Parse response ──
        JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
        JsonArray dataArray = responseJson.getAsJsonArray("data");

        // The API may return embeddings out of order — sort by index
        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            results.add(null); // placeholder
        }

        for (JsonElement element : dataArray) {
            JsonObject item = element.getAsJsonObject();
            int index = item.get("index").getAsInt();
            JsonArray embeddingArray = item.getAsJsonArray("embedding");

            float[] embedding = new float[embeddingArray.size()];
            for (int j = 0; j < embeddingArray.size(); j++) {
                embedding[j] = embeddingArray.get(j).getAsFloat();
            }

            results.set(index, embedding);
        }

        return results;
    }

    /**
     * Compute cosine similarity between two vectors.
     *
     * @return similarity in [-1, 1]; higher = more similar
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: " + a.length + " vs " + b.length
            );
        }

        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * (double) b[i];
            normA += a[i] * (double) a[i];
            normB += b[i] * (double) b[i];
        }

        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}

