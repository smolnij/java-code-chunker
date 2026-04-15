package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.safeloop.SafeLoopConfig;

/**
 * Configuration for the distributed safe refactoring loop.
 *
 * <p>Unlike {@link SafeLoopConfig} which can share a
 * single LLM endpoint, this config explicitly separates the refactoring and
 * analysis LLMs onto different machines:
 *
 * <pre>
 *   ┌──────────────────────┐          ┌──────────────────────┐
 *   │  REFACTOR_MACHINE    │          │  S_ANALYZE_MACHINE   │
 *   │  http://REFACTORM:1234│         │  http://SANALYZEM:1234│
 *   │                      │          │                      │
 *   │  🟦 Generator LLM    │          │  🟩 Planner–Analyzer │
 *   │  - Writes code       │          │  - Plans refactoring │
 *   │  - No decision auth  │          │  - Retrieves context │
 *   │  - Creative (temp=0.3)│         │  - Validates results │
 *   │                      │          │  - Tool calling: YES │
 *   └──────────┬───────────┘          └──────────┬───────────┘
 *              │                                 │
 *              └──────── Orchestrator ───────────┘
 * </pre>
 *
 * <h3>Environment variables / system properties:</h3>
 * <pre>
 *   REFACTOR_MACHINE_URL      / -Ddist.refactorUrl        — Generator endpoint (default: http://REFACTORM:1234/v1/chat/completions)
 *   REFACTOR_MACHINE_MODEL    / -Ddist.refactorModel      — Generator model name
 *   REFACTOR_MACHINE_TEMP     / -Ddist.refactorTemp       — Generator temperature (default: 0.3)
 *
 *   ANALYZER_MACHINE_URL      / -Ddist.analyzerUrl        — Critic endpoint (default: http://SANALYZEM:1234/v1/chat/completions)
 *   ANALYZER_MACHINE_MODEL    / -Ddist.analyzerModel      — Critic model name
 *   ANALYZER_MACHINE_TEMP     / -Ddist.analyzerTemp       — Critic temperature (default: 0.1)
 *
 *   DIST_TOP_P               / -Ddist.topP               — shared top_p (default: 0.9)
 *   DIST_MAX_TOKENS          / -Ddist.maxTokens           — shared max tokens (default: 4096)
 *   DIST_SAFETY_THRESHOLD    / -Ddist.safetyThreshold     — min confidence (default: 0.9)
 *   DIST_MAX_ITERATIONS      / -Ddist.maxIterations       — max loop iterations (default: 5)
 *   DIST_MAX_CHUNKS          / -Ddist.maxChunks           — context chunks (default: 8)
 *   DIST_CHAT_MEMORY_SIZE    / -Ddist.chatMemorySize      — agent memory window (default: 60)
 *   DIST_MAX_TOOL_CALLS      / -Ddist.maxToolCalls        — tool call cap (default: 30)
 *   DIST_MIN_CALLER_DEPTH    / -Ddist.minCallerDepth      — min caller hops (default: 1)
 *   DIST_MIN_CALLEE_DEPTH    / -Ddist.minCalleeDepth      — min callee hops (default: 1)
 *   DIST_STOP_NO_NEW_NODES   / -Ddist.stopOnNoNewNodes    — stop on convergence (default: true)
 *   DIST_STOP_ON_STAGNATION  / -Ddist.stopOnStagnation    — stop on stagnation (default: true)
 *   DIST_STREAM              / -Ddist.stream              — SSE streaming (default: true)
 *   DIST_MAX_PLANNER_STEPS   / -Ddist.maxPlannerSteps     — max planner tool-call steps (default: 8)
 *   DIST_MAX_CHUNKS_PER_RETRIEVAL / -Ddist.maxChunksPerRetrieval — chunks per retrieval call (default: 10)
 *   DIST_MAX_RETRIEVAL_DEPTH / -Ddist.maxRetrievalDepth   — max graph hops per retrieval (default: 2)
 * </pre>
 */
public class DistributedSafeLoopConfig {

    // ── Machine 1: Refactoring Generator ──
    private String refactorUrl = "http://REFACTORM:1234/v1/chat/completions";
    private String refactorModel = "";
    private double refactorTemperature = 0.3;

    // ── Machine 2: Static Analyzer Critic ──
    private String analyzerUrl = "http://SANALYZEM:1234/v1/chat/completions";
    private String analyzerModel = "";
    private double analyzerTemperature = 0.1;

    // ── Shared sampling ──
    private double topP = 0.9;
    private int maxTokens = 4096;

    // ── Safety loop control ──
    private double safetyThreshold = 0.9;
    private int maxIterations = 5;

    // ── Context ──
    private int maxChunks = 8;
    private int chatMemorySize = 60;
    private int maxToolCalls = 30;

    // ── Graph coverage requirements ──
    private int minCallerDepth = 1;
    private int minCalleeDepth = 1;

    // ── Planner-driven mode ──
    private int maxPlannerSteps = 8;
    private int maxChunksPerRetrieval = 10;
    private int maxRetrievalDepth = 2;

    // ── Convergence ──
    private boolean stopOnNoNewNodes = true;
    private boolean stopOnStagnation = true;

    // ── Streaming ──
    private boolean stream = true;

    // ═══════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════

    public static DistributedSafeLoopConfig fromEnvironment() {
        DistributedSafeLoopConfig cfg = new DistributedSafeLoopConfig();

        // Machine 1: Generator
        cfg.refactorUrl = strVal("REFACTOR_MACHINE_URL", "dist.refactorUrl", cfg.refactorUrl);
        cfg.refactorModel = strVal("REFACTOR_MACHINE_MODEL", "dist.refactorModel", cfg.refactorModel);
        cfg.refactorTemperature = doubleVal("REFACTOR_MACHINE_TEMP", "dist.refactorTemp", cfg.refactorTemperature);

        // Machine 2: Critic
        cfg.analyzerUrl = strVal("ANALYZER_MACHINE_URL", "dist.analyzerUrl", cfg.analyzerUrl);
        cfg.analyzerModel = strVal("ANALYZER_MACHINE_MODEL", "dist.analyzerModel", cfg.analyzerModel);
        cfg.analyzerTemperature = doubleVal("ANALYZER_MACHINE_TEMP", "dist.analyzerTemp", cfg.analyzerTemperature);

        // Shared
        cfg.topP = doubleVal("DIST_TOP_P", "dist.topP", cfg.topP);
        cfg.maxTokens = intVal("DIST_MAX_TOKENS", "dist.maxTokens", cfg.maxTokens);
        cfg.safetyThreshold = doubleVal("DIST_SAFETY_THRESHOLD", "dist.safetyThreshold", cfg.safetyThreshold);
        cfg.maxIterations = intVal("DIST_MAX_ITERATIONS", "dist.maxIterations", cfg.maxIterations);
        cfg.maxChunks = intVal("DIST_MAX_CHUNKS", "dist.maxChunks", cfg.maxChunks);
        cfg.chatMemorySize = intVal("DIST_CHAT_MEMORY_SIZE", "dist.chatMemorySize", cfg.chatMemorySize);
        cfg.maxToolCalls = intVal("DIST_MAX_TOOL_CALLS", "dist.maxToolCalls", cfg.maxToolCalls);
        cfg.minCallerDepth = intVal("DIST_MIN_CALLER_DEPTH", "dist.minCallerDepth", cfg.minCallerDepth);
        cfg.minCalleeDepth = intVal("DIST_MIN_CALLEE_DEPTH", "dist.minCalleeDepth", cfg.minCalleeDepth);
        cfg.maxPlannerSteps = intVal("DIST_MAX_PLANNER_STEPS", "dist.maxPlannerSteps", cfg.maxPlannerSteps);
        cfg.maxChunksPerRetrieval = intVal("DIST_MAX_CHUNKS_PER_RETRIEVAL", "dist.maxChunksPerRetrieval", cfg.maxChunksPerRetrieval);
        cfg.maxRetrievalDepth = intVal("DIST_MAX_RETRIEVAL_DEPTH", "dist.maxRetrievalDepth", cfg.maxRetrievalDepth);
        cfg.stopOnNoNewNodes = boolVal("DIST_STOP_NO_NEW_NODES", "dist.stopOnNoNewNodes", cfg.stopOnNoNewNodes);
        cfg.stopOnStagnation = boolVal("DIST_STOP_ON_STAGNATION", "dist.stopOnStagnation", cfg.stopOnStagnation);
        cfg.stream = boolVal("DIST_STREAM", "dist.stream", cfg.stream);

        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getRefactorUrl() { return refactorUrl; }
    public String getRefactorModel() { return refactorModel; }
    public double getRefactorTemperature() { return refactorTemperature; }

    public String getAnalyzerUrl() { return analyzerUrl; }
    public String getAnalyzerModel() { return analyzerModel; }
    public double getAnalyzerTemperature() { return analyzerTemperature; }

    public double getTopP() { return topP; }
    public int getMaxTokens() { return maxTokens; }
    public double getSafetyThreshold() { return safetyThreshold; }
    public int getMaxIterations() { return maxIterations; }
    public int getMaxChunks() { return maxChunks; }
    public int getChatMemorySize() { return chatMemorySize; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getMinCallerDepth() { return minCallerDepth; }
    public int getMinCalleeDepth() { return minCalleeDepth; }
    public int getMaxPlannerSteps() { return maxPlannerSteps; }
    public int getMaxChunksPerRetrieval() { return maxChunksPerRetrieval; }
    public int getMaxRetrievalDepth() { return maxRetrievalDepth; }
    public boolean isStopOnNoNewNodes() { return stopOnNoNewNodes; }
    public boolean isStopOnStagnation() { return stopOnStagnation; }
    public boolean isStream() { return stream; }

    // ═══════════════════════════════════════════════════════════════
    // Builder-style setters
    // ═══════════════════════════════════════════════════════════════

    public DistributedSafeLoopConfig withRefactorUrl(String v) { this.refactorUrl = v; return this; }
    public DistributedSafeLoopConfig withRefactorModel(String v) { this.refactorModel = v; return this; }
    public DistributedSafeLoopConfig withRefactorTemperature(double v) { this.refactorTemperature = v; return this; }
    public DistributedSafeLoopConfig withAnalyzerUrl(String v) { this.analyzerUrl = v; return this; }
    public DistributedSafeLoopConfig withAnalyzerModel(String v) { this.analyzerModel = v; return this; }
    public DistributedSafeLoopConfig withAnalyzerTemperature(double v) { this.analyzerTemperature = v; return this; }
    public DistributedSafeLoopConfig withTopP(double v) { this.topP = v; return this; }
    public DistributedSafeLoopConfig withMaxTokens(int v) { this.maxTokens = v; return this; }
    public DistributedSafeLoopConfig withSafetyThreshold(double v) { this.safetyThreshold = v; return this; }
    public DistributedSafeLoopConfig withMaxIterations(int v) { this.maxIterations = v; return this; }
    public DistributedSafeLoopConfig withMaxChunks(int v) { this.maxChunks = v; return this; }
    public DistributedSafeLoopConfig withChatMemorySize(int v) { this.chatMemorySize = v; return this; }
    public DistributedSafeLoopConfig withMaxToolCalls(int v) { this.maxToolCalls = v; return this; }
    public DistributedSafeLoopConfig withMinCallerDepth(int v) { this.minCallerDepth = v; return this; }
    public DistributedSafeLoopConfig withMinCalleeDepth(int v) { this.minCalleeDepth = v; return this; }
    public DistributedSafeLoopConfig withMaxPlannerSteps(int v) { this.maxPlannerSteps = v; return this; }
    public DistributedSafeLoopConfig withMaxChunksPerRetrieval(int v) { this.maxChunksPerRetrieval = v; return this; }
    public DistributedSafeLoopConfig withMaxRetrievalDepth(int v) { this.maxRetrievalDepth = v; return this; }
    public DistributedSafeLoopConfig withStopOnNoNewNodes(boolean v) { this.stopOnNoNewNodes = v; return this; }
    public DistributedSafeLoopConfig withStopOnStagnation(boolean v) { this.stopOnStagnation = v; return this; }
    public DistributedSafeLoopConfig withStream(boolean v) { this.stream = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "DistributedSafeLoopConfig {\n" +
            "  🟦 Generator: url=%s, model=%s, temp=%.2f\n" +
            "  🟩 Planner–Analyzer: url=%s, model=%s, temp=%.2f\n" +
            "  Shared: topP=%.2f, maxTokens=%d, threshold=%.2f, maxIter=%d,\n" +
            "          maxChunks=%d, memory=%d, maxTools=%d,\n" +
            "          callerDepth=%d, calleeDepth=%d, stopNoNew=%s, stopStagnant=%s, stream=%s,\n" +
            "          plannerSteps=%d, chunksPerRetrieval=%d, retrievalDepth=%d\n" +
            "}",
            refactorUrl,
            refactorModel.isEmpty() ? "(default)" : refactorModel, refactorTemperature,
            analyzerUrl,
            analyzerModel.isEmpty() ? "(default)" : analyzerModel, analyzerTemperature,
            topP, maxTokens, safetyThreshold, maxIterations, maxChunks,
            chatMemorySize, maxToolCalls, minCallerDepth, minCalleeDepth,
            stopOnNoNewNodes, stopOnStagnation, stream,
            maxPlannerSteps, maxChunksPerRetrieval, maxRetrievalDepth
        );
    }

    // ── Helpers ──

    private static String strVal(String envKey, String sysPropKey, String defaultValue) {
        String v = System.getProperty(sysPropKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return defaultValue;
    }

    private static int intVal(String envKey, String sysPropKey, int defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static double doubleVal(String envKey, String sysPropKey, double defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean boolVal(String envKey, String sysPropKey, boolean defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v);
    }
}

