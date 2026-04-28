package com.smolnij.chunker.apply;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;

import java.util.Map;

/**
 * Read-only verification harness for the post-apply re-index repair pass.
 *
 * <p>This is a manual diagnostic — there are no automated tests in this
 * project — used to confirm that {@link GraphReindexer} repaired the graph
 * correctly after a refactor. Runs simple Cypher queries against the
 * configured Neo4j instance and prints node/edge counts and small samples.
 *
 * <h3>Usage</h3>
 * <pre>
 *   # Verify a method rename: before chunkId no longer exists, callers
 *   # of "after" chunkId now include the renamed callers from unchanged files.
 *   java -cp target/java-code-chunker-1.0-SNAPSHOT.jar \
 *        com.smolnij.chunker.apply.ReindexInspectMain \
 *        --check method-rename \
 *        --before "com.example.B#oldName()" \
 *        --after  "com.example.B#newName()"
 *
 *   # Verify a class rename:
 *        --check class-rename \
 *        --before com.example.Foo \
 *        --after  com.example.Bar
 *
 *   # Verify a field rename (FQNs are owningClass + "." + fieldName):
 *        --check field-rename \
 *        --before com.example.Foo.oldField \
 *        --after  com.example.Foo.newField
 *
 *   # Show :Method.code samples for the inbound callers of a chunkId
 *   # (useful for spotting stale source-text references):
 *        --check callers --of "com.example.B#newName()"
 * </pre>
 *
 * <p>Connection settings come from the same env vars / system properties
 * as the rest of the project: {@code NEO4J_URI}, {@code NEO4J_USER},
 * {@code NEO4J_PASSWORD}.
 */
public class ReindexInspectMain {

    public static void main(String[] args) {
        String check = "summary";
        String before = "";
        String after = "";
        String of = "";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> { if (i + 1 < args.length) check = args[++i]; }
                case "--before" -> { if (i + 1 < args.length) before = args[++i]; }
                case "--after" -> { if (i + 1 < args.length) after = args[++i]; }
                case "--of" -> { if (i + 1 < args.length) of = args[++i]; }
                default -> { /* ignore */ }
            }
        }

        String uri = configValue("NEO4J_URI", "neo4j.uri", "bolt://localhost:7687");
        String user = configValue("NEO4J_USER", "neo4j.user", "neo4j");
        String pwd = configValue("NEO4J_PASSWORD", "neo4j.password", "12345678");

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pwd));
             Session session = driver.session()) {

            switch (check) {
                case "method-rename" -> checkMethodRename(session, before, after);
                case "class-rename"  -> checkClassRename(session, before, after);
                case "field-rename"  -> checkFieldRename(session, before, after);
                case "callers"       -> showCallers(session, of);
                case "summary"       -> showSummary(session);
                default -> {
                    System.err.println("unknown --check value: " + check);
                    System.err.println("expected one of: method-rename, class-rename, field-rename, callers, summary");
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ───────────── checks ─────────────

    private static void checkMethodRename(Session s, String before, String after) {
        if (before.isEmpty() || after.isEmpty()) {
            System.err.println("--check method-rename requires --before <oldChunkId> --after <newChunkId>");
            System.exit(2);
        }
        long oldExists = scalarLong(s,
            "MATCH (m:Method {chunkId: $id}) RETURN count(m) AS n",
            Map.of("id", before));
        long newExists = scalarLong(s,
            "MATCH (m:Method {chunkId: $id}) RETURN count(m) AS n",
            Map.of("id", after));
        long inboundOld = scalarLong(s,
            "MATCH (caller:Method)-[:CALLS]->(m:Method {chunkId: $id}) RETURN count(caller) AS n",
            Map.of("id", before));
        long inboundNew = scalarLong(s,
            "MATCH (caller:Method)-[:CALLS]->(m:Method {chunkId: $id}) RETURN count(caller) AS n",
            Map.of("id", after));

        System.out.println("─── method-rename check ───");
        System.out.println("  before exists: " + oldExists + (oldExists == 0 ? " ✓" : " ✗ (should be 0)"));
        System.out.println("  after  exists: " + newExists + (newExists == 1 ? " ✓" : " ✗ (should be 1)"));
        System.out.println("  inbound CALLS to before: " + inboundOld + (inboundOld == 0 ? " ✓" : " ✗"));
        System.out.println("  inbound CALLS to after:  " + inboundNew + (inboundNew >= 1 ? " ✓" : " ✗ (no callers re-pointed?)"));

        // Sample callers for context
        Result callers = s.run(
            "MATCH (caller:Method)-[:CALLS]->(m:Method {chunkId: $id}) " +
            "RETURN caller.chunkId AS id, caller.filePath AS file LIMIT 10",
            Map.of("id", after));
        if (callers.hasNext()) {
            System.out.println("  sample callers of after:");
            while (callers.hasNext()) {
                var rec = callers.next();
                System.out.println("    • " + rec.get("id").asString("?") + "  [" + rec.get("file").asString("?") + "]");
            }
        }
    }

    private static void checkClassRename(Session s, String before, String after) {
        if (before.isEmpty() || after.isEmpty()) {
            System.err.println("--check class-rename requires --before <oldFqn> --after <newFqn>");
            System.exit(2);
        }
        long oldExists = scalarLong(s,
            "MATCH (c) WHERE (c:Class OR c:Interface) AND c.fqName = $fq RETURN count(c) AS n",
            Map.of("fq", before));
        long newExists = scalarLong(s,
            "MATCH (c) WHERE (c:Class OR c:Interface) AND c.fqName = $fq RETURN count(c) AS n",
            Map.of("fq", after));
        long usesNew = scalarLong(s,
            "MATCH (m:Method)-[:USES_TYPE]->(c) WHERE (c:Class OR c:Interface) AND c.fqName = $fq " +
            "RETURN count(m) AS n",
            Map.of("fq", after));
        long importsNew = scalarLong(s,
            "MATCH (src:Class)-[:IMPORTS]->(c) WHERE (c:Class OR c:Interface) AND c.fqName = $fq " +
            "RETURN count(src) AS n",
            Map.of("fq", after));

        System.out.println("─── class-rename check ───");
        System.out.println("  before exists: " + oldExists + (oldExists == 0 ? " ✓" : " ✗"));
        System.out.println("  after  exists: " + newExists + (newExists >= 1 ? " ✓" : " ✗"));
        System.out.println("  USES_TYPE → after:  " + usesNew);
        System.out.println("  IMPORTS  → after:  " + importsNew);
    }

    private static void checkFieldRename(Session s, String before, String after) {
        if (before.isEmpty() || after.isEmpty()) {
            System.err.println("--check field-rename requires --before <oldFqn> --after <newFqn>");
            System.exit(2);
        }
        long oldExists = scalarLong(s,
            "MATCH (f:Field {fqName: $fq}) RETURN count(f) AS n", Map.of("fq", before));
        long newExists = scalarLong(s,
            "MATCH (f:Field {fqName: $fq}) RETURN count(f) AS n", Map.of("fq", after));
        long readsNew = scalarLong(s,
            "MATCH (m:Method)-[:READS_FIELD|WRITES_FIELD]->(f:Field {fqName: $fq}) " +
            "RETURN count(m) AS n", Map.of("fq", after));

        System.out.println("─── field-rename check ───");
        System.out.println("  before exists: " + oldExists + (oldExists == 0 ? " ✓" : " ✗"));
        System.out.println("  after  exists: " + newExists + (newExists == 1 ? " ✓" : " ✗"));
        System.out.println("  READS/WRITES → after: " + readsNew);
    }

    private static void showCallers(Session s, String of) {
        if (of.isEmpty()) {
            System.err.println("--check callers requires --of <chunkId>");
            System.exit(2);
        }
        Result callers = s.run(
            "MATCH (caller:Method)-[:CALLS]->(m:Method {chunkId: $id}) " +
            "RETURN caller.chunkId AS id, caller.filePath AS file, " +
            "       substring(coalesce(caller.code, ''), 0, 240) AS sample LIMIT 25",
            Map.of("id", of));
        System.out.println("─── callers of " + of + " ───");
        int i = 0;
        while (callers.hasNext()) {
            var rec = callers.next();
            System.out.println("  [" + (++i) + "] " + rec.get("id").asString("?")
                + "  [" + rec.get("file").asString("?") + "]");
            String sample = rec.get("sample").asString("");
            if (!sample.isEmpty()) {
                System.out.println("        " + sample.replace("\n", "\n        "));
            }
        }
        if (i == 0) System.out.println("  (none)");
    }

    private static void showSummary(Session s) {
        long methods = scalarLong(s, "MATCH (m:Method) RETURN count(m) AS n", Map.of());
        long classes = scalarLong(s, "MATCH (c) WHERE c:Class OR c:Interface RETURN count(c) AS n", Map.of());
        long fields  = scalarLong(s, "MATCH (f:Field) RETURN count(f) AS n", Map.of());
        long calls   = scalarLong(s, "MATCH ()-[r:CALLS]->() RETURN count(r) AS n", Map.of());
        System.out.printf("Methods=%d  Classes/Interfaces=%d  Fields=%d  CALLS=%d%n",
            methods, classes, fields, calls);
    }

    // ───────────── helpers ─────────────

    private static long scalarLong(Session s, String cypher, Map<String, Object> params) {
        Result r = s.run(cypher, params);
        return r.hasNext() ? r.next().get(0).asLong(0L) : 0L;
    }

    private static String configValue(String envKey, String sysPropKey, String defaultValue) {
        String v = System.getProperty(sysPropKey);
        if (v != null && !v.isEmpty()) return v;
        v = System.getenv(envKey);
        if (v != null && !v.isEmpty()) return v;
        return defaultValue;
    }
}
