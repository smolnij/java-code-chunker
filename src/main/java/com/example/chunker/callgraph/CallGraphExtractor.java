package com.example.chunker.callgraph;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts call graph edges using JavaParser Symbol Solver.
 *
 * <p>Builds two maps:
 * <ul>
 *   <li><b>Forward edges</b> (calls): callerFQN → Set&lt;calleeFQN&gt;</li>
 *   <li><b>Reverse edges</b> (calledBy): calleeFQN → Set&lt;callerFQN&gt;</li>
 * </ul>
 *
 * <p>All method references are fully qualified when symbol resolution succeeds
 * (via JavaParser's {@code SymbolSolver}). When resolution fails (e.g. for
 * external library calls without source), a best-effort unresolved representation
 * is used.
 */
public class CallGraphExtractor {

    // Global call graph: callerFQN → Set<calleeFQN>
    private final Map<String, Set<String>> forwardEdges = new ConcurrentHashMap<>();
    // Reverse: calleeFQN → Set<callerFQN>
    private final Map<String, Set<String>> reverseEdges = new ConcurrentHashMap<>();

    /**
     * Extract all method calls from a method declaration and record them
     * as edges in the call graph.
     *
     * @param method    the method AST node
     * @param callerFqn the FQN of the caller, e.g. "com.example.Foo#doStuff(String)"
     */
    public void extractCalls(MethodDeclaration method, String callerFqn) {
        List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class);

        for (MethodCallExpr call : calls) {
            String calleeFqn = resolveCall(call);

            forwardEdges
                .computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                .add(calleeFqn);
            reverseEdges
                .computeIfAbsent(calleeFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
                .add(callerFqn);
        }
    }

    /**
     * Attempt to resolve a method call to its fully qualified name using
     * the JavaParser Symbol Solver.
     *
     * <p>Falls back to a best-effort string representation if resolution fails
     * (e.g. for calls into external libraries without source on the type solver path).
     */
    private String resolveCall(MethodCallExpr call) {
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            String declaringType = resolved.declaringType().getQualifiedName();
            String methodName = resolved.getName();
            int paramCount = resolved.getNumberOfParams();

            StringBuilder sig = new StringBuilder();
            sig.append(declaringType).append("#").append(methodName).append("(");
            for (int i = 0; i < paramCount; i++) {
                if (i > 0) sig.append(", ");
                try {
                    sig.append(resolved.getParam(i).describeType());
                } catch (Exception e) {
                    sig.append("?");
                }
            }
            sig.append(")");
            return sig.toString();

        } catch (Exception e) {
            // Symbol resolution failed — fallback to unresolved representation
            return buildUnresolvedSignature(call);
        }
    }

    /**
     * Build a best-effort unresolved signature when the Symbol Solver cannot resolve.
     * Format: scope.methodName(...)
     */
    private String buildUnresolvedSignature(MethodCallExpr call) {
        StringBuilder sb = new StringBuilder();
        call.getScope().ifPresentOrElse(
            scope -> sb.append(scope.toString()).append("."),
            () -> sb.append("this.")
        );
        sb.append(call.getNameAsString());
        sb.append("(");
        for (int i = 0; i < call.getArguments().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("...");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Get all methods called by the given method FQN (forward/outgoing edges).
     */
    public Set<String> getCallsFrom(String methodFqn) {
        return forwardEdges.getOrDefault(methodFqn, Collections.emptySet());
    }

    /**
     * Get all methods that call the given method FQN (reverse/incoming edges).
     */
    public Set<String> getCallersOf(String methodFqn) {
        return reverseEdges.getOrDefault(methodFqn, Collections.emptySet());
    }

    /**
     * Get the full forward graph (caller → callees).
     */
    public Map<String, Set<String>> getForwardEdges() {
        return Collections.unmodifiableMap(forwardEdges);
    }

    /**
     * Get the full reverse graph (callee → callers).
     */
    public Map<String, Set<String>> getReverseEdges() {
        return Collections.unmodifiableMap(reverseEdges);
    }
}

