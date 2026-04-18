package com.smolnij.chunker.eval.scorer;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.List;

/**
 * Relays compile / test verifier outputs as metrics. With the default
 * {@link com.smolnij.chunker.eval.verifier.NoopVerifier} both relays
 * report {@code NOT_RUN} — real pass/fail lands with N-1.
 */
public final class BuildScorer implements Scorer {

    @Override
    public String name() { return "build"; }

    @Override
    public List<Metric> score(Fixture fixture, RunResult result,
                              VerifierResult compile, VerifierResult tests) {
        return List.of(
            toMetric("compile.pass", compile),
            toMetric("tests.pass", tests)
        );
    }

    private static Metric toMetric(String name, VerifierResult v) {
        if (v == null) return Metric.notRun(name, "no verifier");
        return switch (v.status()) {
            case PASS -> Metric.pass(name, 1.0, v.note());
            case FAIL -> Metric.fail(name, 0.0, v.note());
            case NOT_RUN -> Metric.notRun(name, v.note());
            case ERROR -> Metric.error(name, v.note());
        };
    }
}
