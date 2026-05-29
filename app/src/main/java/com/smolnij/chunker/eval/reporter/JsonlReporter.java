package com.smolnij.chunker.eval.reporter;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.scorer.Metric;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes {@code runs.jsonl} — one compact JSON record per fixture,
 * line-delimited. Schema keys are stable so {@link BaselineDiffReporter}
 * can re-parse runs from a prior evaluation.
 */
public final class JsonlReporter implements Reporter {

    public static final String FILENAME = "runs.jsonl";

    private static final Gson GSON = new Gson();

    @Override
    public void write(Path outDir, List<EvalRecord> records) throws IOException {
        Files.createDirectories(outDir);
        Path file = outDir.resolve(FILENAME);
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            for (EvalRecord rec : records) {
                w.write(GSON.toJson(toJson(rec)));
                w.newLine();
            }
        }
    }

    static JsonObject toJson(EvalRecord rec) {
        JsonObject o = new JsonObject();
        o.addProperty("fixtureId", rec.fixture().id());
        o.addProperty("mode", rec.result().mode());
        o.addProperty("startedAt", rec.result().startedAt().toString());
        o.addProperty("durationMs", rec.result().durationMs());
        if (rec.result().error() != null) o.addProperty("error", rec.result().error());

        o.addProperty("anchorId", rec.result().anchorId());

        JsonArray retrieved = new JsonArray();
        for (RetrievedChunk rc : rec.result().retrieved()) {
            JsonObject j = new JsonObject();
            j.addProperty("chunkId", rc.chunkId());
            j.addProperty("score", rc.score());
            j.addProperty("rank", rc.rank());
            retrieved.add(j);
        }
        o.add("retrieved", retrieved);

        o.add("loopPayload", rec.result().loopPayload() == null
                ? JsonNull.INSTANCE : rec.result().loopPayload());

        JsonArray metrics = new JsonArray();
        for (Metric m : rec.metrics()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", m.name());
            j.addProperty("value", m.value());
            j.addProperty("status", m.status());
            if (m.note() != null) j.addProperty("note", m.note());
            metrics.add(j);
        }
        o.add("metrics", metrics);
        return o;
    }
}
