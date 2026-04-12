package com.smolnij.chunker;

import com.smolnij.chunker.index.GraphIndex;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.GraphModel;
import com.smolnij.chunker.retrieval.EmbeddingService;
import com.smolnij.chunker.retrieval.LmStudioEmbeddingService;
import com.smolnij.chunker.retrieval.RetrievalConfig;
import com.smolnij.chunker.store.Neo4jGraphStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI entry point for the Java Code Chunker.
 *
 * <p>Processes a Java repository and writes three output files:
 * <ul>
 *   <li><b>chunks.json</b> — all method chunks with call graph edges (for embedding / vector DB)</li>
 *   <li><b>graph.json</b> — the hierarchical + call graph structure (for graph DB / visualization)</li>
 *   <li><b>chunks_readable.txt</b> — human/LLM-readable prompt-formatted chunks (for direct ingestion)</li>
 * </ul>
 *
 * <p>Optionally persists the full code graph to Neo4j when connection parameters are provided.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -jar java-code-chunker.jar [repoRoot] [outputDir] [maxTokens]
 *
 *   Arguments (all optional):
 *     repoRoot   — path to the Java repository root (default: current directory)
 *     outputDir  — path to write output files (default: ./chunker-output)
 *     maxTokens  — max tokens per chunk before splitting (default: 512)
 *
 *   Neo4j options (via environment variables or system properties):
 *     NEO4J_URI      / -Dneo4j.uri      — bolt URI   (e.g. bolt://localhost:7687)
 *     NEO4J_USER     / -Dneo4j.user     — username   (default: neo4j)
 *     NEO4J_PASSWORD / -Dneo4j.password — password
 *     NEO4J_CLEAN    / -Dneo4j.clean    — if "true", wipe the database before import
 * </pre>
 */
public class ChunkerMain {

    // ── Default tunable parameters ──
    private static final int DEFAULT_MAX_TOKENS_PER_CHUNK = 512;
    public static final String NEO4J_DEFAULT_URL = "bolt://localhost:7687";
    public static final String NEO4J_DEFAULT_USER = "neo4j";
    public static final String NEO4J_DEFAULT_PASSWORD = "12345678";

    public static void main(String[] args) throws IOException {

        // ── Parse CLI arguments ──
        Path repoRoot = Path.of(args.length > 0
            ? args[0]
            : "/home/smola/dev/src/AI_tools/java-code-chunker");

        Path outputDir = Path.of(args.length > 1
            ? args[1]
            : "chunker-output");

        int maxTokens = args.length > 2
            ? Integer.parseInt(args[2])
            : DEFAULT_MAX_TOKENS_PER_CHUNK;

        // Source roots for your multi-module Maven project
        // Add or remove entries to match your repository layout
        List<Path> sourceRoots = List.of(
            Path.of("src/main/java"),
            Path.of("src/main/java"),
            Path.of("src/test/java"),
            Path.of("src/test/java")
        );

        // ── Banner ──
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Java Code Chunker for LM-Studio                    ║");
        System.out.println("║  Graph-Aware Hierarchical Indexing                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Repository:   " + repoRoot.toAbsolutePath());
        System.out.println("Output:       " + outputDir.toAbsolutePath());
        System.out.println("Max tokens:   " + maxTokens);
        System.out.println("Source roots: " + sourceRoots);
        System.out.println();

        // ── Run the chunking pipeline ──
        JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokens);
        List<CodeChunk> chunks = chunker.process();

        System.out.println();
        System.out.println("Extracted " + chunks.size() + " non-boilerplate method chunks.");
        System.out.println();

        // ── Get the graph model ──
        GraphModel graphModel = chunker.getGraphModel();

        // ── Build the graph index (for legacy JSON export) ──
        GraphIndex index = new GraphIndex();
        index.buildIndex(chunks);

        // ── Write output files ──
        Files.createDirectories(outputDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        // 1. chunks.json — full structured data for programmatic use / embedding
        Path chunksFile = outputDir.resolve("chunks.json");
        Files.writeString(chunksFile, gson.toJson(chunks));
        System.out.println("✓ Wrote " + chunksFile.toAbsolutePath());

        // 2. graph.json — the hierarchical graph + call edges
        Path graphFile = outputDir.resolve("graph.json");
        Files.writeString(graphFile, gson.toJson(index.exportGraph()));
        System.out.println("✓ Wrote " + graphFile.toAbsolutePath());

        // 3. chunks_readable.txt — prompt-formatted for direct LLM ingestion
        Path readableFile = outputDir.resolve("chunks_readable.txt");
        StringBuilder readable = new StringBuilder();
        for (CodeChunk chunk : chunks) {
            readable.append("═".repeat(72)).append("\n");
            readable.append(chunk.toPromptFormat());
            readable.append("\n");
        }
        Files.writeString(readableFile, readable.toString());
        System.out.println("✓ Wrote " + readableFile.toAbsolutePath());

        // ── Print a sample chunk ──
        if (!chunks.isEmpty()) {
            System.out.println();
            System.out.println("── Sample Chunk ─────────────────────────────────────────");
            System.out.println(chunks.get(0).toPromptFormat());
        }

        // ── Print graph summary ──
        System.out.println();
        System.out.println("── Graph Summary ────────────────────────────────────────");
        System.out.println("Packages:  " + index.getPackages().size());

        int totalClasses = 0;
        int totalMethods = 0;

        for (String pkg : index.getPackages()) {
            System.out.println("  📦 " + pkg);
            for (String cls : index.getClassesInPackage(pkg)) {
                totalClasses++;
                int methodCount = index.getMethodsInClass(cls).size();
                totalMethods += methodCount;
                System.out.println("    📄 " + cls + " (" + methodCount + " methods)");
                for (String method : index.getMethodsInClass(cls)) {
                    CodeChunk c = index.getChunk(method);
                    int callCount = c != null ? c.getCalls().size() : 0;
                    int calledByCount = c != null ? c.getCalledBy().size() : 0;
                    System.out.println("      ⚡ " + method
                        + " [calls=" + callCount + ", calledBy=" + calledByCount + "]");
                }
            }
        }

        System.out.println();
        System.out.println("Total: " + index.getPackages().size() + " packages, "
            + totalClasses + " classes, "
            + totalMethods + " methods");

        // ═══════════════════════════════════════════════════════════════
        // ── Neo4j Persistence (optional) ──
        // ═══════════════════════════════════════════════════════════════
        String neo4jUri = getConfigValue("NEO4J_URI", "neo4j.uri", NEO4J_DEFAULT_URL);
        String neo4jUser = getConfigValue("NEO4J_USER", "neo4j.user", NEO4J_DEFAULT_USER);
        String neo4jPassword = getConfigValue("NEO4J_PASSWORD", "neo4j.password", NEO4J_DEFAULT_PASSWORD);
        boolean neo4jClean = "true".equalsIgnoreCase(getConfigValue("NEO4J_CLEAN", "neo4j.clean", "false"));

        if (neo4jUri != null && neo4jPassword != null) {
            System.out.println();
            System.out.println("── Neo4j Export ─────────────────────────────────────────");
            System.out.println("URI:   " + neo4jUri);
            System.out.println("User:  " + neo4jUser);
            System.out.println("Clean: " + neo4jClean);
            System.out.println();

            try (Neo4jGraphStore store = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword)) {
                store.initSchema();
                if (neo4jClean) {
                    store.cleanAll();
                }
                store.store(graphModel);

                // ── Vector index & embeddings (optional) ──
                String embeddingUrl = getConfigValue("EMBEDDING_URL", "embedding.url", null);
                if (embeddingUrl != null) {
                    RetrievalConfig retrievalConfig = RetrievalConfig.fromEnvironment();
                    System.out.println();
                    System.out.println("── Embedding & Vector Index ─────────────────────────────");
                    System.out.println("Embedding URL: " + retrievalConfig.getEmbeddingUrl());
                    System.out.println("Model:         " + retrievalConfig.getEmbeddingModel());
                    System.out.println("Dimensions:    " + retrievalConfig.getEmbeddingDimensions());
                    System.out.println();

                    store.initVectorIndex(
                        retrievalConfig.getVectorIndexName(),
                        retrievalConfig.getEmbeddingDimensions()
                    );

                    EmbeddingService embeddingService = new LmStudioEmbeddingService(retrievalConfig);
                    store.storeEmbeddings(graphModel, embeddingService);
                } else {
                    System.out.println("ℹ Embedding storage skipped (set EMBEDDING_URL to enable).");
                }
            } catch (Exception e) {
                System.err.println("ERROR: Failed to persist to Neo4j: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println();
            System.out.println("ℹ Neo4j export skipped (set NEO4J_URI and NEO4J_PASSWORD to enable).");
        }

        System.out.println();
        System.out.println("Done! Feed chunks_readable.txt to LM-Studio, or embed chunks.json for RAG.");
    }

    /**
     * Read a configuration value from system property, then environment variable, then default.
     */
    private static String getConfigValue(String envKey, String sysPropKey, String defaultValue) {
        String value = System.getProperty(sysPropKey);
        if (value != null && !value.isEmpty()) return value;

        value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) return value;

        return defaultValue;
    }
}
