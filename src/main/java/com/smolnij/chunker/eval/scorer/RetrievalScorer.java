package com.smolnij.chunker.eval.scorer;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RetrievedChunk;
import com.smolnij.chunker.eval.result.RunResult;
import com.smolnij.chunker.eval.verifier.VerifierResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval-quality metrics: precision@K, recall@K, anchor-hit, MRR.
 * K is the actual retrieved list size (bounded by fixture.topK if set).
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

        Set<String> goldSet = new HashSet<>(gold);
        Set<String> retrievedSet = new HashSet<>();
        for (RetrievedChunk rc : retrieved) retrievedSet.add(rc.chunkId());

        long hits = retrievedSet.stream().filter(goldSet::contains).count();
        double precision = k == 0 ? 0.0 : (double) hits / k;
        double recall = (double) hits / gold.size();

        double mrr = 0.0;
        for (RetrievedChunk rc : retrieved) {
            if (goldSet.contains(rc.chunkId())) {
                mrr = 1.0 / rc.rank();
                break;
            }
        }

        String pNote = hits + "/" + k;
        String rNote = hits + "/" + gold.size();
        return List.of(
            numeric("retrieval.precision" + suffix, precision, pNote),
            numeric("retrieval.recall" + suffix, recall, rNote),
            anchorMetric(fixture, result),
            numeric("retrieval.mrr", mrr, mrr == 0.0 ? "no gold in top-K" : null)
        );
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
        boolean hit = expected.equals(result.anchorId());
        String note = hit ? "matched " + expected
                          : "expected " + expected + ", got " + result.anchorId();
        return hit ? Metric.pass("retrieval.anchor.hit", 1.0, note)
                   : Metric.fail("retrieval.anchor.hit", 0.0, note);
    }
}
