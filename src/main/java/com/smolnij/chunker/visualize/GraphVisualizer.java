package com.smolnij.chunker.visualize;

import com.smolnij.chunker.JavaCodeChunker;
import com.smolnij.chunker.index.GraphIndex;
import com.smolnij.chunker.model.CodeChunk;

import guru.nidi.graphviz.attribute.*;
import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.model.Factory;
import guru.nidi.graphviz.model.MutableGraph;
import guru.nidi.graphviz.model.MutableNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static guru.nidi.graphviz.attribute.Rank.RankDir;
import static guru.nidi.graphviz.model.Factory.*;

/**
 * Visualizes the code chunk graph as a PNG image using Graphviz.
 *
 * <p>Produces a hierarchical graph showing:
 * <ul>
 *   <li>Package nodes (blue folders)</li>
 *   <li>Class nodes (green boxes) nested inside packages via subgraphs</li>
 *   <li>Method nodes (yellow/orange ovals) nested inside classes</li>
 *   <li>Call-graph edges (red dashed arrows between methods)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   java -cp java-code-chunker.jar com.example.chunker.visualize.GraphVisualizer [repoRoot] [outputFile]
 * </pre>
 */
public class GraphVisualizer {

    private final GraphIndex index;

    public GraphVisualizer(GraphIndex index) {
        this.index = index;
    }

    /**
     * Render the full graph (hierarchy + call edges) to a PNG file.
     *
     * @param outputFile the output PNG file path
     * @throws IOException if writing fails
     */
    public void renderToPng(File outputFile) throws IOException {
        renderToFile(outputFile, Format.PNG);
    }

    /**
     * Render the full graph (hierarchy + call edges) to an SVG file.
     *
     * @param outputFile the output SVG file path
     * @throws IOException if writing fails
     */
    public void renderToSvg(File outputFile) throws IOException {
        renderToFile(outputFile, Format.SVG);
    }

    /**
     * Render the graph to a file in the given format.
     */
    private void renderToFile(File outputFile, Format format) throws IOException {
        MutableGraph g = buildGraph();
        Graphviz.fromGraph(g)
                .width(2400)
                .render(format)
                .toFile(outputFile);
    }

    /**
     * Build the Graphviz model from the GraphIndex data.
     */
    private MutableGraph buildGraph() {
        MutableGraph graph = mutGraph("CodeChunkGraph")
                .setDirected(true)
                .graphAttrs().add(
                        Rank.dir(RankDir.TOP_TO_BOTTOM),
                        GraphAttr.splines(GraphAttr.SplineMode.ORTHO),
                        Attributes.attr("fontname", "Arial"),
                        Attributes.attr("fontsize", 12),
                        Attributes.attr("nodesep", "0.6"),
                        Attributes.attr("ranksep", "1.0"),
                        Attributes.attr("compound", true)
                )
                .nodeAttrs().add(
                        Attributes.attr("fontname", "Arial"),
                        Attributes.attr("fontsize", 10)
                );

        // ── Keep track of method nodes by chunkId for call-graph edge wiring ──
        Map<String, MutableNode> methodNodeMap = new LinkedHashMap<>();

        int pkgIndex = 0;
        for (String pkg : index.getPackages()) {
            // Create a subgraph cluster for each package
            MutableGraph pkgCluster = mutGraph("cluster_pkg_" + pkgIndex++)
                    .setDirected(true)
                    .graphAttrs().add(
                            Label.of("📦 " + pkg),
                            Attributes.attr("style", "filled"),
                            Attributes.attr("fillcolor", "#E8F0FE"),
                            Attributes.attr("color", "#4285F4"),
                            Attributes.attr("fontsize", 14),
                            Attributes.attr("fontcolor", "#1A237E"),
                            Attributes.attr("penwidth", 2)
                    );

            int clsIndex = 0;
            for (String cls : index.getClassesInPackage(pkg)) {
                // Create a subgraph cluster for each class
                String simpleClassName = cls.contains(".")
                        ? cls.substring(cls.lastIndexOf('.') + 1)
                        : cls;

                MutableGraph clsCluster = mutGraph("cluster_cls_" + pkgIndex + "_" + clsIndex++)
                        .setDirected(true)
                        .graphAttrs().add(
                                Label.of("📄 " + simpleClassName),
                                Attributes.attr("style", "filled"),
                                Attributes.attr("fillcolor", "#E6F4EA"),
                                Attributes.attr("color", "#34A853"),
                                Attributes.attr("fontsize", 12),
                                Attributes.attr("fontcolor", "#1B5E20"),
                                Attributes.attr("penwidth", 1.5)
                        );

                Set<String> methods = index.getMethodsInClass(cls);
                for (String methodId : methods) {
                    CodeChunk chunk = index.getChunk(methodId);
                    String methodLabel = chunk != null ? chunk.getMethodName() : methodId;

                    // Build a descriptive label
                    String nodeLabel = methodLabel;
                    if (chunk != null) {
                        nodeLabel += "\n(" + chunk.getTokenCount() + " tokens"
                                + ", L" + chunk.getStartLine() + "-" + chunk.getEndLine() + ")";
                    }

                    MutableNode methodNode = mutNode(methodId)
                            .add(Label.of(nodeLabel))
                            .add(Shape.ELLIPSE)
                            .add(Attributes.attr("style", "filled"))
                            .add(Attributes.attr("fillcolor", "#FFF3E0"))
                            .add(Attributes.attr("color", "#FB8C00"))
                            .add(Attributes.attr("fontsize", 9));

                    clsCluster.add(methodNode);
                    methodNodeMap.put(methodId, methodNode);
                }

                pkgCluster.add(clsCluster);
            }

            graph.add(pkgCluster);
        }

        // ── Add call-graph edges (method → method) ──
        for (CodeChunk chunk : index.getAllChunks()) {
            String callerId = chunk.getChunkId();
            MutableNode callerNode = methodNodeMap.get(callerId);
            if (callerNode == null) continue;

            for (String calleeId : chunk.getCalls()) {
                MutableNode calleeNode = methodNodeMap.get(calleeId);
                if (calleeNode != null) {
                    graph.add(callerNode.addLink(
                            Factory.to(calleeNode)
                                    .with(Color.RED, Style.DASHED,
                                            Attributes.attr("penwidth", 1.2),
                                            Attributes.attr("constraint", false))
                    ));
                }
            }
        }

        return graph;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Standalone entry point
    // ═══════════════════════════════════════════════════════════════════

    /**
     * CLI entry point. Parses a Java repo, builds the graph, and renders it to PNG.
     *
     * <pre>
     *   Arguments (all optional):
     *     repoRoot   — path to the Java repository root (default: current directory)
     *     outputFile — output image path (default: ./chunker-output/graph.png)
     *     maxTokens  — max tokens per chunk (default: 512)
     * </pre>
     */
    public static void main(String[] args) throws IOException {

        Path repoRoot = Path.of(args.length > 0 ? args[0] : ".");
//        Path repoRoot = Path.of(args.length > 0 ? args[0] : "C:\\dev\\src\\6529_GL_COMMERCE_GPM_COMMERCE-FILE-MANAGEMENT");
        String outputPath = args.length > 1 ? args[1] : "chunker-output/graph.svg";
        int maxTokens = args.length > 2 ? Integer.parseInt(args[2]) : 512;

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Java Code Chunker — Graph Visualizer                ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Repository: " + repoRoot.toAbsolutePath());
        System.out.println("Output:     " + Path.of(outputPath).toAbsolutePath());
        System.out.println("Max tokens: " + maxTokens);
        System.out.println();

        // ── Detect source roots automatically ──
        List<Path> sourceRoots = detectSourceRoots(repoRoot);
        System.out.println("Source roots: " + sourceRoots);
        System.out.println();

        // ── Run the chunking pipeline ──
        JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokens);
        List<CodeChunk> chunks = chunker.process();

        System.out.println();
        System.out.println("Extracted " + chunks.size() + " non-boilerplate method chunks.");

        // ── Build the graph index ──
        GraphIndex index = new GraphIndex();
        index.buildIndex(chunks);

        // ── Render ──
        File outputFile = new File(outputPath);
        outputFile.getParentFile().mkdirs();

        GraphVisualizer visualizer = new GraphVisualizer(index);

        if (outputPath.toLowerCase().endsWith(".svg")) {
            visualizer.renderToSvg(outputFile);
        } else {
            visualizer.renderToPng(outputFile);
        }

        System.out.println();
        System.out.println("✓ Graph image written to: " + outputFile.getAbsolutePath());
        System.out.println();

        // ── Print summary ──
        System.out.println("── Summary ──────────────────────────────────────────────");
        System.out.println("Packages: " + index.getPackages().size());
        int totalClasses = 0;
        int totalMethods = 0;
        for (String pkg : index.getPackages()) {
            for (String cls : index.getClassesInPackage(pkg)) {
                totalClasses++;
                totalMethods += index.getMethodsInClass(cls).size();
            }
        }
        System.out.println("Classes:  " + totalClasses);
        System.out.println("Methods:  " + totalMethods);
        System.out.println();
        System.out.println("Open the generated image to inspect the hierarchy and call graph.");
    }

    /**
     * Auto-detect source roots by looking for common Maven/Gradle layouts.
     */
    private static List<Path> detectSourceRoots(Path repoRoot) {
        List<Path> roots = new ArrayList<>();
        String[] candidates = {
                "src/main/java",
                "src/test/java",
                "src/main/groovy",
                "src/test/groovy"
        };

        Path absRoot = repoRoot.toAbsolutePath().normalize();

        // Check top-level candidates
        for (String candidate : candidates) {
            if (Files.isDirectory(absRoot.resolve(candidate))) {
                roots.add(Path.of(candidate));
            }
        }

        // Check for multi-module projects (one level deep)
        try {
            Files.list(absRoot)
                    .filter(Files::isDirectory)
                    .forEach(moduleDir -> {
                        String moduleName = moduleDir.getFileName().toString();
                        for (String candidate : candidates) {
                            Path full = moduleDir.resolve(candidate);
                            if (Files.isDirectory(full)) {
                                roots.add(Path.of(moduleName, candidate));
                            }
                        }
                    });
        } catch (IOException e) {
            // ignore
        }

        // Fallback: use repo root itself
        if (roots.isEmpty()) {
            roots.add(Path.of("."));
        }

        return roots;
    }
}

