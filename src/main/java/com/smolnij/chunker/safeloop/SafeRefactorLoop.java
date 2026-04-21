package com.smolnij.chunker.safeloop;

import com.google.gson.JsonObject;
import com.smolnij.chunker.apply.ApplyResult;
import com.smolnij.chunker.apply.ApplyTools;
import com.smolnij.chunker.apply.PatchApplier;
import com.smolnij.chunker.apply.PatchPlan;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LlmResponseParser;
import com.smolnij.chunker.refactor.PromptBuilder;
import com.smolnij.chunker.refactor.RefactorAgent;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.refactor.StructuredOutputSpec;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.CrossMethodDiff;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.refactor.diff.MethodDiff;
import com.smolnij.chunker.refactor.diff.ScoredDiff;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Self-improving refactoring loop that keeps querying the graph until
 * the change is safe.
 *
 * <p>Combines the agentic tool-calling of {@link RefactorAgent} (the LLM
 * autonomously retrieves code via LangChain4j) with a structured
 * confidence-gated safety loop powered by a separate analyzer LLM.
 *
 * <h3>Architecture:</h3>
 * <pre>
 *          User query
 *              │
 *              ▼
 *   ┌──────────────────────┐
 *   │  Phase 1: PLANNING   │ ← Agent identifies target, initial retrieval
 *   └──────────┬───────────┘
 *              │
 *              ▼
 *   ┌──────────────────────┐
 *   │  Phase 2: GRAPH      │ ← SafeLoopTools ensures caller/callee coverage
 *   │          PRE-FETCH   │
 *   └──────────┬───────────┘
 *              │
 *   ┌──────────┴───────────────────────────────────────────┐
 *   │  LOOP (max N iterations)                              │
 *   │  ┌──────────────────────┐                             │
 *   │  │  Phase 3: REFACTOR   │ ← Agent produces code       │
 *   │  └──────────┬───────────┘                             │
 *   │             │                                         │
 *   │             ▼                                         │
 *   │  ┌──────────────────────┐                             │
 *   │  │  Phase 4: VALIDATE   │ ← Analyzer checks safety    │
 *   │  └──────────┬───────────┘                             │
 *   │             │                                         │
 *   │             ▼                                         │
 *   │  ┌──────────────────────┐                             │
 *   │  │  Phase 5: DECIDE     │                             │
 *   │  │  SAFE → return       │                             │
 *   │  │  UNSAFE → expand     │ → fetch missing → loop      │
 *   │  │  CONVERGED → return  │                             │
 *   │  │  STAGNANT → return   │                             │
 *   │  └──────────────────────┘                             │
 *   └───────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Key design decisions:</h3>
 * <ul>
 *   <li>The refactoring agent uses LangChain4j with tool calling — the LLM
 *       decides what context it needs autonomously</li>
 *   <li>The analyzer is a separate ChatService call (no tools) — it only
 *       evaluates the proposal and declares confidence + risks</li>
 *   <li>Agent memory is preserved across iterations (cumulative reasoning)</li>
 *   <li>Convergence is detected when graph expansion yields no new nodes</li>
 *   <li>Stagnation is detected when the analyzer returns the same risks twice</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   SafeLoopTools loopTools = new SafeLoopTools(retriever, graphReader, config);
 *   RefactorTools agentTools = new RefactorTools(retriever, graphReader, config.getMaxChunks());
 *   RefactorAgent agent = new RefactorAgent(refactorConfig, agentTools);
 *   ChatService analyzerChat = new LmStudioChatService(...);
 *
 *   SafeRefactorLoop loop = new SafeRefactorLoop(agent, analyzerChat, loopTools, agentTools, config);
 *   SafeLoopResult result = loop.run("Refactor createUser to async");
 * </pre>
 */
public class SafeRefactorLoop {

    // ═══════════════════════════════════════════════════════════════
    // Analyzer system prompt
    // ═══════════════════════════════════════════════════════════════

    private static final String ANALYZER_SYSTEM_PROMPT = """
        You are a strict static-analysis expert and safety reviewer.
        You evaluate proposed code refactorings for correctness, safety, and completeness.

        You must check for:
        1. Broken method signatures (callers that would break)
        2. Missing dependencies or imports
        3. Threading / concurrency issues
        4. Unhandled side effects
        5. Behavioral changes that weren't requested
        6. Error handling gaps

        Reply ONLY with a single JSON object matching this shape (no prose outside the JSON,
        no markdown fences):
        {
          "confidence": <number in 0.0..1.0 — your actual certainty>,
          "verdict": "SAFE" | "UNSAFE",
          "risks": [
            {"description": "<what could break>",
             "severity": "HIGH" | "MEDIUM" | "LOW",
             "mitigation": "<what to do about it>"}
          ],
          "needs": ["<ClassName#methodName you need to see to be more confident>"],
          "feedback": "<general assessment of the refactoring quality>"
        }

        Rules:
        - Be conservative — default to verdict "UNSAFE" if uncertain.
        - confidence must reflect your actual certainty, not optimism.
        - If you don't have enough context to judge, set confidence low and list items in needs.
        - Each risk entry must include description, severity, and mitigation.
        - Use [] for empty risks/needs; use "" for empty feedback.
        """;

    /** Name used for the analyzer JSON schema / forced tool call. */
    private static final String ANALYZER_SCHEMA_NAME = PromptBuilder.SAFETY_VERDICT_SCHEMA_NAME;

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final RefactorAgent agent;
    private final ChatService analyzerChat;
    private final SafeLoopTools loopTools;
    private final RefactorTools agentTools;
    private final SafeLoopConfig config;

    /** AST diff engine for structural comparison (required). */
    private final AstDiffEngine diffEngine;

    /** Graph-aware diff scorer (required). */
    private final DiffScorer diffScorer;

    /** Callback for streaming progress updates. */
    private Consumer<String> progressCallback;

    /**
     * Construct with AST diff support for structural validation in Phase 4.
     * Both {@code diffEngine} and {@code diffScorer} are required — AST diffing
     * is the cheapest empirical correctness signal and runs locally.
     */
    public SafeRefactorLoop(RefactorAgent agent,
                            ChatService analyzerChat,
                            SafeLoopTools loopTools,
                            RefactorTools agentTools,
                            SafeLoopConfig config,
                            AstDiffEngine diffEngine,
                            DiffScorer diffScorer) {
        this.agent = agent;
        this.analyzerChat = analyzerChat;
        this.loopTools = loopTools;
        this.agentTools = agentTools;
        this.config = config;
        this.diffEngine = Objects.requireNonNull(diffEngine, "diffEngine");
        this.diffScorer = Objects.requireNonNull(diffScorer, "diffScorer");
        this.progressCallback = System.out::print;
    }

    public void setProgressCallback(Consumer<String> callback) {
        this.progressCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the full self-improving safe refactoring loop.
     *
     * @param userQuery the natural-language refactoring request
     * @return the complete result including safety verdict and diagnostics
     */
    public SafeLoopResult run(String userQuery) {
        printBanner();
        System.out.println("Query: " + userQuery);
        System.out.println("Config: " + config);
        System.out.println();

        List<SafetyVerdict> verdictHistory = new ArrayList<>();
        String lastAgentResponse = "";
        String lastAstDiffReport = "";
        List<ScoredDiff> lastScoredDiffs = List.of();
        CrossMethodDiff lastCrossDiff = CrossMethodDiff.empty();
        double lastAstSafetyScore = 1.0;
        SafeLoopResult.TerminalReason terminalReason = SafeLoopResult.TerminalReason.MAX_ITERATIONS;

        try {
            // ══════════════════════════════════════════════════════
            // Phase 1: PLANNING — Initial retrieval + target identification
            // ══════════════════════════════════════════════════════
            System.out.println("━━━ Phase 1: Planning & Initial Retrieval ━━━━━━━━━━━");

            List<RetrievalResult> initialResults = loopTools.initialRetrieve(userQuery);
            System.out.println("  Retrieved " + initialResults.size() + " initial chunks");

            String anchorId = loopTools.findAnchorId(initialResults);
            System.out.println("  Anchor method: " + (anchorId != null ? anchorId : "(none)"));
            System.out.println();

            // ══════════════════════════════════════════════════════
            // Phase 2: GRAPH PRE-FETCH — Ensure minimum coverage
            // ══════════════════════════════════════════════════════
            System.out.println("━━━ Phase 2: Graph Coverage Enforcement ━━━━━━━━━━━━━");

            String preContext = "";
            if (anchorId != null) {
                preContext = loopTools.ensureGraphCoverage(anchorId);
                if (!preContext.isEmpty()) {
                    System.out.println("  Pre-fetched " + loopTools.getLastExpansionChunks().size()
                        + " additional methods for coverage");
                } else {
                    System.out.println("  Graph coverage already satisfied");
                }
            } else {
                System.out.println("  ⚠ No anchor method found, skipping coverage enforcement");
            }
            System.out.println("  Total nodes in context: " + loopTools.getTotalNodesRetrieved());
            System.out.println();

            // ══════════════════════════════════════════════════════
            // Phase 3→5: REFACTOR → VALIDATE → DECIDE (iterative)
            // ══════════════════════════════════════════════════════

            for (int iteration = 0; iteration < config.getMaxIterations(); iteration++) {
                String iterLabel = "Iteration " + (iteration + 1) + "/" + config.getMaxIterations();

                // ── Phase 3: REFACTOR ──
                System.out.println("━━━ " + iterLabel + ": Phase 3 — Refactoring ━━━━━━━━━━━");

                agentTools.resetToolCallCount();

                if (iteration == 0) {
                    // First iteration: send the original query + pre-fetched context
                    String enhancedQuery = userQuery;
                    if (!preContext.isEmpty()) {
                        enhancedQuery = userQuery + "\n\nHere is additional context from the code graph:\n\n"
                            + preContext;
                    }
                    lastAgentResponse = agent.getAssistant().chat(enhancedQuery);
                } else {
                    // Subsequent iterations: ask the agent to refine based on safety feedback
                    SafetyVerdict lastVerdict = verdictHistory.get(verdictHistory.size() - 1);
                    String refinementPrompt = buildRefinementPrompt(
                            lastVerdict, iteration, lastAstDiffReport,
                            lastScoredDiffs, lastCrossDiff, lastAstSafetyScore);
                    lastAgentResponse = agent.getAssistant().chat(refinementPrompt);
                }

                System.out.println("  Agent responded (" + agentTools.getToolCallCount() + " tool calls)");
                System.out.println("  ┌─ Refactoring Proposal ────────────────────────────");
                System.out.println("  │ " + lastAgentResponse.replace("\n", "\n  │ "));
                System.out.println("  └───────────────────────────────────────────────────");
                System.out.println();

                // ── Phase 4: VALIDATE ──
                System.out.println("━━━ " + iterLabel + ": Phase 4 — Safety Validation ━━━━━");

                // Phase 4a: AST diff scoring (deterministic)
                System.out.println("  ┌─ AST Diff ────────────────────────────────────────");
                CrossMethodDiff crossDiff = analyzeCrossMethod(lastAgentResponse);
                List<ScoredDiff> scoredDiffs = new ArrayList<>();
                double astSafetyScore = 1.0;
                for (MethodDiff md : crossDiff.getMethodDiffs()) {
                    ScoredDiff sd = diffScorer.score(md);
                    scoredDiffs.add(sd);
                    astSafetyScore = Math.min(astSafetyScore, sd.getSafetyScore());
                    System.out.println("  │ " + sd.getDiff().getChunkId()
                        + ": score=" + String.format("%.2f", sd.getSafetyScore())
                        + " callers=" + sd.getAffectedCallerCount());
                }
                if (scoredDiffs.isEmpty() && crossDiff.isEmpty()) {
                    System.out.println("  │ (no code blocks matched to graph methods)");
                }
                if (crossDiff.hasInvariantViolations()) {
                    astSafetyScore = Math.min(astSafetyScore, 0.3);
                    System.out.println("  │ ⛔ Cross-method violations: "
                        + crossDiff);
                }

                StringBuilder diffSb = new StringBuilder();
                if (!scoredDiffs.isEmpty()) {
                    diffSb.append("AST DIFF ANALYSIS (deterministic, ").append(scoredDiffs.size()).append(" methods):\n\n");
                    for (ScoredDiff sd : scoredDiffs) {
                        diffSb.append(sd.toDisplayString());
                    }
                }
                String crossDisplay = crossDiff.toDisplayString();
                if (!crossDisplay.isEmpty()) {
                    if (diffSb.length() > 0) diffSb.append("\n");
                    diffSb.append(crossDisplay);
                }
                String astDiffReport = diffSb.toString();

                System.out.println("  │ Worst-case AST safety: " + String.format("%.2f", astSafetyScore));
                System.out.println("  └───────────────────────────────────────────────────");

                // Store for next iteration's refinement prompt
                lastAstDiffReport = astDiffReport;
                lastScoredDiffs = scoredDiffs;
                lastCrossDiff = crossDiff;
                lastAstSafetyScore = astSafetyScore;

                // Phase 4b: LLM analyzer evaluation (with AST diff injected)
                String analyzerPrompt = buildAnalyzerPrompt(userQuery, lastAgentResponse, iteration, astDiffReport);
                System.out.println("  ┌─ Analyzer ─────────────────────────────────────");
                StructuredOutputSpec analyzerSpec = analyzerSpec(config.getStructuredOutput());
                String analyzerResponse = analyzerSpec != null
                    ? analyzerChat.chat(ANALYZER_SYSTEM_PROMPT, analyzerPrompt, analyzerSpec)
                    : analyzerChat.chat(ANALYZER_SYSTEM_PROMPT, analyzerPrompt);
                System.out.println("  │ " + truncateForLog(analyzerResponse, 300).replace("\n", "\n  │ "));
                System.out.println("  └───────────────────────────────────────────────────");

                SafetyVerdict verdict = SafetyVerdict.parse(analyzerResponse);
                if (analyzerSpec != null) {
                    System.out.println();
                    if (verdict.isParsedFromJson()) {
                        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
                        System.out.println("  ║  ✓  STRUCTURED OUTPUT ACTIVE                            ║");
                        System.out.println("  ╠══════════════════════════════════════════════════════════╣");
                        System.out.println("  ║  [Analyzer] Response parsed from JSON successfully (" + analyzerSpec.preferredMode() + ").");
                        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
                    } else {
                        String _prev = truncateForLog(analyzerResponse, 200).replace("\n", " ");
                        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
                        System.out.println("  ║  ⚠  WARNING: STRUCTURED OUTPUT FALLBACK                 ║");
                        System.out.println("  ╠══════════════════════════════════════════════════════════╣");
                        System.out.println("  ║  [Analyzer] LLM ignored response_format — using regex fallback.");
                        System.out.println("  ║  Results may be incomplete or misparse. Check model support.");
                        System.out.println("  ║  Preview: " + _prev);
                        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
                    }
                    System.out.println();
                }
                verdictHistory.add(verdict);

                System.out.println();
                System.out.println("  → " + verdict);

                // ── Phase 5: DECIDE ──
                System.out.println("━━━ " + iterLabel + ": Phase 5 — Decision ━━━━━━━━━━━━━");

                // Check: SAFE?
                if (verdict.isSafe(config.getSafetyThreshold())) {
                    System.out.println("  → ✓ SAFE — confidence " + String.format("%.2f", verdict.getConfidence())
                        + " ≥ threshold " + String.format("%.2f", config.getSafetyThreshold()));
                    System.out.println();
                    terminalReason = SafeLoopResult.TerminalReason.SAFE;
                    break;
                }

                // Check: Stagnation?
                if (config.isStopOnStagnation() && verdictHistory.size() >= 2) {
                    SafetyVerdict prev = verdictHistory.get(verdictHistory.size() - 2);
                    if (verdict.hasSameRisks(prev)) {
                        System.out.println("  → ✗ STAGNANT — same risks as previous iteration, stopping");
                        System.out.println();
                        terminalReason = SafeLoopResult.TerminalReason.STAGNANT;
                        break;
                    }
                }

                // Check: More context needed?
                if (verdict.needsMoreContext()) {
                    System.out.println("  → Analyzer needs more context: " + verdict.getMissingContext());
                    String newContext = loopTools.expandForAnalyzer(verdict.getMissingContext());

                    if (newContext.isEmpty() || !loopTools.hasNewNodes()) {
                        if (config.isStopOnNoNewNodes()) {
                            System.out.println("  → ✗ CONVERGED — no new graph nodes available, stopping");
                            System.out.println();
                            terminalReason = SafeLoopResult.TerminalReason.CONVERGED;
                            break;
                        }
                        System.out.println("  → ⚠ No new nodes, but continuing (stopOnNoNewNodes=false)");
                    } else {
                        System.out.println("  → Expanded graph: +" + loopTools.getLastExpansionChunks().size()
                            + " new methods (total: " + loopTools.getTotalNodesRetrieved() + ")");

                        // Inject new context into agent memory
                        agent.getAssistant().chat(
                            "Here is additional context retrieved from the code graph. "
                            + "Use this in your next refinement:\n\n" + newContext
                        );
                    }
                } else {
                    System.out.println("  → ✗ UNSAFE (confidence " + String.format("%.2f", verdict.getConfidence())
                        + ") but analyzer doesn't need more context");
                }

                System.out.println("  → Looping for refinement...");
                System.out.println();
            }

            // ── Build final result ──
            SafetyVerdict finalVerdict = verdictHistory.isEmpty() ? null : verdictHistory.get(verdictHistory.size() - 1);
            boolean isSafe = finalVerdict != null && finalVerdict.isSafe(config.getSafetyThreshold());

            SafeLoopResult result = new SafeLoopResult(
                userQuery,
                lastAgentResponse,   // The full agent response includes code blocks
                extractExplanation(lastAgentResponse),
                isSafe,
                finalVerdict != null ? finalVerdict.getConfidence() : 0.0,
                terminalReason,
                verdictHistory.size(),
                verdictHistory,
                agentTools.getToolCallCount(),
                loopTools.getTotalNodesRetrieved(),
                finalVerdict != null ? finalVerdict.getRisks() : List.of(),
                lastAgentResponse
            );

            // ══════════════════════════════════════════════════════
            // Phase 6: APPLY (optional) — write SAFE changes to disk
            // ══════════════════════════════════════════════════════
            ApplyTools agentApply = agent.getApplyTools();
            ApplyResult toolResult = agentApply == null ? null : agentApply.getLastResult();
            if (toolResult != null) {
                System.out.println("━━━ Phase 6: Apply ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("  → Agent applied via CHANGES tools (analyzer-gated).");
                System.out.println(toolResult.toReport().replace("\n", "\n  "));
                System.out.println();
                result.withApplyResult(toolResult.getChangedFiles(), toolResult.toReport());
            } else if (config.isApply() && isSafe) {
                applyPatch(result, lastAgentResponse);
            } else if (config.isApply()) {
                System.out.println("━━━ Phase 6: Apply ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                System.out.println("  → Skipped — verdict not SAFE (" + terminalReason + ")");
                System.out.println();
            }

            return result;

        } catch (Exception e) {
            System.err.println("  ✗ ERROR: " + e.getMessage());
            e.printStackTrace();

            return new SafeLoopResult(
                userQuery, "", "Error: " + e.getMessage(),
                false, 0.0,
                SafeLoopResult.TerminalReason.ERROR,
                verdictHistory.size(), verdictHistory,
                agentTools.getToolCallCount(), loopTools.getTotalNodesRetrieved(),
                List.of(), lastAgentResponse
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Prompt builders
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the prompt for the analyzer LLM to evaluate a refactoring proposal.
     *
     * @param originalQuery the user's refactoring request
     * @param agentResponse the agent's proposed refactoring
     * @param iteration     current iteration index (0-based)
     * @param astDiffReport AST diff analysis report (empty string if not available)
     */
    private String buildAnalyzerPrompt(String originalQuery, String agentResponse,
                                       int iteration, String astDiffReport) {
        StringBuilder sb = new StringBuilder();

        sb.append("TASK: Evaluate the safety of this proposed refactoring.\n\n");

        sb.append("ORIGINAL REQUEST:\n");
        sb.append(originalQuery).append("\n\n");

        sb.append("PROPOSED REFACTORING (iteration ").append(iteration + 1).append("):\n");
        sb.append(agentResponse).append("\n\n");

        sb.append("GRAPH COVERAGE:\n");
        sb.append("Total methods retrieved: ").append(loopTools.getTotalNodesRetrieved()).append("\n");
        sb.append("Graph expansions performed: ").append(loopTools.getExpansionCount()).append("\n\n");

        // Inject AST diff results (deterministic structural analysis)
        if (astDiffReport != null && !astDiffReport.isEmpty()) {
            sb.append("── DETERMINISTIC AST ANALYSIS (from JavaParser) ──────────\n");
            sb.append("The following is a deterministic structural diff computed by parsing\n");
            sb.append("the proposed code and comparing it against the original AST.\n");
            sb.append("Use these facts to inform your safety assessment:\n\n");
            sb.append(astDiffReport).append("\n");
        }

        sb.append("Evaluate this refactoring against ALL safety criteria.\n");
        sb.append("Be thorough — check for broken signatures, missing deps, threading, and side effects.\n");
        sb.append("If you need to see specific methods to increase confidence, list them in NEEDS.\n");

        return sb.toString();
    }

    /**
     * Build a refinement prompt that feeds the analyzer's safety feedback
     * and deterministic AST diff results back to the refactoring agent.
     *
     * <p>When AST diff data is available, a concise actionable summary is
     * generated first (e.g. "Your previous proposal had a safety score of
     * 0.35 because you changed the return type, affecting 4 callers."),
     * followed by the full structural report. This gives the agent both
     * an immediate understanding of what went wrong and the detailed
     * evidence to fix it.
     *
     * @param verdict         the analyzer's safety verdict from the previous iteration
     * @param iteration       current iteration index (0-based)
     * @param astDiffReport   AST diff analysis from Phase 4a (empty if unavailable)
     * @param scoredDiffs     structured AST diff results for actionable summary
     * @param astSafetyScore  worst-case AST safety score across all diffs (1.0 if none)
     */
    private String buildRefinementPrompt(SafetyVerdict verdict, int iteration,
                                         String astDiffReport,
                                         List<ScoredDiff> scoredDiffs,
                                         CrossMethodDiff crossDiff,
                                         double astSafetyScore) {
        StringBuilder sb = new StringBuilder();

        sb.append("Your previous refactoring was evaluated by a safety analyzer.\n\n");

        sb.append("SAFETY RESULT: ").append(verdict.isVerdictSafe() ? "SAFE" : "UNSAFE").append("\n");
        sb.append("CONFIDENCE: ").append(String.format("%.2f", verdict.getConfidence())).append("\n");

        // Show the deterministic AST safety score alongside the LLM confidence
        if (!scoredDiffs.isEmpty() || (crossDiff != null && crossDiff.hasInvariantViolations())) {
            sb.append("AST SAFETY SCORE: ").append(String.format("%.2f", astSafetyScore)).append(" / 1.00\n");
        }
        sb.append("\n");

        if (crossDiff != null && crossDiff.hasInvariantViolations()) {
            sb.append("── CROSS-METHOD INVARIANT VIOLATIONS (deterministic) ──\n");
            sb.append(buildCrossMethodSummary(crossDiff));
            sb.append("\n");
        }

        // Inject actionable summary derived from structured AST diff data
        if (!scoredDiffs.isEmpty()) {
            sb.append("── STRUCTURAL ISSUES (deterministic, from JavaParser AST diff) ──\n\n");
            sb.append(buildAstDiffSummary(scoredDiffs, astSafetyScore));
            sb.append("\n");

            // Append the full detailed report as supporting evidence
            if (astDiffReport != null && !astDiffReport.isEmpty()) {
                sb.append("── FULL AST DIFF DETAILS ──\n");
                sb.append(astDiffReport).append("\n");
            }
        } else if (astDiffReport != null && !astDiffReport.isEmpty()) {
            // Fallback: no structured diffs but we have a text report
            sb.append("── STRUCTURAL ANALYSIS (deterministic, from JavaParser AST diff) ──\n");
            sb.append("The following is a factual structural diff of your proposed code\n");
            sb.append("compared against the original method in the codebase:\n\n");
            sb.append(astDiffReport).append("\n");
            sb.append("Use these facts to guide your revision. If the safety score is low,\n");
            sb.append("consider preserving the original method signature and adding an overload.\n\n");
        }

        if (!verdict.getRisks().isEmpty()) {
            sb.append("RISKS IDENTIFIED:\n");
            for (SafetyVerdict.Risk risk : verdict.getRisks()) {
                sb.append("- [").append(risk.getSeverity()).append("] ").append(risk.getDescription());
                if (!risk.getMitigation().isEmpty()) {
                    sb.append(" → ").append(risk.getMitigation());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!verdict.getFeedback().isEmpty()) {
            sb.append("ANALYZER FEEDBACK:\n");
            sb.append(verdict.getFeedback()).append("\n\n");
        }

        sb.append("INSTRUCTIONS:\n");
        sb.append("1. Address ALL risks listed above\n");
        sb.append("2. Use your retrieval tools if you need more context to fix the issues\n");
        sb.append("3. Produce a refined refactoring that resolves these safety concerns\n");
        sb.append("4. Do not introduce new issues while fixing old ones\n");
        sb.append("5. This is refinement iteration ").append(iteration + 1)
            .append(" — previous attempts were not safe enough\n");

        return sb.toString();
    }

    /**
     * Build a concise, actionable natural-language summary from structured
     * AST diff results. This tells the refactoring agent exactly what went
     * wrong and why, deterministically.
     *
     * <p>Example output:
     * <pre>
     *   Your previous proposal had a safety score of 0.35 because:
     *   • You changed the return type of `createUser` from void to CompletableFuture,
     *     affecting 4 callers: [UserService#register, AdminService#bulkCreate, ...]
     *   • You narrowed the visibility of `validate` from public to private
     *
     *   To fix this, preserve the original method signature and add an overload,
     *   or update all affected callers in your proposal.
     * </pre>
     *
     * @param scoredDiffs    the list of scored AST diffs from Phase 4a
     * @param worstScore     the worst-case safety score across all diffs
     * @return a concise summary string for LLM consumption
     */
    /**
     * Build a concise natural-language summary of cross-method invariant
     * violations detected by {@link AstDiffEngine#analyze}.
     */
    private static String buildCrossMethodSummary(CrossMethodDiff crossDiff) {
        StringBuilder sb = new StringBuilder();

        List<CrossMethodDiff.DeletedMember> externalDeletes = new ArrayList<>();
        for (CrossMethodDiff.DeletedMember d : crossDiff.getDeletedMethods()) {
            if (d.isExternallyVisible()) externalDeletes.add(d);
        }
        if (!externalDeletes.isEmpty()) {
            sb.append("  • You DELETED public/protected methods that the original class exposed. ");
            sb.append("Callers that referenced them will fail to compile:\n");
            for (CrossMethodDiff.DeletedMember d : externalDeletes) {
                sb.append("      - ").append(d).append("\n");
            }
            sb.append("    Restore these methods or keep shim implementations.\n");
        }

        if (!crossDiff.getAddedPublicMembers().isEmpty()) {
            sb.append("  • You added new public/protected methods that the task did not ask for. ");
            sb.append("Either scope them private or remove them unless they are strictly required:\n");
            for (CrossMethodDiff.AddedMember a : crossDiff.getAddedPublicMembers()) {
                sb.append("      + ").append(a).append("\n");
            }
        }

        if (!crossDiff.getRemovedOverrides().isEmpty()) {
            sb.append("  • You removed @Override from methods that had it originally. ");
            sb.append("This breaks polymorphism checks and is almost always a mistake:\n");
            for (String name : crossDiff.getRemovedOverrides()) {
                sb.append("      - ").append(name).append("\n");
            }
        }

        List<MethodDiff> pubBreaks = crossDiff.getPublicSignatureBreaks();
        if (!pubBreaks.isEmpty()) {
            sb.append("  • You changed the signature of originally public/protected members — ");
            sb.append("each one is a compile-time break for every caller:\n");
            for (MethodDiff d : pubBreaks) {
                sb.append("      - ").append(d.getChunkId()).append("\n");
            }
        }

        return sb.toString();
    }

    private static String buildAstDiffSummary(List<ScoredDiff> scoredDiffs, double worstScore) {
        StringBuilder sb = new StringBuilder();

        sb.append("Your previous proposal had a safety score of ")
            .append(String.format("%.2f", worstScore))
            .append(" because:\n");

        boolean hasSignatureIssue = false;
        boolean hasCallerImpact = false;

        for (ScoredDiff sd : scoredDiffs) {
            MethodDiff diff = sd.getDiff();
            String methodId = diff.getChunkId();

            if (diff.isParseError()) {
                sb.append("  • Your proposed code for `").append(methodId)
                    .append("` does not parse: ").append(diff.getParseErrorDetail()).append("\n");
                continue;
            }

            if (sd.isCompletelySafe()) {
                continue; // skip safe diffs from the summary
            }

            List<String> issues = new ArrayList<>();

            if (diff.isReturnTypeChanged()) {
                issues.add("changed the return type from `" + diff.getOldReturnType()
                    + "` to `" + diff.getNewReturnType() + "`");
                hasSignatureIssue = true;
            }
            if (diff.isParamsChanged()) {
                issues.add("changed the parameters from `(" + diff.getOldParams()
                    + ")` to `(" + diff.getNewParams() + ")`");
                hasSignatureIssue = true;
            }
            if (diff.isVisibilityChanged()) {
                issues.add("changed the visibility from `" + diff.getOldVisibility()
                    + "` to `" + diff.getNewVisibility() + "`");
                hasSignatureIssue = true;
            }
            if (diff.isThrowsChanged()) {
                issues.add("changed the throws clause from `" + diff.getOldThrows()
                    + "` to `" + diff.getNewThrows() + "`");
                hasSignatureIssue = true;
            }
            if (!diff.getRemovedCalls().isEmpty()) {
                issues.add("removed " + diff.getRemovedCalls().size()
                    + " method call(s): " + diff.getRemovedCalls());
            }
            if (!diff.getAddedCalls().isEmpty()) {
                issues.add("added " + diff.getAddedCalls().size()
                    + " new method call(s): " + diff.getAddedCalls());
            }
            if (!diff.getRemovedAnnotations().isEmpty()) {
                issues.add("removed annotations: " + diff.getRemovedAnnotations());
            }
            if (!diff.getAddedAnnotations().isEmpty()) {
                issues.add("added annotations: " + diff.getAddedAnnotations());
            }

            if (issues.isEmpty()) continue;

            sb.append("  • In `").append(methodId).append("`: you ");
            sb.append(String.join("; ", issues));

            // Append caller impact
            if (sd.getAffectedCallerCount() > 0) {
                hasCallerImpact = true;
                sb.append(", affecting ").append(sd.getAffectedCallerCount()).append(" caller(s)");
                if (!sd.getAffectedCallers().isEmpty()) {
                    sb.append(": ").append(sd.getAffectedCallers());
                }
            }
            sb.append("\n");
        }

        // Actionable guidance based on what went wrong
        sb.append("\n");
        if (hasSignatureIssue && hasCallerImpact) {
            sb.append("To fix this: preserve the original method signature and add a new overload,\n");
            sb.append("or include updates to ALL affected callers in your proposal.\n");
            sb.append("Use your retrieval tools to fetch the affected callers listed above.\n");
        } else if (hasSignatureIssue) {
            sb.append("To fix this: preserve the original method signature to avoid breaking callers.\n");
            sb.append("Consider adding a new overloaded method instead of modifying the existing one.\n");
        } else {
            sb.append("Review the structural changes above and ensure they are intentional.\n");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Safe Refactoring Loop — Self-Improving Agent        ║");
        System.out.println("║  Keeps querying graph until change is safe           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Extract a rough explanation from the agent's response.
     * Looks for an EXPLANATION: section or text between code blocks.
     */
    private static String extractExplanation(String response) {
        if (response == null || response.isBlank()) return "";

        int explIdx = response.toLowerCase().indexOf("explanation:");
        if (explIdx >= 0) {
            String after = response.substring(explIdx + "explanation:".length()).trim();
            // Take until next section header or code block
            int end = findSectionEnd(after);
            return end >= 0 ? after.substring(0, end).trim() : after.trim();
        }
        return "";
    }

    private static int findSectionEnd(String text) {
        String[] headers = {"CHANGES:", "BREAKING", "MISSING:", "```"};
        int earliest = -1;
        for (String header : headers) {
            int idx = text.toLowerCase().indexOf(header.toLowerCase());
            if (idx > 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest;
    }

    private static String truncateForLog(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... (" + text.length() + " chars total)";
    }

    // ═══════════════════════════════════════════════════════════════
    // AST Diff support
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the full cross-method AST diff for the agent's response.
     *
     * <p>Collects originals by resolving every method name that appears in the
     * response via {@link SafeLoopTools#resolveMethodForDiff(String)} and any
     * nodes already pulled into {@link SafeLoopTools#getRetrievedNodeIds()} so
     * the engine can detect deletions (originals in scope but absent from the
     * proposal).
     */
    private CrossMethodDiff analyzeCrossMethod(String agentResponse) {
        try {
            Set<String> chunkIds = new java.util.LinkedHashSet<>();

            // Include everything the loop has already retrieved — these are the
            // methods in scope and are the baseline for deletion detection.
            chunkIds.addAll(loopTools.getRetrievedNodeIds());

            // Resolve every method-name appearing in the response too, in case
            // the agent produced code for a method we haven't fetched yet.
            for (String block : extractCodeBlocks(agentResponse)) {
                String methodName = extractMethodNameFromCode(block);
                if (methodName == null || methodName.isEmpty()) continue;
                String resolved = loopTools.resolveMethodForDiff(methodName);
                if (resolved != null) chunkIds.add(resolved);
            }

            if (chunkIds.isEmpty()) return CrossMethodDiff.empty();

            Map<String, CodeChunk> chunks = loopTools.fetchChunksForDiff(chunkIds);
            if (chunks.isEmpty()) return CrossMethodDiff.empty();

            return diffEngine.analyze(new ArrayList<>(chunks.values()), agentResponse);
        } catch (Exception e) {
            System.out.println("    WARN: AST diff failed: " + e.getMessage());
            return CrossMethodDiff.empty();
        }
    }

    /**
     * Extract all ```java code blocks from a response string.
     */
    private static List<String> extractCodeBlocks(String response) {
        List<String> blocks = new ArrayList<>();
        if (response == null) return blocks;

        int searchFrom = 0;
        while (true) {
            int start = response.indexOf("```java", searchFrom);
            if (start < 0) break;

            int codeStart = response.indexOf('\n', start);
            if (codeStart < 0) break;
            codeStart++; // skip the newline

            int end = response.indexOf("```", codeStart);
            if (end < 0) break;

            String block = response.substring(codeStart, end).trim();
            if (!block.isEmpty()) {
                blocks.add(block);
            }
            searchFrom = end + 3;
        }
        return blocks;
    }

    /**
     * Try to extract a method name from a code block.
     *
     * <p>Looks for common Java method declaration patterns:
     * {@code (public|private|protected|static|...) ReturnType methodName(}
     */
    // ═══════════════════════════════════════════════════════════════
    // Analyzer schema (SafetyVerdict JSON)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build the {@link StructuredOutputSpec} that constrains the analyzer
     * reply to the SafetyVerdict JSON shape, or {@code null} when mode is
     * {@link RefactorConfig.StructuredOutputMode#OFF}.
     */
    private static StructuredOutputSpec analyzerSpec(RefactorConfig.StructuredOutputMode mode) {
        if (mode == RefactorConfig.StructuredOutputMode.OFF) return null;
        StructuredOutputSpec.Mode wireMode = switch (mode) {
            case JSON_SCHEMA -> StructuredOutputSpec.Mode.JSON_SCHEMA;
            case JSON_OBJECT -> StructuredOutputSpec.Mode.JSON_OBJECT;
            case TOOL_CALL -> StructuredOutputSpec.Mode.TOOL_CALL;
            case OFF -> throw new IllegalStateException("unreachable");
        };
        return new StructuredOutputSpec(ANALYZER_SCHEMA_NAME, safetyVerdictSchema(), wireMode);
    }

    private static JsonObject safetyVerdictSchema() {
        return PromptBuilder.safetyVerdictSchema();
    }

    // ═══════════════════════════════════════════════════════════════
    // Apply phase — deterministic file edits after SAFE verdict
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse the agent response into a {@link PatchPlan} and run
     * {@link PatchApplier} against it. Records the outcome on the result.
     */
    private void applyPatch(SafeLoopResult result, String agentResponse) {
        System.out.println("━━━ Phase 6: Apply ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String repoRootStr = config.getRepoRoot();
        if (repoRootStr == null || repoRootStr.isEmpty()) {
            String msg = "Apply requested but repoRoot is empty — set safeloop.repoRoot / SAFELOOP_REPO_ROOT.";
            System.out.println("  ✗ " + msg);
            System.out.println();
            result.withApplyResult(List.of(), msg);
            return;
        }

        try {
            PatchPlan plan = new LlmResponseParser().parsePatchPlan(
                agentResponse, loopTools.getGraphReader(), "safeloop");

            if (plan.isEmpty()) {
                String msg = "No applicable edits found in agent response (empty PatchPlan).";
                System.out.println("  ⚠ " + msg);
                System.out.println();
                result.withApplyResult(List.of(), msg);
                return;
            }

            Path repoRoot = Paths.get(repoRootStr);
            PatchApplier applier = new PatchApplier(
                repoRoot, loopTools.getGraphReader(), config.isDryRun(), config.isBackup());

            System.out.println("  Plan: " + plan.ops().size() + " op(s), dryRun=" + config.isDryRun()
                + ", backup=" + config.isBackup());
            ApplyResult ar = applier.apply(plan);
            System.out.println(ar.toReport().replace("\n", "\n  "));
            System.out.println();

            result.withApplyResult(ar.getChangedFiles(), ar.toReport());
        } catch (Exception e) {
            String msg = "Apply failed: " + e.getClass().getSimpleName() + " — " + e.getMessage();
            System.out.println("  ✗ " + msg);
            e.printStackTrace();
            System.out.println();
            result.withApplyResult(List.of(), msg);
        }
    }

    private static String extractMethodNameFromCode(String code) {
        // Look for method declaration pattern
        java.util.regex.Pattern methodPattern = java.util.regex.Pattern.compile(
                "(?:public|private|protected|static|final|abstract|synchronized|native|\\s)*"
                        + "\\s+\\w[\\w<>\\[\\],\\s]*\\s+(\\w+)\\s*\\(",
                java.util.regex.Pattern.MULTILINE
        );

        java.util.regex.Matcher matcher = methodPattern.matcher(code);
        if (matcher.find()) {
            String name = matcher.group(1);
            // Filter out common false positives
            if (!"if".equals(name) && !"for".equals(name) && !"while".equals(name)
                    && !"switch".equals(name) && !"catch".equals(name)
                    && !"new".equals(name) && !"return".equals(name)) {
                return name;
            }
        }
        return null;
    }
}


