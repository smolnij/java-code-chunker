package com.smolnij.chunker.apply;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.RefactorLoop;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.EmbeddingService;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.LmStudioEmbeddingService;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalConfig;
import com.smolnij.chunker.safeloop.SafeLoopBundle;
import com.smolnij.chunker.safeloop.SafeLoopConfig;
import com.smolnij.chunker.safeloop.SafeLoopResult;
import com.smolnij.chunker.store.Neo4jGraphStore;
import com.smolnij.chunker.util.Errors;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

/**
 * CLI entry point for apply-enabled refactoring.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.apply.ApplyMain config/apply.properties
 * </pre>
 */
public class ApplyMain {

    enum Mode { SAFELOOP, REFACTOR }

    /** Source roots fed to the post-apply Neo4j re-indexer; mirror {@code ChunkerMain}. */
    private static final List<Path> DEFAULT_SOURCE_ROOTS = List.of(
        Path.of("src/main/java"),
        Path.of("src/test/java")
    );

    private static final int DEFAULT_MAX_TOKENS_PER_CHUNK = 512;

    public static void main(String[] args) {
        Properties p = PropertiesLoader.loadOrExit(args, "ApplyMain", "config/apply.properties");

        String repoRoot = PropertiesLoader.requireString(p, "apply.repoRoot");
        String query = PropertiesLoader.requireString(p, "apply.query");
        String modeStr = PropertiesLoader.getString(p, "apply.mode", "safeloop");
        boolean dryRun = PropertiesLoader.getBoolean(p, "apply.dryRun", false);
        boolean backup = PropertiesLoader.getBoolean(p, "apply.backup", true);

        Mode mode;
        try {
            mode = Mode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("Unknown apply.mode: " + modeStr + " (expected: safeloop | refactor)");
            System.exit(1);
            return;
        }

        Path repoRootPath = Paths.get(repoRoot).toAbsolutePath().normalize();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Apply-Enabled Refactoring                           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Repo root: " + repoRootPath);
        System.out.println("Mode: " + mode);
        System.out.println("Dry run: " + dryRun);
        System.out.println("Backup:  " + backup);
        System.out.println("Query:   " + query);
        System.out.println();

        String neo4jUri = PropertiesLoader.requireString(p, "neo4j.uri");
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.requireString(p, "neo4j.password");
        RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
             Neo4jGraphStore store = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword)) {

            reader.ensureVectorIndex();
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            GraphReindexer reindexer = new GraphReindexer(
                repoRootPath, DEFAULT_SOURCE_ROOTS,
                DEFAULT_MAX_TOKENS_PER_CHUNK, store, embeddings);

            int exit;
            if (mode == Mode.SAFELOOP) {
                exit = runSafeLoop(p, reader, retriever, reindexer, repoRootPath, query, dryRun, backup);
            } else {
                exit = runRefactor(p, reader, retriever, reindexer, repoRootPath, query, dryRun, backup);
            }
            System.exit(exit);

        } catch (Exception e) {
            System.err.println("ERROR: " + Errors.format(e));
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int runSafeLoop(Properties p,
                                   Neo4jGraphReader reader,
                                   HybridRetriever retriever,
                                   GraphReindexer reindexer,
                                   Path repoRoot,
                                   String query,
                                   boolean dryRun,
                                   boolean backup) throws Exception {
        SafeLoopConfig cfg = SafeLoopConfig.fromProperties(p)
            .withRepoRoot(repoRoot.toString())
            .withApply(true)
            .withDryRun(dryRun)
            .withBackup(backup);

        try (SafeLoopBundle bundle = SafeLoopBundle.build(reader, retriever, cfg, reindexer)) {
            SafeLoopResult result = bundle.loop().run(query);

            System.out.println();
            System.out.println(result.toDisplayString());
            if (!result.getApplyReport().isEmpty()) {
                System.out.println("── Apply Report ─────────────────────────────────────────");
                System.out.println(result.getApplyReport());
            }
            return result.isSafe() && !result.getAppliedFiles().isEmpty() ? 0 : 1;
        }
    }

    private static int runRefactor(Properties p,
                                   Neo4jGraphReader reader,
                                   HybridRetriever retriever,
                                   GraphReindexer reindexer,
                                   Path repoRoot,
                                   String query,
                                   boolean dryRun,
                                   boolean backup) {
        RefactorConfig cfg = RefactorConfig.fromProperties(p)
            .withRepoRoot(repoRoot.toString())
            .withApply(true)
            .withDryRun(dryRun)
            .withBackup(backup);

        try (ChatService chat = new LmStudioChatService(
                cfg.getChatUrl(), cfg.getChatModel(),
                cfg.getTemperature(), cfg.getTopP(), cfg.getMaxTokens())) {

            AstDiffEngine diffEngine = new AstDiffEngine();
            DiffScorer diffScorer = new DiffScorer(reader);
            RefactorLoop loop = new RefactorLoop(retriever, reader, chat, cfg, diffEngine, diffScorer, reindexer);
            RefactorLoop.RefactorResult result = loop.run(query);

            System.out.println();
            System.out.println(result.toDisplayString());
            if (!result.getApplyReport().isEmpty()) {
                System.out.println("── Apply Report ─────────────────────────────────────────");
                System.out.println(result.getApplyReport());
            }
            return result.getAppliedFiles().isEmpty() ? 1 : 0;
        } catch (Exception e) {
            System.err.println("ERROR: " + Errors.format(e));
            e.printStackTrace();
            return 1;
        }
    }
}
