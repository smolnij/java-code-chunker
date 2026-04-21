package com.smolnij.chunker.apply;

/**
 * Pluggable safety check that runs immediately before
 * {@link ApplyTools#commitPlan(String)} hands a {@link PatchPlan} to
 * {@link PatchApplier}. Lets the enclosing loop reuse its analyzer to
 * veto unsafe edits without coupling {@link ApplyTools} to loop internals.
 */
@FunctionalInterface
public interface SafetyGate {

    Verdict evaluate(PatchPlan staged);

    record Verdict(boolean safe, double confidence, String reason) {}

    /** Always-allow gate; used by standalone callers (e.g. {@code ApplyMain}). */
    SafetyGate ALLOW_ALL = plan -> new Verdict(true, 1.0, "no safety gate configured");
}
