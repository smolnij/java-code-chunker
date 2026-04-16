# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build fat JAR
mvn clean package -q

# Run chunker (repoRoot, outputDir, maxTokens)
java -jar target/java-code-chunker-1.0-SNAPSHOT.jar "/path/to/repo" "output-dir" 512

# Run with Neo4j persistence
java -jar target/java-code-chunker-1.0-SNAPSHOT.jar "/path/to/repo" "output-dir" 512 \
  -Dneo4j.uri=bolt://localhost:7687 -Dneo4j.password=secret
```

There are no automated tests — the project uses main classes for manual verification.

## Architecture

**java-code-chunker** parses Java repositories into method-level chunks with call graph edges, suitable for graph-aware RAG (retrieval-augmented generation) and LLM-driven code refactoring.

### Chunking Pipeline (`ChunkerMain` → `JavaCodeChunker`)

```
.java files → JavaParser AST → CodeChunks + CallGraph edges
    → back-patch calledBy reverse edges
    → filter boilerplate
    → assemble GraphModel
    → serialize: chunks.json, graph.json, chunks_readable.txt
    → (optional) Neo4j storage + vector index
```

`JavaCodeChunker` orchestrates phases: file collection, AST parsing (with Symbol Solver for FQ names), chunk extraction, call-graph back-patching, boilerplate filtering, and serialization.

### Core Data Model

- **`CodeChunk`** — identity (chunkId = FQN + part index), class context (fields, annotations, signatures), method source, call edges (`calls`/`calledBy` as FQ method names), token count
- **`GraphModel`** — full graph for Neo4j: CodeChunk nodes, ClassNode, FieldNode, GraphEdge (CALLS, CALLED_BY, BELONGS_TO, CONTAINS, EXTENDS, IMPLEMENTS, HAS_FIELD)
- **`GraphIndex`** — in-memory hierarchical index (package→class→method) supporting N-hop context expansion and keyword search

### Key Components

| Package | Responsibility |
|---------|---------------|
| `callgraph/CallGraphExtractor` | Extracts forward/reverse call edges via JavaParser Symbol Solver |
| `filter/BoilerplateDetector` | Detects getters/setters/DTOs by AST pattern + Lombok annotations |
| `tokenizer/TokenCounter` | cl100k_base token counting + line-aware splitting for chunk size limits |
| `index/GraphIndex` | In-memory graph index with BFS traversal for context expansion |
| `store/Neo4jGraphStore` | Graph DB persistence + vector index initialization |
| `retrieval/HybridRetriever` | RAG pipeline: exact match → graph BFS → vector fallback → re-rank |

### Refactoring Modes (6 entry points)

All modes use `HybridRetriever` to fetch context from Neo4j, then drive an LM-Studio LLM:

1. **`RetrievalMain`** — retrieval only
2. **`RefactorMain`** — single-turn refactoring with human review
3. **`AgentRefactorMain`** — LangChain4j agentic tool-calling loop
4. **`RalphMain`** — worker/judge loop (separate LLM personas)
5. **`SafeLoopMain`** — safety-gated loop with judge verdicts
6. **`DistributedSafeLoopMain`** — multi-machine planner + analyzer agents

### Environment Variables

| Variable | Default | Purpose |
|----------|---------|---------|
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j connection |
| `NEO4J_USER` | `neo4j` | Neo4j username |
| `NEO4J_PASSWORD` | — | Neo4j password |
| `NEO4J_CLEAN` | `false` | Wipe DB before import |
| `EMBEDDING_URL` | — | Embedding service endpoint |
| `LLM_CHAT_URL` | `http://localhost:1234/v1/chat/completions` | LM-Studio chat endpoint |
| `LLM_CHAT_MODEL` | — | Model name |
| `LLM_TEMPERATURE` | `0.1` | Sampling temperature |
| `LLM_MAX_TOKENS` | `4096` | Max response tokens |

All env vars can also be set as JVM system properties (e.g., `-Dneo4j.uri=...`).

## Key Dependencies

- **javaparser-symbol-solver-core 3.26.4** — AST parsing with fully-qualified name resolution
- **jtokkit 1.1.0** — cl100k_base tokenizer (GPT-4/LLaMA compatible)
- **langchain4j 1.0.0-beta1** — LLM orchestration (AI Services, tool-calling, memory)
- **neo4j-java-driver 5.27.0** — Neo4j graph DB client
- **gson 2.12.1** — JSON serialization
