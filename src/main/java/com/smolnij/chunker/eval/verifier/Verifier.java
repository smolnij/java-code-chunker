package com.smolnij.chunker.eval.verifier;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;

/**
 * Applies a fixture's produced edit (when present) and reports whether
 * the resulting project compiles and its selected tests pass.
 *
 * <p>In this PR the default wiring is {@link NoopVerifier}; real
 * compile/test execution is owned by roadmap item N-1.
 */
public interface Verifier {
    VerifierResult verifyCompile(Fixture fixture, RunResult result);
    VerifierResult verifyTests(Fixture fixture, RunResult result);
}
