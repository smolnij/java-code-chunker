package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.config.PropertiesLoader;

import java.util.Properties;

/**
 * Configuration for the distributed safe refactoring loop.
 *
 * <p>Explicitly separates the refactoring (Generator) and analysis
 * (Planner-Analyzer) LLMs onto different machines.
 */
public class DistributedSafeLoopConfig {

    private String refactorUrl = "http://REFACTORM:1234/v1/chat/completions";
    private String refactorModel = "";
    private double refactorTemperature = 0.3;

    private String analyzerUrl = "http://SANALYZEM:1234/v1/chat/completions";
    private String analyzerModel = "";
    private double analyzerTemperature = 0.1;

    private double topP = 0.9;
    private int maxTokens = 66000;

    private double safetyThreshold = 0.9;
    private int maxIterations = 5;

    private int maxChunks = 8;
    private int chatMemorySize = 60;
    private int maxToolCalls = 30;

    private int minCallerDepth = 1;
    private int minCalleeDepth = 1;

    private int maxPlannerSteps = 8;
    private int maxChunksPerRetrieval = 10;
    private int maxRetrievalDepth = 2;

    private boolean stopOnNoNewNodes = true;
    private boolean stopOnStagnation = true;

    private boolean stream = true;

    private boolean trace = false;

    public static DistributedSafeLoopConfig fromProperties(Properties p) {
        DistributedSafeLoopConfig cfg = new DistributedSafeLoopConfig();

        cfg.refactorUrl = PropertiesLoader.getString(p, "dist.refactorUrl", cfg.refactorUrl);
        cfg.refactorModel = PropertiesLoader.getString(p, "dist.refactorModel", cfg.refactorModel);
        cfg.refactorTemperature = PropertiesLoader.getDouble(p, "dist.refactorTemp", cfg.refactorTemperature);

        cfg.analyzerUrl = PropertiesLoader.getString(p, "dist.analyzerUrl", cfg.analyzerUrl);
        cfg.analyzerModel = PropertiesLoader.getString(p, "dist.analyzerModel", cfg.analyzerModel);
        cfg.analyzerTemperature = PropertiesLoader.getDouble(p, "dist.analyzerTemp", cfg.analyzerTemperature);

        cfg.topP = PropertiesLoader.getDouble(p, "dist.topP", cfg.topP);
        cfg.maxTokens = PropertiesLoader.getInt(p, "dist.maxTokens", cfg.maxTokens);
        cfg.safetyThreshold = PropertiesLoader.getDouble(p, "dist.safetyThreshold", cfg.safetyThreshold);
        cfg.maxIterations = PropertiesLoader.getInt(p, "dist.maxIterations", cfg.maxIterations);
        cfg.maxChunks = PropertiesLoader.getInt(p, "dist.maxChunks", cfg.maxChunks);
        cfg.chatMemorySize = PropertiesLoader.getInt(p, "dist.chatMemorySize", cfg.chatMemorySize);
        cfg.maxToolCalls = PropertiesLoader.getInt(p, "dist.maxToolCalls", cfg.maxToolCalls);
        cfg.minCallerDepth = PropertiesLoader.getInt(p, "dist.minCallerDepth", cfg.minCallerDepth);
        cfg.minCalleeDepth = PropertiesLoader.getInt(p, "dist.minCalleeDepth", cfg.minCalleeDepth);
        cfg.maxPlannerSteps = PropertiesLoader.getInt(p, "dist.maxPlannerSteps", cfg.maxPlannerSteps);
        cfg.maxChunksPerRetrieval = PropertiesLoader.getInt(p, "dist.maxChunksPerRetrieval", cfg.maxChunksPerRetrieval);
        cfg.maxRetrievalDepth = PropertiesLoader.getInt(p, "dist.maxRetrievalDepth", cfg.maxRetrievalDepth);
        cfg.stopOnNoNewNodes = PropertiesLoader.getBoolean(p, "dist.stopOnNoNewNodes", cfg.stopOnNoNewNodes);
        cfg.stopOnStagnation = PropertiesLoader.getBoolean(p, "dist.stopOnStagnation", cfg.stopOnStagnation);
        cfg.stream = PropertiesLoader.getBoolean(p, "dist.stream", cfg.stream);
        cfg.trace = PropertiesLoader.getBoolean(p, "dist.trace", cfg.trace);

        return cfg;
    }

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
    public boolean isTrace() { return trace; }

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
    public DistributedSafeLoopConfig withTrace(boolean v) { this.trace = v; return this; }

    @Override
    public String toString() {
        return String.format(
            "DistributedSafeLoopConfig {\n" +
            "  Generator: url=%s, model=%s, temp=%.2f\n" +
            "  Planner-Analyzer: url=%s, model=%s, temp=%.2f\n" +
            "  Shared: topP=%.2f, maxTokens=%d, threshold=%.2f, maxIter=%d,\n" +
            "          maxChunks=%d, memory=%d, maxTools=%d,\n" +
            "          callerDepth=%d, calleeDepth=%d, stopNoNew=%s, stopStagnant=%s, stream=%s,\n" +
            "          plannerSteps=%d, chunksPerRetrieval=%d, retrievalDepth=%d, trace=%s\n" +
            "}",
            refactorUrl,
            refactorModel.isEmpty() ? "(default)" : refactorModel, refactorTemperature,
            analyzerUrl,
            analyzerModel.isEmpty() ? "(default)" : analyzerModel, analyzerTemperature,
            topP, maxTokens, safetyThreshold, maxIterations, maxChunks,
            chatMemorySize, maxToolCalls, minCallerDepth, minCalleeDepth,
            stopOnNoNewNodes, stopOnStagnation, stream,
            maxPlannerSteps, maxChunksPerRetrieval, maxRetrievalDepth, trace
        );
    }
}
