# Neo4j Vector Index Setup — `method_embeddings`

## Overview

The hybrid Graph-RAG retrieval pipeline uses a **Neo4j vector index** on
`:Method` nodes to perform embedding-based similarity search. This index is
named `method_embeddings` by default and operates on the `embedding` property.

> **Requires Neo4j 5.11+** with vector index support enabled.

---

## Automatic Creation

Starting from the latest version, the vector index is **created automatically**
by the application in these scenarios:

1. **During chunking** (`ChunkerMain`) — when `EMBEDDING_URL` is set, the
   chunker creates the vector index and stores embeddings.
2. **At retrieval startup** (`RetrievalMain`, `RefactorMain`, `SafeLoopMain`,
   `DistributedSafeLoopMain`) — `ensureVectorIndex()` is called on the
   `Neo4jGraphReader` before any vector search, creating the index if it
   doesn't already exist.

No manual action is needed if the application runs normally.

---

## Manual Creation (Cypher)

If you need to create the index manually (e.g. the application crashed before
index creation, or you want to set it up ahead of time), run the following
Cypher in **Neo4j Browser**, **cypher-shell**, or any Neo4j client:

### 1. Create the Vector Index

```cypher
CREATE VECTOR INDEX method_embeddings IF NOT EXISTS
FOR (m:Method) ON (m.embedding)
OPTIONS {
  indexConfig: {
    `vector.dimensions`: 768,
    `vector.similarity_function`: 'cosine'
  }
}
```

> **Note:** Adjust `vector.dimensions` to match your embedding model output:
> - `768` for `nomic-embed-text-v1.5` (default)
> - `1536` for OpenAI `text-embedding-ada-002`
> - `384` for `all-MiniLM-L6-v2`

### 2. Verify the Index Exists

```cypher
SHOW INDEXES
YIELD name, type, state, labelsOrTypes, properties
WHERE name = 'method_embeddings'
```

Expected output:
| name               | type     | state  | labelsOrTypes | properties    |
|--------------------|----------|--------|---------------|---------------|
| method_embeddings  | VECTOR   | ONLINE | [Method]      | [embedding]   |

The `state` must be **ONLINE** before vector queries will work.

### 3. Verify Embeddings Are Stored

```cypher
MATCH (m:Method)
WHERE m.embedding IS NOT NULL
RETURN count(m) AS methodsWithEmbeddings
```

If this returns `0`, you need to run the embedding step. Either:
- Re-run `ChunkerMain` with `EMBEDDING_URL` set, or
- Compute and store embeddings programmatically via `Neo4jGraphStore.storeEmbeddings()`

### 4. Test a Vector Search

```cypher
CALL db.index.vector.queryNodes('method_embeddings', 5, $embedding)
YIELD node, score
RETURN node.chunkId AS id, score
ORDER BY score DESC
```

(Replace `$embedding` with an actual embedding vector.)

---

## Dropping and Recreating the Index

If you need to recreate the index (e.g. after changing embedding dimensions):

```cypher
DROP INDEX method_embeddings IF EXISTS
```

Then re-run the `CREATE VECTOR INDEX` command above with the new dimensions.

---

## Configuration

The index name and dimensions are configurable via environment variables or
system properties:

| Setting              | Env Variable          | System Property       | Default                |
|----------------------|-----------------------|-----------------------|------------------------|
| Index name           | `VECTOR_INDEX_NAME`   | `vector.indexName`    | `method_embeddings`    |
| Embedding dimensions | `EMBEDDING_DIMENSIONS`| `embedding.dimensions`| `768`                  |
| Vector search K      | `VECTOR_SEARCH_K`     | `vector.searchK`     | `20`                   |
| Embedding URL        | `EMBEDDING_URL`       | `embedding.url`      | `http://localhost:1234/v1/embeddings` |
| Embedding model      | `EMBEDDING_MODEL`     | `embedding.model`    | `text-embedding-nomic-embed-text-v1.5` |

---

## Troubleshooting

### Error: `There is no such vector schema index: method_embeddings`

**Cause:** The vector index was never created. This happens when:
- `ChunkerMain` was run without `EMBEDDING_URL` set
- An older version of the retrieval code was used (before `ensureVectorIndex()` was added)

**Fix:** Either update to the latest code (which auto-creates the index) or run
the manual `CREATE VECTOR INDEX` Cypher above.

### Error: Index exists but state is POPULATING

After creation, Neo4j needs time to populate the index. Wait a few seconds and
check `SHOW INDEXES` — the state should transition to **ONLINE**.

### Error: Dimension mismatch

If your embedding model produces vectors of a different dimension than the index
was created with, you must drop and recreate the index with the correct
`vector.dimensions` value.

