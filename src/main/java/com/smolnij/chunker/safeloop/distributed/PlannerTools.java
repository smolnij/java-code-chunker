package com.smolnij.chunker.safeloop.distributed;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.RetrievalResult;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

/**
 * LangChain4j tool provider for the Planner–Analyzer agent.
 *
 * <p>The Planner runs on S_ANALYZE_MACHINE and has full authority over the
 * refactoring process. It decides what to retrieve, when to invoke the
 * Generator, and whether the result is safe.
 *
 * <h3>Available tools:</h3>
 * <ul>
 *   <li>{@link #retrieveCode(String, int)} — Hybrid graph+vector retrieval with depth control</li>
 *   <li>{@link #retrieveCodeById(String, int)} — Targeted graph expansion from a specific method</li>
 *   <li>{@link #getMethodCallers(String)} — Impact analysis: find all callers of a method</li>
 *   <li>{@link #getMethodCallees(String)} — Dependency analysis: find all callees of a method</li>
 *   <li>{@link #refactorCode(String)} — Delegate refactoring to the Generator (REFACTOR_MACHINE)</li>
 * </ul>
 *
 * <h3>Architecture:</h3>
 * <pre>
 *   🟩 Planner–Analyzer (S_ANALYZE_MACHINE)
 *     │
 *     ├── retrieveCode()         → local graph + embeddings
 *     ├── retrieveCodeById()     → local graph expansion
 *     ├── getMethodCallers()     → local graph traversal
 *     ├── getMethodCallees()     → local graph traversal
 *     └── refactorCode()         → remote call to 🟦 Generator (REFACTOR_MACHINE)
 * </pre>
 */
public class PlannerTools {

    private final HybridRetriever retriever;
    private final Neo4jGraphReader graphReader;
    private final ChatService generatorChat;

    /** Cap on chunks per retrieval (prevents context overflow). */
    private final int maxChunksPerRetrieval;

    /** Maximum graph depth for retrieval. */
    private final int maxRetrievalDepth;

    /** Print full prompts and responses when true. */
    private final boolean trace;

    /** Track tool calls for logging / safety caps. */
    private int toolCallCount = 0;

    /** Track all retrieved node IDs across all invocations. */
    private final Set<String> retrievedNodeIds = new LinkedHashSet<>();

    /** Last refactoring result from the Generator. */
    private String lastRefactoringResult = "";

    /** Count of refactorCode invocations. */
    private int refactorCallCount = 0;

    // ═══════════════════════════════════════════════════════════════
    // Generator system prompt — used when delegating to REFACTOR_MACHINE
    // ═══════════════════════════════════════════════════════════════

    private static final String GENERATOR_SYSTEM_PROMPT = """
        You are a senior Java engineer.

        Goal:
        Refactor code exactly as instructed by the planner.

        Rules:
        - Do not assume missing dependencies
        - If unsure, state assumptions
        - Keep changes minimal and correct
        - You do NOT decide what to refactor — the planner tells you.
        - You do NOT retrieve context — the planner provides it.

        Response format:
        Produce your response as structured JSON:
        {
          "refactored_code": "... the complete refactored code ...",
          "changes": [
            { "file": "ClassName.java", "method": "methodName", "description": "what changed" }
          ],
          "explanation": "Brief explanation of what changed and why",
          "assumptions": ["assumption 1", "assumption 2"],
          "breaking_changes": ["breaking change 1", "breaking change 2"]
        }

        If there are no breaking changes, use an empty array: "breaking_changes": []
        If there are no assumptions, use an empty array: "assumptions": []
        """;

    public PlannerTools(HybridRetriever retriever,
                        Neo4jGraphReader graphReader,
                        ChatService generatorChat,
                        int maxChunksPerRetrieval,
                        int maxRetrievalDepth) {
        this(retriever, graphReader, generatorChat, maxChunksPerRetrieval, maxRetrievalDepth, false);
    }

    public PlannerTools(HybridRetriever retriever,
                        Neo4jGraphReader graphReader,
                        ChatService generatorChat,
                        int maxChunksPerRetrieval,
                        int maxRetrievalDepth,
                        boolean trace) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.generatorChat = generatorChat;
        this.maxChunksPerRetrieval = maxChunksPerRetrieval;
        this.maxRetrievalDepth = maxRetrievalDepth;
        this.trace = trace;
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 1: Hybrid retrieval by natural-language query
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Search the codebase using hybrid graph + vector retrieval.
        Use this to find relevant methods before planning a refactoring.
        The query should describe what you're looking for in natural language.
        The depth parameter controls how many hops of graph neighbours to include (1-2).
        Returns method names, signatures, code, and call-graph relationships.
        ALWAYS use this before asking the generator to refactor anything.
        """)
    public String retrieveCode(
            @P("Natural language query, e.g. 'UserService createUser dependencies'")
            String query,
            @P("Graph expansion depth: 1 = direct dependencies, 2 = two hops")
            int depth) {

        toolCallCount++;
        depth = Math.max(1, Math.min(depth, maxRetrievalDepth));
        System.out.println("  🔧 Planner tool #" + toolCallCount
            + ": retrieveCode(\"" + query + "\", depth=" + depth + ")");

        try {
            HybridRetriever.RetrievalResponse response = retriever.retrieve(query);
            List<RetrievalResult> results = response.getResults();

            if (results.isEmpty()) {
                return "No results found for query: " + query;
            }

            int limit = Math.min(results.size(), maxChunksPerRetrieval);
            List<RetrievalResult> capped = results.subList(0, limit);

            // Track retrieved IDs
            for (RetrievalResult r : capped) {
                retrievedNodeIds.add(r.getChunkId());
            }

            // If depth > 1, expand the graph for the best match
            StringBuilder sb = new StringBuilder();
            sb.append(formatResults(capped, query));

            if (depth >= 2 && !capped.isEmpty()) {
                String anchorId = capped.get(0).getChunkId();
                String expansion = expandGraph(anchorId, depth);
                if (!expansion.isEmpty()) {
                    sb.append("\n").append(expansion);
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error retrieving code: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 2: Targeted retrieval by method ID + graph depth
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Retrieve a specific method and its graph neighbourhood by method identifier.
        Use this when you know the exact method you need, e.g. "UserService#createUser".
        The depth parameter controls how many hops of dependencies to include.
        Returns the method code plus its connected methods up to the specified depth.
        """)
    public String retrieveCodeById(
            @P("Method identifier, e.g. 'UserService#createUser' or 'createUser'")
            String methodId,
            @P("Graph expansion depth: 1 = direct dependencies, 2 = two hops")
            int depth) {

        toolCallCount++;
        depth = Math.max(1, Math.min(depth, maxRetrievalDepth));
        System.out.println("  🔧 Planner tool #" + toolCallCount
            + ": retrieveCodeById(\"" + methodId + "\", depth=" + depth + ")");

        try {
            String resolvedId = resolveMethodId(methodId);
            if (resolvedId == null) {
                return "Method not found: " + methodId
                    + "\nTry using retrieveCode() with a descriptive query instead.";
            }

            return expandGraph(resolvedId, depth);
        } catch (Exception e) {
            return "Error retrieving method: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 3: Get callers of a method (impact analysis)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Find all methods that call a given method (incoming edges in the call graph).
        Use this for impact analysis — understand who depends on a method before
        changing its signature or behavior. ALWAYS call this before refactoring.
        """)
    public String getMethodCallers(
            @P("Method identifier, e.g. 'createUser' or 'UserService#createUser'")
            String methodId) {

        toolCallCount++;
        System.out.println("  🔧 Planner tool #" + toolCallCount
            + ": getMethodCallers(\"" + methodId + "\")");

        try {
            String resolvedId = resolveMethodId(methodId);
            if (resolvedId == null) {
                return "Method not found: " + methodId;
            }

            Map<String, Integer> subgraph = graphReader.expandSubgraph(resolvedId, 1);
            Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(subgraph.keySet());

            CodeChunk targetChunk = chunks.get(resolvedId);
            if (targetChunk == null) {
                return "Could not load method code for: " + resolvedId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Callers of ").append(resolvedId).append(" ===\n\n");

            int callerCount = graphReader.getCallerCount(resolvedId);
            sb.append("Total caller count: ").append(callerCount).append("\n\n");

            sb.append("Target method:\n");
            sb.append("  ").append(targetChunk.getMethodSignature()).append("\n");
            if (!targetChunk.getCalledBy().isEmpty()) {
                sb.append("  Called by:\n");
                for (String caller : targetChunk.getCalledBy()) {
                    sb.append("    - ").append(caller).append("\n");
                }
            }
            sb.append("\n");

            int shown = 0;
            for (Map.Entry<String, CodeChunk> entry : chunks.entrySet()) {
                if (entry.getKey().equals(resolvedId)) continue;
                if (shown >= maxChunksPerRetrieval) break;

                CodeChunk c = entry.getValue();
                retrievedNodeIds.add(entry.getKey());
                sb.append("── Caller: ").append(c.getClassName()).append(".")
                    .append(c.getMethodName()).append(" ──\n");
                sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
                sb.append("```java\n").append(c.getCode()).append("\n```\n\n");
                shown++;
            }

            if (shown == 0) {
                sb.append("(No caller code available in the graph)\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error finding callers: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 4: Get callees of a method (dependency analysis)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Find all methods that a given method calls (outgoing edges in the call graph).
        Use this for dependency analysis — understand what a method depends on before
        refactoring its body. ALWAYS call this before refactoring.
        """)
    public String getMethodCallees(
            @P("Method identifier, e.g. 'createUser' or 'UserService#createUser'")
            String methodId) {

        toolCallCount++;
        System.out.println("  🔧 Planner tool #" + toolCallCount
            + ": getMethodCallees(\"" + methodId + "\")");

        try {
            String resolvedId = resolveMethodId(methodId);
            if (resolvedId == null) {
                return "Method not found: " + methodId;
            }

            Map<String, CodeChunk> targetMap = graphReader.fetchMethodChunks(Set.of(resolvedId));
            CodeChunk targetChunk = targetMap.get(resolvedId);
            if (targetChunk == null) {
                return "Could not load method code for: " + resolvedId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Callees of ").append(resolvedId).append(" ===\n\n");
            sb.append("Target method:\n");
            sb.append("  ").append(targetChunk.getMethodSignature()).append("\n\n");

            if (targetChunk.getCalls().isEmpty()) {
                sb.append("This method does not call any other indexed methods.\n");
                return sb.toString();
            }

            sb.append("Calls:\n");
            for (String callee : targetChunk.getCalls()) {
                sb.append("  - ").append(callee).append("\n");
            }
            sb.append("\n");

            Set<String> calleeIds = new LinkedHashSet<>();
            for (String callee : targetChunk.getCalls()) {
                String found = resolveMethodId(callee);
                if (found != null) calleeIds.add(found);
                if (calleeIds.size() >= maxChunksPerRetrieval) break;
            }

            if (!calleeIds.isEmpty()) {
                Map<String, CodeChunk> calleeChunks = graphReader.fetchMethodChunks(calleeIds);
                for (Map.Entry<String, CodeChunk> entry : calleeChunks.entrySet()) {
                    CodeChunk c = entry.getValue();
                    retrievedNodeIds.add(entry.getKey());
                    sb.append("── Callee: ").append(c.getClassName()).append(".")
                        .append(c.getMethodName()).append(" ──\n");
                    sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
                    sb.append("```java\n").append(c.getCode()).append("\n```\n\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error finding callees: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 5: Refactor code (delegates to Generator on REFACTOR_MACHINE)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Delegate a refactoring task to the Generator (REFACTOR_MACHINE).
        The prompt MUST include all code context the generator needs — it cannot
        retrieve anything on its own. Include the original code, all callers,
        all callees, and shared state dependencies in the prompt.
        The generator will return structured JSON with refactored code.
        NEVER call this without first retrieving ALL necessary context.
        """)
    public String refactorCode(
            @P("Complete refactoring prompt including ALL code context. The generator has no retrieval tools.")
            String prompt) {

        toolCallCount++;
        refactorCallCount++;
        System.out.println("  🔧 Planner tool #" + toolCallCount
            + ": refactorCode(prompt=" + prompt.length() + " chars) [refactor #" + refactorCallCount + "]");

        try {
            if (trace) {
                System.out.println("  │ [TRACE:Generator] SYSTEM PROMPT (" + GENERATOR_SYSTEM_PROMPT.length() + " chars):");
                System.out.println(GENERATOR_SYSTEM_PROMPT.replace("\n", "\n  │   "));
                System.out.println("  │ [TRACE:Generator] USER PROMPT (" + prompt.length() + " chars):");
                System.out.println(prompt.replace("\n", "\n  │   "));
            }
            lastRefactoringResult = generatorChat.chat(GENERATOR_SYSTEM_PROMPT, prompt);
            System.out.println("  ← Generator responded (" + lastRefactoringResult.length() + " chars)");
            if (trace) {
                System.out.println("  │ [TRACE:Generator] FULL RESPONSE (" + lastRefactoringResult.length() + " chars):");
                System.out.println(lastRefactoringResult.replace("\n", "\n  │   "));
            }
            return lastRefactoringResult;
        } catch (Exception e) {
            return "Error calling generator: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // State accessors
    // ═══════════════════════════════════════════════════════════════

    public int getToolCallCount() { return toolCallCount; }
    public void resetToolCallCount() { this.toolCallCount = 0; }
    public int getRefactorCallCount() { return refactorCallCount; }
    public Set<String> getRetrievedNodeIds() { return Collections.unmodifiableSet(retrievedNodeIds); }
    public int getTotalNodesRetrieved() { return retrievedNodeIds.size(); }
    public String getLastRefactoringResult() { return lastRefactoringResult; }

    // ═══════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Expand the graph from an anchor node and return formatted context.
     */
    private String expandGraph(String anchorId, int depth) {
        Map<String, Integer> subgraph = graphReader.expandSubgraph(anchorId, depth);

        // Also include same-class siblings at depth 1
        List<String> siblings = graphReader.getSameClassMethods(anchorId);
        for (String sibling : siblings) {
            subgraph.putIfAbsent(sibling, 1);
        }

        // Cap and hydrate
        int limit = Math.min(subgraph.size(), maxChunksPerRetrieval);
        Set<String> idsToFetch = new LinkedHashSet<>();
        idsToFetch.add(anchorId);
        subgraph.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .filter(id -> !id.equals(anchorId))
            .limit(limit - 1)
            .forEach(idsToFetch::add);

        Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(idsToFetch);
        if (chunks.isEmpty()) {
            return "Method found (" + anchorId + ") but could not load code.";
        }

        // Track
        retrievedNodeIds.addAll(chunks.keySet());

        return formatChunks(chunks, subgraph, anchorId);
    }

    /**
     * Resolve a method identifier to its graph node ID.
     */
    private String resolveMethodId(String methodId) {
        if (methodId == null || methodId.isBlank()) return null;

        String resolved = graphReader.findMethodExact(methodId);
        if (resolved != null) return resolved;

        // Strip to simple name
        String simpleName = methodId;
        if (simpleName.contains("#")) {
            simpleName = simpleName.substring(simpleName.indexOf('#') + 1);
        } else if (simpleName.contains(".")) {
            simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
        }
        int parenIdx = simpleName.indexOf('(');
        if (parenIdx >= 0) {
            simpleName = simpleName.substring(0, parenIdx);
        }

        return graphReader.findMethodExact(simpleName);
    }

    /**
     * Format hybrid retrieval results as LLM-friendly text.
     */
    private String formatResults(List<RetrievalResult> results, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Retrieved Code for: \"").append(query).append("\" ===\n");
        sb.append("Found ").append(results.size()).append(" relevant methods:\n\n");

        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            CodeChunk c = r.getChunk();

            sb.append("── Method ").append(i + 1).append(" of ").append(results.size());
            if (r.isAnchor()) sb.append(" (PRIMARY TARGET)");
            sb.append(" ──\n");

            sb.append("Name: ").append(c.getClassName()).append(".").append(c.getMethodName()).append("\n");
            sb.append("File: ").append(c.getFilePath()).append("\n");
            sb.append("Signature: ").append(c.getMethodSignature()).append("\n");

            if (!c.getMethodAnnotations().isEmpty()) {
                sb.append("Annotations: ").append(String.join(", ", c.getMethodAnnotations())).append("\n");
            }
            if (!c.getCalls().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", c.getCalls())).append("\n");
            }
            if (!c.getCalledBy().isEmpty()) {
                sb.append("Called by: ").append(String.join(", ", c.getCalledBy())).append("\n");
            }

            sb.append("```java\n");
            sb.append(c.getCode()).append("\n");
            sb.append("```\n\n");
        }

        return sb.toString();
    }

    /**
     * Format graph-expanded chunks as LLM-friendly text.
     */
    private String formatChunks(Map<String, CodeChunk> chunks,
                                Map<String, Integer> subgraph,
                                String anchorId) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Graph Expansion from: ").append(anchorId).append(" ===\n");
        sb.append("Retrieved ").append(chunks.size()).append(" methods:\n\n");

        for (Map.Entry<String, CodeChunk> entry : chunks.entrySet()) {
            String id = entry.getKey();
            CodeChunk c = entry.getValue();
            int distance = subgraph.getOrDefault(id, -1);

            sb.append("── ").append(c.getClassName()).append(".").append(c.getMethodName());
            if (id.equals(anchorId)) {
                sb.append(" (ANCHOR)");
            } else {
                sb.append(" (").append(distance).append(" hop").append(distance != 1 ? "s" : "").append(")");
            }
            sb.append(" ──\n");

            sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
            if (!c.getCalls().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", c.getCalls())).append("\n");
            }
            if (!c.getCalledBy().isEmpty()) {
                sb.append("Called by: ").append(String.join(", ", c.getCalledBy())).append("\n");
            }

            sb.append("```java\n");
            sb.append(c.getCode()).append("\n");
            sb.append("```\n\n");
        }

        return sb.toString();
    }
}

