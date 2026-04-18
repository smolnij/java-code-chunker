package com.smolnij.chunker.eval.fixture;

import java.util.List;

/**
 * Expected results for a fixture run. Any field may be null, meaning
 * "not asserted" — the corresponding metric emits NOT_RUN.
 */
public record ExpectedOutcome(String analyzer, String compile, List<String> tests) {
    public ExpectedOutcome {
        if (tests == null) tests = List.of();
    }
}
