package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.safeloop.SafeLoopResult;
import com.smolnij.chunker.safeloop.SafetyVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Distributed planner-driven safe refactoring loop.
 *
 * <p>This is the evolution from the manual orchestrator loop to a planner-driven
 * agentic architecture. The Planner–Analyzer on S_ANALYZE_MACHINE has full
 * authority — it decides what to retrieve, when to invoke the Generator, and
 * when to stop.
 *
 * <h3>Architecture:</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │              PLANNER-DRIVEN REFACTORING LOOP                 │
 *   │                                                              │
 *   │  ┌─────────────────────┐      ┌─────────────────────┐       │
 *   │  │  REFACTOR_MACHINE   │      │  S_ANALYZE_MACHINE  │       │
 *   │  │  http://REFACTORM   │      │  http://SANALYZEM   │       │
 *   │  │  :1234              │      │  :1234              │       │
 *   │  │                     │      │                     │       │
 *   │  │  🟦 Generator LLM   │      │  🟩 Planner–Analyzer│       │
 *   │  │  "Senior Java       │      │  "Senior architect  │       │
 *   │  │   engineer"         │      │   + static analyzer"│       │
 *   │  │  temp=0.3           │      │  temp=0.1           │       │
 *   │  │  Tool-calling: NO   │      │  Tool-calling: YES  │       │
 *   │  │  No decision auth   │      │  Full authority     │       │
 *   │  └─────────┬───────────┘      └──────────┬──────────┘       │
 *   │            │                              │                  │
 *   │            │  refactorCode() via          │                  │
 *   │            │  planner tool call           │                  │
 *   │            │ ◄────────────────────────────┤                  │
 *   │            │                              │                  │
 *   │            │  retrieveCode()              │                  │
 *   │            │  getMethodCallers()           │                  │
 *   │            │  getMethodCallees()           │                  │
 *   │            │  ────────────────────────────►│                  │
 *   │            │                              │                  │
 *   │  ┌─────────┴──────────────────────────────┴──────────┐      │
 *   │  │              Graph Retrieval Layer                  │      │
 *   │  │       Neo4j + Embeddings (runs locally)            │      │
 *   │  └───────────────────────────────────────────────────┘      │
 *   └──────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Planner-driven flow:</h3>
 * <pre>
 *   1. User submits refactoring request
 *   2. Planner autonomously:
 *      a. Plans what context is needed
 *      b. Calls retrieval tools (retrieveCode, getMethodCallers, getMethodCallees)
 *      c. Validates sufficient context (callees, callers, shared state)
 *      d. Delegates to Generator via refactorCode() tool
 *      e. Critically validates the Generator's response
 *      f. If unsafe → retrieves more context → asks Generator to retry
 *      g. Repeats until confidence &gt; 0.9 or max steps reached
 *   3. Planner returns structured JSON verdict
 * </pre>
 *
 * <h3>Guardrails:</h3>
 * <ul>
 *   <li>Max planner steps (tool calls) — default 8</li>
 *   <li>Max chunks per retrieval — default 10</li>
 *   <li>Max retrieval depth — default 2 hops</li>
 *   <li>Safety threshold — confidence must be &gt; 0.9</li>
 *   <li>Planner must retrieve callers + callees before refactoring (enforced in prompt)</li>
 * </ul>
 */
public class DistributedSafeRefactorLoop {

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    /** The planner–analyzer agent (runs on S_ANALYZE_MACHINE). Controls everything. */
    private final PlannerAgent plannerAgent;

    /** Configuration. */
    private final DistributedSafeLoopConfig config;

    /** Callback for streaming progress updates. */
    private Consumer<String> progressCallback;

    public DistributedSafeRefactorLoop(PlannerAgent plannerAgent,
                                        DistributedSafeLoopConfig config) {
        this.plannerAgent = plannerAgent;
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
     * Run the planner-driven safe refactoring loop.
     *
     * <p>Instead of a manual orchestrator loop, the Planner–Analyzer agent
     * on S_ANALYZE_MACHINE autonomously drives the entire process using
     * LangChain4j tool calling:
     * <ul>
     *   <li>retrieval tools — to gather code context from the graph</li>
     *   <li>refactorCode() — to delegate code generation to the Generator</li>
     *   <li>validation — built into the planner's reasoning loop</li>
     * </ul>
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
        String plannerResponse = "";
        SafeLoopResult.TerminalReason terminalReason = SafeLoopResult.TerminalReason.MAX_ITERATIONS;

        try {
            // ══════════════════════════════════════════════════════
            // Single call: Planner drives everything autonomously
            // ══════════════════════════════════════════════════════
            System.out.println("━━━ Planner–Analyzer Autonomous Loop ━━━━━━━━━━━━━━━━━━");
            System.out.println("  The planner will autonomously:");
            System.out.println("    1. Plan refactoring steps");
            System.out.println("    2. Retrieve all necessary context from the code graph");
            System.out.println("    3. Delegate refactoring to Generator (REFACTOR_MACHINE)");
            System.out.println("    4. Validate results and iterate until safe");
            System.out.println();

            plannerResponse = plannerAgent.plan(userQuery);

            System.out.println("━━━ Planner Response ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  " + truncateForLog(plannerResponse, 600).replace("\n", "\n  "));
            System.out.println();

            // ══════════════════════════════════════════════════════
            // Parse the planner's final JSON verdict
            // ══════════════════════════════════════════════════════
            DistributedSafetyVerdict verdict = DistributedSafetyVerdict.parse(plannerResponse);

            // Also extract refactored code if the planner included it
            String refactoredCode = extractRefactoredCode(plannerResponse);

            // Create a SafetyVerdict for compatibility
            SafetyVerdict compatVerdict = convertToSafetyVerdict(verdict);
            verdictHistory.add(compatVerdict);

            System.out.println("━━━ Final Verdict ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("  " + verdict);

            System.out.println();
            if (verdict.isParsedFromJson()) {
                System.out.println("  ╔══════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ✓  STRUCTURED OUTPUT ACTIVE                            ║");
                System.out.println("  ╠══════════════════════════════════════════════════════════╣");
                System.out.println("  ║  [Planner] Response parsed from JSON successfully.");
                System.out.println("  ╚══════════════════════════════════════════════════════════╝");
            } else {
                System.out.println("  ╔══════════════════════════════════════════════════════════╗");
                System.out.println("  ║  ⚠  WARNING: STRUCTURED OUTPUT FALLBACK                 ║");
                System.out.println("  ╠══════════════════════════════════════════════════════════╣");
                System.out.println("  ║  [Planner] LLM ignored response_format — using regex fallback.");
                System.out.println("  ║  Results may be incomplete or misparse. Check model support.");
                System.out.println("  ╚══════════════════════════════════════════════════════════╝");
            }
            System.out.println();

            // Determine terminal reason
            PlannerTools tools = plannerAgent.getTools();
            if (verdict.isSafe(config.getSafetyThreshold())) {
                terminalReason = SafeLoopResult.TerminalReason.SAFE;
                System.out.println("  → ✓ SAFE — confidence " + String.format("%.2f", verdict.getConfidence())
                    + " ≥ threshold " + String.format("%.2f", config.getSafetyThreshold()));
            } else if (tools.getToolCallCount() == 0) {
                terminalReason = SafeLoopResult.TerminalReason.ERROR;
                System.out.println("  → ✗ ERROR — planner made no tool calls");
            } else {
                // Planner finished but wasn't confident enough
                if (verdict.getConfidence() > 0) {
                    terminalReason = SafeLoopResult.TerminalReason.MAX_ITERATIONS;
                    System.out.println("  → ✗ UNSAFE — planner finished with confidence "
                        + String.format("%.2f", verdict.getConfidence())
                        + " < threshold " + String.format("%.2f", config.getSafetyThreshold()));
                } else {
                    terminalReason = SafeLoopResult.TerminalReason.CONVERGED;
                    System.out.println("  → ✗ CONVERGED — planner could not improve further");
                }
            }

            System.out.println();
            System.out.println("  📊 Planner stats:");
            System.out.println("     Tool calls: " + tools.getToolCallCount());
            System.out.println("     Refactor delegations: " + tools.getRefactorCallCount());
            System.out.println("     Graph nodes retrieved: " + tools.getTotalNodesRetrieved());
            System.out.println();

            boolean isSafe = verdict.isSafe(config.getSafetyThreshold());

            // Use the generator's last result if available, otherwise the planner response
            String finalCode = refactoredCode.isEmpty()
                ? tools.getLastRefactoringResult()
                : refactoredCode;

            return new SafeLoopResult(
                userQuery,
                finalCode,
                extractExplanation(plannerResponse),
                isSafe,
                verdict.getConfidence(),
                terminalReason,
                verdictHistory.size(),
                verdictHistory,
                tools.getToolCallCount(),
                tools.getTotalNodesRetrieved(),
                compatVerdict != null ? compatVerdict.getRisks() : List.of(),
                plannerResponse
            );

        } catch (Exception e) {
            System.err.println("  ✗ ERROR: " + e.getMessage());
            e.printStackTrace();

            return new SafeLoopResult(
                userQuery, "", "Error: " + e.getMessage(),
                false, 0.0,
                SafeLoopResult.TerminalReason.ERROR,
                verdictHistory.size(), verdictHistory,
                plannerAgent.getTools().getToolCallCount(),
                plannerAgent.getTools().getTotalNodesRetrieved(),
                List.of(), plannerResponse
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Compatibility converter
    // ═══════════════════════════════════════════════════════════════

    /**
     * Convert a {@link DistributedSafetyVerdict} to the legacy
     * {@link SafetyVerdict} format for backwards compatibility.
     */
    private SafetyVerdict convertToSafetyVerdict(DistributedSafetyVerdict dv) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONFIDENCE: ").append(String.format("%.2f", dv.getConfidence())).append("\n");
        sb.append("VERDICT: ").append(dv.isSafe() ? "SAFE" : "UNSAFE").append("\n\n");

        if (!dv.getIssues().isEmpty()) {
            sb.append("RISKS:\n");
            for (DistributedSafetyVerdict.Issue issue : dv.getIssues()) {
                sb.append("- RISK: ").append(issue.getDescription())
                    .append(" | SEVERITY: ").append(issue.getSeverity())
                    .append(" | MITIGATION: ").append(issue.getMitigation().isEmpty()
                        ? "N/A" : issue.getMitigation())
                    .append("\n");
            }
        } else {
            sb.append("RISKS: none\n");
        }

        sb.append("\n");
        if (!dv.getMissingContext().isEmpty()) {
            sb.append("NEEDS:\n");
            for (String ctx : dv.getMissingContext()) {
                sb.append("- ").append(ctx).append("\n");
            }
        } else {
            sb.append("NEEDS: none\n");
        }

        sb.append("\nFEEDBACK:\n").append(dv.getSummary());

        return SafetyVerdict.parse(sb.toString());
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private void printBanner() {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║  Planner-Driven Distributed Refactoring Loop              ║");
        System.out.println("║  🟦 Generator (REFACTOR_MACHINE) — writes code            ║");
        System.out.println("║  🟩 Planner–Analyzer (S_ANALYZE) — controls everything    ║");
        System.out.println("║  Planner has tools: retrieve, refactor, validate           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Extract refactored code from the planner's final JSON response.
     */
    private static String extractRefactoredCode(String response) {
        if (response == null || response.isBlank()) return "";

        // Try JSON-based extraction: "refactored_code": "..."
        int idx = response.toLowerCase().indexOf("\"refactored_code\"");
        if (idx >= 0) {
            int colonIdx = response.indexOf(':', idx);
            if (colonIdx >= 0) {
                int startQuote = response.indexOf('"', colonIdx + 1);
                if (startQuote >= 0) {
                    int endQuote = findClosingQuote(response, startQuote + 1);
                    if (endQuote > startQuote) {
                        return response.substring(startQuote + 1, endQuote)
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\t", "\t");
                    }
                }
            }
        }

        return "";
    }

    /**
     * Find the closing quote, handling escaped quotes.
     */
    private static int findClosingQuote(String text, int start) {
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '"' && text.charAt(i - 1) != '\\') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract a rough explanation from the planner's response.
     */
    private static String extractExplanation(String response) {
        if (response == null || response.isBlank()) return "";

        // Try JSON-based extraction first
        int explIdx = response.toLowerCase().indexOf("\"explanation\"");
        if (explIdx >= 0) {
            int colonIdx = response.indexOf(':', explIdx);
            if (colonIdx >= 0) {
                int startQuote = response.indexOf('"', colonIdx + 1);
                if (startQuote >= 0) {
                    int endQuote = findClosingQuote(response, startQuote + 1);
                    if (endQuote > startQuote) {
                        return response.substring(startQuote + 1, endQuote);
                    }
                }
            }
        }

        // Try "summary" field
        int sumIdx = response.toLowerCase().indexOf("\"summary\"");
        if (sumIdx >= 0) {
            int colonIdx = response.indexOf(':', sumIdx);
            if (colonIdx >= 0) {
                int startQuote = response.indexOf('"', colonIdx + 1);
                if (startQuote >= 0) {
                    int endQuote = findClosingQuote(response, startQuote + 1);
                    if (endQuote > startQuote) {
                        return response.substring(startQuote + 1, endQuote);
                    }
                }
            }
        }

        return "";
    }

    private static String truncateForLog(String text, int maxLen) {
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "\n... (" + text.length() + " chars total)";
    }
}
