package com.smolnij.chunker.refactor;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.time.Duration;

/**
 * LangChain4j-powered agentic refactoring assistant.
 *
 * <p>Builds an {@link Assistant} AI Service backed by an OpenAI-compatible
 * chat model (LM-Studio) with tool-calling support. The LLM can autonomously
 * invoke {@link RefactorTools} to gather code context from the Neo4j graph
 * before suggesting refactorings.
 *
 * <h3>Architecture:</h3>
 * <pre>
 *   User ──→ Assistant.chat("Refactor createUser to async")
 *                │
 *                ├──→ LLM reasons, decides it needs context
 *                ├──→ LLM calls retrieveCode("UserService createUser")     ← tool call
 *                ├──→ Tool returns method code + call graph
 *                ├──→ LLM calls getMethodCallers("createUser")             ← tool call
 *                ├──→ Tool returns caller methods
 *                ├──→ LLM has enough context, produces refactored code
 *                │
 *                └──→ Returns final response with code + explanation
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   RefactorTools tools = new RefactorTools(retriever, graphReader);
 *   RefactorAgent agent = new RefactorAgent(config, tools);
 *   String result = agent.chat("Refactor createUser to async");
 *   System.out.println(result);
 * </pre>
 */
public class RefactorAgent {

    // ═══════════════════════════════════════════════════════════════
    // Assistant interface — LangChain4j AI Service
    // ═══════════════════════════════════════════════════════════════

    /**
     * The AI Service interface. LangChain4j generates an implementation
     * that handles tool calling, chat memory, and response parsing.
     */
    public interface Assistant {

        @SystemMessage("""
            You are a senior Java refactoring agent with access to a code graph database.

            You have access to these tools:
            - retrieveCode(query): Search the codebase using natural language. Use this to find relevant methods.
            - retrieveCodeById(methodId, depth): Fetch a specific method and its graph neighbourhood.
              methodId can be "ClassName#methodName", "methodName", or a fully-qualified reference.
              depth controls how many hops of dependencies to include (1-3).
            - getMethodCallers(methodId): Find all methods that call a given method (impact analysis).
            - getMethodCallees(methodId): Find all methods that a given method calls (dependency analysis).

            Rules:
            1. ALWAYS gather context before suggesting changes. Never guess at code you haven't seen.
            2. If you lack context about a method or class, call the appropriate retrieval tool.
            3. Prefer multiple small, targeted retrievals over one large query.
            4. Always check callers of a method before changing its signature (impact analysis).
            5. Always check callees before refactoring a method body (dependency analysis).
            6. After gathering sufficient context, produce your refactoring response.

            Response format (after gathering all context):
            1. CHANGES: For each modified method, provide the complete updated code in a ```java block.
            2. EXPLANATION: Brief explanation of what changed and why.
            3. BREAKING CHANGES: List any potential breaking changes, signature changes, or side effects.
            4. If there are no breaking changes, write: NO BREAKING CHANGES.
            """)
        String chat(@UserMessage String userMessage);
    }

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final Assistant assistant;
    private final RefactorTools tools;
    private final RefactorConfig config;

    // ═══════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════

    public RefactorAgent(RefactorConfig config, RefactorTools tools) {
        this.config = config;
        this.tools = tools;

        // ── Build the OpenAI-compatible chat model (LM-Studio) ──
        ChatLanguageModel chatModel = buildChatModel(config);

        // ── Build chat memory (sliding window) ──
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(config.getChatMemorySize())
                .build();

        // ── Wire up the AI Service with tools ──
        this.assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(chatModel)
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a refactoring request to the agentic assistant.
     *
     * <p>The LLM will autonomously call retrieval tools to gather context,
     * then produce a refactoring response with code changes and explanations.
     *
     * @param userMessage the refactoring request, e.g. "Refactor createUser to async"
     * @return the LLM's complete response including code, explanation, and breaking changes
     */
    public String chat(String userMessage) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║  Agentic Refactoring (LangChain4j + LM-Studio)       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Query: " + userMessage);
        System.out.println("Config: " + config);
        System.out.println();

        tools.resetToolCallCount();

        String response = assistant.chat(userMessage);

        System.out.println();
        System.out.println("  ✓ Agent completed with " + tools.getToolCallCount() + " tool calls");
        System.out.println();

        return response;
    }

    /**
     * Get the underlying assistant for advanced usage (e.g. multi-turn conversations).
     */
    public Assistant getAssistant() {
        return assistant;
    }

    /**
     * Get the tools instance (for inspection / testing).
     */
    public RefactorTools getTools() {
        return tools;
    }

    // ═══════════════════════════════════════════════════════════════
    // Chat model builder
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build an OpenAI-compatible chat model configured for LM-Studio.
     *
     * <p>Uses the same endpoint and sampling parameters from {@link RefactorConfig}.
     * The base URL is derived from the chat URL by stripping the path.
     */
    private static ChatLanguageModel buildChatModel(RefactorConfig config) {
        // Extract base URL from the chat completions URL
        // e.g. "http://localhost:1234/v1/chat/completions" → "http://localhost:1234/v1"
        String chatUrl = config.getChatUrl();
        String baseUrl = chatUrl;
        if (chatUrl.endsWith("/chat/completions")) {
            baseUrl = chatUrl.substring(0, chatUrl.length() - "/chat/completions".length());
        } else if (chatUrl.contains("/v1/")) {
            baseUrl = chatUrl.substring(0, chatUrl.indexOf("/v1/") + 4);
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey("lm-studio")  // LM-Studio doesn't validate API keys
                .temperature(config.getTemperature())
                .topP(config.getTopP())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofMinutes(5))
                .logRequests(false)
                .logResponses(false);

        // Set model name if specified
        String modelName = config.getChatModel();
        if (modelName != null && !modelName.isEmpty()) {
            builder.modelName(modelName);
        } else {
            // LM-Studio uses whatever model is loaded; pass a placeholder
            builder.modelName("local-model");
        }

        return builder.build();
    }
}

