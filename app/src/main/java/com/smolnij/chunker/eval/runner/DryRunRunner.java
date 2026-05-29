package com.smolnij.chunker.eval.runner;

import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.result.RunResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthesizes a {@link RunResult} from a fixture's gold data without
 * hitting Neo4j or any LLM. Used by {@code EvalMain --dry-run} and
 * {@code --self-check} to verify the harness wiring end-to-end.
 *
 * <p>Under synthesis:
 * <ul>
 *   <li>{@code retrieved} is the full gold.relevant list, in order, with
 *       decreasing scores — yields precision@K=1.0, recall@K=1.0, MRR=1.0.</li>
 *   <li>{@code anchorId} equals {@code gold.anchor} — yields anchor.hit=PASS.</li>
 *   <li>For safeloop fixtures, {@code loopPayload.isSafe} mirrors
 *       {@code expected.analyzer == "SAFE"} — yields analyzer.verdict=PASS.</li>
 * </ul>
 */
public final class DryRunRunner implements ModeRunner {

    @Override
    public String modeName() { return "dry-run"; }

    @Override
    public RunResult run(Fixture fixture, RunContext ctx) {
        Instant startedAt = Instant.now();
        long t0 = System.currentTimeMillis();

        List<String> gold = fixture.gold().relevant();
        List<RetrievedChunk> retrieved = new ArrayList<>(gold.size());
        for (int i = 0; i < gold.size(); i++) {
            double score = Math.max(0.0, 1.0 - i / 10.0);
            retrieved.add(new RetrievedChunk(gold.get(i), score, i + 1));
        }

        JsonObject payload = null;
        String effectiveMode = fixture.mode() == null ? "" : fixture.mode().toLowerCase();
        if ("safeloop".equals(effectiveMode)) {
            payload = new JsonObject();
            boolean syntheticSafe = "SAFE".equalsIgnoreCase(fixture.expected().analyzer());
            payload.addProperty("isSafe", syntheticSafe);
            payload.addProperty("dryRun", true);
            payload.addProperty("terminalReason", syntheticSafe ? "SAFE" : "MAX_ITERATIONS");
        }

        return new RunResult(
                fixture.id(), effectiveMode,
                startedAt, System.currentTimeMillis() - t0,
                fixture.gold().anchor(), retrieved, payload, null);
    }
}
