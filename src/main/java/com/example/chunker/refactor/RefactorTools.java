package com.example.chunker.refactor;

import com.example.chunker.model.CodeChunk;
import com.example.chunker.retrieval.HybridRetriever;
import com.example.chunker.retrieval.Neo4jGraphReader;
import com.example.chunker.retrieval.RetrievalResult;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

/**
 * LangChain4j tool provider for the agentic refactoring loop.
 *
 * <p>These {@link Tool @Tool}-annotated methods are exposed to the LLM
 * via LangChain4j's function-calling mechanism. The LLM can invoke them
 * autonomously to gather code context before suggesting refactorings.
 *
 * <h3>Available tools:</h3>
 * <ul>
 *   <li>{@link #retrieveCode(String)} — hybrid graph+vector search by natural-language query</li>
 *   <li>{@link #retrieveCodeById(String, int)} — targeted graph expansion from a specific method</li>
 *   <li>{@link #getMethodCallers(String)} — find all callers of a method</li>
 *   <li>{@link #getMethodCallees(String)} — find all methods called by a method</li>
 * </ul>
 *
 * <p>All tools return LLM-friendly text (not raw objects) containing
 * method names, code, and call-graph relationships.
 */
public class RefactorTools {

    private final HybridRetriever retriever;
    private final Neo4jGraphReader graphReader;

    /** Cap on how many chunks to include per retrieval call (prevents context overflow). */
    private final int maxChunksPerCall;

    /** Track how many tool calls have been made (for logging / safety caps). */
    private int toolCallCount = 0;

    public RefactorTools(HybridRetriever retriever,
                         Neo4jGraphReader graphReader,
                         int maxChunksPerCall) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.maxChunksPerCall = maxChunksPerCall;
    }

    public RefactorTools(HybridRetriever retriever,
                         Neo4jGraphReader graphReader) {
        this(retriever, graphReader, 5);
    }

    public int getToolCallCount() {
        return toolCallCount;
    }

    public void resetToolCallCount() {
        this.toolCallCount = 0;
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 1: Hybrid retrieval by natural-language query
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Search the codebase using hybrid graph + vector retrieval.
        Use this when you need to find relevant methods for a refactoring task.
        The query should describe what you're looking for in natural language,
        e.g. "UserService createUser dependencies" or "methods that validate user input".
        Returns method names, code, and call-graph relationships.
        """)
    public String retrieveCode(
            @P("Natural language query describing the code you need, e.g. 'UserService createUser dependencies'")
            String query) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount + ": retrieveCode(\"" + query + "\")");

        try {
            HybridRetriever.RetrievalResponse response = retriever.retrieve(query);
            List<RetrievalResult> results = response.getResults();

            if (results.isEmpty()) {
                return "No results found for query: " + query;
            }

            // Cap results to avoid blowing up context
            int limit = Math.min(results.size(), maxChunksPerCall);
            return formatResults(results.subList(0, limit), query);
        } catch (Exception e) {
            return "Error retrieving code: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 2: Targeted retrieval by method ID + graph depth
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Retrieve a specific method and its graph neighbourhood by method identifier.
        Use this when you know the exact method you need, e.g. "UserService#createUser"
        or just "createUser". The depth parameter controls how many hops of
        dependencies to include (1 = direct callers/callees, 2 = two hops, etc.).
        Returns the method code plus its connected methods up to the specified depth.
        """)
    public String retrieveCodeById(
            @P("Method identifier, e.g. 'UserService#createUser', 'createUser', or 'com.example.UserService#createUser(User)'")
            String methodId,
            @P("Graph expansion depth: 1 = direct dependencies only, 2 = two hops, 3 = three hops")
            int depth) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": retrieveCodeById(\"" + methodId + "\", depth=" + depth + ")");

        try {
            // Clamp depth to a safe range
            depth = Math.max(1, Math.min(depth, 3));

            // Resolve the method ID
            String resolvedId = graphReader.findMethodExact(methodId);
            if (resolvedId == null) {
                // Try just the method name part (strip class prefix)
                String simpleName = methodId.contains(".")
                        ? methodId.substring(methodId.lastIndexOf('.') + 1)
                        : methodId.contains("#")
                        ? methodId.substring(methodId.indexOf('#') + 1)
                        : methodId;
                // Strip parameters if present
                int parenIdx = simpleName.indexOf('(');
                if (parenIdx >= 0) simpleName = simpleName.substring(0, parenIdx);

                resolvedId = graphReader.findMethodExact(simpleName);
            }

            if (resolvedId == null) {
                return "Method not found: " + methodId
                        + "\nTry using retrieveCode() with a descriptive query instead.";
            }

            // Make effectively final for use in lambda
            final String anchorId = resolvedId;

            // Expand subgraph from the resolved anchor
            Map<String, Integer> subgraph = graphReader.expandSubgraph(anchorId, depth);

            // Also include same-class siblings at depth 1
            List<String> siblings = graphReader.getSameClassMethods(anchorId);
            for (String sibling : siblings) {
                subgraph.putIfAbsent(sibling, 1);
            }

            // Hydrate chunks
            int limit = Math.min(subgraph.size(), maxChunksPerCall);
            Set<String> idsToFetch = new LinkedHashSet<>();
            // Always include the anchor first
            idsToFetch.add(anchorId);
            // Then add remaining by distance
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

            return formatChunks(chunks, subgraph, anchorId);
        } catch (Exception e) {
            return "Error retrieving method: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 3: Get callers of a method
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Find all methods that call a given method (incoming edges in the call graph).
        Use this to understand the impact of changing a method — who depends on it?
        Returns caller method names, their code, and relationships.
        """)
    public String getMethodCallers(
            @P("Method identifier, e.g. 'createUser' or 'UserService#createUser'")
            String methodId) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": getMethodCallers(\"" + methodId + "\")");

        try {
            String resolvedId = graphReader.findMethodExact(methodId);
            if (resolvedId == null) {
                String simpleName = stripToSimpleName(methodId);
                resolvedId = graphReader.findMethodExact(simpleName);
            }
            if (resolvedId == null) {
                return "Method not found: " + methodId;
            }

            // Expand 1 hop to get direct callers
            Map<String, Integer> subgraph = graphReader.expandSubgraph(resolvedId, 1);

            // Fetch the anchor + its neighbours
            Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(subgraph.keySet());

            // Filter to only callers (methods that have the target in their calls list)
            CodeChunk targetChunk = chunks.get(resolvedId);
            if (targetChunk == null) {
                return "Could not load method code for: " + resolvedId;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Callers of ").append(resolvedId).append(" ===\n\n");

            int callerCount = graphReader.getCallerCount(resolvedId);
            sb.append("Total caller count: ").append(callerCount).append("\n\n");

            // Show the target method signature
            sb.append("Target method:\n");
            sb.append("  ").append(targetChunk.getMethodSignature()).append("\n");
            if (!targetChunk.getCalledBy().isEmpty()) {
                sb.append("  Called by:\n");
                for (String caller : targetChunk.getCalledBy()) {
                    sb.append("    - ").append(caller).append("\n");
                }
            }
            sb.append("\n");

            // Show caller code
            int shown = 0;
            for (Map.Entry<String, CodeChunk> entry : chunks.entrySet()) {
                if (entry.getKey().equals(resolvedId)) continue;
                if (shown >= maxChunksPerCall) break;

                CodeChunk c = entry.getValue();
                sb.append("── Caller: ").append(c.getClassName()).append(".")
                        .append(c.getMethodName()).append(" ──\n");
                sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
                sb.append("```java\n").append(c.getCode()).append("\n```\n\n");
                shown++;
            }

            if (shown == 0) {
                sb.append("(No caller code available in the graph — callers may be outside indexed scope)\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error finding callers: " + e.getMessage();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 4: Get callees of a method
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Find all methods that a given method calls (outgoing edges in the call graph).
        Use this to understand what dependencies a method has before refactoring it.
        Returns callee method names, their code, and relationships.
        """)
    public String getMethodCallees(
            @P("Method identifier, e.g. 'createUser' or 'UserService#createUser'")
            String methodId) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": getMethodCallees(\"" + methodId + "\")");

        try {
            String resolvedId = graphReader.findMethodExact(methodId);
            if (resolvedId == null) {
                String simpleName = stripToSimpleName(methodId);
                resolvedId = graphReader.findMethodExact(simpleName);
            }
            if (resolvedId == null) {
                return "Method not found: " + methodId;
            }

            // Fetch the target method
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

            // Try to fetch code for each callee
            Set<String> calleeIds = new LinkedHashSet<>();
            for (String callee : targetChunk.getCalls()) {
                String found = graphReader.findMethodExact(callee);
                if (found == null) {
                    String simpleName = stripToSimpleName(callee);
                    found = graphReader.findMethodExact(simpleName);
                }
                if (found != null) calleeIds.add(found);
                if (calleeIds.size() >= maxChunksPerCall) break;
            }

            if (!calleeIds.isEmpty()) {
                Map<String, CodeChunk> calleeChunks = graphReader.fetchMethodChunks(calleeIds);
                for (CodeChunk c : calleeChunks.values()) {
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
    // Formatting helpers
    // ═══════════════════════════════════════════════════════════════

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

            // Call-graph edges
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

    /**
     * Strip a fully-qualified method reference to its simple name.
     * "com.example.UserService#createUser(User)" → "createUser"
     */
    private String stripToSimpleName(String ref) {
        // Strip class prefix
        String name = ref;
        if (name.contains("#")) {
            name = name.substring(name.indexOf('#') + 1);
        } else if (name.contains(".")) {
            name = name.substring(name.lastIndexOf('.') + 1);
        }
        // Strip parameters
        int parenIdx = name.indexOf('(');
        if (parenIdx >= 0) {
            name = name.substring(0, parenIdx);
        }
        return name;
    }
}

