package com.smolnij.chunker.refactor;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.retrieval.GraphPath;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;
import com.smolnij.chunker.retrieval.PathEdge;
import com.smolnij.chunker.retrieval.RetrievalResult;
import com.smolnij.chunker.retrieval.SubgraphView;

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

    // In-memory storage for last ingested structured self-review (JSON) and hydrated context
    private volatile String lastSelfReviewJson = "{}";
    private volatile String lastSelfReviewContext = "";

    public RefactorTools(HybridRetriever retriever,
                         Neo4jGraphReader graphReader,
                         int maxChunksPerCall) {
        this.retriever = retriever;
        this.graphReader = graphReader;
        this.maxChunksPerCall = maxChunksPerCall;
    }

    /**
     * Ingest a structured self-review JSON produced by a low-temperature reviewer.
     * This method is intended to be called by the harness (SafeRefactorLoop) to
     * store a machine-readable review for later retrieval by the agent via
     * {@link #fetchSelfReview()} and {@link #fetchSelfReviewContext()}.
     *
     * Note: deliberately NOT annotated with @Tool to prevent the agent from
     * overwriting the stored payload.
     */
    public String ingestSelfReview(String selfReviewJson) {
        if (selfReviewJson == null) selfReviewJson = "{}";
        this.lastSelfReviewJson = selfReviewJson;
        return "INGESTED";
    }

    /** Store hydrated context text associated with the last self-review. */
    public void storeSelfReviewContext(String ctx) {
        this.lastSelfReviewContext = ctx == null ? "" : ctx;
    }

    /** Return the last ingested self-review JSON (tool available to the agent). */
    @Tool("Fetch the last structured self-review the harness stored. Returns a JSON string matching the self_review_response schema.")
    public String fetchSelfReview() {
        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount + ": fetchSelfReview()");
        return logToolReturn(lastSelfReviewJson == null ? "{}" : lastSelfReviewJson);
    }

    /** Return the hydrated context (code bodies) associated with the last self-review, if any. */
    @Tool("Fetch additional context that was retrieved by the harness for the last self-review (code snippets).")
    public String fetchSelfReviewContext() {
        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount + ": fetchSelfReviewContext()");
        return logToolReturn(lastSelfReviewContext == null ? "" : lastSelfReviewContext);
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

    /**
     * Log the size and terse status of a tool result, then return it unchanged.
     * Called right before each @Tool return so worklogs show what the LLM saw,
     * not just which tool was invoked.
     */
    private String logToolReturn(String result) {
        int chars = result == null ? 0 : result.length();
        String status;
        if (result == null || result.isEmpty()) {
            status = "[empty]";
        } else if (result.startsWith("Method not found")
                || result.startsWith("Source method not found")
                || result.startsWith("Target method not found")
                || result.startsWith("No results found")
                || result.startsWith("Invalid direction")
                || result.startsWith("Could not load")) {
            status = "[not-found]";
        } else if (result.startsWith("Error ")) {
            status = "[error]";
        } else {
            status = "[ok]";
        }
        System.out.println("    └─ " + status + " (" + chars + " chars)");
        return result;
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
                return logToolReturn("No results found for query: " + query);
            }

            // Cap results to avoid blowing up context
            int limit = Math.min(results.size(), maxChunksPerCall);
            return logToolReturn(formatResults(results.subList(0, limit), query));
        } catch (Exception e) {
            return logToolReturn("Error retrieving code: " + e.getMessage());
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
                return logToolReturn("Method not found: " + methodId
                        + "\nTry using retrieveCode() with a descriptive query instead.");
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
                return logToolReturn("Method found (" + anchorId + ") but could not load code.");
            }

            System.out.println("    [retrieveCodeById] anchor='" + anchorId
                    + "' subgraphSize=" + subgraph.size() + " hydrated=" + chunks.size());
            return logToolReturn(formatChunks(chunks, subgraph, anchorId));
        } catch (Exception e) {
            return logToolReturn("Error retrieving method: " + e.getMessage());
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
                return logToolReturn("Method not found: " + methodId);
            }

            // Expand 1 hop to get direct callers
            Map<String, Integer> subgraph = graphReader.expandSubgraph(resolvedId, 1);

            // Fetch the anchor + its neighbours
            Map<String, CodeChunk> chunks = graphReader.fetchMethodChunks(subgraph.keySet());

            // Filter to only callers (methods that have the target in their calls list)
            CodeChunk targetChunk = chunks.get(resolvedId);
            if (targetChunk == null) {
                return logToolReturn("Could not load method code for: " + resolvedId);
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

            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error finding callers: " + e.getMessage());
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
                return logToolReturn("Method not found: " + methodId);
            }

            // Fetch the target method
            Map<String, CodeChunk> targetMap = graphReader.fetchMethodChunks(Set.of(resolvedId));
            CodeChunk targetChunk = targetMap.get(resolvedId);
            if (targetChunk == null) {
                return logToolReturn("Could not load method code for: " + resolvedId);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Callees of ").append(resolvedId).append(" ===\n\n");

            sb.append("Target method:\n");
            sb.append("  ").append(targetChunk.getMethodSignature()).append("\n\n");

            if (targetChunk.getCalls().isEmpty()) {
                sb.append("This method does not call any other indexed methods.\n");
                return logToolReturn(sb.toString());
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

            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error finding callees: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 5: Find call path between two methods
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Find the shortest call path(s) between two methods.
        Returns a chain like A -[CALLS]-> B -[CALLS]-> C showing how one method reaches another.
        Falls back to an undirected traversal (mixing CALLS and CALLED_BY) when no purely
        directed call path exists within the hop budget.
        Use this to explain transitive dependencies.
        """)
    public String findCallPath(
            @P("Source method id or name, e.g. 'UserService#createUser'")
            String fromMethod,
            @P("Target method id or name, e.g. 'Repository#save'")
            String toMethod,
            @P("Max path length in hops (1-6)")
            int maxDepth) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": findCallPath(\"" + fromMethod + "\" → \"" + toMethod + "\", depth=" + maxDepth + ")");

        try {
            String fromId = resolveMethodId(fromMethod);
            if (fromId == null) return logToolReturn("Source method not found: " + fromMethod);
            String toId = resolveMethodId(toMethod);
            if (toId == null) return logToolReturn("Target method not found: " + toMethod);

            int clamped = Math.max(1, Math.min(maxDepth, 6));

            List<GraphPath> paths = graphReader.findShortestCallPaths(fromId, toId, clamped);
            boolean undirectedFallback = false;
            if (paths.isEmpty()) {
                paths = graphReader.findUndirectedPaths(fromId, toId, clamped);
                undirectedFallback = true;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Call path: ").append(fromId).append(" → ").append(toId).append(" ===\n");

            if (paths.isEmpty()) {
                sb.append("No path found within ").append(clamped).append(" hops (directed or undirected).\n");
                return logToolReturn(sb.toString());
            }
            if (undirectedFallback) {
                sb.append("(no directed path within ").append(clamped)
                        .append(" hops; showing undirected fallback using CALLS + CALLED_BY)\n");
            }
            sb.append("\n");

            for (int i = 0; i < paths.size(); i++) {
                GraphPath p = paths.get(i);
                sb.append("Path ").append(i + 1).append(" (length ").append(p.length()).append("):\n");
                sb.append("  ").append(p.render()).append("\n\n");
            }

            // Batch-load code for nodes on the first path, capped at maxChunksPerCall.
            GraphPath first = paths.get(0);
            List<String> nodeIds = first.getNodes();
            int bodyLimit = Math.min(nodeIds.size(), maxChunksPerCall);
            Set<String> toFetch = new LinkedHashSet<>(nodeIds.subList(0, bodyLimit));
            Map<String, CodeChunk> bodies = graphReader.fetchMethodChunks(toFetch);

            for (int i = 0; i < bodyLimit; i++) {
                String id = nodeIds.get(i);
                CodeChunk c = bodies.get(id);
                if (c == null) continue;
                sb.append("── Step ").append(i + 1).append(": ").append(id).append(" ──\n");
                sb.append("Signature: ").append(c.getMethodSignature()).append("\n");
                sb.append("```java\n").append(c.getCode()).append("\n```\n\n");
            }
            if (nodeIds.size() > bodyLimit) {
                sb.append("(").append(nodeIds.size() - bodyLimit).append(" further nodes on the path; code omitted)\n");
            }
            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error finding call path: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 6: Trace a transitive call chain (callers or callees)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Walk the call graph from a method following callers (incoming) or callees (outgoing).
        Returns the chain as an indented tree of method ids (no code bodies).
        Use this to understand the full transitive reach in one direction.
        """)
    public String traceCallChain(
            @P("Method id or name")
            String methodId,
            @P("'callers' (who calls this) or 'callees' (who this calls)")
            String direction,
            @P("Max traversal depth 1-4")
            int maxDepth) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": traceCallChain(\"" + methodId + "\", " + direction + ", depth=" + maxDepth + ")");

        try {
            String resolvedId = resolveMethodId(methodId);
            if (resolvedId == null) return logToolReturn("Method not found: " + methodId);

            boolean callers;
            if ("callers".equalsIgnoreCase(direction)) callers = true;
            else if ("callees".equalsIgnoreCase(direction)) callers = false;
            else return logToolReturn("Invalid direction '" + direction + "'. Use 'callers' or 'callees'.");

            int clamped = Math.max(1, Math.min(maxDepth, 4));

            StringBuilder sb = new StringBuilder();
            sb.append("=== Call chain (").append(callers ? "callers" : "callees")
                    .append(") from ").append(resolvedId).append(" ===\n");
            sb.append(resolvedId).append("\n");

            Set<String> visited = new HashSet<>();
            visited.add(resolvedId);
            int printed = traverseChain(resolvedId, callers, 1, clamped, visited, sb, 0);
            if (printed == 0) {
                sb.append("  (no ").append(callers ? "callers" : "callees").append(" found)\n");
            }
            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error tracing call chain: " + e.getMessage());
        }
    }

    private int traverseChain(String currentId, boolean callers, int depth, int maxDepth,
                               Set<String> visited, StringBuilder sb, int printedSoFar) {
        if (depth > maxDepth) return printedSoFar;
        List<PathEdge> edges = graphReader.getImmediateNeighbors(currentId);
        int printed = printedSoFar;
        for (PathEdge e : edges) {
            boolean isCallerEdge = e.getDirection() == PathEdge.Direction.IN;
            if (callers != isCallerEdge) continue;
            String neighbor = callers ? e.getSourceId() : e.getTargetId();
            if (!visited.add(neighbor)) continue;
            sb.append("  ".repeat(depth))
                    .append(callers ? "← " : "→ ")
                    .append(neighbor).append("\n");
            printed++;
            printed = traverseChain(neighbor, callers, depth + 1, maxDepth, visited, sb, printed);
        }
        return printed;
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 7: Subgraph topology around a method (no code bodies)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Return the call-graph topology (nodes + directed CALLS edges) around a method,
        without code bodies. Useful for a high-level map before zooming into specific chunks.
        """)
    public String getSubgraphTopology(
            @P("Method id or name to anchor the subgraph")
            String anchorMethodId,
            @P("Graph expansion depth 1-3")
            int depth) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": getSubgraphTopology(\"" + anchorMethodId + "\", depth=" + depth + ")");

        try {
            String resolvedId = resolveMethodId(anchorMethodId);
            if (resolvedId == null) return logToolReturn("Method not found: " + anchorMethodId);

            int clamped = Math.max(1, Math.min(depth, 3));
            Map<String, Integer> subgraph = graphReader.expandSubgraph(resolvedId, clamped);
            if (subgraph.isEmpty()) subgraph = new LinkedHashMap<>(Map.of(resolvedId, 0));
            else subgraph.putIfAbsent(resolvedId, 0);

            List<PathEdge> induced = graphReader.getInducedEdges(subgraph.keySet());
            SubgraphView view = new SubgraphView(new LinkedHashSet<>(subgraph.keySet()), induced);

            int cap = retriever.getConfig().getMaxTopologyEdges();
            StringBuilder sb = new StringBuilder();
            sb.append("=== Subgraph topology around ").append(resolvedId)
                    .append(" (depth ").append(clamped).append(") ===\n");
            sb.append(view.renderTopology(resolvedId, cap)).append("\n");
            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error computing subgraph topology: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool: class-level overview (header + fields + member signatures)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Return a class-level overview: class signature, fields, imports, and the
        signatures (no bodies) of every indexed method and constructor on that class.
        Use this BEFORE method-level retrieval when you need to see the structure of
        a class as a whole — e.g. to pick a constructor to modify, or to enumerate
        methods. Accepts an FQN ('com.example.UserService'), a simple class name
        ('UserService'), or a file path ('UserService.java').
        """)
    public String getClassOverview(
            @P("Class identifier: FQN, simple name, or file path (e.g. 'RalphLoop', 'com.example.RalphLoop', 'RalphLoop.java')")
            String classIdentifier) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount
                + ": getClassOverview(\"" + classIdentifier + "\")");

        try {
            String fqName = graphReader.findClass(classIdentifier);
            if (fqName == null) {
                return logToolReturn("Class not found: " + classIdentifier
                        + "\nTry a fully-qualified name or a different identifier.");
            }
            Neo4jGraphReader.ClassOverview ov = graphReader.getClassOverview(fqName);
            if (ov == null) {
                return logToolReturn("Class not found: " + fqName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("=== Class Overview: ").append(ov.fqName).append(" ===\n");
            sb.append("Kind: ").append(ov.kind).append("\n");
            sb.append("File: ").append(ov.filePath).append("\n");
            sb.append("Package: ").append(ov.packageName).append("\n");
            if (!ov.annotations.isEmpty()) {
                sb.append("Annotations: ").append(String.join(", ", ov.annotations)).append("\n");
            }
            if (!ov.extendedTypes.isEmpty()) {
                sb.append("Extends: ").append(String.join(", ", ov.extendedTypes)).append("\n");
            }
            if (!ov.implementedTypes.isEmpty()) {
                sb.append("Implements: ").append(String.join(", ", ov.implementedTypes)).append("\n");
            }
            if (ov.signature != null && !ov.signature.isEmpty()) {
                sb.append("Signature:\n  ").append(ov.signature).append("\n");
            }

            sb.append("\n── Imports (").append(ov.imports.size()).append(") ──\n");
            if (ov.imports.isEmpty()) {
                sb.append("  (none indexed)\n");
            } else {
                for (String imp : ov.imports) sb.append("  ").append(imp).append("\n");
            }

            sb.append("\n── Fields (").append(ov.fieldDeclarations.size()).append(") ──\n");
            if (ov.fieldDeclarations.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (String decl : ov.fieldDeclarations) sb.append("  ").append(decl).append("\n");
            }

            sb.append("\n── Methods & Constructors (").append(ov.methods.size()).append(") ──\n");
            if (ov.methods.isEmpty()) {
                sb.append("  (none indexed — class may have only filtered/boilerplate methods)\n");
            } else {
                for (Neo4jGraphReader.ClassOverview.MethodSummary m : ov.methods) {
                    sb.append("  ").append(m.signature.isEmpty() ? m.methodName : m.signature);
                    if (m.totalParts > 1) {
                        sb.append("    [body split into ").append(m.totalParts).append(" parts]");
                    }
                    sb.append("\n    id: ").append(m.chunkId).append("\n");
                }
                sb.append("\nUse retrieveCodeById(\"<id>\", depth=1) to fetch a specific method body.\n");
            }
            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error fetching class overview: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tool 8: One-hop neighbor expansion (for interactive graph walks)
    // ═══════════════════════════════════════════════════════════════

    @Tool("""
        Show only the immediate (1-hop) neighbors of a method with edge direction.
        Use this to walk the graph interactively one step at a time, following
        either outgoing calls or incoming callers.
        """)
    public String expandNode(
            @P("Method id or name")
            String methodId) {

        toolCallCount++;
        System.out.println("  🔧 Tool call #" + toolCallCount + ": expandNode(\"" + methodId + "\")");

        try {
            String resolvedId = resolveMethodId(methodId);
            if (resolvedId == null) return logToolReturn("Method not found: " + methodId);

            List<PathEdge> edges = graphReader.getImmediateNeighbors(resolvedId);
            List<PathEdge> out = new ArrayList<>();
            List<PathEdge> in = new ArrayList<>();
            for (PathEdge e : edges) {
                if (e.getDirection() == PathEdge.Direction.OUT) out.add(e);
                else in.add(e);
            }
            Collections.sort(out);
            Collections.sort(in);

            StringBuilder sb = new StringBuilder();
            sb.append("=== Neighbors of ").append(resolvedId).append(" ===\n\n");
            sb.append("Outgoing (callees):\n");
            if (out.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (PathEdge e : out) {
                    sb.append("  - ").append(e.getSourceId())
                            .append(" -[").append(e.getRelType()).append("]-> ")
                            .append(e.getTargetId()).append("\n");
                }
            }
            sb.append("\nIncoming (callers):\n");
            if (in.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (PathEdge e : in) {
                    sb.append("  - ").append(e.getSourceId())
                            .append(" -[").append(e.getRelType()).append("]-> ")
                            .append(e.getTargetId()).append("\n");
                }
            }
            return logToolReturn(sb.toString());
        } catch (Exception e) {
            return logToolReturn("Error expanding node: " + e.getMessage());
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
     * Resolve a method id or name to a canonical chunkId.
     * Falls back through stripToSimpleName like the other tools do.
     *
     * @return the resolved chunkId, or {@code null} if no match
     */
    private String resolveMethodId(String methodId) {
        if (methodId == null || methodId.isBlank()) return null;
        String resolved = graphReader.findMethodExact(methodId);
        if (resolved != null) return resolved;
        return graphReader.findMethodExact(stripToSimpleName(methodId));
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

