package com.smolnij.chunker.ralph;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured result parsed from a judge LLM's evaluation response.
 *
 * <p>The judge is instructed to output:
 * <pre>
 *   VERDICT: PASS      (or VERDICT: FAIL)
 *   SCORE: 0.85        (optional confidence 0.0–1.0)
 *   FEEDBACK:
 *   - issue 1
 *   - issue 2
 * </pre>
 *
 * <p>The parser is intentionally lenient — local LLMs don't always
 * follow the format precisely. Multiple fallback strategies are used:
 * <ol>
 *   <li>Look for explicit {@code VERDICT: PASS/FAIL}</li>
 *   <li>Look for keywords like "approved", "looks good", "lgtm"</li>
 *   <li>Default to FAIL (conservative — avoids false approvals)</li>
 * </ol>
 */
public class JudgeVerdict {

    private static final Pattern VERDICT_PATTERN =
        Pattern.compile("VERDICT:\\s*(PASS|FAIL)", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCORE_PATTERN =
        Pattern.compile("SCORE:\\s*([0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FEEDBACK_PATTERN =
        Pattern.compile("FEEDBACK:\\s*\\n?(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final boolean passed;
    private final double score;
    private final String feedback;
    private final List<String> issues;
    private final String rawResponse;

    private JudgeVerdict(boolean passed, double score, String feedback,
                          List<String> issues, String rawResponse) {
        this.passed = passed;
        this.score = score;
        this.feedback = feedback;
        this.issues = List.copyOf(issues);
        this.rawResponse = rawResponse;
    }

    // ═══════════════════════════════════════════════════════════════
    // Parser
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a judge LLM response into a structured verdict.
     *
     * @param judgeResponse the raw text from the judge LLM
     * @return the parsed verdict
     */
    public static JudgeVerdict parse(String judgeResponse) {
        if (judgeResponse == null || judgeResponse.isBlank()) {
            return new JudgeVerdict(false, 0.0, "Empty judge response", List.of(), "");
        }

        // ── Verdict ──
        boolean passed = false;
        Matcher vm = VERDICT_PATTERN.matcher(judgeResponse);
        if (vm.find()) {
            passed = vm.group(1).equalsIgnoreCase("PASS");
        } else {
            // Fallback: look for approval keywords
            String lower = judgeResponse.toLowerCase();
            if (lower.contains("lgtm") || lower.contains("looks good") ||
                lower.contains("approved") || lower.contains("no issues found") ||
                lower.contains("all correct")) {
                passed = true;
            }
            // else default to FAIL (conservative)
        }

        // ── Score ──
        double score = passed ? 1.0 : 0.0;
        Matcher sm = SCORE_PATTERN.matcher(judgeResponse);
        if (sm.find()) {
            try {
                score = Double.parseDouble(sm.group(1));
                score = Math.max(0.0, Math.min(1.0, score)); // clamp
            } catch (NumberFormatException ignored) { }
        }

        // ── Feedback ──
        String feedback = "";
        Matcher fm = FEEDBACK_PATTERN.matcher(judgeResponse);
        if (fm.find()) {
            feedback = fm.group(1).trim();
        } else {
            // Use the whole response as feedback if no section header found
            feedback = judgeResponse.trim();
        }

        // ── Issues (bullet points from feedback) ──
        List<String> issues = new ArrayList<>();
        for (String line : feedback.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
                trimmed.matches("^\\d+\\.\\s+.*")) {
                String issue = trimmed.replaceFirst("^[-*]\\s+|^\\d+\\.\\s+", "").trim();
                if (!issue.isEmpty()) {
                    issues.add(issue);
                }
            }
        }

        return new JudgeVerdict(passed, score, feedback, issues, judgeResponse);
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public boolean isPassed() { return passed; }
    public double getScore() { return score; }
    public String getFeedback() { return feedback; }
    public List<String> getIssues() { return issues; }
    public String getRawResponse() { return rawResponse; }

    @Override
    public String toString() {
        return String.format(
            "JudgeVerdict { %s, score=%.2f, issues=%d, feedback=%d chars }",
            passed ? "✓ PASS" : "✗ FAIL", score, issues.size(), feedback.length()
        );
    }
}

