package com.smolnij.chunker.ralph;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.*;
import com.smolnij.chunker.util.Errors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * CLI entry point for the Ralph Wiggum Loop (Worker/Judge orchestrator).
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.ralph.RalphMain config/ralph.properties
 * </pre>
 */
public class RalphMain {

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "RalphMain", "config/ralph.properties");

        String query = PropertiesLoader.requireString(p, "ralph.query");
        String outputFile = PropertiesLoader.getString(p, "ralph.outputFile", null);
        boolean debug = PropertiesLoader.getBoolean(p, "ralph.debug", false);

        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        RalphConfig ralphConfig = RalphConfig.fromProperties(p);

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Ralph Wiggum Loop — Worker/Judge Orchestrator       ║");
        System.out.println("║  \"I'm helping!\" — Ralph Wiggum                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("Ralph:     " + ralphConfig);
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig)) {
            System.out.println("━━━ Step 1: Hybrid Retrieval ━━━━━━━━━━━━━━━━━━━━━━━━━");
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);
            HybridRetriever.RetrievalResponse retrievalResponse = retriever.retrieve(query);
            System.out.println("Retrieved " + retrievalResponse.getResults().size() + " chunks");
            System.out.println();

            AstDiffEngine diffEngine = new AstDiffEngine();
            DiffScorer diffScorer = new DiffScorer(reader);
            RefactorRalphTask task = new RefactorRalphTask(
                    query, retrievalResponse.getResults(), ralphConfig, diffEngine, diffScorer);

            try (ChatService workerChat = new LmStudioChatService(
                    ralphConfig.getChatUrl(),
                    ralphConfig.getWorkerModel(),
                    ralphConfig.getWorkerTemperature(),
                    ralphConfig.getTopP(),
                    ralphConfig.getMaxTokens());
                 ChatService judgeChat = ralphConfig.getJudgeModel().isEmpty() ||
                     ralphConfig.getJudgeModel().equals(ralphConfig.getWorkerModel())
                     ? new LmStudioChatService(
                         ralphConfig.getChatUrl(),
                         ralphConfig.getWorkerModel(),
                         ralphConfig.getJudgeTemperature(),
                         ralphConfig.getTopP(),
                         ralphConfig.getMaxTokens())
                     : new LmStudioChatService(
                         ralphConfig.getChatUrl(),
                         ralphConfig.getJudgeModel(),
                         ralphConfig.getJudgeTemperature(),
                         ralphConfig.getTopP(),
                         ralphConfig.getMaxTokens())) {

                RalphLoop loop = new RalphLoop(workerChat, judgeChat, ralphConfig);
                RalphResult result = loop.run(task);

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
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
        }
    }
}
