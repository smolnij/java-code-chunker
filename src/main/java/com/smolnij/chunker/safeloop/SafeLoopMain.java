package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.apply.GraphReindexer;
import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.retrieval.*;
import com.smolnij.chunker.store.Neo4jGraphStore;
import com.smolnij.chunker.util.Errors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * CLI entry point for the self-improving safe refactoring loop.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.safeloop.SafeLoopMain config/safeloop.properties
 * </pre>
 */
public class SafeLoopMain {

    /** Source roots fed to the post-apply Neo4j re-indexer; mirror {@code ChunkerMain}. */
    private static final List<Path> DEFAULT_SOURCE_ROOTS = List.of(
        Path.of("src/main/java"),
        Path.of("src/test/java")
    );

    private static final int DEFAULT_MAX_TOKENS_PER_CHUNK = 512;

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "SafeLoopMain", "config/safeloop.properties");

        String query = PropertiesLoader.requireString(p, "safeloop.query");
        String outputFile = PropertiesLoader.getString(p, "safeloop.outputFile", null);
        boolean debug = PropertiesLoader.getBoolean(p, "safeloop.debug", false);

        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
        SafeLoopConfig safeConfig = SafeLoopConfig.fromProperties(p);

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Safe Refactoring Loop — Self-Improving Agent        ║");
        System.out.println("║  Keeps querying graph + refining until change is safe ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Retrieval: " + retrievalConfig);
        System.out.println("SafeLoop: " + safeConfig);
        System.out.println();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
             Neo4jGraphStore store = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword)) {

            reader.ensureVectorIndex();

            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            GraphReindexer reindexer = null;
            String repoRoot = safeConfig.getRepoRoot();
            if (repoRoot != null && !repoRoot.isEmpty()) {
                reindexer = new GraphReindexer(
                    Path.of(repoRoot), DEFAULT_SOURCE_ROOTS,
                    DEFAULT_MAX_TOKENS_PER_CHUNK, store, embeddings,
                    safeConfig.isReindexCascadeEnabled(),
                    safeConfig.getReindexCascadeMaxFiles());
            }

            try (SafeLoopBundle bundle = SafeLoopBundle.build(reader, retriever, safeConfig, reindexer)) {

                System.out.println("━━━ Starting Safe Refactoring Loop ━━━━━━━━━━━━━━━━━━━");
                System.out.println();

                SafeLoopResult result = bundle.loop().run(query);

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

                System.exit(result.isSafe() ? 0 : 1);
            }

        } catch (Exception e) {
            System.err.println("ERROR: " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
        }
    }
}
