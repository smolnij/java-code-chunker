package com.example.chunker.ralph;

import com.example.chunker.refactor.ChatService;
import com.example.chunker.refactor.LmStudioChatService;
import com.example.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the Ralph Wiggum Loop (Worker/Judge orchestrator).
 *
 * <p>Connects to Neo4j, runs hybrid retrieval to gather code context,
 * then executes the Ralph Loop: a naive worker attempts the refactoring,
 * a strict judge evaluates it, and the worker retries with judge feedback
 * until the judge approves or max iterations are reached.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.ralph.RalphMain "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Optional:
 *     RALPH_CHAT_URL      / -Dralph.chatUrl      — LLM endpoint (default: http://localhost:1234/v1/chat/completions)
 *     RALPH_WORKER_MODEL  / -Dralph.workerModel  — worker model (default: loaded model)
 *     RALPH_JUDGE_MODEL   / -Dralph.judgeModel   — judge model (default: same as worker)
 *     RALPH_WORKER_TEMP   / -Dralph.workerTemp   — worker temperature (default: 0.3)
 *     RALPH_JUDGE_TEMP    / -Dralph.judgeTemp     — judge temperature (default: 0.1)
 *     RALPH_MAX_ITERATIONS / -Dralph.maxIterations — max retries (default: 5)
 *     RALPH_MAX_CHUNKS    / -Dralph.maxChunks     — context chunks (default: 6)
 *     EMBEDDING_URL       / -Dembedding.url       — embedding endpoint
 *
 *   Flags:
 *     --output / -o &lt;file&gt;  — write result to file
 *     --no-stream            — disable SSE streaming
 *     --debug                — print raw outputs
 * </pre>
 */
public class RalphMain {

    public static void main(String[] args) {

        // ── Parse arguments ──
        String query = null;
        String outputFile = null;
        boolean noStream = false;
        boolean debug = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (i + 1 < args.length) outputFile = args[++i];
                }
                case "--no-stream" -> noStream = true;
                case "--debug" -> debug = true;
                default -> {
                    if (!args[i].startsWith("-")) {
                        query = args[i];
                    }
                }
            }
        }

        if (query == null || query.isBlank()) {
            System.err.println("Usage: RalphMain <query> [--output <file>] [--no-stream] [--debug]");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  RalphMain \"Refactor createUser to async\"");
            System.err.println();
            System.err.println("Required: NEO4J_URI, NEO4J_PASSWORD");
            System.err.println("Optional: RALPH_CHAT_URL, RALPH_WORKER_MODEL, RALPH_JUDGE_MODEL");
            System.exit(1);
            return;
        }

        // ── Load configs ──
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        RalphConfig ralphConfig = RalphConfig.fromEnvironment();

        if (noStream) {
            ralphConfig = ralphConfig.withStream(false);
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
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Ralph Wiggum Loop — Worker/Judge Orchestrator       ║");
        System.out.println("║  \"I'm helping!\" — Ralph Wiggum                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Ralph:     " + ralphConfig);
        System.out.println();

        // ── Run ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig)) {
            // ── Step 1: Hybrid retrieval ──
            System.out.println("━━━ Step 1: Hybrid Retrieval ━━━━━━━━━━━━━━━━━━━━━━━━━");
            EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);
            HybridRetriever.RetrievalResponse retrievalResponse = retriever.retrieve(query);
            System.out.println("Retrieved " + retrievalResponse.getResults().size() + " chunks");
            System.out.println();

            // ── Step 2: Build task ──
            RefactorRalphTask task = new RefactorRalphTask(query, retrievalResponse.getResults(), ralphConfig);

            // ── Step 3: Build chat services ──
            // Worker and judge can use different models/temperatures
            ChatService workerChat = new LmStudioChatService(
                ralphConfig.getChatUrl(),
                ralphConfig.getWorkerModel(),
                ralphConfig.getWorkerTemperature(),
                ralphConfig.getTopP(),
                ralphConfig.getMaxTokens()
            );

            ChatService judgeChat;
            if (!ralphConfig.getJudgeModel().isEmpty() &&
                !ralphConfig.getJudgeModel().equals(ralphConfig.getWorkerModel())) {
                // Different model for judge
                judgeChat = new LmStudioChatService(
                    ralphConfig.getChatUrl(),
                    ralphConfig.getJudgeModel(),
                    ralphConfig.getJudgeTemperature(),
                    ralphConfig.getTopP(),
                    ralphConfig.getMaxTokens()
                );
            } else {
                // Same endpoint, but with judge temperature
                judgeChat = new LmStudioChatService(
                    ralphConfig.getChatUrl(),
                    ralphConfig.getWorkerModel(),
                    ralphConfig.getJudgeTemperature(),
                    ralphConfig.getTopP(),
                    ralphConfig.getMaxTokens()
                );
            }

            // ── Step 4: Run the Ralph Loop ──
            RalphLoop loop = new RalphLoop(workerChat, judgeChat, ralphConfig);
            RalphResult result = loop.run(task);

            // ── Output ──
            String output = result.toDisplayString();

            if (outputFile != null) {
                Files.writeString(Path.of(outputFile), output);
                System.out.println("✓ Result written to " + outputFile);
            } else {
                System.out.println();
                System.out.println(output);
            }

            if (debug) {
                System.out.println();
                System.out.println("── Debug: Full Verdict History ─────────────────────────");
                for (int i = 0; i < result.getVerdictHistory().size(); i++) {
                    JudgeVerdict v = result.getVerdictHistory().get(i);
                    System.out.println("  Round " + (i + 1) + ":");
                    System.out.println("    " + v);
                    System.out.println("    Raw: " + v.getRawResponse().substring(0,
                        Math.min(200, v.getRawResponse().length())) + "...");
                    System.out.println();
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

