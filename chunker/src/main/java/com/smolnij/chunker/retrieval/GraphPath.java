package com.smolnij.chunker.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered sequence of nodes connected by directed edges.
 *
 * <p>Invariant: {@code edges.size() == nodes.size() - 1}. Each edge
 * {@code edges[i]} connects {@code nodes[i]} to {@code nodes[i+1]}
 * in call-direction reading order.
 */
public final class GraphPath {

    private static final int ONELINE_MAX_HOPS = 4;

    private final List<String> nodes;
    private final List<PathEdge> edges;

    public GraphPath(List<String> nodes, List<PathEdge> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("GraphPath must have at least one node");
        }
        if (edges == null || edges.size() != nodes.size() - 1) {
            throw new IllegalArgumentException(
                "edges.size() must equal nodes.size() - 1 (nodes=" + nodes.size()
                    + ", edges=" + (edges == null ? "null" : edges.size()) + ")");
        }
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
    }

    public static GraphPath single(String nodeId) {
        return new GraphPath(List.of(nodeId), Collections.emptyList());
    }

    public List<String> getNodes() { return nodes; }
    public List<PathEdge> getEdges() { return edges; }

    public int length() { return edges.size(); }
    public boolean isEmpty() { return edges.isEmpty(); }

    /**
     * Render as a single line for short paths, or one indented edge per line
     * for long paths. A length-0 path (single node) renders as a stable
     * anchor marker since it is only produced when source == target.
     */
    public String render() {
        if (isEmpty()) {
            return "(this is the anchor)";
        }
        if (length() <= ONELINE_MAX_HOPS) {
            StringBuilder sb = new StringBuilder();
            sb.append(nodes.get(0));
            for (int i = 0; i < edges.size(); i++) {
                PathEdge e = edges.get(i);
                String arrow = e.getDirection() == PathEdge.Direction.OUT
                    ? " -[" + e.getRelType() + "]-> "
                    : " <-[" + e.getRelType() + "]- ";
                sb.append(arrow).append(nodes.get(i + 1));
            }
            return sb.toString();
        }
        List<String> lines = new ArrayList<>(edges.size());
        for (int i = 0; i < edges.size(); i++) {
            PathEdge e = edges.get(i);
            String arrow = e.getDirection() == PathEdge.Direction.OUT
                ? " -[" + e.getRelType() + "]-> "
                : " <-[" + e.getRelType() + "]- ";
            lines.add("  " + nodes.get(i) + arrow + nodes.get(i + 1));
        }
        return String.join("\n", lines);
    }

    @Override
    public String toString() {
        return render();
    }
}
