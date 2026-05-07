package com.smolnij.chunker.eval.scorer;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval-quality metrics: precision@K, recall@K, anchor-hit, MRR.
 *
 * <p>Metric names use a literal {@code @K} suffix (not the actual retrieved
 * count) so per-fixture rows aggregate cleanly in {@code SummaryReporter} and
 * diff cleanly in {@code BaselineDiffReporter} across fixtures with different
 * {@code topK} budgets. The actual retrieved size and gold size are recorded
 * in the metric {@code note} (e.g. {@code "2/8 (k=8, gold=4)"}).
 *
 * <p>chunkIds are normalized before set comparison to absorb two known sources
 * of lexical drift between the chunker and hand-written fixture gold lists:
 * <ul>
 *   <li>{@code #partN} suffixes appended by {@link com.smolnij.chunker.tokenizer.TokenCounter}
 *       when a method exceeds the per-chunk token budget.</li>
 *   <li>Parameter-list drift (e.g. JavaParser surfaces a parameter as
 *       {@code Node} where the gold list says {@code MethodDeclaration}).</li>
 * </ul>
 * The {@code @K} metrics use strict (parameter-aware) matching after part-suffix
 * stripping; a parallel pair of {@code _loose} metrics drops the parameter list
 * so that param-type drift does not silently flip every metric to FAIL.
 */
public final class RetrievalScorer implements Scorer {

    @Override
    public String name() { return "retrieval"; }

    private static final String M_PRECISION = "retrieval.precision@K";
    private static final String M_RECALL = "retrieval.recall@K";
    private static final String M_PRECISION_LOOSE = "retrieval.precision_loose@K";
    private static final String M_RECALL_LOOSE = "retrieval.recall_loose@K";
    private static final String M_MRR = "retrieval.mrr";
    private static final String M_ANCHOR = "retrieval.anchor.hit";

    @Override
    public List<Metric> score(Fixture fixture, RunResult result,
                              VerifierResult compile, VerifierResult tests) {
        if (result.isError()) {
            return List.of(
                Metric.error(M_PRECISION, result.error()),
                Metric.error(M_RECALL, result.error()),
                Metric.error(M_ANCHOR, result.error()),
                Metric.error(M_MRR, result.error())
            );
        }

        List<RetrievedChunk> retrieved = result.retrieved();
        List<String> gold = fixture.gold().relevant();
        int k = retrieved.size();

        if (gold.isEmpty()) {
            return List.of(
                Metric.notRun(M_PRECISION, "gold.relevant empty"),
                Metric.notRun(M_RECALL, "gold.relevant empty"),
                anchorMetric(fixture, result),
                Metric.notRun(M_MRR, "gold.relevant empty")
            );
        }

        Set<String> goldStrict = new HashSet<>();
        Set<String> goldLoose = new HashSet<>();
        for (String g : gold) {
            String n = stripPartSuffix(g);
            goldStrict.add(n);
            goldLoose.add(stripParamList(n));
        }

        Set<String> retrievedStrict = new HashSet<>();
        Set<String> retrievedLoose = new HashSet<>();
        List<String> retrievedStrictOrdered = new ArrayList<>();
        for (RetrievedChunk rc : retrieved) {
            String n = stripPartSuffix(rc.chunkId());
            retrievedStrict.add(n);
            retrievedLoose.add(stripParamList(n));
            retrievedStrictOrdered.add(n);
        }

        long hitsStrict = retrievedStrict.stream().filter(goldStrict::contains).count();
        long hitsLoose = retrievedLoose.stream().filter(goldLoose::contains).count();
        double precisionStrict = k == 0 ? 0.0 : (double) hitsStrict / k;
        double recallStrict = (double) hitsStrict / goldStrict.size();
        double precisionLoose = k == 0 ? 0.0 : (double) hitsLoose / k;
        double recallLoose = (double) hitsLoose / goldLoose.size();

        double mrr = 0.0;
        for (int i = 0; i < retrievedStrictOrdered.size(); i++) {
            if (goldStrict.contains(retrievedStrictOrdered.get(i))) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        if (mrr == 0.0) {
            // Fall back to loose match for MRR so param-drift doesn't zero it out.
            for (int i = 0; i < retrievedStrictOrdered.size(); i++) {
                if (goldLoose.contains(stripParamList(retrievedStrictOrdered.get(i)))) {
                    mrr = 1.0 / (i + 1);
                    break;
                }
            }
        }

        Integer fixtureK = fixture.topK();
        String budget = fixtureK == null ? "k=" + k : "k=" + k + ",budget=" + fixtureK;

        List<Metric> metrics = new ArrayList<>();
        metrics.add(numeric(M_PRECISION, precisionStrict,
                hitsStrict + "/" + k + " (" + budget + ", gold=" + goldStrict.size() + ")"));
        metrics.add(numeric(M_RECALL, recallStrict,
                hitsStrict + "/" + goldStrict.size() + " (" + budget + ")"));
        metrics.add(anchorMetric(fixture, result));
        metrics.add(numeric(M_MRR, mrr, mrr == 0.0 ? "no gold in top-K (" + budget + ")" : budget));

        // Loose variants surface param-type drift between fixture gold and chunker output.
        if (precisionLoose != precisionStrict || recallLoose != recallStrict) {
            metrics.add(numeric(M_PRECISION_LOOSE, precisionLoose,
                hitsLoose + "/" + k + " (param-list ignored, " + budget + ")"));
            metrics.add(numeric(M_RECALL_LOOSE, recallLoose,
                hitsLoose + "/" + goldLoose.size() + " (param-list ignored, " + budget + ")"));
        }
        return metrics;
    }

    private static Metric numeric(String name, double value, String note) {
        return value > 0.0
                ? Metric.pass(name, value, note)
                : Metric.fail(name, value, note);
    }

    private static Metric anchorMetric(Fixture fixture, RunResult result) {
        String expected = fixture.gold().anchor();
        if (expected == null || expected.isBlank()) {
            return Metric.notRun(M_ANCHOR, "no gold.anchor");
        }
        String expectedNorm = stripPartSuffix(expected);
        String actualNorm = stripPartSuffix(result.anchorId());
        boolean strict = expectedNorm.equals(actualNorm);
        boolean loose = !strict
                && actualNorm != null
                && stripParamList(expectedNorm).equals(stripParamList(actualNorm));
        if (strict) {
            return Metric.pass(M_ANCHOR, 1.0, "matched " + expected);
        }
        if (loose) {
            return Metric.pass(M_ANCHOR, 1.0,
                    "matched (param-list ignored) " + expected + " ~ " + result.anchorId());
        }
        return Metric.fail(M_ANCHOR, 0.0,
                "expected " + expected + ", got " + result.anchorId());
    }

    /** Drop a trailing {@code #partN} segment added by the token-aware splitter. */
    public static String stripPartSuffix(String chunkId) {
        if (chunkId == null) return null;
        int hash = chunkId.lastIndexOf('#');
        if (hash < 0) return chunkId;
        String tail = chunkId.substring(hash + 1);
        if (!tail.startsWith("part")) return chunkId;
        for (int i = 4; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) return chunkId;
        }
        return tail.length() > 4 ? chunkId.substring(0, hash) : chunkId;
    }

    /** Drop the parameter list so {@code Foo#bar(int, String)} becomes {@code Foo#bar}. */
    public static String stripParamList(String chunkId) {
        if (chunkId == null) return null;
        int paren = chunkId.indexOf('(');
        return paren < 0 ? chunkId : chunkId.substring(0, paren);
    }
}
