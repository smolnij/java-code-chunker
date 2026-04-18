package com.smolnij.chunker.eval.runner;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.retrieval.HybridRetriever.RetrievalResponse;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the retrieval pipeline only — no LLM calls, no loop.
 * Captures the ranked top-K and the resolved anchor for scoring.
 */
public final class RetrievalRunner implements ModeRunner {

    @Override
    public String modeName() { return "retrieval"; }

    @Override
    public RunResult run(Fixture fixture, RunContext ctx) {
        Instant startedAt = Instant.now();
        long t0 = System.currentTimeMillis();

        int originalTopK = ctx.retrievalConfig().getTopK();
        if (fixture.topK() != null) ctx.retrievalConfig().withTopK(fixture.topK());

        try {
            RetrievalResponse response = ctx.retriever().retrieve(fixture.query());
            List<RetrievedChunk> retrieved = new ArrayList<>();
            List<RetrievalResult> results = response.getResults();
            for (int i = 0; i < results.size(); i++) {
                RetrievalResult r = results.get(i);
                retrieved.add(new RetrievedChunk(r.getChunkId(), r.getFinalScore(), i + 1));
            }
            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), modeName(), startedAt, duration,
                    response.getAnchorId(), retrieved, null, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - t0;
            return new RunResult(
                    fixture.id(), modeName(), startedAt, duration,
                    null, List.of(), null,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ctx.retrievalConfig().withTopK(originalTopK);
        }
    }
}
