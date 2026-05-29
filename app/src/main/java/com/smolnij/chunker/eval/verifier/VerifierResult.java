package com.smolnij.chunker.eval.verifier;

/**
 * Outcome of a compile or test verification attempt. {@link Status#NOT_RUN}
 * is the default when no verifier is wired — reporters and scorers treat
 * it distinctly from FAIL so rollups don't count a missing verifier
 * against the pass-rate.
 */
public record VerifierResult(Status status, String note) {

    public enum Status { PASS, FAIL, NOT_RUN, ERROR }

    public static VerifierResult notRun(String note) {
        return new VerifierResult(Status.NOT_RUN, note);
    }

    public static VerifierResult pass(String note) {
        return new VerifierResult(Status.PASS, note);
    }

    public static VerifierResult fail(String note) {
        return new VerifierResult(Status.FAIL, note);
    }
}
