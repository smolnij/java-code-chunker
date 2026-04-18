package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.retrieval.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the self-improving safe refactoring loop.
 *
 * <p>This is the evolution from RAG → Graph RAG → Agentic Graph RAG → <b>Safe Agentic
 * Graph RAG</b>. The LLM autonomously retrieves context via tool calling (like
 * {@link AgentRefactorMain}), but now a separate analyzer LLM evaluates each
 * proposal for safety. The loop continues querying the graph and refining until
 * the analyzer's confidence exceeds the safety threshold.
 *
 * <h3>How it works:</h3>
 * <pre>
 *   1. Initial retrieval + graph coverage enforcement
 *   2. Agent proposes refactoring (with autonomous tool calls)
 *   3. Analyzer evaluates safety → confidence + risks + needed context
 *   4. If UNSAFE → expand graph → inject context → agent refines → loop
 *   5. If SAFE → return result
 *   6. If CONVERGED/STAGNANT → return best-effort
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.safeloop.SafeLoopMain "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Optional (SafeLoop-specific):
 *     SAFELOOP_CHAT_URL          / -Dsafeloop.chatUrl          — LLM endpoint (default: http://localhost:1234/v1/chat/completions)
 *     SAFELOOP_REFACTOR_MODEL    / -Dsafeloop.refactorModel    — refactorer model name
 *     SAFELOOP_ANALYZER_MODEL    / -Dsafeloop.analyzerModel    — analyzer model name
 *     SAFELOOP_REFACTOR_TEMP     / -Dsafeloop.refactorTemp     — refactorer temperature (default: 0.3)
 *     SAFELOOP_ANALYZER_TEMP     / -Dsafeloop.analyzerTemp     — analyzer temperature (default: 0.1)
 *     SAFELOOP_SAFETY_THRESHOLD  / -Dsafeloop.safetyThreshold  — min confidence (default: 0.9)
 *     SAFELOOP_MAX_ITERATIONS    / -Dsafeloop.maxIterations    — max loop iterations (default: 5)
 *     SAFELOOP_MAX_CHUNKS        / -Dsafeloop.maxChunks        — context chunks (default: 8)
 *     SAFELOOP_CHAT_MEMORY_SIZE  / -Dsafeloop.chatMemorySize   — agent memory window (default: 60)
 *     SAFELOOP_MAX_TOOL_CALLS    / -Dsafeloop.maxToolCalls     — tool call cap (default: 30)
 *     SAFELOOP_MIN_CALLER_DEPTH  / -Dsafeloop.minCallerDepth   — min caller hops (default: 1)
 *     SAFELOOP_MIN_CALLEE_DEPTH  / -Dsafeloop.minCalleeDepth   — min callee hops (default: 1)
 *     EMBEDDING_URL              / -Dembedding.url             — embedding endpoint
 *
 *   Flags:
 *     --output / -o &lt;file&gt;       — write result to file
 *     --max-iterations &lt;N&gt;       — override max iterations
 *     --threshold &lt;0.0-1.0&gt;     — override safety threshold
 *     --no-stream                 — disable SSE streaming
 *     --debug                     — print extra diagnostics
 * </pre>
 *
 * <h3>Requirements:</h3>
 * <p>The LM-Studio model must support function/tool calling (e.g. Qwen 2.5,
 * Mistral, Llama 3.1+, or any model with tool-use capability).
 */
public class SafeLoopMain {
    public static final String NEO4J_DEFAULT_URL = "bolt://localhost:7687";
    public static final String NEO4J_DEFAULT_USER = "neo4j";
    public static final String NEO4J_DEFAULT_PASSWORD = "12345678";

    public static void main(String[] args) {

        // ── Parse arguments ──
        String query = "Suggest refactoring of RalphLoop to take prompt and other settings from file instead of from CLI. " +
                "Don't change files, write your suggestion to output file. Do not use \"CHANGES\" tool, you don't have it";
        String outputFile = "/home/smola/llmout6_with_AST_lang4j13";
        boolean noStream = false;
        boolean debug = true;
        Integer maxIterOverride = null;
        Double thresholdOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (i + 1 < args.length) outputFile = args[++i];
                }
                case "--max-iterations" -> {
                    if (i + 1 < args.length) {
                        try { maxIterOverride = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException ignored) { }
                    }
                }
                case "--threshold" -> {
                    if (i + 1 < args.length) {
                        try { thresholdOverride = Double.parseDouble(args[++i]); }
                        catch (NumberFormatException ignored) { }
                    }
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
            System.err.println("Usage: SafeLoopMain <query> [options]");
            System.err.println();
            System.err.println("Options:");
            System.err.println("  --output / -o <file>       Write result to file");
            System.err.println("  --max-iterations <N>       Override max iterations (default: 5)");
            System.err.println("  --threshold <0.0-1.0>      Override safety threshold (default: 0.9)");
            System.err.println("  --no-stream                Disable SSE streaming");
            System.err.println("  --debug                    Print extra diagnostics");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  SafeLoopMain \"Refactor createUser to async\" --threshold 0.85");
            System.err.println();
            System.err.println("This uses a self-improving loop:");
            System.err.println("  Agent proposes refactoring → Analyzer checks safety");
            System.err.println("  → If unsafe, expand graph → Agent refines → repeat");
            System.err.println();
            System.err.println("Required: NEO4J_URI, NEO4J_PASSWORD");
            System.err.println("Optional: SAFELOOP_CHAT_URL, SAFELOOP_REFACTOR_MODEL, EMBEDDING_URL");
            System.err.println();
            System.err.println("IMPORTANT: Your LM-Studio model must support function/tool calling");
            System.err.println("  (e.g. Qwen 2.5, Mistral, Llama 3.1+)");
            System.exit(1);
            return;
        }

        // ── Load configs ──
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        SafeLoopConfig safeConfig = SafeLoopConfig.fromEnvironment();

        // Apply CLI overrides
        if (maxIterOverride != null) {
            safeConfig = safeConfig.withMaxIterations(maxIterOverride);
        }
        if (thresholdOverride != null) {
            safeConfig = safeConfig.withSafetyThreshold(thresholdOverride);
        }
        if (noStream) {
            safeConfig = safeConfig.withStream(false);
        }

        String neo4jUri = getConfigValue("NEO4J_URI", "neo4j.uri", NEO4J_DEFAULT_URL);
        String neo4jUser = getConfigValue("NEO4J_USER", "neo4j.user", NEO4J_DEFAULT_USER);
        String neo4jPassword = getConfigValue("NEO4J_PASSWORD", "neo4j.password", NEO4J_DEFAULT_PASSWORD);

        if (neo4jUri == null || neo4jPassword == null) {
            System.err.println("ERROR: NEO4J_URI and NEO4J_PASSWORD must be set.");
            System.exit(1);
            return;
        }

        // ── Banner ──
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Safe Refactoring Loop — Self-Improving Agent        ║");
        System.out.println("║  Keeps querying graph + refining until change is safe ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("SafeLoop: " + safeConfig);
        System.out.println();

        // ── Build components ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig)) {

            // Ensure the vector index exists before any vector search
            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            try (SafeLoopBundle bundle = SafeLoopBundle.build(reader, retriever, safeConfig)) {

                System.out.println("━━━ Starting Safe Refactoring Loop ━━━━━━━━━━━━━━━━━━━");
                System.out.println();

                SafeLoopResult result = bundle.loop().run(query);

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
                        SafetyVerdict v = result.getVerdictHistory().get(i);
                        System.out.println("  Round " + (i + 1) + ":");
                        System.out.println("    " + v);
                        System.out.println("    Risks: " + v.getRisks().size());
                        for (SafetyVerdict.Risk risk : v.getRisks()) {
                            System.out.println("      " + risk);
                        }
                        if (!v.getMissingContext().isEmpty()) {
                            System.out.println("    Needs: " + v.getMissingContext());
                        }
                        System.out.println("    Raw: " + v.getRawResponse().substring(0,
                            Math.min(300, v.getRawResponse().length())) + "...");
                        System.out.println();
                    }

                    System.out.println("── Debug: Raw Agent Response ───────────────────────────");
                    System.out.println(result.getRawAgentResponse());
                }

                // Exit with appropriate code
                System.exit(result.isSafe() ? 0 : 1);
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

