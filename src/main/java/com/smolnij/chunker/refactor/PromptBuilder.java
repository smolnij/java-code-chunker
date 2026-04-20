package com.smolnij.chunker.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.util.List;

/**
 * Builds structured prompts for the LLM following the graph-aware refactoring pattern.
 *
 * <p>Prompt structure:
 * <pre>
 *   SYSTEM: "You are a senior Java engineer."
 *
 *   USER:
 *     TASK: (user's refactoring request)
 *     CONTEXT: [Method: ClassName.methodName] + code  (×N chunks)
 *     RELATIONSHIPS: (calls/calledBy edges between provided chunks)
 *     INSTRUCTIONS: (constraints, patterns)
 *     OUTPUT FORMAT: (what the model should produce)
 * </pre>
 *
 * <p>Also provides a safety-check prompt that asks the LLM to list breaking changes,
 * and a "missing context" prompt that asks the LLM to declare what it needs.
 */
public class PromptBuilder {

    /** Identifies which prompt (and schema) is being built. */
    public enum PromptKind { REFACTOR, ANALYSIS, SAFETY_CHECK }

    private static final String SYSTEM_PROMPT =
        "You are a senior Java engineer with deep expertise in refactoring, " +
        "design patterns, and Java concurrency. You analyze code carefully, " +
        "preserve existing behavior, and produce clean, well-documented changes.";

    private final int maxChunks;

    public PromptBuilder(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    public PromptBuilder(RefactorConfig config) {
        this.maxChunks = config.getMaxChunks();
    }

    // ═══════════════════════════════════════════════════════════════
    // System prompt
    // ═══════════════════════════════════════════════════════════════

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main refactoring prompt
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the full refactoring prompt from a task description and retrieval results.
     *
     * @param task    the user's natural-language request (e.g. "Refactor createUser to async")
     * @param results ranked retrieval results (will be capped at {@code maxChunks})
     * @return the assembled user-message prompt
     */
    public String buildRefactorPrompt(String task, List<RetrievalResult> results) {
        List<RetrievalResult> chunks = results.subList(0, Math.min(results.size(), maxChunks));

        StringBuilder sb = new StringBuilder();

        // ── TASK ──
        sb.append("TASK:\n");
        sb.append(task).append("\n\n");

        // ── CONTEXT ──
        sb.append("CONTEXT:\n\n");
        for (RetrievalResult r : chunks) {
            CodeChunk c = r.getChunk();
            sb.append("[Method: ").append(c.getClassName()).append(".").append(c.getMethodName());
            if (r.isAnchor()) {
                sb.append(" (PRIMARY TARGET)");
            }
            sb.append("]\n");
            sb.append("File: ").append(c.getFilePath()).append("\n");
            sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
            if (!c.getMethodAnnotations().isEmpty()) {
                sb.append("Annotations: ").append(String.join(", ", c.getMethodAnnotations())).append("\n");
            }
            sb.append("```java\n");
            sb.append(c.getCode()).append("\n");
            sb.append("```\n\n");
        }

        // ── RELATIONSHIPS ──
        sb.append("RELATIONSHIPS:\n");

        boolean hasRelationships = false;
        for (RetrievalResult r : chunks) {
            CodeChunk c = r.getChunk();
            String from = c.getClassName() + "." + c.getMethodName();

            for (String callTarget : c.getCalls()) {
                sb.append("- ").append(from).append(" calls ").append(shortName(callTarget)).append("\n");
                hasRelationships = true;
            }
            for (String caller : c.getCalledBy()) {
                sb.append("- ").append(from).append(" called by ").append(shortName(caller)).append("\n");
                hasRelationships = true;
            }
        }
        if (!hasRelationships) {
            sb.append("- (no direct call relationships found between provided methods)\n");
        }
        sb.append("\n");

        // ── INSTRUCTIONS ──
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Keep behavior identical unless the task explicitly asks to change it\n");
        sb.append("- Preserve all existing validation and error handling\n");
        sb.append("- Follow existing code style and naming conventions\n");
        sb.append("- If you need code from a method/class that was NOT provided above, ");
        sb.append("list it in a MISSING section (see output format)\n\n");

        // ── OUTPUT FORMAT ──
        sb.append("OUTPUT FORMAT: Reply with a single JSON object (no prose outside the JSON):\n");
        sb.append("{\n");
        sb.append("  \"code_blocks\": [{\"class\": \"ClassName\", \"method\": \"methodName\", \"code\": \"full updated method source\"}],\n");
        sb.append("  \"explanation\": \"brief summary of what changed and why\",\n");
        sb.append("  \"breaking_changes\": [\"description of each potential break or regression\"],\n");
        sb.append("  \"missing_references\": [\"ClassName.methodName\", \"OtherClass.otherMethod\"]\n");
        sb.append("}\n");
        sb.append("Rules: use [] for empty arrays; use \"\" for empty strings; do NOT wrap the JSON in markdown fences; ");
        sb.append("each code_blocks[].code must contain the complete method source as a single string.\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Safety-check prompt
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a follow-up prompt asking the LLM to analyze the proposed
     * changes for breaking changes and risks.
     *
     * @param proposedChanges the LLM's previous response with code changes
     * @param results         the retrieval results (for context)
     * @return the safety-check user prompt
     */
    public String buildSafetyCheckPrompt(String proposedChanges, List<RetrievalResult> results) {
        return buildSafetyCheckPrompt(proposedChanges, results, "");
    }

    /**
     * Build a follow-up safety prompt enriched with a deterministic AST-diff
     * report. When {@code astDiffReport} is non-empty it is injected as a
     * "DETERMINISTIC AST ANALYSIS" section so the LLM must reconcile its
     * verdict with observable structural facts.
     */
    public String buildSafetyCheckPrompt(String proposedChanges, List<RetrievalResult> results,
                                         String astDiffReport) {
        StringBuilder sb = new StringBuilder();

        sb.append("TASK:\n");
        sb.append("Review the following proposed code changes and list ALL possible breaking changes, ");
        sb.append("regressions, and risks.\n\n");

        sb.append("PROPOSED CHANGES:\n");
        sb.append(proposedChanges).append("\n\n");

        if (astDiffReport != null && !astDiffReport.isEmpty()) {
            sb.append("── DETERMINISTIC AST ANALYSIS (from JavaParser) ──────────\n");
            sb.append("The following is a deterministic structural diff computed by parsing\n");
            sb.append("the proposed code and comparing it against the original AST.\n");
            sb.append("Treat these facts as ground truth when assessing risks:\n\n");
            sb.append(astDiffReport).append("\n");
        }

        // Include a summary of the call graph for context
        sb.append("CALL GRAPH CONTEXT:\n");
        List<RetrievalResult> chunks = results.subList(0, Math.min(results.size(), maxChunks));
        for (RetrievalResult r : chunks) {
            CodeChunk c = r.getChunk();
            String name = c.getClassName() + "." + c.getMethodName();
            if (!c.getCalledBy().isEmpty()) {
                sb.append("- ").append(name).append(" is called by: ");
                sb.append(String.join(", ", c.getCalledBy().stream().map(this::shortName).toList()));
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("OUTPUT FORMAT: Reply with a single JSON object (no prose outside the JSON):\n");
        sb.append("{\n");
        sb.append("  \"code_blocks\": [],\n");
        sb.append("  \"explanation\": \"one-paragraph overall assessment\",\n");
        sb.append("  \"breaking_changes\": [\"<risk description> | SEVERITY: HIGH|MEDIUM|LOW | MITIGATION: <fix>\"],\n");
        sb.append("  \"missing_references\": []\n");
        sb.append("}\n");
        sb.append("Rules: if there are no risks, use [] for breaking_changes; do NOT wrap the JSON in markdown fences.\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Dependency analysis prompt (step 1 of multi-step)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a prompt asking the LLM to first analyze dependencies
     * before suggesting any changes.
     */
    public String buildAnalysisPrompt(String task, List<RetrievalResult> results) {
        List<RetrievalResult> chunks = results.subList(0, Math.min(results.size(), maxChunks));

        StringBuilder sb = new StringBuilder();

        sb.append("TASK:\n");
        sb.append("Analyze the dependencies and impacts BEFORE making any changes.\n");
        sb.append("Original request: ").append(task).append("\n\n");

        sb.append("CONTEXT:\n\n");
        for (RetrievalResult r : chunks) {
            CodeChunk c = r.getChunk();
            sb.append("[Method: ").append(c.getClassName()).append(".").append(c.getMethodName()).append("]\n");
            sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
            if (!c.getCalls().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", c.getCalls().stream().map(this::shortName).toList())).append("\n");
            }
            if (!c.getCalledBy().isEmpty()) {
                sb.append("Called by: ").append(String.join(", ", c.getCalledBy().stream().map(this::shortName).toList())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("OUTPUT FORMAT: Reply with a single JSON object (no prose outside the JSON):\n");
        sb.append("{\n");
        sb.append("  \"dependencies\": [\"ClassName.methodName affected by the change\"],\n");
        sb.append("  \"impact\": \"one-paragraph description of the ripple effect\",\n");
        sb.append("  \"missing_references\": [\"ClassName.methodName you need to see to finish\"]\n");
        sb.append("}\n");
        sb.append("Rules: use [] for empty arrays; use \"\" for empty strings; do NOT wrap the JSON in markdown fences.\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract a short name from a fully-qualified reference.
     * "com.example.UserRepository#save(User)" → "UserRepository.save"
     */
    private String shortName(String fqRef) {
        if (fqRef == null || fqRef.isEmpty()) return fqRef;

        // Strip package prefix — keep from last dot before '#', or last dot overall
        int hashIdx = fqRef.indexOf('#');
        if (hashIdx >= 0) {
            // fqClass#method(...) → ClassName.method
            String classPart = fqRef.substring(0, hashIdx);
            String methodPart = fqRef.substring(hashIdx + 1);
            // Remove parameters for brevity
            int parenIdx = methodPart.indexOf('(');
            if (parenIdx >= 0) {
                methodPart = methodPart.substring(0, parenIdx);
            }
            int lastDot = classPart.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? classPart.substring(lastDot + 1) : classPart;
            return simpleName + "." + methodPart;
        }

        // No hash — just shorten the last segment
        int lastDot = fqRef.lastIndexOf('.');
        return lastDot >= 0 ? fqRef.substring(lastDot + 1) : fqRef;
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON schemas for structured output
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a {@link StructuredOutputSpec} for a prompt kind under the
     * given mode. Returns {@code null} when mode is {@link
     * RefactorConfig.StructuredOutputMode#OFF} — callers should then fall
     * back to the plain {@link ChatService#chat(String, String)} path.
     */
    public static StructuredOutputSpec specFor(PromptKind kind,
                                               RefactorConfig.StructuredOutputMode mode) {
        if (mode == RefactorConfig.StructuredOutputMode.OFF) return null;
        StructuredOutputSpec.Mode wireMode = switch (mode) {
            case JSON_SCHEMA -> StructuredOutputSpec.Mode.JSON_SCHEMA;
            case JSON_OBJECT -> StructuredOutputSpec.Mode.JSON_OBJECT;
            case TOOL_CALL -> StructuredOutputSpec.Mode.TOOL_CALL;
            case OFF -> throw new IllegalStateException("unreachable");
        };
        return new StructuredOutputSpec(schemaName(kind), schemaFor(kind), wireMode);
    }

    public static JsonObject schemaFor(PromptKind kind) {
        return switch (kind) {
            case REFACTOR -> refactorSchema();
            case ANALYSIS -> analysisSchema();
            case SAFETY_CHECK -> safetyCheckSchema();
        };
    }

    public static final String REFACTOR_SCHEMA_NAME = "refactor_response";
    public static final String ANALYSIS_SCHEMA_NAME = "analysis_response";
    public static final String SAFETY_CHECK_SCHEMA_NAME = "safety_check_response";
    public static final String SAFETY_VERDICT_SCHEMA_NAME = "safety_verdict";

    private static String schemaName(PromptKind kind) {
        return switch (kind) {
            case REFACTOR -> REFACTOR_SCHEMA_NAME;
            case ANALYSIS -> ANALYSIS_SCHEMA_NAME;
            case SAFETY_CHECK -> SAFETY_CHECK_SCHEMA_NAME;
        };
    }

    /**
     * Schema for the refactoring step. Every property is in {@code required}
     * so the model cannot shortcut to a minimal object — it must populate
     * {@code code_blocks} (with at least one entry) and {@code explanation};
     * {@code breaking_changes} and {@code missing_references} may be {@code []}.
     */
    public static JsonObject refactorSchema() {
        JsonObject codeBlockItem = object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "class", stringSchema(),
                "method", stringSchema(),
                "code", stringSchema()
            ),
            "required", array("class", "method", "code")
        );
        return object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "code_blocks", arraySchema(codeBlockItem),
                "explanation", stringSchema(),
                "breaking_changes", arraySchema(stringSchema()),
                "missing_references", arraySchema(stringSchema())
            ),
            "required", array("code_blocks", "explanation", "breaking_changes", "missing_references")
        );
    }

    /** Schema for the dependency-analysis step. */
    public static JsonObject analysisSchema() {
        return object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "dependencies", arraySchema(stringSchema()),
                "impact", stringSchema(),
                "missing_references", arraySchema(stringSchema())
            ),
            "required", array("dependencies", "impact", "missing_references")
        );
    }

    /** Schema for the post-refactor safety-check step. */
    public static JsonObject safetyCheckSchema() {
        return object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "explanation", stringSchema(),
                "breaking_changes", arraySchema(stringSchema())
            ),
            "required", array("explanation", "breaking_changes")
        );
    }

    /**
     * Schema for the SafeRefactorLoop analyzer's verdict. Every field is
     * required so the LLM cannot satisfy the schema with a trivial object —
     * it must commit to a {@code verdict}, a numeric {@code confidence},
     * and populate {@code risks}/{@code needs}/{@code feedback} (empty
     * arrays / empty string are permitted).
     */
    public static JsonObject safetyVerdictSchema() {
        JsonObject riskItem = object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "description", stringSchema(),
                "severity", enumSchema("HIGH", "MEDIUM", "LOW"),
                "mitigation", stringSchema()
            ),
            "required", array("description", "severity", "mitigation")
        );
        JsonObject confidence = new JsonObject();
        confidence.addProperty("type", "number");
        confidence.addProperty("minimum", 0.0);
        confidence.addProperty("maximum", 1.0);
        return object(
            "type", "object",
            "additionalProperties", Boolean.FALSE,
            "properties", object(
                "confidence", confidence,
                "verdict", enumSchema("SAFE", "UNSAFE"),
                "risks", arraySchema(riskItem),
                "needs", arraySchema(stringSchema()),
                "feedback", stringSchema()
            ),
            "required", array("confidence", "verdict", "risks", "needs", "feedback")
        );
    }

    private static JsonObject enumSchema(String... values) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        JsonArray arr = new JsonArray();
        for (String v : values) arr.add(v);
        o.add("enum", arr);
        return o;
    }

    private static JsonObject stringSchema() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject arraySchema(JsonObject itemSchema) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "array");
        o.add("items", itemSchema);
        return o;
    }

    private static JsonObject object(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < kv.length; i += 2) {
            String key = (String) kv[i];
            Object val = kv[i + 1];
            if (val instanceof JsonObject jo) o.add(key, jo);
            else if (val instanceof JsonArray ja) o.add(key, ja);
            else if (val instanceof Boolean b) o.addProperty(key, b);
            else if (val instanceof Number n) o.addProperty(key, n);
            else o.addProperty(key, String.valueOf(val));
        }
        return o;
    }

    private static JsonArray array(String... items) {
        JsonArray arr = new JsonArray();
        for (String s : items) arr.add(s);
        return arr;
    }
}

