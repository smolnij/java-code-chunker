package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.model.CodeChunk;

public final class EmbeddingText {

    private EmbeddingText() {}

    // Body-only, with banner-line stripping. The previous formula prepended
    // `className + " " + signature`, but `code` already begins with the method
    // declaration, so the signature got ~2× weight in cosine — letting method
    // names that lexically echo the query (e.g. `splitByStatementsPreservingSignature`
    // vs query "preserving method signature") win over body-relevant siblings.
    //
    // Banner stripping removes lines that are predominantly box-drawing or
    // repeated divider characters (`// ═══════════════════════════════════════`
    // and similar), which pull every method's embedding toward a shared
    // "comment scaffolding" centroid and away from the actual query terms.
    // See plan note: this matters for methods like `findMethodExact(String)`
    // whose 130-token body is dominated by section banners + javadoc.
    //
    // Falls back to className + signature when code is blank (interfaces,
    // abstract decls).
    public static String forChunk(CodeChunk chunk) {
        String code = chunk.getCode();
        if (code == null || code.isBlank()) {
            return chunk.getClassName() + " " + chunk.getMethodSignature();
        }
        StringBuilder out = new StringBuilder(code.length());
        for (String line : code.split("\n", -1)) {
            if (isBannerLine(line)) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isBannerLine(String line) {
        String s = line.strip();
        if (s.startsWith("//")) {
            s = s.substring(2);
        } else if (s.startsWith("/*")) {
            s = s.substring(2);
        } else if (s.startsWith("*/")) {
            s = s.substring(2);
        } else if (s.startsWith("*")) {
            s = s.substring(1);
        }
        s = s.replaceAll("\\s+", "");
        if (s.length() < 4) return false;
        char first = s.charAt(0);
        if (!isDividerChar(first)) return false;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != first) return false;
        }
        return true;
    }

    private static boolean isDividerChar(char c) {
        if (c >= 0x2500 && c <= 0x257F) return true;
        return c == '=' || c == '-' || c == '_' || c == '#';
    }
}
