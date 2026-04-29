package com.smolnij.chunker.refactor;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * CLI entry point for the LangChain4j agentic refactoring system.
 *
 * <p>Forces {@code refactor.agentMode=true} regardless of the file value.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.refactor.AgentRefactorMain config/agent-refactor.properties
 * </pre>
 */
public class AgentRefactorMain {

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "AgentRefactorMain", "config/agent-refactor.properties");

        String query = PropertiesLoader.requireString(p, "refactor.query");
        String outputFile = PropertiesLoader.getString(p, "refactor.outputFile", null);
        boolean debug = PropertiesLoader.getBoolean(p, "refactor.debug", false);

        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        RefactorConfig refactorConfig = RefactorConfig.fromProperties(p)
                .withAgentMode(true);  // Force agent mode for this entry point

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Agentic Refactoring (LangChain4j + LM-Studio)       ║");
        System.out.println("║  LLM autonomously retrieves code via tool calling     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Refactor:  " + refactorConfig);
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig)) {
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            RefactorTools tools = new RefactorTools(retriever, reader, refactorConfig.getMaxChunks());

            RefactorAgent agent = new RefactorAgent(refactorConfig, tools);

            System.out.println("━━━ Sending query to agent ━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println();

            String response = agent.chat(query);

            String output = formatOutput(query, response, tools.getToolCallCount());

            if (outputFile != null) {
                Files.writeString(Path.of(outputFile), output);
                System.out.println("✓ Result written to " + outputFile);
            } else {
                System.out.println();
                System.out.println(output);
            }

            if (debug) {
                System.out.println();
                System.out.println("── Debug: Raw LLM Response ─────────────────────────────");
                System.out.println(response);
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String formatOutput(String query, String response, int toolCalls) {
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
