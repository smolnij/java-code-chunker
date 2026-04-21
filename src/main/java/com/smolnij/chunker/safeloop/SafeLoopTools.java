package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.util.*;

/**
 * Graph-coverage enforcer and context tracker for the safe refactoring loop.
 *
 * <p>Unlike {@link RefactorTools} which exposes
 * {@code @Tool}-annotated methods for autonomous LLM invocation, this class
 * is called <b>programmatically</b> by {@link SafeRefactorLoop} to:
 *
 * <ul>
 *   <li>Pre-fetch required callers and callees before the refactoring phase</li>
 *   <li>Expand the graph when the analyzer requests more context</li>
 *   <li>Track which graph nodes have been retrieved (for convergence detection)</li>
 *   <li>Format newly-discovered context for injection into agent memory</li>
 * </ul>
 *
 * <h3>Graph coverage guarantee:</h3>
 * <p>Before refactoring begins, {@link #ensureGraphCoverage(String)} makes sure
 * that at least {@code minCallerDepth} hops of callers and {@code minCalleeDepth}
 * hops of callees have been retrieved for the target method.
 *
 * <h3>Convergence detection:</h3>
 * <p>{@link #hasNewNodes()} returns false when an expansion attempt yields no
 * nodes beyond what's already been seen, signaling the loop should stop.
 */
public class SafeLoopTools {

    private final HybridRetriever retriever;
    private final Neo4jGraphReader graphReader;
    private final SafeLoopConfig config;

    /** All graph node IDs that have been retrieved across all iterations. */
    private final Set<String> retrievedNodeIds = new LinkedHashSet<>();

    /** Chunks fetched in the most recent expansion (for injection into agent). */
    private final Map<String, CodeChunk> lastExpansionChunks = new LinkedHashMap<>();

    /** Flag set by each expansion — true if new nodes were discovered. */
    private boolean lastExpansionHadNewNodes = false;

    /** Total number of graph expansion calls made. */
    private int expansionCount = 0;

    public SafeLoopTools(HybridRetriever retriever,
                         Neo4jGraphReader graphReader,
                         SafeLoopConfig config) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.config = config;
    }

    /** Expose the graph reader so apply-time components can resolve FQNs to files. */
    public Neo4jGraphReader getGraphReader() {
        return graphReader;
    }

    // ═══════════════════════════════════════════════════════════════
    // Initial retrieval
    // ═══════════════════════════════════════════════════════════════

    /**
     * Perform the initial hybrid retrieval for a user query.
     *
     * @param query the natural-language refactoring request
     * @return the retrieval results
     */
    public List<RetrievalResult> initialRetrieve(String query) {
        HybridRetriever.RetrievalResponse response = retriever.retrieve(query);
        List<RetrievalResult> results = response.getResults();

        // Track all retrieved node IDs
        for (RetrievalResult r : results) {
            retrievedNodeIds.add(r.getChunkId());
        }

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // Graph coverage enforcement
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ensure that the minimum graph coverage (callers + callees) has been
     * retrieved for the given target method.
     *
     * <p>This is the "graph-awareness" enforcer — it guarantees the agent
     * has seen enough context for a safe refactoring, even if the LLM
     * didn't request it autonomously.
     *
     * @param targetMethodId the anchor method to ensure coverage for
     * @return formatted context string for any newly-fetched chunks, or empty if nothing new
     */
    public String ensureGraphCoverage(String targetMethodId) {
        expansionCount++;
        lastExpansionChunks.clear();
        lastExpansionHadNewNodes = false;

        String resolvedId = resolveMethodId(targetMethodId);
        if (resolvedId == null) {
            return ""; // Can't enforce coverage if we can't find the method
        }

        Set<String> newIds = new LinkedHashSet<>();

        // ── Ensure callers at configured depth ──
        Map<String, Integer> callerSubgraph = graphReader.expandSubgraph(resolvedId, config.getMinCallerDepth());
        for (String id : callerSubgraph.keySet()) {
            if (!retrievedNodeIds.contains(id)) {
                newIds.add(id);
            }
        }

        // ── Ensure callees at configured depth ──
        Map<String, Integer> calleeSubgraph = graphReader.expandSubgraph(resolvedId, config.getMinCalleeDepth());
        for (String id : calleeSubgraph.keySet()) {
            if (!retrievedNodeIds.contains(id)) {
                newIds.add(id);
            }
        }

        // ── Also include same-class siblings ──
        List<String> siblings = graphReader.getSameClassMethods(resolvedId);
        for (String sibling : siblings) {
            if (!retrievedNodeIds.contains(sibling)) {
                newIds.add(sibling);
            }
        }

        // Cap to avoid context overflow
        if (newIds.size() > config.getMaxChunks()) {
            Set<String> capped = new LinkedHashSet<>();
            int count = 0;
            for (String id : newIds) {
                if (count++ >= config.getMaxChunks()) break;
                capped.add(id);
            }
            newIds = capped;
        }

        return hydrateAndTrack(newIds, "Graph Coverage for " + targetMethodId);
    }

    // ═══════════════════════════════════════════════════════════════
    // Targeted expansion (analyzer-requested)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Expand the graph to retrieve specific methods requested by the analyzer.
     *
     * @param requestedIds method identifiers the analyzer says it needs
     * @return formatted context string for injection into agent memory
     */
    public String expandForAnalyzer(List<String> requestedIds) {
        expansionCount++;
        lastExpansionChunks.clear();
        lastExpansionHadNewNodes = false;

        Set<String> newIds = new LinkedHashSet<>();

        for (String requestedId : requestedIds) {
            // Try to resolve the method ID
            String resolved = resolveMethodId(requestedId);
            if (resolved != null && !retrievedNodeIds.contains(resolved)) {
                newIds.add(resolved);

                // Also expand 1 hop from the resolved method to get its neighbourhood
                Map<String, Integer> neighbourhood = graphReader.expandSubgraph(resolved, 1);
                for (String neighbourId : neighbourhood.keySet()) {
                    if (!retrievedNodeIds.contains(neighbourId)) {
                        newIds.add(neighbourId);
                    }
                }
            }

            // Cap total new IDs
            if (newIds.size() >= config.getMaxChunks()) break;
        }

        return hydrateAndTrack(newIds, "Analyzer-requested expansion");
    }

    // ═══════════════════════════════════════════════════════════════
    // Convergence / tracking
    // ═══════════════════════════════════════════════════════════════

    /**
     * @return true if the most recent expansion discovered any new graph nodes
     */
    public boolean hasNewNodes() {
        return lastExpansionHadNewNodes;
    }

    /**
     * @return the set of all node IDs retrieved across all iterations
     */
    public Set<String> getRetrievedNodeIds() {
        return Collections.unmodifiableSet(retrievedNodeIds);
    }

    /**
     * @return the total number of unique nodes retrieved
     */
    public int getTotalNodesRetrieved() {
        return retrievedNodeIds.size();
    }

    /**
     * @return total graph expansion calls made
     */
    public int getExpansionCount() {
        return expansionCount;
    }

    /**
     * @return chunks from the most recent expansion (for inspection/testing)
     */
    public Map<String, CodeChunk> getLastExpansionChunks() {
        return Collections.unmodifiableMap(lastExpansionChunks);
    }

    // ═══════════════════════════════════════════════════════════════
    // Anchor detection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Try to identify the primary target method from a set of retrieval results.
     *
     * @param results the initial retrieval results
     * @return the chunkId of the anchor method, or null if none found
     */
    public String findAnchorId(List<RetrievalResult> results) {
        for (RetrievalResult r : results) {
            if (r.isAnchor()) {
                return r.getChunkId();
            }
        }
        // Fallback: first result
        return results.isEmpty() ? null : results.get(0).getChunkId();
    }

    // ═══════════════════════════════════════════════════════════════
    // AST Diff support (public delegates for SafeRefactorLoop)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve a method name to its graph node ID for AST diff comparison.
     * Public delegate of the internal {@link #resolveMethodId(String)}.
     *
     * @param methodName simple method name, e.g. "createUser"
     * @return the resolved chunkId, or null if not found
     */
    public String resolveMethodForDiff(String methodName) {
        return resolveMethodId(methodName);
    }

    /**
     * Fetch method chunks by IDs for AST diff comparison.
     * Delegates to {@link Neo4jGraphReader#fetchMethodChunks(java.util.Collection)}.
     *
     * @param chunkIds the set of chunkIds to fetch
     * @return map of chunkId → CodeChunk
     */
    public Map<String, CodeChunk> fetchChunksForDiff(Set<String> chunkIds) {
        return graphReader.fetchMethodChunks(chunkIds);
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve a method identifier to its graph node ID.
     * Tries exact match, then class#method, then simple name.
     */
    private String resolveMethodId(String methodId) {
        if (methodId == null || methodId.isBlank()) return null;

        // Try exact match
        String resolved = graphReader.findMethodExact(methodId);
        if (resolved != null) return resolved;

        // Strip to simple name
        String simpleName = methodId;
        if (simpleName.contains("#")) {
            simpleName = simpleName.substring(simpleName.indexOf('#') + 1);
        } else if (simpleName.contains(".")) {
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
        }
        // Strip parameters
        int parenIdx = simpleName.indexOf('(');
        if (parenIdx >= 0) {
            simpleName = simpleName.substring(0, parenIdx);
        }

        return graphReader.findMethodExact(simpleName);
    }

    /**
     * Hydrate a set of new node IDs into CodeChunks, track them,
     * and format them as LLM-friendly context text.
     */
    private String hydrateAndTrack(Set<String> newIds, String label) {
        if (newIds.isEmpty()) {
            return "";
        }

        Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(newIds);
        if (chunks.isEmpty()) {
            return "";
        }

        // Track all fetched IDs
        retrievedNodeIds.addAll(chunks.keySet());
        lastExpansionChunks.putAll(chunks);
        lastExpansionHadNewNodes = true;

        // Format for LLM consumption
        return formatChunksForAgent(chunks, label);
    }

    /**
     * Format chunks as LLM-friendly text for injection into agent memory.
     */
    private String formatChunksForAgent(Map<String, CodeChunk> chunks, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Additional Context: ").append(label).append(" ===\n");
        sb.append("Retrieved ").append(chunks.size()).append(" additional methods:\n\n");

        for (Map.Entry<String, CodeChunk> entry : chunks.entrySet()) {
            CodeChunk c = entry.getValue();

            sb.append("── ").append(c.getClassName()).append(".").append(c.getMethodName())
                .append(" ──\n");
            sb.append("Signature: ").append(c.getMethodSignature()).append("\n");

            if (!c.getCalls().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", c.getCalls())).append("\n");
            }
            if (!c.getCalledBy().isEmpty()) {
                sb.append("Called by: ").append(String.join(", ", c.getCalledBy())).append("\n");
            }

            sb.append("```java\n");
            sb.append(c.getCode()).append("\n");
            sb.append("```\n\n");
        }

        return sb.toString();
    }
}

