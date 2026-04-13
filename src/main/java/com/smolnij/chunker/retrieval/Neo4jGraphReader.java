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
            if (rec != null) return rec.get("id").asString();

            // Try matching by methodName (e.g. "createUser")
            rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.methodName = $name RETURN m.chunkId AS id LIMIT 1",
                    Map.of("name", identifier)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) return rec.get("id").asString();

            // Try contains match on chunkId (e.g. "UserService.createUser" matches "com.example.UserService#createUser(...)")
            rec = session.executeRead(tx -> {
                Result r = tx.run(
                    "MATCH (m:Method) WHERE m.chunkId CONTAINS $fragment RETURN m.chunkId AS id LIMIT 1",
                    Map.of("fragment", identifier)
                );
                return r.hasNext() ? r.next() : null;
            });
            if (rec != null) return rec.get("id").asString();

            return null;
        }
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

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
        }
    }
}

