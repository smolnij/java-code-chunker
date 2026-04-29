# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build fat JAR
mvn clean package -q

# Each main takes exactly one arg: a properties file.
java -jar target/java-code-chunker-1.0-SNAPSHOT.jar config/chunker.properties
```

Every main entry point reads its configuration from a single `.properties`
file — no CLI flags, no env vars, no `-D` system properties. Default-valued,
heavily commented templates live in `config/`:

| Main | Properties file |
|------|-----------------|
| `ChunkerMain` | `config/chunker.properties` |
| `RetrievalMain` | `config/retrieval.properties` |
| `RefactorMain` | `config/refactor.properties` |
| `AgentRefactorMain` | `config/agent-refactor.properties` |
| `RalphMain` | `config/ralph.properties` |
| `SafeLoopMain` | `config/safeloop.properties` |
| `DistributedSafeLoopMain` | `config/safeloop-distributed.properties` |
| `EvalMain` | `config/eval.properties` |
| `ApplyMain` | `config/apply.properties` |
| `ReindexInspectMain` | `config/reindex-inspect.properties` |

Copy the template you want, edit the per-run fields (query, repoRoot,
neo4j.password, etc.), and pass the path as the only argument.

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

### Refactoring Modes (7 entry points)

All modes use `HybridRetriever` to fetch context from Neo4j, then drive an LM-Studio LLM:

1. **`RetrievalMain`** — retrieval only
2. **`RefactorMain`** — single-turn refactoring with human review
3. **`AgentRefactorMain`** — LangChain4j agentic tool-calling loop
4. **`RalphMain`** — worker/judge loop (separate LLM personas)
5. **`SafeLoopMain`** — safety-gated loop with judge verdicts
6. **`DistributedSafeLoopMain`** — multi-machine planner + analyzer agents
7. **`EvalMain`** — golden-task eval harness; scores retrieval + safeloop fixtures from `eval-fixtures/` against a gold set (precision@K, recall@K, MRR, analyzer.verdict). Supports `eval.selfCheck=true` (no Neo4j/LLM) and `eval.baseline=...` regression diffs.

### Configuration

All configuration lives in `config/<main>.properties` — one file per main
entry point. Each file is self-documenting (every key has a comment block
above it) and ships pre-populated with the same defaults the code falls
back to when a key is omitted. Loading is implemented by
`com.smolnij.chunker.config.PropertiesLoader`; each `Config` class exposes
a `fromProperties(Properties)` factory.

## Key Dependencies

- **javaparser-symbol-solver-core 3.26.4** — AST parsing with fully-qualified name resolution
- **jtokkit 1.1.0** — cl100k_base tokenizer (GPT-4/LLaMA compatible)
- **langchain4j 1.0.0-beta1** — LLM orchestration (AI Services, tool-calling, memory)
- **neo4j-java-driver 5.27.0** — Neo4j graph DB client
- **gson 2.12.1** — JSON serialization
