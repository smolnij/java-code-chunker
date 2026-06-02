package com.smolnij.chunker.callgraph;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    // Additional relational maps for P-G1
    private final Map<String, Set<String>> usesType = new ConcurrentHashMap<>();      // methodFqn -> set(typeFqn)
    private final Map<String, Set<String>> returnsType = new ConcurrentHashMap<>();   // methodFqn -> set(typeFqn)
    private final Map<String, Set<String>> throwsType = new ConcurrentHashMap<>();    // methodFqn -> set(exceptionFqn)
    private final Map<String, Set<String>> importsByClass = new ConcurrentHashMap<>(); // classFqn -> set(importedFqn)
    private final Map<String, Set<String>> testFor = new ConcurrentHashMap<>();       // testMethodFqn -> set(targetMethodFqn)
    private final Map<String, Set<String>> readsField = new ConcurrentHashMap<>();    // methodFqn -> set(fieldFqn)
    private final Map<String, Set<String>> writesField = new ConcurrentHashMap<>();   // methodFqn -> set(fieldFqn)

    // Symbol-resolution telemetry: how many MethodCallExprs resolved to a real
    // FQN target vs. fell back to an unresolved (non-navigable) representation.
    private final AtomicInteger resolvedCalls = new AtomicInteger();
    private final AtomicInteger unresolvedCalls = new AtomicInteger();

    /**
     * Clear every edge map. Used by {@link com.smolnij.chunker.JavaCodeChunker}
     * when re-running per-file extraction for a delta re-index — the resulting
     * model must reflect only the calls discovered in the current invocation.
     */
    public void reset() {
        forwardEdges.clear();
        reverseEdges.clear();
        usesType.clear();
        returnsType.clear();
        throwsType.clear();
        importsByClass.clear();
        testFor.clear();
        readsField.clear();
        writesField.clear();
        resolvedCalls.set(0);
        unresolvedCalls.set(0);
    }

    /** Number of method calls that resolved to a fully-qualified target. */
    public int getResolvedCallCount() {
        return resolvedCalls.get();
    }

    /**
     * Number of method calls that could not be resolved and fell back to an
     * unresolved {@code scope.name(...)} representation. These edges can never
     * match a chunk id, so they are dead-ends for graph traversal — a high
     * count usually means the type solver is missing source roots or dependency
     * jars (see {@code chunker.classpath}).
     */
    public int getUnresolvedCallCount() {
        return unresolvedCalls.get();
    }

    /**
     * Extract all method calls from a method declaration and record them
     * as edges in the call graph.
     *
     * @param method    the method AST node
     * @param callerFqn the FQN of the caller, e.g. "com.example.Foo#doStuff(String)"
     */
    public void extractCalls(Node method, String callerFqn) {
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
     * Extract field accesses from a method/constructor body and record them as
     * READS_FIELD / WRITES_FIELD edges against the owning class's declared fields.
     *
     * <p>Best-effort and intentionally conservative: only references to fields
     * declared on {@code classFqn} are recorded, so every emitted target points at
     * a real {@link com.smolnij.chunker.model.graph.FieldNode}. Local variables and
     * parameters whose name shadows a field are excluded (a bare {@code NameExpr}
     * matching such a name is assumed to refer to the local, not the field). An
     * unqualified {@code this.field} access is never shadowed and is always recorded.
     *
     * @param method          the method or constructor AST node
     * @param callerFqn        fully-qualified method id (same format used elsewhere)
     * @param classFqn         FQN of the class declaring the fields
     * @param classFieldNames  simple names of the fields declared on {@code classFqn}
     */
    public void extractFieldAccess(Node method, String callerFqn, String classFqn, Set<String> classFieldNames) {
        if (classFieldNames == null || classFieldNames.isEmpty()) return;

        // Names declared inside the method (params + local vars) that shadow a field.
        Set<String> shadowed = new HashSet<>();
        for (Parameter p : method.findAll(Parameter.class)) shadowed.add(p.getNameAsString());
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) shadowed.add(v.getNameAsString());

        // Unqualified `this.field` accesses — never shadowed.
        for (FieldAccessExpr fa : method.findAll(FieldAccessExpr.class)) {
            if (fa.getScope() instanceof ThisExpr) {
                String name = fa.getNameAsString();
                if (classFieldNames.contains(name)) {
                    recordFieldAccess(callerFqn, classFqn, name, fa);
                }
            }
        }

        // Bare references — match a field name only when not shadowed by a local.
        for (NameExpr ne : method.findAll(NameExpr.class)) {
            String name = ne.getNameAsString();
            if (classFieldNames.contains(name) && !shadowed.contains(name)) {
                recordFieldAccess(callerFqn, classFqn, name, ne);
            }
        }
    }

    /**
     * Classify a matched field reference as a read and/or write based on its
     * surrounding expression, then record it in the appropriate edge map(s).
     */
    private void recordFieldAccess(String callerFqn, String classFqn, String fieldName, Expression ref) {
        String fieldFqn = classFqn + "." + fieldName;
        boolean write = false;
        boolean read = true;

        Node parent = ref.getParentNode().orElse(null);
        if (parent instanceof AssignExpr assign && assign.getTarget() == ref) {
            write = true;
            // Plain `=` is a pure write; compound assignments (+=, |=, …) also read.
            read = assign.getOperator() != AssignExpr.Operator.ASSIGN;
        } else if (parent instanceof UnaryExpr unary && isIncDec(unary.getOperator())) {
            write = true;
            read = true;
        }

        if (read) {
            readsField.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(fieldFqn);
        }
        if (write) {
            writesField.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(fieldFqn);
        }
    }

    private static boolean isIncDec(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT
            || op == UnaryExpr.Operator.POSTFIX_INCREMENT
            || op == UnaryExpr.Operator.PREFIX_DECREMENT
            || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    /**
     * Extract parameter/return/throws/import/test heuristics and record lightweight
     * type edges. This is intentionally best-effort: we prefer resilient string
     * representations when symbol resolution is not available.
     *
     * @param method    the method AST node
     * @param callerFqn fully-qualified method id (same format used elsewhere)
     * @param cu        compilation unit (to gather imports and detect test classes)
     */
    public void extractTypeInfo(MethodDeclaration method, String callerFqn, CompilationUnit cu) {
        // Parameters -> USES_TYPE
        for (var p : method.getParameters()) {
            String typeName;
            try {
                typeName = p.getType().resolve().describe();
            } catch (Exception e) {
                typeName = p.getType().toString();
            }
            usesType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(typeName);
        }

        // Return type -> RETURNS_TYPE
        try {
            String ret = method.getType().resolve().describe();
            returnsType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(ret);
        } catch (Exception e) {
            String ret = method.getType().toString();
            if (ret != null && !ret.isBlank()) {
                returnsType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(ret);
            }
        }

        // Thrown exceptions -> THROWS
        method.getThrownExceptions().forEach(t -> {
            String thrown;
            try {
                thrown = t.resolve().describe();
            } catch (Exception ex) {
                thrown = t.toString();
            }
            throwsType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(thrown);
        });

        // Imports (record at class level)
        String classFqn = callerFqn.split("#")[0];
        if (cu != null) {
            for (ImportDeclaration id : cu.getImports()) {
                String imp = id.getNameAsString();
                importsByClass.computeIfAbsent(classFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(imp);
            }
        }

        // TEST_FOR heuristic: if the enclosing class looks like a test class
        method.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(cd -> {
            String className = cd.getNameAsString();
            boolean isTestClass = className.endsWith("Test") || className.endsWith("Tests");
            if (isTestClass) {
                // For now, map test method -> all callees (best-effort)
                Set<String> callees = forwardEdges.getOrDefault(callerFqn, Collections.emptySet());
                if (!callees.isEmpty()) {
                    testFor.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).addAll(callees);
                }
            }
        });
    }

    /**
     * Constructor variant of {@link #extractTypeInfo(MethodDeclaration, String, CompilationUnit)}.
     * Records parameters (USES_TYPE), thrown exceptions (THROWS), and class-level imports.
     * Constructors have no return type, so RETURNS_TYPE is omitted.
     */
    public void extractTypeInfo(ConstructorDeclaration ctor, String callerFqn, CompilationUnit cu) {
        for (var p : ctor.getParameters()) {
            String typeName;
            try {
                typeName = p.getType().resolve().describe();
            } catch (Exception e) {
                typeName = p.getType().toString();
            }
            usesType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(typeName);
        }
        ctor.getThrownExceptions().forEach(t -> {
            String thrown;
            try {
                thrown = t.resolve().describe();
            } catch (Exception ex) {
                thrown = t.toString();
            }
            throwsType.computeIfAbsent(callerFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(thrown);
        });
        String classFqn = callerFqn.split("#")[0];
        if (cu != null) {
            for (ImportDeclaration id : cu.getImports()) {
                String imp = id.getNameAsString();
                importsByClass.computeIfAbsent(classFqn, k -> Collections.synchronizedSet(new LinkedHashSet<>())).add(imp);
            }
        }
    }

    // Accessors for the new maps
    public Set<String> getUsesTypesFrom(String methodFqn) {
        return usesType.getOrDefault(methodFqn, Collections.emptySet());
    }

    public Set<String> getReturnsTypesFrom(String methodFqn) {
        return returnsType.getOrDefault(methodFqn, Collections.emptySet());
    }

    public Set<String> getThrowsTypesFrom(String methodFqn) {
        return throwsType.getOrDefault(methodFqn, Collections.emptySet());
    }

    public Set<String> getImportsForClass(String classFqn) {
        return importsByClass.getOrDefault(classFqn, Collections.emptySet());
    }

    public Set<String> getTestForTargets(String methodFqn) {
        return testFor.getOrDefault(methodFqn, Collections.emptySet());
    }

    /** Fields read by the given method FQN (READS_FIELD edge targets). */
    public Set<String> getReadsFieldFrom(String methodFqn) {
        return readsField.getOrDefault(methodFqn, Collections.emptySet());
    }

    /** Fields written by the given method FQN (WRITES_FIELD edge targets). */
    public Set<String> getWritesFieldFrom(String methodFqn) {
        return writesField.getOrDefault(methodFqn, Collections.emptySet());
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
            resolvedCalls.incrementAndGet();

            String declaringType = resolved.declaringType().getQualifiedName();
            String methodName = resolved.getName();
            int paramCount = resolved.getNumberOfParams();

            // Render parameter types through the SAME canonicalizer the chunk-id
            // builder uses, so this edge target byte-matches the callee chunk id.
            List<String> rawParams = new ArrayList<>(paramCount);
            for (int i = 0; i < paramCount; i++) {
                try {
                    rawParams.add(resolved.getParam(i).describeType());
                } catch (Exception e) {
                    rawParams.add("?");
                }
            }
            return MethodId.of(declaringType, methodName, rawParams);

        } catch (Exception e) {
            // Symbol resolution failed — fallback to unresolved representation
            unresolvedCalls.incrementAndGet();
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

