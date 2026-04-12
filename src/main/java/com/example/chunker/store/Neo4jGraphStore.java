package com.example.chunker.store;

import com.example.chunker.model.CodeChunk;
import com.example.chunker.model.graph.ClassNode;
import com.example.chunker.model.graph.FieldNode;
import com.example.chunker.model.graph.GraphEdge;
import com.example.chunker.model.graph.GraphModel;

import org.neo4j.driver.*;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;

import com.example.chunker.retrieval.EmbeddingService;
import com.example.chunker.retrieval.RetrievalConfig;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists the {@link GraphModel} into a Neo4j graph database.
 *
 * <h3>Schema</h3>
 * <pre>
 * Node labels:
 *   :Package   { name }
 *   :Class     { fqName, simpleName, signature, filePath, packageName, annotations }
 *   :Interface { fqName, simpleName, signature, filePath, packageName, annotations }
 *   :Field     { fqName, name, declaration, type, owningClassFqn }
 *   :Method    { chunkId, methodName, methodSignature, className, fqClassName,
 *                filePath, packageName, code, tokenCount, startLine, endLine,
 *                partIndex, totalParts, classSignature, annotations }
 *
 * Relationships:
 *   (:Method)-[:CALLS]->(:Method)
 *   (:Method)-[:CALLED_BY]->(:Method)
 *   (:Method)-[:BELONGS_TO]->(:Class|:Interface)
 *   (:Class|:Interface)-[:HAS_FIELD]->(:Field)
 *   (:Class)-[:IMPLEMENTS]->(:Interface)
 *   (:Class|:Interface)-[:EXTENDS]->(:Class|:Interface)
 *   (:Package)-[:CONTAINS]->(:Class|:Interface)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   try (Neo4jGraphStore store = new Neo4jGraphStore("bolt://localhost:7687", "neo4j", "password")) {
 *       store.initSchema();
 *       store.store(graphModel);
 *   }
 * </pre>
 */
public class Neo4jGraphStore implements AutoCloseable {

    private static final int BATCH_SIZE = 500;

    private final Driver driver;

    /**
     * Create a new store connected to a Neo4j instance.
     *
     * @param uri      bolt URI, e.g. "bolt://localhost:7687"
     * @param user     Neo4j username
     * @param password Neo4j password
     */
    public Neo4jGraphStore(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        // Verify connectivity
        driver.verifyConnectivity();
        System.out.println("✓ Connected to Neo4j at " + uri);
    }

    /**
     * Create uniqueness constraints and indexes for all node types.
     */
    public void initSchema() {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (p:Package) REQUIRE p.name IS UNIQUE");
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (c:Class) REQUIRE c.fqName IS UNIQUE");
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (i:Interface) REQUIRE i.fqName IS UNIQUE");
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (f:Field) REQUIRE f.fqName IS UNIQUE");
                tx.run("CREATE CONSTRAINT IF NOT EXISTS FOR (m:Method) REQUIRE m.chunkId IS UNIQUE");
                return null;
            });
        }
        System.out.println("✓ Neo4j schema constraints initialized.");
    }

    /**
     * Create a Neo4j vector index on :Method nodes for embedding-based similarity search.
     * Requires Neo4j 5.11+ with vector index support.
     *
     * @param indexName  name for the vector index (e.g. "method_embeddings")
     * @param dimensions embedding vector dimensions (e.g. 768 for nomic-embed)
     */
    public void initVectorIndex(String indexName, int dimensions) {
        try (Session session = driver.session()) {
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
        }
        System.out.println("✓ Neo4j vector index '" + indexName + "' initialized (dims=" + dimensions + ").");
    }

    /**
     * Compute and store embeddings for all Method nodes in the graph model.
     *
     * <p>For each {@link CodeChunk}, builds an embedding text from the class name,
     * method signature, and code, then stores the resulting vector as the
     * {@code embedding} property on the :Method node in Neo4j.
     *
     * @param model            the graph model containing method nodes
     * @param embeddingService the embedding service to compute vectors
     */
    public void storeEmbeddings(GraphModel model, EmbeddingService embeddingService) {
        List<CodeChunk> methods = model.getMethodNodes();
        if (methods.isEmpty()) return;

        System.out.println("Computing and storing embeddings for " + methods.size() + " methods...");

        // Build texts to embed
        List<String> texts = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (CodeChunk chunk : methods) {
            StringBuilder sb = new StringBuilder();
            sb.append(chunk.getClassName()).append(" ");
            sb.append(chunk.getMethodSignature()).append("\n");
            sb.append(chunk.getCode());
            texts.add(sb.toString());
            chunkIds.add(chunk.getChunkId());
        }

        // Batch embed
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        // Store in Neo4j in batches
        int embBatchSize = 100;
        try (Session session = driver.session()) {
            for (int i = 0; i < chunkIds.size(); i += embBatchSize) {
                int end = Math.min(i + embBatchSize, chunkIds.size());
                List<Map<String, Object>> batch = new ArrayList<>();

                for (int j = i; j < end; j++) {
                    float[] emb = embeddings.get(j);
                    List<Double> embList = new ArrayList<>(emb.length);
                    for (float v : emb) {
                        embList.add((double) v);
                    }
                    batch.add(Map.of("id", chunkIds.get(j), "embedding", embList));
                }

                final List<Map<String, Object>> finalBatch = batch;
                session.executeWrite(tx -> {
                    tx.run(
                        "UNWIND $batch AS row " +
                        "MATCH (m:Method {chunkId: row.id}) " +
                        "SET m.embedding = row.embedding",
                        Map.of("batch", finalBatch)
                    );
                    return null;
                });
            }
        }

        System.out.printf("  ✓ Stored embeddings for %d methods%n", chunkIds.size());
    }

    /**
     * Optionally wipe all data before a fresh import.
     */
    public void cleanAll() {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                return null;
            });
        }
        System.out.println("✓ Neo4j database cleaned.");
    }

    /**
     * Persist the entire {@link GraphModel} — all nodes and edges — into Neo4j.
     * Uses MERGE for idempotent upserts (safe to run multiple times).
     */
    public void store(GraphModel model) {
        System.out.println("Persisting graph to Neo4j...");

        // ── 1. Upsert Package nodes ──
        upsertPackages(model.getPackageNodes());

        // ── 2. Upsert Class/Interface nodes ──
        upsertClassNodes(model.getClassNodes().values());

        // ── 3. Upsert Field nodes ──
        upsertFieldNodes(model.getFieldNodes().values());

        // ── 4. Upsert Method nodes ──
        upsertMethodNodes(model.getMethodNodes());

        // ── 5. Create all edges ──
        createEdges(model.getEdges());

        System.out.println("✓ Graph persisted to Neo4j.");
    }

    // ═══════════════════════════════════════════════════════════════
    // Node upserts
    // ═══════════════════════════════════════════════════════════════

    private void upsertPackages(Set<String> packages) {
        List<Map<String, Object>> batch = packages.stream()
            .filter(p -> p != null && !p.isEmpty())
            .map(p -> Map.<String, Object>of("name", p))
            .toList();

        executeBatched("UNWIND $batch AS row MERGE (p:Package {name: row.name})", batch, "Package");
    }

    private void upsertClassNodes(Collection<ClassNode> classNodes) {
        List<Map<String, Object>> classBatch = new ArrayList<>();
        List<Map<String, Object>> ifaceBatch = new ArrayList<>();

        for (ClassNode cn : classNodes) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("fqName", cn.getFqName());
            props.put("simpleName", cn.getSimpleName());
            props.put("signature", cn.getSignature());
            props.put("filePath", cn.getFilePath());
            props.put("packageName", cn.getPackageName());
            props.put("annotations", cn.getAnnotations());
            props.put("extendedTypes", cn.getExtendedTypes());
            props.put("implementedTypes", cn.getImplementedTypes());

            if (cn.isInterface()) {
                ifaceBatch.add(props);
            } else {
                classBatch.add(props);
            }
        }

        executeBatched(
            "UNWIND $batch AS row " +
            "MERGE (c:Class {fqName: row.fqName}) " +
            "SET c.simpleName = row.simpleName, " +
            "    c.signature = row.signature, " +
            "    c.filePath = row.filePath, " +
            "    c.packageName = row.packageName, " +
            "    c.annotations = row.annotations, " +
            "    c.extendedTypes = row.extendedTypes, " +
            "    c.implementedTypes = row.implementedTypes",
            classBatch, "Class"
        );

        executeBatched(
            "UNWIND $batch AS row " +
            "MERGE (i:Interface {fqName: row.fqName}) " +
            "SET i.simpleName = row.simpleName, " +
            "    i.signature = row.signature, " +
            "    i.filePath = row.filePath, " +
            "    i.packageName = row.packageName, " +
            "    i.annotations = row.annotations, " +
            "    i.extendedTypes = row.extendedTypes, " +
            "    i.implementedTypes = row.implementedTypes",
            ifaceBatch, "Interface"
        );
    }

    private void upsertFieldNodes(Collection<FieldNode> fieldNodes) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (FieldNode fn : fieldNodes) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("fqName", fn.getFqName());
            props.put("name", fn.getName());
            props.put("declaration", fn.getDeclaration());
            props.put("type", fn.getType());
            props.put("owningClassFqn", fn.getOwningClassFqn());
            batch.add(props);
        }

        executeBatched(
            "UNWIND $batch AS row " +
            "MERGE (f:Field {fqName: row.fqName}) " +
            "SET f.name = row.name, " +
            "    f.declaration = row.declaration, " +
            "    f.type = row.type, " +
            "    f.owningClassFqn = row.owningClassFqn",
            batch, "Field"
        );
    }

    private void upsertMethodNodes(List<CodeChunk> methods) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (CodeChunk chunk : methods) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("chunkId", chunk.getChunkId());
            props.put("methodName", chunk.getMethodName());
            props.put("methodSignature", chunk.getMethodSignature());
            props.put("className", chunk.getClassName());
            props.put("fqClassName", chunk.getFullyQualifiedClassName());
            props.put("classSignature", chunk.getClassSignature());
            props.put("filePath", chunk.getFilePath());
            props.put("packageName", chunk.getPackageName());
            props.put("code", chunk.getCode());
            props.put("tokenCount", chunk.getTokenCount());
            props.put("startLine", chunk.getStartLine());
            props.put("endLine", chunk.getEndLine());
            props.put("partIndex", chunk.getPartIndex());
            props.put("totalParts", chunk.getTotalParts());
            props.put("methodAnnotations", chunk.getMethodAnnotations());
            props.put("classAnnotations", chunk.getClassAnnotations());
            props.put("fieldDeclarations", chunk.getFieldDeclarations());
            props.put("imports", chunk.getImports());
            batch.add(props);
        }

        executeBatched(
            "UNWIND $batch AS row " +
            "MERGE (m:Method {chunkId: row.chunkId}) " +
            "SET m.methodName = row.methodName, " +
            "    m.methodSignature = row.methodSignature, " +
            "    m.className = row.className, " +
            "    m.fqClassName = row.fqClassName, " +
            "    m.classSignature = row.classSignature, " +
            "    m.filePath = row.filePath, " +
            "    m.packageName = row.packageName, " +
            "    m.code = row.code, " +
            "    m.tokenCount = row.tokenCount, " +
            "    m.startLine = row.startLine, " +
            "    m.endLine = row.endLine, " +
            "    m.partIndex = row.partIndex, " +
            "    m.totalParts = row.totalParts, " +
            "    m.methodAnnotations = row.methodAnnotations, " +
            "    m.classAnnotations = row.classAnnotations, " +
            "    m.fieldDeclarations = row.fieldDeclarations, " +
            "    m.imports = row.imports",
            batch, "Method"
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Edge creation
    // ═══════════════════════════════════════════════════════════════

    private void createEdges(List<GraphEdge> edges) {
        // Group edges by type for efficient batched Cypher
        Map<GraphEdge.EdgeType, List<GraphEdge>> byType = edges.stream()
            .collect(Collectors.groupingBy(GraphEdge::getType));

        for (var entry : byType.entrySet()) {
            GraphEdge.EdgeType type = entry.getKey();
            List<Map<String, Object>> batch = entry.getValue().stream()
                .map(e -> Map.<String, Object>of("src", e.getSourceFqn(), "tgt", e.getTargetFqn()))
                .toList();

            String cypher = switch (type) {
                case CALLS -> buildEdgeCypher("Method", "chunkId", "Method", "chunkId", "CALLS");
                case CALLED_BY -> buildEdgeCypher("Method", "chunkId", "Method", "chunkId", "CALLED_BY");
                case BELONGS_TO -> buildBelongsToEdgeCypher();
                case HAS_FIELD -> buildHasFieldEdgeCypher();
                case IMPLEMENTS -> buildImplementsEdgeCypher();
                case EXTENDS -> buildExtendsEdgeCypher();
                case CONTAINS -> buildContainsEdgeCypher();
            };

            executeBatched(cypher, batch, type.name() + " edges");
        }
    }

    /**
     * Build edge Cypher for simple same-label-to-same-label relationships.
     */
    private String buildEdgeCypher(String srcLabel, String srcKey, String tgtLabel, String tgtKey, String relType) {
        return "UNWIND $batch AS row " +
            "MATCH (a:" + srcLabel + " {" + srcKey + ": row.src}) " +
            "MATCH (b:" + tgtLabel + " {" + tgtKey + ": row.tgt}) " +
            "MERGE (a)-[:" + relType + "]->(b)";
    }

    /**
     * BELONGS_TO: Method → Class or Interface.
     * Try Class first, then Interface (using OPTIONAL MATCH + COALESCE pattern).
     */
    private String buildBelongsToEdgeCypher() {
        return "UNWIND $batch AS row " +
            "MATCH (m:Method {chunkId: row.src}) " +
            "OPTIONAL MATCH (c:Class {fqName: row.tgt}) " +
            "OPTIONAL MATCH (i:Interface {fqName: row.tgt}) " +
            "WITH m, coalesce(c, i) AS target " +
            "WHERE target IS NOT NULL " +
            "MERGE (m)-[:BELONGS_TO]->(target)";
    }

    /**
     * HAS_FIELD: Class/Interface → Field.
     */
    private String buildHasFieldEdgeCypher() {
        return "UNWIND $batch AS row " +
            "MATCH (f:Field {fqName: row.tgt}) " +
            "OPTIONAL MATCH (c:Class {fqName: row.src}) " +
            "OPTIONAL MATCH (i:Interface {fqName: row.src}) " +
            "WITH f, coalesce(c, i) AS owner " +
            "WHERE owner IS NOT NULL " +
            "MERGE (owner)-[:HAS_FIELD]->(f)";
    }

    /**
     * IMPLEMENTS: Class → Interface.
     * The target may be an external interface we haven't parsed — create a stub Interface node.
     */
    private String buildImplementsEdgeCypher() {
        return "UNWIND $batch AS row " +
            "OPTIONAL MATCH (c:Class {fqName: row.src}) " +
            "OPTIONAL MATCH (ci:Interface {fqName: row.src}) " +
            "WITH row, coalesce(c, ci) AS source " +
            "WHERE source IS NOT NULL " +
            "MERGE (target:Interface {fqName: row.tgt}) " +
            "MERGE (source)-[:IMPLEMENTS]->(target)";
    }

    /**
     * EXTENDS: Class → Class or Interface → Interface.
     * Creates a stub Class target node if no existing Class or Interface node is found.
     */
    private String buildExtendsEdgeCypher() {
        return "UNWIND $batch AS row " +
            "OPTIONAL MATCH (sc:Class {fqName: row.src}) " +
            "OPTIONAL MATCH (si:Interface {fqName: row.src}) " +
            "WITH row, coalesce(sc, si) AS source " +
            "WHERE source IS NOT NULL " +
            "OPTIONAL MATCH (tc:Class {fqName: row.tgt}) " +
            "OPTIONAL MATCH (ti:Interface {fqName: row.tgt}) " +
            "WITH source, row, coalesce(tc, ti) AS target " +
            "FOREACH (_ IN CASE WHEN target IS NOT NULL THEN [1] ELSE [] END | " +
            "  MERGE (source)-[:EXTENDS]->(target) " +
            ") " +
            "FOREACH (_ IN CASE WHEN target IS NULL THEN [1] ELSE [] END | " +
            "  MERGE (stub:Class {fqName: row.tgt}) " +
            "  MERGE (source)-[:EXTENDS]->(stub) " +
            ")";
    }

    /**
     * CONTAINS: Package → Class or Interface.
     */
    private String buildContainsEdgeCypher() {
        return "UNWIND $batch AS row " +
            "MATCH (p:Package {name: row.src}) " +
            "OPTIONAL MATCH (c:Class {fqName: row.tgt}) " +
            "OPTIONAL MATCH (i:Interface {fqName: row.tgt}) " +
            "WITH p, coalesce(c, i) AS target " +
            "WHERE target IS NOT NULL " +
            "MERGE (p)-[:CONTAINS]->(target)";
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch execution helper
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute a Cypher statement in batches of {@link #BATCH_SIZE}.
     */
    private void executeBatched(String cypher, List<Map<String, Object>> data, String label) {
        if (data.isEmpty()) return;

        int totalCreated = 0;
        try (Session session = driver.session()) {
            for (int i = 0; i < data.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, data.size());
                final List<Map<String, Object>> finalSlice = data.subList(i, end);
                ResultSummary summary = session.executeWrite(tx -> {
                    Result result = tx.run(cypher, Map.of("batch", finalSlice));
                    return result.consume();
                });

                SummaryCounters counters = summary.counters();
                int created = counters.nodesCreated() + counters.relationshipsCreated();
                totalCreated += created;
            }
        }

        System.out.printf("  ✓ %s: %d items processed (%d created/merged)%n", label, data.size(), totalCreated);
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            System.out.println("✓ Neo4j connection closed.");
        }
    }
}

