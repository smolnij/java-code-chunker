package com.smolnij.chunker.refactor;

import com.smolnij.chunker.apply.ApplyResult;
import com.smolnij.chunker.apply.GraphReindexer;
import com.smolnij.chunker.apply.PatchApplier;
import com.smolnij.chunker.apply.PatchPlan;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.CrossMethodDiff;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.refactor.diff.MethodDiff;
import com.smolnij.chunker.refactor.diff.ScoredDiff;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final AstDiffEngine diffEngine;
    private final DiffScorer diffScorer;
    /** Optional Neo4j delta re-indexer used by the apply step. */
    private final GraphReindexer reindexer;

    /** Callback for streaming tokens to the console (or elsewhere). */
    private Consumer<String> streamCallback;

    public RefactorLoop(HybridRetriever retriever,
                        Neo4jGraphReader graphReader,
                        ChatService chatService,
                        RefactorConfig config,
                        AstDiffEngine diffEngine,
                        DiffScorer diffScorer) {
        this(retriever, graphReader, chatService, config, diffEngine, diffScorer, null);
    }

    public RefactorLoop(HybridRetriever retriever,
                        Neo4jGraphReader graphReader,
                        ChatService chatService,
                        RefactorConfig config,
                        AstDiffEngine diffEngine,
                        DiffScorer diffScorer,
                        GraphReindexer reindexer) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.chatService = chatService;
        this.config = config;
        this.promptBuilder = new PromptBuilder(config);
        this.parser = new LlmResponseParser();
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.diffScorer = Objects.requireNonNull(diffScorer, "diffScorer");
        this.reindexer = reindexer;
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
        if (config.isTrace()) {
            System.out.println("[TRACE] Trace logging enabled — full prompts and responses will be printed");
        }
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
        StructuredOutputSpec analysisSpec = PromptBuilder.specFor(
            PromptBuilder.PromptKind.ANALYSIS, config.getStructuredOutput());
        String analysisResponse = callLlm("Analysis", promptBuilder.getSystemPrompt(),
            analysisPrompt, analysisSpec);

        // Check if analysis reveals missing context
        LlmResponseParser.AnalysisResponse analysisParsed = parser.parseAnalysis(analysisResponse);
        warnIfFallback("Analysis", analysisParsed.isParsedFromJson(), analysisSpec, analysisResponse);
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
            StructuredOutputSpec refactorSpec = PromptBuilder.specFor(
                PromptBuilder.PromptKind.REFACTOR, config.getStructuredOutput());
            String refactorResponse = callLlm(label, promptBuilder.getSystemPrompt(),
                refactorPrompt, refactorSpec);

            lastResponse = parser.parse(refactorResponse);
            warnIfFallback(label, lastResponse.isParsedFromJson(), refactorSpec, refactorResponse);

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
            String astDiffReport = computeAstDiffReport(lastResponse.getRawResponse(), currentResults);

            String safetyPrompt = promptBuilder.buildSafetyCheckPrompt(
                lastResponse.getRawResponse(), currentResults, astDiffReport
            );
            StructuredOutputSpec safetySpec = PromptBuilder.specFor(
                PromptBuilder.PromptKind.SAFETY_CHECK, config.getStructuredOutput());
            safetyResponse = callLlm("Safety Check", promptBuilder.getSystemPrompt(),
                safetyPrompt, safetySpec);

            // Merge breaking changes from refactor + safety
            allBreakingChanges.addAll(lastResponse.getBreakingChanges());
            LlmResponseParser.RefactorResponse safetyParsed = parser.parse(safetyResponse);
            warnIfFallback("Safety Check", safetyParsed.isParsedFromJson(), safetySpec, safetyResponse);
            allBreakingChanges.addAll(safetyParsed.getBreakingChanges());
        } else {
            System.out.println("  → No code changes produced, skipping safety check");
        }
        System.out.println();

        // ── Build final result ──
        RefactorResult result = new RefactorResult(
            userQuery,
            lastResponse != null ? lastResponse.getCode() : "",
            lastResponse != null ? lastResponse.getExplanation() : "",
            allBreakingChanges,
            safetyResponse,
            round + 1,
            currentResults.size(),
            lastResponse != null ? lastResponse.getRawResponse() : "",
            lastResponse != null && lastResponse.isParsedFromJson()
        );

        // ── Step 5: Apply (optional) — deterministic file edits ──
        if (config.isApply() && lastResponse != null && lastResponse.hasCode()) {
            applyPatch(result, lastResponse.getRawResponse());
        } else if (config.isApply()) {
            System.out.println("━━━ Step 5: Apply ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  → Skipped — no code produced");
            System.out.println();
        }

        return result;
    }

    /**
     * Translate the LLM response into a {@link PatchPlan} and run {@link PatchApplier}.
     * Outcome is recorded on {@link RefactorResult} via {@code withApplyResult}.
     */
    private void applyPatch(RefactorResult result, String rawLlmResponse) {
        System.out.println("━━━ Step 5: Apply ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String repoRootStr = config.getRepoRoot();
        if (repoRootStr == null || repoRootStr.isEmpty()) {
            String msg = "Apply requested but repoRoot is empty — set refactor.repoRoot / REFACTOR_REPO_ROOT.";
            System.out.println("  ✗ " + msg);
            System.out.println();
            result.withApplyResult(List.of(), msg);
            return;
        }

        try {
            PatchPlan plan = parser.parsePatchPlan(rawLlmResponse, graphReader, "refactor");
            if (plan.isEmpty()) {
                String msg = "No applicable edits found in refactor response.";
                System.out.println("  ⚠ " + msg);
                System.out.println();
                result.withApplyResult(List.of(), msg);
                return;
            }

            Path repoRoot = Paths.get(repoRootStr);
            PatchApplier applier = new PatchApplier(
                repoRoot, graphReader, config.isDryRun(), config.isBackup());

            System.out.println("  Plan: " + plan.ops().size() + " op(s), dryRun=" + config.isDryRun()
                + ", backup=" + config.isBackup());
            ApplyResult ar = applier.apply(plan);
            System.out.println(ar.toReport().replace("\n", "\n  "));

            String reindexReport = "";
            if (reindexer != null && ar.isSuccess() && !config.isDryRun()) {
                try {
                    GraphReindexer.ReindexResult rr = reindexer.reindex(ar.getChangedFiles());
                    reindexReport = rr.toReport();
                    System.out.println("  " + reindexReport);
                } catch (Exception e) {
                    reindexReport = "Reindex: ✗ " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    System.out.println("  ⚠ " + reindexReport);
                }
            }
            System.out.println();
            String fullReport = reindexReport.isEmpty()
                ? ar.toReport()
                : ar.toReport() + "\n" + reindexReport;
            result.withApplyResult(ar.getChangedFiles(), fullReport);
        } catch (Exception e) {
            String msg = "Apply failed: " + e.getClass().getSimpleName() + " — " + e.getMessage();
            System.out.println("  ✗ " + msg);
            e.printStackTrace();
            System.out.println();
            result.withApplyResult(List.of(), msg);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM call (streaming or non-streaming)
    // ═══════════════════════════════════════════════════════════════

    private String callLlm(String label, String systemPrompt, String userPrompt) {
        return callLlm(label, systemPrompt, userPrompt, null);
    }

    /**
     * Call the chat endpoint. When {@code spec != null}, structured output
     * is requested (streaming is disabled for structured calls — streaming
     * a JSON blob token-by-token produces a poor console experience and
     * only delays parsing).
     */
    private String callLlm(String label, String systemPrompt, String userPrompt,
                           StructuredOutputSpec spec) {
        System.out.println("  ┌─ LLM [" + label + "] ─────────────────────────────");

        if (config.isTrace()) {
            System.out.println("  │ [TRACE] SYSTEM PROMPT (" + systemPrompt.length() + " chars):");
            System.out.println(systemPrompt.replace("\n", "\n  │   "));
            System.out.println("  │ [TRACE] USER PROMPT (" + userPrompt.length() + " chars):");
            System.out.println(userPrompt.replace("\n", "\n  │   "));
        }

        String response;
        boolean canStream = spec == null && config.isStream() && streamCallback != null;
        if (canStream) {
            System.out.println("  │ (streaming) ");
            response = chatService.chatStream(systemPrompt, userPrompt, streamCallback::accept);
            System.out.println(); // newline after streamed output
        } else {
            if (spec != null) {
                System.out.println("  │ (structured: " + spec.preferredMode() + " / " + spec.name() + ") ");
            } else {
                System.out.println("  │ (waiting for response...) ");
            }
            response = spec != null
                ? chatService.chat(systemPrompt, userPrompt, spec)
                : chatService.chat(systemPrompt, userPrompt);
            if (config.isTrace()) {
                System.out.println("  │ [TRACE] FULL RESPONSE (" + response);
                System.out.println(response.replace("\n", "\n  │   "));
            } else {
                System.out.println(response);
            }
        }

        if (config.isTrace()) {
            System.out.println("  │ [TRACE] response length: " + response);
        }
        System.out.println("  └───────────────────────────────────────────────────");
        return response;
    }

    private static void warnIfFallback(String label, boolean parsedFromJson,
                                       StructuredOutputSpec spec, String response) {
        if (spec == null) return;
        System.out.println();
        if (parsedFromJson) {
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  ✓  STRUCTURED OUTPUT ACTIVE                            ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════╣");
            System.out.println("  ║  [" + label + "] Response parsed from JSON successfully (" + spec.preferredMode() + ").");
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        } else {
            String preview = response == null ? "" : response.replace("\n", " ");
            if (preview.length() > 200) preview = preview.substring(0, 200) + "…";
            System.out.println("  ╔══════════════════════════════════════════════════════════╗");
            System.out.println("  ║  ⚠  WARNING: STRUCTURED OUTPUT FALLBACK                 ║");
            System.out.println("  ╠══════════════════════════════════════════════════════════╣");
            System.out.println("  ║  [" + label + "] LLM ignored response_format — using regex fallback.");
            System.out.println("  ║  Results may be incomplete or misparse. Check model support.");
            System.out.println("  ║  Preview: " + preview);
            System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        }
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════
    // AST diff (deterministic structural analysis)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute a deterministic AST-level structural diff between the chunks
     * currently in context and the LLM's proposed code, returning a formatted
     * report suitable for injection into the safety-check prompt.
     *
     * <p>Returns an empty string when the proposal parses cleanly with no
     * structural issues worth surfacing.
     */
    private String computeAstDiffReport(String proposedResponse, List<RetrievalResult> contextResults) {
        try {
            List<CodeChunk> originals = new ArrayList<>();
            for (RetrievalResult r : contextResults) {
                originals.add(r.getChunk());
            }
            CrossMethodDiff crossDiff = diffEngine.analyze(originals, proposedResponse);
            if (crossDiff.isEmpty()) return "";

            List<ScoredDiff> scoredDiffs = new ArrayList<>();
            for (MethodDiff md : crossDiff.getMethodDiffs()) {
                scoredDiffs.add(diffScorer.score(md));
            }

            StringBuilder sb = new StringBuilder();
            if (!scoredDiffs.isEmpty()) {
                sb.append("AST DIFF ANALYSIS (deterministic, ").append(scoredDiffs.size()).append(" method(s)):\n\n");
                for (ScoredDiff sd : scoredDiffs) {
                    sb.append(sd.toDisplayString());
                }
            }
            String crossDisplay = crossDiff.toDisplayString();
            if (!crossDisplay.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(crossDisplay);
            }
            return sb.toString();
        } catch (Exception e) {
            System.out.println("  ⚠ AST diff failed: " + e.getMessage());
            return "";
        }
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
        private final boolean parsedFromJson;
        private List<Path> appliedFiles = List.of();
        private String applyReport = "";

        public RefactorResult(String query, String code, String explanation,
                               List<String> breakingChanges, String safetyAnalysis,
                               int iterations, int chunksUsed, String rawLlmResponse,
                               boolean parsedFromJson) {
            this.query = query;
            this.code = code;
            this.explanation = explanation;
            this.breakingChanges = List.copyOf(breakingChanges);
            this.safetyAnalysis = safetyAnalysis;
            this.iterations = iterations;
            this.chunksUsed = chunksUsed;
            this.rawLlmResponse = rawLlmResponse;
            this.parsedFromJson = parsedFromJson;
        }

        public String getQuery() { return query; }
        public String getCode() { return code; }
        public String getExplanation() { return explanation; }
        public List<String> getBreakingChanges() { return breakingChanges; }
        public String getSafetyAnalysis() { return safetyAnalysis; }
        public int getIterations() { return iterations; }
        public int getChunksUsed() { return chunksUsed; }
        public String getRawLlmResponse() { return rawLlmResponse; }
        public boolean isParsedFromJson() { return parsedFromJson; }
        public List<Path> getAppliedFiles() { return appliedFiles; }
        public String getApplyReport() { return applyReport; }

        /** Populate apply-phase fields after PatchApplier runs. */
        public RefactorResult withApplyResult(List<Path> files, String report) {
            this.appliedFiles = files == null ? List.of() : List.copyOf(files);
            this.applyReport = report == null ? "" : report;
            return this;
        }

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

