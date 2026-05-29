package com.smolnij.chunker.apply.verify;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of a {@link CompilationVerifier#verify} call.
 *
 * <p>{@link #success} is {@code true} when no {@code ERROR}-level diagnostics
 * were produced. Warnings/notes do not flip the flag.
 *
 * <p>{@link #backend} identifies which backend produced the result
 * ({@code "javac"}, {@code "maven"}, or {@code "unavailable"}). Callers may
 * surface this so the LLM understands speed/accuracy trade-offs.
 *
 * <p>{@link #note} is a free-form line for backend-specific context (e.g.
 * "javac not available; falling back to mvn"). May be {@code null}.
 */
public record CompilationResult(
        boolean success,
        List<CompilationDiagnostic> diagnostics,
        String backend,
        Duration duration,
        String note
) {
    public long errorCount() {
        return diagnostics.stream().filter(CompilationDiagnostic::isError).count();
    }

    public static CompilationResult ok(String backend, Duration duration) {
        return new CompilationResult(true, List.of(), backend, duration, null);
    }

    public static CompilationResult unavailable(String reason) {
        return new CompilationResult(true, List.of(), "unavailable", Duration.ZERO, reason);
    }
}
