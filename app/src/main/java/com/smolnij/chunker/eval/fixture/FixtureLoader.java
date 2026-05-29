package com.smolnij.chunker.eval.fixture;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads fixture JSON files from a directory (or, as a fallback, from
 * the {@code eval-fixtures/} classpath resource).
 *
 * <p>Schema rules enforced here:
 * <ul>
 *   <li>{@code schemaVersion} must equal {@link Fixture#CURRENT_SCHEMA_VERSION}.</li>
 *   <li>{@code id}, {@code mode}, {@code query}, {@code gold.relevant} are required.</li>
 *   <li>{@code mode} must be one of {@link #SUPPORTED_MODES} — this PR covers
 *       {@code retrieval} and {@code safeloop}. Refactor/Ralph/Agent runners are deferred.</li>
 * </ul>
 */
public final class FixtureLoader {

    /** Modes wired in the current first-milestone PR. */
    public static final Set<String> SUPPORTED_MODES = Set.of("retrieval", "safeloop");

    /** Modes named in the plan but not yet wired. */
    public static final Set<String> PLANNED_MODES = Set.of("refactor", "ralph", "agent");

    /** Classpath fallback directory when no on-disk fixtures dir is found. */
    public static final String CLASSPATH_FALLBACK = "eval-fixtures";

    private static final Gson GSON = new Gson();

    private FixtureLoader() {}

    public static List<Fixture> loadAll(Path dir) throws IOException {
        if (dir != null && Files.isDirectory(dir)) {
            return loadFromDir(dir);
        }
        return loadFromClasspath();
    }

    private static List<Fixture> loadFromDir(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> jsons = s.filter(p -> p.getFileName().toString().endsWith(".json"))
                                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                                .toList();
            List<Fixture> out = new ArrayList<>(jsons.size());
            for (Path p : jsons) {
                out.add(parseAndValidate(Files.readString(p), p.toString()));
            }
            return out;
        }
    }

    private static List<Fixture> loadFromClasspath() throws IOException {
        List<Fixture> out = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream index = cl.getResourceAsStream(CLASSPATH_FALLBACK + "/index.txt");
        if (index == null) return out;
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(index))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                String path = CLASSPATH_FALLBACK + "/" + name;
                try (InputStream in = cl.getResourceAsStream(path)) {
                    if (in == null) continue;
                    String json = new String(in.readAllBytes());
                    out.add(parseAndValidate(json, "classpath:" + path));
                }
            }
        }
        return out;
    }

    public static Fixture parseAndValidate(String json, String source) {
        Fixture f;
        try {
            f = GSON.fromJson(json, Fixture.class);
        } catch (JsonSyntaxException e) {
            throw new FixtureException(source + ": malformed JSON — " + e.getMessage());
        }
        if (f == null) throw new FixtureException(source + ": empty JSON");
        if (f.schemaVersion() != Fixture.CURRENT_SCHEMA_VERSION) {
            throw new FixtureException(source + ": unsupported schemaVersion="
                    + f.schemaVersion() + " (expected " + Fixture.CURRENT_SCHEMA_VERSION + ")");
        }
        requireNonBlank(source, "id", f.id());
        requireNonBlank(source, "mode", f.mode());
        requireNonBlank(source, "query", f.query());
        if (f.gold() == null || f.gold().relevant().isEmpty()) {
            throw new FixtureException(source + ": gold.relevant must be non-empty");
        }
        String mode = f.mode().toLowerCase();
        if (!SUPPORTED_MODES.contains(mode)) {
            if (PLANNED_MODES.contains(mode)) {
                throw new FixtureException(source + ": mode '" + mode
                        + "' is planned but not wired in this PR (supported: " + SUPPORTED_MODES + ")");
            }
            throw new FixtureException(source + ": unknown mode '" + mode
                    + "' (supported: " + SUPPORTED_MODES + ")");
        }
        return f;
    }

    private static void requireNonBlank(String source, String field, String v) {
        if (v == null || v.isBlank()) {
            throw new FixtureException(source + ": '" + field + "' is required");
        }
    }

    public static final class FixtureException extends RuntimeException {
        public FixtureException(String msg) { super(msg); }
    }
}
