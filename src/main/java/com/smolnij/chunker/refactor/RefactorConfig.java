package com.smolnij.chunker.refactor;

/**
 * Configuration for the graph-aware LLM refactoring loop.
 *
 * <p>Controls the LLM chat endpoint, sampling parameters, and
 * refinement-loop behaviour. All values can be overridden via
 * environment variables or system properties (system props win).
 *
 * <h3>Defaults:</h3>
 * <pre>
 *   chatUrl          = http://localhost:1234/v1/chat/completions
 *   chatModel        = (empty → use whatever is loaded in LM-Studio)
 *   temperature      = 0.1
 *   topP             = 0.9
 *   maxTokens        = 4096
 *   maxChunks        = 6      (4–8 works best for local models)
 *   maxRefinements   = 2      (how many expand-and-retry rounds)
 *   stream           = true   (SSE streaming from LM-Studio)
 *   agentMode        = false  (true → use LangChain4j agentic loop with tool calling)
 *   maxToolCalls     = 20     (safety cap on LLM tool invocations per conversation)
 *   chatMemorySize   = 40     (sliding window of messages for agent memory)
 * </pre>
 */
public class RefactorConfig {

    /**
     * How the /v1/chat/completions endpoint should constrain its reply.
     * {@link #OFF} preserves legacy behavior (free-form text + regex parsers).
     */
    public enum StructuredOutputMode { OFF, JSON_SCHEMA, JSON_OBJECT, TOOL_CALL }

    // ── LLM endpoint ──
    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String chatModel = "";

    // ── Sampling ──
    private double temperature = 0.1;
    private double topP = 0.9;
    private int maxTokens = 4096;

    // ── Context window ──
    private int maxChunks = 6;

    // ── Refinement loop ──
    private int maxRefinements = 2;

    // ── Streaming ──
    private boolean stream = true;

    // ── Agent mode (LangChain4j) ──
    private boolean agentMode = false;
    private int maxToolCalls = 20;
    private int chatMemorySize = 40;

    // ── Structured output (response_format / tool-call) ──
    private StructuredOutputMode structuredOutput = StructuredOutputMode.JSON_SCHEMA;

    // ── Patch apply (deterministic file edits after SAFE verdict) ──
    private String repoRoot = "";
    private boolean apply = false;
    private boolean dryRun = true;
    private boolean backup = true;

    // ═══════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════

    public static RefactorConfig fromEnvironment() {
        RefactorConfig cfg = new RefactorConfig();

        cfg.chatUrl = strVal("LLM_CHAT_URL", "llm.chatUrl", cfg.chatUrl);
        cfg.chatModel = strVal("LLM_CHAT_MODEL", "llm.chatModel", cfg.chatModel);

        cfg.temperature = doubleVal("LLM_TEMPERATURE", "llm.temperature", cfg.temperature);
        cfg.topP = doubleVal("LLM_TOP_P", "llm.topP", cfg.topP);
        cfg.maxTokens = intVal("LLM_MAX_TOKENS", "llm.maxTokens", cfg.maxTokens);

        cfg.maxChunks = intVal("REFACTOR_MAX_CHUNKS", "refactor.maxChunks", cfg.maxChunks);
        cfg.maxRefinements = intVal("REFACTOR_MAX_REFINEMENTS", "refactor.maxRefinements", cfg.maxRefinements);
        cfg.stream = boolVal("REFACTOR_STREAM", "refactor.stream", cfg.stream);

        cfg.agentMode = boolVal("REFACTOR_AGENT_MODE", "refactor.agentMode", cfg.agentMode);
        cfg.maxToolCalls = intVal("REFACTOR_MAX_TOOL_CALLS", "refactor.maxToolCalls", cfg.maxToolCalls);
        cfg.chatMemorySize = intVal("REFACTOR_CHAT_MEMORY_SIZE", "refactor.chatMemorySize", cfg.chatMemorySize);

        cfg.structuredOutput = enumVal(
            "LLM_STRUCTURED_OUTPUT", "llm.structuredOutput",
            StructuredOutputMode.class, cfg.structuredOutput);

        cfg.repoRoot = strVal("REFACTOR_REPO_ROOT", "refactor.repoRoot", cfg.repoRoot);
        cfg.apply = boolVal("REFACTOR_APPLY", "refactor.apply", cfg.apply);
        cfg.dryRun = boolVal("REFACTOR_DRY_RUN", "refactor.dryRun", cfg.dryRun);
        cfg.backup = boolVal("REFACTOR_BACKUP", "refactor.backup", cfg.backup);

        return cfg;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getChatUrl() { return chatUrl; }
    public String getChatModel() { return chatModel; }
    public double getTemperature() { return temperature; }
    public double getTopP() { return topP; }
    public int getMaxTokens() { return maxTokens; }
    public int getMaxChunks() { return maxChunks; }
    public int getMaxRefinements() { return maxRefinements; }
    public boolean isStream() { return stream; }
    public boolean isAgentMode() { return agentMode; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getChatMemorySize() { return chatMemorySize; }
    public StructuredOutputMode getStructuredOutput() { return structuredOutput; }
    public String getRepoRoot() { return repoRoot; }
    public boolean isApply() { return apply; }
    public boolean isDryRun() { return dryRun; }
    public boolean isBackup() { return backup; }

    // ═══════════════════════════════════════════════════════════════
    // Builder-style setters
    // ═══════════════════════════════════════════════════════════════

    public RefactorConfig withChatUrl(String v) { this.chatUrl = v; return this; }
    public RefactorConfig withChatModel(String v) { this.chatModel = v; return this; }
    public RefactorConfig withTemperature(double v) { this.temperature = v; return this; }
    public RefactorConfig withTopP(double v) { this.topP = v; return this; }
    public RefactorConfig withMaxTokens(int v) { this.maxTokens = v; return this; }
    public RefactorConfig withMaxChunks(int v) { this.maxChunks = v; return this; }
    public RefactorConfig withMaxRefinements(int v) { this.maxRefinements = v; return this; }
    public RefactorConfig withStream(boolean v) { this.stream = v; return this; }
    public RefactorConfig withAgentMode(boolean v) { this.agentMode = v; return this; }
    public RefactorConfig withMaxToolCalls(int v) { this.maxToolCalls = v; return this; }
    public RefactorConfig withChatMemorySize(int v) { this.chatMemorySize = v; return this; }
    public RefactorConfig withStructuredOutput(StructuredOutputMode v) { this.structuredOutput = v; return this; }
    public RefactorConfig withRepoRoot(String v) { this.repoRoot = v; return this; }
    public RefactorConfig withApply(boolean v) { this.apply = v; return this; }
    public RefactorConfig withDryRun(boolean v) { this.dryRun = v; return this; }
    public RefactorConfig withBackup(boolean v) { this.backup = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "RefactorConfig { chatUrl=%s, model=%s, temp=%.2f, topP=%.2f, maxTokens=%d, " +
            "maxChunks=%d, maxRefinements=%d, stream=%s, agentMode=%s, maxToolCalls=%d, chatMemorySize=%d, structuredOutput=%s }",
            chatUrl, chatModel.isEmpty() ? "(default)" : chatModel,
            temperature, topP, maxTokens, maxChunks, maxRefinements, stream,
            agentMode, maxToolCalls, chatMemorySize, structuredOutput
        );
    }

    // ── Helpers (same pattern as RetrievalConfig) ──

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

