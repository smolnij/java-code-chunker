package com.smolnij.chunker.eval.fixture;

import java.util.List;

/**
 * Labelled gold data for a fixture: the expected entry-point anchor
 * (if known) and the chunkIds that should appear in the retrieval
 * top-K for the fixture's query.
 */
public record GoldSet(String anchor, List<String> relevant) {
    public GoldSet {
        if (relevant == null) relevant = List.of();
    }
}
