package com.smolnij.chunker.refactor;

import com.smolnij.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the LangChain4j agentic refactoring system.
 *
 * <p>This is the agentic alternative to {@link RefactorMain}. Instead of
 * a fixed Retrieve→Analyze→Refactor→Safety pipeline, the LLM autonomously
 * decides when to call retrieval tools using LangChain4j function calling.
 *
 * <h3>How it works:</h3>
 * <pre>
 *   1. User sends a refactoring request
 *   2. LLM reasons about what context it needs
 *   3. LLM calls retrieveCode() / retrieveCodeById() / getMethodCallers() / getMethodCallees()
 *   4. Tools return method code + call graph relationships
 *   5. LLM continues reasoning, may call more tools
 *   6. LLM produces final refactoring with code, explanation, and breaking changes
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.refactor.AgentRefactorMain "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Optional:
 *     LLM_CHAT_URL              / -Dllm.chatUrl              — chat endpoint (default: http://localhost:1234/v1/chat/completions)
 *     LLM_CHAT_MODEL            / -Dllm.chatModel            — model name (default: whatever is loaded)
 *     LLM_TEMPERATURE           / -Dllm.temperature          — sampling temperature (default: 0.1)
 *     LLM_MAX_TOKENS            / -Dllm.maxTokens            — max response tokens (default: 4096)
 *     REFACTOR_MAX_TOOL_CALLS   / -Drefactor.maxToolCalls    — max tool calls per conversation (default: 10)
 *     REFACTOR_CHAT_MEMORY_SIZE / -Drefactor.chatMemorySize  — sliding window size (default: 20)
 *     REFACTOR_MAX_CHUNKS       / -Drefactor.maxChunks       — chunks per tool call (default: 6)
 *     EMBEDDING_URL             / -Dembedding.url            — embedding endpoint
 *
 *   Flags:
 *     --output / -o &lt;file&gt;  — write result to file
 *     --debug                — print extra diagnostics
 * </pre>
 *
 * <h3>Requirements:</h3>
 * <p>The LM-Studio model must support function/tool calling (e.g. Qwen 2.5,
 * Mistral, Llama 3.1+, or any model with tool-use capability).
 */
public class AgentRefactorMain {

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
            System.err.println("Usage: AgentRefactorMain <query> [--output <file>] [--debug]");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  AgentRefactorMain \"Refactor createUser to async\"");
            System.err.println();
            System.err.println("This uses LangChain4j agentic tool-calling.");
            System.err.println("The LLM decides when to retrieve code context.");
            System.err.println();
            System.err.println("Required: NEO4J_URI, NEO4J_PASSWORD");
            System.err.println("Optional: LLM_CHAT_URL, LLM_CHAT_MODEL, EMBEDDING_URL");
            System.err.println();
            System.err.println("IMPORTANT: Your LM-Studio model must support function/tool calling");
            System.err.println("  (e.g. Qwen 2.5, Mistral, Llama 3.1+)");
            System.exit(1);
            return;
        }

        // ── Load configs ──
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        RefactorConfig refactorConfig = RefactorConfig.fromEnvironment()
                .withAgentMode(true);  // Force agent mode for this entry point

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
        System.out.println("║  Agentic Refactoring (LangChain4j + LM-Studio)       ║");
        System.out.println("║  LLM autonomously retrieves code via tool calling     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Refactor:  " + refactorConfig);
        System.out.println();

        // ── Build components ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig)) {
            EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            // Create tools with configured chunk limit
            RefactorTools tools = new RefactorTools(retriever, reader, refactorConfig.getMaxChunks());

            // Create the agentic assistant
            RefactorAgent agent = new RefactorAgent(refactorConfig, tools);

            // ── Run ──
            System.out.println("━━━ Sending query to agent ━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println();

            String response = agent.chat(query);

            // ── Output ──
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

    /**
     * Format the agent's response for display.
     */
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

    private static String getConfigValue(String envKey, String sysPropKey, String defaultValue) {
        String value = System.getProperty(sysPropKey);
        if (value != null && !value.isEmpty()) return value;
        value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) return value;
        return defaultValue;
    }
}

