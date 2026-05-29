package com.smolnij.chunker.refactor.diff;

import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import java.util.*;

/**
 * Scores a {@link MethodDiff} by querying the Neo4j call graph for impact analysis.
 *
 * <p>Produces a {@link ScoredDiff} with a safety score in [0.0, 1.0] and a list
 * of risk reasons. The score is computed from blast radius, call graph delta,
 * visibility narrowing, and parse errors.
 */
public class DiffScorer {

    private final Neo4jGraphReader graphReader;

    private double signatureBreakWeight = 0.4;
    private double callAddedWeight      = 0.05;
    private double callRemovedWeight    = 0.15;
    private double visibilityNarrowWeight = 0.3;
    private double throwsChangedWeight  = 0.1;
    private double annotationWeight     = 0.05;

    public DiffScorer(Neo4jGraphReader graphReader) {
        this.graphReader = graphReader;
    }

    /**
     * Score a MethodDiff by querying the graph for blast radius and computing
     * a weighted risk score.
     */
    public ScoredDiff score(MethodDiff diff) {
        double risk = 0.0;
        List<String> reasons = new ArrayList<>();

        if (diff.isParseError()) {
            return new ScoredDiff(diff, 0.0,
                    List.of("Proposed code does not parse: " + diff.getParseErrorDetail()),
                    0, Set.of());
        }

        Set<String> affectedCallers = new LinkedHashSet<>();
        int callerCount = 0;

        if (diff.isSignatureChanged()) {
            callerCount = graphReader.getCallerCount(diff.getChunkId());
            Map<String, Integer> subgraph = graphReader.expandSubgraph(diff.getChunkId(), 1);
            for (Map.Entry<String, Integer> entry : subgraph.entrySet()) {
                if (!entry.getKey().equals(diff.getChunkId()) && entry.getValue() == 1) {
                    affectedCallers.add(entry.getKey());
                }
            }
            risk += Math.min(callerCount * signatureBreakWeight, 0.8);

            if (diff.isReturnTypeChanged()) {
                reasons.add(String.format("Return type changed: %s → %s (affects %d callers)",
                        diff.getOldReturnType(), diff.getNewReturnType(), callerCount));
            }
            if (diff.isParamsChanged()) {
                reasons.add(String.format("Parameters changed: (%s) → (%s) (affects %d callers)",
                        diff.getOldParams(), diff.getNewParams(), callerCount));
            }
            if (diff.isVisibilityChanged()) {
                reasons.add(String.format("Visibility changed: %s → %s",
                        diff.getOldVisibility(), diff.getNewVisibility()));
                if (isNarrowing(diff.getOldVisibility(), diff.getNewVisibility())) {
                    risk += visibilityNarrowWeight;
                    reasons.add("⚠ Visibility NARROWED — may break external callers");
                }
            }
            if (diff.isThrowsChanged()) {
                reasons.add(String.format("Throws clause changed: %s → %s",
                        diff.getOldThrows(), diff.getNewThrows()));
                risk += throwsChangedWeight;
            }
        }

        if (!diff.getRemovedCalls().isEmpty()) {
            risk += diff.getRemovedCalls().size() * callRemovedWeight;
            reasons.add("Removed " + diff.getRemovedCalls().size() + " method calls: " + diff.getRemovedCalls());
        }
        if (!diff.getAddedCalls().isEmpty()) {
            risk += diff.getAddedCalls().size() * callAddedWeight;
            reasons.add("Added " + diff.getAddedCalls().size() + " new method calls: " + diff.getAddedCalls());
        }

        int annotationChanges = diff.getAddedAnnotations().size() + diff.getRemovedAnnotations().size();
        if (annotationChanges > 0) {
            risk += annotationChanges * annotationWeight;
            if (!diff.getRemovedAnnotations().isEmpty()) reasons.add("Removed annotations: " + diff.getRemovedAnnotations());
            if (!diff.getAddedAnnotations().isEmpty()) reasons.add("Added annotations: " + diff.getAddedAnnotations());
        }

        if (diff.isBodyOnlyChange() && !diff.isCallGraphChanged()) {
            reasons.add("✓ Body-only change — no external call-site impact");
        }

        risk = Math.min(1.0, Math.max(0.0, risk));
        return new ScoredDiff(diff, 1.0 - risk, reasons, callerCount, affectedCallers);
    }

    private boolean isNarrowing(String oldVis, String newVis) {
        return visibilityLevel(newVis) < visibilityLevel(oldVis);
    }

    private int visibilityLevel(String visibility) {
        return switch (visibility) {
            case "public" -> 3;
            case "protected" -> 2;
            case "package-private" -> 1;
            case "private" -> 0;
            default -> 1;
        };
    }

    // ── Weight configuration ──

    public DiffScorer withSignatureBreakWeight(double v) { this.signatureBreakWeight = v; return this; }
    public DiffScorer withCallAddedWeight(double v) { this.callAddedWeight = v; return this; }
    public DiffScorer withCallRemovedWeight(double v) { this.callRemovedWeight = v; return this; }
    public DiffScorer withVisibilityNarrowWeight(double v) { this.visibilityNarrowWeight = v; return this; }
    public DiffScorer withThrowsChangedWeight(double v) { this.throwsChangedWeight = v; return this; }
    public DiffScorer withAnnotationWeight(double v) { this.annotationWeight = v; return this; }
}

