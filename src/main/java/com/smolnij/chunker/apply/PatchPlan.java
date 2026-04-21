package com.smolnij.chunker.apply;

import java.util.List;

/**
 * A deterministic, structured description of the file-level changes the LLM
 * proposes. Produced by {@link com.smolnij.chunker.refactor.LlmResponseParser}
 * (either directly from {@code patch_plan} JSON or derived from {@code code_blocks})
 * and consumed by {@link PatchApplier}.
 *
 * @param ops        ordered list of edit operations; applied as a single atomic batch
 * @param rationale  one-paragraph justification for the change set (for logs / reports)
 * @param proposedBy the loop mode that produced the plan (e.g. {@code "safeloop"}, {@code "refactor"})
 */
public record PatchPlan(List<EditOp> ops, String rationale, String proposedBy) {

    public PatchPlan {
        ops = List.copyOf(ops);
        rationale = rationale == null ? "" : rationale;
        proposedBy = proposedBy == null ? "" : proposedBy;
    }

    public static PatchPlan empty(String proposedBy) {
        return new PatchPlan(List.of(), "", proposedBy);
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }
}
