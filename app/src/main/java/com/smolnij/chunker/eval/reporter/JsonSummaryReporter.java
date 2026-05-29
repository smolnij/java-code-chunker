package com.smolnij.chunker.eval.reporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.scorer.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Machine-readable {@code summary.json} — per-metric counts and rates
 * plus a per-mode fixture tally. Designed to be consumed by a future
 * regression dashboard or a subsequent Claude session.
 */
public final class JsonSummaryReporter implements Reporter {

    public static final String FILENAME = "summary.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void write(Path outDir, List<EvalRecord> records) throws IOException {
        Files.createDirectories(outDir);

        Map<String, int[]> byMode = new TreeMap<>();  // [fixtures, errors]
        Map<String, SummaryReporter.Agg> byMetric = new LinkedHashMap<>();

        for (EvalRecord rec : records) {
            int[] row = byMode.computeIfAbsent(rec.result().mode(), k -> new int[2]);
            row[0]++;
            if (rec.result().isError()) row[1]++;
            for (Metric m : rec.metrics()) {
                byMetric.computeIfAbsent(m.name(), k -> new SummaryReporter.Agg()).add(m);
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("fixtureCount", records.size());

        JsonObject modes = new JsonObject();
        for (var e : byMode.entrySet()) {
            JsonObject m = new JsonObject();
            m.addProperty("fixtures", e.getValue()[0]);
            m.addProperty("errors", e.getValue()[1]);
            modes.add(e.getKey(), m);
        }
        root.add("byMode", modes);

        JsonObject metrics = new JsonObject();
        for (var e : byMetric.entrySet()) {
            SummaryReporter.Agg a = e.getValue();
            JsonObject m = new JsonObject();
            m.addProperty("pass", a.pass);
            m.addProperty("fail", a.fail);
            m.addProperty("error", a.error);
            m.addProperty("notRun", a.notRun);
            double rate = a.passRate();
            m.addProperty("passRate", Double.isNaN(rate) ? null : rate);
            double mean = a.mean();
            m.addProperty("meanValue", Double.isNaN(mean) ? null : mean);
            metrics.add(e.getKey(), m);
        }
        root.add("byMetric", metrics);

        Files.writeString(outDir.resolve(FILENAME), GSON.toJson(root));
    }
}
