package com.smolnij.chunker.ralph;

/**
 * Configuration for the Ralph Wiggum Loop (worker/judge orchestrator).
 *
 * <p>Controls iteration limits, independent sampling parameters for the
 * worker and judge LLMs, and streaming behaviour.
 *
 * <h3>Design rationale:</h3>
 * <ul>
 *   <li>Worker gets slightly higher temperature (0.3) for creative solutions</li>
 *   <li>Judge gets low temperature (0.1) for precise, deterministic evaluation</li>
 *   <li>Separate model fields allow using different models for each role</li>
 * </ul>
 *
 * <h3>Defaults:</h3>
 * <pre>
 *   maxIterations       = 5
 *   workerTemperature   = 0.3
 *   judgeTemperature    = 0.1
 *   topP                = 0.9
 *   maxTokens           = 4096
 *   stream              = true
 *   chatUrl             = http://localhost:1234/v1/chat/completions
 *   workerModel         = (empty — use loaded model)
 *   judgeModel          = (empty — use loaded model)
 * </pre>
 */
public class RalphConfig {

    // ── LLM endpoints ──
    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String workerModel = "";
    private String judgeModel = "";

    // ── Worker sampling ──
    private double workerTemperature = 0.3;
    private double topP = 0.9;
    private int maxTokens = 4096;

    // ── Judge sampling ──
    private double judgeTemperature = 0.1;

    // ── Loop control ──
    private int maxIterations = 5;

    // ── Streaming ──
    private boolean stream = true;

    // ── Context ──
    private int maxChunks = 6;

    // ── Trace ──
    private boolean trace = false;

    // ═══════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════

    public static RalphConfig fromEnvironment() {
        RalphConfig cfg = new RalphConfig();

        cfg.chatUrl = strVal("RALPH_CHAT_URL", "ralph.chatUrl", cfg.chatUrl);
        cfg.workerModel = strVal("RALPH_WORKER_MODEL", "ralph.workerModel", cfg.workerModel);
        cfg.judgeModel = strVal("RALPH_JUDGE_MODEL", "ralph.judgeModel", cfg.judgeModel);

        cfg.workerTemperature = doubleVal("RALPH_WORKER_TEMP", "ralph.workerTemp", cfg.workerTemperature);
        cfg.judgeTemperature = doubleVal("RALPH_JUDGE_TEMP", "ralph.judgeTemp", cfg.judgeTemperature);
        cfg.topP = doubleVal("RALPH_TOP_P", "ralph.topP", cfg.topP);
        cfg.maxTokens = intVal("RALPH_MAX_TOKENS", "ralph.maxTokens", cfg.maxTokens);

        cfg.maxIterations = intVal("RALPH_MAX_ITERATIONS", "ralph.maxIterations", cfg.maxIterations);
        cfg.maxChunks = intVal("RALPH_MAX_CHUNKS", "ralph.maxChunks", cfg.maxChunks);
        cfg.stream = boolVal("RALPH_STREAM", "ralph.stream", cfg.stream);
        cfg.trace = boolVal("RALPH_TRACE", "ralph.trace", cfg.trace);

        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getChatUrl() { return chatUrl; }
    public String getWorkerModel() { return workerModel; }
    public String getJudgeModel() { return judgeModel; }
    public double getWorkerTemperature() { return workerTemperature; }
    public double getJudgeTemperature() { return judgeTemperature; }
    public double getTopP() { return topP; }
    public int getMaxTokens() { return maxTokens; }
    public int getMaxIterations() { return maxIterations; }
    public int getMaxChunks() { return maxChunks; }
    public boolean isStream() { return stream; }
    public boolean isTrace() { return trace; }

    // ═══════════════════════════════════════════════════════════════
    // Builder-style setters
    // ═══════════════════════════════════════════════════════════════

    public RalphConfig withChatUrl(String v) { this.chatUrl = v; return this; }
    public RalphConfig withWorkerModel(String v) { this.workerModel = v; return this; }
    public RalphConfig withJudgeModel(String v) { this.judgeModel = v; return this; }
    public RalphConfig withWorkerTemperature(double v) { this.workerTemperature = v; return this; }
    public RalphConfig withJudgeTemperature(double v) { this.judgeTemperature = v; return this; }
    public RalphConfig withTopP(double v) { this.topP = v; return this; }
    public RalphConfig withMaxTokens(int v) { this.maxTokens = v; return this; }
    public RalphConfig withMaxIterations(int v) { this.maxIterations = v; return this; }
    public RalphConfig withMaxChunks(int v) { this.maxChunks = v; return this; }
    public RalphConfig withStream(boolean v) { this.stream = v; return this; }
    public RalphConfig withTrace(boolean v) { this.trace = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "RalphConfig { url=%s, worker=[model=%s, temp=%.2f], judge=[model=%s, temp=%.2f], " +
            "topP=%.2f, maxTokens=%d, maxIter=%d, maxChunks=%d, stream=%s, trace=%s }",
            chatUrl,
            workerModel.isEmpty() ? "(default)" : workerModel, workerTemperature,
            judgeModel.isEmpty() ? "(default)" : judgeModel, judgeTemperature,
            topP, maxTokens, maxIterations, maxChunks, stream, trace
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

