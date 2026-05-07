package com.smolnij.chunker.eval.scorer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Surfaces health signals from the {@code SafeLoopResult} payload that
 * {@link com.smolnij.chunker.eval.runner.SafeLoopRunner} writes into
 * {@link RunResult#loopPayload()}. Without this scorer a loop that finishes
 * with {@code terminalReason=ERROR} after 0 iterations looks identical to a
 * clean SAFE run from {@link com.smolnij.chunker.eval.result.RunResult#isError()}'s
 * perspective — the runner caught the inner failure and produced a result.
 *
 * <p>Three metrics, all safeloop-only:
 * <ul>
 *   <li>{@code safeloop.terminal} — PASS for any non-error terminal reason
 *       ({@code SAFE/MAX_ITERATIONS/CONVERGED/STAGNANT}), FAIL for {@code ERROR};
 *       the most important new failure signal.</li>
 *   <li>{@code safeloop.iterations} — value carries iterationsUsed; FAIL when 0.</li>
 *   <li>{@code safeloop.toolCalls} — value carries totalToolCalls; informational
 *       PASS so a sudden jump (e.g. a 200-call commitPlan storm) shows up as a
 *       baseline regression on the {@code value} delta even when status is fine.</li>
 * </ul>
 *
 * <p>In dry-run mode the scorer emits NOT_RUN: {@link com.smolnij.chunker.eval.runner.DryRunRunner}
 * synthesizes a partial payload (no iterations/toolCalls) and exists to verify
 * harness wiring, not loop behaviour.
 */
public final class SafeLoopScorer implements Scorer {

    private static final String M_TERMINAL = "safeloop.terminal";
    private static final String M_ITERATIONS = "safeloop.iterations";
    private static final String M_TOOL_CALLS = "safeloop.toolCalls";

    @Override
    public String name() { return "safeloop"; }

    @Override
    public List<Metric> score(Fixture fixture, RunResult result,
                              VerifierResult compile, VerifierResult tests) {
        String mode = result.mode() == null ? "" : result.mode().toLowerCase();
        if (!"safeloop".equals(mode)) {
            return List.of(
                Metric.notRun(M_TERMINAL, "not applicable to mode=" + mode),
                Metric.notRun(M_ITERATIONS, "not applicable to mode=" + mode),
                Metric.notRun(M_TOOL_CALLS, "not applicable to mode=" + mode)
            );
        }

        if (result.isError()) {
            return List.of(
                Metric.error(M_TERMINAL, result.error()),
                Metric.error(M_ITERATIONS, result.error()),
                Metric.error(M_TOOL_CALLS, result.error())
            );
        }

        JsonElement payload = result.loopPayload();
        if (payload == null || !payload.isJsonObject()) {
            return List.of(
                Metric.error(M_TERMINAL, "loopPayload missing"),
                Metric.error(M_ITERATIONS, "loopPayload missing"),
                Metric.error(M_TOOL_CALLS, "loopPayload missing")
            );
        }
        JsonObject obj = payload.getAsJsonObject();

        // DryRunRunner synthesizes a stub payload (only isSafe + terminalReason)
        // to keep AnalyzerScorer happy. iterationsUsed/totalToolCalls aren't real,
        // so don't fail the run on their absence.
        boolean dryRun = obj.has("dryRun") && obj.get("dryRun").getAsBoolean();

        List<Metric> out = new ArrayList<>(3);
        out.add(terminalMetric(obj));
        if (dryRun) {
            out.add(Metric.notRun(M_ITERATIONS, "dry-run"));
            out.add(Metric.notRun(M_TOOL_CALLS, "dry-run"));
        } else {
            out.add(iterationsMetric(obj));
            out.add(toolCallsMetric(obj));
        }
        return out;
    }

    private static Metric terminalMetric(JsonObject obj) {
        if (!obj.has("terminalReason") || obj.get("terminalReason").isJsonNull()) {
            return Metric.error(M_TERMINAL, "loopPayload missing terminalReason");
        }
        // Mirrors com.smolnij.chunker.safeloop.SafeLoopResult.TerminalReason: any
        // non-ERROR value means the loop reached an orderly stop (even if UNSAFE).
        String reason = obj.get("terminalReason").getAsString();
        boolean ok = !"ERROR".equals(reason);
        double value = ok ? 1.0 : 0.0;
        String note = "terminalReason=" + reason;
        return ok ? Metric.pass(M_TERMINAL, value, note) : Metric.fail(M_TERMINAL, value, note);
    }

    private static Metric iterationsMetric(JsonObject obj) {
        if (!obj.has("iterationsUsed") || obj.get("iterationsUsed").isJsonNull()) {
            return Metric.error(M_ITERATIONS, "loopPayload missing iterationsUsed");
        }
        int n = obj.get("iterationsUsed").getAsInt();
        String note = "iterationsUsed=" + n;
        return n > 0 ? Metric.pass(M_ITERATIONS, n, note)
                     : Metric.fail(M_ITERATIONS, 0.0, note + " (loop did no work)");
    }

    private static Metric toolCallsMetric(JsonObject obj) {
        if (!obj.has("totalToolCalls") || obj.get("totalToolCalls").isJsonNull()) {
            return Metric.notRun(M_TOOL_CALLS, "loopPayload missing totalToolCalls");
        }
        int n = obj.get("totalToolCalls").getAsInt();
        // Informational: never FAIL on absolute count — baseline diff catches drift.
        return Metric.pass(M_TOOL_CALLS, n, "totalToolCalls=" + n);
    }
}
