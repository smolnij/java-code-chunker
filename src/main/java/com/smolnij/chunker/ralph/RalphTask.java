package com.smolnij.chunker.ralph;

/**
 * Defines a task that the Ralph Wiggum Loop can execute.
 *
 * <p>Implementations provide the prompt-building logic for both the
 * <b>worker</b> (the "Ralph" that attempts the task) and the <b>judge</b>
 * (the evaluator that reviews the output and provides feedback).
 *
 * <p>This is the generic extension point — implement this interface to
 * use the Ralph Loop for any task type (code refactoring, code review,
 * documentation, testing, etc.) without touching the loop itself.
 *
 * <h3>Contract:</h3>
 * <ul>
 *   <li>Iteration 0: {@code buildWorkerPrompt(null, null, 0)} — first attempt, no prior feedback</li>
 *   <li>Iteration N: {@code buildWorkerPrompt(prevOutput, judgeFeedback, N)} — retry with feedback</li>
 *   <li>Judge is called after every worker attempt</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>
 *   RalphTask task = new RefactorRalphTask(query, retrievalResults);
 *   RalphLoop loop = new RalphLoop(workerChat, judgeChat, config);
 *   RalphResult result = loop.run(task);
 * </pre>
 */
public interface RalphTask {

    /**
     * @return the system prompt for the worker LLM (persona / role definition)
     */
    String getWorkerSystemPrompt();

    /**
     * Build the user prompt for the worker LLM.
     *
     * @param previousAttempt the worker's previous output (null on first iteration)
     * @param judgeFeedback   the judge's feedback on the previous attempt (null on first iteration)
     * @param iteration       0-based iteration counter
     * @return the user-message prompt for the worker
     */
    String buildWorkerPrompt(String previousAttempt, String judgeFeedback, int iteration);

    /**
     * @return the system prompt for the judge LLM (evaluator persona)
     */
    String getJudgeSystemPrompt();

    /**
     * Build the user prompt for the judge LLM.
     *
     * @param workerOutput the worker's latest output to evaluate
     * @param iteration    0-based iteration counter
     * @return the user-message prompt for the judge
     */
    String buildJudgePrompt(String workerOutput, int iteration);

    /**
     * @return a short human-readable label for this task (used in logs)
     */
    default String getTaskLabel() {
        return "RalphTask";
    }
}

