package com.example.chunker.refactor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured LLM responses from the refactoring loop.
 *
 * <p>Extracts:
 * <ul>
 *   <li>Code blocks (```java ... ```)</li>
 *   <li>Explanation text</li>
 *   <li>Missing-context references (MISSING: [name1, name2])</li>
 *   <li>Breaking changes / risks</li>
 * </ul>
 *
 * <p>The parser is intentionally lenient — local LLMs may not follow
 * the output format perfectly, so we use multiple detection strategies.
 */
public class LlmResponseParser {

    // ── Patterns ──

    /** Matches MISSING: [item1, item2, ...] — the structured machine-readable format. */
    private static final Pattern MISSING_BRACKET_PATTERN =
        Pattern.compile("MISSING:\\s*\\[([^\\]]*)]", Pattern.CASE_INSENSITIVE);

    /** Matches fenced Java code blocks. */
    private static final Pattern CODE_BLOCK_PATTERN =
        Pattern.compile("```java\\s*\\n(.*?)```", Pattern.DOTALL);

    /** Matches any fenced code block (no language). */
    private static final Pattern GENERIC_CODE_BLOCK_PATTERN =
        Pattern.compile("```\\s*\\n(.*?)```", Pattern.DOTALL);

    /** Matches phrases like "I need to see X" or "please provide X". */
    private static final Pattern NEED_TO_SEE_PATTERN =
        Pattern.compile(
            "(?:need to see|need access to|please provide|missing context for|require the code of)\\s+" +
            "(?:the\\s+)?(?:method\\s+|class\\s+)?[`'\"]?([A-Z][A-Za-z0-9]*(?:\\.[a-zA-Z][A-Za-z0-9]*)*)\\b",
            Pattern.CASE_INSENSITIVE
        );

    // ═══════════════════════════════════════════════════════════════
    // Main parse
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse the full LLM response into structured components.
     *
     * @param llmResponse the raw text response from the LLM
     * @return parsed result containing code, explanation, and missing references
     */
    public RefactorResponse parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new RefactorResponse("", "", List.of(), List.of(), llmResponse);
        }

        String code = extractCode(llmResponse);
        String explanation = extractExplanation(llmResponse);
        List<String> missingRefs = extractMissingReferences(llmResponse);
        List<String> breakingChanges = extractBreakingChanges(llmResponse);

        return new RefactorResponse(code, explanation, missingRefs, breakingChanges, llmResponse);
    }

    // ═══════════════════════════════════════════════════════════════
    // Code extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract all Java code blocks from the response, concatenated.
     */
    private String extractCode(String response) {
        StringBuilder sb = new StringBuilder();

        Matcher m = CODE_BLOCK_PATTERN.matcher(response);
        while (m.find()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.group(1).trim());
        }

        // Fallback: try generic code blocks if no Java-specific ones found
        if (sb.length() == 0) {
            m = GENERIC_CODE_BLOCK_PATTERN.matcher(response);
            while (m.find()) {
                String block = m.group(1).trim();
                // Heuristic: looks like Java code if it has common Java keywords
                if (looksLikeJava(block)) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(block);
                }
            }
        }

        return sb.toString();
    }

    private boolean looksLikeJava(String code) {
        return code.contains("public ") || code.contains("private ") ||
               code.contains("class ") || code.contains("void ") ||
               code.contains("return ") || code.contains("import ");
    }

    // ═══════════════════════════════════════════════════════════════
    // Explanation extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract the explanation section from the response.
     * Looks for an "EXPLANATION:" header or text between code blocks.
     */
    private String extractExplanation(String response) {
        // Try to find an explicit EXPLANATION section
        int explIdx = indexOfIgnoreCase(response, "EXPLANATION:");
        if (explIdx >= 0) {
            String after = response.substring(explIdx + "EXPLANATION:".length()).trim();
            // Take until next section header or code block
            int end = findNextSection(after);
            return end >= 0 ? after.substring(0, end).trim() : after.trim();
        }

        // Fallback: text after the last code block and before BREAKING/MISSING
        Matcher m = CODE_BLOCK_PATTERN.matcher(response);
        int lastCodeEnd = -1;
        while (m.find()) {
            lastCodeEnd = m.end();
        }
        if (lastCodeEnd >= 0 && lastCodeEnd < response.length()) {
            String after = response.substring(lastCodeEnd).trim();
            int breakIdx = indexOfIgnoreCase(after, "BREAKING");
            int missIdx = indexOfIgnoreCase(after, "MISSING:");
            int end = Math.min(
                breakIdx >= 0 ? breakIdx : after.length(),
                missIdx >= 0 ? missIdx : after.length()
            );
            String explanation = after.substring(0, end).trim();
            if (!explanation.isEmpty()) return explanation;
        }

        return "";
    }

    // ═══════════════════════════════════════════════════════════════
    // Missing references
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract the list of missing method/class references.
     * Uses two strategies:
     * <ol>
     *   <li>Structured: MISSING: [name1, name2]</li>
     *   <li>Heuristic: "I need to see X" / "please provide X"</li>
     * </ol>
     */
    List<String> extractMissingReferences(String response) {
        List<String> missing = new ArrayList<>();

        // Strategy 1: structured MISSING: [...] block
        Matcher m = MISSING_BRACKET_PATTERN.matcher(response);
        while (m.find()) {
            String inner = m.group(1).trim();
            if (inner.isEmpty()) continue;

            // Split by comma, trim each
            for (String ref : inner.split(",")) {
                String cleaned = ref.trim()
                    .replaceAll("[`'\"]", "")  // strip quotes/backticks
                    .trim();
                if (!cleaned.isEmpty()) {
                    missing.add(cleaned);
                }
            }
        }

        // Strategy 2: heuristic NLP detection
        if (missing.isEmpty()) {
            m = NEED_TO_SEE_PATTERN.matcher(response);
            while (m.find()) {
                String ref = m.group(1).trim();
                if (!ref.isEmpty() && !missing.contains(ref)) {
                    missing.add(ref);
                }
            }
        }

        return missing;
    }

    // ═══════════════════════════════════════════════════════════════
    // Breaking changes
    // ═══════════════════════════════════════════════════════════════

    private List<String> extractBreakingChanges(String response) {
        List<String> changes = new ArrayList<>();

        int idx = indexOfIgnoreCase(response, "BREAKING CHANGE");
        if (idx < 0) {
            idx = indexOfIgnoreCase(response, "BREAKING:");
        }
        if (idx < 0) return changes;

        String section = response.substring(idx).trim();
        int end = findNextSection(section.substring(Math.min(20, section.length())));
        if (end >= 0) {
            section = section.substring(0, end + 20);
        }

        // Extract bullet points
        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches("^\\d+\\.\\s.*")) {
                changes.add(trimmed.replaceFirst("^[-*]\\s+|^\\d+\\.\\s+", "").trim());
            }
        }

        // If section exists but no bullets, take the whole text
        if (changes.isEmpty() && section.length() > 20) {
            String content = section.replaceFirst("(?i)^BREAKING[^:]*:?\\s*", "").trim();
            if (!content.isEmpty() && !content.equalsIgnoreCase("none") &&
                !content.toLowerCase().contains("no breaking changes")) {
                changes.add(content);
            }
        }

        return changes;
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private int indexOfIgnoreCase(String text, String search) {
        return text.toLowerCase().indexOf(search.toLowerCase());
    }

    /**
     * Find the start of the next section header (e.g. "CHANGES:", "EXPLANATION:", etc.).
     */
    private int findNextSection(String text) {
        String[] headers = {"CHANGES:", "EXPLANATION:", "BREAKING", "MISSING:", "RISK:", "```"};
        int earliest = -1;
        for (String header : headers) {
            int idx = indexOfIgnoreCase(text, header);
            if (idx > 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest;
    }

    // ═══════════════════════════════════════════════════════════════
    // Response record
    // ═══════════════════════════════════════════════════════════════

    /**
     * Structured result from parsing an LLM refactoring response.
     */
    public static class RefactorResponse {

        private final String code;
        private final String explanation;
        private final List<String> missingReferences;
        private final List<String> breakingChanges;
        private final String rawResponse;

        public RefactorResponse(String code, String explanation,
                                 List<String> missingReferences,
                                 List<String> breakingChanges,
                                 String rawResponse) {
            this.code = code;
            this.explanation = explanation;
            this.missingReferences = List.copyOf(missingReferences);
            this.breakingChanges = List.copyOf(breakingChanges);
            this.rawResponse = rawResponse;
        }

        public String getCode() { return code; }
        public String getExplanation() { return explanation; }
        public List<String> getMissingReferences() { return missingReferences; }
        public List<String> getBreakingChanges() { return breakingChanges; }
        public String getRawResponse() { return rawResponse; }

        public boolean hasMissingReferences() {
            return !missingReferences.isEmpty();
        }

        public boolean hasCode() {
            return code != null && !code.isBlank();
        }

        @Override
        public String toString() {
            return String.format(
                "RefactorResponse { hasCode=%s, missing=%d refs, breaking=%d items, explanation=%d chars }",
                hasCode(), missingReferences.size(), breakingChanges.size(), explanation.length()
            );
        }
    }
}

