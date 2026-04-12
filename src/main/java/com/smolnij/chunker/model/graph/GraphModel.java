package com.smolnij.chunker.model.graph;

import com.smolnij.chunker.model.CodeChunk;

import java.util.*;

/**
 * Aggregated result of the chunking pipeline — contains all graph nodes and edges
 * ready for persistence into Neo4j or any other graph store.
 *
 * <p>This is the single output object that {@code JavaCodeChunker.process()} returns
 * after the full pipeline has run (parse → extract → back-patch → filter).
 */
public class GraphModel {

    // ── Nodes ──
    private final List<CodeChunk> methodNodes = new ArrayList<>();
    private final Map<String, ClassNode> classNodes = new LinkedHashMap<>();   // fqName → ClassNode
    private final Map<String, FieldNode> fieldNodes = new LinkedHashMap<>();   // fqName → FieldNode
    private final Set<String> packageNodes = new LinkedHashSet<>();

    // ── Edges ──
    private final List<GraphEdge> edges = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════════
    // Mutators (used during pipeline construction)
    // ═══════════════════════════════════════════════════════════════

    public void addMethodNode(CodeChunk chunk) {
        methodNodes.add(chunk);
    }

    public void addClassNode(ClassNode classNode) {
        classNodes.put(classNode.getFqName(), classNode);
    }

    public void addFieldNode(FieldNode fieldNode) {
        fieldNodes.put(fieldNode.getFqName(), fieldNode);
    }

    public void addPackage(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            packageNodes.add(packageName);
        }
    }

    public void addEdge(GraphEdge edge) {
        edges.add(edge);
    }

    public void addEdges(Collection<GraphEdge> edgeBatch) {
        edges.addAll(edgeBatch);
    }

    // ═══════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════

    public List<CodeChunk> getMethodNodes() {
        return Collections.unmodifiableList(methodNodes);
    }

    public Map<String, ClassNode> getClassNodes() {
        return Collections.unmodifiableMap(classNodes);
    }

    public Map<String, FieldNode> getFieldNodes() {
        return Collections.unmodifiableMap(fieldNodes);
    }

    public Set<String> getPackageNodes() {
        return Collections.unmodifiableSet(packageNodes);
    }

    public List<GraphEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    /**
     * Get edges filtered by type.
     */
    public List<GraphEdge> getEdgesByType(GraphEdge.EdgeType type) {
        return edges.stream()
            .filter(e -> e.getType() == type)
            .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // Statistics
    // ═══════════════════════════════════════════════════════════════

    public String getSummary() {
        Map<GraphEdge.EdgeType, Long> edgeCounts = new LinkedHashMap<>();
        for (GraphEdge.EdgeType t : GraphEdge.EdgeType.values()) {
            long count = edges.stream().filter(e -> e.getType() == t).count();
            if (count > 0) edgeCounts.put(t, count);
        }

        return String.format(
            "GraphModel { packages=%d, classes=%d, interfaces=%d, fields=%d, methods=%d, edges=%d %s }",
            packageNodes.size(),
            classNodes.values().stream().filter(c -> !c.isInterface()).count(),
            classNodes.values().stream().filter(ClassNode::isInterface).count(),
            fieldNodes.size(),
            methodNodes.size(),
            edges.size(),
            edgeCounts
        );
    }
}

