package com.smolnij.chunker.refactor;

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
        sb.append("OUTPUT FORMAT:\n");
        sb.append("1. CHANGES: For each modified method, provide the complete updated code in a ```java block\n");
        sb.append("2. EXPLANATION: Brief explanation of what changed and why\n");
        sb.append("3. BREAKING CHANGES: List any potential breaking changes or side effects\n");
        sb.append("4. MISSING: If you need to see additional methods/classes to make a safe change, ");
        sb.append("list them as:\n");
        sb.append("   MISSING: [ClassName.methodName, ClassName2.methodName2]\n");
        sb.append("   If you have everything you need, write: MISSING: []\n");

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
        StringBuilder sb = new StringBuilder();

        sb.append("TASK:\n");
        sb.append("Review the following proposed code changes and list ALL possible breaking changes, ");
        sb.append("regressions, and risks.\n\n");

        sb.append("PROPOSED CHANGES:\n");
        sb.append(proposedChanges).append("\n\n");

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

        sb.append("OUTPUT FORMAT:\n");
        sb.append("List each risk as:\n");
        sb.append("- RISK: <description>\n");
        sb.append("- SEVERITY: HIGH | MEDIUM | LOW\n");
        sb.append("- MITIGATION: <what to do about it>\n");
        sb.append("\nIf there are no risks, write: NO BREAKING CHANGES DETECTED\n");

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

        sb.append("OUTPUT FORMAT:\n");
        sb.append("1. DEPENDENCIES: List all methods/classes that would be affected\n");
        sb.append("2. IMPACT: Describe the ripple effect of the proposed change\n");
        sb.append("3. MISSING: If you need to see additional methods/classes, list them as:\n");
        sb.append("   MISSING: [ClassName.methodName, ClassName2.methodName2]\n");
        sb.append("   If you have everything you need, write: MISSING: []\n");

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
}

