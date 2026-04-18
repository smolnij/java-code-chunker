package com.smolnij.chunker.eval.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.refactor.RefactorAgent;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.HybridRetriever.RetrievalResponse;
import com.smolnij.chunker.retrieval.RetrievalResult;
import com.smolnij.chunker.safeloop.SafeLoopConfig;
import com.smolnij.chunker.safeloop.SafeLoopResult;
import com.smolnij.chunker.safeloop.SafeLoopTools;
import com.smolnij.chunker.safeloop.SafeRefactorLoop;
import com.smolnij.chunker.safeloop.SafetyVerdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a fixture through {@link SafeRefactorLoop} and emits a
 * structured JSON payload derived from the {@link SafeLoopResult}.
 *
 * <p>Before the loop runs, a single {@code HybridRetriever.retrieve}
 * call captures the initial top-K for retrieval scoring. The loop's
 * own agent-driven retrieval happens inside the loop and is not
 * re-measured here — precision/recall is about what the agent sees
 * at the outset.
 */
public final class SafeLoopRunner implements ModeRunner {

    @Override
    public String modeName() { return "safeloop"; }

    @Override
    public RunResult run(Fixture fixture, RunContext ctx) {
        Instant startedAt = Instant.now();
        long t0 = System.currentTimeMillis();

        int originalTopK = ctx.retrievalConfig().getTopK();
        if (fixture.topK() != null) ctx.retrievalConfig().withTopK(fixture.topK());

        SafeLoopConfig safeConfig = ctx.safeLoopConfig();
        List<RetrievedChunk> retrieved = List.of();
        String anchorId = null;

        try {
            RetrievalResponse response = ctx.retriever().retrieve(fixture.query());
            anchorId = response.getAnchorId();
            retrieved = new ArrayList<>();
            List<RetrievalResult> results = response.getResults();
            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                retrieved.add(new RetrievedChunk(r.getChunkId(), r.getFinalScore(), i + 1));
            }

            RefactorConfig refactorConfig = new RefactorConfig()
                    .withChatUrl(safeConfig.getChatUrl())
                    .withChatModel(safeConfig.getRefactorModel())
                    .withTemperature(safeConfig.getRefactorTemperature())
                    .withTopP(safeConfig.getTopP())
                    .withMaxTokens(safeConfig.getMaxTokens())
                    .withMaxChunks(safeConfig.getMaxChunks())
                    .withAgentMode(true)
                    .withMaxToolCalls(safeConfig.getMaxToolCalls())
                    .withChatMemorySize(safeConfig.getChatMemorySize());

            RefactorTools agentTools = new RefactorTools(
                    ctx.retriever(), ctx.reader(), safeConfig.getMaxChunks());
            AstDiffEngine diffEngine = new AstDiffEngine();
            DiffScorer diffScorer = new DiffScorer(ctx.reader());
            RefactorAgent agent = new RefactorAgent(refactorConfig, agentTools);

            SafeLoopResult result;
            try (ChatService analyzerChat = new LmStudioChatService(
                    safeConfig.getChatUrl(),
                    safeConfig.getAnalyzerModel(),
                    safeConfig.getAnalyzerTemperature(),
                    safeConfig.getTopP(),
                    safeConfig.getMaxTokens())) {

                SafeLoopTools loopTools = new SafeLoopTools(ctx.retriever(), ctx.reader(), safeConfig);
                SafeRefactorLoop loop = new SafeRefactorLoop(
                        agent, analyzerChat, loopTools, agentTools, safeConfig,
                        diffEngine, diffScorer);
                result = loop.run(fixture.query());
            }

            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), modeName(), startedAt, duration,
                    anchorId, retrieved, toPayload(result), null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), modeName(), startedAt, duration,
                    anchorId, retrieved, null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ctx.retrievalConfig().withTopK(originalTopK);
        }
    }

    private static JsonObject toPayload(SafeLoopResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("isSafe", r.isSafe());
        o.addProperty("finalConfidence", r.getFinalConfidence());
        o.addProperty("terminalReason", r.getTerminalReason().name());
        o.addProperty("iterationsUsed", r.getIterationsUsed());
        o.addProperty("totalToolCalls", r.getTotalToolCalls());
        o.addProperty("totalNodesRetrieved", r.getTotalNodesRetrieved());
        o.addProperty("hasCode", r.hasCode());

        JsonArray verdicts = new JsonArray();
        for (SafetyVerdict v : r.getVerdictHistory()) {
            JsonObject vj = new JsonObject();
            vj.addProperty("safe", v.isVerdictSafe());
            vj.addProperty("confidence", v.getConfidence());
            vj.addProperty("risks", v.getRisks().size());
            vj.addProperty("missingContext", v.getMissingContext().size());
            verdicts.add(vj);
        }
        o.add("verdictHistory", verdicts);

        JsonArray finalRisks = new JsonArray();
        for (SafetyVerdict.Risk risk : r.getFinalRisks()) {
            JsonObject rj = new JsonObject();
            rj.addProperty("severity", risk.getSeverity().name());
            rj.addProperty("description", risk.getDescription());
            finalRisks.add(rj);
        }
        o.add("finalRisks", finalRisks);

        return o;
    }
}
