package com.smolnij.chunker.ralph;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.CrossMethodDiff;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.refactor.diff.MethodDiff;
import com.smolnij.chunker.refactor.diff.ScoredDiff;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link RalphTask} implementation for graph-aware code refactoring.
 *
 * <p>Bridges the Ralph Loop into the existing retrieval pipeline by
 * building prompts from {@link RetrievalResult} chunks. The worker acts
 * as a "senior Java engineer" performing the refactoring, and the judge
 * acts as a "strict code reviewer" evaluating correctness, completeness,
 * style, and safety.
 *
 * <h3>Worker prompt structure:</h3>
 * <pre>
 *   TASK: (user's request)
 *   CONTEXT: [Method: Class.method] + code   (×N chunks)
 *   RELATIONSHIPS: (call edges)
 *   [PREVIOUS FEEDBACK: (judge's critique from last round)]
 *   INSTRUCTIONS: (constraints)
 *   OUTPUT FORMAT: (code + explanation)
 * </pre>
 *
 * <h3>Judge prompt structure:</h3>
 * <pre>
 *   TASK: Evaluate the following code refactoring output.
 *   ORIGINAL REQUEST: (user's request)
 *   ORIGINAL CODE: (anchor chunk)
 *   PROPOSED CHANGES: (worker's output)
 *   EVALUATION CRITERIA: (correctness, completeness, style, safety)
 *   OUTPUT FORMAT: VERDICT: PASS/FAIL + SCORE + FEEDBACK
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   List&lt;RetrievalResult&gt; results = retriever.retrieve(query).getResults();
 *   RalphTask task = new RefactorRalphTask(query, results, 6);
 *   RalphResult result = new RalphLoop(chatService, config).run(task);
 * </pre>
 */
public class RefactorRalphTask implements RalphTask {

    private static final String WORKER_SYSTEM =
        "You are a senior Java engineer with deep expertise in refactoring, " +
        "design patterns, and Java concurrency. You analyze code carefully, " +
        "preserve existing behavior, and produce clean, well-documented changes. " +
        "When given feedback from a code reviewer, you address every issue raised.";

    private static final String JUDGE_SYSTEM =
        "You are a strict, experienced code reviewer. You evaluate proposed code " +
        "changes for correctness, completeness, adherence to requirements, code " +
        "style, and potential breaking changes. You are thorough and precise. " +
        "You MUST output a structured verdict.\n\n" +
        "Your output MUST start with exactly one of these lines:\n" +
        "  VERDICT: PASS\n" +
        "  VERDICT: FAIL\n\n" +
        "Followed by:\n" +
        "  SCORE: <0.0 to 1.0>\n" +
        "  FEEDBACK:\n" +
        "  - <issue or comment>\n";

    private final String userQuery;
    private final List<RetrievalResult> results;
    private final int maxChunks;
    private final AstDiffEngine diffEngine;
    private final DiffScorer diffScorer;

    public RefactorRalphTask(String userQuery, List<RetrievalResult> results, int maxChunks,
                             AstDiffEngine diffEngine, DiffScorer diffScorer) {
        this.userQuery = userQuery;
        this.results = results;
        this.maxChunks = maxChunks;
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.diffScorer = Objects.requireNonNull(diffScorer, "diffScorer");
    }

    public RefactorRalphTask(String userQuery, List<RetrievalResult> results, RalphConfig config,
                             AstDiffEngine diffEngine, DiffScorer diffScorer) {
        this(userQuery, results, config.getMaxChunks(), diffEngine, diffScorer);
    }

    @Override
    public String getTaskLabel() {
        return "Refactor: " + userQuery;
    }

    // ═══════════════════════════════════════════════════════════════
    // Worker prompts
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String getWorkerSystemPrompt() {
        return WORKER_SYSTEM;
    }

    @Override
    public String buildWorkerPrompt(String previousAttempt, String judgeFeedback, int iteration) {
        List<RetrievalResult> chunks = results.subList(0, Math.min(results.size(), maxChunks));
        StringBuilder sb = new StringBuilder();

        // ── TASK ──
        sb.append("TASK:\n");
        sb.append(userQuery).append("\n\n");

        // ── PREVIOUS FEEDBACK (if retry) ──
        if (judgeFeedback != null && !judgeFeedback.isEmpty()) {
            sb.append("PREVIOUS ATTEMPT FEEDBACK (iteration ").append(iteration).append("):\n");
            sb.append("A code reviewer found the following issues with your previous attempt. ");
            sb.append("You MUST address ALL of these issues:\n\n");
            sb.append(judgeFeedback).append("\n\n");

            String astDiffReport = computeAstDiffReport(previousAttempt);
            if (!astDiffReport.isEmpty()) {
                sb.append("── DETERMINISTIC AST DIFF (from JavaParser) ──────────────\n");
                sb.append("These are structural facts about your previous code. ");
                sb.append("Treat them as ground truth when revising:\n\n");
                sb.append(astDiffReport).append("\n");
            }

            sb.append("YOUR PREVIOUS OUTPUT (for reference):\n");
            sb.append(previousAttempt != null ? truncate(previousAttempt, 2000) : "(none)");
            sb.append("\n\n");
        }

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
            sb.append("```java\n");
            sb.append(c.getCode()).append("\n");
            sb.append("```\n\n");
        }

        // ── RELATIONSHIPS ──
        sb.append("RELATIONSHIPS:\n");
        boolean hasRels = false;
        for (RetrievalResult r : chunks) {
            CodeChunk c = r.getChunk();
            String from = c.getClassName() + "." + c.getMethodName();
            for (String target : c.getCalls()) {
                sb.append("- ").append(from).append(" calls ").append(shortName(target)).append("\n");
                hasRels = true;
            }
            for (String caller : c.getCalledBy()) {
                sb.append("- ").append(from).append(" called by ").append(shortName(caller)).append("\n");
                hasRels = true;
            }
        }
        if (!hasRels) {
            sb.append("- (no direct relationships found)\n");
        }
        sb.append("\n");

        // ── INSTRUCTIONS ──
        sb.append("INSTRUCTIONS:\n");
        sb.append("- Keep behavior identical unless the task explicitly asks to change it\n");
        sb.append("- Preserve all existing validation and error handling\n");
        sb.append("- Follow existing code style and naming conventions\n");
        if (iteration > 0) {
            sb.append("- Address ALL issues from the reviewer feedback above\n");
            sb.append("- Do not introduce new issues while fixing old ones\n");
        }
        sb.append("\n");

        // ── OUTPUT FORMAT ──
        sb.append("OUTPUT FORMAT:\n");
        sb.append("1. CHANGES: For each modified method, provide the complete updated code in a ```java block\n");
        sb.append("2. EXPLANATION: Brief explanation of what changed and why\n");
        sb.append("3. BREAKING CHANGES: List any potential breaking changes or side effects\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Judge prompts
    // ═══════════════════════════════════════════════════════════════

    @Override
    public String getJudgeSystemPrompt() {
        return JUDGE_SYSTEM;
    }

    @Override
    public String buildJudgePrompt(String workerOutput, int iteration) {
        List<RetrievalResult> chunks = results.subList(0, Math.min(results.size(), maxChunks));
        StringBuilder sb = new StringBuilder();

        sb.append("TASK: Evaluate the following code refactoring output.\n\n");

        sb.append("ORIGINAL REQUEST:\n");
        sb.append(userQuery).append("\n\n");

        // ── Original code (anchor and key chunks) ──
        sb.append("ORIGINAL CODE:\n");
        int shown = 0;
        for (RetrievalResult r : chunks) {
            if (shown >= 3) break; // only show top 3 for judge context
            CodeChunk c = r.getChunk();
            sb.append("[").append(c.getClassName()).append(".").append(c.getMethodName());
            if (r.isAnchor()) sb.append(" (TARGET)");
            sb.append("]\n");
            sb.append("```java\n").append(c.getCode()).append("\n```\n\n");
            shown++;
        }

        // ── Worker's proposed changes ──
        sb.append("PROPOSED CHANGES (from worker, iteration ").append(iteration + 1).append("):\n");
        sb.append(workerOutput).append("\n\n");

        // ── Deterministic AST diff ──
        String astDiffReport = computeAstDiffReport(workerOutput);
        if (!astDiffReport.isEmpty()) {
            sb.append("── DETERMINISTIC AST ANALYSIS (from JavaParser) ──────────\n");
            sb.append("The following is a deterministic structural diff of the worker's code\n");
            sb.append("against the originals in the graph. Use these facts as ground truth —\n");
            sb.append("you MUST call out anything flagged here in your verdict:\n\n");
            sb.append(astDiffReport).append("\n");
        }

        // ── Evaluation criteria ──
        sb.append("EVALUATION CRITERIA:\n");
        sb.append("1. CORRECTNESS: Does the refactored code compile? Is the logic correct?\n");
        sb.append("2. COMPLETENESS: Does it fully address the original request?\n");
        sb.append("3. BEHAVIOR PRESERVATION: Is existing behavior preserved where required?\n");
        sb.append("4. CODE QUALITY: Is the code clean, well-structured, and idiomatic Java?\n");
        sb.append("5. SAFETY: Are there unhandled breaking changes or missing error handling?\n\n");

        // ── Required output format ──
        sb.append("OUTPUT FORMAT (you MUST follow this exactly):\n");
        sb.append("VERDICT: PASS    (if all criteria are met)\n");
        sb.append("VERDICT: FAIL    (if any criterion is not met)\n\n");
        sb.append("SCORE: <0.0 to 1.0>  (overall quality score)\n\n");
        sb.append("FEEDBACK:\n");
        sb.append("- <specific issue or positive observation>\n");
        sb.append("- <another issue or observation>\n\n");
        sb.append("Be specific. If you write FAIL, your feedback MUST explain exactly what ");
        sb.append("needs to be fixed so the worker can correct it on the next attempt.\n");

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // AST diff (deterministic structural analysis)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the AST diff engine on a worker response and format a report for
     * the judge and for the worker's retry prompt. Returns an empty string
     * when there is nothing structurally noteworthy.
     */
    private String computeAstDiffReport(String response) {
        if (response == null || response.isBlank()) return "";
        try {
            List<CodeChunk> originals = new ArrayList<>();
            for (RetrievalResult r : results) {
                originals.add(r.getChunk());
            }
            CrossMethodDiff crossDiff = diffEngine.analyze(originals, response);
            if (crossDiff.isEmpty()) return "";

            List<ScoredDiff> scoredDiffs = new ArrayList<>();
            for (MethodDiff md : crossDiff.getMethodDiffs()) {
                scoredDiffs.add(diffScorer.score(md));
            }

            StringBuilder sb = new StringBuilder();
            if (!scoredDiffs.isEmpty()) {
                sb.append("AST DIFF ANALYSIS (deterministic, ").append(scoredDiffs.size()).append(" method(s)):\n\n");
                for (ScoredDiff sd : scoredDiffs) {
                    sb.append(sd.toDisplayString());
                }
            }
            String crossDisplay = crossDiff.toDisplayString();
            if (!crossDisplay.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(crossDisplay);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private String shortName(String fqRef) {
        if (fqRef == null || fqRef.isEmpty()) return fqRef;
        int hashIdx = fqRef.indexOf('#');
        if (hashIdx >= 0) {
            String classPart = fqRef.substring(0, hashIdx);
            String methodPart = fqRef.substring(hashIdx + 1);
            int parenIdx = methodPart.indexOf('(');
            if (parenIdx >= 0) methodPart = methodPart.substring(0, parenIdx);
            int lastDot = classPart.lastIndexOf('.');
            String simpleName = lastDot >= 0 ? classPart.substring(lastDot + 1) : classPart;
            return simpleName + "." + methodPart;
        }
        int lastDot = fqRef.lastIndexOf('.');
        return lastDot >= 0 ? fqRef.substring(lastDot + 1) : fqRef;
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... (truncated, " + text.length() + " chars total)";
    }
}

