package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.model.CodeChunk;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid Graph-Vector retriever implementing the Graph-RAG pattern:
 *
 * <pre>
 *   query → resolve anchor → graph expansion → collect chunks
 *         → vector re-rank → final context selection
 * </pre>
 *
 * <h3>5-step pipeline:</h3>
 * <ol>
 *   <li><b>Resolve entry point</b> — exact match on method/class name, fallback to vector search</li>
 *   <li><b>Graph expansion</b> — BFS from anchor through CALLS/CALLED_BY/BELONGS_TO edges</li>
 *   <li><b>Collect candidate chunks</b> — hydrate all discovered Method nodes</li>
 *   <li><b>Re-rank</b> — weighted blend of semantic similarity + graph distance + structural bonus</li>
 *   <li><b>Final context selection</b> — top-K with anchor pinned, formatted for LLM</li>
 * </ol>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   try (Neo4jGraphReader reader = new Neo4jGraphReader(uri, user, pw, config)) {
 *       EmbeddingService embeddings = new LmStudioEmbeddingService(config);
 *       HybridRetriever retriever = new HybridRetriever(reader, embeddings, config);
 *
 *       HybridRetriever.RetrievalResponse response = retriever.retrieve("Refactor createUser to async");
 *       System.out.println(response.toLlmContext());
 *   }
 * </pre>
 */
public class HybridRetriever {

    private final Neo4jGraphReader graphReader;
    private final EmbeddingService embeddingService;
    private final RetrievalConfig config;

    public HybridRetriever(Neo4jGraphReader graphReader,
                           EmbeddingService embeddingService,
                           RetrievalConfig config) {
        this.graphReader = graphReader;
        this.embeddingService = embeddingService;
        this.config = config;
    }

    public RetrievalConfig getConfig() {
        return config;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the full 5-step hybrid retrieval pipeline.
     *
     * @param userQuery the natural-language query (e.g. "Refactor createUser to async")
     * @return the retrieval response containing ranked chunks and LLM-ready context
     */
    public RetrievalResponse retrieve(String userQuery) {
        System.out.println("── Hybrid Graph-RAG Retrieval ──────────────────────────");
        System.out.println("Query: " + userQuery);
        System.out.println("Config: " + config);
        System.out.println();

        // ── Step 1: Resolve entry point ──
        String anchorId = resolveEntryPoint(userQuery);
        System.out.println("Step 1 — Anchor: " + (anchorId != null ? anchorId : "(none, using vector-only)"));

        // ── Step 2: Graph expansion ──
        Map<String, Integer> subgraph = expandGraph(anchorId);
        System.out.println("Step 2 — Subgraph: " + subgraph.size() + " nodes");

        // ── Step 3: Collect candidate chunks ──
        Map<String, CodeChunk> candidates = collectCandidates(subgraph, userQuery, anchorId);
        System.out.println("Step 3 — Candidates: " + candidates.size() + " chunks");

        // ── Step 4: Re-rank with vector similarity ──
        List<RetrievalResult> ranked = rerank(userQuery, candidates, subgraph, anchorId);
        System.out.println("Step 4 — Ranked " + ranked.size() + " results");

        // ── Step 5: Final context selection ──
        List<RetrievalResult> selected = selectFinal(ranked, anchorId);
        System.out.println("Step 5 — Selected top-" + selected.size() + " results");

        // ── Enrich with path-from-anchor + induced subgraph topology ──
        SubgraphView subgraphView = enrichWithGraphStructure(selected, anchorId);

        // ── Print ranking debug ──
        System.out.println();
        System.out.println("── Ranking ─────────────────────────────────────────────");
        for (int i = 0; i < selected.size(); i++) {
            System.out.printf("  #%d  %s%n", i + 1, selected.get(i).toDebugString());
        }
        System.out.println();

        return new RetrievalResponse(userQuery, anchorId, selected, subgraphView, config.getMaxTopologyEdges());
    }

    /**
     * Attach a shortest path-from-anchor to each selected result and compute
     * the induced subgraph topology among the selected chunks.
     */
    private SubgraphView enrichWithGraphStructure(List<RetrievalResult> selected, String anchorId) {
        if (selected.isEmpty()) {
            return new SubgraphView(Set.of(), List.of());
        }
        LinkedHashSet<String> selectedIds = new LinkedHashSet<>();
        for (RetrievalResult r : selected) selectedIds.add(r.getChunkId());

        if (anchorId != null) {
            try {
                Map<String, GraphPath> paths =
                    graphReader.getShortestPathsFromAnchor(anchorId, selectedIds, config.getMaxDepth());
                for (RetrievalResult r : selected) {
                    r.setPathFromAnchor(paths.get(r.getChunkId()));
                }
            } catch (Exception e) {
                System.err.println("WARN: Path-from-anchor batch query failed: " + e.getMessage());
            }
        }

        List<PathEdge> induced = List.of();
        try {
            induced = graphReader.getInducedEdges(selectedIds);
        } catch (Exception e) {
            System.err.println("WARN: Induced-edge query failed: " + e.getMessage());
        }
        return new SubgraphView(selectedIds, induced);
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 1 — Resolve entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Try to find the anchor node:
     * 1. Extract likely method/class identifiers from the query
     * 2. Try exact match against Neo4j
     * 3. If no hit, fall back to vector search to find the closest method
     */
    private String resolveEntryPoint(String userQuery) {
        // Extract candidate identifiers from the query
        List<String> candidates = extractIdentifiers(userQuery);

        // Try exact match for each candidate
        for (String candidate : candidates) {
            String found = graphReader.findMethodExact(candidate);
            if (found != null) {
                return found;
            }
        }

        // Fallback: vector search
        try {
            float[] queryEmbedding = embeddingService.embed(userQuery);
            List<String> vectorHits = graphReader.vectorSearch(queryEmbedding, 1);
            if (!vectorHits.isEmpty()) {
                return vectorHits.get(0);
            }
        } catch (Exception e) {
            System.err.println("WARN: Vector search failed (index may not exist yet): " + e.getMessage());
        }

        return null;
    }

    /**
     * Extract likely method/class identifiers from a natural-language query.
     * Looks for camelCase tokens and dot-separated identifiers.
     */
    private List<String> extractIdentifiers(String query) {
        List<String> identifiers = new ArrayList<>();
        String[] tokens = query.split("[\\s,;:!?()\"']+");

        for (String token : tokens) {
            // Skip common English words
            if (token.length() <= 2) continue;
            if (isCommonWord(token)) continue;

            // Check if it looks like an identifier (contains uppercase in middle, or has dots/hashes)
            if (token.contains(".") || token.contains("#") ||
                (!token.equals(token.toLowerCase()) && !token.equals(token.toUpperCase()))) {
                identifiers.add(token);
            }
        }

        return identifiers;
    }

    private boolean isCommonWord(String word) {
        Set<String> common = Set.of(
            "the", "to", "and", "is", "in", "it", "of", "for", "that", "this",
            "with", "from", "refactor", "change", "modify", "update", "make",
            "how", "what", "why", "where", "when", "can", "will", "should",
            "async", "sync", "method", "class", "function", "add", "remove",
            "fix", "bug", "error", "issue", "implement", "create", "delete"
        );
        return common.contains(word.toLowerCase());
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 2 — Graph expansion
    // ═══════════════════════════════════════════════════════════════

    /**
     * BFS-expand from the anchor node, collecting all connected Method nodes
     * within the configured max depth.
     */
    private Map<String, Integer> expandGraph(String anchorId) {
        if (anchorId == null) {
            return new LinkedHashMap<>();
        }

        Map<String, Integer> subgraph = graphReader.expandSubgraph(anchorId, config.getMaxDepth());

        // Also include same-class siblings (even if not directly connected via call edges)
        List<String> siblings = graphReader.getSameClassMethods(anchorId);
        for (String sibling : siblings) {
            subgraph.putIfAbsent(sibling, 1); // Treat same-class as distance 1
        }

        return subgraph;
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3 — Collect candidate chunks
    // ═══════════════════════════════════════════════════════════════

    /**
     * Hydrate all Method nodes in the subgraph into CodeChunk objects.
     * If no anchor was found, fall back to pure vector search.
     */
    private Map<String, CodeChunk> collectCandidates(Map<String, Integer> subgraph,
                                                      String userQuery,
                                                      String anchorId) {
        Map<String, CodeChunk> candidates = new LinkedHashMap<>();

        // Hydrate graph-discovered nodes
        if (!subgraph.isEmpty()) {
            candidates.putAll(graphReader.fetchMethodChunks(subgraph.keySet()));
        }

        // If we have no anchor (or very few graph results), supplement with vector search
        if (anchorId == null || candidates.size() < config.getTopK()) {
            try {
                float[] queryEmbedding = embeddingService.embed(userQuery);
                int needed = config.getVectorSearchK();
                List<String> vectorHits = graphReader.vectorSearch(queryEmbedding, needed);

                // Fetch any new nodes not already in the candidate set
                Set<String> newIds = new LinkedHashSet<>(vectorHits);
                newIds.removeAll(candidates.keySet());
                if (!newIds.isEmpty()) {
                    Map<String, CodeChunk> vectorChunks = graphReader.fetchMethodChunks(newIds);
                    candidates.putAll(vectorChunks);
                }
            } catch (Exception e) {
//                There is no such vector schema index: method_embeddings
                e.printStackTrace();
                System.err.println("WARN: Vector supplement failed: " + e.getMessage());
            }
        }

        return candidates;
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 4 — Re-rank with vector similarity
    // ═══════════════════════════════════════════════════════════════

    /**
     * Score each candidate using the weighted blend of:
     *   - semantic similarity (query ↔ chunk embedding)
     *   - graph distance (closer to anchor = higher)
     *   - structural bonus (same class, same package, high fan-in)
     */
    private List<RetrievalResult> rerank(String userQuery,
                                          Map<String, CodeChunk> candidates,
                                          Map<String, Integer> subgraph,
                                          String anchorId) {
        if (candidates.isEmpty()) return List.of();

        // ── Get anchor context for structural scoring ──
        String anchorClass = "";
        String anchorPackage = "";
        if (anchorId != null) {
            String[] ctx = graphReader.getMethodContext(anchorId);
            if (ctx != null) {
                anchorClass = ctx[0];
                anchorPackage = ctx[1];
            }
        }

        // ── Embed the user query ──
        float[] queryEmbedding = embeddingService.embed(userQuery);

        // ── Try to get stored embeddings for all candidates ──
        Map<String, float[]> storedEmbeddings = graphReader.getStoredEmbeddings(candidates.keySet());

        // ── For any candidates without stored embeddings, compute on the fly ──
        List<String> needsEmbedding = new ArrayList<>();
        for (String id : candidates.keySet()) {
            if (!storedEmbeddings.containsKey(id)) {
                needsEmbedding.add(id);
            }
        }

        if (!needsEmbedding.isEmpty()) {
            List<String> textsToEmbed = needsEmbedding.stream()
                .map(id -> buildEmbeddingText(candidates.get(id)))
                .collect(Collectors.toList());

            try {
                List<float[]> computed = embeddingService.embedBatch(textsToEmbed);
                for (int i = 0; i < needsEmbedding.size(); i++) {
                    storedEmbeddings.put(needsEmbedding.get(i), computed.get(i));
                }
            } catch (Exception e) {
                System.err.println("WARN: On-the-fly embedding failed: " + e.getMessage());
            }
        }

        // ── Score each candidate ──
        // Batch-fetch caller/callee counts to avoid N+1 round-trips to Neo4j
        Map<String, Integer> callerCounts = Map.of();
        Map<String, Integer> calleeCounts = Map.of();
        try {
            callerCounts = graphReader.getCallerCountsBatch(candidates.keySet());
            calleeCounts = graphReader.getCalleeCountsBatch(candidates.keySet());
        } catch (Exception e) {
            System.err.println("WARN: Batched fan-count query failed: " + e.getMessage());
        }

        List<RetrievalResult> results = new ArrayList<>();

        for (Map.Entry<String, CodeChunk> entry : candidates.entrySet()) {
            String chunkId = entry.getKey();
            CodeChunk chunk = entry.getValue();
            RetrievalResult result = new RetrievalResult(chunk);

            // Semantic similarity
            float[] chunkEmbedding = storedEmbeddings.get(chunkId);
            if (chunkEmbedding != null) {
                double sim = LmStudioEmbeddingService.cosineSimilarity(queryEmbedding, chunkEmbedding);
                result.setSemanticSimilarity(Math.max(0.0, sim)); // clamp negatives
            } else {
                result.setSemanticSimilarity(0.0);
            }

            // Graph distance
            int hops = subgraph.getOrDefault(chunkId, config.getMaxDepth() + 1);
            result.setHopDistance(hops);

            // Anchor flag
            result.setAnchor(chunkId.equals(anchorId));

            // Structural bonus
            boolean sameClass = chunk.getFullyQualifiedClassName().equals(anchorClass);
            boolean samePackage = chunk.getPackageName().equals(anchorPackage);
            int callerCount = callerCounts.getOrDefault(chunkId, 0);
            int calleeCount = calleeCounts.getOrDefault(chunkId, 0);

            result.computeStructuralBonus(
                sameClass, samePackage, callerCount, calleeCount, config.getFanInThreshold(),
                config.getSameClassBonus(), config.getSamePackageBonus(),
                config.getFanInBonus(), config.getFanOutBonus()
            );

            // Final blended score
            result.computeFinalScore(
                config.getSemanticWeight(),
                config.getGraphWeight(),
                config.getStructuralWeight()
            );

            results.add(result);
        }

        // Sort descending by final score
        Collections.sort(results);

        return results;
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 5 — Final context selection
    // ═══════════════════════════════════════════════════════════════

    /**
     * Select the top-K results, ensuring the anchor node is always included.
     */
    private List<RetrievalResult> selectFinal(List<RetrievalResult> ranked, String anchorId) {
        if (ranked.isEmpty()) return ranked;

        int topK = config.getTopK();

        // If no anchor, fall back to simple top-K
        if (anchorId == null) {
            List<RetrievalResult> top = new ArrayList<>();
            for (int i = 0; i < ranked.size() && top.size() < topK; i++) {
                RetrievalResult r = ranked.get(i);
                r.setTopologyOnly(false);
                top.add(r);
            }
            return top;
        }

        // Determine anchor context for type neighbors
        String anchorClass = "";
        try {
            String[] ctx = graphReader.getMethodContext(anchorId);
            if (ctx != null) anchorClass = ctx[0];
        } catch (Exception e) {
            // ignore
        }

        // Bucket candidates by relationship to anchor (preserve ranked order)
        List<RetrievalResult> anchorBucket = new ArrayList<>();
        List<RetrievalResult> callersBucket = new ArrayList<>();
        List<RetrievalResult> calleesBucket = new ArrayList<>();
        List<RetrievalResult> typeBucket = new ArrayList<>();
        List<RetrievalResult> others = new ArrayList<>();

        for (RetrievalResult r : ranked) {
            if (r.isAnchor()) {
                anchorBucket.add(r);
                continue;
            }
            CodeChunk c = r.getChunk();
            boolean placed = false;
            try {
                if (c.getCalls() != null && c.getCalls().contains(anchorId)) {
                    callersBucket.add(r);
                    placed = true;
                }
                if (!placed && c.getCalledBy() != null && c.getCalledBy().contains(anchorId)) {
                    calleesBucket.add(r);
                    placed = true;
                }
                if (!placed && anchorClass != null && !anchorClass.isEmpty()
                    && anchorClass.equals(c.getFullyQualifiedClassName())) {
                    typeBucket.add(r);
                    placed = true;
                }
            } catch (Exception e) {
                // defensive: place into others
            }
            if (!placed) others.add(r);
        }

        // Compute integer slot allocation from percentages
        int remainingSlots = topK;
        Map<String, Integer> slots = new LinkedHashMap<>();

        double anchorPct = config.getAnchorPct();
        double callersPct = config.getCallersPct();
        double calleesPct = config.getCalleesPct();
        double typePct = config.getTypeNeighborsPct();
        double topoPct = config.getTopologyFallbackPct();

        Map<String, Double> exact = new LinkedHashMap<>();
        exact.put("anchor", anchorPct * topK);
        exact.put("callers", callersPct * topK);
        exact.put("callees", calleesPct * topK);
        exact.put("type", typePct * topK);
        exact.put("topo", topoPct * topK);

        Map<String, Integer> floor = new LinkedHashMap<>();
        Map<String, Double> frac = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : exact.entrySet()) {
            int f = (int) Math.floor(e.getValue());
            floor.put(e.getKey(), f);
            frac.put(e.getKey(), e.getValue() - f);
            remainingSlots -= f;
        }

        // Ensure anchor gets at least 1 slot if present
        if (anchorBucket.size() > 0 && floor.get("anchor") == 0 && remainingSlots > 0) {
            floor.put("anchor", 1);
            remainingSlots -= 1;
        }

        // Distribute remaining slots by largest fractional remainder (deterministic)
        List<String> orderByFrac = new ArrayList<>(frac.keySet());
        orderByFrac.sort((a, b) -> Double.compare(frac.get(b), frac.get(a)));
        for (String k : orderByFrac) {
            if (remainingSlots <= 0) break;
            floor.put(k, floor.get(k) + 1);
            remainingSlots -= 1;
        }

        // Final slots map
        slots.put("anchor", floor.getOrDefault("anchor", 0));
        slots.put("callers", floor.getOrDefault("callers", 0));
        slots.put("callees", floor.getOrDefault("callees", 0));
        slots.put("type", floor.getOrDefault("type", 0));
        slots.put("topo", floor.getOrDefault("topo", 0));

        List<RetrievalResult> selected = new ArrayList<>();
        List<RetrievalResult> overflow = new ArrayList<>();

        // Helper: pick up to n from bucket into selected; push extras to overflow
        java.util.function.BiConsumer<List<RetrievalResult>, Integer> pickFrom = (bucket, n) -> {
            int take = Math.min(bucket.size(), Math.max(0, n));
            for (int i = 0; i < take; i++) {
                RetrievalResult r = bucket.get(i);
                r.setTopologyOnly(false);
                selected.add(r);
            }
            for (int i = take; i < bucket.size(); i++) {
                overflow.add(bucket.get(i));
            }
        };

        pickFrom.accept(anchorBucket, slots.getOrDefault("anchor", 0));
        pickFrom.accept(callersBucket, slots.getOrDefault("callers", 0));
        pickFrom.accept(calleesBucket, slots.getOrDefault("callees", 0));
        pickFrom.accept(typeBucket, slots.getOrDefault("type", 0));

        // Others and overflow form the topology pool (in ranked order)
        List<RetrievalResult> topologyPool = new ArrayList<>();
        // keep overflow in original ranked order — overflow were appended in ranked order
        topologyPool.addAll(overflow);
        for (RetrievalResult r : others) {
            // skip already-selected
            if (!selected.contains(r)) topologyPool.add(r);
        }

        // Fill remaining slots with topology-only items
        int toFill = topK - selected.size();
        int topoAllocated = Math.min(toFill, slots.getOrDefault("topo", 0));
        // If topoAllocated is zero but we still have space, allow filling from topologyPool
        int fillCount = Math.min(topK - selected.size(), topologyPool.size());
        for (int i = 0; i < fillCount; i++) {
            RetrievalResult r = topologyPool.get(i);
            if (selected.contains(r)) continue;
            r.setTopologyOnly(true);
            selected.add(r);
            if (selected.size() >= topK) break;
        }

        // As a last resort, if still underfilled and there are more ranked items, take them (non-topology)
        if (selected.size() < topK) {
            for (RetrievalResult r : ranked) {
                if (selected.contains(r)) continue;
                r.setTopologyOnly(false);
                selected.add(r);
                if (selected.size() >= topK) break;
            }
        }

        // Ensure anchor is present (if possible)
        boolean anchorPresent = false;
        for (RetrievalResult r : selected) if (r.isAnchor()) anchorPresent = true;
        if (!anchorPresent) {
            for (RetrievalResult r : ranked) {
                if (r.isAnchor()) {
                    // replace last element with anchor
                    if (selected.size() >= topK) selected.set(selected.size() - 1, r);
                    else selected.add(r);
                    r.setTopologyOnly(false);
                    break;
                }
            }
        }

        return selected;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the text to embed for a code chunk.
     * Combines method signature + code for a richer embedding.
     */
    private String buildEmbeddingText(CodeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append(chunk.getClassName()).append(" ");
        sb.append(chunk.getMethodSignature()).append("\n");
        sb.append(chunk.getCode());
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Response object
    // ═══════════════════════════════════════════════════════════════

    /**
     * The complete response from a retrieval query, containing ranked results
     * and a method to format the final LLM context.
     */
    public static class RetrievalResponse {

        private final String query;
        private final String anchorId;
        private final List<RetrievalResult> results;
        private final SubgraphView subgraphView;
        private final int maxTopologyEdges;

        public RetrievalResponse(String query, String anchorId, List<RetrievalResult> results,
                                 SubgraphView subgraphView, int maxTopologyEdges) {
            this.query = query;
            this.anchorId = anchorId;
            this.results = results;
            this.subgraphView = subgraphView;
            this.maxTopologyEdges = maxTopologyEdges;
        }

        public String getQuery() { return query; }
        public String getAnchorId() { return anchorId; }
        public List<RetrievalResult> getResults() { return results; }
        public SubgraphView getSubgraphView() { return subgraphView; }

        /**
         * Format the retrieval results as an LLM-ready context string.
         *
         * <p>Output format:
         * <pre>
         * === RETRIEVED CODE CONTEXT ===
         * Query: Refactor createUser to async
         * Anchor: com.example.UserService#createUser(...)
         * Retrieved 5 relevant code chunks (ranked by relevance):
         *
         * ── Chunk 1 of 5 (score: 0.8723, anchor) ──
         * [chunk.toPromptFormat()]
         *
         * ── Chunk 2 of 5 (score: 0.6511) ──
         * [chunk.toPromptFormat()]
         * ...
         * </pre>
         */
        public String toLlmContext() {
            StringBuilder sb = new StringBuilder();

            sb.append("=== RETRIEVED CODE CONTEXT ===\n");
            sb.append("Query: ").append(query).append("\n");
            if (anchorId != null) {
                sb.append("Anchor: ").append(anchorId).append("\n");
            }
            sb.append("Retrieved ").append(results.size())
              .append(" relevant code chunks (ranked by relevance):\n\n");

            if (subgraphView != null && anchorId != null) {
                sb.append("=== SUBGRAPH TOPOLOGY ===\n");
                sb.append(subgraphView.renderTopology(anchorId, maxTopologyEdges));
                sb.append("\n\n");
            }

            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                sb.append("── Chunk ").append(i + 1).append(" of ").append(results.size());
                sb.append(String.format(" (score: %.4f", r.getFinalScore()));
                if (r.isAnchor()) sb.append(", anchor");
                sb.append(") ──\n");
                if (anchorId != null) {
                    sb.append("Path from anchor: ").append(r.formatPathFromAnchor()).append("\n");
                }
                sb.append(r.getChunk().toPromptFormat());
                sb.append("\n");
            }

            return sb.toString();
        }

        /**
         * Get just the code chunks in prompt format without scoring metadata.
         */
        public String toSimpleContext() {
            StringBuilder sb = new StringBuilder();
            for (RetrievalResult r : results) {
                sb.append("═".repeat(72)).append("\n");
                sb.append(r.getChunk().toPromptFormat());
                sb.append("\n");
            }
            return sb.toString();
        }
    }
}

