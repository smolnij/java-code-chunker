package com.smolnij.chunker.apply;

import com.smolnij.chunker.JavaCodeChunker;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.ClassNode;
import com.smolnij.chunker.model.graph.FieldNode;
import com.smolnij.chunker.model.graph.GraphModel;
import com.smolnij.chunker.retrieval.EmbeddingService;
import com.smolnij.chunker.store.Neo4jGraphStore;

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

    private final Path repoRoot;
    private final List<Path> sourceRoots;
    private final int maxTokensPerChunk;
    private final Neo4jGraphStore store;
    private final EmbeddingService embeddings;

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
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sourceRoots = List.copyOf(sourceRoots);
        this.maxTokensPerChunk = maxTokensPerChunk;
        this.store = store;
        this.embeddings = embeddings;
    }

    /**
     * Re-index the given files. Returns a small report; never throws — any
     * exception is captured in the result so callers can log it without
     * masking a successful patch apply.
     *
     * @param changedAbsolutePaths absolute file paths emitted by
     *        {@link ApplyResult#getChangedFiles()}
     */
    public ReindexResult reindex(Collection<Path> changedAbsolutePaths) {
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

        // 2. Re-chunk just those files.
        GraphModel deltaModel;
        try {
            JavaCodeChunker chunker = new JavaCodeChunker(repoRoot, sourceRoots, maxTokensPerChunk);
            deltaModel = chunker.processFiles(javaAbs);
        } catch (IOException e) {
            return ReindexResult.failure("processFiles failed: " + e.getMessage());
        }

        // 3. Build keep-sets and prune anything that disappeared.
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

        // 4. Upsert the fresh model — this is idempotent (MERGE) so it both
        //    refreshes existing nodes and creates new ones (e.g. CreateFile).
        int upserted;
        try {
            store.store(deltaModel);
            upserted = deltaModel.getMethodNodes().size();
        } catch (Exception e) {
            return ReindexResult.failure("store failed: " + e.getMessage());
        }

        // 5. Optional vector refresh.
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

        return new ReindexResult(true, relPaths.size(), upserted, embedded, "");
    }

    // ═══════════════════════════════════════════════════════════════
    // Result type
    // ═══════════════════════════════════════════════════════════════

    /**
     * @param success      false only when an exception aborted the re-index
     * @param filesTouched number of .java files re-parsed
     * @param methodsUpserted number of :Method nodes re-stored (includes new + updated)
     * @param methodsEmbedded number of :Method nodes whose embeddings were refreshed
     * @param error        empty when success, otherwise a one-line reason
     */
    public record ReindexResult(boolean success,
                                int filesTouched,
                                int methodsUpserted,
                                int methodsEmbedded,
                                String error) {

        public static ReindexResult empty() {
            return new ReindexResult(true, 0, 0, 0, "");
        }

        public static ReindexResult failure(String reason) {
            return new ReindexResult(false, 0, 0, 0, reason);
        }

        public String toReport() {
            if (!success) return "Reindex: ✗ " + error;
            if (filesTouched == 0) return "Reindex: skipped (no .java files in changed set)";
            return "Reindex: ✓ files=" + filesTouched
                + ", methodsUpserted=" + methodsUpserted
                + ", methodsEmbedded=" + methodsEmbedded;
        }
    }
}
