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

