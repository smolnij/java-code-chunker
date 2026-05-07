package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.config.PropertiesLoader;
import com.smolnij.chunker.refactor.RefactorConfig;

import java.util.Properties;

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
 */
public class SafeLoopConfig {

    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String refactorModel = "";
    private String analyzerModel = "";

    private double refactorTemperature = 0.3;
    private double topP = 0.9;
    private int maxTokens = 66000;

    private double analyzerTemperature = 0.1;

    private double safetyThreshold = 0.9;
    private int maxIterations = 5;

    private int maxChunks = 8;
    private int chatMemorySize = 60;
    private int maxToolCalls = 30;

    private int minCallerDepth = 1;
    private int minCalleeDepth = 1;

    private boolean stopOnNoNewNodes = true;
    private boolean stopOnStagnation = true;

    private boolean stream = true;

    private double selfReviewTemperature = 0.05;

    private RefactorConfig.StructuredOutputMode structuredOutput =
        RefactorConfig.StructuredOutputMode.JSON_SCHEMA;

    private String repoRoot = "";
    private boolean apply = true;
    private boolean dryRun = false;
    private boolean backup = true;

    private boolean trace = true;

    private boolean reindexCascadeEnabled = true;
    private int reindexCascadeMaxFiles = 25;

    private boolean requireCompile = true;
    private String verifyMode = "auto";
    private int verifyMaxErrors = 25;
    private String classpathCacheDir = "";

    public static SafeLoopConfig fromProperties(Properties p) {
        SafeLoopConfig cfg = new SafeLoopConfig();

        cfg.chatUrl = PropertiesLoader.getString(p, "safeloop.chatUrl", cfg.chatUrl);
        cfg.refactorModel = PropertiesLoader.getString(p, "safeloop.refactorModel", cfg.refactorModel);
        cfg.analyzerModel = PropertiesLoader.getString(p, "safeloop.analyzerModel", cfg.analyzerModel);

        cfg.refactorTemperature = PropertiesLoader.getDouble(p, "safeloop.refactorTemp", cfg.refactorTemperature);
        cfg.analyzerTemperature = PropertiesLoader.getDouble(p, "safeloop.analyzerTemp", cfg.analyzerTemperature);
        cfg.topP = PropertiesLoader.getDouble(p, "safeloop.topP", cfg.topP);
        cfg.maxTokens = PropertiesLoader.getInt(p, "safeloop.maxTokens", cfg.maxTokens);

        cfg.safetyThreshold = PropertiesLoader.getDouble(p, "safeloop.safetyThreshold", cfg.safetyThreshold);
        cfg.maxIterations = PropertiesLoader.getInt(p, "safeloop.maxIterations", cfg.maxIterations);

        cfg.maxChunks = PropertiesLoader.getInt(p, "safeloop.maxChunks", cfg.maxChunks);
        cfg.chatMemorySize = PropertiesLoader.getInt(p, "safeloop.chatMemorySize", cfg.chatMemorySize);
        cfg.maxToolCalls = PropertiesLoader.getInt(p, "safeloop.maxToolCalls", cfg.maxToolCalls);

        cfg.minCallerDepth = PropertiesLoader.getInt(p, "safeloop.minCallerDepth", cfg.minCallerDepth);
        cfg.minCalleeDepth = PropertiesLoader.getInt(p, "safeloop.minCalleeDepth", cfg.minCalleeDepth);

        cfg.stopOnNoNewNodes = PropertiesLoader.getBoolean(p, "safeloop.stopOnNoNewNodes", cfg.stopOnNoNewNodes);
        cfg.stopOnStagnation = PropertiesLoader.getBoolean(p, "safeloop.stopOnStagnation", cfg.stopOnStagnation);
        cfg.stream = PropertiesLoader.getBoolean(p, "safeloop.stream", cfg.stream);

        cfg.selfReviewTemperature = PropertiesLoader.getDouble(p, "safeloop.selfReviewTemp", cfg.selfReviewTemperature);

        cfg.structuredOutput = PropertiesLoader.getEnum(p, "llm.structuredOutput",
            RefactorConfig.StructuredOutputMode.class, cfg.structuredOutput);

        cfg.repoRoot = PropertiesLoader.getString(p, "safeloop.repoRoot", cfg.repoRoot);
        cfg.apply = PropertiesLoader.getBoolean(p, "safeloop.apply", cfg.apply);
        cfg.dryRun = PropertiesLoader.getBoolean(p, "safeloop.dryRun", cfg.dryRun);
        cfg.backup = PropertiesLoader.getBoolean(p, "safeloop.backup", cfg.backup);
        cfg.trace = PropertiesLoader.getBoolean(p, "safeloop.trace", cfg.trace);

        cfg.reindexCascadeEnabled = PropertiesLoader.getBoolean(
            p, "safeloop.reindex.cascadeEnabled", cfg.reindexCascadeEnabled);
        cfg.reindexCascadeMaxFiles = PropertiesLoader.getInt(
            p, "safeloop.reindex.cascadeMaxFiles", cfg.reindexCascadeMaxFiles);

        cfg.requireCompile = PropertiesLoader.getBoolean(p, "apply.requireCompile", cfg.requireCompile);
        cfg.verifyMode = PropertiesLoader.getString(p, "verify.mode", cfg.verifyMode);
        cfg.verifyMaxErrors = PropertiesLoader.getInt(p, "verify.maxErrors", cfg.verifyMaxErrors);
        cfg.classpathCacheDir = PropertiesLoader.getString(p, "verify.classpathCacheDir", cfg.classpathCacheDir);

        return cfg;
    }

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
    public boolean isTrace() { return trace; }
    public boolean isReindexCascadeEnabled() { return reindexCascadeEnabled; }
    public int getReindexCascadeMaxFiles() { return reindexCascadeMaxFiles; }
    public boolean isRequireCompile() { return requireCompile; }
    public String getVerifyMode() { return verifyMode; }
    public int getVerifyMaxErrors() { return verifyMaxErrors; }
    public String getClasspathCacheDir() { return classpathCacheDir; }

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
    public SafeLoopConfig withTrace(boolean v) { this.trace = v; return this; }
    public SafeLoopConfig withReindexCascadeEnabled(boolean v) { this.reindexCascadeEnabled = v; return this; }
    public SafeLoopConfig withReindexCascadeMaxFiles(int v) { this.reindexCascadeMaxFiles = v; return this; }
    public SafeLoopConfig withRequireCompile(boolean v) { this.requireCompile = v; return this; }
    public SafeLoopConfig withVerifyMode(String v) { this.verifyMode = v; return this; }
    public SafeLoopConfig withVerifyMaxErrors(int v) { this.verifyMaxErrors = v; return this; }
    public SafeLoopConfig withClasspathCacheDir(String v) { this.classpathCacheDir = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "SafeLoopConfig { url=%s, refactor=[model=%s, temp=%.2f], analyzer=[model=%s, temp=%.2f], " +
            "topP=%.2f, maxTokens=%d, safetyThreshold=%.2f, maxIter=%d, maxChunks=%d, " +
            "memory=%d, maxTools=%d, callerDepth=%d, calleeDepth=%d, stopNoNew=%s, stopStagnant=%s, stream=%s, structuredOutput=%s, trace=%s }",
            chatUrl,
            refactorModel.isEmpty() ? "(default)" : refactorModel, refactorTemperature,
            analyzerModel.isEmpty() ? "(default)" : analyzerModel, analyzerTemperature,
            topP, maxTokens, safetyThreshold, maxIterations, maxChunks,
            chatMemorySize, maxToolCalls, minCallerDepth, minCalleeDepth,
            stopOnNoNewNodes, stopOnStagnation, stream, structuredOutput, trace
        );
    }
}
