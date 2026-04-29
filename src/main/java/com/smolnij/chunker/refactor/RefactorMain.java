package com.smolnij.chunker.refactor;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * CLI entry point for the graph-aware LLM refactoring loop.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.refactor.RefactorMain config/refactor.properties
 * </pre>
 */
public class RefactorMain {

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "RefactorMain", "config/refactor.properties");

        String query = PropertiesLoader.requireString(p, "refactor.query");
        String outputFile = PropertiesLoader.getString(p, "refactor.outputFile", null);
        boolean debug = PropertiesLoader.getBoolean(p, "refactor.debug", false);

        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        RefactorConfig refactorConfig = RefactorConfig.fromProperties(p);

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        String mode = refactorConfig.isAgentMode() ? "Agentic (LangChain4j)" : "Pipeline (Retrieve → Refactor → Safety)";
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Graph-Aware LLM Refactoring Loop                    ║");
        System.out.println("║  Mode: " + String.format("%-46s", mode) + "║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Refactor:  " + refactorConfig);
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
             com.smolnij.chunker.store.Neo4jGraphStore store = new com.smolnij.chunker.store.Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword)) {

            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            com.smolnij.chunker.apply.GraphReindexer reindexer = null;
            if (refactorConfig.isApply() && !refactorConfig.getRepoRoot().isEmpty()) {
                reindexer = new com.smolnij.chunker.apply.GraphReindexer(
                    Path.of(refactorConfig.getRepoRoot()),
                    java.util.List.of(Path.of("src/main/java"), Path.of("src/test/java")),
                    512, store, embeddings);
            }

            String output;

            if (refactorConfig.isAgentMode()) {
                RefactorTools tools = new RefactorTools(retriever, reader, refactorConfig.getMaxChunks());
                RefactorAgent agent = new RefactorAgent(refactorConfig, tools);

                String response = agent.chat(query);
                output = formatAgentOutput(query, response, tools.getToolCallCount());

                if (debug) {
                    System.out.println();
                    System.out.println("── Debug: Raw Agent Response ────────────────────────────");
                    System.out.println(response);
                }
            } else {
                try (ChatService chatService = new LmStudioChatService(refactorConfig)) {
                    AstDiffEngine diffEngine = new AstDiffEngine();
                    DiffScorer diffScorer = new DiffScorer(reader);
                    RefactorLoop loop = new RefactorLoop(
                            retriever, reader, chatService, refactorConfig, diffEngine, diffScorer, reindexer);
                    RefactorLoop.RefactorResult result = loop.run(query);

                    output = result.toDisplayString();

                    if (debug) {
                        System.out.println();
                        System.out.println("── Debug: Raw LLM Response ─────────────────────────────");
                        System.out.println(result.getRawLlmResponse());
                    }
                }
            }

            if (outputFile != null) {
                Files.writeString(Path.of(outputFile), output);
                System.out.println("✓ Result written to " + outputFile);
            } else {
                System.out.println();
                System.out.println(output);
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String formatAgentOutput(String query, String response, int toolCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("═".repeat(72)).append("\n");
        sb.append("  AGENTIC REFACTORING RESULT\n");
        sb.append("═".repeat(72)).append("\n\n");
        sb.append("Query: ").append(query).append("\n");
        sb.append("Tool calls: ").append(toolCalls).append("\n\n");
        sb.append("── Agent Response ──────────────────────────────────────\n");
        sb.append(response).append("\n\n");
        sb.append("═".repeat(72)).append("\n");
        return sb.toString();
    }
}
