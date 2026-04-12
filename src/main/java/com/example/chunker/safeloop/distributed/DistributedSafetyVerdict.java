package com.example.chunker.safeloop.distributed;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured JSON protocol between the Generator and Critic LLMs.
 *
 * <p>This is the exact wire format that drives the distributed safe loop
 * automatically. The Critic (analyzer) is forced to return this JSON:
 *
 * <h3>Analyzer → Orchestrator JSON protocol:</h3>
 * <pre>
 * {
 *   "safe": false,
 *   "confidence": 0.65,
 *   "issues": [
 *     {
 *       "description": "Missing async handling in caller",
 *       "severity": "HIGH",
 *       "mitigation": "Wrap caller in CompletableFuture"
 *     },
 *     {
 *       "description": "Potential race condition on shared state",
 *       "severity": "MEDIUM",
 *       "mitigation": "Add synchronized block or use AtomicReference"
 *     }
 *   ],
 *   "missing_context": [
 *     "UserController#createUser",
 *     "TransactionManager#beginTransaction"
 *   ],
 *   "summary": "The refactoring introduces async behavior but callers are not prepared for it."
 * }
 * </pre>
 *
 * <h3>Generator → Orchestrator JSON protocol:</h3>
 * <pre>
 * {
 *   "refactored_code": "public CompletableFuture&lt;User&gt; createUser(User user) { ... }",
 *   "changes": [
 *     {
 *       "file": "UserService.java",
 *       "method": "createUser",
 *       "description": "Converted to async using CompletableFuture"
 *     }
 *   ],
 *   "explanation": "Changed createUser to return CompletableFuture for non-blocking execution.",
 *   "assumptions": [
 *     "UserRepository.save() is thread-safe",
 *     "Caller can handle CompletableFuture return type"
 *   ],
 *   "breaking_changes": [
 *     "Method signature changed from User to CompletableFuture&lt;User&gt;",
 *     "All callers must be updated"
 *   ]
 * }
 * </pre>
 *
 * <h3>Orchestrator → Generator refinement protocol:</h3>
 * <pre>
 * {
 *   "action": "refine",
 *   "iteration": 2,
 *   "original_query": "Refactor createUser to async",
 *   "previous_issues": [
 *     {
 *       "description": "Missing async handling in caller",
 *       "severity": "HIGH",
 *       "mitigation": "Wrap caller in CompletableFuture"
 *     }
 *   ],
 *   "new_context": "=== Additional context ...",
 *   "instructions": "Address all issues. Use the new context to fix caller impact."
 * }
 * </pre>
 *
 * <h3>Orchestrator → Analyzer evaluation protocol:</h3>
 * <pre>
 * {
 *   "action": "evaluate",
 *   "iteration": 1,
 *   "original_query": "Refactor createUser to async",
 *   "proposed_refactoring": { ... generator output ... },
 *   "graph_coverage": {
 *     "total_methods_retrieved": 12,
 *     "graph_expansions": 2
 *   }
 * }
 * </pre>
 *
 * <p>The parser is lenient — if the LLM wraps JSON in markdown code fences,
 * or mixes text with JSON, the parser extracts the JSON block. If the response
 * is not valid JSON at all, falls back to regex-based text parsing (conservative
 * defaults: safe=false, confidence=0.0).
 */
public class DistributedSafetyVerdict {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── JSON extraction patterns ──
    private static final Pattern JSON_BLOCK_PATTERN =
        Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```", Pattern.DOTALL);
    private static final Pattern RAW_JSON_PATTERN =
        Pattern.compile("(\\{\\s*\"safe\"\\s*:.*\\})", Pattern.DOTALL);

    // ── Fallback text patterns (if JSON parsing fails completely) ──
    private static final Pattern CONFIDENCE_PATTERN =
        Pattern.compile("\"?confidence\"?\\s*[:=]\\s*([0-9]*\\.?[0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFE_PATTERN =
        Pattern.compile("\"?safe\"?\\s*[:=]\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    // ═══════════════════════════════════════════════════════════════
    // Parsed fields
    // ═══════════════════════════════════════════════════════════════

    private final boolean safe;
    private final double confidence;
    private final List<Issue> issues;
    private final List<String> missingContext;
    private final String summary;
    private final String rawResponse;
    private final boolean parsedFromJson;

    private DistributedSafetyVerdict(boolean safe, double confidence,
                                     List<Issue> issues, List<String> missingContext,
                                     String summary, String rawResponse,
                                     boolean parsedFromJson) {
        this.safe = safe;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.issues = List.copyOf(issues);
        this.missingContext = List.copyOf(missingContext);
        this.summary = summary;
        this.rawResponse = rawResponse;
        this.parsedFromJson = parsedFromJson;
    }

    // ═══════════════════════════════════════════════════════════════
    // Issue record
    // ═══════════════════════════════════════════════════════════════

    /**
     * A single issue found by the analyzer.
     */
    public static class Issue {

        public enum Severity { HIGH, MEDIUM, LOW }

        private final String description;
        private final Severity severity;
        private final String mitigation;

        public Issue(String description, Severity severity, String mitigation) {
            this.description = description;
            this.severity = severity;
            this.mitigation = mitigation != null ? mitigation : "";
        }

        public String getDescription() { return description; }
        public Severity getSeverity() { return severity; }
        public String getMitigation() { return mitigation; }

        /**
         * Serialize this issue to a JSON object.
         */
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("description", description);
            obj.addProperty("severity", severity.name());
            if (!mitigation.isEmpty()) {
                obj.addProperty("mitigation", mitigation);
            }
            return obj;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s%s", severity,
                description,
                mitigation.isEmpty() ? "" : " → " + mitigation);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON Protocol Builders (Orchestrator messages)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the JSON message from Orchestrator → Analyzer for evaluation.
     *
     * <p>This is the structured handoff protocol:
     * <pre>
     * {
     *   "action": "evaluate",
     *   "iteration": 1,
     *   "original_query": "...",
     *   "proposed_refactoring": "... generator's full response ...",
     *   "graph_coverage": { "total_methods_retrieved": 12, "graph_expansions": 2 }
     * }
     * </pre>
     */
    public static String buildEvaluationRequest(String originalQuery,
                                                 String generatorResponse,
                                                 int iteration,
                                                 int totalMethodsRetrieved,
                                                 int graphExpansions) {
        JsonObject request = new JsonObject();
        request.addProperty("action", "evaluate");
        request.addProperty("iteration", iteration);
        request.addProperty("original_query", originalQuery);
        request.addProperty("proposed_refactoring", generatorResponse);

        JsonObject coverage = new JsonObject();
        coverage.addProperty("total_methods_retrieved", totalMethodsRetrieved);
        coverage.addProperty("graph_expansions", graphExpansions);
        request.add("graph_coverage", coverage);

        return GSON.toJson(request);
    }

    /**
     * Build the JSON message from Orchestrator → Generator for refinement.
     *
     * <p>This is the structured feedback protocol:
     * <pre>
     * {
     *   "action": "refine",
     *   "iteration": 2,
     *   "original_query": "...",
     *   "previous_issues": [ ... ],
     *   "new_context": "...",
     *   "instructions": "Address all issues..."
     * }
     * </pre>
     */
    public static String buildRefinementRequest(String originalQuery,
                                                 DistributedSafetyVerdict previousVerdict,
                                                 int iteration,
                                                 String newContext) {
        JsonObject request = new JsonObject();
        request.addProperty("action", "refine");
        request.addProperty("iteration", iteration);
        request.addProperty("original_query", originalQuery);

        // Include previous issues as structured JSON
        JsonArray issuesArray = new JsonArray();
        for (Issue issue : previousVerdict.getIssues()) {
            issuesArray.add(issue.toJson());
        }
        request.add("previous_issues", issuesArray);

        if (newContext != null && !newContext.isEmpty()) {
            request.addProperty("new_context", newContext);
        }

        // Build instructions
        StringBuilder instructions = new StringBuilder();
        instructions.append("Address ALL issues listed in previous_issues.\n");
        instructions.append("Your previous confidence score was: ")
            .append(String.format("%.2f", previousVerdict.getConfidence())).append("\n");

        if (!previousVerdict.getMissingContext().isEmpty()) {
            instructions.append("The analyzer needed: ")
                .append(String.join(", ", previousVerdict.getMissingContext())).append("\n");
            if (newContext != null && !newContext.isEmpty()) {
                instructions.append("New context has been provided in new_context field.\n");
            }
        }

        instructions.append("Do not introduce new issues while fixing old ones.\n");
        instructions.append("This is refinement iteration ").append(iteration)
            .append(" — previous attempts were not safe enough.");

        request.addProperty("instructions", instructions.toString());

        return GSON.toJson(request);
    }

    // ═══════════════════════════════════════════════════════════════
    // Parser — Analyzer response → DistributedSafetyVerdict
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse the analyzer LLM's response into a structured verdict.
     *
     * <p>Tries multiple strategies:
     * <ol>
     *   <li>Extract JSON from markdown code fences ({@code ```json ... ```})</li>
     *   <li>Find raw JSON object starting with {@code {"safe":}</li>
     *   <li>Try parsing the entire response as JSON</li>
     *   <li>Fall back to regex-based text extraction (conservative defaults)</li>
     * </ol>
     *
     * @param analyzerResponse the raw text from the analyzer LLM
     * @return the parsed safety verdict
     */
    public static DistributedSafetyVerdict parse(String analyzerResponse) {
        if (analyzerResponse == null || analyzerResponse.isBlank()) {
            return new DistributedSafetyVerdict(false, 0.0, List.of(), List.of(),
                "Empty analyzer response", "", false);
        }

        // Strategy 1: Extract JSON from markdown code fences
        Matcher fenceMatch = JSON_BLOCK_PATTERN.matcher(analyzerResponse);
        if (fenceMatch.find()) {
            DistributedSafetyVerdict parsed = tryParseJson(fenceMatch.group(1), analyzerResponse);
            if (parsed != null) return parsed;
        }

        // Strategy 2: Find raw JSON object { "safe": ... }
        Matcher rawMatch = RAW_JSON_PATTERN.matcher(analyzerResponse);
        if (rawMatch.find()) {
            DistributedSafetyVerdict parsed = tryParseJson(rawMatch.group(1), analyzerResponse);
            if (parsed != null) return parsed;
        }

        // Strategy 3: Try parsing the entire response as JSON
        DistributedSafetyVerdict parsed = tryParseJson(analyzerResponse.trim(), analyzerResponse);
        if (parsed != null) return parsed;

        // Strategy 4: Regex fallback (conservative)
        return parseFromText(analyzerResponse);
    }

    /**
     * Try to parse a JSON string into a verdict.
     * Returns null if parsing fails.
     */
    private static DistributedSafetyVerdict tryParseJson(String jsonStr, String rawResponse) {
        try {
            JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);
            if (json == null) return null;

            // ── safe field ──
            boolean safe = false;
            if (json.has("safe") && json.get("safe").isJsonPrimitive()) {
                safe = json.get("safe").getAsBoolean();
            }

            // ── confidence field ──
            double confidence = 0.0;
            if (json.has("confidence") && json.get("confidence").isJsonPrimitive()) {
                confidence = json.get("confidence").getAsDouble();
            }

            // ── issues array ──
            List<Issue> issues = new ArrayList<>();
            if (json.has("issues") && json.get("issues").isJsonArray()) {
                JsonArray issuesArr = json.getAsJsonArray("issues");
                for (JsonElement el : issuesArr) {
                    if (el.isJsonPrimitive()) {
                        // Simple string issue: "Missing async handling in caller"
                        issues.add(new Issue(el.getAsString(), Issue.Severity.MEDIUM, ""));
                    } else if (el.isJsonObject()) {
                        JsonObject issueObj = el.getAsJsonObject();
                        String desc = issueObj.has("description")
                            ? issueObj.get("description").getAsString() : "Unknown issue";
                        Issue.Severity sev = Issue.Severity.MEDIUM;
                        if (issueObj.has("severity")) {
                            try {
                                sev = Issue.Severity.valueOf(
                                    issueObj.get("severity").getAsString().toUpperCase());
                            } catch (IllegalArgumentException ignored) { }
                        }
                        String mit = issueObj.has("mitigation")
                            ? issueObj.get("mitigation").getAsString() : "";
                        issues.add(new Issue(desc, sev, mit));
                    }
                }
            }

            // ── missing_context array ──
            List<String> missingContext = new ArrayList<>();
            if (json.has("missing_context") && json.get("missing_context").isJsonArray()) {
                for (JsonElement el : json.getAsJsonArray("missing_context")) {
                    if (el.isJsonPrimitive()) {
                        String item = el.getAsString().trim();
                        if (!item.isEmpty()) missingContext.add(item);
                    }
                }
            }

            // ── summary field ──
            String summary = "";
            if (json.has("summary") && json.get("summary").isJsonPrimitive()) {
                summary = json.get("summary").getAsString();
            }

            return new DistributedSafetyVerdict(safe, confidence, issues, missingContext,
                summary, rawResponse, true);

        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * Fallback: parse from unstructured text using regex.
     * Always conservative: defaults to safe=false, confidence=0.0.
     */
    private static DistributedSafetyVerdict parseFromText(String rawResponse) {
        boolean safe = false;
        double confidence = 0.0;

        Matcher sm = SAFE_PATTERN.matcher(rawResponse);
        if (sm.find()) {
            safe = Boolean.parseBoolean(sm.group(1));
        }

        Matcher cm = CONFIDENCE_PATTERN.matcher(rawResponse);
        if (cm.find()) {
            try {
                confidence = Double.parseDouble(cm.group(1));
            } catch (NumberFormatException ignored) { }
        }

        return new DistributedSafetyVerdict(safe, confidence, List.of(), List.of(),
            "WARNING: Could not parse JSON from analyzer. Raw text used as fallback.",
            rawResponse, false);
    }

    // ═══════════════════════════════════════════════════════════════
    // Safety computation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Determine if the refactoring is safe, considering:
     * <ul>
     *   <li>The analyzer's own {@code safe} field must be true</li>
     *   <li>Confidence must be ≥ threshold</li>
     *   <li>No HIGH-severity issues</li>
     * </ul>
     */
    public boolean isSafe(double safetyThreshold) {
        if (!safe) return false;
        if (confidence < safetyThreshold) return false;
        for (Issue issue : issues) {
            if (issue.getSeverity() == Issue.Severity.HIGH) return false;
        }
        return true;
    }

    /**
     * Check if the analyzer has requested additional context.
     */
    public boolean needsMoreContext() {
        return !missingContext.isEmpty();
    }

    /**
     * Check if this verdict has the same issues as another (stagnation detection).
     */
    public boolean hasSameIssues(DistributedSafetyVerdict other) {
        if (other == null) return false;
        if (this.issues.size() != other.issues.size()) return false;
        for (int i = 0; i < issues.size(); i++) {
            if (!issues.get(i).getDescription().equalsIgnoreCase(
                    other.issues.get(i).getDescription())) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // Serialization (verdict → JSON for logging / debugging)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Serialize this verdict back to the canonical JSON protocol format.
     */
    public String toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("safe", safe);
        obj.addProperty("confidence", confidence);

        JsonArray issuesArr = new JsonArray();
        for (Issue issue : issues) {
            issuesArr.add(issue.toJson());
        }
        obj.add("issues", issuesArr);

        JsonArray contextArr = new JsonArray();
        for (String ctx : missingContext) {
            contextArr.add(ctx);
        }
        obj.add("missing_context", contextArr);

        obj.addProperty("summary", summary);

        return GSON.toJson(obj);
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public boolean isSafe() { return safe; }
    public double getConfidence() { return confidence; }
    public List<Issue> getIssues() { return issues; }
    public List<String> getMissingContext() { return missingContext; }
    public String getSummary() { return summary; }
    public String getRawResponse() { return rawResponse; }
    public boolean isParsedFromJson() { return parsedFromJson; }

    public int getHighIssueCount() {
        return (int) issues.stream()
            .filter(i -> i.getSeverity() == Issue.Severity.HIGH).count();
    }

    public int getMediumIssueCount() {
        return (int) issues.stream()
            .filter(i -> i.getSeverity() == Issue.Severity.MEDIUM).count();
    }

    @Override
    public String toString() {
        return String.format(
            "DistributedSafetyVerdict { %s, confidence=%.2f, issues=%d (H=%d M=%d L=%d), " +
            "missing_context=%d, json=%s }",
            safe ? "✓ SAFE" : "✗ UNSAFE",
            confidence, issues.size(),
            getHighIssueCount(), getMediumIssueCount(),
            issues.size() - getHighIssueCount() - getMediumIssueCount(),
            missingContext.size(),
            parsedFromJson ? "yes" : "FALLBACK"
        );
    }
}

