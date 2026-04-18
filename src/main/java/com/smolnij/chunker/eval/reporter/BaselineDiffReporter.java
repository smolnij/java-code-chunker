package com.smolnij.chunker.eval.reporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smolnij.chunker.eval.scorer.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the current run to a prior {@code runs.jsonl} baseline.
 *
 * <p>Writes {@code diff.txt} (human) and {@code diff.json} (machine).
 * A fixture is a regression when a metric flipped PASS → FAIL/ERROR
 * or when a numeric metric dropped more than {@code diffEpsilon}.
 * {@code NOT_RUN → NOT_RUN} is ignored; {@code NOT_RUN → PASS/FAIL}
 * is reported as "verifier newly wired" and is not a regression.
 * Anchor changes are flagged as a diagnostic line regardless of
 * metric outcome.
 */
public final class BaselineDiffReporter implements Reporter {

    public static final String TEXT_FILENAME = "diff.txt";
    public static final String JSON_FILENAME = "diff.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path baselinePath;
    private final double diffEpsilon;

    public BaselineDiffReporter(Path baselinePath, double diffEpsilon) {
        this.baselinePath = baselinePath;
        this.diffEpsilon = diffEpsilon;
    }

    @Override
    public void write(Path outDir, List<EvalRecord> records) throws IOException {
        Files.createDirectories(outDir);

        Map<String, BaselineRecord> baseline = loadBaseline(baselinePath);
        List<String> regressions = new ArrayList<>();
        List<String> wins = new ArrayList<>();
        List<String> verifiersWired = new ArrayList<>();
        List<String> anchorChanges = new ArrayList<>();
        int unchanged = 0;

        for (EvalRecord rec : records) {
            String fid = rec.fixture().id();
            BaselineRecord prior = baseline.get(fid);
            if (prior == null) continue;

            // Anchor change
            if (!eq(prior.anchorId, rec.result().anchorId())) {
                anchorChanges.add(String.format("  %s: anchor %s → %s",
                        fid, prior.anchorId, rec.result().anchorId()));
            }

            boolean anyChange = false;
            for (Metric cur : rec.metrics()) {
                Metric base = prior.metrics.get(cur.name());
                if (base == null) continue;
                DiffKind k = classify(base, cur);
                if (k == DiffKind.UNCHANGED) continue;
                anyChange = true;
                String line = String.format("  %s [%s]: %s=%s (%.4f) → %s (%.4f)  %s",
                        fid, cur.name(), cur.name(),
                        base.status(), base.value(), cur.status(), cur.value(),
                        note(cur, base));
                switch (k) {
                    case REGRESSION -> regressions.add(line);
                    case WIN -> wins.add(line);
                    case VERIFIER_WIRED -> verifiersWired.add(line);
                    default -> {}
                }
            }
            if (!anyChange) unchanged++;
        }

        writeText(outDir, regressions, wins, verifiersWired, anchorChanges, unchanged,
                records.size(), baseline.size());
        writeJson(outDir, regressions, wins, verifiersWired, anchorChanges, unchanged,
                records.size(), baseline.size());
    }

    private DiffKind classify(Metric base, Metric cur) {
        boolean baseRun = !Metric.NOT_RUN.equals(base.status());
        boolean curRun = !Metric.NOT_RUN.equals(cur.status());

        if (!baseRun && !curRun) return DiffKind.UNCHANGED;
        if (!baseRun /* && curRun */) return DiffKind.VERIFIER_WIRED;
        if (/* baseRun && */ !curRun) return DiffKind.REGRESSION;  // lost a signal

        boolean basePass = Metric.PASS.equals(base.status());
        boolean curPass = Metric.PASS.equals(cur.status());
        double drop = base.value() - cur.value();

        if (basePass && !curPass) return DiffKind.REGRESSION;
        if (!basePass && curPass) return DiffKind.WIN;
        if (drop > diffEpsilon) return DiffKind.REGRESSION;
        if (-drop > diffEpsilon) return DiffKind.WIN;
        return DiffKind.UNCHANGED;
    }

    private static String note(Metric cur, Metric base) {
        StringBuilder sb = new StringBuilder();
        if (cur.note() != null) sb.append("cur: ").append(cur.note());
        if (base.note() != null) {
            if (sb.length() > 0) sb.append("  |  ");
            sb.append("prev: ").append(base.note());
        }
        return sb.toString();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void writeText(Path outDir, List<String> regressions, List<String> wins,
                            List<String> verifiersWired, List<String> anchorChanges,
                            int unchanged, int current, int baseline) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("═".repeat(72)).append('\n');
        sb.append("  BASELINE DIFF — ").append(regressions.size()).append(" regressions, ")
                .append(wins.size()).append(" wins\n");
        sb.append("═".repeat(72)).append('\n');
        sb.append("Baseline fixtures: ").append(baseline).append('\n');
        sb.append("Current fixtures:  ").append(current).append('\n');
        sb.append("Unchanged:         ").append(unchanged).append('\n');
        if (!regressions.isEmpty()) section(sb, "Regressions", regressions);
        if (!wins.isEmpty()) section(sb, "Wins", wins);
        if (!verifiersWired.isEmpty()) section(sb, "Verifier newly wired", verifiersWired);
        if (!anchorChanges.isEmpty()) section(sb, "Anchor changes", anchorChanges);
        Files.writeString(outDir.resolve(TEXT_FILENAME), sb.toString());
    }

    private static void section(StringBuilder sb, String title, List<String> lines) {
        sb.append('\n').append("── ").append(title).append(" ──\n");
        for (String line : lines) sb.append(line).append('\n');
    }

    private void writeJson(Path outDir, List<String> regressions, List<String> wins,
                            List<String> verifiersWired, List<String> anchorChanges,
                            int unchanged, int current, int baseline) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("baselineFixtures", baseline);
        root.addProperty("currentFixtures", current);
        root.addProperty("unchanged", unchanged);
        root.add("regressions", toArray(regressions));
        root.add("wins", toArray(wins));
        root.add("verifierWired", toArray(verifiersWired));
        root.add("anchorChanges", toArray(anchorChanges));
        Files.writeString(outDir.resolve(JSON_FILENAME), GSON.toJson(root));
    }

    private static JsonArray toArray(List<String> lines) {
        JsonArray arr = new JsonArray();
        for (String line : lines) arr.add(line.trim());
        return arr;
    }

    private static Map<String, BaselineRecord> loadBaseline(Path path) throws IOException {
        Map<String, BaselineRecord> out = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) return out;
        Gson g = new Gson();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            JsonObject o = g.fromJson(line, JsonObject.class);
            BaselineRecord br = new BaselineRecord();
            br.anchorId = o.has("anchorId") && !o.get("anchorId").isJsonNull()
                    ? o.get("anchorId").getAsString() : null;
            if (o.has("metrics")) {
                for (var el : o.getAsJsonArray("metrics")) {
                    JsonObject m = el.getAsJsonObject();
                    String name = m.get("name").getAsString();
                    double value = m.get("value").getAsDouble();
                    String status = m.get("status").getAsString();
                    String note = m.has("note") && !m.get("note").isJsonNull()
                            ? m.get("note").getAsString() : null;
                    br.metrics.put(name, new Metric(name, value, status, note));
                }
            }
            if (o.has("fixtureId")) out.put(o.get("fixtureId").getAsString(), br);
        }
        return out;
    }

    private static final class BaselineRecord {
        String anchorId;
        Map<String, Metric> metrics = new HashMap<>();
    }

    private enum DiffKind { UNCHANGED, REGRESSION, WIN, VERIFIER_WIRED }
}
