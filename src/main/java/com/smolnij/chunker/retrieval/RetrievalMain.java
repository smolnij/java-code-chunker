package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.util.Errors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * CLI entry point for the hybrid Graph-RAG retrieval pipeline.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.retrieval.RetrievalMain config/retrieval.properties
 * </pre>
 */
public class RetrievalMain {

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "RetrievalMain", "config/retrieval.properties");

        String query = PropertiesLoader.requireString(p, "retrieval.query");
        String outputFile = PropertiesLoader.getString(p, "retrieval.outputFile", null);
        boolean debug = PropertiesLoader.getBoolean(p, "retrieval.debug", false);

        RetrievalConfig config = RetrievalConfig.fromProperties(p);

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Graph-RAG Hybrid Retrieval                          ║");
        System.out.println("║  Graph-First → Vector-Second                         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, config);
             EmbeddingService embeddings = new LmStudioEmbeddingService(config)) {

            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, config);

            HybridRetriever.RetrievalResponse response = retriever.retrieve(query);

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
            System.err.println("ERROR: " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
        }
    }
}
