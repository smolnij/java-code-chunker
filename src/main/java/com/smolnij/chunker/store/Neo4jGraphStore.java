package com.smolnij.chunker.store;

import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.ClassNode;
import com.smolnij.chunker.model.graph.FieldNode;
import com.smolnij.chunker.model.graph.GraphEdge;
import com.smolnij.chunker.model.graph.GraphModel;

import org.neo4j.driver.*;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;

import com.smolnij.chunker.retrieval.EmbeddingService;

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
     * Delete graph elements that disappeared from a set of files between
     * two re-indexings. Used by the post-apply delta re-indexer.
     *
     * <p>The caller has just rebuilt a {@link GraphModel} for {@code filePaths}
     * (the files modified by {@link com.smolnij.chunker.apply.PatchApplier})
     * and is about to call {@link #store(GraphModel)} to upsert the fresh
     * nodes/edges. Before that, this method removes anything in those files
     * that the new model no longer contains:
     * <ul>
     *   <li>{@code :Method} nodes whose {@code filePath} matches but whose
     *       {@code chunkId} is not in {@code keepMethodChunkIds} (renames
     *       and explicit deletions).</li>
     *   <li>{@code :Field} nodes owned by classes whose {@code filePath}
     *       matches but whose {@code fqName} is not in {@code keepFieldFqns}.</li>
     *   <li>Outgoing relationships from kept methods (CALLS, CALLED_BY,
     *       USES_TYPE, RETURNS_TYPE, THROWS, TEST_FOR, BELONGS_TO) — these
     *       will be recreated by the subsequent {@code store(...)} call so
     *       MERGE doesn't accumulate stale edges from a prior code shape.</li>
     *   <li>Outgoing relationships from kept classes (IMPORTS, EXTENDS,
     *       IMPLEMENTS, INNER_CLASS_OF) for the same reason.</li>
     * </ul>
     *
     * <p>Inbound edges from <em>unchanged</em> files are not touched here —
     * Neo4j's {@code DETACH DELETE} on a removed method automatically drops
     * dangling relationships. To repair them after the new (renamed) nodes
     * are upserted, call {@link #captureBeforeSnapshot} BEFORE this prune,
     * then {@link #recreateInboundEdges} after the subsequent
     * {@link #store(GraphModel)}; that flow re-points the inbound edges at
     * the renamed targets so call-graph topology is preserved across
     * renames in unchanged caller files. The caller's stored
     * {@code :Method.code} text still reflects its on-disk source — the
     * repair operates on edges, not source.
     *
     * @param filePaths           repo-relative file paths just touched by the apply
     * @param keepMethodChunkIds  method chunk ids that remain in the new model
     * @param keepFieldFqns       field FQNs that remain in the new model
     * @param keepClassFqns       class FQNs whose outgoing edges should be wiped
     */
    public void pruneByFile(Set<String> filePaths,
                            Set<String> keepMethodChunkIds,
                            Set<String> keepFieldFqns,
                            Set<String> keepClassFqns) {
        if (filePaths == null || filePaths.isEmpty()) return;

        List<String> files = new ArrayList<>(filePaths);
        List<String> keepMethods = new ArrayList<>(keepMethodChunkIds);
        List<String> keepFields = new ArrayList<>(keepFieldFqns);
        List<String> keepClasses = new ArrayList<>(keepClassFqns);

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                // 1. Remove :Method nodes that disappeared from these files.
                tx.run(
                    "MATCH (m:Method) " +
                    "WHERE m.filePath IN $files AND NOT m.chunkId IN $keep " +
                    "DETACH DELETE m",
                    Map.of("files", files, "keep", keepMethods)
                );

                // 2. Remove :Field nodes whose owning class is in these files
                //    but whose FQN no longer appears in the new model.
                tx.run(
                    "MATCH (c)-[:HAS_FIELD]->(f:Field) " +
                    "WHERE (c:Class OR c:Interface) AND c.filePath IN $files " +
                    "  AND NOT f.fqName IN $keep " +
                    "DETACH DELETE f",
                    Map.of("files", files, "keep", keepFields)
                );

                // 3. Wipe outgoing edges from kept methods so MERGE in the
                //    subsequent store() doesn't leave stale CALLS/etc behind.
                if (!keepMethods.isEmpty()) {
                    tx.run(
                        "MATCH (m:Method)-[r:CALLS|CALLED_BY|USES_TYPE|RETURNS_TYPE|THROWS|TEST_FOR|BELONGS_TO]->() " +
                        "WHERE m.chunkId IN $keep " +
                        "DELETE r",
                        Map.of("keep", keepMethods)
                    );
                }

                // 4. Same idea for kept classes — wipe outgoing structural
                //    edges (IMPORTS, EXTENDS, IMPLEMENTS, INNER_CLASS_OF)
                //    so they're authoritatively repopulated.
                if (!keepClasses.isEmpty()) {
                    tx.run(
                        "MATCH (c)-[r:IMPORTS|EXTENDS|IMPLEMENTS|INNER_CLASS_OF]->() " +
                        "WHERE (c:Class OR c:Interface) AND c.fqName IN $keep " +
                        "DELETE r",
                        Map.of("keep", keepClasses)
                    );
                }

                return null;
            });
        }
        System.out.printf(
            "  ✓ pruneByFile: %d file(s), kept %d method(s), %d field(s), %d class(es)%n",
            filePaths.size(), keepMethodChunkIds.size(), keepFieldFqns.size(), keepClassFqns.size()
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // Cross-file reindex repair (snapshot + edge rewrite)
    // ═══════════════════════════════════════════════════════════════

    /** Identity tuple for a {@code :Method} node, captured pre-prune. */
    public record MethodIdent(String chunkId,
                              String fqClassName,
                              String methodName,
                              String methodSignature,
                              String filePath,
                              int partIndex) { }

    /** Identity tuple for a {@code :Class}/{@code :Interface} node, captured pre-prune. */
    public record ClassIdent(String fqName,
                             String simpleName,
                             String packageName,
                             String filePath,
                             boolean isInterface) { }

    /** Identity tuple for a {@code :Field} node, captured pre-prune. */
    public record FieldIdent(String fqName,
                             String name,
                             String type,
                             String owningClassFqn) { }

    /**
     * One inbound edge into a node in a changed file, captured pre-prune so
     * we can recreate it after MERGE upserts new (possibly renamed) targets.
     *
     * <p>{@code sourceLabel} is one of {@code Method}, {@code Class},
     * {@code Interface}. {@code targetLabel} is one of those plus {@code Field}.
     * Source keys are {@code chunkId} for Method and {@code fqName} for
     * Class/Interface; target keys analogous, plus {@code fqName} for Field.
     */
    public record InboundEdge(String relType,
                              String sourceLabel,
                              String sourceKeyValue,
                              String targetLabel,
                              String oldTargetKeyValue) { }

    /** Pre-prune snapshot: identity of nodes-in-changed-files plus their inbound edges from elsewhere. */
    public record BeforeSnapshot(Map<String, MethodIdent> methodsByChunkId,
                                 Map<String, ClassIdent>  classesByFqn,
                                 Map<String, FieldIdent>  fieldsByFqn,
                                 List<InboundEdge>        inboundEdges) {

        public static BeforeSnapshot empty() {
            return new BeforeSnapshot(Map.of(), Map.of(), Map.of(), List.of());
        }

        public Set<String> allMethodChunkIds() { return methodsByChunkId.keySet(); }
        public Set<String> allClassFqns()      { return classesByFqn.keySet(); }
        public Set<String> allFieldFqns()      { return fieldsByFqn.keySet(); }

        public boolean isEmpty() {
            return methodsByChunkId.isEmpty() && classesByFqn.isEmpty()
                && fieldsByFqn.isEmpty() && inboundEdges.isEmpty();
        }
    }

    /**
     * Capture the identity of every {@code :Method}/{@code :Class}/
     * {@code :Interface}/{@code :Field} node currently living in
     * {@code filePaths}, plus every inbound edge into those nodes whose
     * source lives outside {@code filePaths}.
     *
     * <p>Run BEFORE {@link #pruneByFile}: prune deletes the targets and
     * cascades {@code DETACH DELETE} over the inbound edges, so a snapshot
     * taken after prune would be empty.
     */
    public BeforeSnapshot captureBeforeSnapshot(Set<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) return BeforeSnapshot.empty();
        List<String> files = new ArrayList<>(filePaths);

        Map<String, MethodIdent> methods = new LinkedHashMap<>();
        Map<String, ClassIdent>  classes = new LinkedHashMap<>();
        Map<String, FieldIdent>  fields  = new LinkedHashMap<>();
        List<InboundEdge> inboundEdges = new ArrayList<>();

        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result mres = tx.run(
                    "MATCH (m:Method) WHERE m.filePath IN $files " +
                    "RETURN m.chunkId AS chunkId, m.fqClassName AS fqc, m.methodName AS name, " +
                    "       m.methodSignature AS sig, m.filePath AS filePath, m.partIndex AS partIndex",
                    Map.of("files", files));
                while (mres.hasNext()) {
                    var rec = mres.next();
                    String chunkId = rec.get("chunkId").asString(null);
                    if (chunkId == null) continue;
                    methods.put(chunkId, new MethodIdent(
                        chunkId,
                        rec.get("fqc").asString(""),
                        rec.get("name").asString(""),
                        rec.get("sig").asString(""),
                        rec.get("filePath").asString(""),
                        rec.get("partIndex").asInt(0)));
                }

                Result cres = tx.run(
                    "MATCH (c) WHERE (c:Class OR c:Interface) AND c.filePath IN $files " +
                    "RETURN c.fqName AS fqName, c.simpleName AS simpleName, c.packageName AS pkg, " +
                    "       c.filePath AS filePath, (CASE WHEN c:Interface THEN true ELSE false END) AS isIface",
                    Map.of("files", files));
                while (cres.hasNext()) {
                    var rec = cres.next();
                    String fq = rec.get("fqName").asString(null);
                    if (fq == null) continue;
                    classes.put(fq, new ClassIdent(
                        fq,
                        rec.get("simpleName").asString(""),
                        rec.get("pkg").asString(""),
                        rec.get("filePath").asString(""),
                        rec.get("isIface").asBoolean(false)));
                }

                // Fields don't carry filePath, so join through HAS_FIELD to the owning class.
                Result fres = tx.run(
                    "MATCH (c)-[:HAS_FIELD]->(f:Field) " +
                    "WHERE (c:Class OR c:Interface) AND c.filePath IN $files " +
                    "RETURN f.fqName AS fqName, f.name AS name, f.type AS type, " +
                    "       f.owningClassFqn AS owningClass",
                    Map.of("files", files));
                while (fres.hasNext()) {
                    var rec = fres.next();
                    String fq = rec.get("fqName").asString(null);
                    if (fq == null) continue;
                    fields.put(fq, new FieldIdent(
                        fq,
                        rec.get("name").asString(""),
                        rec.get("type").asString(""),
                        rec.get("owningClass").asString("")));
                }

                // ── Inbound edges: outside-source → in-changed-files target ──

                // Method-source → Method-target (CALLS, OVERRIDES, TEST_FOR)
                Result e1 = tx.run(
                    "MATCH (src:Method)-[r:CALLS|OVERRIDES|TEST_FOR]->(tgt:Method) " +
                    "WHERE tgt.filePath IN $files AND NOT src.filePath IN $files " +
                    "RETURN type(r) AS rt, src.chunkId AS srcKey, tgt.chunkId AS tgtKey",
                    Map.of("files", files));
                while (e1.hasNext()) {
                    var rec = e1.next();
                    inboundEdges.add(new InboundEdge(
                        rec.get("rt").asString(),
                        "Method", rec.get("srcKey").asString(""),
                        "Method", rec.get("tgtKey").asString("")));
                }

                // Method-source → Class-or-Interface-target (USES_TYPE, RETURNS_TYPE, THROWS, CATCHES)
                Result e2 = tx.run(
                    "MATCH (src:Method)-[r:USES_TYPE|RETURNS_TYPE|THROWS|CATCHES]->(tgt) " +
                    "WHERE (tgt:Class OR tgt:Interface) AND tgt.filePath IN $files " +
                    "  AND NOT src.filePath IN $files " +
                    "RETURN type(r) AS rt, src.chunkId AS srcKey, tgt.fqName AS tgtKey, " +
                    "       (CASE WHEN tgt:Interface THEN 'Interface' ELSE 'Class' END) AS tgtLabel",
                    Map.of("files", files));
                while (e2.hasNext()) {
                    var rec = e2.next();
                    inboundEdges.add(new InboundEdge(
                        rec.get("rt").asString(),
                        "Method", rec.get("srcKey").asString(""),
                        rec.get("tgtLabel").asString("Class"), rec.get("tgtKey").asString("")));
                }

                // Method-source → Field-target (READS_FIELD, WRITES_FIELD)
                Result e3 = tx.run(
                    "MATCH (src:Method)-[r:READS_FIELD|WRITES_FIELD]->(tgt:Field) " +
                    "MATCH (owner)-[:HAS_FIELD]->(tgt) WHERE (owner:Class OR owner:Interface) " +
                    "  AND owner.filePath IN $files AND NOT src.filePath IN $files " +
                    "RETURN type(r) AS rt, src.chunkId AS srcKey, tgt.fqName AS tgtKey",
                    Map.of("files", files));
                while (e3.hasNext()) {
                    var rec = e3.next();
                    inboundEdges.add(new InboundEdge(
                        rec.get("rt").asString(),
                        "Method", rec.get("srcKey").asString(""),
                        "Field", rec.get("tgtKey").asString("")));
                }

                // Class-source → Class/Interface-target (IMPORTS, EXTENDS, IMPLEMENTS, INNER_CLASS_OF)
                Result e4 = tx.run(
                    "MATCH (src)-[r:IMPORTS|EXTENDS|IMPLEMENTS|INNER_CLASS_OF]->(tgt) " +
                    "WHERE (src:Class OR src:Interface) AND (tgt:Class OR tgt:Interface) " +
                    "  AND tgt.filePath IN $files AND src.filePath IS NOT NULL " +
                    "  AND NOT src.filePath IN $files " +
                    "RETURN type(r) AS rt, src.fqName AS srcKey, tgt.fqName AS tgtKey, " +
                    "       (CASE WHEN src:Interface THEN 'Interface' ELSE 'Class' END) AS srcLabel, " +
                    "       (CASE WHEN tgt:Interface THEN 'Interface' ELSE 'Class' END) AS tgtLabel",
                    Map.of("files", files));
                while (e4.hasNext()) {
                    var rec = e4.next();
                    inboundEdges.add(new InboundEdge(
                        rec.get("rt").asString(),
                        rec.get("srcLabel").asString("Class"), rec.get("srcKey").asString(""),
                        rec.get("tgtLabel").asString("Class"), rec.get("tgtKey").asString("")));
                }
                return null;
            });
        }

        System.out.printf(
            "  ✓ captureBeforeSnapshot: %d method(s), %d class(es), %d field(s), %d inbound edge(s)%n",
            methods.size(), classes.size(), fields.size(), inboundEdges.size());
        return new BeforeSnapshot(methods, classes, fields, inboundEdges);
    }

    /**
     * Find unchanged files (i.e. files NOT in {@code excludeFilePaths}) that
     * contain at least one node with an outbound edge into a symbol from
     * the snapshot. Returns repo-relative paths, capped at {@code maxFiles}.
     */
    public Set<String> findFilesReferencingSymbols(Set<String> chunkIds,
                                                   Set<String> classFqns,
                                                   Set<String> fieldFqns,
                                                   Set<String> excludeFilePaths,
                                                   int maxFiles) {
        if ((chunkIds == null || chunkIds.isEmpty())
                && (classFqns == null || classFqns.isEmpty())
                && (fieldFqns == null || fieldFqns.isEmpty())) {
            return Set.of();
        }
        List<String> exclude = new ArrayList<>(excludeFilePaths == null ? Set.of() : excludeFilePaths);
        List<String> mIds  = new ArrayList<>(chunkIds  == null ? Set.of() : chunkIds);
        List<String> cFqns = new ArrayList<>(classFqns == null ? Set.of() : classFqns);
        List<String> fFqns = new ArrayList<>(fieldFqns == null ? Set.of() : fieldFqns);

        Set<String> result = new LinkedHashSet<>();
        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                if (!mIds.isEmpty()) {
                    Result r = tx.run(
                        "MATCH (src:Method)-[r:CALLS|OVERRIDES|TEST_FOR]->(tgt:Method) " +
                        "WHERE tgt.chunkId IN $ids AND NOT src.filePath IN $exclude " +
                        "RETURN DISTINCT src.filePath AS fp",
                        Map.of("ids", mIds, "exclude", exclude));
                    while (r.hasNext()) result.add(r.next().get("fp").asString());
                }
                if (!cFqns.isEmpty()) {
                    Result r = tx.run(
                        "MATCH (src:Method)-[r:USES_TYPE|RETURNS_TYPE|THROWS|CATCHES]->(tgt) " +
                        "WHERE (tgt:Class OR tgt:Interface) AND tgt.fqName IN $fqns " +
                        "  AND NOT src.filePath IN $exclude " +
                        "RETURN DISTINCT src.filePath AS fp",
                        Map.of("fqns", cFqns, "exclude", exclude));
                    while (r.hasNext()) result.add(r.next().get("fp").asString());

                    Result r2 = tx.run(
                        "MATCH (src)-[r:IMPORTS|EXTENDS|IMPLEMENTS|INNER_CLASS_OF]->(tgt) " +
                        "WHERE (src:Class OR src:Interface) AND (tgt:Class OR tgt:Interface) " +
                        "  AND tgt.fqName IN $fqns AND src.filePath IS NOT NULL " +
                        "  AND NOT src.filePath IN $exclude " +
                        "RETURN DISTINCT src.filePath AS fp",
                        Map.of("fqns", cFqns, "exclude", exclude));
                    while (r2.hasNext()) result.add(r2.next().get("fp").asString());
                }
                if (!fFqns.isEmpty()) {
                    Result r = tx.run(
                        "MATCH (src:Method)-[r:READS_FIELD|WRITES_FIELD]->(tgt:Field) " +
                        "WHERE tgt.fqName IN $fqns AND NOT src.filePath IN $exclude " +
                        "RETURN DISTINCT src.filePath AS fp",
                        Map.of("fqns", fFqns, "exclude", exclude));
                    while (r.hasNext()) result.add(r.next().get("fp").asString());
                }
                return null;
            });
        }
        if (maxFiles > 0 && result.size() > maxFiles) {
            return new LinkedHashSet<>(new ArrayList<>(result).subList(0, maxFiles));
        }
        return result;
    }

    /**
     * Recreate inbound edges from {@code snapshot} whose target was renamed,
     * pointing them at the new target. Edges whose target was deleted with
     * no rename are skipped (Neo4j's {@code DETACH DELETE} already removed
     * them when the old node disappeared during {@link #pruneByFile}).
     *
     * @return number of edges recreated
     */
    public int recreateInboundEdges(BeforeSnapshot snapshot,
                                    Map<String, String> methodRenames,
                                    Map<String, String> classRenames,
                                    Map<String, String> fieldRenames) {
        if (snapshot == null || snapshot.inboundEdges().isEmpty()) return 0;
        if (methodRenames == null) methodRenames = Map.of();
        if (classRenames  == null) classRenames  = Map.of();
        if (fieldRenames  == null) fieldRenames  = Map.of();

        // Group edges by their canonical Cypher shape: (srcLabel, tgtLabel, relType)
        // Each shape maps to one batched UNWIND query.
        Map<String, List<Map<String, Object>>> bucketsByShape = new LinkedHashMap<>();

        for (InboundEdge e : snapshot.inboundEdges()) {
            String newTgt = switch (e.targetLabel()) {
                case "Method" -> methodRenames.getOrDefault(e.oldTargetKeyValue(), null);
                case "Field"  -> fieldRenames.getOrDefault(e.oldTargetKeyValue(), null);
                case "Class", "Interface" -> classRenames.getOrDefault(e.oldTargetKeyValue(), null);
                default -> null;
            };
            if (newTgt == null) continue; // deleted with no rename → leave gone

            String shapeKey = e.sourceLabel() + "|" + e.targetLabel() + "|" + e.relType();
            bucketsByShape.computeIfAbsent(shapeKey, k -> new ArrayList<>())
                .add(Map.of("src", e.sourceKeyValue(), "tgt", newTgt));
        }
        if (bucketsByShape.isEmpty()) return 0;

        int total = 0;
        try (Session session = driver.session()) {
            for (Map.Entry<String, List<Map<String, Object>>> entry : bucketsByShape.entrySet()) {
                String[] parts = entry.getKey().split("\\|", -1);
                String srcLabel = parts[0];
                String tgtLabel = parts[1];
                String relType = parts[2];
                String srcKey = "Method".equals(srcLabel) ? "chunkId" : "fqName";
                String tgtKey = switch (tgtLabel) {
                    case "Method" -> "chunkId";
                    default -> "fqName"; // Class, Interface, Field
                };

                String cypher =
                    "UNWIND $batch AS row " +
                    "MATCH (a:" + srcLabel + " {" + srcKey + ": row.src}) " +
                    "MATCH (b:" + tgtLabel + " {" + tgtKey + ": row.tgt}) " +
                    "MERGE (a)-[:" + relType + "]->(b)";

                final List<Map<String, Object>> batch = entry.getValue();
                ResultSummary summary = session.executeWrite(tx -> tx.run(cypher,
                    Map.of("batch", batch)).consume());
                total += summary.counters().relationshipsCreated();
            }
        }
        if (total > 0) {
            System.out.printf("  ✓ recreateInboundEdges: %d edge(s) re-pointed at renamed targets%n", total);
        }
        return total;
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
                // New edge types
                case USES_TYPE -> buildTypeEdgeCypher("Method", "chunkId", "Class", "fqName", "USES_TYPE");
                case RETURNS_TYPE -> buildTypeEdgeCypher("Method", "chunkId", "Class", "fqName", "RETURNS_TYPE");
                case READS_FIELD -> buildFieldEdgeCypher("READS_FIELD");
                case WRITES_FIELD -> buildFieldEdgeCypher("WRITES_FIELD");
                case THROWS -> buildThrowsEdgeCypher("THROWS");
                case CATCHES -> buildThrowsEdgeCypher("CATCHES");
                case OVERRIDES -> buildEdgeCypher("Method", "chunkId", "Method", "chunkId", "OVERRIDES");
                case TEST_FOR -> buildEdgeCypher("Method", "chunkId", "Method", "chunkId", "TEST_FOR");
                case IMPORTS -> buildImportsEdgeCypher();
                case INNER_CLASS_OF -> buildInnerClassEdgeCypher();
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

    /**
     * Generic Type edge: Method -> Class (or similar). Creates a stub Class node if missing.
     */
    private String buildTypeEdgeCypher(String srcLabel, String srcKey, String tgtLabel, String tgtKey, String relType) {
        return "UNWIND $batch AS row " +
            "MATCH (a:" + srcLabel + " {" + srcKey + ": row.src}) " +
            "MERGE (b:" + tgtLabel + " {" + tgtKey + ": row.tgt}) " +
            "MERGE (a)-[:" + relType + "]->(b)";
    }

    /**
     * Field read/write edges: Method -> Field
     */
    private String buildFieldEdgeCypher(String relType) {
        return "UNWIND $batch AS row " +
            "MATCH (m:Method {chunkId: row.src}) " +
            "MATCH (f:Field {fqName: row.tgt}) " +
            "MERGE (m)-[:" + relType + "]->(f)";
    }

    /**
     * THROWS / CATCHES: Method -> ExceptionType (stored as :Class stub)
     */
    private String buildThrowsEdgeCypher(String relType) {
        return "UNWIND $batch AS row " +
            "MATCH (m:Method {chunkId: row.src}) " +
            "MERGE (e:Class {fqName: row.tgt}) " +
            "MERGE (m)-[:" + relType + "]->(e)";
    }

    /**
     * IMPORTS: Class -> Class
     */
    private String buildImportsEdgeCypher() {
        return "UNWIND $batch AS row " +
            "MATCH (c:Class {fqName: row.src}) " +
            "MERGE (imp:Class {fqName: row.tgt}) " +
            "MERGE (c)-[:IMPORTS]->(imp)";
    }

    /**
     * INNER_CLASS_OF: innerClass -> outerClass
     */
    private String buildInnerClassEdgeCypher() {
        return "UNWIND $batch AS row " +
            "MATCH (inner:Class {fqName: row.src}) " +
            "OPTIONAL MATCH (outer:Class {fqName: row.tgt}) " +
            "WHERE outer IS NOT NULL " +
            "MERGE (inner)-[:INNER_CLASS_OF]->(outer)";
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

