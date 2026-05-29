package com.smolnij.chunker.eval.result;

import com.google.gson.JsonElement;

import java.time.Instant;
import java.util.List;

/**
 * Output of a single fixture run, before scoring.
 *
 * <p>{@code loopPayload} carries the raw loop-result object serialized
 * via Gson's reflective tree (e.g. {@code SafeLoopResult}'s fields) —
 * a {@link JsonElement} so the reporter can write it through verbatim
 * without needing a type adapter for every existing result class.
 */
public record RunResult(
        String fixtureId,
        String mode,
        Instant startedAt,
        long durationMs,
        String anchorId,
        List<RetrievedChunk> retrieved,
        JsonElement loopPayload,
        String error
) {
    public RunResult {
        if (retrieved == null) retrieved = List.of();
    }

    public boolean isError() {
        return error != null && !error.isBlank();
    }
}
