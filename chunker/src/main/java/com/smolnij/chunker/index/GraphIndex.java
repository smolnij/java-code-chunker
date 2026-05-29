package com.smolnij.chunker.index;

import com.smolnij.chunker.model.CodeChunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory graph index for graph-aware retrieval.
 *
 * <p><b>Nodes:</b> packages, classes, methods (chunks)
 * <br><b>Edges:</b> package→class, class→method, method→method (calls/calledBy)
 *
 * <p>Supports:
 * <ul>
 *   <li>Hierarchical traversal (package → class → method)</li>
 *   <li>Call-graph–aware context expansion (fetch a method + its callees + callers up to N hops)</li>
 *   <li>Keyword search across chunk metadata</li>
 *   <li>Full graph export for visualization or embedding into a graph DB</li>
 * </ul>
 */
public class GraphIndex {

    // ── Node stores ──
    private final Map<String, CodeChunk> methodNodes = new LinkedHashMap<>();   // chunkId → chunk
    private final Map<String, Set<String>> classToMethods = new LinkedHashMap<>();  // FQ class → chunkIds
    private final Map<String, Set<String>> packageToClasses = new LinkedHashMap<>(); // package → FQ classes

    // ── Edge stores (call graph) ──
    private final Map<String, Set<String>> callsEdges = new LinkedHashMap<>();    // chunkId → callees
    private final Map<String, Set<String>> calledByEdges = new LinkedHashMap<>();  // chunkId → callers

    /**
     * Build the index from a list of processed chunks.
     * Must be called after the chunking pipeline has finished and calledBy edges are back-patched.
     */
    public void buildIndex(List<CodeChunk> chunks) {
        for (CodeChunk chunk : chunks) {
            String chunkId = chunk.getChunkId();
            String classFqn = chunk.getFullyQualifiedClassName();
            String pkg = chunk.getPackageName();

            methodNodes.put(chunkId, chunk);

            classToMethods
                .computeIfAbsent(classFqn, k -> new LinkedHashSet<>())
                .add(chunkId);
            packageToClasses
                .computeIfAbsent(pkg, k -> new LinkedHashSet<>())
                .add(classFqn);

            if (!chunk.getCalls().isEmpty()) {
                callsEdges.put(chunkId, new LinkedHashSet<>(chunk.getCalls()));
            }
            if (!chunk.getCalledBy().isEmpty()) {
                calledByEdges.put(chunkId, new LinkedHashSet<>(chunk.getCalledBy()));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hierarchical queries
    // ═══════════════════════════════════════════════════════════════

    /** Get all indexed packages. */
    public Set<String> getPackages() {
        return packageToClasses.keySet();
    }

    /** Get all classes in a package. */
    public Set<String> getClassesInPackage(String packageName) {
        return packageToClasses.getOrDefault(packageName, Collections.emptySet());
    }

    /** Get all method chunk IDs in a class. */
    public Set<String> getMethodsInClass(String classFqn) {
        return classToMethods.getOrDefault(classFqn, Collections.emptySet());
    }

    /** Get a specific chunk by its ID. */
    public CodeChunk getChunk(String chunkId) {
        return methodNodes.get(chunkId);
    }

    /** Get all indexed chunks. */
    public Collection<CodeChunk> getAllChunks() {
        return methodNodes.values();
    }

    // ═══════════════════════════════════════════════════════════════
    // Graph-aware context expansion
    // ═══════════════════════════════════════════════════════════════

    /**
     * Retrieve a method chunk PLUS all its direct callees and callers,
     * expanded up to {@code depth} hops.
     *
     * <p>This gives the LLM enough context to reason about data flow
     * and control flow around the target method.
     *
     * @param methodFqn the method FQN to center the retrieval on
     * @param depth     how many hops to traverse (1 = direct, 2 = transitive)
     * @return ordered list of chunks: [target, callees..., callers...]
     */
    public List<CodeChunk> getContextExpanded(String methodFqn, int depth) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depthMap = new HashMap<>();

        queue.add(methodFqn);
        depthMap.put(methodFqn, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (visited.contains(current)) continue;
            visited.add(current);

            int currentDepth = depthMap.getOrDefault(current, 0);
            if (currentDepth >= depth) continue;

            // Expand callees
            Set<String> callees = callsEdges.getOrDefault(current, Collections.emptySet());
            for (String callee : callees) {
                if (!visited.contains(callee)) {
                    queue.add(callee);
                    depthMap.putIfAbsent(callee, currentDepth + 1);
                }
            }

            // Expand callers
            Set<String> callers = calledByEdges.getOrDefault(current, Collections.emptySet());
            for (String caller : callers) {
                if (!visited.contains(caller)) {
                    queue.add(caller);
                    depthMap.putIfAbsent(caller, currentDepth + 1);
                }
            }
        }

        return visited.stream()
            .map(methodNodes::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // Search
    // ═══════════════════════════════════════════════════════════════

    /**
     * Search chunks by keyword (method name, class name, or code content).
     */
    public List<CodeChunk> search(String keyword) {
        String lower = keyword.toLowerCase();
        return methodNodes.values().stream()
            .filter(chunk ->
                chunk.getMethodName().toLowerCase().contains(lower)
                || chunk.getClassName().toLowerCase().contains(lower)
                || chunk.getCode().toLowerCase().contains(lower)
                || chunk.getChunkId().toLowerCase().contains(lower)
            )
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════
    // Export
    // ═══════════════════════════════════════════════════════════════

    /**
     * Export the full graph structure as a nested map suitable for JSON serialization.
     *
     * <p>Structure:
     * <pre>
     * {
     *   "hierarchy": { "pkg" → { "class" → ["method1", "method2"] } },
     *   "callEdges": { "caller" → ["callee1", "callee2"] },
     *   "calledByEdges": { "callee" → ["caller1", "caller2"] },
     *   "totalPackages": N,
     *   "totalClasses": N,
     *   "totalMethods": N
     * }
     * </pre>
     */
    public Map<String, Object> exportGraph() {
        Map<String, Object> graph = new LinkedHashMap<>();

        // Package hierarchy
        Map<String, Object> hierarchy = new LinkedHashMap<>();
        for (var pkgEntry : packageToClasses.entrySet()) {
            Map<String, Set<String>> classes = new LinkedHashMap<>();
            for (String cls : pkgEntry.getValue()) {
                classes.put(cls, classToMethods.getOrDefault(cls, Collections.emptySet()));
            }
            hierarchy.put(pkgEntry.getKey(), classes);
        }
        graph.put("hierarchy", hierarchy);

        // Call graph edges
        graph.put("callEdges", callsEdges);
        graph.put("calledByEdges", calledByEdges);

        // Stats
        graph.put("totalPackages", packageToClasses.size());
        graph.put("totalClasses", classToMethods.size());
        graph.put("totalMethods", methodNodes.size());

        return graph;
    }
}

