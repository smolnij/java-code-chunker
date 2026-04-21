package com.smolnij.chunker.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small parsing utilities used by the refactoring harness.
 */
public final class RefactorUtils {

    private RefactorUtils() {}

    /**
     * Parse the structured self-review JSON and return a deterministic,
     * deduplicated, lexically-sorted list of "missing" identifiers named
     * in the assumptions->needs arrays. The resulting list is capped to
     * {@code max} entries.
     *
     * <p>Behavior:
     * - If input is null/blank or cannot be parsed, returns an empty list.
     * - Extracts every string value under assumptions[*].needs[*].
     * - Trims and deduplicates values.
     * - Sorts the results lexicographically for deterministic ordering.
     * - Limits the returned list to {@code max} entries.
     */
    public static List<String> parseMissingFromSelfReview(String selfReviewJson, int max) {
        if (selfReviewJson == null) return List.of();
        String trimmed = selfReviewJson.trim();
        if (trimmed.isEmpty()) return List.of();

        try {
            JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
            JsonArray assumptions = root.has("assumptions") ? root.getAsJsonArray("assumptions") : new JsonArray();
            Set<String> dedup = new LinkedHashSet<>();

            for (JsonElement aEl : assumptions) {
                if (!aEl.isJsonObject()) continue;
                JsonObject aObj = aEl.getAsJsonObject();
                if (!aObj.has("needs") || !aObj.get("needs").isJsonArray()) continue;
                JsonArray needs = aObj.getAsJsonArray("needs");
                for (JsonElement nEl : needs) {
                    if (nEl == null || nEl.isJsonNull()) continue;
                    String s = nEl.getAsString();
                    if (s == null) continue;
                    s = s.trim();
                    if (s.isEmpty()) continue;
                    dedup.add(s);
                }
            }

            List<String> result = new ArrayList<>(dedup);
            // Lexical sort to ensure deterministic ordering across runs
            Collections.sort(result);

            if (max > 0 && result.size() > max) return result.subList(0, max);
            return result;
        } catch (Exception e) {
            // On parse failure, return empty list (caller should handle)
            return List.of();
        }
    }
}

