package com.example.chunker.safeloop;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured result parsed from the safety-analyzer LLM's evaluation response.
 *
 * <p>The analyzer is instructed to output:
 * <pre>
 *   CONFIDENCE: 0.85
 *   VERDICT: SAFE           (or VERDICT: UNSAFE)
 *   RISKS:
 *   - RISK: broken method signatures | SEVERITY: HIGH | MITIGATION: update callers
 *   - RISK: threading issue  | SEVERITY: MEDIUM | MITIGATION: add synchronization
 *   NEEDS:
 *   - UserRepository#save
 *   - ValidationService#validate
 * </pre>
 *
 * <p>The parser is intentionally lenient — local LLMs don't always
 * follow the format precisely. Multiple fallback strategies are used:
 * <ol>
 *   <li>Look for explicit {@code CONFIDENCE:} and {@code VERDICT:} headers</li>
 *   <li>Look for keywords like "safe", "no risks", "all clear"</li>
 *   <li>Default to UNSAFE (conservative — avoids false safety declarations)</li>
 * </ol>
 *
 * <p>The {@link #isSafe(double)} method computes safety from both the confidence
 * score and the risk severity: a single HIGH-severity risk forces UNSAFE regardless
 * of confidence.
 */
public class SafetyVerdict {

    // ── Patterns ──

    private static final Pattern CONFIDENCE_PATTERN =
        Pattern.compile("CONFIDENCE:\\s*([0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VERDICT_PATTERN =
        Pattern.compile("VERDICT:\\s*(SAFE|UNSAFE)", Pattern.CASE_INSENSITIVE);

    private static final Pattern RISK_LINE_PATTERN =
        Pattern.compile(
            "RISK:\\s*([^|]+?)\\s*\\|\\s*SEVERITY:\\s*(HIGH|MEDIUM|LOW)\\s*(?:\\|\\s*MITIGATION:\\s*(.+))?",
            Pattern.CASE_INSENSITIVE
        );

    private static final Pattern NEEDS_SECTION_PATTERN =
        Pattern.compile("NEEDS:\\s*\\n?(.*?)(?:\\n\\n|\\z)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NEEDS_ITEM_PATTERN =
        Pattern.compile("^\\s*[-*]\\s+(.+)$", Pattern.MULTILINE);

    private final double confidence;
    private final boolean verdictSafe;
    private final List<Risk> risks;
    private final List<String> missingContext;
    private final String feedback;
    private final String rawResponse;

    private SafetyVerdict(double confidence, boolean verdictSafe, List<Risk> risks,
                          List<String> missingContext, String feedback, String rawResponse) {
        this.confidence = confidence;
        this.verdictSafe = verdictSafe;
        this.risks = List.copyOf(risks);
        this.missingContext = List.copyOf(missingContext);
        this.feedback = feedback;
        this.rawResponse = rawResponse;
    }

    // ═══════════════════════════════════════════════════════════════
    // Risk record
    // ═══════════════════════════════════════════════════════════════

    /**
     * A single identified risk from the safety analysis.
     */
    public static class Risk {

        public enum Severity { HIGH, MEDIUM, LOW }

        private final String description;
        private final Severity severity;
        private final String mitigation;

        public Risk(String description, Severity severity, String mitigation) {
            this.description = description;
            this.severity = severity;
            this.mitigation = mitigation != null ? mitigation : "";
        }

        public String getDescription() { return description; }
        public Severity getSeverity() { return severity; }
        public String getMitigation() { return mitigation; }

        @Override
        public String toString() {
            return String.format("[%s] %s%s", severity,
                description,
                mitigation.isEmpty() ? "" : " → " + mitigation);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Parser
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a safety-analyzer LLM response into a structured verdict.
     *
     * @param analyzerResponse the raw text from the analyzer LLM
     * @return the parsed safety verdict
     */
    public static SafetyVerdict parse(String analyzerResponse) {
        if (analyzerResponse == null || analyzerResponse.isBlank()) {
            return new SafetyVerdict(0.0, false, List.of(), List.of(),
                "Empty analyzer response", "");
        }

        // ── Confidence ──
        double confidence = 0.0;
        Matcher cm = CONFIDENCE_PATTERN.matcher(analyzerResponse);
        if (cm.find()) {
            try {
                confidence = Double.parseDouble(cm.group(1));
                confidence = Math.max(0.0, Math.min(1.0, confidence)); // clamp
            } catch (NumberFormatException ignored) { }
        }

        // ── Verdict ──
        boolean verdictSafe = false;
        Matcher vm = VERDICT_PATTERN.matcher(analyzerResponse);
        if (vm.find()) {
            verdictSafe = vm.group(1).equalsIgnoreCase("SAFE");
        } else {
            // Fallback: keyword detection
            String lower = analyzerResponse.toLowerCase();
            if (lower.contains("no risks") || lower.contains("all clear") ||
                lower.contains("change is safe") || lower.contains("refactoring is safe") ||
                lower.contains("no breaking changes detected")) {
                verdictSafe = true;
            }
            // else default to UNSAFE (conservative)
        }

        // If confidence is high but no explicit verdict, infer from confidence
        if (confidence >= 0.9 && !vm.find()) {
            verdictSafe = true;
        }

        // ── Risks ──
        List<Risk> risks = new ArrayList<>();
        Matcher rm = RISK_LINE_PATTERN.matcher(analyzerResponse);
        while (rm.find()) {
            String desc = rm.group(1).trim();
            Risk.Severity sev;
            try {
                sev = Risk.Severity.valueOf(rm.group(2).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                sev = Risk.Severity.MEDIUM;
            }
            String mitigation = rm.group(3) != null ? rm.group(3).trim() : "";
            risks.add(new Risk(desc, sev, mitigation));
        }

        // Fallback: look for bullet-point risks without the structured format
        if (risks.isEmpty()) {
            risks.addAll(extractUnstructuredRisks(analyzerResponse));
        }

        // ── Missing context (NEEDS section) ──
        List<String> missingContext = new ArrayList<>();
        Matcher nm = NEEDS_SECTION_PATTERN.matcher(analyzerResponse);
        if (nm.find()) {
            String needsBlock = nm.group(1);
            Matcher itemMatcher = NEEDS_ITEM_PATTERN.matcher(needsBlock);
            while (itemMatcher.find()) {
                String item = itemMatcher.group(1).trim()
                    .replaceAll("[`'\"]", ""); // strip quotes/backticks
                if (!item.isEmpty() && !item.equalsIgnoreCase("none") &&
                    !item.equalsIgnoreCase("nothing")) {
                    missingContext.add(item);
                }
            }
        }

        // ── Feedback (everything after RISKS/NEEDS as general feedback) ──
        String feedback = extractFeedback(analyzerResponse);

        return new SafetyVerdict(confidence, verdictSafe, risks, missingContext,
            feedback, analyzerResponse);
    }

    // ═══════════════════════════════════════════════════════════════
    // Safety computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Determine if the refactoring is safe, considering:
     * <ul>
     *   <li>Confidence must be ≥ threshold</li>
     *   <li>No HIGH-severity risks</li>
     *   <li>The analyzer's own verdict</li>
     * </ul>
     *
     * @param safetyThreshold the minimum confidence to consider safe
     * @return true if all safety criteria are met
     */
    public boolean isSafe(double safetyThreshold) {
        if (!verdictSafe) return false;
        if (confidence < safetyThreshold) return false;
        // Any HIGH-severity risk forces UNSAFE
        for (Risk r : risks) {
            if (r.getSeverity() == Risk.Severity.HIGH) return false;
        }
        return true;
    }

    /**
     * Check if the analyzer has requested additional context from the graph.
     */
    public boolean needsMoreContext() {
        return !missingContext.isEmpty();
    }

    /**
     * Check if this verdict has the same risk descriptions as another
     * (used for stagnation detection).
     */
    public boolean hasSameRisks(SafetyVerdict other) {
        if (other == null) return false;
        if (this.risks.size() != other.risks.size()) return false;
        for (int i = 0; i < risks.size(); i++) {
            if (!risks.get(i).getDescription().equalsIgnoreCase(
                    other.risks.get(i).getDescription())) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public double getConfidence() { return confidence; }
    public boolean isVerdictSafe() { return verdictSafe; }
    public List<Risk> getRisks() { return risks; }
    public List<String> getMissingContext() { return missingContext; }
    public String getFeedback() { return feedback; }
    public String getRawResponse() { return rawResponse; }

    public int getHighRiskCount() {
        return (int) risks.stream()
            .filter(r -> r.getSeverity() == Risk.Severity.HIGH).count();
    }

    public int getMediumRiskCount() {
        return (int) risks.stream()
            .filter(r -> r.getSeverity() == Risk.Severity.MEDIUM).count();
    }

    @Override
    public String toString() {
        return String.format(
            "SafetyVerdict { %s, confidence=%.2f, risks=%d (H=%d M=%d L=%d), needs=%d, feedback=%d chars }",
            verdictSafe ? "✓ SAFE" : "✗ UNSAFE",
            confidence, risks.size(),
            getHighRiskCount(), getMediumRiskCount(),
            risks.size() - getHighRiskCount() - getMediumRiskCount(),
            missingContext.size(), feedback.length()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract bullet-point risks from unstructured text (fallback).
     */
    private static List<Risk> extractUnstructuredRisks(String response) {
        List<Risk> risks = new ArrayList<>();

        // Look for a RISKS: section
        int risksIdx = response.toLowerCase().indexOf("risks:");
        if (risksIdx < 0) risksIdx = response.toLowerCase().indexOf("risk:");
        if (risksIdx < 0) return risks;

        String section = response.substring(risksIdx);
        // Cut at next major section
        int nextSection = findNextSection(section, 10);
        if (nextSection > 0) {
            section = section.substring(0, nextSection);
        }

        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String riskText = trimmed.replaceFirst("^[-*]\\s+", "").trim();
                if (!riskText.isEmpty() && !riskText.toLowerCase().startsWith("risk:")) {
                    // Guess severity from keywords
                    Risk.Severity sev = Risk.Severity.MEDIUM;
                    String lower = riskText.toLowerCase();
                    if (lower.contains("critical") || lower.contains("breaking") ||
                        lower.contains("compilation") || lower.contains("compile error")) {
                        sev = Risk.Severity.HIGH;
                    } else if (lower.contains("minor") || lower.contains("cosmetic") ||
                               lower.contains("style")) {
                        sev = Risk.Severity.LOW;
                    }
                    risks.add(new Risk(riskText, sev, ""));
                }
            }
        }
        return risks;
    }

    /**
     * Extract general feedback text from the response.
     */
    private static String extractFeedback(String response) {
        // Try to find a FEEDBACK section
        int fbIdx = response.toLowerCase().indexOf("feedback:");
        if (fbIdx >= 0) {
            String after = response.substring(fbIdx + "feedback:".length()).trim();
            int end = findNextSection(after, 0);
            return end > 0 ? after.substring(0, end).trim() : after.trim();
        }
        // Otherwise use everything as feedback
        return response.trim();
    }

    /**
     * Find the position of the next section header after a given offset.
     */
    private static int findNextSection(String text, int startAfter) {
        String[] headers = {"CONFIDENCE:", "VERDICT:", "RISKS:", "NEEDS:", "FEEDBACK:", "```"};
        String sub = text.substring(Math.min(startAfter, text.length()));
        int earliest = -1;
        for (String header : headers) {
            int idx = sub.toLowerCase().indexOf(header.toLowerCase());
            if (idx > 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest > 0 ? earliest + startAfter : -1;
    }
}

