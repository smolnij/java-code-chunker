package com.smolnij.chunker.eval.scorer;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.List;

/**
 * Converts a {@link RunResult} (plus compile / test verifier outputs)
 * into a list of {@link Metric} observations.
 */
public interface Scorer {
    String name();
    List<Metric> score(Fixture fixture, RunResult result,
                       VerifierResult compile, VerifierResult tests);
}
