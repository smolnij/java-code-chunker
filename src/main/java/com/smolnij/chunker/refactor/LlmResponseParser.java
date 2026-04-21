package com.smolnij.chunker.refactor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import com.smolnij.chunker.apply.EditOp;
import com.smolnij.chunker.apply.PatchPlan;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured LLM responses from the refactoring loop.
 *
 * <p>Primary wire format is JSON, matching the schema requested by
 * {@link PromptBuilder}:
 * <pre>
 * {
 *   "code_blocks": [{"class": "UserService", "method": "createUser", "code": "..."}],
 *   "explanation": "...",
 *   "breaking_changes": ["..."],
 *   "missing_references": ["ClassName.methodName"]
 * }
 * </pre>
 *
 * <p>Analysis-step responses use a narrower shape and go through
 * {@link #parseAnalysis(String)}:
 * <pre>
 * { "dependencies": ["..."], "impact": "...", "missing_references": ["..."] }
 * </pre>
 *
 * <p>When the LLM returns free-form text (no {@code response_format}
 * support), both entry points fall back to the original tagged-text
 * regex extractor — {@code ```java ... ```} blocks, {@code EXPLANATION:},
 * {@code BREAKING CHANGES:}, {@code MISSING: [...]} — so existing
 * {@code RefactorConfig.StructuredOutputMode.OFF} behavior is preserved.
 */
public class LlmResponseParser {

    private static final Gson GSON = new Gson();

    // ── JSON extraction patterns ──

    private static final Pattern JSON_FENCE_PATTERN =
        Pattern.compile("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*\\n?```", Pattern.DOTALL);

    private static final Pattern JSON_OBJECT_START_PATTERN =
        Pattern.compile(
            "(\\{\\s*\"(?:code_blocks|explanation|breaking_changes|missing_references|dependencies|impact)\"\\s*:.*\\})",
            Pattern.DOTALL
        );

    // ── Legacy text patterns (fallback) ──

    /** Matches MISSING: [item1, item2, ...] — the original tagged format. */
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
    // Refactor-response parser
    // ═══════════════════════════════════════════════════════════════

    public RefactorResponse parse(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new RefactorResponse("", "", List.of(), List.of(), llmResponse, false);
        }
        JsonObject json = extractJson(llmResponse);
        if (json != null) {
            RefactorResponse jsonResult = refactorFromJson(json, llmResponse);
            if (jsonResult != null) return jsonResult;
        }
        return refactorFromText(llmResponse);
    }

    private RefactorResponse refactorFromJson(JsonObject json, String rawResponse) {
        String code = readCodeBlocks(json);
        String explanation = readStringField(json, "explanation");
        List<String> breakingChanges = readStringArray(json, "breaking_changes");
        List<String> missingRefs = readStringArray(json, "missing_references");

        // If JSON is the wrong shape entirely (e.g. analysis response given to parse),
        // still succeed — missingRefs may be populated and that drives the refinement loop.
        boolean anyField = !code.isEmpty() || !explanation.isEmpty()
            || !breakingChanges.isEmpty() || !missingRefs.isEmpty();
        if (!anyField && !json.has("code_blocks") && !json.has("explanation")
            && !json.has("breaking_changes") && !json.has("missing_references")) {
            return null;
        }
        return new RefactorResponse(code, explanation, missingRefs, breakingChanges,
            rawResponse, true);
    }

    private static String readCodeBlocks(JsonObject json) {
        if (!json.has("code_blocks") || !json.get("code_blocks").isJsonArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : json.getAsJsonArray("code_blocks")) {
            String code;
            if (el.isJsonPrimitive()) {
                code = el.getAsString();
            } else if (el.isJsonObject() && el.getAsJsonObject().has("code")
                       && el.getAsJsonObject().get("code").isJsonPrimitive()) {
                code = el.getAsJsonObject().get("code").getAsString();
            } else {
                continue;
            }
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(code.strip());
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Analysis-response parser
    // ═══════════════════════════════════════════════════════════════

    public AnalysisResponse parseAnalysis(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return new AnalysisResponse(List.of(), "", List.of(), llmResponse, false);
        }
        JsonObject json = extractJson(llmResponse);
        if (json != null) {
            AnalysisResponse jsonResult = analysisFromJson(json, llmResponse);
            if (jsonResult != null) return jsonResult;
        }
        return analysisFromText(llmResponse);
    }

    private AnalysisResponse analysisFromJson(JsonObject json, String rawResponse) {
        List<String> dependencies = readStringArray(json, "dependencies");
        String impact = readStringField(json, "impact");
        List<String> missingRefs = readStringArray(json, "missing_references");
        if (!json.has("dependencies") && !json.has("impact") && !json.has("missing_references")) {
            return null;
        }
        return new AnalysisResponse(dependencies, impact, missingRefs, rawResponse, true);
    }

    private AnalysisResponse analysisFromText(String response) {
        List<String> missingRefs = extractMissingReferences(response);
        // No reliable regex for "dependencies" / "impact" in the legacy format — leave empty.
        return new AnalysisResponse(List.of(), "", missingRefs, response, false);
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON extraction helpers
    // ═══════════════════════════════════════════════════════════════

    private static JsonObject extractJson(String text) {
        Matcher fence = JSON_FENCE_PATTERN.matcher(text);
        if (fence.find()) {
            JsonObject o = tryParse(fence.group(1));
            if (o != null) return o;
        }
        Matcher raw = JSON_OBJECT_START_PATTERN.matcher(text);
        if (raw.find()) {
            JsonObject o = tryParse(raw.group(1));
            if (o != null) return o;
        }
        return tryParse(text.trim());
    }

    private static JsonObject tryParse(String candidate) {
        try {
            JsonElement el = GSON.fromJson(candidate, JsonElement.class);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static String readStringField(JsonObject json, String name) {
        if (json.has(name) && json.get(name).isJsonPrimitive()) {
            return json.get(name).getAsString();
        }
        return "";
    }

    private static List<String> readStringArray(JsonObject json, String name) {
        List<String> out = new ArrayList<>();
        if (!json.has(name) || !json.get(name).isJsonArray()) return out;
        for (JsonElement el : json.getAsJsonArray(name)) {
            if (el.isJsonPrimitive()) {
                String s = el.getAsString().trim();
                if (!s.isEmpty() && !s.equalsIgnoreCase("none")) out.add(s);
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    // Legacy text-regex path (fallback when JSON is missing/invalid)
    // ═══════════════════════════════════════════════════════════════

    private RefactorResponse refactorFromText(String response) {
        String code = extractCode(response);
        String explanation = extractExplanation(response);
        List<String> missingRefs = extractMissingReferences(response);
        List<String> breakingChanges = extractBreakingChanges(response);
        return new RefactorResponse(code, explanation, missingRefs, breakingChanges,
            response, false);
    }

    private String extractCode(String response) {
        StringBuilder sb = new StringBuilder();

        Matcher m = CODE_BLOCK_PATTERN.matcher(response);
        while (m.find()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(m.group(1).trim());
        }

        if (sb.length() == 0) {
            m = GENERIC_CODE_BLOCK_PATTERN.matcher(response);
            while (m.find()) {
                String block = m.group(1).trim();
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

    private String extractExplanation(String response) {
        int explIdx = indexOfIgnoreCase(response, "EXPLANATION:");
        if (explIdx >= 0) {
            String after = response.substring(explIdx + "EXPLANATION:".length()).trim();
            int end = findNextSection(after);
            return end >= 0 ? after.substring(0, end).trim() : after.trim();
        }

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

    List<String> extractMissingReferences(String response) {
        Set<String> missing = new LinkedHashSet<>();

        Matcher m = MISSING_BRACKET_PATTERN.matcher(response);
        while (m.find()) {
            String inner = m.group(1).trim();
            if (inner.isEmpty()) continue;
            for (String ref : inner.split(",")) {
                String cleaned = ref.trim().replaceAll("[`'\"]", "").trim();
                if (!cleaned.isEmpty()) missing.add(cleaned);
            }
        }

        if (missing.isEmpty()) {
            m = NEED_TO_SEE_PATTERN.matcher(response);
            while (m.find()) {
                String ref = m.group(1).trim();
                if (!ref.isEmpty()) missing.add(ref);
            }
        }

        return new ArrayList<>(missing);
    }

    private List<String> extractBreakingChanges(String response) {
        List<String> changes = new ArrayList<>();

        int idx = indexOfIgnoreCase(response, "BREAKING CHANGE");
        if (idx < 0) idx = indexOfIgnoreCase(response, "BREAKING:");
        if (idx < 0) return changes;

        String section = response.substring(idx).trim();
        int end = findNextSection(section.substring(Math.min(20, section.length())));
        if (end >= 0) {
            section = section.substring(0, end + 20);
        }

        for (String line : section.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches("^\\d+\\.\\s.*")) {
                changes.add(trimmed.replaceFirst("^[-*]\\s+|^\\d+\\.\\s+", "").trim());
            }
        }

        if (changes.isEmpty() && section.length() > 20) {
            String content = section.replaceFirst("(?i)^BREAKING[^:]*:?\\s*", "").trim();
            if (!content.isEmpty() && !content.equalsIgnoreCase("none") &&
                !content.toLowerCase().contains("no breaking changes")) {
                changes.add(content);
            }
        }

        return changes;
    }

    private int indexOfIgnoreCase(String text, String search) {
        return text.toLowerCase().indexOf(search.toLowerCase());
    }

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
    // PatchPlan parser (for deterministic applier)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract a {@link PatchPlan} from an LLM response.
     *
     * <p>Preferred path: the model emitted a JSON object matching
     * {@code patch_plan.schema.json} — an {@code ops[]} array of tagged
     * union members. Fallback path: the model produced the legacy
     * {@code code_blocks} shape; each entry is turned into a
     * {@link EditOp.ReplaceMethod} by resolving {@code class}→FQN and
     * {@code method}→original signature through the Neo4j graph.
     *
     * @param raw         the full raw LLM response (unprocessed)
     * @param graphReader used to resolve {@code class}→FQN and pull
     *                    original signatures for overload disambiguation;
     *                    may be {@code null} to skip the graph-backed fallback
     * @param proposedBy  label recorded on the returned {@link PatchPlan}
     *                    (e.g. {@code "safeloop"})
     */
    public PatchPlan parsePatchPlan(String raw, Neo4jGraphReader graphReader, String proposedBy) {
        if (raw == null || raw.isBlank()) return PatchPlan.empty(proposedBy);

        JsonObject json = extractJson(raw);
        if (json != null && json.has("ops") && json.get("ops").isJsonArray()) {
            List<EditOp> ops = readOpsArray(json.getAsJsonArray("ops"));
            if (!ops.isEmpty()) {
                return new PatchPlan(ops, readStringField(json, "rationale"), proposedBy);
            }
        }

        // Fallback: use the existing RefactorResponse machinery — parse code
        // blocks, then resolve each (class, method) pair against the graph to
        // recover the full FQN + original signature needed for deterministic apply.
        if (graphReader == null) return PatchPlan.empty(proposedBy);

        List<CodeBlock> blocks = extractCodeBlocksWithMeta(raw);
        if (blocks.isEmpty()) return PatchPlan.empty(proposedBy);

        List<EditOp> ops = new ArrayList<>();
        for (CodeBlock b : blocks) {
            if (b.code == null || b.code.isBlank()) continue;
            ResolvedMethod resolved = resolveMethod(graphReader, b.className, b.methodName);
            if (resolved == null) continue;
            ops.add(new EditOp.ReplaceMethod(
                    resolved.fqClassName,
                    resolved.methodName,
                    resolved.originalSignature,
                    b.code));
        }
        return new PatchPlan(ops, "", proposedBy);
    }

    /** Simple no-graph variant: JSON-only extraction, no code_blocks fallback. */
    public PatchPlan parsePatchPlan(String raw, String proposedBy) {
        return parsePatchPlan(raw, null, proposedBy);
    }

    private List<EditOp> readOpsArray(JsonArray arr) {
        List<EditOp> ops = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String kind = readStringField(o, "kind");
            EditOp op = switch (kind.toLowerCase()) {
                case "replace_method" -> new EditOp.ReplaceMethod(
                        readStringField(o, "fq_class_name"),
                        readStringField(o, "method_name"),
                        readStringField(o, "original_signature"),
                        readStringField(o, "new_code"));
                case "add_method" -> new EditOp.AddMethod(
                        readStringField(o, "fq_class_name"),
                        readStringField(o, "new_code"));
                case "delete_method" -> new EditOp.DeleteMethod(
                        readStringField(o, "fq_class_name"),
                        readStringField(o, "method_name"),
                        readStringField(o, "original_signature"));
                case "add_import" -> new EditOp.AddImport(
                        readStringField(o, "file_path"),
                        readStringField(o, "import_decl"));
                case "create_file" -> new EditOp.CreateFile(
                        readStringField(o, "rel_path"),
                        readStringField(o, "content"));
                default -> null;
            };
            if (op != null) ops.add(op);
        }
        return ops;
    }

    /**
     * Resolve a (simpleClassName, methodName) pair — the shape the existing
     * {@code code_blocks} schema exposes — into a full FQN + original
     * signature by looking the method up in the graph.
     */
    private ResolvedMethod resolveMethod(Neo4jGraphReader graphReader,
                                         String className, String methodName) {
        if (methodName == null || methodName.isBlank()) return null;

        // Try "ClassName.methodName" first (Neo4jGraphReader#findMethodExact
        // does a CONTAINS match on chunkId), then just the method name.
        String chunkId = null;
        if (className != null && !className.isBlank()) {
            chunkId = graphReader.findMethodExact(className + "." + methodName);
            if (chunkId == null) {
                chunkId = graphReader.findMethodExact(className + "#" + methodName);
            }
        }
        if (chunkId == null) {
            chunkId = graphReader.findMethodExact(methodName);
        }
        if (chunkId == null) return null;

        Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(List.of(chunkId));
        CodeChunk chunk = chunks.get(chunkId);
        if (chunk == null) return null;
        String fqClass = chunk.getFullyQualifiedClassName();
        if (fqClass == null || fqClass.isBlank()) return null;
        return new ResolvedMethod(fqClass, chunk.getMethodName(), chunk.getMethodSignature());
    }

    /**
     * Extract {@code code_blocks} entries with their {@code class} / {@code method}
     * metadata. Re-uses {@link #extractJson} so both fenced and raw JSON replies
     * work, and silently skips entries whose structure is wrong.
     */
    private List<CodeBlock> extractCodeBlocksWithMeta(String raw) {
        List<CodeBlock> out = new ArrayList<>();
        JsonObject json = extractJson(raw);
        if (json == null || !json.has("code_blocks") || !json.get("code_blocks").isJsonArray()) {
            return out;
        }
        for (JsonElement el : json.getAsJsonArray("code_blocks")) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String cls = readStringField(obj, "class");
            String method = readStringField(obj, "method");
            String code = readStringField(obj, "code");
            if (method.isEmpty() || code.isEmpty()) continue;
            out.add(new CodeBlock(cls, method, code));
        }
        return out;
    }

    private record CodeBlock(String className, String methodName, String code) { }
    private record ResolvedMethod(String fqClassName, String methodName, String originalSignature) { }

    // ═══════════════════════════════════════════════════════════════
    // Response records
    // ═══════════════════════════════════════════════════════════════

    public static class RefactorResponse {

        private final String code;
        private final String explanation;
        private final List<String> missingReferences;
        private final List<String> breakingChanges;
        private final String rawResponse;
        private final boolean parsedFromJson;

        public RefactorResponse(String code, String explanation,
                                 List<String> missingReferences,
                                 List<String> breakingChanges,
                                 String rawResponse,
                                 boolean parsedFromJson) {
            this.code = code;
            this.explanation = explanation;
            this.missingReferences = List.copyOf(missingReferences);
            this.breakingChanges = List.copyOf(breakingChanges);
            this.rawResponse = rawResponse;
            this.parsedFromJson = parsedFromJson;
        }

        public String getCode() { return code; }
        public String getExplanation() { return explanation; }
        public List<String> getMissingReferences() { return missingReferences; }
        public List<String> getBreakingChanges() { return breakingChanges; }
        public String getRawResponse() { return rawResponse; }
        public boolean isParsedFromJson() { return parsedFromJson; }

        public boolean hasMissingReferences() {
            return !missingReferences.isEmpty();
        }

        public boolean hasCode() {
            return code != null && !code.isBlank();
        }

        @Override
        public String toString() {
            return String.format(
                "RefactorResponse { hasCode=%s, missing=%d refs, breaking=%d items, explanation=%d chars, json=%s }",
                hasCode(), missingReferences.size(), breakingChanges.size(),
                explanation.length(), parsedFromJson ? "yes" : "FALLBACK"
            );
        }
    }

    /**
     * Structured result from parsing an analysis-step response.
     */
    public static class AnalysisResponse {

        private final List<String> dependencies;
        private final String impact;
        private final List<String> missingReferences;
        private final String rawResponse;
        private final boolean parsedFromJson;

        public AnalysisResponse(List<String> dependencies, String impact,
                                List<String> missingReferences, String rawResponse,
                                boolean parsedFromJson) {
            this.dependencies = List.copyOf(dependencies);
            this.impact = impact;
            this.missingReferences = List.copyOf(missingReferences);
            this.rawResponse = rawResponse;
            this.parsedFromJson = parsedFromJson;
        }

        public List<String> getDependencies() { return dependencies; }
        public String getImpact() { return impact; }
        public List<String> getMissingReferences() { return missingReferences; }
        public String getRawResponse() { return rawResponse; }
        public boolean isParsedFromJson() { return parsedFromJson; }

        public boolean hasMissingReferences() {
            return !missingReferences.isEmpty();
        }

        @Override
        public String toString() {
            return String.format(
                "AnalysisResponse { deps=%d, impact=%d chars, missing=%d, json=%s }",
                dependencies.size(), impact.length(), missingReferences.size(),
                parsedFromJson ? "yes" : "FALLBACK"
            );
        }
    }
}
