package com.smolnij.chunker.eval.scorer;

import com.google.gson.JsonElement;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.List;

/**
 * Scores the analyzer verdict for modes that produce one. Today that
 * is safeloop only — retrieval emits NOT_RUN; refactor/ralph/agent
 * will be handled when those runners land.
 */
public final class AnalyzerScorer implements Scorer {

    private static final String METRIC = "analyzer.verdict";

    @Override
    public String name() { return "analyzer"; }

    @Override
    public List<Metric> score(Fixture fixture, RunResult result,
                              VerifierResult compile, VerifierResult tests) {
        if (result.isError()) {
            return List.of(Metric.error(METRIC, result.error()));
        }

        String mode = result.mode() == null ? "" : result.mode().toLowerCase();
        if ("retrieval".equals(mode) || "dry-run".equals(mode)) {
            return List.of(Metric.notRun(METRIC, "not applicable to " + mode + " mode"));
        }

        if (!"safeloop".equals(mode)) {
            return List.of(Metric.notRun(METRIC, "analyzer scoring not yet wired for mode=" + mode));
        }

        String expected = fixture.expected().analyzer();
        if (expected == null || expected.isBlank()) {
            return List.of(Metric.notRun(METRIC, "no expected.analyzer assertion"));
        }

        JsonElement payload = result.loopPayload();
        if (payload == null || !payload.isJsonObject()
                || !payload.getAsJsonObject().has("isSafe")) {
            return List.of(Metric.error(METRIC, "loopPayload missing isSafe"));
        }

        boolean isSafe = payload.getAsJsonObject().get("isSafe").getAsBoolean();
        String actual = isSafe ? "SAFE" : "UNSAFE";
        boolean matched = expected.equalsIgnoreCase(actual);
        String note = "expected=" + expected + ", actual=" + actual;
        double value = matched ? 1.0 : 0.0;
        return List.of(matched ? Metric.pass(METRIC, value, note)
                               : Metric.fail(METRIC, value, note));
    }
}
