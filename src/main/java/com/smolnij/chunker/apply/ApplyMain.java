package com.smolnij.chunker.apply;

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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for apply-enabled refactoring.
 *
 * <p>Wraps {@link com.smolnij.chunker.safeloop.SafeRefactorLoop} (and
 * {@link RefactorLoop}) with {@code apply=true} so the proposed changes are
 * actually written to disk after the SAFE verdict. Uses {@link PatchApplier}
 * under the hood.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.smolnij.chunker.apply.ApplyMain \
 *       &lt;repoRoot&gt; "&lt;query&gt;" [--mode safeloop|refactor] [--dry-run] [--no-backup]
 * </pre>
 *
 * <p>Default: {@code --mode safeloop}, dry-run off (writes happen), backups on.
 */
public class ApplyMain {

    public static final String NEO4J_DEFAULT_URL = "bolt://localhost:7687";
    public static final String NEO4J_DEFAULT_USER = "neo4j";
    public static final String NEO4J_DEFAULT_PASSWORD = "12345678";

    enum Mode { SAFELOOP, REFACTOR }

    /** Source roots fed to the post-apply Neo4j re-indexer; mirror {@code ChunkerMain}. */
    private static final List<Path> DEFAULT_SOURCE_ROOTS = List.of(
        Path.of("src/main/java"),
        Path.of("src/test/java")
    );

    /** Default token budget — must match {@code ChunkerMain.DEFAULT_MAX_TOKENS_PER_CHUNK}. */
    private static final int DEFAULT_MAX_TOKENS_PER_CHUNK = 512;

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
            return;
        }

        String repoRoot = args[0];
        String query = args[1];
        Mode mode = Mode.SAFELOOP;
        boolean dryRun = false;
        boolean backup = true;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--mode" -> {
                    if (i + 1 < args.length) {
                        try { mode = Mode.valueOf(args[++i].toUpperCase()); }
                        catch (IllegalArgumentException e) {
                            System.err.println("Unknown mode: " + args[i]);
                            System.exit(1);
                            return;
                        }
                    }
                }
                case "--dry-run" -> dryRun = true;
                case "--no-backup" -> backup = false;
                default -> {
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
                    return;
                }
            }
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

        String neo4jUri = cfg("NEO4J_URI", "neo4j.uri", NEO4J_DEFAULT_URL);
        String neo4jUser = cfg("NEO4J_USER", "neo4j.user", NEO4J_DEFAULT_USER);
        String neo4jPassword = cfg("NEO4J_PASSWORD", "neo4j.password", NEO4J_DEFAULT_PASSWORD);
        RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();

        try (Neo4jGraphReader reader = new Neo4jGraphReader(neo4jUri, neo4jUser, neo4jPassword, retrievalConfig);
             EmbeddingService embeddings = new LmStudioEmbeddingService(retrievalConfig);
             Neo4jGraphStore store = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword)) {

            reader.ensureVectorIndex();
            HybridRetriever retriever = new HybridRetriever(reader, embeddings, retrievalConfig);

            // Build the post-apply re-indexer so subsequent retrievals see fresh code.
            // The reindexer catches embedding failures and continues without vector
            // refresh, so passing the embedding service is safe even when EMBEDDING_URL
            // isn't configured.
            GraphReindexer reindexer = new GraphReindexer(
                repoRootPath, DEFAULT_SOURCE_ROOTS,
                DEFAULT_MAX_TOKENS_PER_CHUNK, store, embeddings);

            int exit;
            if (mode == Mode.SAFELOOP) {
                exit = runSafeLoop(reader, retriever, reindexer, repoRootPath, query, dryRun, backup);
            } else {
                exit = runRefactor(reader, retriever, reindexer, repoRootPath, query, dryRun, backup);
            }
            System.exit(exit);

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int runSafeLoop(Neo4jGraphReader reader,
                                   HybridRetriever retriever,
                                   GraphReindexer reindexer,
                                   Path repoRoot,
                                   String query,
                                   boolean dryRun,
                                   boolean backup) throws Exception {
        SafeLoopConfig cfg = SafeLoopConfig.fromEnvironment()
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

    private static int runRefactor(Neo4jGraphReader reader,
                                   HybridRetriever retriever,
                                   GraphReindexer reindexer,
                                   Path repoRoot,
                                   String query,
                                   boolean dryRun,
                                   boolean backup) {
        RefactorConfig cfg = RefactorConfig.fromEnvironment()
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
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private static void printUsage() {
        System.err.println("Usage: ApplyMain <repoRoot> \"<query>\" [--mode safeloop|refactor] [--dry-run] [--no-backup]");
        System.err.println();
        System.err.println("Required env / sysprops: NEO4J_URI, NEO4J_PASSWORD, LLM_CHAT_URL, EMBEDDING_URL");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  ApplyMain /home/me/repo \"Rename Util.foo to bar\"");
        System.err.println("  ApplyMain /home/me/repo \"...\" --dry-run");
        System.err.println("  ApplyMain /home/me/repo \"...\" --mode refactor --no-backup");
    }

    private static String cfg(String envKey, String sysPropKey, String defaultValue) {
        String v = System.getProperty(sysPropKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return defaultValue;
    }
}
