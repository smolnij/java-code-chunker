package com.smolnij.chunker;

import com.smolnij.chunker.index.GraphIndex;
import com.smolnij.chunker.model.CodeChunk;
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
 * <h3>Usage:</h3>
 * <pre>
 *   java -jar java-code-chunker.jar [repoRoot] [outputDir] [maxTokens]
 *
 *   Arguments (all optional):
 *     repoRoot   — path to the Java repository root (default: current directory)
 *     outputDir  — path to write output files (default: ./chunker-output)
 *     maxTokens  — max tokens per chunk before splitting (default: 512)
 * </pre>
 */
public class ChunkerMain {

    // ── Default tunable parameters ──
    private static final int DEFAULT_MAX_TOKENS_PER_CHUNK = 512;

    public static void main(String[] args) throws IOException {

        // ── Parse CLI arguments ──
        Path repoRoot = Path.of(args.length > 0
            ? args[0]
            : "/home/smola/dev/src/parkingsys/parking-system-main/");

        Path outputDir = Path.of(args.length > 1
            ? args[1]
            : "chunker-output");

        int maxTokens = args.length > 2
            ? Integer.parseInt(args[2])
            : DEFAULT_MAX_TOKENS_PER_CHUNK;

        // Source roots for your multi-module Maven project
        // Add or remove entries to match your repository layout
        List<Path> sourceRoots = List.of(
            Path.of("/src/main/java")
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

        // ── Build the graph index ──
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
        System.out.println();
        System.out.println("Done! Feed chunks_readable.txt to LM-Studio, or embed chunks.json for RAG.");
    }
}

