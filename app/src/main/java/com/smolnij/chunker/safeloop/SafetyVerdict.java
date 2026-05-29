package com.smolnij.chunker.safeloop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured result parsed from the safety-analyzer LLM's evaluation response.
 *
 * <p>Primary wire format is JSON, matching the schema requested by
 * {@code SafeRefactorLoop.ANALYZER_SYSTEM_PROMPT}:
 * <pre>
 * {
 *   "confidence": 0.85,
 *   "verdict": "SAFE" | "UNSAFE",
 *   "risks": [{"description": "...", "severity": "HIGH|MEDIUM|LOW", "mitigation": "..."}],
 *   "needs": ["UserRepository#save"],
 *   "feedback": "..."
 * }
 * </pre>
 *
 * <p>Legacy field names (from {@code DistributedSafetyVerdict}) are also
 * accepted: {@code safe}/{@code issues}/{@code missing_context}/{@code summary}.
 *
 * <p>When the LLM returns free-form text instead of JSON (older models,
 * models without {@code response_format} support), the parser falls back
 * to the regex logic that still matches the original tagged format:
 * <pre>
 *   CONFIDENCE: 0.85
 *   VERDICT: SAFE           (or VERDICT: UNSAFE)
 *   RISKS:
 *   - RISK: ... | SEVERITY: HIGH | MITIGATION: ...
 *   NEEDS:
 *   - ClassName#methodName
 * </pre>
 *
 * <p>{@link #isSafe(double)} computes safety from both confidence and
 * severity: a single HIGH-severity risk forces UNSAFE regardless of
 * confidence. {@link #isParsedFromJson()} reports whether JSON parsing
 * succeeded — the eval harness uses it as a format-fidelity metric.
 */
public class SafetyVerdict {

    private static final Gson GSON = new Gson();

    // ── JSON extraction patterns ──

    private static final Pattern JSON_FENCE_PATTERN =
        Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```", Pattern.DOTALL);

    private static final Pattern JSON_OBJECT_START_PATTERN =
        Pattern.compile("(\\{\\s*\"(?:verdict|safe|confidence)\"\\s*:.*\\})", Pattern.DOTALL);

    // ── Legacy text patterns (fallback) ──

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
    private final boolean parsedFromJson;

    private SafetyVerdict(double confidence, boolean verdictSafe, List<Risk> risks,
                          List<String> missingContext, String feedback, String rawResponse,
                          boolean parsedFromJson) {
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.verdictSafe = verdictSafe;
        this.risks = List.copyOf(risks);
        this.missingContext = List.copyOf(missingContext);
        this.feedback = feedback;
        this.rawResponse = rawResponse;
        this.parsedFromJson = parsedFromJson;
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
     * <p>Strategies, in order: fenced JSON block → raw JSON object →
     * whole response as JSON → legacy tagged-text regex.
     */
    public static SafetyVerdict parse(String analyzerResponse) {
        if (analyzerResponse == null || analyzerResponse.isBlank()) {
            return new SafetyVerdict(0.0, false, List.of(), List.of(),
                "Empty analyzer response", "", false);
        }

        Matcher fence = JSON_FENCE_PATTERN.matcher(analyzerResponse);
        if (fence.find()) {
            SafetyVerdict v = tryParseJson(fence.group(1), analyzerResponse);
            if (v != null) return v;
        }

        Matcher raw = JSON_OBJECT_START_PATTERN.matcher(analyzerResponse);
        if (raw.find()) {
            SafetyVerdict v = tryParseJson(raw.group(1), analyzerResponse);
            if (v != null) return v;
        }

        SafetyVerdict whole = tryParseJson(analyzerResponse.trim(), analyzerResponse);
        if (whole != null) return whole;

        return parseFromText(analyzerResponse);
    }

    private static SafetyVerdict tryParseJson(String jsonStr, String rawResponse) {
        JsonObject json;
        try {
            json = GSON.fromJson(jsonStr, JsonObject.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
        if (json == null) return null;

        boolean verdictSafe = readVerdict(json);
        double confidence = readConfidence(json);
        List<Risk> risks = readRisks(json);
        List<String> missingContext = readStringArray(json, "needs", "missing_context");
        String feedback = readStringField(json, "feedback", "summary");

        return new SafetyVerdict(confidence, verdictSafe, risks, missingContext,
            feedback, rawResponse, true);
    }

    private static boolean readVerdict(JsonObject json) {
        if (json.has("verdict") && json.get("verdict").isJsonPrimitive()) {
            return "SAFE".equalsIgnoreCase(json.get("verdict").getAsString());
        }
        if (json.has("safe") && json.get("safe").isJsonPrimitive()) {
            return json.get("safe").getAsBoolean();
        }
        return false;
    }

    private static double readConfidence(JsonObject json) {
        if (json.has("confidence") && json.get("confidence").isJsonPrimitive()) {
            try { return json.get("confidence").getAsDouble(); }
            catch (NumberFormatException ignored) { }
        }
        return 0.0;
    }

    private static List<Risk> readRisks(JsonObject json) {
        List<Risk> risks = new ArrayList<>();
        JsonArray arr = null;
        if (json.has("risks") && json.get("risks").isJsonArray()) {
            arr = json.getAsJsonArray("risks");
        } else if (json.has("issues") && json.get("issues").isJsonArray()) {
            arr = json.getAsJsonArray("issues");
        }
        if (arr == null) return risks;

        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                risks.add(new Risk(el.getAsString(), Risk.Severity.MEDIUM, ""));
                continue;
            }
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String desc = o.has("description") ? o.get("description").getAsString() : "Unknown risk";
            Risk.Severity sev = Risk.Severity.MEDIUM;
            if (o.has("severity") && o.get("severity").isJsonPrimitive()) {
                try { sev = Risk.Severity.valueOf(o.get("severity").getAsString().trim().toUpperCase()); }
                catch (IllegalArgumentException ignored) { }
            }
            String mit = o.has("mitigation") && o.get("mitigation").isJsonPrimitive()
                ? o.get("mitigation").getAsString() : "";
            risks.add(new Risk(desc, sev, mit));
        }
        return risks;
    }

    private static List<String> readStringArray(JsonObject json, String... fieldNames) {
        List<String> out = new ArrayList<>();
        for (String name : fieldNames) {
            if (!json.has(name) || !json.get(name).isJsonArray()) continue;
            for (JsonElement el : json.getAsJsonArray(name)) {
                if (!el.isJsonPrimitive()) continue;
                String s = el.getAsString().trim();
                if (!s.isEmpty() && !s.equalsIgnoreCase("none")) out.add(s);
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static String readStringField(JsonObject json, String... fieldNames) {
        for (String name : fieldNames) {
            if (json.has(name) && json.get(name).isJsonPrimitive()) {
                return json.get(name).getAsString();
            }
        }
        return "";
    }

    private static SafetyVerdict parseFromText(String analyzerResponse) {
        // ── Confidence ──
        double confidence = 0.0;
        Matcher cm = CONFIDENCE_PATTERN.matcher(analyzerResponse);
        if (cm.find()) {
            try { confidence = Double.parseDouble(cm.group(1)); }
            catch (NumberFormatException ignored) { }
        }

        // ── Verdict ──
        boolean verdictSafe = false;
        Matcher vm = VERDICT_PATTERN.matcher(analyzerResponse);
        if (vm.find()) {
            verdictSafe = vm.group(1).equalsIgnoreCase("SAFE");
        } else {
            String lower = analyzerResponse.toLowerCase();
            if (lower.contains("no risks") || lower.contains("all clear") ||
                lower.contains("change is safe") || lower.contains("refactoring is safe") ||
                lower.contains("no breaking changes detected")) {
                verdictSafe = true;
            }
        }

        if (confidence >= 0.9 && !vm.find()) {
            verdictSafe = true;
        }

        // ── Risks ──
        List<Risk> risks = new ArrayList<>();
        Matcher rm = RISK_LINE_PATTERN.matcher(analyzerResponse);
        while (rm.find()) {
            String desc = rm.group(1).trim();
            Risk.Severity sev;
            try { sev = Risk.Severity.valueOf(rm.group(2).trim().toUpperCase()); }
            catch (IllegalArgumentException e) { sev = Risk.Severity.MEDIUM; }
            String mitigation = rm.group(3) != null ? rm.group(3).trim() : "";
            risks.add(new Risk(desc, sev, mitigation));
        }

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
                    .replaceAll("[`'\"]", "");
                if (!item.isEmpty() && !item.equalsIgnoreCase("none") &&
                    !item.equalsIgnoreCase("nothing")) {
                    missingContext.add(item);
                }
            }
        }

        String feedback = extractFeedback(analyzerResponse);

        return new SafetyVerdict(confidence, verdictSafe, risks, missingContext,
            feedback, analyzerResponse, false);
    }

    // ═══════════════════════════════════════════════════════════════
    // Safety computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Determine if the refactoring is safe:
     * verdict must be SAFE, confidence ≥ threshold, no HIGH-severity risks.
     */
    public boolean isSafe(double safetyThreshold) {
        if (!verdictSafe) return false;
        if (confidence < safetyThreshold) return false;
        for (Risk r : risks) {
            if (r.getSeverity() == Risk.Severity.HIGH) return false;
        }
        return true;
    }

    public boolean needsMoreContext() {
        return !missingContext.isEmpty();
    }

    /**
     * Stagnation detection: same risk descriptions as a previous verdict.
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
    public boolean isParsedFromJson() { return parsedFromJson; }

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
            "SafetyVerdict { %s, confidence=%.2f, risks=%d (H=%d M=%d L=%d), needs=%d, feedback=%d chars, json=%s }",
            verdictSafe ? "✓ SAFE" : "✗ UNSAFE",
            confidence, risks.size(),
            getHighRiskCount(), getMediumRiskCount(),
            risks.size() - getHighRiskCount() - getMediumRiskCount(),
            missingContext.size(), feedback.length(),
            parsedFromJson ? "yes" : "FALLBACK"
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers (shared with the legacy text path)
    // ═══════════════════════════════════════════════════════════════

    private static List<Risk> extractUnstructuredRisks(String response) {
        List<Risk> risks = new ArrayList<>();

        int risksIdx = response.toLowerCase().indexOf("risks:");
        if (risksIdx < 0) risksIdx = response.toLowerCase().indexOf("risk:");
        if (risksIdx < 0) return risks;

        String section = response.substring(risksIdx);
        int nextSection = findNextSection(section, 10);
        if (nextSection > 0) {
            section = section.substring(0, nextSection);
        }

        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                String riskText = trimmed.replaceFirst("^[-*]\\s+", "").trim();
                if (!riskText.isEmpty() && !riskText.toLowerCase().startsWith("risk:")) {
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

    private static String extractFeedback(String response) {
        int fbIdx = response.toLowerCase().indexOf("feedback:");
        if (fbIdx >= 0) {
            String after = response.substring(fbIdx + "feedback:".length()).trim();
            int end = findNextSection(after, 0);
            return end > 0 ? after.substring(0, end).trim() : after.trim();
        }
        return response.trim();
    }

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
