package com.example.chunker.retrieval;

import com.example.chunker.model.CodeChunk;

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

        // ── Print ranking debug ──
        System.out.println();
        System.out.println("── Ranking ─────────────────────────────────────────────");
        for (int i = 0; i < selected.size(); i++) {
            System.out.printf("  #%d  %s%n", i + 1, selected.get(i).toDebugString());
        }
        System.out.println();

        return new RetrievalResponse(userQuery, anchorId, selected);
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
            int callerCount = graphReader.getCallerCount(chunkId);
            boolean highFanIn = callerCount >= config.getFanInThreshold();

            result.computeStructuralBonus(
                sameClass, samePackage, highFanIn,
                config.getSameClassBonus(), config.getSamePackageBonus(), config.getHighFanInBonus()
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

        // Check if anchor is already in top-K
        List<RetrievalResult> topResults = new ArrayList<>();
        RetrievalResult anchorResult = null;
        boolean anchorInTopK = false;

        for (int i = 0; i < ranked.size() && topResults.size() < topK; i++) {
            RetrievalResult r = ranked.get(i);
            topResults.add(r);
            if (r.isAnchor()) {
                anchorInTopK = true;
            }
        }

        // If anchor exists but wasn't in top-K, find and pin it
        if (anchorId != null && !anchorInTopK) {
            for (RetrievalResult r : ranked) {
                if (r.isAnchor()) {
                    anchorResult = r;
                    break;
                }
            }
            if (anchorResult != null) {
                // Replace the last item with the anchor and re-sort
                if (topResults.size() >= topK) {
                    topResults.set(topResults.size() - 1, anchorResult);
                } else {
                    topResults.add(anchorResult);
                }
                Collections.sort(topResults);
            }
        }

        return topResults;
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

        public RetrievalResponse(String query, String anchorId, List<RetrievalResult> results) {
            this.query = query;
            this.anchorId = anchorId;
            this.results = results;
        }

        public String getQuery() { return query; }
        public String getAnchorId() { return anchorId; }
        public List<RetrievalResult> getResults() { return results; }

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

            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                sb.append("── Chunk ").append(i + 1).append(" of ").append(results.size());
                sb.append(String.format(" (score: %.4f", r.getFinalScore()));
                if (r.isAnchor()) sb.append(", anchor");
                sb.append(") ──\n");
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

