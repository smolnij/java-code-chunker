package com.smolnij.chunker.refactor;

import com.smolnij.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the graph-aware LLM refactoring loop.
 *
 * <p>Connects to Neo4j (code graph must be loaded via {@code ChunkerMain}),
 * runs hybrid retrieval, and orchestrates an iterative refactoring conversation
 * with a local LLM via LM-Studio.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.refactor.RefactorMain "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Optional:
 *     LLM_CHAT_URL       / -Dllm.chatUrl       — chat endpoint (default: http://localhost:1234/v1/chat/completions)
 *     LLM_CHAT_MODEL     / -Dllm.chatModel     — model name (default: whatever is loaded)
 *     LLM_TEMPERATURE    / -Dllm.temperature    — sampling temperature (default: 0.1)
 *     LLM_MAX_TOKENS     / -Dllm.maxTokens      — max response tokens (default: 4096)
 *     REFACTOR_MAX_CHUNKS / -Drefactor.maxChunks — context chunks (default: 6)
 *     REFACTOR_MAX_REFINEMENTS / -Drefactor.maxRefinements — retry rounds (default: 2)
 *     EMBEDDING_URL      / -Dembedding.url      — embedding endpoint
 *
 *   Flags:
 *     --output / -o &lt;file&gt;  — write result to file
 *     --no-stream            — disable SSE streaming (wait for full response)
 *     --agent                — use LangChain4j agentic mode (LLM calls tools autonomously)
 *     --debug                — print raw LLM responses
 * </pre>
 */
public class RefactorMain {

    public static void main(String[] args) {

        // ── Parse arguments ──
        String query = null;
        String outputFile = null;
        boolean noStream = false;
        boolean debug = false;
        boolean agentMode = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (i + 1 < args.length) outputFile = args[++i];
                }
                case "--no-stream" -> noStream = true;
                case "--agent" -> agentMode = true;
                case "--debug" -> debug = true;
                default -> {
                    if (!args[i].startsWith("-")) {
                        query = args[i];
                    }
                }
            }
        }

        if (query == null || query.isBlank()) {
            System.err.println("Usage: RefactorMain <query> [--output <file>] [--no-stream] [--agent] [--debug]");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  RefactorMain \"Refactor createUser to async\"");
            System.err.println();
            System.err.println("Modes:");
            System.err.println("  (default)  — Fixed pipeline: Retrieve → Analyze → Refactor → Safety");
            System.err.println("  --agent    — Agentic mode: LLM calls retrieval tools autonomously (requires tool-calling model)");
            System.err.println();
            System.err.println("Required: NEO4J_URI, NEO4J_PASSWORD");
            System.err.println("Optional: LLM_CHAT_URL, LLM_CHAT_MODEL, EMBEDDING_URL");
            System.exit(1);
            return;
        }

        // ── Load configs ──
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        RefactorConfig refactorConfig = RefactorConfig.fromEnvironment();

        if (noStream) {
            refactorConfig = refactorConfig.withStream(false);
        }
        if (agentMode) {
            refactorConfig = refactorConfig.withAgentMode(true);
        }

        String neo4jUri = getConfigValue("NEO4J_URI", "neo4j.uri", null);
        String neo4jUser = getConfigValue("NEO4J_USER", "neo4j.user", "neo4j");
        String neo4jPassword = getConfigValue("NEO4J_PASSWORD", "neo4j.password", null);

        if (neo4jUri == null || neo4jPassword == null) {
            System.err.println("ERROR: NEO4J_URI and NEO4J_PASSWORD must be set.");
            System.exit(1);
            return;
        }

        // ── Banner ──
        String mode = refactorConfig.isAgentMode() ? "Agentic (LangChain4j)" : "Pipeline (Retrieve → Refactor → Safety)";
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Graph-Aware LLM Refactoring Loop                    ║");
        System.out.println("║  Mode: " + String.format("%-46s", mode) + "║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Refactor:  " + refactorConfig);
        System.out.println();

        // ── Run ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig)) {
            EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            String output;

            if (refactorConfig.isAgentMode()) {
                // ── Agentic mode: LLM calls tools autonomously ──
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
                // ── Legacy pipeline mode ──
                ChatService chatService = new LmStudioChatService(refactorConfig);
                RefactorLoop loop = new RefactorLoop(retriever, reader, chatService, refactorConfig);
                RefactorLoop.RefactorResult result = loop.run(query);

                output = result.toDisplayString();

                if (debug) {
                    System.out.println();
                    System.out.println("── Debug: Raw LLM Response ─────────────────────────────");
                    System.out.println(result.getRawLlmResponse());
                }
            }

            // ── Output ──
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

    private static String getConfigValue(String envKey, String sysPropKey, String defaultValue) {
        String value = System.getProperty(sysPropKey);
        if (value != null && !value.isEmpty()) return value;
        value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) return value;
        return defaultValue;
    }
}

