package com.smolnij.chunker.ralph;

import com.smolnij.chunker.config.PropertiesLoader;

import java.util.Properties;

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
 */
public class RalphConfig {

    private String chatUrl = "http://localhost:1234/v1/chat/completions";
    private String workerModel = "";
    private String judgeModel = "";

    private double workerTemperature = 0.3;
    private double topP = 0.9;
    private int maxTokens = 4096;

    private double judgeTemperature = 0.1;

    private int maxIterations = 5;

    private boolean stream = true;

    private int maxChunks = 6;

    private boolean trace = false;

    public static RalphConfig fromProperties(Properties p) {
        RalphConfig cfg = new RalphConfig();
        cfg.chatUrl = PropertiesLoader.getString(p, "ralph.chatUrl", cfg.chatUrl);
        cfg.workerModel = PropertiesLoader.getString(p, "ralph.workerModel", cfg.workerModel);
        cfg.judgeModel = PropertiesLoader.getString(p, "ralph.judgeModel", cfg.judgeModel);
        cfg.workerTemperature = PropertiesLoader.getDouble(p, "ralph.workerTemp", cfg.workerTemperature);
        cfg.judgeTemperature = PropertiesLoader.getDouble(p, "ralph.judgeTemp", cfg.judgeTemperature);
        cfg.topP = PropertiesLoader.getDouble(p, "ralph.topP", cfg.topP);
        cfg.maxTokens = PropertiesLoader.getInt(p, "ralph.maxTokens", cfg.maxTokens);
        cfg.maxIterations = PropertiesLoader.getInt(p, "ralph.maxIterations", cfg.maxIterations);
        cfg.maxChunks = PropertiesLoader.getInt(p, "ralph.maxChunks", cfg.maxChunks);
        cfg.stream = PropertiesLoader.getBoolean(p, "ralph.stream", cfg.stream);
        cfg.trace = PropertiesLoader.getBoolean(p, "ralph.trace", cfg.trace);
        return cfg;
    }

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
}
