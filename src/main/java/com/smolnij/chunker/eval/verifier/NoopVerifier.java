package com.smolnij.chunker.eval.verifier;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;

/**
 * Default verifier: always returns {@link VerifierResult.Status#NOT_RUN}.
 * The harness ships this stub; N-1 swaps in the real verifier.
 */
public final class NoopVerifier implements Verifier {

    private static final String NOTE = "no verifier wired (N-1 pending)";

    @Override
    public VerifierResult verifyCompile(Fixture fixture, RunResult result) {
        return VerifierResult.notRun(NOTE);
    }

    @Override
    public VerifierResult verifyTests(Fixture fixture, RunResult result) {
        return VerifierResult.notRun(NOTE);
    }
}
