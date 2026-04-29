package com.smolnij.chunker;

import com.smolnij.chunker.config.PropertiesLoader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * CLI entry point for the Java Code Chunker.
 *
 * <p>Processes a Java repository and writes three output files
 * (chunks.json, graph.json, chunks_readable.txt). Optionally persists the
 * full code graph to Neo4j when {@code neo4j.uri} and {@code neo4j.password}
 * are configured.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -jar java-code-chunker.jar config/chunker.properties
 * </pre>
 */
public class ChunkerMain {

    public static void main(String[] args) throws IOException {
        Properties p = PropertiesLoader.loadOrExit(args, "ChunkerMain", "config/chunker.properties");

        Path repoRoot = Path.of(PropertiesLoader.requireString(p, "chunker.repoRoot"));
        Path outputDir = Path.of(PropertiesLoader.getString(p, "chunker.outputDir", "chunker-output"));
        int maxTokens = PropertiesLoader.getInt(p, "chunker.maxTokens", 512);

        List<String> sourceRootStrings = PropertiesLoader.getList(p, "chunker.sourceRoots",
            List.of("src/main/java", "src/test/java"));
        List<Path> sourceRoots = new ArrayList<>(sourceRootStrings.size());
        for (String s : sourceRootStrings) sourceRoots.add(Path.of(s));

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

        JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokens);
        List<CodeChunk> chunks = chunker.process();

        System.out.println();
        System.out.println("Extracted " + chunks.size() + " non-boilerplate method chunks.");
        System.out.println();

        GraphModel graphModel = chunker.getGraphModel();

        GraphIndex index = new GraphIndex();
        index.buildIndex(chunks);

        Files.createDirectories(outputDir);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        Path chunksFile = outputDir.resolve("chunks.json");
        Files.writeString(chunksFile, gson.toJson(chunks));
        System.out.println("✓ Wrote " + chunksFile.toAbsolutePath());

        Path graphFile = outputDir.resolve("graph.json");
        Files.writeString(graphFile, gson.toJson(index.exportGraph()));
        System.out.println("✓ Wrote " + graphFile.toAbsolutePath());

        Path readableFile = outputDir.resolve("chunks_readable.txt");
        StringBuilder readable = new StringBuilder();
        for (CodeChunk chunk : chunks) {
            readable.append("═".repeat(72)).append("\n");
            readable.append(chunk.toPromptFormat());
            readable.append("\n");
        }
        Files.writeString(readableFile, readable.toString());
        System.out.println("✓ Wrote " + readableFile.toAbsolutePath());

        if (!chunks.isEmpty()) {
            System.out.println();
            System.out.println("── Sample Chunk ─────────────────────────────────────────");
            System.out.println(chunks.get(0).toPromptFormat());
        }

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
        // Neo4j Persistence (optional)
        // ═══════════════════════════════════════════════════════════════
        String neo4jUri = PropertiesLoader.getString(p, "neo4j.uri", null);
        String neo4jUser = PropertiesLoader.getString(p, "neo4j.user", "neo4j");
        String neo4jPassword = PropertiesLoader.getString(p, "neo4j.password", null);
        boolean neo4jClean = PropertiesLoader.getBoolean(p, "neo4j.clean", true);

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
                    System.out.println("⚠ Wiping Neo4j database before import (neo4j.clean=true).");
                    store.cleanAll();
                }
                store.store(graphModel);

                String embeddingUrl = PropertiesLoader.getString(p, "embedding.url", null);
                if (embeddingUrl != null) {
                    RetrievalConfig retrievalConfig = RetrievalConfig.fromProperties(p);
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

                    try (EmbeddingService embeddingService = new LmStudioEmbeddingService(retrievalConfig)) {
                        store.storeEmbeddings(graphModel, embeddingService);
                    }
                } else {
                    System.out.println("ℹ Embedding storage skipped (set embedding.url to enable).");
                }
            } catch (Exception e) {
                System.err.println("ERROR: Failed to persist to Neo4j: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println();
            System.out.println("ℹ Neo4j export skipped (set neo4j.uri and neo4j.password to enable).");
        }

        System.out.println();
        System.out.println("Done! Feed chunks_readable.txt to LM-Studio, or embed chunks.json for RAG.");
    }
}
