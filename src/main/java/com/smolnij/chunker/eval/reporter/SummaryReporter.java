package com.smolnij.chunker.eval.reporter;

import com.smolnij.chunker.eval.scorer.Metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Human-readable {@code summary.txt} — per-metric rollups and a
 * per-mode fixture count. {@code NOT_RUN} entries are excluded from
 * pass-rate denominators so stubbed verifiers don't drag numbers down.
 */
public final class SummaryReporter implements Reporter {

    public static final String FILENAME = "summary.txt";

    @Override
    public void write(Path outDir, List<EvalRecord> records) throws IOException {
        Files.createDirectories(outDir);

        Map<String, int[]> byMode = new TreeMap<>();  // [fixtures, errors]
        Map<String, Agg> byMetric = new LinkedHashMap<>();

        for (EvalRecord rec : records) {
            int[] row = byMode.computeIfAbsent(rec.result().mode(), k -> new int[2]);
            row[0]++;
            if (rec.result().isError()) row[1]++;

            for (Metric m : rec.metrics()) {
                byMetric.computeIfAbsent(m.name(), k -> new Agg()).add(m);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═".repeat(72)).append('\n');
        sb.append("  EVALUATION SUMMARY — ").append(records.size()).append(" fixture(s)\n");
        sb.append("═".repeat(72)).append("\n\n");

        sb.append("── By mode ─────────────────────────────────────────────\n");
        for (var e : byMode.entrySet()) {
            sb.append(String.format("  %-12s  fixtures=%d  errors=%d%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        sb.append('\n');

        sb.append("── By metric (NOT_RUN excluded from rates) ─────────────\n");
        sb.append(String.format("  %-32s  %6s  %6s  %6s  %6s  %8s  %8s%n",
                "metric", "pass", "fail", "error", "n/run", "pass-rt", "mean"));
        for (var e : byMetric.entrySet()) {
            Agg a = e.getValue();
            sb.append(String.format("  %-32s  %6d  %6d  %6d  %6d  %8s  %8s%n",
                    trim(e.getKey(), 32),
                    a.pass, a.fail, a.error, a.notRun,
                    a.passRateString(), a.meanString()));
        }
        sb.append('\n');

        Files.writeString(outDir.resolve(FILENAME), sb.toString());
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static final class Agg {
        int pass, fail, error, notRun;
        double sumValue;
        int valueCount;

        void add(Metric m) {
            switch (m.status()) {
                case Metric.PASS -> { pass++; sumValue += m.value(); valueCount++; }
                case Metric.FAIL -> { fail++; sumValue += m.value(); valueCount++; }
                case Metric.ERROR -> error++;
                case Metric.NOT_RUN -> notRun++;
            }
        }

        double passRate() {
            int denom = pass + fail + error;
            return denom == 0 ? Double.NaN : (double) pass / denom;
        }

        double mean() {
            return valueCount == 0 ? Double.NaN : sumValue / valueCount;
        }

        String passRateString() {
            double r = passRate();
            return Double.isNaN(r) ? "-" : String.format("%.2f", r);
        }

        String meanString() {
            double m = mean();
            return Double.isNaN(m) ? "-" : String.format("%.4f", m);
        }
    }
}
