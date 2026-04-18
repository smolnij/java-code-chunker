package com.smolnij.chunker.eval.fixture;

import java.util.List;

/**
 * A single golden-task evaluation fixture. Loaded from JSON.
 *
 * <p>See {@code eval-fixtures/README.md} for field semantics and the
 * schema contract enforced by {@link FixtureLoader}.
 */
public record Fixture(
        int schemaVersion,
        String id,
        String description,
        String mode,
        String query,
        Integer topK,
        GoldSet gold,
        ExpectedOutcome expected,
        List<String> tags,
        Integer timeoutSeconds
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public Fixture {
        if (tags == null) tags = List.of();
        if (expected == null) expected = new ExpectedOutcome(null, null, List.of());
        if (gold == null) gold = new GoldSet(null, List.of());
    }
}
