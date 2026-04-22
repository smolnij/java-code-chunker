package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.refactor.RefactorConfig;

/**
 * Configuration for the self-improving safe refactoring loop.
 *
 * <p>Controls the confidence-gated iteration loop, graph-coverage
 * requirements, and dual-role LLM sampling parameters.
 *
 * <h3>Design rationale:</h3>
 * <ul>
 *   <li>Refactorer gets higher temperature (0.3) for creative solutions</li>
 *   <li>Analyzer gets low temperature (0.1) for precise safety analysis</li>
 *   <li>Safety threshold gates the loop — if analyzer confidence &lt; threshold, loop continues</li>
 *   <li>Graph coverage requirements force minimum caller/callee retrieval before refactoring</li>
 *   <li>Convergence detection stops the loop when no new graph nodes are discovered</li>
 * </ul>
 *
 * <h3>Defaults:</h3>
 * <pre>
 *   chatUrl               = http://localhost:1234/v1/chat/completions
 *   refactorModel         = (empty — use loaded model)
 *   analyzerModel         = (empty — use loaded model)
 *   refactorTemperature   = 0.3
 *   analyzerTemperature   = 0.1
 *   topP                  = 0.9
 *   maxTokens             = 4096
 *   safetyThreshold       = 0.9    (confidence ≥ this → SAFE)
 *   maxIterations         = 5      (hard cap on refine loops)
 *   maxChunks             = 8      (context chunks per retrieval)
 *   chatMemorySize        = 60     (sliding window for agent memory)
 *   maxToolCalls          = 30     (safety cap on tool invocations)
 *   minCallerDepth        = 1      (ensure at least this many hops of callers)
 *   minCalleeDepth        = 1      (ensure at least this many hops of callees)
 *   stopOnNoNewNodes      = true   (stop loop if graph expansion yields nothing new)
 *   stopOnStagnation      = true   (stop if analyzer returns same risks twice)
 *   stream                = true   (SSE streaming for refactorer output)
 * </pre>
 */
public class SafeLoopConfig {

    // ── LLM endpoints ──
    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String refactorModel = "";
    private String analyzerModel = "";

    // ── Refactorer sampling ──
    private double refactorTemperature = 0.3;
    private double topP = 0.9;
    private int maxTokens = 4096;

    // ── Analyzer sampling ──
    private double analyzerTemperature = 0.1;

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

    // ── Convergence ──
    private boolean stopOnNoNewNodes = true;
    private boolean stopOnStagnation = true;

    // ── Streaming ──
    private boolean stream = true;

    // ── Self-review sampling (low-temperature reflexion pass) ──
    private double selfReviewTemperature = 0.05;

    // ── Structured output (response_format / tool-call) ──
    private RefactorConfig.StructuredOutputMode structuredOutput =
        RefactorConfig.StructuredOutputMode.JSON_SCHEMA;

    // ── Patch apply (deterministic file edits after SAFE verdict) ──
    private String repoRoot = "/home/smola/dev/src/AI_tools/java-code-chunker/";
    private boolean apply = true;
    private boolean dryRun = false;
    private boolean backup = true;

    // ═══════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════

    public static SafeLoopConfig fromEnvironment() {
        SafeLoopConfig cfg = new SafeLoopConfig();

        cfg.chatUrl = strVal("SAFELOOP_CHAT_URL", "safeloop.chatUrl", cfg.chatUrl);
        cfg.refactorModel = strVal("SAFELOOP_REFACTOR_MODEL", "safeloop.refactorModel", cfg.refactorModel);
        cfg.analyzerModel = strVal("SAFELOOP_ANALYZER_MODEL", "safeloop.analyzerModel", cfg.analyzerModel);

        cfg.refactorTemperature = doubleVal("SAFELOOP_REFACTOR_TEMP", "safeloop.refactorTemp", cfg.refactorTemperature);
        cfg.analyzerTemperature = doubleVal("SAFELOOP_ANALYZER_TEMP", "safeloop.analyzerTemp", cfg.analyzerTemperature);
        cfg.topP = doubleVal("SAFELOOP_TOP_P", "safeloop.topP", cfg.topP);
        cfg.maxTokens = intVal("SAFELOOP_MAX_TOKENS", "safeloop.maxTokens", cfg.maxTokens);

        cfg.safetyThreshold = doubleVal("SAFELOOP_SAFETY_THRESHOLD", "safeloop.safetyThreshold", cfg.safetyThreshold);
        cfg.maxIterations = intVal("SAFELOOP_MAX_ITERATIONS", "safeloop.maxIterations", cfg.maxIterations);

        cfg.maxChunks = intVal("SAFELOOP_MAX_CHUNKS", "safeloop.maxChunks", cfg.maxChunks);
        cfg.chatMemorySize = intVal("SAFELOOP_CHAT_MEMORY_SIZE", "safeloop.chatMemorySize", cfg.chatMemorySize);
        cfg.maxToolCalls = intVal("SAFELOOP_MAX_TOOL_CALLS", "safeloop.maxToolCalls", cfg.maxToolCalls);

        cfg.minCallerDepth = intVal("SAFELOOP_MIN_CALLER_DEPTH", "safeloop.minCallerDepth", cfg.minCallerDepth);
        cfg.minCalleeDepth = intVal("SAFELOOP_MIN_CALLEE_DEPTH", "safeloop.minCalleeDepth", cfg.minCalleeDepth);

        cfg.stopOnNoNewNodes = boolVal("SAFELOOP_STOP_NO_NEW_NODES", "safeloop.stopOnNoNewNodes", cfg.stopOnNoNewNodes);
        cfg.stopOnStagnation = boolVal("SAFELOOP_STOP_ON_STAGNATION", "safeloop.stopOnStagnation", cfg.stopOnStagnation);
        cfg.stream = boolVal("SAFELOOP_STREAM", "safeloop.stream", cfg.stream);

        cfg.selfReviewTemperature = doubleVal("SAFELOOP_SELF_REVIEW_TEMP", "safeloop.selfReviewTemp", cfg.selfReviewTemperature);

        cfg.structuredOutput = enumVal(
            "LLM_STRUCTURED_OUTPUT", "llm.structuredOutput",
            RefactorConfig.StructuredOutputMode.class, cfg.structuredOutput);

        cfg.repoRoot = strVal("SAFELOOP_REPO_ROOT", "safeloop.repoRoot", cfg.repoRoot);
        cfg.apply = boolVal("SAFELOOP_APPLY", "safeloop.apply", cfg.apply);
        cfg.dryRun = boolVal("SAFELOOP_DRY_RUN", "safeloop.dryRun", cfg.dryRun);
        cfg.backup = boolVal("SAFELOOP_BACKUP", "safeloop.backup", cfg.backup);

        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getChatUrl() { return chatUrl; }
    public String getRefactorModel() { return refactorModel; }
    public String getAnalyzerModel() { return analyzerModel; }
    public double getRefactorTemperature() { return refactorTemperature; }
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
    public boolean isStopOnNoNewNodes() { return stopOnNoNewNodes; }
    public boolean isStopOnStagnation() { return stopOnStagnation; }
    public boolean isStream() { return stream; }
    public RefactorConfig.StructuredOutputMode getStructuredOutput() { return structuredOutput; }
    public double getSelfReviewTemperature() { return selfReviewTemperature; }
    public String getRepoRoot() { return repoRoot; }
    public boolean isApply() { return apply; }
    public boolean isDryRun() { return dryRun; }
    public boolean isBackup() { return backup; }

    // ═══════════════════════════════════════════════════════════════
    // Builder-style setters
    // ═══════════════════════════════════════════════════════════════

    public SafeLoopConfig withChatUrl(String v) { this.chatUrl = v; return this; }
    public SafeLoopConfig withRefactorModel(String v) { this.refactorModel = v; return this; }
    public SafeLoopConfig withAnalyzerModel(String v) { this.analyzerModel = v; return this; }
    public SafeLoopConfig withRefactorTemperature(double v) { this.refactorTemperature = v; return this; }
    public SafeLoopConfig withAnalyzerTemperature(double v) { this.analyzerTemperature = v; return this; }
    public SafeLoopConfig withTopP(double v) { this.topP = v; return this; }
    public SafeLoopConfig withMaxTokens(int v) { this.maxTokens = v; return this; }
    public SafeLoopConfig withSafetyThreshold(double v) { this.safetyThreshold = v; return this; }
    public SafeLoopConfig withMaxIterations(int v) { this.maxIterations = v; return this; }
    public SafeLoopConfig withMaxChunks(int v) { this.maxChunks = v; return this; }
    public SafeLoopConfig withChatMemorySize(int v) { this.chatMemorySize = v; return this; }
    public SafeLoopConfig withMaxToolCalls(int v) { this.maxToolCalls = v; return this; }
    public SafeLoopConfig withMinCallerDepth(int v) { this.minCallerDepth = v; return this; }
    public SafeLoopConfig withMinCalleeDepth(int v) { this.minCalleeDepth = v; return this; }
    public SafeLoopConfig withStopOnNoNewNodes(boolean v) { this.stopOnNoNewNodes = v; return this; }
    public SafeLoopConfig withStopOnStagnation(boolean v) { this.stopOnStagnation = v; return this; }
    public SafeLoopConfig withStream(boolean v) { this.stream = v; return this; }
    public SafeLoopConfig withStructuredOutput(RefactorConfig.StructuredOutputMode v) {
        this.structuredOutput = v; return this;
    }
    public SafeLoopConfig withSelfReviewTemperature(double v) { this.selfReviewTemperature = v; return this; }
    public SafeLoopConfig withRepoRoot(String v) { this.repoRoot = v; return this; }
    public SafeLoopConfig withApply(boolean v) { this.apply = v; return this; }
    public SafeLoopConfig withDryRun(boolean v) { this.dryRun = v; return this; }
    public SafeLoopConfig withBackup(boolean v) { this.backup = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "SafeLoopConfig { url=%s, refactor=[model=%s, temp=%.2f], analyzer=[model=%s, temp=%.2f], " +
            "topP=%.2f, maxTokens=%d, safetyThreshold=%.2f, maxIter=%d, maxChunks=%d, " +
            "memory=%d, maxTools=%d, callerDepth=%d, calleeDepth=%d, stopNoNew=%s, stopStagnant=%s, stream=%s, structuredOutput=%s }",
            chatUrl,
            refactorModel.isEmpty() ? "(default)" : refactorModel, refactorTemperature,
            analyzerModel.isEmpty() ? "(default)" : analyzerModel, analyzerTemperature,
            topP, maxTokens, safetyThreshold, maxIterations, maxChunks,
            chatMemorySize, maxToolCalls, minCallerDepth, minCalleeDepth,
            stopOnNoNewNodes, stopOnStagnation, stream, structuredOutput
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

    private static <E extends Enum<E>> E enumVal(String envKey, String sysPropKey,
                                                 Class<E> enumType, E defaultValue) {
        String v = strVal(envKey, sysPropKey, null);
        if (v == null) return defaultValue;
        try { return Enum.valueOf(enumType, v.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return defaultValue; }
    }
}

