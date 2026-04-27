package com.smolnij.chunker.retrieval;

import com.smolnij.chunker.model.CodeChunk;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.types.Node;

import java.util.*;

/**
 * Read-only Neo4j client for the Graph-RAG retrieval pipeline.
 *
 * <p>Provides:
 * <ul>
 *   <li>Exact-match node lookup by chunkId / fqName</li>
 *   <li>BFS subgraph expansion with hop-distance tracking</li>
 *   <li>Same-class / same-package sibling queries</li>
 *   <li>Native vector search via Neo4j vector index</li>
 *   <li>Batch Method node hydration (Cypher → {@link CodeChunk})</li>
 * </ul>
 */
public class Neo4jGraphReader implements AutoCloseable {

    private final Driver driver;
    private final RetrievalConfig config;

    public Neo4jGraphReader(String uri, String user, String password, RetrievalConfig config) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        driver.verifyConnectivity();
        this.config = config;
    }

    // ═══════════════════════════════════════════════════════════════
    // Vector index management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ensure the Neo4j vector index exists, creating it if necessary.
     * This must be called before any {@link #vectorSearch} invocation.
     *
     * <p>Requires Neo4j 5.11+ with vector index support.
     * The index is created with {@code IF NOT EXISTS} so it is safe to call repeatedly.
     */
    public void ensureVectorIndex() {
        String indexName = config.getVectorIndexName();
        int dimensions = config.getEmbeddingDimensions();

        try (Session session = driver.session()) {
            // Check if the index already exists
            boolean exists = session.executeRead(tx -> {
                Result r = tx.run("SHOW INDEXES YIELD name WHERE name = $name RETURN name",
                        Map.of("name", indexName));
                return r.hasNext();
            });

            if (exists) {
                System.out.println("✓ Vector index '" + indexName + "' already exists.");
                return;
            }

            // Create the vector index
            session.executeWrite(tx -> {
                tx.run(
                    "CREATE VECTOR INDEX " + indexName + " IF NOT EXISTS " +
                    "FOR (m:Method) ON (m.embedding) " +
                    "OPTIONS {indexConfig: {" +
                    " `vector.dimensions`: " + dimensions + "," +
                    " `vector.similarity_function`: 'cosine'" +
                    "}}"
                );
                return null;
            });
            System.out.println("✓ Created vector index '" + indexName + "' (dims=" + dimensions + ").");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 1 — Resolve entry point
    // ═══════════════════════════════════════════════════════════════

    /**
     * Try to find a Method node by exact chunkId match.
     *
     * @return the chunkId if found, or {@code null}
     */
    public String findMethodExact(String identifier) {
        try (Session session = driver.session()) {
            // Try exact chunkId match first
            Record rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.chunkId = $id RETURN m.chunkId AS id LIMIT 1",
                    Map.of("id", identifier)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) {
                String id = rec.get("id").asString();
                System.out.println("    [resolver] '" + identifier + "' → '" + id + "' (exact chunkId)");
                return id;
            }

            // If the caller wrote something like "RalphLoop#main", honour the class qualifier:
            // require both the class name (substring before #) and the method name to match.
            // Don't fall through to the bare methodName branch — that would silently swap classes
            // (e.g. RalphLoop#main → RalphMain.main).
            int hashIdx = identifier.indexOf('#');
            if (hashIdx > 0 && hashIdx < identifier.length() - 1) {
                String classFragment = identifier.substring(0, hashIdx);
                String methodFragment = identifier.substring(hashIdx + 1);
                // Strip params from methodFragment if present, e.g. "main(String[])" -> "main"
                int paren = methodFragment.indexOf('(');
                String methodName = paren >= 0 ? methodFragment.substring(0, paren) : methodFragment;
                Record qualified = session.executeRead(tx -> {
                    Result r = tx.run(
                        "MATCH (m:Method) " +
                            "WHERE m.methodName = $name " +
                            "  AND (m.fqClassName = $cls " +
                            "       OR m.className = $cls " +
                            "       OR m.fqClassName ENDS WITH ('.' + $cls)) " +
                            "RETURN m.chunkId AS id LIMIT 1",
                        Map.of("name", methodName, "cls", classFragment)
                    );
                    return r.hasNext() ? r.next() : null;
                });
                if (qualified != null) {
                    String id = qualified.get("id").asString();
                    System.out.println("    [resolver] '" + identifier + "' → '" + id + "' (qualified class#method match)");
                    return id;
                }
                System.out.println("    [resolver] '" + identifier + "' unresolved");
                return null;
            }

            // No '#' qualifier — try matching by methodName (e.g. "createUser").
            rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.methodName = $name RETURN m.chunkId AS id LIMIT 1",
                    Map.of("name", identifier)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) {
                String id = rec.get("id").asString();
                System.out.println("    [resolver] '" + identifier + "' → '" + id + "' (methodName match)");
                return id;
            }

            // Boundary-anchored CONTAINS: only match the class-name segment of chunkIds,
            // i.e. require the input to be followed by '#' (a class boundary). This prevents
            // a substring like "Ralph" from hitting unrelated methods like RalphMain.run.
            String boundaryFragment = identifier + "#";
            Record containsRec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.chunkId CONTAINS $fragment " +
                        "RETURN m.chunkId AS id LIMIT 1",
                    Map.of("fragment", boundaryFragment)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (containsRec != null) {
                long total = session.executeRead(tx -> {
                    Result r = tx.run(
                        "MATCH (m:Method) WHERE m.chunkId CONTAINS $fragment RETURN count(m) AS c",
                        Map.of("fragment", boundaryFragment)
                    );
                    return r.hasNext() ? r.next().get("c").asLong() : 1L;
                });
                String id = containsRec.get("id").asString();
                System.out.println("    [resolver] '" + identifier + "' → '" + id
                        + "' (CONTAINS fallback @class boundary, " + total + " candidate" + (total == 1 ? "" : "s") + ")");
                return id;
            }

            System.out.println("    [resolver] '" + identifier + "' unresolved");
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Class-level lookup (for getClassOverview tool)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Resolve a class identifier (FQN, simple name, or file path) to a Class
     * node's fqName. Tries, in order:
     * <ol>
     *   <li>exact :Class.fqName</li>
     *   <li>exact :Class.simpleName</li>
     *   <li>file path ending with the input (e.g. "RalphLoop.java")</li>
     *   <li>:Class.fqName ENDS WITH ('.' + input) — handles partial qualifiers</li>
     * </ol>
     * Logs the resolution branch for diagnostics. Returns {@code null} if no class
     * (or interface) matches.
     */
    public String findClass(String identifier) {
        if (identifier == null || identifier.isBlank()) return null;
        String trimmed = identifier.trim();
        try (Session session = driver.session()) {
            // 1. exact fqName on Class or Interface
            Record rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (c) WHERE (c:Class OR c:Interface) AND c.fqName = $id " +
                        "RETURN c.fqName AS id LIMIT 1",
                    Map.of("id", trimmed)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) {
                String id = rec.get("id").asString();
                System.out.println("    [class-resolver] '" + identifier + "' → '" + id + "' (exact fqName)");
                return id;
            }

            // 2. exact simpleName
            rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (c) WHERE (c:Class OR c:Interface) AND c.simpleName = $name " +
                        "RETURN c.fqName AS id, count(*) AS total LIMIT 1",
                    Map.of("name", trimmed)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) {
                long total = session.executeRead(tx -> {
                    Result r = tx.run(
                        "MATCH (c) WHERE (c:Class OR c:Interface) AND c.simpleName = $name RETURN count(c) AS c",
                        Map.of("name", trimmed)
                    );
                    return r.hasNext() ? r.next().get("c").asLong() : 1L;
                });
                String id = rec.get("id").asString();
                System.out.println("    [class-resolver] '" + identifier + "' → '" + id
                        + "' (simpleName match, " + total + " candidate" + (total == 1 ? "" : "s") + ")");
                return id;
            }

            // 3. filePath ENDS WITH input (e.g. "RalphLoop.java" or "ralph/RalphLoop.java")
            if (trimmed.endsWith(".java")) {
                rec = session.executeRead(tx -> {
                    Result r = tx.run(
                        "MATCH (c) WHERE (c:Class OR c:Interface) AND c.filePath ENDS WITH $path " +
                            "RETURN c.fqName AS id LIMIT 1",
                        Map.of("path", trimmed)
                    );
                    return r.hasNext() ? r.next() : null;
                });
                if (rec != null) {
                    String id = rec.get("id").asString();
                    System.out.println("    [class-resolver] '" + identifier + "' → '" + id + "' (filePath ENDS WITH match)");
                    return id;
                }
            }

            // 4. fqName ends with '.<input>' — handles partial package qualifiers
            String suffix = "." + trimmed;
            rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (c) WHERE (c:Class OR c:Interface) AND c.fqName ENDS WITH $suffix " +
                        "RETURN c.fqName AS id LIMIT 1",
                    Map.of("suffix", suffix)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) {
                String id = rec.get("id").asString();
                System.out.println("    [class-resolver] '" + identifier + "' → '" + id + "' (fqName ENDS WITH match)");
                return id;
            }

            System.out.println("    [class-resolver] '" + identifier + "' unresolved");
            return null;
        }
    }

    /** Holder for class-level metadata returned by {@link #getClassOverview(String)}. */
    public static class ClassOverview {
        public String fqName;
        public String simpleName;
        public String signature;
        public String filePath;
        public String packageName;
        public String kind; // "Class" or "Interface"
        public List<String> annotations = new ArrayList<>();
        public List<String> extendedTypes = new ArrayList<>();
        public List<String> implementedTypes = new ArrayList<>();
        public List<String> imports = new ArrayList<>();
        public List<String> fieldDeclarations = new ArrayList<>();
        /** Method/constructor signatures (one entry per indexed method, deduped across parts). */
        public List<MethodSummary> methods = new ArrayList<>();

        public static class MethodSummary {
            public String chunkId;
            public String methodName;
            public String signature;
            public int totalParts;
            public MethodSummary(String chunkId, String methodName, String signature, int totalParts) {
                this.chunkId = chunkId;
                this.methodName = methodName;
                this.signature = signature;
                this.totalParts = totalParts;
            }
        }
    }

    /**
     * Fetch a complete class-level overview: signature, fields, imports, and the
     * full list of method (and constructor, if indexed) signatures. Method bodies
     * are NOT included — this is meant to give a class map cheaply.
     *
     * <p>Imports are sourced from any one method node in the class (they're stored
     * per-method but identical within a file).
     *
     * @return populated overview or {@code null} if the class is unknown
     */
    public ClassOverview getClassOverview(String fqName) {
        if (fqName == null || fqName.isBlank()) return null;
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // Class header
                Result classResult = tx.run(
                    "MATCH (c) WHERE (c:Class OR c:Interface) AND c.fqName = $id " +
                        "RETURN labels(c) AS labels, c.fqName AS fqName, c.simpleName AS simpleName, " +
                        "       c.signature AS signature, c.filePath AS filePath, " +
                        "       c.packageName AS packageName, c.annotations AS annotations, " +
                        "       c.extendedTypes AS extendedTypes, c.implementedTypes AS implementedTypes",
                    Map.of("id", fqName)
                );
                if (!classResult.hasNext()) return null;
                Record cr = classResult.next();

                ClassOverview overview = new ClassOverview();
                overview.fqName = cr.get("fqName").asString("");
                overview.simpleName = cr.get("simpleName").asString("");
                overview.signature = cr.get("signature").asString("");
                overview.filePath = cr.get("filePath").asString("");
                overview.packageName = cr.get("packageName").asString("");
                List<String> labels = cr.get("labels").asList(Value::asString);
                overview.kind = labels.contains("Interface") ? "Interface" : "Class";
                overview.annotations = nonNullStringList(cr.get("annotations"));
                overview.extendedTypes = nonNullStringList(cr.get("extendedTypes"));
                overview.implementedTypes = nonNullStringList(cr.get("implementedTypes"));

                // Fields via HAS_FIELD
                Result fieldResult = tx.run(
                    "MATCH (c {fqName: $id})-[:HAS_FIELD]->(f:Field) " +
                        "RETURN f.declaration AS decl ORDER BY f.name",
                    Map.of("id", fqName)
                );
                while (fieldResult.hasNext()) {
                    String decl = fieldResult.next().get("decl").asString("");
                    if (!decl.isEmpty()) overview.fieldDeclarations.add(decl);
                }

                // Methods via BELONGS_TO. Dedupe across parts (chunkId may end with #partN).
                // Pick the part-1 (or only) chunk for the canonical signature.
                Result methodResult = tx.run(
                    "MATCH (m:Method)-[:BELONGS_TO]->(c {fqName: $id}) " +
                        "RETURN m.chunkId AS chunkId, m.methodName AS methodName, " +
                        "       m.methodSignature AS signature, m.partIndex AS partIndex, " +
                        "       m.totalParts AS totalParts " +
                        "ORDER BY m.methodName, m.partIndex",
                    Map.of("id", fqName)
                );
                Map<String, ClassOverview.MethodSummary> byBaseId = new LinkedHashMap<>();
                while (methodResult.hasNext()) {
                    Record mr = methodResult.next();
                    String chunkId = mr.get("chunkId").asString("");
                    String name = mr.get("methodName").asString("");
                    String sig = mr.get("signature").asString("");
                    int partIndex = mr.get("partIndex").asInt(0);
                    int totalParts = mr.get("totalParts").asInt(1);
                    // Strip trailing #partN to get the base method id
                    String baseId = chunkId.replaceFirst("#part\\d+$", "");
                    ClassOverview.MethodSummary existing = byBaseId.get(baseId);
                    if (existing == null || partIndex < existing.totalParts /* prefer earlier part */) {
                        if (existing == null) {
                            byBaseId.put(baseId, new ClassOverview.MethodSummary(baseId, name, sig, totalParts));
                        }
                    }
                }
                overview.methods.addAll(byBaseId.values());

                // Imports — pulled from any one method node in this class (they're identical per file)
                Result importsResult = tx.run(
                    "MATCH (m:Method)-[:BELONGS_TO]->(c {fqName: $id}) " +
                        "WHERE m.imports IS NOT NULL " +
                        "RETURN m.imports AS imports LIMIT 1",
                    Map.of("id", fqName)
                );
                if (importsResult.hasNext()) {
                    overview.imports = nonNullStringList(importsResult.next().get("imports"));
                }

                return overview;
            });
        }
    }

    private static List<String> nonNullStringList(Value v) {
        if (v == null || v.isNull()) return new ArrayList<>();
        return new ArrayList<>(v.asList(Value::asString));
    }

    /**
     * Find the closest Method nodes using the Neo4j vector index.
     *
     * @param queryEmbedding the embedded query vector
     * @param k              number of nearest neighbours to return
     * @return list of chunkIds ordered by vector similarity (best first)
     */
    public List<String> vectorSearch(float[] queryEmbedding, int k) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // Convert float[] to List<Double> for Neo4j driver compatibility
                List<Double> embeddingList = new ArrayList<>(queryEmbedding.length);
                for (float v : queryEmbedding) {
                    embeddingList.add((double) v);
                }

                Result r = tx.run(
                    "CALL db.index.vector.queryNodes($indexName, $k, $embedding) " +
                    "YIELD node, score " +
                    "RETURN node.chunkId AS id, score " +
                    "ORDER BY score DESC",
                    Map.of(
                        "indexName", config.getVectorIndexName(),
                        "k", k,
                        "embedding", embeddingList
                    )
                );

                List<String> ids = new ArrayList<>();
                while (r.hasNext()) {
                    ids.add(r.next().get("id").asString());
                }
                return ids;
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 2 — Graph expansion (BFS with hop distance)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Expand from an anchor Method node, traversing CALLS, CALLED_BY, and BELONGS_TO
     * edges up to {@code maxDepth} hops. Returns a map of chunkId → hop distance.
     *
     * <p>The anchor itself is always included at distance 0.
     *
     * @param anchorChunkId the starting Method chunkId
     * @param maxDepth      maximum traversal depth (1–3 recommended)
     * @return map of discovered chunkId → minimum hop distance from anchor
     */
    public Map<String, Integer> expandSubgraph(String anchorChunkId, int maxDepth) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                // Use variable-length path to expand through call-graph and belongs-to edges.
                // We collect ALL nodes along the path and track the shortest hop distance.
                Result r = tx.run(
                    "MATCH (anchor:Method {chunkId: $anchorId}) " +
                    "OPTIONAL MATCH path = (anchor)-[:CALLS|CALLED_BY|BELONGS_TO*1.." + maxDepth + "]-(connected) " +
                    "WHERE connected:Method " +
                    "WITH anchor, connected, min(length(path)) AS hops " +
                    "RETURN coalesce(connected.chunkId, anchor.chunkId) AS id, coalesce(hops, 0) AS distance " +
                    "UNION " +
                    "MATCH (anchor:Method {chunkId: $anchorId}) " +
                    "RETURN anchor.chunkId AS id, 0 AS distance",
                    Map.of("anchorId", anchorChunkId)
                );

                Map<String, Integer> result = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    String id = rec.get("id").asString();
                    int dist = rec.get("distance").asInt();
                    // Keep the minimum distance if we see duplicates
                    result.merge(id, dist, Math::min);
                }
                return result;
            });
        }
    }

    /**
     * Find all sibling methods in the same class as the given Method node.
     *
     * @param chunkId the method's chunkId
     * @return list of chunkIds of methods in the same class
     */
    public List<String> getSameClassMethods(String chunkId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method {chunkId: $id})-[:BELONGS_TO]->(c) " +
                    "MATCH (sibling:Method)-[:BELONGS_TO]->(c) " +
                    "WHERE sibling.chunkId <> $id " +
                    "RETURN sibling.chunkId AS id",
                    Map.of("id", chunkId)
                );

                List<String> ids = new ArrayList<>();
                while (r.hasNext()) {
                    ids.add(r.next().get("id").asString());
                }
                return ids;
            });
        }
    }

    /**
     * Get the owning class FQN and package name for a Method node.
     *
     * @return a 2-element array: [fqClassName, packageName], or null if not found
     */
    public String[] getMethodContext(String chunkId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method {chunkId: $id}) RETURN m.fqClassName AS cls, m.packageName AS pkg",
                    Map.of("id", chunkId)
                );
                if (!r.hasNext()) return null;
                Record rec = r.next();
                return new String[]{rec.get("cls").asString(), rec.get("pkg").asString()};
            });
        }
    }

    /**
     * Count how many callers (CALLED_BY incoming edges) a method has.
     */
    public int getCallerCount(String chunkId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method {chunkId: $id})<-[:CALLS]-(caller:Method) RETURN count(caller) AS cnt",
                    Map.of("id", chunkId)
                );
                return r.hasNext() ? r.next().get("cnt").asInt() : 0;
            });
        }
    }

    /**
     * Batch-fetch caller counts for multiple methods in a single query.
     *
     * <p>Returns a map of chunkId -> caller count. Methods with zero callers
     * will be present with a count of 0. If the provided collection is empty
     * an empty map is returned.
     */
    public Map<String, Integer> getCallerCountsBatch(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "UNWIND $ids AS id " +
                    "OPTIONAL MATCH (m:Method {chunkId:id})<-[:CALLS]-(caller:Method) " +
                    "RETURN id AS id, count(caller) AS cnt",
                    Map.of("ids", new ArrayList<>(chunkIds))
                );

                Map<String, Integer> out = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    out.put(rec.get("id").asString(), rec.get("cnt").asInt());
                }
                return out;
            });
        }
    }

    /**
     * Batch-fetch callee counts (fan-out) for multiple methods in a single query.
     */
    public Map<String, Integer> getCalleeCountsBatch(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return Map.of();

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "UNWIND $ids AS id " +
                    "OPTIONAL MATCH (m:Method {chunkId:id})-[:CALLS]->(callee:Method) " +
                    "RETURN id AS id, count(callee) AS cnt",
                    Map.of("ids", new ArrayList<>(chunkIds))
                );

                Map<String, Integer> out = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    out.put(rec.get("id").asString(), rec.get("cnt").asInt());
                }
                return out;
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3 — Hydrate Method nodes into CodeChunk objects
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch full Method node properties and hydrate into {@link CodeChunk} objects.
     *
     * @param chunkIds the set of chunkIds to fetch
     * @return map of chunkId → CodeChunk
     */
    public Map<String, CodeChunk> fetchMethodChunks(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) return Map.of();

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.chunkId IN $ids RETURN m",
                    Map.of("ids", new ArrayList<>(chunkIds))
                );

                Map<String, CodeChunk> result = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Node node = r.next().get("m").asNode();
                    CodeChunk chunk = hydrateCodeChunk(node);
                    result.put(chunk.getChunkId(), chunk);
                }
                return result;
            });
        }
    }

    /**
     * Fetch the stored embedding vector for a Method node.
     *
     * @return the embedding vector, or null if not set
     */
    public float[] getStoredEmbedding(String chunkId) {
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method {chunkId: $id}) RETURN m.embedding AS emb",
                    Map.of("id", chunkId)
                );
                if (!r.hasNext()) return null;
                Value embValue = r.next().get("emb");
                if (embValue.isNull()) return null;

                List<Object> embList = embValue.asList();
                float[] embedding = new float[embList.size()];
                for (int i = 0; i < embList.size(); i++) {
                    embedding[i] = ((Number) embList.get(i)).floatValue();
                }
                return embedding;
            });
        }
    }

    /**
     * Batch-fetch stored embeddings for multiple Method nodes.
     *
     * @return map of chunkId → embedding (only for nodes that have embeddings)
     */
    public Map<String, float[]> getStoredEmbeddings(Collection<String> chunkIds) {
        if (chunkIds.isEmpty()) return Map.of();

        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.chunkId IN $ids AND m.embedding IS NOT NULL " +
                    "RETURN m.chunkId AS id, m.embedding AS emb",
                    Map.of("ids", new ArrayList<>(chunkIds))
                );

                Map<String, float[]> result = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    String id = rec.get("id").asString();
                    List<Object> embList = rec.get("emb").asList();
                    float[] embedding = new float[embList.size()];
                    for (int i = 0; i < embList.size(); i++) {
                        embedding[i] = ((Number) embList.get(i)).floatValue();
                    }
                    result.put(id, embedding);
                }
                return result;
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Hydration helper
    // ═══════════════════════════════════════════════════════════════

    private CodeChunk hydrateCodeChunk(Node node) {
        CodeChunk chunk = new CodeChunk();

        chunk.setChunkId(getStr(node, "chunkId"));
        chunk.setMethodName(getStr(node, "methodName"));
        chunk.setMethodSignature(getStr(node, "methodSignature"));
        chunk.setClassName(getStr(node, "className"));
        chunk.setFullyQualifiedClassName(getStr(node, "fqClassName"));
        chunk.setClassSignature(getStr(node, "classSignature"));
        chunk.setFilePath(getStr(node, "filePath"));
        chunk.setPackageName(getStr(node, "packageName"));
        chunk.setCode(getStr(node, "code"));
        chunk.setTokenCount(getInt(node, "tokenCount"));
        chunk.setStartLine(getInt(node, "startLine"));
        chunk.setEndLine(getInt(node, "endLine"));
        chunk.setPartIndex(getInt(node, "partIndex"));
        chunk.setTotalParts(getInt(node, "totalParts"));

        chunk.setMethodAnnotations(getStrList(node, "methodAnnotations"));
        chunk.setClassAnnotations(getStrList(node, "classAnnotations"));
        chunk.setFieldDeclarations(getStrList(node, "fieldDeclarations"));
        chunk.setImports(getStrList(node, "imports"));

        return chunk;
    }

    private String getStr(Node node, String key) {
        Value v = node.get(key);
        return v.isNull() ? "" : v.asString();
    }

    private int getInt(Node node, String key) {
        Value v = node.get(key);
        return v.isNull() ? 0 : v.asInt();
    }

    private List<String> getStrList(Node node, String key) {
        Value v = node.get(key);
        if (v.isNull()) return new ArrayList<>();
        return v.asList(Value::asString);
    }

    // ═══════════════════════════════════════════════════════════════
    // Path / topology queries (for graph-aware LLM context)
    // ═══════════════════════════════════════════════════════════════

    private static final int PATH_MAX_HOPS_CEILING = 6;

    private static int clampHops(int maxHops) {
        if (maxHops < 1) return 1;
        if (maxHops > PATH_MAX_HOPS_CEILING) return PATH_MAX_HOPS_CEILING;
        return maxHops;
    }

    /**
     * Find the shortest directed CALLS path(s) between two methods.
     *
     * <p>Returns up to {@link RetrievalConfig#getMaxPathsReturned()} shortest
     * paths. Returns a single length-0 path when {@code fromChunkId == toChunkId}.
     * Returns an empty list when no directed path of length ≤ {@code maxHops} exists.
     *
     * @param maxHops clamped to {@code [1, 6]}
     */
    public List<GraphPath> findShortestCallPaths(String fromChunkId, String toChunkId, int maxHops) {
        if (fromChunkId == null || toChunkId == null) return List.of();
        if (fromChunkId.equals(toChunkId)) {
            return List.of(GraphPath.single(fromChunkId));
        }
        int hops = clampHops(maxHops);
        int cap = Math.max(1, config.getMaxPathsReturned());
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (a:Method {chunkId:$from}), (b:Method {chunkId:$to}) " +
                    "MATCH p = allShortestPaths((a)-[:CALLS*.." + hops + "]->(b)) " +
                    "RETURN [n IN nodes(p) | n.chunkId] AS ns, " +
                    "       [rel IN relationships(p) | type(rel)] AS ts " +
                    "LIMIT $cap",
                    Map.of("from", fromChunkId, "to", toChunkId, "cap", cap)
                );
                List<GraphPath> paths = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    List<String> nodes = rec.get("ns").asList(Value::asString);
                    List<String> types = rec.get("ts").asList(Value::asString);
                    paths.add(buildDirectedPath(nodes, types));
                }
                return paths;
            });
        }
    }

    /**
     * Find the shortest undirected path(s) between two methods, traversing
     * either {@code CALLS} or {@code CALLED_BY} edges. Used as a fallback when
     * no purely directed call path exists.
     *
     * <p>Per-edge direction is reconstructed by comparing the edge's
     * {@code startNode.chunkId} against the previous node in the path.
     */
    public List<GraphPath> findUndirectedPaths(String fromChunkId, String toChunkId, int maxHops) {
        if (fromChunkId == null || toChunkId == null) return List.of();
        if (fromChunkId.equals(toChunkId)) {
            return List.of(GraphPath.single(fromChunkId));
        }
        int hops = clampHops(maxHops);
        int cap = Math.max(1, config.getMaxPathsReturned());
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (a:Method {chunkId:$from}), (b:Method {chunkId:$to}) " +
                    "MATCH p = allShortestPaths((a)-[:CALLS|CALLED_BY*.." + hops + "]-(b)) " +
                    "RETURN [n IN nodes(p) | n.chunkId] AS ns, " +
                    "       [rel IN relationships(p) | {t: type(rel), s: startNode(rel).chunkId}] AS rs " +
                    "LIMIT $cap",
                    Map.of("from", fromChunkId, "to", toChunkId, "cap", cap)
                );
                List<GraphPath> paths = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    List<String> nodes = rec.get("ns").asList(Value::asString);
                    List<Value> rs = rec.get("rs").asList(v -> v);
                    List<PathEdge> edges = new ArrayList<>(rs.size());
                    for (int i = 0; i < rs.size(); i++) {
                        String type = rs.get(i).get("t").asString();
                        String startId = rs.get(i).get("s").asString();
                        String src = nodes.get(i);
                        String tgt = nodes.get(i + 1);
                        PathEdge.Direction dir = startId.equals(src)
                            ? PathEdge.Direction.OUT
                            : PathEdge.Direction.IN;
                        edges.add(new PathEdge(src, tgt, type, dir));
                    }
                    paths.add(new GraphPath(nodes, edges));
                }
                return paths;
            });
        }
    }

    /**
     * Return all {@code CALLS} edges induced among the given set of method nodes.
     * Used to render the topology of a retrieved subgraph.
     */
    public List<PathEdge> getInducedEdges(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.size() < 2) return List.of();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (a:Method)-[rel:CALLS]->(b:Method) " +
                    "WHERE a.chunkId IN $ids AND b.chunkId IN $ids " +
                    "RETURN DISTINCT a.chunkId AS src, b.chunkId AS tgt, type(rel) AS t",
                    Map.of("ids", new ArrayList<>(chunkIds))
                );
                List<PathEdge> edges = new ArrayList<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    edges.add(new PathEdge(
                        rec.get("src").asString(),
                        rec.get("tgt").asString(),
                        rec.get("t").asString(),
                        PathEdge.Direction.OUT
                    ));
                }
                return edges;
            });
        }
    }

    /**
     * Batch-compute the shortest {@code CALLS} path from an anchor to each
     * target chunkId. Absent entries in the returned map indicate no path
     * was found within {@code maxHops}. The anchor maps to a length-0 path.
     */
    public Map<String, GraphPath> getShortestPathsFromAnchor(String anchorId,
                                                              Collection<String> targetIds,
                                                              int maxHops) {
        if (anchorId == null || targetIds == null || targetIds.isEmpty()) return Map.of();
        int hops = clampHops(maxHops);
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (a:Method {chunkId:$anchor}) " +
                    "UNWIND $targets AS tid " +
                    "OPTIONAL MATCH (b:Method {chunkId:tid}) " +
                    "CALL { " +
                    "  WITH a, b " +
                    "  OPTIONAL MATCH p = (a)-[:CALLS*0.." + hops + "]->(b) " +
                    "  WITH p ORDER BY length(p) ASC LIMIT 1 " +
                    "  RETURN p " +
                    "} " +
                    "RETURN tid, " +
                    "       CASE WHEN p IS NULL THEN null ELSE [n IN nodes(p) | n.chunkId] END AS ns, " +
                    "       CASE WHEN p IS NULL THEN null ELSE [rel IN relationships(p) | type(rel)] END AS ts",
                    Map.of("anchor", anchorId, "targets", new ArrayList<>(targetIds))
                );
                Map<String, GraphPath> out = new LinkedHashMap<>();
                while (r.hasNext()) {
                    Record rec = r.next();
                    String tid = rec.get("tid").asString();
                    Value nsVal = rec.get("ns");
                    Value tsVal = rec.get("ts");
                    if (nsVal.isNull() || tsVal.isNull()) continue;
                    List<String> nodes = nsVal.asList(Value::asString);
                    List<String> types = tsVal.asList(Value::asString);
                    if (nodes.isEmpty()) continue;
                    out.put(tid, buildDirectedPath(nodes, types));
                }
                return out;
            });
        }
    }

    /**
     * Return the immediate (1-hop) {@code CALLS} neighbors of a method,
     * separated by direction. Outgoing edges use {@link PathEdge.Direction#OUT OUT}
     * (this method calls the neighbor) and incoming use {@link PathEdge.Direction#IN IN}
     * (the neighbor calls this method).
     */
    public List<PathEdge> getImmediateNeighbors(String chunkId) {
        if (chunkId == null) return List.of();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<PathEdge> edges = new ArrayList<>();
                Result outs = tx.run(
                    "MATCH (m:Method {chunkId:$id})-[rel:CALLS]->(n:Method) " +
                    "RETURN m.chunkId AS src, n.chunkId AS tgt, type(rel) AS t",
                    Map.of("id", chunkId)
                );
                while (outs.hasNext()) {
                    Record rec = outs.next();
                    edges.add(new PathEdge(
                        rec.get("src").asString(),
                        rec.get("tgt").asString(),
                        rec.get("t").asString(),
                        PathEdge.Direction.OUT
                    ));
                }
                Result ins = tx.run(
                    "MATCH (n:Method)-[rel:CALLS]->(m:Method {chunkId:$id}) " +
                    "RETURN n.chunkId AS src, m.chunkId AS tgt, type(rel) AS t",
                    Map.of("id", chunkId)
                );
                while (ins.hasNext()) {
                    Record rec = ins.next();
                    edges.add(new PathEdge(
                        rec.get("src").asString(),
                        rec.get("tgt").asString(),
                        rec.get("t").asString(),
                        PathEdge.Direction.IN
                    ));
                }
                return edges;
            });
        }
    }

    private static GraphPath buildDirectedPath(List<String> nodes, List<String> types) {
        if (nodes.size() == 1 && types.isEmpty()) {
            return GraphPath.single(nodes.get(0));
        }
        List<PathEdge> edges = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            edges.add(new PathEdge(nodes.get(i), nodes.get(i + 1), types.get(i), PathEdge.Direction.OUT));
        }
        return new GraphPath(nodes, edges);
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}

