package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.retrieval.*;
import com.smolnij.chunker.safeloop.SafeLoopResult;
import com.smolnij.chunker.safeloop.SafetyVerdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * CLI entry point for the planner-driven distributed safe refactoring loop.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.safeloop.distributed.DistributedSafeLoopMain \
 *       config/safeloop-distributed.properties
 * </pre>
 */
public class DistributedSafeLoopMain {

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "DistributedSafeLoopMain",
            "config/safeloop-distributed.properties");

        String query = PropertiesLoader.requireString(p, "dist.query");
        String outputFile = PropertiesLoader.getString(p, "dist.outputFile", null);
        String jsonLogFile = PropertiesLoader.getString(p, "dist.jsonLog", null);
        boolean debug = PropertiesLoader.getBoolean(p, "dist.debug", false);

        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        DistributedSafeLoopConfig distConfig = DistributedSafeLoopConfig.fromProperties(p);

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║  Planner-Driven Distributed Refactoring Loop              ║");
        System.out.println("║  Generator (REFACTOR_MACHINE) — writes code               ║");
        System.out.println("║  Planner-Analyzer (S_ANALYZE) — controls everything       ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Machines:");
        System.out.println("  Generator:         " + distConfig.getRefactorUrl());
        System.out.println("  Planner-Analyzer:  " + distConfig.getAnalyzerUrl());
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Distributed: " + distConfig);
        if (jsonLogFile != null) System.out.println("JSON log: " + jsonLogFile);
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig)) {

            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            try (ChatService generatorChat = new LmStudioChatService(
                    distConfig.getRefactorUrl(),
                    distConfig.getRefactorModel(),
                    distConfig.getRefactorTemperature(),
                    distConfig.getTopP(),
                    distConfig.getMaxTokens())) {

                PlannerTools plannerTools = new PlannerTools(
                    retriever,
                    reader,
                    generatorChat,
                    distConfig.getMaxChunksPerRetrieval(),
                    distConfig.getMaxRetrievalDepth(),
                    distConfig.isTrace()
                );

                PlannerAgent plannerAgent = new PlannerAgent(distConfig, plannerTools);

                DistributedSafeRefactorLoop loop = new DistributedSafeRefactorLoop(
                    plannerAgent, distConfig);

                System.out.println("━━━ Starting Planner-Driven Distributed Loop ━━━━━━━━━━━");
                System.out.println();

                SafeLoopResult result = loop.run(query);

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

                System.exit(result.isSafe() ? 0 : 1);
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
