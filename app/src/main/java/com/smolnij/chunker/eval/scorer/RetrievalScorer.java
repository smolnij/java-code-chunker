package com.smolnij.chunker.eval.scorer;

import com.smolnij.chunker.callgraph.MethodId;
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
 * K is the actual retrieved list size (bounded by fixture.topK if set).
 *
 * <p>chunkIds are normalized before set comparison to absorb three known sources
 * of lexical drift between the chunker and hand-written fixture gold lists:
 * <ul>
 *   <li>{@code #partN} suffixes appended by {@link com.smolnij.chunker.tokenizer.TokenCounter}
 *       when a method exceeds the per-chunk token budget.</li>
 *   <li>Parameter <em>format</em> drift — qualified/generic-bearing param types
 *       in gold lists ({@code expandForAnalyzer(List<String>, StagedPlanIndex)})
 *       vs. the erased simple-name form the current chunker emits
 *       ({@code expandForAnalyzer(List, StagedPlanIndex)}). Both sides are run
 *       through {@link MethodId#canonicalize} so strict matching stays meaningful.</li>
 *   <li>Parameter <em>type</em> drift (e.g. JavaParser surfaces a parameter as
 *       {@code Node} where the gold list says {@code MethodDeclaration}) — a genuine
 *       disagreement canonicalization cannot fix.</li>
 * </ul>
 * The {@code @K} metrics use strict (parameter-aware) matching after part-suffix
 * stripping and param canonicalization; a parallel pair of {@code _loose} metrics
 * drops the parameter list entirely so that residual type drift does not silently
 * flip every metric to FAIL.
 */
public final class RetrievalScorer implements Scorer {

    @Override
    public String name() { return "retrieval"; }

    @Override
    public List<Metric> score(Fixture fixture, RunResult result,
                              VerifierResult compile, VerifierResult tests) {
        if (result.isError()) {
            return List.of(
                Metric.error("retrieval.precision@K", result.error()),
                Metric.error("retrieval.recall@K", result.error()),
                Metric.error("retrieval.anchor.hit", result.error()),
                Metric.error("retrieval.mrr", result.error())
            );
        }

        List<RetrievedChunk> retrieved = result.retrieved();
        List<String> gold = fixture.gold().relevant();
        int k = retrieved.size();
        String suffix = "@" + k;

        if (gold.isEmpty()) {
            return List.of(
                Metric.notRun("retrieval.precision" + suffix, "gold.relevant empty"),
                Metric.notRun("retrieval.recall" + suffix, "gold.relevant empty"),
                anchorMetric(fixture, result),
                Metric.notRun("retrieval.mrr", "gold.relevant empty")
            );
        }

        Set<String> goldStrict = new HashSet<>();
        Set<String> goldLoose = new HashSet<>();
        for (String g : gold) {
            String n = normalizeId(g);
            goldStrict.add(n);
            goldLoose.add(stripParamList(n));
        }

        Set<String> retrievedStrict = new HashSet<>();
        Set<String> retrievedLoose = new HashSet<>();
        List<String> retrievedStrictOrdered = new ArrayList<>();
        for (RetrievedChunk rc : retrieved) {
            String n = normalizeId(rc.chunkId());
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

        List<Metric> metrics = new ArrayList<>();
        metrics.add(numeric("retrieval.precision" + suffix, precisionStrict, hitsStrict + "/" + k));
        metrics.add(numeric("retrieval.recall" + suffix, recallStrict, hitsStrict + "/" + goldStrict.size()));
        metrics.add(anchorMetric(fixture, result));
        metrics.add(numeric("retrieval.mrr", mrr, mrr == 0.0 ? "no gold in top-K" : null));

        // Loose variants surface param-type drift between fixture gold and chunker output.
        if (precisionLoose != precisionStrict || recallLoose != recallStrict) {
            metrics.add(numeric("retrieval.precision_loose" + suffix, precisionLoose,
                hitsLoose + "/" + k + " (param-list ignored)"));
            metrics.add(numeric("retrieval.recall_loose" + suffix, recallLoose,
                hitsLoose + "/" + goldLoose.size() + " (param-list ignored)"));
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
            return Metric.notRun("retrieval.anchor.hit", "no gold.anchor");
        }
        String expectedNorm = normalizeId(expected);
        String actualNorm = normalizeId(result.anchorId());
        boolean strict = expectedNorm.equals(actualNorm);
        boolean loose = !strict
                && actualNorm != null
                && stripParamList(expectedNorm).equals(stripParamList(actualNorm));
        if (strict) {
            return Metric.pass("retrieval.anchor.hit", 1.0, "matched " + expected);
        }
        if (loose) {
            return Metric.pass("retrieval.anchor.hit", 1.0,
                    "matched (param-list ignored) " + expected + " ~ " + result.anchorId());
        }
        return Metric.fail("retrieval.anchor.hit", 0.0,
                "expected " + expected + ", got " + result.anchorId());
    }

    /**
     * Normalize a chunk id for strict comparison: drop the {@code #partN} split
     * suffix, then re-render the parameter list through {@link MethodId#canonicalize}
     * so that qualified/generic param forms in fixture gold lists
     * ({@code expandForAnalyzer(List<String>, StagedPlanIndex)}) match the erased
     * simple-name form the current chunker emits
     * ({@code expandForAnalyzer(List, StagedPlanIndex)}).
     */
    public static String normalizeId(String chunkId) {
        return MethodId.canonicalize(stripPartSuffix(chunkId));
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
