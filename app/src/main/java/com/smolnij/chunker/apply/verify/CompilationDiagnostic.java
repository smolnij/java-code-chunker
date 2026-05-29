package com.smolnij.chunker.apply.verify;

import java.nio.file.Path;

/**
 * One compiler diagnostic, normalised across backends (in-process javac and
 * shelled mvn). {@link #file} is absolute when known; {@code null} for global
 * errors that don't refer to a single source. {@link #line} / {@link #column}
 * are 1-based; {@code -1} when the backend didn't supply a coordinate.
 *
 * <p>{@link #code} is the backend's diagnostic key (e.g.
 * {@code compiler.err.cant.resolve.location} for javac); may be {@code null}
 * when the source was Maven and only a free-form message was available.
 */
public record CompilationDiagnostic(
        Path file,
        long line,
        long column,
        Severity severity,
        String code,
        String message
) {
    public enum Severity { ERROR, WARNING, NOTE }

    public boolean isError() { return severity == Severity.ERROR; }
}
