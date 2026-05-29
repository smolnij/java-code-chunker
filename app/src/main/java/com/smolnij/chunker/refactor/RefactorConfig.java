package com.smolnij.chunker.refactor;

import com.smolnij.chunker.config.PropertiesLoader;

import java.util.Properties;

/**
 * Configuration for the graph-aware LLM refactoring loop.
 *
 * <p>Controls the LLM chat endpoint, sampling parameters, and refinement-loop
 * behaviour. Loaded from a per-main {@code .properties} file via
 * {@link #fromProperties(Properties)}.
 */
public class RefactorConfig {

    /**
     * How the /v1/chat/completions endpoint should constrain its reply.
     * {@link #OFF} preserves legacy behavior (free-form text + regex parsers).
     */
    public enum StructuredOutputMode { OFF, JSON_SCHEMA, JSON_OBJECT, TOOL_CALL }

    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String chatModel = "";

    private double temperature = 0.1;
    private double topP = 0.9;
    private int maxTokens = 66000;

    private int maxChunks = 6;

    private int maxRefinements = 2;

    private boolean stream = true;

    private boolean agentMode = false;
    private int maxToolCalls = 20;
    private int chatMemorySize = 40;

    private StructuredOutputMode structuredOutput = StructuredOutputMode.JSON_SCHEMA;

    private String repoRoot = "";
    private boolean apply = false;
    private boolean dryRun = true;
    private boolean backup = true;

    private boolean requireCompile = true;
    private String verifyMode = "auto";   // fast | full | auto
    private int verifyMaxErrors = 25;
    private String classpathCacheDir = ""; // empty → <repoRoot>/target

    private boolean trace = true;

    public static RefactorConfig fromProperties(Properties p) {
        RefactorConfig cfg = new RefactorConfig();

        cfg.chatUrl = PropertiesLoader.getString(p, "llm.chatUrl", cfg.chatUrl);
        cfg.chatModel = PropertiesLoader.getString(p, "llm.chatModel", cfg.chatModel);

        cfg.temperature = PropertiesLoader.getDouble(p, "llm.temperature", cfg.temperature);
        cfg.topP = PropertiesLoader.getDouble(p, "llm.topP", cfg.topP);
        cfg.maxTokens = PropertiesLoader.getInt(p, "llm.maxTokens", cfg.maxTokens);

        cfg.maxChunks = PropertiesLoader.getInt(p, "refactor.maxChunks", cfg.maxChunks);
        cfg.maxRefinements = PropertiesLoader.getInt(p, "refactor.maxRefinements", cfg.maxRefinements);
        cfg.stream = PropertiesLoader.getBoolean(p, "refactor.stream", cfg.stream);

        cfg.agentMode = PropertiesLoader.getBoolean(p, "refactor.agentMode", cfg.agentMode);
        cfg.maxToolCalls = PropertiesLoader.getInt(p, "refactor.maxToolCalls", cfg.maxToolCalls);
        cfg.chatMemorySize = PropertiesLoader.getInt(p, "refactor.chatMemorySize", cfg.chatMemorySize);

        cfg.structuredOutput = PropertiesLoader.getEnum(p, "llm.structuredOutput",
            StructuredOutputMode.class, cfg.structuredOutput);

        cfg.repoRoot = PropertiesLoader.getString(p, "refactor.repoRoot", cfg.repoRoot);
        cfg.apply = PropertiesLoader.getBoolean(p, "refactor.apply", cfg.apply);
        cfg.dryRun = PropertiesLoader.getBoolean(p, "refactor.dryRun", cfg.dryRun);
        cfg.backup = PropertiesLoader.getBoolean(p, "refactor.backup", cfg.backup);

        cfg.requireCompile = PropertiesLoader.getBoolean(p, "apply.requireCompile", cfg.requireCompile);
        cfg.verifyMode = PropertiesLoader.getString(p, "verify.mode", cfg.verifyMode);
        cfg.verifyMaxErrors = PropertiesLoader.getInt(p, "verify.maxErrors", cfg.verifyMaxErrors);
        cfg.classpathCacheDir = PropertiesLoader.getString(p, "verify.classpathCacheDir", cfg.classpathCacheDir);

        cfg.trace = PropertiesLoader.getBoolean(p, "refactor.trace", cfg.trace);

        return cfg;
    }

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
    public boolean isRequireCompile() { return requireCompile; }
    public String getVerifyMode() { return verifyMode; }
    public int getVerifyMaxErrors() { return verifyMaxErrors; }
    public String getClasspathCacheDir() { return classpathCacheDir; }
    public boolean isTrace() { return trace; }

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
    public RefactorConfig withRequireCompile(boolean v) { this.requireCompile = v; return this; }
    public RefactorConfig withVerifyMode(String v) { this.verifyMode = v; return this; }
    public RefactorConfig withVerifyMaxErrors(int v) { this.verifyMaxErrors = v; return this; }
    public RefactorConfig withClasspathCacheDir(String v) { this.classpathCacheDir = v; return this; }
    public RefactorConfig withTrace(boolean v) { this.trace = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "RefactorConfig { chatUrl=%s, model=%s, temp=%.2f, topP=%.2f, maxTokens=%d, " +
            "maxChunks=%d, maxRefinements=%d, stream=%s, agentMode=%s, maxToolCalls=%d, chatMemorySize=%d, structuredOutput=%s, trace=%s }",
            chatUrl, chatModel.isEmpty() ? "(default)" : chatModel,
            temperature, topP, maxTokens, maxChunks, maxRefinements, stream,
            agentMode, maxToolCalls, chatMemorySize, structuredOutput, trace
        );
    }
}
