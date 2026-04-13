package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.retrieval.*;
import com.smolnij.chunker.safeloop.SafeLoopResult;
import com.smolnij.chunker.safeloop.SafetyVerdict;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry point for the planner-driven distributed safe refactoring loop.
 *
 * <p>Runs the self-improving refactoring loop across two separate LLM machines,
 * with the Planner–Analyzer on S_ANALYZE_MACHINE driving everything:
 *
 * <pre>
 *   ┌──────────────────────────┐        ┌──────────────────────────┐
 *   │  REFACTOR_MACHINE        │        │  S_ANALYZE_MACHINE       │
 *   │  http://REFACTORM:1234   │        │  http://SANALYZEM:1234   │
 *   │                          │        │                          │
 *   │  🟦 Generator LLM        │        │  🟩 Planner–Analyzer     │
 *   │  Role: Senior Java Eng.  │        │  Role: Senior Architect  │
 *   │  Temp: 0.3 (creative)    │        │  + Static Analyzer       │
 *   │  Tool calling: NO        │        │  Temp: 0.1 (precise)     │
 *   │  Decision authority: NO  │        │  Tool calling: YES       │
 *   │                          │        │  Decision authority: FULL │
 *   │  Produces refactored code│        │                          │
 *   │  when asked by Planner   │        │  Controls:               │
 *   │                          │        │  - retrieval (graph)      │
 *   │                          │        │  - refactoring (remote)   │
 *   │                          │        │  - validation             │
 *   │                          │        │  - loop termination       │
 *   └──────────┬───────────────┘        └──────────┬───────────────┘
 *              │                                    │
 *              └──────── Planner tool calls ────────┘
 *
 *   Planner tools:
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │  retrieveCode(query, depth)     → graph + vector search      │
 *   │  retrieveCodeById(id, depth)    → targeted graph expansion   │
 *   │  getMethodCallers(id)           → impact analysis            │
 *   │  getMethodCallees(id)           → dependency analysis        │
 *   │  refactorCode(prompt)           → delegate to Generator      │
 *   └───────────────────────────────────────────────────────────────┘
 *
 *   Planner loop (autonomous, inside single LangChain4j call):
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │  1. Plan: what context do I need?                            │
 *   │  2. Retrieve: call retrieval tools                           │
 *   │  3. Validate context: all callers + callees + shared state?  │
 *   │  4. Refactor: call refactorCode() → Generator                │
 *   │  5. Validate result: safe? confidence &gt; 0.9?                 │
 *   │  6. If unsafe → expand context → retry refactor              │
 *   │  7. Return final JSON verdict                                │
 *   └───────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.safeloop.distributed.DistributedSafeLoopMain \
 *       "Refactor createUser to async"
 *
 *   Required environment variables / system properties:
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *
 *   Machine configuration:
 *     REFACTOR_MACHINE_URL   / -Ddist.refactorUrl    — Generator endpoint (default: http://REFACTORM:1234/v1/chat/completions)
 *     REFACTOR_MACHINE_MODEL / -Ddist.refactorModel  — Generator model name
 *     ANALYZER_MACHINE_URL   / -Ddist.analyzerUrl    — Planner endpoint (default: http://SANALYZEM:1234/v1/chat/completions)
 *     ANALYZER_MACHINE_MODEL / -Ddist.analyzerModel  — Planner model name
 *
 *   Optional:
 *     DIST_SAFETY_THRESHOLD  / -Ddist.safetyThreshold  — min confidence (default: 0.9)
 *     DIST_MAX_ITERATIONS    / -Ddist.maxIterations    — max loop iterations (default: 5)
 *     DIST_MAX_CHUNKS        / -Ddist.maxChunks        — context chunks (default: 8)
 *     DIST_MAX_PLANNER_STEPS / -Ddist.maxPlannerSteps  — max planner tool calls (default: 8)
 *     DIST_MAX_CHUNKS_PER_RETRIEVAL / -Ddist.maxChunksPerRetrieval — chunks per retrieval (default: 10)
 *     DIST_MAX_RETRIEVAL_DEPTH / -Ddist.maxRetrievalDepth — max graph depth (default: 2)
 *     EMBEDDING_URL          / -Dembedding.url         — embedding endpoint
 *
 *   Flags:
 *     --output / -o &lt;file&gt;       — write result to file
 *     --max-iterations &lt;N&gt;       — override max iterations
 *     --threshold &lt;0.0-1.0&gt;     — override safety threshold
 *     --no-stream                 — disable SSE streaming
 *     --debug                     — print extra diagnostics
 *     --json-log &lt;file&gt;          — write full JSON protocol log to file
 * </pre>
 *
 * <h3>Requirements:</h3>
 * <p>The Planner–Analyzer machine (S_ANALYZE_MACHINE) must support function/tool calling.
 * The Generator machine (REFACTOR_MACHINE) only needs standard chat completions.
 */
public class DistributedSafeLoopMain {

    public static void main(String[] args) {

        // ── Parse arguments ──
        String query = null;
        String outputFile = null;
        String jsonLogFile = null;
        boolean noStream = false;
        boolean debug = false;
        Integer maxIterOverride = null;
        Double thresholdOverride = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output", "-o" -> {
                    if (i + 1 < args.length) outputFile = args[++i];
                }
                case "--json-log" -> {
                    if (i + 1 < args.length) jsonLogFile = args[++i];
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
            System.err.println("Usage: DistributedSafeLoopMain <query> [options]");
            System.err.println();
            System.err.println("Options:");
            System.err.println("  --output / -o <file>       Write result to file");
            System.err.println("  --json-log <file>          Write JSON protocol log to file");
            System.err.println("  --max-iterations <N>       Override max iterations (default: 5)");
            System.err.println("  --threshold <0.0-1.0>      Override safety threshold (default: 0.9)");
            System.err.println("  --no-stream                Disable SSE streaming");
            System.err.println("  --debug                    Print extra diagnostics");
            System.err.println();
            System.err.println("Machine Configuration:");
            System.err.println("  REFACTOR_MACHINE_URL=http://REFACTORM:1234/v1/chat/completions");
            System.err.println("  ANALYZER_MACHINE_URL=http://SANALYZEM:1234/v1/chat/completions");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  DistributedSafeLoopMain \"Refactor createUser to async\" --threshold 0.85");
            System.err.println();
            System.err.println("This uses a planner-driven distributed loop:");
            System.err.println("  🟩 Planner–Analyzer (S_ANALYZE_MACHINE) controls everything:");
            System.err.println("     - retrieves context from code graph");
            System.err.println("     - delegates refactoring to Generator");
            System.err.println("     - validates results, iterates until safe");
            System.err.println("  🟦 Generator (REFACTOR_MACHINE) writes code when asked");
            System.err.println();
            System.err.println("Required: NEO4J_URI, NEO4J_PASSWORD");
            System.err.println("Required: REFACTOR_MACHINE_URL, ANALYZER_MACHINE_URL");
            System.err.println();
            System.err.println("IMPORTANT: Planner LLM (S_ANALYZE_MACHINE) must support function/tool calling");
            System.err.println("  (e.g. Qwen 2.5, Mistral, Llama 3.1+)");
            System.exit(1);
            return;
        }

        // ── Load configs ──
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
        DistributedSafeLoopConfig distConfig = DistributedSafeLoopConfig.fromEnvironment();

        // Apply CLI overrides
        if (maxIterOverride != null) {
            distConfig = distConfig.withMaxIterations(maxIterOverride);
        }
        if (thresholdOverride != null) {
            distConfig = distConfig.withSafetyThreshold(thresholdOverride);
        }
        if (noStream) {
            distConfig = distConfig.withStream(false);
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
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║  Planner-Driven Distributed Refactoring Loop              ║");
        System.out.println("║  🟦 Generator (REFACTOR_MACHINE) — writes code            ║");
        System.out.println("║  🟩 Planner–Analyzer (S_ANALYZE) — controls everything    ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Machines:");
        System.out.println("  🟦 Generator:         " + distConfig.getRefactorUrl());
        System.out.println("  🟩 Planner–Analyzer:  " + distConfig.getAnalyzerUrl());
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Distributed: " + distConfig);
        System.out.println();

        // ── Build components ──
        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig)) {

            // Ensure the vector index exists before any vector search
            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            // 🟦 Generator chat service (REFACTOR_MACHINE, no tool calling)
            // The Generator is now a plain chat service — the Planner calls it via refactorCode()
            try (ChatService generatorChat = new LmStudioChatService(
                    distConfig.getRefactorUrl(),
                    distConfig.getRefactorModel(),
                    distConfig.getRefactorTemperature(),
                    distConfig.getTopP(),
                    distConfig.getMaxTokens())) {

                // 🟩 Planner tools (retrieval runs locally, refactor delegates to Generator)
                PlannerTools plannerTools = new PlannerTools(
                    retriever,
                    reader,
                    generatorChat,
                    distConfig.getMaxChunksPerRetrieval(),
                    distConfig.getMaxRetrievalDepth()
                );

                // 🟩 Planner–Analyzer agent (S_ANALYZE_MACHINE with tool calling)
                PlannerAgent plannerAgent = new PlannerAgent(distConfig, plannerTools);

                // ── Build and run the planner-driven loop ──
                DistributedSafeRefactorLoop loop = new DistributedSafeRefactorLoop(
                    plannerAgent, distConfig);

                System.out.println("━━━ Starting Planner-Driven Distributed Loop ━━━━━━━━━━━");
                System.out.println();

                SafeLoopResult result = loop.run(query);

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

                    System.out.println("── Debug: Raw Planner Response ─────────────────────────");
                    System.out.println(result.getRawAgentResponse());

                    System.out.println();
                    System.out.println("── Debug: Planner Stats ────────────────────────────────");
                    System.out.println("  Tool calls: " + plannerTools.getToolCallCount());
                    System.out.println("  Refactor delegations: " + plannerTools.getRefactorCallCount());
                    System.out.println("  Graph nodes retrieved: " + plannerTools.getTotalNodesRetrieved());
                    System.out.println("  Retrieved node IDs: " + plannerTools.getRetrievedNodeIds());
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

