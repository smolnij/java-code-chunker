package com.smolnij.chunker.apply;

import com.smolnij.chunker.JavaCodeChunker;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.ClassNode;
import com.smolnij.chunker.model.graph.FieldNode;
import com.smolnij.chunker.model.graph.GraphModel;
import com.smolnij.chunker.retrieval.EmbeddingService;
import com.smolnij.chunker.store.Neo4jGraphStore;
import com.smolnij.chunker.store.Neo4jGraphStore.BeforeSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Post-apply Neo4j delta re-indexer.
 *
 * <p>{@link com.smolnij.chunker.ChunkerMain} ingests the repo into Neo4j once
 * offline. After that, any LLM-driven {@link PatchApplier} run mutates source
 * files on disk while the graph stays frozen — so subsequent retrievals miss
 * newly added classes and see stale code for modified methods. This class
 * closes that gap by re-parsing only the files just touched and updating
 * Neo4j (nodes, edges, optional embeddings) for that subset.
 *
 * <p>Pipeline per call:
 * <ol>
 *   <li>Filter the input paths to {@code .java} files that live under the
 *       configured repo root; convert to absolute paths.</li>
 *   <li>Build a fresh {@link JavaCodeChunker} and call
 *       {@link JavaCodeChunker#processFiles(Collection)} to get a
 *       {@link GraphModel} containing only those files.</li>
 *   <li>Compute the keep-sets (method chunkIds, field FQNs, class FQNs) and
 *       call {@link Neo4jGraphStore#pruneByFile} to wipe anything that
 *       disappeared (renames + deletions) and clear outgoing edges from
 *       kept nodes so the MERGE pass can re-author them.</li>
 *   <li>Call {@link Neo4jGraphStore#store(GraphModel)} to upsert.</li>
 *   <li>If an {@link EmbeddingService} was provided, recompute and store
 *       embeddings for the methods in the delta model.</li>
 * </ol>
 *
 * <p>Wire one of these into {@link ApplyTools} (and the prose-extracted apply
 * fallbacks) so {@code commitPlan} keeps the graph aligned with disk.
 * Failures are logged but do not propagate — a failed re-index does not
 * undo a successful file write.
 */
public final class GraphReindexer {

    /** Default for {@code cascadeMaxFiles} — see {@link #reindex(Collection, List)}. */
    public static final int DEFAULT_CASCADE_MAX_FILES = 25;

    private final Path repoRoot;
    private final List<Path> sourceRoots;
    private final int maxTokensPerChunk;
    private final Neo4jGraphStore store;
    private final EmbeddingService embeddings;
    private final boolean cascadeEnabled;
    private final int cascadeMaxFiles;

    /**
     * @param repoRoot           absolute repo root (same as {@code ChunkerMain})
     * @param sourceRoots        relative source roots, e.g. {@code [src/main/java, src/test/java]}
     * @param maxTokensPerChunk  same value used at initial indexing
     * @param store              live Neo4jGraphStore (write-side)
     * @param embeddings         optional; null disables vector refresh
     */
    public GraphReindexer(Path repoRoot,
                          List<Path> sourceRoots,
                          int maxTokensPerChunk,
                          Neo4jGraphStore store,
                          EmbeddingService embeddings) {
        this(repoRoot, sourceRoots, maxTokensPerChunk, store, embeddings,
            true, DEFAULT_CASCADE_MAX_FILES);
    }

    /**
     * @param cascadeEnabled  when true, after the snapshot/mapping/edge-rewrite
     *                        phase we re-parse up to {@code cascadeMaxFiles}
     *                        unchanged files that still hold edges into the
     *                        changed-file symbols, so their {@code :Method.code}
     *                        and outbound edges reflect the latest neighbourhood.
     * @param cascadeMaxFiles cap on the cascade pass; files beyond the cap are
     *                        skipped and a WARN line is logged.
     */
    public GraphReindexer(Path repoRoot,
                          List<Path> sourceRoots,
                          int maxTokensPerChunk,
                          Neo4jGraphStore store,
                          EmbeddingService embeddings,
                          boolean cascadeEnabled,
                          int cascadeMaxFiles) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sourceRoots = List.copyOf(sourceRoots);
        this.maxTokensPerChunk = maxTokensPerChunk;
        this.store = store;
        this.embeddings = embeddings;
        this.cascadeEnabled = cascadeEnabled;
        this.cascadeMaxFiles = cascadeMaxFiles > 0 ? cascadeMaxFiles : DEFAULT_CASCADE_MAX_FILES;
    }

    /**
     * Re-index the given files. Returns a small report; never throws — any
     * exception is captured in the result so callers can log it without
     * masking a successful patch apply.
     *
     * <p>This overload knows nothing about the agent's intent; it falls
     * back to the heuristic in {@link MappingResolver} for cross-file
     * repair. To pass authoritative rename signals, use
     * {@link #reindex(Collection, List)}.
     *
     * @param changedAbsolutePaths absolute file paths emitted by
     *        {@link ApplyResult#getChangedFiles()}
     */
    public ReindexResult reindex(Collection<Path> changedAbsolutePaths) {
        return reindex(changedAbsolutePaths, List.of());
    }

    /**
     * Re-index the given files and seed the cross-file repair pass with the
     * supplied rename ops. The rename ops are an authoritative signal that
     * short-circuits {@link MappingResolver}'s heuristic for the symbols
     * they cover.
     *
     * @param changedAbsolutePaths absolute file paths emitted by
     *        {@link ApplyResult#getChangedFiles()}
     * @param committedOps         ops the {@link PatchApplier} actually wrote to
     *                             disk (typically {@link ApplyResult#getCommittedOps()});
     *                             only the {@link EditOp.RenameMethod} /
     *                             {@link EditOp.RenameClass} /
     *                             {@link EditOp.RenameField} subset is consulted.
     */
    public ReindexResult reindex(Collection<Path> changedAbsolutePaths,
                                 List<EditOp> committedOps) {
        if (changedAbsolutePaths == null || changedAbsolutePaths.isEmpty()) {
            return ReindexResult.empty();
        }

        // 1. Filter to .java files inside the repo and translate to repo-relative
        //    paths (the same shape Neo4j stores in :Method.filePath).
        Set<Path> javaAbs = new LinkedHashSet<>();
        Set<String> relPaths = new LinkedHashSet<>();
        for (Path raw : changedAbsolutePaths) {
            if (raw == null) continue;
            Path abs = raw.toAbsolutePath().normalize();
            if (!abs.toString().endsWith(".java")) continue;
            if (!abs.startsWith(repoRoot)) continue;
            javaAbs.add(abs);
            relPaths.add(repoRoot.relativize(abs).toString().replace('\\', '/'));
        }
        if (javaAbs.isEmpty()) {
            return ReindexResult.empty();
        }

        // 2. Snapshot the pre-prune state of the changed files plus the
        //    inbound edges from outside callers — needed later for surgical
        //    edge rewrite once we know the old → new mapping.
        BeforeSnapshot snapshot;
        try {
            snapshot = store.captureBeforeSnapshot(relPaths);
        } catch (Exception e) {
            return ReindexResult.failure("captureBeforeSnapshot failed: " + e.getMessage());
        }

        // 3. Identify unchanged files referencing changed-file symbols. We
        //    capture this BEFORE the prune deletes those edges so we can
        //    cascade-reparse later. Cap to keep blast-radius bounded.
        Set<String> referencingFiles = Set.of();
        if (cascadeEnabled) {
            try {
                referencingFiles = store.findFilesReferencingSymbols(
                    snapshot.allMethodChunkIds(),
                    snapshot.allClassFqns(),
                    snapshot.allFieldFqns(),
                    relPaths,
                    cascadeMaxFiles + 1); // +1 so we can detect overflow
            } catch (Exception e) {
                System.err.println("  ⚠ findFilesReferencingSymbols failed: " + e.getMessage());
                referencingFiles = Set.of();
            }
        }
        boolean cascadeOverflow = referencingFiles.size() > cascadeMaxFiles;
        Set<String> cascadeTargets = cascadeOverflow
            ? new LinkedHashSet<>(new java.util.ArrayList<>(referencingFiles).subList(0, cascadeMaxFiles))
            : referencingFiles;
        int referencingFileCountAtSnapshot = referencingFiles.size();

        // 4. Re-chunk just the changed files.
        GraphModel deltaModel;
        try {
            JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokensPerChunk);
            deltaModel = chunker.processFiles(javaAbs);
        } catch (IOException e) {
            return ReindexResult.failure("processFiles failed: " + e.getMessage());
        }

        // 5. Build keep-sets and prune anything that disappeared.
        Set<String> keepMethodIds = new LinkedHashSet<>();
        for (CodeChunk c : deltaModel.getMethodNodes()) keepMethodIds.add(c.getChunkId());

        Set<String> keepFieldFqns = new LinkedHashSet<>();
        for (FieldNode f : deltaModel.getFieldNodes().values()) keepFieldFqns.add(f.getFqName());

        Set<String> keepClassFqns = new LinkedHashSet<>();
        for (ClassNode cn : deltaModel.getClassNodes().values()) keepClassFqns.add(cn.getFqName());

        try {
            store.pruneByFile(relPaths, keepMethodIds, keepFieldFqns, keepClassFqns);
        } catch (Exception e) {
            return ReindexResult.failure("pruneByFile failed: " + e.getMessage());
        }

        // 6. Upsert the fresh model — this is idempotent (MERGE) so it both
        //    refreshes existing nodes and creates new ones (e.g. CreateFile).
        int upserted;
        try {
            store.store(deltaModel);
            upserted = deltaModel.getMethodNodes().size();
        } catch (Exception e) {
            return ReindexResult.failure("store failed: " + e.getMessage());
        }

        // 7. Compute old → new mapping and re-create any inbound edges that
        //    point at renamed targets so unchanged callers keep their call
        //    graph topology. Deletions are left to Neo4j's DETACH DELETE.
        MappingResolver.Mapping mapping = MappingResolver.Mapping.empty();
        int edgesRewritten = 0;
        try {
            mapping = MappingResolver.resolve(snapshot, deltaModel, committedOps);
            edgesRewritten = store.recreateInboundEdges(snapshot,
                mapping.methodRenames(), mapping.classRenames(), mapping.fieldRenames());
        } catch (Exception e) {
            System.err.println("  ⚠ cross-file repair failed: " + e.getMessage());
        }

        // 8. Cascade pass: re-parse unchanged files that referenced any
        //    changed-file symbol so their :Method.code and outbound edges
        //    reflect the newest neighbourhood. Bounded by cascadeMaxFiles.
        int cascadedFiles = 0;
        int cascadeEmbedded = 0;
        if (!cascadeTargets.isEmpty()) {
            ReindexResult cascade = reindexCascade(cascadeTargets);
            cascadedFiles = cascade.filesTouched();
            cascadeEmbedded = cascade.methodsEmbedded();
        }
        if (cascadeOverflow) {
            System.err.printf(
                "  ⚠ stale :Method.code in %d caller file(s) (over cascade cap of %d) — " +
                "edges still re-pointed at renames; full ChunkerMain run will refresh source text%n",
                referencingFileCountAtSnapshot - cascadeMaxFiles, cascadeMaxFiles);
        }

        // 9. Optional vector refresh for the changed files themselves.
        int embedded = 0;
        if (embeddings != null && upserted > 0) {
            try {
                store.storeEmbeddings(deltaModel, embeddings);
                embedded = upserted;
            } catch (Exception e) {
                // Don't fail the whole reindex on embedding hiccups — exact
                // lookup still works, the new methods just won't be vector-searchable.
                System.err.println("  ⚠ embedding refresh failed: " + e.getMessage());
            }
        }

        return new ReindexResult(true, relPaths.size(), upserted,
            embedded + cascadeEmbedded, edgesRewritten, cascadedFiles, "");
    }

    /**
     * Re-parse a set of repo-relative file paths and prune+upsert them, with
     * NO cross-file repair recursion. Used as the cascade pass after the
     * primary reindex has already established the new mapping.
     */
    private ReindexResult reindexCascade(Set<String> relPaths) {
        if (relPaths == null || relPaths.isEmpty()) return ReindexResult.empty();
        Set<Path> abs = new LinkedHashSet<>();
        Set<String> rels = new LinkedHashSet<>();
        for (String r : relPaths) {
            if (r == null || r.isBlank()) continue;
            if (!r.endsWith(".java")) continue;
            Path p = repoRoot.resolve(r).toAbsolutePath().normalize();
            if (!p.startsWith(repoRoot)) continue;
            abs.add(p);
            rels.add(r);
        }
        if (abs.isEmpty()) return ReindexResult.empty();

        GraphModel cascadeModel;
        try {
            JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokensPerChunk);
            cascadeModel = chunker.processFiles(abs);
        } catch (IOException e) {
            System.err.println("  ⚠ cascade processFiles failed: " + e.getMessage());
            return ReindexResult.empty();
        }

        Set<String> keepMethodIds = new LinkedHashSet<>();
        for (CodeChunk c : cascadeModel.getMethodNodes()) keepMethodIds.add(c.getChunkId());
        Set<String> keepFieldFqns = new LinkedHashSet<>();
        for (FieldNode f : cascadeModel.getFieldNodes().values()) keepFieldFqns.add(f.getFqName());
        Set<String> keepClassFqns = new LinkedHashSet<>();
        for (ClassNode cn : cascadeModel.getClassNodes().values()) keepClassFqns.add(cn.getFqName());

        try {
            store.pruneByFile(rels, keepMethodIds, keepFieldFqns, keepClassFqns);
            store.store(cascadeModel);
        } catch (Exception e) {
            System.err.println("  ⚠ cascade prune/store failed: " + e.getMessage());
            return ReindexResult.empty();
        }

        int upserted = cascadeModel.getMethodNodes().size();
        int embedded = 0;
        if (embeddings != null && upserted > 0) {
            try {
                store.storeEmbeddings(cascadeModel, embeddings);
                embedded = upserted;
            } catch (Exception e) {
                System.err.println("  ⚠ cascade embedding refresh failed: " + e.getMessage());
            }
        }
        System.out.printf("  ✓ cascade reindex: %d file(s), %d method(s) re-stored%n", rels.size(), upserted);
        return new ReindexResult(true, rels.size(), upserted, embedded, 0, 0, "");
    }

    // ═══════════════════════════════════════════════════════════════
    // Result type
    // ═══════════════════════════════════════════════════════════════

    /**
     * @param success         false only when an exception aborted the re-index
     * @param filesTouched    number of .java files re-parsed (changed files only;
     *                        cascade-only files are reported via {@code cascadedFiles})
     * @param methodsUpserted number of :Method nodes re-stored (includes new + updated)
     * @param methodsEmbedded number of :Method nodes whose embeddings were refreshed
     *                        (sum of changed-file and cascade-file methods)
     * @param edgesRewritten  number of inbound edges re-pointed at renamed targets
     * @param cascadedFiles   number of unchanged caller files re-parsed in the
     *                        cascade pass to refresh their source text and edges
     * @param error           empty when success, otherwise a one-line reason
     */
    public record ReindexResult(boolean success,
                                int filesTouched,
                                int methodsUpserted,
                                int methodsEmbedded,
                                int edgesRewritten,
                                int cascadedFiles,
                                String error) {

        public static ReindexResult empty() {
            return new ReindexResult(true, 0, 0, 0, 0, 0, "");
        }

        public static ReindexResult failure(String reason) {
            return new ReindexResult(false, 0, 0, 0, 0, 0, reason);
        }

        public String toReport() {
            if (!success) return "Reindex: ✗ " + error;
            if (filesTouched == 0) return "Reindex: skipped (no .java files in changed set)";
            return "Reindex: ✓ files=" + filesTouched
                + ", methodsUpserted=" + methodsUpserted
                + ", methodsEmbedded=" + methodsEmbedded
                + ", edgesRewritten=" + edgesRewritten
                + ", cascadedFiles=" + cascadedFiles;
        }
    }
}
