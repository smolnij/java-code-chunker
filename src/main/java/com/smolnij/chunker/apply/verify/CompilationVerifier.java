package com.smolnij.chunker.apply.verify;

/**
 * Verifies that a {@link CompilationRequest} compiles cleanly. Implementations
 * differ by backend (in-process {@code javac} vs shelled {@code mvn}) and by
 * dispatch policy ({@code LayeredCompilationVerifier}).
 *
 * <p>Implementations must be safe to call concurrently <em>across</em>
 * requests; a single {@link #verify} call is allowed to be stateful internally
 * (e.g. classpath cache).
 */
public interface CompilationVerifier {

    /** Compile {@code request} and return the diagnostics. Never throws on compile errors — only on infrastructure failure. */
    CompilationResult verify(CompilationRequest request);

    /** {@code false} when this backend can't run on the current JVM (e.g. javac missing on a JRE). */
    boolean isAvailable();

    /** Stable identifier for telemetry / log output (e.g. {@code "javac"}, {@code "maven"}). */
    String backendName();
}
