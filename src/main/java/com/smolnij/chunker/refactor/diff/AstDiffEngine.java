package com.smolnij.chunker.refactor.diff;

import com.smolnij.chunker.model.CodeChunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes AST-level structural diffs between original code (from the Neo4j graph)
 * and LLM-proposed code using JavaParser.
 *
 * <p>Unlike text-based diffs, this understands Java structure:
 * <ul>
 *   <li>Signature changes: return type, parameters, visibility, throws clause</li>
 *   <li>Body changes: new/removed method calls (call graph delta)</li>
 *   <li>Annotation changes: added/removed method annotations</li>
 *   <li>Classification: body-only vs. signature-breaking</li>
 * </ul>
 *
 * <p>Method call extraction uses unresolved names (no symbol solver) since
 * proposed code is parsed in isolation. This is sufficient for delta detection
 * — we compare the set of call names, not fully-qualified signatures.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   AstDiffEngine engine = new AstDiffEngine();
 *   MethodDiff diff = engine.diff(originalChunk, proposedCode);
 *   System.out.println(diff.isSignatureChanged());
 *   System.out.println(diff.getAddedCalls());
 * </pre>
 */
public class AstDiffEngine {

    private final JavaParser parser;

    public AstDiffEngine() {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.parser = new JavaParser(config);
    }

    // ═══════════════════════════════════════════════════════════════
    // Main diff entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compute a structured diff between the original chunk and proposed code.
     *
     * @param original     the original CodeChunk from the Neo4j graph
     * @param proposedCode the LLM's proposed replacement code (a method body or full method)
     * @return the structured MethodDiff, or a parse-error diff if either side fails
     */
    public MethodDiff diff(CodeChunk original, String proposedCode) {
        MethodDeclaration originalAst = parseMethod(original.getCode(), original.getImports());
        if (originalAst == null) {
            return MethodDiff.parseError(original.getChunkId(), "original");
        }

        MethodDeclaration proposedAst = parseMethod(proposedCode, original.getImports());
        if (proposedAst == null) {
            return MethodDiff.parseError(original.getChunkId(), "proposed");
        }

        MethodDiff diff = new MethodDiff(original.getChunkId());

        // ── 1. Return type ──
        String oldReturn = originalAst.getTypeAsString();
        String newReturn = proposedAst.getTypeAsString();
        diff.setReturnTypeChanged(!oldReturn.equals(newReturn));
        diff.setOldReturnType(oldReturn);
        diff.setNewReturnType(newReturn);

        // ── 2. Parameters ──
        String oldParams = originalAst.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        String newParams = proposedAst.getParameters().stream()
                .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                .collect(Collectors.joining(", "));
        diff.setParamsChanged(!oldParams.equals(newParams));
        diff.setOldParams(oldParams);
        diff.setNewParams(newParams);

        // ── 3. Visibility ──
        String oldVis = getVisibility(originalAst);
        String newVis = getVisibility(proposedAst);
        diff.setVisibilityChanged(!oldVis.equals(newVis));
        diff.setOldVisibility(oldVis);
        diff.setNewVisibility(newVis);

        // ── 4. Throws clause ──
        String oldThrows = originalAst.getThrownExceptions().stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
        String newThrows = proposedAst.getThrownExceptions().stream()
                .map(Object::toString)
                .sorted()
                .collect(Collectors.joining(", "));
        diff.setThrowsChanged(!oldThrows.equals(newThrows));
        diff.setOldThrows(oldThrows.isEmpty() ? "(none)" : oldThrows);
        diff.setNewThrows(newThrows.isEmpty() ? "(none)" : newThrows);

        // ── 5. Call graph delta ──
        Set<String> originalCalls = extractMethodCalls(originalAst);
        Set<String> proposedCalls = extractMethodCalls(proposedAst);

        Set<String> addedCalls = new LinkedHashSet<>(proposedCalls);
        addedCalls.removeAll(originalCalls);

        Set<String> removedCalls = new LinkedHashSet<>(originalCalls);
        removedCalls.removeAll(proposedCalls);

        diff.setAddedCalls(addedCalls);
        diff.setRemovedCalls(removedCalls);

        // ── 6. Annotation delta ──
        Set<String> origAnnotations = extractAnnotations(originalAst);
        Set<String> propAnnotations = extractAnnotations(proposedAst);

        Set<String> addedAnnotations = new LinkedHashSet<>(propAnnotations);
        addedAnnotations.removeAll(origAnnotations);

        Set<String> removedAnnotations = new LinkedHashSet<>(origAnnotations);
        removedAnnotations.removeAll(propAnnotations);

        diff.setAddedAnnotations(addedAnnotations);
        diff.setRemovedAnnotations(removedAnnotations);

        // ── 7. Body-only change detection ──
        boolean signatureUnchanged = !diff.isSignatureChanged();
        boolean annotationsUnchanged = addedAnnotations.isEmpty() && removedAnnotations.isEmpty();
        boolean bodyDiffers = !getBodyText(originalAst).equals(getBodyText(proposedAst));
        diff.setBodyOnlyChange(signatureUnchanged && annotationsUnchanged && bodyDiffers);

        return diff;
    }

    // ═══════════════════════════════════════════════════════════════
    // Parsing helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a code string into a MethodDeclaration.
     *
     * <p>Tries multiple strategies:
     * <ol>
     *   <li>Parse as a standalone method declaration</li>
     *   <li>Wrap in a synthetic class and parse the compilation unit</li>
     *   <li>Wrap in a synthetic class with imports and parse</li>
     * </ol>
     */
    MethodDeclaration parseMethod(String code, List<String> imports) {
        if (code == null || code.isBlank()) return null;

        String trimmed = code.trim();

        // Strategy 1: Try parsing as a standalone method body statement
        // (JavaParser can parse a MethodDeclaration directly in some cases)
        try {
            ParseResult<CompilationUnit> result = parser.parse(
                    "class _Synthetic { " + trimmed + " }");
            if (result.isSuccessful() && result.getResult().isPresent()) {
                List<MethodDeclaration> methods = result.getResult().get()
                        .findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) {
                    return methods.get(0);
                }
            }
        } catch (Exception ignored) { }

        // Strategy 2: Wrap with imports for better type resolution
        if (imports != null && !imports.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String imp : imports) {
                    String importLine = imp.trim();
                    if (!importLine.startsWith("import ")) {
                        importLine = "import " + importLine;
                    }
                    if (!importLine.endsWith(";")) {
                        importLine += ";";
                    }
                    sb.append(importLine).append("\n");
                }
                sb.append("class _Synthetic { ").append(trimmed).append(" }");

                ParseResult<CompilationUnit> result = parser.parse(sb.toString());
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    List<MethodDeclaration> methods = result.getResult().get()
                            .findAll(MethodDeclaration.class);
                    if (!methods.isEmpty()) {
                        return methods.get(0);
                    }
                }
            } catch (Exception ignored) { }
        }

        // Strategy 3: The code might already be a full class — try parsing as-is
        try {
            ParseResult<CompilationUnit> result = parser.parse(trimmed);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                List<MethodDeclaration> methods = result.getResult().get()
                        .findAll(MethodDeclaration.class);
                if (!methods.isEmpty()) {
                    return methods.get(0);
                }
            }
        } catch (Exception ignored) { }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Extraction helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract all method call names from a method body (unresolved).
     *
     * <p>Returns call names in the form "scope.method" or just "method"
     * when there is no explicit scope. This matches the pattern used in
     * {@link com.smolnij.chunker.callgraph.CallGraphExtractor} but without
     * symbol resolution (since proposed code has no type context).
     */
    private Set<String> extractMethodCalls(MethodDeclaration method) {
        Set<String> calls = new LinkedHashSet<>();
        List<MethodCallExpr> callExprs = method.findAll(MethodCallExpr.class);

        for (MethodCallExpr call : callExprs) {
            StringBuilder sb = new StringBuilder();
            call.getScope().ifPresent(scope -> sb.append(scope.toString()).append("."));
            sb.append(call.getNameAsString());
            calls.add(sb.toString());
        }
        return calls;
    }

    /**
     * Extract annotation names from a method declaration.
     */
    private Set<String> extractAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Get the effective visibility modifier of a method.
     */
    private String getVisibility(MethodDeclaration method) {
        if (method.isPublic()) return "public";
        if (method.isProtected()) return "protected";
        if (method.isPrivate()) return "private";
        return "package-private";
    }

    /**
     * Get the body text for comparison (normalized).
     */
    private String getBodyText(MethodDeclaration method) {
        return method.getBody()
                .map(body -> body.toString().replaceAll("\\s+", " ").trim())
                .orElse("");
    }
}

