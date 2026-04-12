package com.example.chunker.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the hybrid Graph-RAG retrieval pipeline.
 *
 * <p>Connects to Neo4j (which must already have the code graph loaded
 * via {@code ChunkerMain}), runs the 5-step hybrid retrieval, and
 * outputs LLM-ready context.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.retrieval.RetrievalMain "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Optional:
 *     EMBEDDING_URL  / -Dembedding.url   — embedding endpoint (default: http://localhost:1234/v1/embeddings)
 *     EMBEDDING_MODEL / -Dembedding.model — model name (default: text-embedding-nomic-embed-text-v1.5)
 *     RETRIEVAL_MAX_DEPTH / -Dretrieval.maxDepth — graph expansion depth (default: 2)
 *     RETRIEVAL_TOP_K     / -Dretrieval.topK     — number of results (default: 10)
 *
 *   Output:
 *     --output / -o &lt;file&gt;  — write context to file instead of stdout
 *     --debug               — print full ranking debug info
 * </pre>
 */
public class RetrievalMain {

    public static void main(String[] args) {

        // ── Parse arguments ──
        String query = null;
        String outputFile = null;
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (i + 1 < args.length) outputFile = args[++i];
                }
                case "--debug" -> debug = true;
                default -> {
                    if (!args[i].startsWith("-")) {
                        query = args[i];
                    }
                }
            }
        }

        if (query == null || query.isBlank()) {
            System.err.println("Usage: RetrievalMain <query> [--output <file>] [--debug]");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  RetrievalMain \"Refactor createUser to async\"");
            System.err.println();
            System.err.println("Set NEO4J_URI, NEO4J_PASSWORD, and optionally EMBEDDING_URL.");
            System.exit(1);
            return;
        }

        // ── Load config ──
        RetrievalConfig config = RetrievalConfig.fromEnvironment();

        String neo4jUri = getConfigValue("NEO4J_URI", "neo4j.uri", null);
        String neo4jUser = getConfigValue("NEO4J_USER", "neo4j.user", "neo4j");
        String neo4jPassword = getConfigValue("NEO4J_PASSWORD", "neo4j.password", null);

        if (neo4jUri == null || neo4jPassword == null) {
            System.err.println("ERROR: NEO4J_URI and NEO4J_PASSWORD must be set.");
            System.exit(1);
            return;
        }

        // ── Banner ──
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Graph-RAG Hybrid Retrieval                          ║");
        System.out.println("║  Graph-First → Vector-Second                         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Run retrieval ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, config)) {
            EmbeddingService embeddings = new LmStudioEmbeddingService(config);
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, config);

            HybridRetriever.RetrievalResponse response = retriever.retrieve(query);

            // ── Output ──
            String context = response.toLlmContext();

            if (outputFile != null) {
                Files.writeString(Path.of(outputFile), context);
                System.out.println("✓ Context written to " + outputFile);
            } else {
                System.out.println();
                System.out.println(context);
            }

            if (debug) {
                System.out.println();
                System.out.println("── Debug: Full Ranking ─────────────────────────────────");
                for (RetrievalResult r : response.getResults()) {
                    System.out.println("  " + r);
                }
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String getConfigValue(String envKey, String sysPropKey, String defaultValue) {
        String value = System.getProperty(sysPropKey);
        if (value != null && !value.isEmpty()) return value;
        value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) return value;
        return defaultValue;
    }
}

