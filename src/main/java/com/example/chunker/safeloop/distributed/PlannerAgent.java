package com.example.chunker.safeloop.distributed;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.time.Duration;

/**
 * LangChain4j-powered Planner–Analyzer agent for the distributed safe refactoring loop.
 *
 * <p>This is the "brain" of the system. Unlike the old architecture where the
 * orchestrator manually drove retrieve→refactor→analyze steps, the Planner
 * makes all decisions autonomously using tool calling.
 *
 * <h3>Architecture:</h3>
 * <pre>
 *   User ──→ PlannerAgent.plan("Refactor createUser to async")
 *                │
 *                ├── 🧠 Step 1: PLAN
 *                │   "I need: createUser, its callers, repository.save"
 *                │
 *                ├── 🔍 Step 2: RETRIEVE
 *                │   → retrieveCode("UserService.createUser", depth=2)
 *                │   → getMethodCallers("createUser")
 *                │   → getMethodCallees("createUser")
 *                │
 *                ├── 🔧 Step 3: REFACTOR
 *                │   → refactorCode("Refactor to async... &lt;context&gt;")
 *                │
 *                ├── 🔍 Step 4: VALIDATE
 *                │   Evaluates response, finds issues:
 *                │   - caller not async
 *                │   - transaction boundary unclear
 *                │
 *                ├── 🔍 Step 5: EXPAND CONTEXT
 *                │   → retrieveCodeById("UserController#createUser", depth=1)
 *                │
 *                ├── 🔧 Step 6: RETRY REFACTOR
 *                │   → refactorCode("Fix async caller... &lt;expanded context&gt;")
 *                │
 *                └── ✅ Step 7: FINISH
 *                    confidence ≥ 0.9 → safe
 * </pre>
 *
 * <h3>Key design:</h3>
 * <ul>
 *   <li>🟦 Generator (REFACTOR_MACHINE) — writes code, no decision-making authority</li>
 *   <li>🟩 Planner–Analyzer (S_ANALYZE_MACHINE) — decides, validates, controls retrieval, terminates</li>
 * </ul>
 *
 * <p>The planner is forced to think structurally via its output format:
 * <pre>
 * {
 *   "action": "retrieve | refactor | validate | finish",
 *   "reason": "...",
 *   "confidence": 0.72,
 *   "safe": false,
 *   "issues": [...],
 *   "refactored_code": "..."
 * }
 * </pre>
 */
public class PlannerAgent {

    // ═══════════════════════════════════════════════════════════════
    // Planner AI Service interface
    // ═══════════════════════════════════════════════════════════════

    /**
     * The AI Service interface for the Planner–Analyzer.
     * LangChain4j generates an implementation that handles tool calling,
     * chat memory, and response parsing.
     */
    public interface PlannerAssistant {

        @SystemMessage("""
            You are a senior software architect and static analyzer.
            You control the entire refactoring process.

            You have tools:
            - retrieveCode(query, depth): Search the codebase using natural language + graph expansion
            - retrieveCodeById(methodId, depth): Fetch a specific method and its graph neighbourhood
            - getMethodCallers(methodId): Find all callers of a method (impact analysis)
            - getMethodCallees(methodId): Find all callees of a method (dependency analysis)
            - refactorCode(prompt): Delegate refactoring to the Generator. YOU MUST include ALL code context in the prompt.

            Your responsibilities:
            1. Plan refactoring steps
            2. Retrieve all necessary dependencies using the retrieval tools
            3. Ask the generator to refactor by calling refactorCode with a detailed prompt
            4. Critically validate the generator's results
            5. If unsafe:
               - Request more context using retrieval tools
               - Refine the plan
               - Ask the generator to retry
            6. Repeat until safe

            MANDATORY RULES:
            - NEVER call refactorCode without first retrieving sufficient context
            - Before refactoring:
              * Ensure all direct callees are retrieved (dependency analysis)
              * Ensure at least one level of callers is retrieved (impact analysis)
              * Ensure shared state dependencies are included
            - ALWAYS validate the generator's output before approving
            - Prefer multiple small retrieval steps over one large query
            - Stop only when confidence > 0.9 and you are certain the refactoring is safe

            When you are DONE (either the refactoring is safe or you cannot proceed), produce your
            FINAL answer as STRICT JSON with this exact structure:

            {
              "action": "finish",
              "safe": true,
              "confidence": 0.93,
              "refactored_code": "... the final approved refactored code ...",
              "changes": [
                { "file": "ClassName.java", "method": "methodName", "description": "what changed" }
              ],
              "issues": [],
              "missing_context": [],
              "summary": "Brief overall assessment",
              "explanation": "Why this refactoring is safe"
            }

            Rules for the final JSON:
            - "safe" must be true ONLY if confidence > 0.9 AND no HIGH severity issues remain
            - "confidence" must be between 0.0 and 1.0
            - If any issues remain, list them with severity HIGH/MEDIUM/LOW
            - Return ONLY the JSON as your final message. No markdown, no text before or after.
            """)
        String chat(@UserMessage String userMessage);
    }

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final PlannerAssistant assistant;
    private final PlannerTools tools;
    private final DistributedSafeLoopConfig config;

    // ═══════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════

    public PlannerAgent(DistributedSafeLoopConfig config, PlannerTools tools) {
        this.config = config;
        this.tools = tools;

        // Build the OpenAI-compatible chat model for the Planner (S_ANALYZE_MACHINE)
        ChatLanguageModel chatModel = buildChatModel(config);

        // Build chat memory (sliding window)
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(config.getChatMemorySize())
            .build();

        // Wire up the AI Service with planner tools
        this.assistant = AiServices.builder(PlannerAssistant.class)
            .chatLanguageModel(chatModel)
            .chatMemory(chatMemory)
            .tools(tools)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run the planner-driven refactoring process.
     *
     * <p>The planner autonomously:
     * <ol>
     *   <li>Retrieves necessary code context from the graph</li>
     *   <li>Delegates refactoring to the Generator</li>
     *   <li>Validates the result</li>
     *   <li>Iterates until safe or gives up</li>
     * </ol>
     *
     * @param userQuery the refactoring request
     * @return the planner's final structured JSON response
     */
    public String plan(String userQuery) {
        System.out.println("  🟩 Planner–Analyzer starting autonomous planning...");
        System.out.println("  Query: " + userQuery);
        System.out.println();

        tools.resetToolCallCount();

        String response = assistant.chat(userQuery);

        System.out.println();
        System.out.println("  ✓ Planner completed with " + tools.getToolCallCount()
            + " tool calls (" + tools.getRefactorCallCount() + " refactor delegations)");
        System.out.println("  ✓ Total graph nodes retrieved: " + tools.getTotalNodesRetrieved());
        System.out.println();

        return response;
    }

    /**
     * Get the underlying assistant for advanced multi-turn usage.
     */
    public PlannerAssistant getAssistant() {
        return assistant;
    }

    /**
     * Get the tools instance for inspection.
     */
    public PlannerTools getTools() {
        return tools;
    }

    // ═══════════════════════════════════════════════════════════════
    // Chat model builder
    // ═══════════════════════════════════════════════════════════════

    private static ChatLanguageModel buildChatModel(DistributedSafeLoopConfig config) {
        // Extract base URL from the analyzer URL
        String chatUrl = config.getAnalyzerUrl();
        String baseUrl = chatUrl;
        if (chatUrl.endsWith("/chat/completions")) {
            baseUrl = chatUrl.substring(0, chatUrl.length() - "/chat/completions".length());
        } else if (chatUrl.contains("/v1/")) {
            baseUrl = chatUrl.substring(0, chatUrl.indexOf("/v1/") + 4);
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey("lm-studio")  // LM-Studio doesn't validate API keys
            .temperature(config.getAnalyzerTemperature())
            .topP(config.getTopP())
            .maxTokens(config.getMaxTokens())
            .timeout(Duration.ofMinutes(10))  // Planner may take longer due to multiple tool calls
            .logRequests(false)
            .logResponses(false);

        String modelName = config.getAnalyzerModel();
        if (modelName != null && !modelName.isEmpty()) {
            builder.modelName(modelName);
        } else {
            builder.modelName("local-model");
        }

        return builder.build();
    }
}

