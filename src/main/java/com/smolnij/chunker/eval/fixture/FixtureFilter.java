package com.smolnij.chunker.eval.fixture;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Filters a fixture suite by id regex, tag set, and/or mode.
 * All three are optional; missing filters match anything.
 */
public final class FixtureFilter {

    private final Pattern idPattern;
    private final List<String> requiredTags;
    private final String mode;

    public FixtureFilter(String idRegex, List<String> requiredTags, String mode) {
        this.idPattern = (idRegex == null || idRegex.isBlank()) ? null : Pattern.compile(idRegex);
        this.requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        this.mode = (mode == null || mode.isBlank()) ? null : mode;
    }

    public boolean includes(Fixture f) {
        if (idPattern != null && !idPattern.matcher(f.id()).find()) return false;
        if (mode != null && !mode.equalsIgnoreCase(f.mode())) return false;
        if (!requiredTags.isEmpty() && !f.tags().containsAll(requiredTags)) return false;
        return true;
    }

    public List<Fixture> apply(List<Fixture> all) {
        return all.stream().filter(this::includes).toList();
    }
}
