package com.smolnij.chunker.eval.runner;

import com.smolnij.chunker.eval.fixture.Fixture;
import com.smolnij.chunker.eval.result.RunResult;

/**
 * Runs a fixture through one of the project's existing retrieval /
 * refactoring pipelines and produces a {@link RunResult} for scoring.
 */
public interface ModeRunner {
    String modeName();
    RunResult run(Fixture fixture, RunContext ctx) throws Exception;
}
