package com.smolnij.chunker.callgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Canonical method identifier shared by the two producers that must agree
 * byte-for-byte or the call graph becomes non-navigable:
 *
 * <ul>
 *   <li><b>chunk identity</b> — {@link com.smolnij.chunker.JavaCodeChunker}
 *       builds {@code "fqClass#name(params)"} from <em>AST</em> parameter types.</li>
 *   <li><b>call-edge targets</b> — {@link CallGraphExtractor} builds the same
 *       string from <em>Symbol-Solver-resolved</em> parameter types.</li>
 * </ul>
 *
 * <p>Historically these diverged: the AST side emitted source-form types
 * ({@code "String"}, {@code "List<String>"}) while the resolver emitted
 * fully-qualified, generic-bearing types ({@code "java.lang.String"},
 * {@code "java.util.List<java.lang.String>"}). The two never {@code equals()},
 * so {@code calledBy} back-patching and graph BFS silently failed for every
 * method that takes parameters.
 *
 * <p>The fix: canonicalize every parameter type to its <em>erased simple
 * name</em> (generics stripped, package qualifier dropped, array dimensions
 * preserved). Both producers route through {@link #normalizeType(String)} so
 * they converge regardless of whether the type was resolved.
 */
public final class MethodId {

    private MethodId() {}

    /**
     * Build the canonical id {@code declaringTypeFqn#methodName(t1, t2, ...)}
     * where each raw parameter type is normalized via {@link #normalizeType(String)}.
     */
    public static String of(String declaringTypeFqn, String methodName, List<String> rawParamTypes) {
        String params = rawParamTypes.stream()
            .map(MethodId::normalizeType)
            .collect(Collectors.joining(", "));
        return declaringTypeFqn + "#" + methodName + "(" + params + ")";
    }

    /**
     * Reduce a parameter type — in either source form or resolved/qualified
     * form — to a canonical erased simple name, preserving array dimensions.
     *
     * <pre>
     *   "String"                              -> "String"
     *   "java.lang.String"                    -> "String"
     *   "List&lt;String&gt;"                  -> "List"
     *   "java.util.List&lt;java.lang.String&gt;" -> "List"
     *   "Map.Entry&lt;K,V&gt;"                -> "Entry"
     *   "int[]"                               -> "int[]"
     *   "String[]"  (varargs element + "[]")  -> "String[]"
     * </pre>
     */
    public static String normalizeType(String rawType) {
        if (rawType == null || rawType.isBlank()) return "?";
        String t = stripGenerics(rawType.trim());
        int dims = 0;
        while (t.endsWith("[]")) {
            dims++;
            t = t.substring(0, t.length() - 2).trim();
        }
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0) t = t.substring(lastDot + 1);
        t = t.trim();
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }

    /**
     * Re-render the parameter list of an <em>existing</em> method/chunk id so it
     * matches what the current chunker emits. Useful for reconciling ids built
     * before the canonicalization fix, or hand-written fixture gold lists that
     * still use qualified or generic-bearing parameter types
     * ({@code Foo#bar(java.util.List<String>, int)} → {@code Foo#bar(List, int)}).
     *
     * <p>Only the substring between the first {@code (} and the last {@code )}
     * is rewritten; the {@code declaringType#name} prefix and any trailing
     * {@code #partN} suffix are left untouched. Ids with no parameter list are
     * returned unchanged.
     */
    public static String canonicalize(String id) {
        if (id == null) return null;
        int open = id.indexOf('(');
        int close = id.lastIndexOf(')');
        if (open < 0 || close < open) return id;
        String prefix = id.substring(0, open);
        String rawParams = id.substring(open + 1, close);
        String suffix = id.substring(close + 1);
        String params = rawParams.isBlank()
            ? ""
            : splitTopLevel(rawParams).stream()
                .map(MethodId::normalizeType)
                .collect(Collectors.joining(", "));
        return prefix + "(" + params + ")" + suffix;
    }

    /**
     * Split a parameter list on top-level commas only, so commas inside generic
     * argument groups ({@code Map<String, Integer>}) do not split a single type.
     */
    private static List<String> splitTopLevel(String params) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (depth > 0) depth--; }
            else if (c == ',' && depth == 0) {
                out.add(params.substring(start, i));
                start = i + 1;
            }
        }
        out.add(params.substring(start));
        return out;
    }

    /** Remove balanced {@code <...>} generic argument groups (handles nesting). */
    private static String stripGenerics(String s) {
        if (s.indexOf('<') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (depth > 0) depth--; }
            else if (depth == 0) sb.append(c);
        }
        return sb.toString();
    }
}
