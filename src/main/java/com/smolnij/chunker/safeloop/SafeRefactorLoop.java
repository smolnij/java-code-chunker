package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.RefactorAgent;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.retrieval.RetrievalResult;

import java.util.ArrayList;
import java.util.List;
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

        Your output MUST follow this exact format:

        CONFIDENCE: <0.0 to 1.0>
        VERDICT: SAFE    (if all checks pass)
        VERDICT: UNSAFE  (if any check fails)

        RISKS:
        - RISK: <description> | SEVERITY: HIGH | MITIGATION: <fix>
        - RISK: <description> | SEVERITY: MEDIUM | MITIGATION: <fix>
        - RISK: <description> | SEVERITY: LOW | MITIGATION: <fix>

        NEEDS:
        - <ClassName#methodName that you need to see to be more confident>
        - <another method>

        FEEDBACK:
        <General assessment of the refactoring quality>

        Rules:
        - Be conservative — default to UNSAFE if uncertain
        - CONFIDENCE must reflect your actual certainty, not optimism
        - If you don't have enough context to judge, set CONFIDENCE low and list NEEDS
        - Each RISK must have a SEVERITY and MITIGATION
        - If there are no risks, write: RISKS: none
        - If you don't need more context, write: NEEDS: none
        """;

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final RefactorAgent agent;
    private final ChatService analyzerChat;
    private final SafeLoopTools loopTools;
    private final RefactorTools agentTools;
    private final SafeLoopConfig config;

    /** Callback for streaming progress updates. */
    private Consumer<String> progressCallback;

    public SafeRefactorLoop(RefactorAgent agent,
                            ChatService analyzerChat,
                            SafeLoopTools loopTools,
                            RefactorTools agentTools,
                            SafeLoopConfig config) {
        this.agent = agent;
        this.analyzerChat = analyzerChat;
        this.loopTools = loopTools;
        this.agentTools = agentTools;
        this.config = config;
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
                    String refinementPrompt = buildRefinementPrompt(lastVerdict, iteration);
                    lastAgentResponse = agent.getAssistant().chat(refinementPrompt);
                }

                System.out.println("  Agent responded (" + agentTools.getToolCallCount() + " tool calls)");
                System.out.println();

                // ── Phase 4: VALIDATE ──
                System.out.println("━━━ " + iterLabel + ": Phase 4 — Safety Validation ━━━━━");

                String analyzerPrompt = buildAnalyzerPrompt(userQuery, lastAgentResponse, iteration);
                System.out.println("  ┌─ Analyzer ─────────────────────────────────────");
                String analyzerResponse = analyzerChat.chat(ANALYZER_SYSTEM_PROMPT, analyzerPrompt);
                System.out.println("  │ " + truncateForLog(analyzerResponse, 300).replace("\n", "\n  │ "));
                System.out.println("  └───────────────────────────────────────────────────");

                SafetyVerdict verdict = SafetyVerdict.parse(analyzerResponse);
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

            return new SafeLoopResult(
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
     */
    private String buildAnalyzerPrompt(String originalQuery, String agentResponse, int iteration) {
        StringBuilder sb = new StringBuilder();

        sb.append("TASK: Evaluate the safety of this proposed refactoring.\n\n");

        sb.append("ORIGINAL REQUEST:\n");
        sb.append(originalQuery).append("\n\n");

        sb.append("PROPOSED REFACTORING (iteration ").append(iteration + 1).append("):\n");
        sb.append(agentResponse).append("\n\n");

        sb.append("GRAPH COVERAGE:\n");
        sb.append("Total methods retrieved: ").append(loopTools.getTotalNodesRetrieved()).append("\n");
        sb.append("Graph expansions performed: ").append(loopTools.getExpansionCount()).append("\n\n");

        sb.append("Evaluate this refactoring against ALL safety criteria.\n");
        sb.append("Be thorough — check for broken signatures, missing deps, threading, and side effects.\n");
        sb.append("If you need to see specific methods to increase confidence, list them in NEEDS.\n");

        return sb.toString();
    }

    /**
     * Build a refinement prompt that feeds the analyzer's safety feedback back
     * to the refactoring agent.
     */
    private String buildRefinementPrompt(SafetyVerdict verdict, int iteration) {
        StringBuilder sb = new StringBuilder();

        sb.append("Your previous refactoring was evaluated by a safety analyzer.\n\n");

        sb.append("SAFETY RESULT: ").append(verdict.isVerdictSafe() ? "SAFE" : "UNSAFE").append("\n");
        sb.append("CONFIDENCE: ").append(String.format("%.2f", verdict.getConfidence())).append("\n\n");

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
}


