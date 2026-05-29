package com.smolnij.chunker.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A view of a retrieved subgraph: the selected node IDs plus the
 * directed edges induced among them.
 *
 * <p>Intended to be rendered as a topology block that precedes the
 * flat chunk listing so the LLM can see the connected shape of the
 * retrieved context rather than treating each chunk in isolation.
 */
public final class SubgraphView {

    private final Set<String> nodeIds;
    private final List<PathEdge> edges;

    public SubgraphView(Set<String> nodeIds, List<PathEdge> edges) {
        this.nodeIds = Set.copyOf(nodeIds == null ? Collections.emptySet() : nodeIds);
        this.edges = List.copyOf(edges == null ? Collections.emptyList() : edges);
    }

    public Set<String> getNodeIds() { return nodeIds; }
    public List<PathEdge> getEdges() { return edges; }

    /**
     * Render the subgraph as an edge list. Edges are sorted deterministically.
     * The anchor (if present in {@code nodeIds}) is marked inline.
     * Truncates at {@code maxEdges} with a footer when exceeded.
     *
     * @param anchorId the anchor chunkId to highlight, or {@code null}
     * @param maxEdges truncation cap (e.g. {@code RetrievalConfig.getMaxTopologyEdges()})
     */
    public String renderTopology(String anchorId, int maxEdges) {
        StringBuilder sb = new StringBuilder();
        if (edges.isEmpty()) {
            sb.append("(no call edges among ").append(nodeIds.size()).append(" selected nodes)");
            return sb.toString();
        }

        List<PathEdge> sorted = new ArrayList<>(edges);
        Collections.sort(sorted);

        // Dedupe (src, tgt, type) triples while preserving sort order.
        LinkedHashSet<String> seen = new LinkedHashSet<>(sorted.size());
        List<PathEdge> unique = new ArrayList<>(sorted.size());
        for (PathEdge e : sorted) {
            String key = e.getSourceId() + '\u0001' + e.getTargetId() + '\u0001' + e.getRelType();
            if (seen.add(key)) {
                unique.add(e);
            }
        }

        int limit = Math.min(unique.size(), Math.max(1, maxEdges));
        for (int i = 0; i < limit; i++) {
            PathEdge e = unique.get(i);
            sb.append(formatEdgeWithAnchor(e, anchorId)).append('\n');
        }

        int omitted = unique.size() - limit;
        if (omitted > 0) {
            sb.append("... (+").append(omitted).append(" more edges omitted)\n");
        }
        sb.append("(").append(unique.size()).append(" edge")
          .append(unique.size() == 1 ? "" : "s")
          .append(" among ").append(nodeIds.size()).append(" nodes)");
        return sb.toString();
    }

    private String formatEdgeWithAnchor(PathEdge e, String anchorId) {
        String src = e.getSourceId();
        String tgt = e.getTargetId();
        if (anchorId != null) {
            if (src.equals(anchorId)) src = src + " (ANCHOR)";
            if (tgt.equals(anchorId)) tgt = tgt + " (ANCHOR)";
        }
        if (e.getDirection() == PathEdge.Direction.OUT) {
            return src + " -[" + e.getRelType() + "]-> " + tgt;
        }
        return tgt + " -[" + e.getRelType() + "]-> " + src;
    }
}
