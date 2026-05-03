package com.smolnij.chunker.util;

/**
 * Formatting helpers for printing failure reasons from main classes.
 *
 * <p>{@link Throwable#getMessage()} returns {@code null} for many exceptions
 * (notably {@link NullPointerException} without a synthesised message), and
 * a single message line drops any wrapped cause. The helpers here always
 * produce a non-empty, human-readable string with the exception type and
 * the full cause chain so the user sees a real reason in the logs.
 */
public final class Errors {

    private Errors() {}

    /**
     * Formats {@code t} as {@code "<ClassName>: <message>"}, walking the
     * cause chain on subsequent lines (each prefixed with {@code "caused by: "}).
     * Never returns null; substitutes {@code "(no message)"} for absent text.
     */
    public static String format(Throwable t) {
        if (t == null) return "(null throwable)";
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(messageOrPlaceholder(t));
        Throwable cause = t.getCause();
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        seen.add(t);
        while (cause != null && seen.add(cause)) {
            sb.append("\n  caused by: ").append(cause.getClass().getName())
              .append(": ").append(messageOrPlaceholder(cause));
            cause = cause.getCause();
        }
        return sb.toString();
    }

    private static String messageOrPlaceholder(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? "(no message)" : m;
    }
}
