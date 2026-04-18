# Eval fixtures (N-8 harness)

Seed fixtures for `com.smolnij.chunker.eval.EvalMain`. Each `*.json` file
defines a single task: a query, a mode (`retrieval` or `safeloop`), a gold
set of chunk IDs that should appear in the retrieved context, and (for
safeloop) the expected analyzer verdict.

All five seeds self-evaluate against **this project's own code**, so running
them against a Neo4j graph built from a different repo will produce low
precision/recall — that is expected and not a harness bug.

## Running

### Self-check (no Neo4j, no LLM)

Verifies harness wiring end-to-end by synthesizing a perfect run from each
fixture's gold set:

```bash
mvn clean package -q
java -cp target/java-code-chunker-1.0-SNAPSHOT.jar \
     com.smolnij.chunker.eval.EvalMain --self-check
```

Exit code 0 means every fixture's retrieval + analyzer metric passed under
synthesis. A nonzero exit code points at a schema or scorer bug, not a
retrieval regression.

### Live retrieval

Requires Neo4j pre-loaded with this project's graph via `ChunkerMain`:

```bash
java -jar target/java-code-chunker-1.0-SNAPSHOT.jar . out 512
java -cp target/java-code-chunker-1.0-SNAPSHOT.jar \
     com.smolnij.chunker.eval.EvalMain --mode retrieval
```

Results land in `eval-results/<UTC-timestamp>/`. Keep `runs.jsonl` around —
subsequent runs can be diffed against it via `--baseline`.

### Live safeloop

Requires Neo4j **and** LM-Studio (embeddings + chat endpoints):

```bash
java -cp target/java-code-chunker-1.0-SNAPSHOT.jar \
     com.smolnij.chunker.eval.EvalMain --mode safeloop --limit 1
```

Each safeloop fixture can take several minutes; use `--limit` and `--id`
to scope the run.

## Fixture schema (v1)

| Field | Required | Notes |
|---|---|---|
| `schemaVersion` | yes | Must be `1`. Breaking changes bump this. |
| `id` | yes | Unique fixture id, used as filename stem and in reports. |
| `description` | no | Human note; ignored by the harness. |
| `mode` | yes | `retrieval` or `safeloop`. Other modes (refactor/ralph/agent) are reserved for later PRs. |
| `query` | yes | Natural-language query passed to `HybridRetriever` / `SafeRefactorLoop`. |
| `topK` | no | Overrides `RetrievalConfig.topK` for this fixture only. |
| `gold.anchor` | no | Expected anchor chunk id. Used for anchor-stability diagnostics, not scored directly. |
| `gold.relevant` | yes | Ordered list of chunk ids. Precision@K / recall@K / MRR are computed against this. |
| `expected.analyzer` | no | For `safeloop` mode: `SAFE` or `UNSAFE`. Compared to `SafeLoopResult.isSafe()`. |
| `expected.compile` | no | Reserved for N-1 verifier. Harness currently emits `NOT_RUN`. |
| `expected.tests` | no | Reserved for N-1 verifier. |
| `tags` | no | Filter key for `--tag <tag>`. |
| `timeoutSeconds` | no | Advisory; not yet enforced. |

## Regenerating gold sets

After a retrieval- or chunking-rule change, hand-curate each fixture by
running `RetrievalMain` with the fixture's query and inspecting the top-K
output. Update `gold.relevant` to the chunk ids you believe *should* be
returned, in order of importance.

Chunk ids follow this format:
```
com.package.Class#methodName(ParamType1, ParamType2)
```
(no generics, simple type names only — matches what `JavaCodeChunker`
writes to Neo4j).

## Adding a fixture

1. Drop a new `*.json` file under `eval-fixtures/` matching the schema above.
2. Add its filename to `eval-fixtures/index.txt` so it resolves from the
   classpath when the harness is run from a distributed JAR without access
   to the source tree.
3. Run `--self-check` to verify the schema is accepted.
4. Run `--id <new-fixture-id>` live to validate the gold set against the
   current graph.

## Output layout

```
eval-results/<ts>/
├── manifest.json      harness config snapshot + CLI args
├── runs.jsonl         one JSON line per fixture (stable schema)
├── summary.txt        per-mode + per-metric human rollup
├── summary.json       machine rollup (pass-rate, means, counts)
└── diff.txt|json      only when --baseline was provided
```
