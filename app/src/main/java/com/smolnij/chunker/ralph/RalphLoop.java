package com.smolnij.chunker.ralph;

import com.smolnij.chunker.refactor.ChatService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Ralph Wiggum Loop — a Worker/Judge LLM orchestrator.
 *
 * <p>Named after Ralph Wiggum from The Simpsons: a "naive" worker agent
 * repeatedly attempts a task while a strict judge evaluates the output.
 * By feeding judge feedback back to the worker on each retry, the worker
 * stumbles into increasingly correct answers.
 *
 * <h3>Algorithm:</h3>
 * <pre>
 *   for iteration = 0 .. maxIterations:
 *       workerOutput = worker.attempt(task, previousFeedback)
 *       verdict      = judge.evaluate(workerOutput)
 *       if verdict.passed:
 *           return SUCCESS(workerOutput)
 *       else:
 *           previousFeedback = verdict.feedback
 *   return BEST_EFFORT(lastWorkerOutput)
 * </pre>
 *
 * <h3>Key properties:</h3>
 * <ul>
 *   <li><b>Generic</b> — works with any {@link RalphTask} implementation</li>
 *   <li><b>Dual-agent</b> — separate ChatService instances for worker and judge
 *       (can be the same instance if using one model)</li>
 *   <li><b>Streaming</b> — supports SSE streaming for both worker and judge</li>
 *   <li><b>Conservative</b> — defaults to FAIL if judge output is unparseable</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   ChatService worker = new LmStudioChatService(...);
 *   ChatService judge  = new LmStudioChatService(...);  // or same instance
 *
 *   RalphLoop loop = new RalphLoop(worker, judge, config);
 *   RalphResult result = loop.run(myTask);
 *
 *   if (result.isApproved()) {
 *       System.out.println("Judge approved: " + result.getFinalOutput());
 *   }
 * </pre>
 */
public class RalphLoop {

    private final ChatService workerChat;
    private final ChatService judgeChat;
    private final RalphConfig config;

    /** Callback for streaming tokens to the console (or elsewhere). */
    private Consumer<String> streamCallback;

    /**
     * Create a Ralph Loop with separate worker and judge chat services.
     *
     * @param workerChat the chat service for the worker (Ralph)
     * @param judgeChat  the chat service for the judge (can be the same instance)
     * @param config     loop configuration
     */
    public RalphLoop(ChatService workerChat, ChatService judgeChat, RalphConfig config) {
        this.workerChat = workerChat;
        this.judgeChat = judgeChat;
        this.config = config;
        this.streamCallback = System.out::print;
    }

    /**
     * Create a Ralph Loop using the same chat service for both roles.
     */
    public RalphLoop(ChatService chatService, RalphConfig config) {
        this(chatService, chatService, config);
    }

    public void setStreamCallback(Consumer<String> callback) {
        this.streamCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the Worker/Judge loop for the given task.
     *
     * @param task the task definition (provides prompts for both roles)
     * @return the result, including whether the judge approved
     */
    public RalphResult run(RalphTask task) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Ralph Wiggum Loop — Worker/Judge Orchestrator       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Task: " + task.getTaskLabel());
        System.out.println("Config: " + config);
        System.out.println("Max iterations: " + config.getMaxIterations());
        if (config.isTrace()) {
            System.out.println("[TRACE] Trace logging enabled — full prompts and responses will be printed");
        }
        System.out.println();

        List<JudgeVerdict> verdictHistory = new ArrayList<>();
        String lastWorkerOutput = "";
        String lastFeedback = null;

        for (int iteration = 0; iteration < config.getMaxIterations(); iteration++) {
            String iterLabel = "Iteration " + (iteration + 1) + "/" + config.getMaxIterations();

            // ── Worker attempt ──
            System.out.println("━━━ " + iterLabel + ": Worker (Ralph) ━━━━━━━━━━━━━━━━━");
            String workerPrompt = task.buildWorkerPrompt(
                iteration == 0 ? null : lastWorkerOutput,
                lastFeedback,
                iteration
            );
            lastWorkerOutput = callLlm(
                "Worker #" + (iteration + 1),
                task.getWorkerSystemPrompt(),
                workerPrompt,
                workerChat,
                true // stream worker output
            );

            System.out.println();

            // ── Judge evaluation ──
            System.out.println("━━━ " + iterLabel + ": Judge ━━━━━━━━━━━━━━━━━━━━━━━━━━");
            String judgePrompt = task.buildJudgePrompt(lastWorkerOutput, iteration);
            String judgeResponse = callLlm(
                "Judge #" + (iteration + 1),
                task.getJudgeSystemPrompt(),
                judgePrompt,
                judgeChat,
                false // don't stream judge — we need to parse it
            );

            JudgeVerdict verdict = JudgeVerdict.parse(judgeResponse);
            verdictHistory.add(verdict);

            System.out.println();
            System.out.println("  → " + verdict);

            // ── Check verdict ──
            if (verdict.isPassed()) {
                System.out.println("  → ✓ Judge APPROVED on iteration " + (iteration + 1));
                System.out.println();
                return new RalphResult(
                    lastWorkerOutput, true, iteration + 1, verdictHistory, task.getTaskLabel()
                );
            }

            // ── Prepare feedback for next iteration ──
            lastFeedback = verdict.getFeedback();
            System.out.println("  → ✗ Judge REJECTED — feeding back for retry");
            if (!verdict.getIssues().isEmpty()) {
                System.out.println("  → Issues:");
                for (String issue : verdict.getIssues()) {
                    System.out.println("      • " + issue);
                }
            }
            System.out.println();
        }

        // ── Max iterations exhausted ──
        System.out.println("━━━ Max iterations reached ━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("  → Returning best-effort output (NOT approved by judge)");
        System.out.println();

        return new RalphResult(
            lastWorkerOutput, false, config.getMaxIterations(), verdictHistory, task.getTaskLabel()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // LLM call (streaming or non-streaming)
    // ═══════════════════════════════════════════════════════════════

    private String callLlm(String label, String systemPrompt, String userPrompt,
                           ChatService chat, boolean allowStream) {
        System.out.println("  ┌─ LLM [" + label + "] ─────────────────────────────");

        if (config.isTrace()) {
            System.out.println("  │ [TRACE] SYSTEM PROMPT (" + systemPrompt.length() + " chars):");
            System.out.println(systemPrompt.replace("\n", "\n  │   "));
            System.out.println("  │ [TRACE] USER PROMPT (" + userPrompt.length() + " chars):");
            System.out.println(userPrompt.replace("\n", "\n  │   "));
        }

        String response;
        if (allowStream && config.isStream() && streamCallback != null) {
            System.out.println("  │ (streaming) ");
            response = chat.chatStream(systemPrompt, userPrompt, streamCallback);
            System.out.println();
        } else {
            System.out.println("  │ (waiting for response...) ");
            response = chat.chat(systemPrompt, userPrompt);
            if (config.isTrace()) {
                System.out.println("  │ [TRACE] FULL RESPONSE (" + response.length() + " chars):");
                System.out.println(response.replace("\n", "\n  │   "));
            } else {
                String preview = response.length() > 500
                    ? response.substring(0, 500) + "\n  │ ... (" + response.length() + " chars total)"
                    : response;
                System.out.println("  │ " + preview.replace("\n", "\n  │ "));
            }
        }

        if (config.isTrace()) {
            System.out.println("  │ [TRACE] response length: " + response.length() + " chars");
        }
        System.out.println("  └───────────────────────────────────────────────────");
        return response;
    }
}

