package com.smolnij.chunker.refactor;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.util.*;
import java.util.function.Consumer;

/**
 * Graph-aware refactoring loop that orchestrates:
 *
 * <pre>
 *   1. User query
 *   2. Hybrid retrieval (graph + vector)
 *   3. LLM suggests changes (streaming)
 *   4. If missing context → expand graph → fetch new chunks
 *   5. Re-run LLM with expanded context
 *   6. Safety check — ask LLM for breaking changes
 * </pre>
 *
 * <h3>Multi-step refinement:</h3>
 * <ol>
 *   <li><b>Analyze dependencies</b> — ask the LLM what it needs</li>
 *   <li><b>Suggest refactor</b> — generate the actual code changes</li>
 *   <li><b>Apply safety check</b> — list potential breaking changes</li>
 * </ol>
 *
 * <p>The loop will automatically expand the graph and retry if the LLM
 * declares missing context (up to {@code maxRefinements} rounds).
 *
 * <h3>Usage:</h3>
 * <pre>
 *   RefactorLoop loop = new RefactorLoop(retriever, graphReader, chatService, config);
 *   RefactorLoop.RefactorResult result = loop.run("Refactor createUser to async");
 *   System.out.println(result);
 * </pre>
 */
public class RefactorLoop {

    private final HybridRetriever retriever;
    private final Neo4jGraphReader graphReader;
    private final ChatService chatService;
    private final RefactorConfig config;
    private final PromptBuilder promptBuilder;
    private final LlmResponseParser parser;

    /** Callback for streaming tokens to the console (or elsewhere). */
    private Consumer<String> streamCallback;

    public RefactorLoop(HybridRetriever retriever,
                        Neo4jGraphReader graphReader,
                        ChatService chatService,
                        RefactorConfig config) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.chatService = chatService;
        this.config = config;
        this.promptBuilder = new PromptBuilder(config);
        this.parser = new LlmResponseParser();
        this.streamCallback = System.out::print; // default: print to stdout
    }

    public void setStreamCallback(Consumer<String> callback) {
        this.streamCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the full graph-aware refactoring loop.
     *
     * @param userQuery the natural-language refactoring request
     * @return the complete result including code, explanation, and safety analysis
     */
    public RefactorResult run(String userQuery) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Graph-Aware Refactoring Loop                        ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Query: " + userQuery);
        System.out.println("Config: " + config);
        System.out.println();

        // ── Step 1: Hybrid retrieval ──
        System.out.println("━━━ Step 1: Hybrid Retrieval ━━━━━━━━━━━━━━━━━━━━━━━━━");
        HybridRetriever.RetrievalResponse retrievalResponse = retriever.retrieve(userQuery);
        List<RetrievalResult> currentResults = new ArrayList<>(retrievalResponse.getResults());

        System.out.println("Retrieved " + currentResults.size() + " chunks");
        System.out.println();

        // ── Step 2: Dependency analysis (optional first pass) ──
        System.out.println("━━━ Step 2: Dependency Analysis ━━━━━━━━━━━━━━━━━━━━━━");
        String analysisPrompt = promptBuilder.buildAnalysisPrompt(userQuery, currentResults);
        String analysisResponse = callLlm("Analysis", promptBuilder.getSystemPrompt(), analysisPrompt);

        // Check if analysis reveals missing context
        LlmResponseParser.RefactorResponse analysisParsed = parser.parse(analysisResponse);
        if (analysisParsed.hasMissingReferences()) {
            System.out.println();
            System.out.println("  → Analysis found missing refs: " + analysisParsed.getMissingReferences());
            currentResults = expandAndMerge(currentResults, analysisParsed.getMissingReferences());
        }
        System.out.println();

        // ── Step 3: Main refactoring loop with refinement ──
        LlmResponseParser.RefactorResponse lastResponse = null;
        int round;

        for (round = 0; round <= config.getMaxRefinements(); round++) {
            String label = round == 0 ? "Initial Refactoring" : "Refinement Round " + round;
            System.out.println("━━━ Step 3." + round + ": " + label + " ━━━━━━━━━━━━━━━━━━");
            System.out.println("  Context: " + currentResults.size() + " chunks");

            String refactorPrompt = promptBuilder.buildRefactorPrompt(userQuery, currentResults);
            String refactorResponse = callLlm(label, promptBuilder.getSystemPrompt(), refactorPrompt);

            lastResponse = parser.parse(refactorResponse);

            System.out.println();
            System.out.println("  → Parsed: " + lastResponse);

            // Check if we need more context
            if (lastResponse.hasMissingReferences() && round < config.getMaxRefinements()) {
                System.out.println("  → Missing context detected, expanding graph...");
                System.out.println("  → Missing refs: " + lastResponse.getMissingReferences());
                currentResults = expandAndMerge(currentResults, lastResponse.getMissingReferences());
                System.out.println("  → Expanded to " + currentResults.size() + " chunks");
                System.out.println();
            } else {
                break; // Done — either no missing refs or max refinements reached
            }
        }
        System.out.println();

        // ── Step 4: Safety check ──
        System.out.println("━━━ Step 4: Safety Check ━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        String safetyResponse = "";
        List<String> allBreakingChanges = new ArrayList<>();

        if (lastResponse != null && lastResponse.hasCode()) {
            String safetyPrompt = promptBuilder.buildSafetyCheckPrompt(
                lastResponse.getRawResponse(), currentResults
            );
            safetyResponse = callLlm("Safety Check", promptBuilder.getSystemPrompt(), safetyPrompt);

            // Merge breaking changes from refactor + safety
            allBreakingChanges.addAll(lastResponse.getBreakingChanges());
            LlmResponseParser.RefactorResponse safetyParsed = parser.parse(safetyResponse);
            allBreakingChanges.addAll(safetyParsed.getBreakingChanges());
        } else {
            System.out.println("  → No code changes produced, skipping safety check");
        }
        System.out.println();

        // ── Build final result ──
        return new RefactorResult(
            userQuery,
            lastResponse != null ? lastResponse.getCode() : "",
            lastResponse != null ? lastResponse.getExplanation() : "",
            allBreakingChanges,
            safetyResponse,
            round + 1,
            currentResults.size(),
            lastResponse != null ? lastResponse.getRawResponse() : ""
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM call (streaming or non-streaming)
    // ═══════════════════════════════════════════════════════════════

    private String callLlm(String label, String systemPrompt, String userPrompt) {
        System.out.println("  ┌─ LLM [" + label + "] ─────────────────────────────");

        String response;
        if (config.isStream() && streamCallback != null) {
            System.out.println("  │ (streaming) ");
            response = chatService.chatStream(systemPrompt, userPrompt, streamCallback::accept);
            System.out.println(); // newline after streamed output
        } else {
            System.out.println("  │ (waiting for response...) ");
            response = chatService.chat(systemPrompt, userPrompt);
            System.out.println(response);
        }

        System.out.println("  └───────────────────────────────────────────────────");
        return response;
    }

    // ═══════════════════════════════════════════════════════════════
    // Graph expansion for missing context
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve missing references via the graph and merge new chunks
     * into the existing result set.
     */
    private List<RetrievalResult> expandAndMerge(List<RetrievalResult> existing,
                                                  List<String> missingRefs) {
        // Collect existing chunk IDs to avoid duplicates
        Set<String> existingIds = new LinkedHashSet<>();
        for (RetrievalResult r : existing) {
            existingIds.add(r.getChunkId());
        }

        Set<String> newChunkIds = new LinkedHashSet<>();

        for (String ref : missingRefs) {
            // Try exact match first
            String found = graphReader.findMethodExact(ref);
            if (found != null && !existingIds.contains(found)) {
                newChunkIds.add(found);
                continue;
            }

            // Try stripping to just the method name (e.g. "UserService.save" → "save")
            String simpleName = ref.contains(".") ? ref.substring(ref.lastIndexOf('.') + 1) : ref;
            found = graphReader.findMethodExact(simpleName);
            if (found != null && !existingIds.contains(found)) {
                newChunkIds.add(found);
                continue;
            }

            // Try graph expansion from existing anchors
            for (RetrievalResult r : existing) {
                if (r.isAnchor()) {
                    Map<String, Integer> expanded = graphReader.expandSubgraph(r.getChunkId(), 3);
                    for (String id : expanded.keySet()) {
                        if (!existingIds.contains(id) && id.toLowerCase().contains(simpleName.toLowerCase())) {
                            newChunkIds.add(id);
                        }
                    }
                    break;
                }
            }
        }

        // Hydrate new chunks
        if (!newChunkIds.isEmpty()) {
            Map<String, CodeChunk> newChunks = graphReader.fetchMethodChunks(newChunkIds);
            List<RetrievalResult> merged = new ArrayList<>(existing);
            for (Map.Entry<String, CodeChunk> entry : newChunks.entrySet()) {
                RetrievalResult result = new RetrievalResult(entry.getValue());
                result.setHopDistance(3); // mark as expanded context
                result.setSemanticSimilarity(0.0);
                result.setAnchor(false);
                merged.add(result);
            }
            return merged;
        }

        return existing;
    }

    // ═══════════════════════════════════════════════════════════════
    // Result object
    // ═══════════════════════════════════════════════════════════════

    /**
     * Complete result from a refactoring loop run.
     */
    public static class RefactorResult {

        private final String query;
        private final String code;
        private final String explanation;
        private final List<String> breakingChanges;
        private final String safetyAnalysis;
        private final int iterations;
        private final int chunksUsed;
        private final String rawLlmResponse;

        public RefactorResult(String query, String code, String explanation,
                               List<String> breakingChanges, String safetyAnalysis,
                               int iterations, int chunksUsed, String rawLlmResponse) {
            this.query = query;
            this.code = code;
            this.explanation = explanation;
            this.breakingChanges = List.copyOf(breakingChanges);
            this.safetyAnalysis = safetyAnalysis;
            this.iterations = iterations;
            this.chunksUsed = chunksUsed;
            this.rawLlmResponse = rawLlmResponse;
        }

        public String getQuery() { return query; }
        public String getCode() { return code; }
        public String getExplanation() { return explanation; }
        public List<String> getBreakingChanges() { return breakingChanges; }
        public String getSafetyAnalysis() { return safetyAnalysis; }
        public int getIterations() { return iterations; }
        public int getChunksUsed() { return chunksUsed; }
        public String getRawLlmResponse() { return rawLlmResponse; }

        public boolean hasCode() {
            return code != null && !code.isBlank();
        }

        /**
         * Format the result for display / file output.
         */
        public String toDisplayString() {
            StringBuilder sb = new StringBuilder();

            sb.append("═".repeat(72)).append("\n");
            sb.append("  REFACTORING RESULT\n");
            sb.append("═".repeat(72)).append("\n\n");

            sb.append("Query: ").append(query).append("\n");
            sb.append("Iterations: ").append(iterations).append("\n");
            sb.append("Chunks used: ").append(chunksUsed).append("\n\n");

            if (hasCode()) {
                sb.append("── Updated Code ─────────────────────────────────────────\n");
                sb.append("```java\n");
                sb.append(code).append("\n");
                sb.append("```\n\n");
            }

            if (!explanation.isEmpty()) {
                sb.append("── Explanation ──────────────────────────────────────────\n");
                sb.append(explanation).append("\n\n");
            }

            if (!breakingChanges.isEmpty()) {
                sb.append("── Breaking Changes ────────────────────────────────────\n");
                for (String change : breakingChanges) {
                    sb.append("  ⚠ ").append(change).append("\n");
                }
                sb.append("\n");
            }

            if (!safetyAnalysis.isEmpty()) {
                sb.append("── Safety Analysis ─────────────────────────────────────\n");
                sb.append(safetyAnalysis).append("\n\n");
            }

            sb.append("═".repeat(72)).append("\n");
            return sb.toString();
        }

        @Override
        public String toString() {
            return String.format(
                "RefactorResult { hasCode=%s, iterations=%d, chunks=%d, breaking=%d }",
                hasCode(), iterations, chunksUsed, breakingChanges.size()
            );
        }
    }
}

