package com.smolnij.chunker.eval.scorer;

/**
 * A single scoring observation.
 *
 * @param name    dotted metric name (e.g. {@code retrieval.precision@8}).
 * @param value   numeric form — {@code 1.0}/{@code 0.0} for boolean metrics,
 *                precision/recall as doubles in [0, 1], {@code 0.0} for NOT_RUN.
 * @param status  one of {@link #PASS}, {@link #FAIL}, {@link #NOT_RUN}, {@link #ERROR}.
 * @param note    short free-text detail (e.g. {@code "5/8"}), possibly null.
 */
public record Metric(String name, double value, String status, String note) {

    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String NOT_RUN = "NOT_RUN";
    public static final String ERROR = "ERROR";

    public static Metric pass(String name, double value, String note) {
        return new Metric(name, value, PASS, note);
    }

    public static Metric fail(String name, double value, String note) {
        return new Metric(name, value, FAIL, note);
    }

    public static Metric notRun(String name, String note) {
        return new Metric(name, 0.0, NOT_RUN, note);
    }

    public static Metric error(String name, String note) {
        return new Metric(name, 0.0, ERROR, note);
    }
}
