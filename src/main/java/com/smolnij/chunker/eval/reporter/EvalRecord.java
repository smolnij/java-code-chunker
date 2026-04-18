package com.smolnij.chunker.eval.reporter;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.scorer.Metric;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.List;

/**
 * Per-fixture bundle passed to reporters: the input fixture, the raw
 * run output, the verifier outputs that fed scoring, and the resulting
 * metrics.
 */
public record EvalRecord(
        Fixture fixture,
        RunResult result,
        VerifierResult compile,
        VerifierResult tests,
        List<Metric> metrics
) {}
