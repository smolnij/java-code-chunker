# java-code-chunker — Future Plan

Analysis of the current LLM-orchestration layer with proposals to
improve existing features and add new ones, ending with a prioritized
roadmap. Written 2026-04-17.

All file paths are relative to
`src/main/java/com/smolnij/chunker/`.

---

## 1. Current architecture (snapshot)

### 1.1 Six entry points

| Mode | Loop shape | Memory | Verdict |
|---|---|---|---|
| `RetrievalMain` | one-shot retrieval | none | none |
| `RefactorMain` (`refactor/RefactorLoop`) | analyze → refactor → safety, ≤2 refinements | none | inline safety prompt |
| `AgentRefactorMain` (`refactor/RefactorAgent`) | LangChain4j AI Service tool-calling | sliding window, size = `config.getChatMemorySize()` (`RefactorAgent.java:111-113`) | none |
| `RalphMain` (`ralph/RalphLoop`) | worker(0.3) → judge(0.1), ≤5 iters; uses `ChatService` directly, **not** LangChain4j tool-calling | feedback-only injection | binary judge |
| `SafeLoopMain` (`safeloop/SafeRefactorLoop`) | plan → prefetch → refactor → AST-diff → analyze → decide, ≤5 iters | agent memory + verdict history | structured `SafetyVerdict` |
| `DistributedSafeLoopMain` (`safeloop/distributed/DistributedSafeRefactorLoop`) | local planner-analyzer drives remote generator over a tool-call channel | planner sliding window | structured |

### 1.2 Retrieval pipeline (`retrieval/HybridRetriever`)

`resolveEntryPoint → expandSubgraph(BFS, maxDepth=2 default) →
fetchChunks → rerank → enrich with anchor→result paths`.

Score = `0.6·cosine + 0.3·(1/(1+hop)) + 0.1·structuralBonus` where
`structuralBonus` is binary: same-class 1.0, same-package 0.5,
fan-in ≥ `fanInThreshold` (default 3) +0.3
(`retrieval/RetrievalConfig.java:29-38`,
`retrieval/RetrievalResult.java:66-75`).

### 1.3 Graph model

Edge types in `model/graph/GraphEdge.java:20-26`:
`CALLS`, `CALLED_BY`, `BELONGS_TO`, `HAS_FIELD`, `IMPLEMENTS`,
`EXTENDS`, `CONTAINS`.

`CodeChunk` (`model/CodeChunk.java`) carries: chunkId, FQ class,
class signature + annotations, field declarations, method signature +
annotations, `imports` (collected but **not rendered** in
`toPromptFormat`), `calls`, `calledBy`, code, token count, and
already-rendered `Lines: start-end` (`model/CodeChunk.java:281`).

### 1.4 Agent tools (`refactor/RefactorTools`)

Eight `@Tool` methods exposed to the agent: `retrieveCode`,
`retrieveCodeById`, `getMethodCallers`, `getMethodCallees`,
`findCallPath`, `traceCallChain`, `getSubgraphTopology`, `expandNode`.
All return human-readable text. There is no per-loop tool-call
budget; LangChain4j's `maxSequentialToolsInvocations` is currently
commented out (`refactor/RefactorAgent.java:120`).

### 1.5 Response parsing

Both `refactor/LlmResponseParser.java` and
`safeloop/SafetyVerdict.java` are regex-based with multiple lenient
fallbacks. `SafetyVerdict.parse` defaults to `UNSAFE` when it cannot
locate a `VERDICT:` token (`safeloop/SafetyVerdict.java`, 353 lines).
A malformed `MISSING:` line silently yields no missing references and
exits the refinement loop early.

### 1.6 AST-diff scoring (already present)

`refactor/diff/` contains `AstDiffEngine`, `MethodDiff`, `DiffScorer`,
`ScoredDiff`. `safeloop/SafeRefactorLoop` already wires them in:

- field declared at line 142, accepted via constructor at 169;
- invoked in Phase 4a at lines 282-302 via `computeAstDiffs(...)`;
- formatted into the analyzer prompt by `buildAstDiffSummary` at
  554-565;
- per-method diff produced at line 729 by `diffEngine.diff(original,
  codeBlock)`.

The engine is **optional** (constructor accepts a nullable instance)
and is scoped to a single method's AST. `RefactorLoop` and `RalphLoop`
do not invoke it. This matters for §3.1 / N-2 below: the work is to
finish what's started, not to build the engine.
DONE, see in summary

### 1.7 Storage and chunking

`store/Neo4jGraphStore` owns schema, batch upserts, and an optional
vector index on `Method.embedding`. `tokenizer/TokenCounter` uses
`cl100k_base` and splits methods at line boundaries when token budget
is exceeded — semantics-aware splitting is not implemented.
`callgraph/CallGraphExtractor` walks `MethodCallExpr` nodes via
JavaParser Symbol Solver and records forward + reverse call edges
only; type, field, exception, and override relationships are not
extracted.

---

## 2. Improvements to existing features

### 2.1 Orchestration & prompting

**P-O1. Structured (JSON-schema) output**
Replace `refactor/LlmResponseParser.java` and
`safeloop/SafetyVerdict.java` with JSON-schema-constrained output
(LM-Studio supports `response_format`; for models that do not, fall
back to a single tool-call whose arguments are the structured
verdict). Eliminates the silent-failure modes in §1.5 and makes the
journal (N-7) and prompt-versioning (N-9) trivially analyzable.
DONE, see in summary


**P-O2. Plan-then-execute enforcement**
The `RefactorAgent` system prompt
(`refactor/RefactorAgent.java:63-87`) already asks the model to gather
context before editing; today only the model's discipline enforces it.
Make the harness reject any code-producing turn that lacks a preceding
structured `RetrievalPlan` artifact (target chunkIds + edges to
traverse + invariants to preserve).

**P-O3. Self-critique pass (Reflexion-style)**
Between Phase 3 (refactor) and Phase 4 (analyze) of
`safeloop/SafeRefactorLoop.java` (around lines 245-371), insert a
single self-review turn at lower temperature: "list the three weakest
assumptions in your previous edit; if any are unverified, request the
missing context." One extra LLM call typically halves analyzer
round-trips.
DONE

**P-O4. Few-shot exemplars in `PromptBuilder`**
Add a small bank of canonical examples (rename, extract method,
inline, signature change with caller updates) and pick 1-2 whose AST
shape resembles the current target (cosine on signature embedding is
enough). File: `refactor/PromptBuilder.java`.

**P-O5. Edit-region enforcement (not "show line ranges" — that exists)**
`CodeChunk.toPromptFormat` already emits `Lines: start-end`
(`model/CodeChunk.java:281`). The missing piece is *enforcement*:
parse the LLM's emitted code blocks (or structured edits per P-O1),
identify which methods they touch, and reject the response if any
touched method is not in the prompt's chunk set. Catches scope drift
before the analyzer has to.

**P-O6. Memory of prior attempts**
Persist a per-task `AttemptLog` (chunkId → {edit hash, verdict tag,
top risk}). On the next iteration of `safeloop/SafeRefactorLoop`,
inject "you previously tried X and the analyzer flagged Y — do not
repeat." Today the agent's chat memory keeps turns but the loop never
serializes a compact failure summary. New file:
`safeloop/AttemptLog.java`.

**P-O7. Token-budget aware context assembly**
Replace the fixed top-K chunk list with a budget allocator: reserve
percentages for anchor / direct callers / direct callees / type
neighbors, with overflow demoted to topology-only (signature +
calls/calledBy, no body). Files:
`retrieval/HybridRetriever.java:386-424`,
`refactor/PromptBuilder.java`.
DONE

**P-O8. Differentiated termination signals**
`safeloop/SafeRefactorLoop` exits today on
`SAFE | converged | stagnant | maxIter`. Add `compileFailed`,
`testsFailed`, `editRegionViolation`, `budgetExhausted` and surface
them in `safeloop/SafeLoopResult`.

### 2.2 Retrieval & ranking

**P-R1. Fix N+1 caller-count fetch**
`retrieval/HybridRetriever.java:355` calls
`graphReader.getCallerCount(chunkId)` per candidate inside the rerank
loop. Add `getCallerCountsBatch(Collection<String>) → Map<String,
Integer>` to `retrieval/Neo4jGraphReader.java` and call it once
before the loop. Saves ~`topK` round-trips per retrieval; near-zero
risk.
DONE, see in summary

**P-R2. Continuous structural-bonus gradient**
Replace binary `fanIn ≥ threshold` with `min(fanIn / threshold, 1.0)`,
and add `min(fanOut / threshold, 1.0)`. File:
`retrieval/RetrievalResult.java:66-75`.
DONE

**P-R3. MMR diversity rerank**
After scoring, apply maximal marginal relevance with λ≈0.7 over the
top-K to penalize redundancy (multiple chunks from the same class /
package). Prevents the "all 10 from one file" failure mode. New step
in `retrieval/HybridRetriever.rerank()`.

**P-R4. Adaptive BFS depth**
Make `maxDepth` query-dependent: start at 2; if the anchor has fewer
than `K/2` neighbors at depth 2, escalate to 3. Files:
`retrieval/RetrievalConfig.java`,
`retrieval/HybridRetriever.java:214-228`.
DONE

**P-R5. Smarter keyword extraction**
Add Snowball stemming and a Java-domain synonym map
(`init|create|make`, `fetch|load|get|read`,
`dispose|close|release`, …). Drop the hardcoded English stoplist in
favour of an IDF cutoff over chunk vocabulary. File:
`retrieval/HybridRetriever.java:176-193`.

**P-R6. Session-level embedding cache**
LRU cache (chunkId → embedding) populated by `getStoredEmbeddings`
and `embedBatch`. Avoids re-embedding on every retrieval and on
re-runs in interactive use. Wire into
`embedding/LmStudioEmbeddingService.java`.

**P-R7. Always-on vector supplement**
Today vector search runs only when graph yields fewer than topK
(`retrieval/HybridRetriever.java:249`). Run it unconditionally and
merge before rerank — graph-only retrieval misses semantically
similar but structurally distant code.
DONE

**P-R8. Query-rewriting pre-step**
A cheap LLM call (or template) rewrites natural-language queries into
2-3 keyword/identifier candidates. Keeps determinism (no synthetic
documents). New class `retrieval/QueryRewriter.java`.

**P-R9. Renderable retrieval explanations**
The recent `GraphPath` / `SubgraphView` work feeds anchor→result paths
to the LLM. Extend to render *why* each chunk was selected — "called
by anchor; same package; cosine 0.71." Helps the model judge how much
to trust each chunk.

**P-R10 🔧 Tool call #19: retrieveCode("any config file patterns or properties files in the project")
We need LLM-generated summary of a class.

**P-R11 We need to keep diffs in memory, because now LLM looks in neo4j and cannot see the changes done.

### 2.3 Graph & indexing

**P-G1. New edge types** (single biggest semantic uplift)
Extend `model/graph/GraphEdge.java`:

- `USES_TYPE` (Method → Class/Interface) — parameter and local types
- `RETURNS_TYPE` (Method → Class/Interface)
- `READS_FIELD` / `WRITES_FIELD` (Method → Field) — data flow
- `THROWS` / `CATCHES` (Method → ExceptionType)
- `OVERRIDES` (Method → Method) — polymorphism
- `TEST_FOR` (TestMethod → Method) — heuristic on `*Test` classes
- `IMPORTS` (Class → Class) — explicit import edges
- `INNER_CLASS_OF` (Class → Class)

These ride the existing JavaParser AST traversal in
`callgraph/CallGraphExtractor.java`. Schema additions land in
`store/Neo4jGraphStore` (constraints) and edge creation in
`createEdges`.
DONE


**P-G2. Javadoc & inline-comment capture**
Add `documentation` field to `model/CodeChunk.java`; concat with code
in the embedding text; surface in `toPromptFormat`. Cheap and gives
embeddings a strong intent signal in well-documented codebases.

**P-G3. AST-aware splitting in `tokenizer/TokenCounter`**
Today line-greedy splitting can break mid-block. Switch to "split at
the nearest statement boundary at-or-before the budget" with line
boundary as fallback. File: `tokenizer/TokenCounter.java`.
DONE

**P-G4. Code-quality features as Method node properties**
Compute and store: cyclomatic complexity, statement count, max
nesting depth, parameter count, boilerplate score. Ranking can
down-weight trivial methods; the LLM can see "complexity 14" as a
context cue.

**P-G5. Git-aware ranking signals**
At chunking time, walk `git log -- <file>` per file and attach
last-modified timestamp, 90-day commit count, distinct author count.
Use as a small additive bonus in `RetrievalResult.structuralBonus`
("hot" methods slightly preferred). Optional dep: JGit.

**P-G6. Render imports in prompt**
Imports are already collected on `CodeChunk` but not rendered by
`toPromptFormat` (`model/CodeChunk.java:261-301`). Wire them through.
Cheapest possible win.

**P-G7. Inner-class containment**
Add `INNER_CLASS_OF` so the model can find sibling inner classes when
working on a parent. Cheap to extract from JavaParser (folded into
P-G1's enum extension).

---

## 3. New features

### 3.1 Reasoning aids

**N-1. Compile + test verifier loop**
After the agent produces an edit, apply it to a worktree, run
`mvn -q -o compile` (and optionally `mvn -q -o test -pl <module>`),
feed compile errors / failing test names back into the loop as
feedback. The analyzer is currently the only correctness signal; a
build/test gate is ground truth. New module:
`refactor/verify/CompileVerifier.java`,
`refactor/verify/TestVerifier.java`. Hook into
`safeloop/SafeRefactorLoop` after Phase 3.

**N-2. Make AST-diff non-optional and broaden checks**
`refactor/diff/AstDiffEngine` already exists and `SafeRefactorLoop`
already calls it (`safeloop/SafeRefactorLoop.java:282-302, 729`), but
the engine is optional (constructor accepts null) and scoped to a
single method. Work:

1. Make the engine a required dependency of every loop
   (`refactor/RefactorLoop`, `ralph/RalphLoop`,
   `safeloop/SafeRefactorLoop`).
2. Extend it to detect cross-method invariants:
   deleted methods, signature changes on public members, new
   public/protected members not requested by the task, removed
   `@Override` annotations.
3. Wire diff output into `RefactorLoop`'s safety prompt and into
   `RalphLoop`'s judge feedback.

**N-3. Tool-call budget & cost tracker**
Cap `RefactorTools` calls per loop iteration with a hard budget;
surface "3 of 10 tool calls remaining" in the system prompt. Today
`maxSequentialToolsInvocations` is commented out
(`refactor/RefactorAgent.java:120`). Files:
`refactor/RefactorTools.java`, `refactor/RefactorAgent.java`.

**N-4. Conversation summarization**
When agent chat memory exceeds a threshold (today: configurable
sliding window in `RefactorAgent.java:111-113`), insert a
summarization turn that compacts older messages into a 200-token
recap. Keeps long agentic loops on-task. New helper:
`refactor/MemorySummarizer.java`.

**N-5. Hierarchical chunking (class summary + method detail)**
Generate a synthetic `ClassSummary` chunk per class containing field
list, method index, key annotations, and a one-sentence
LLM-generated purpose. Index it for retrieval; great anchor for
"what does class X do?" queries. New types:
`model/ClassSummaryChunk.java`, new pass in `JavaCodeChunker`.

**N-6. Multi-shot worker with critic ensemble**
Run the worker with `n=3` at temperature 0.5; have the analyzer rank
the three; submit the winner. Trades compute for quality on hard
refactors. Wire into `ralph/RalphLoop` and `safeloop/SafeRefactorLoop`
behind a flag.

**N-7. Persistent task journal**
Beyond `AttemptLog` (P-O6), keep a SQLite/JSONL log of every
(query, retrieved chunkIds, prompt, response, verdict, tool-call
trace) tuple. Enables offline replay, prompt A/B testing, regression
detection. New module: `eval/TaskJournal.java`. Depends on P-O1
because structured logs only pay off when inputs/outputs are
structured.

### 3.2 Evaluation infrastructure

**N-8. Golden-task eval harness**
A small fixture suite of (repo snapshot, query, expected behaviour
preserved) tasks runnable via a new entry point `EvalMain`. Each
prompt iteration is scored against:

- compile pass-rate
- test pass-rate
- analyzer SAFE-rate
- chunks-retrieved precision/recall vs. a labelled gold set

Without this, every other improvement is opinion. The project
explicitly has "no automated tests" today (per `CLAUDE.md`).
DONE, see in summary 

**N-9. Prompt versioning**
Move prompt strings out of `refactor/PromptBuilder.java` into
versioned files (`prompts/refactor.v3.md`) loaded at runtime. The
journal records which version produced which result. Enables
systematic prompt iteration.

### 3.3 Type / data-flow features (mid-term)

**N-10. Lightweight intra-procedural data flow**
Within a method, derive (param → field-write), (field-read → return),
(param → call-arg) chains. Attach as edge metadata or as compact
textual hints in the prompt ("param `userId` flows to
`repo.findById`"). Helps the model reason about behavior
preservation. Builds on P-G1.

**N-11. Test impact prediction**
Given a target chunk, walk `TEST_FOR` (added in P-G1) and surface the
relevant tests in the prompt context. Closes the loop with N-1's test
verifier.

**N-12. Cross-repo retrieval**
Allow Neo4j to host multiple repos with a `repo` label. Queries can
opt into "show me how repo Y solved a similar problem." Big lift;
only worthwhile after the single-repo flow is solid.

---

## 4. Prioritized roadmap

Ranked by `(expected effect on output quality and reliability) /
(implementation effort and risk)`. Tier 1 should ship first; within
each tier the order is the recommended sequence.

### Tier 1 — Foundation

1. **N-8. Golden-task eval harness.** Every other change is
   unverifiable without metrics; the project has no automated tests
   today. Moderate effort (fixture loader + four metrics) but unlocks
   honest comparison for everything that follows. 
   - DONE, requires improval, more tasks and better prompt 
2. **P-O1. JSON structured output.** The regex parser is the single
   most fragile link — a malformed `MISSING:` silently exits the
   refinement loop, a missing `BREAKING:` produces empty risk lists.
   JSON-mode is supported by LM-Studio. High reliability, low effort.
   - DONE, but must be verified, as it doesn't currently work if structuredOutput is enabled in the LM-studio.
3. **N-2. Finish AST-diff: non-optional + cross-method.** The engine
   already exists and `SafeRefactorLoop` already integrates it
   (`safeloop/SafeRefactorLoop.java:282`); the gap is making it
   mandatory and broadening its checks. Cheapest empirical
   correctness signal — pure local computation, no extra LLM cost.
   - DONE, but looks like AST scoring always says that refactoring is bad. Might be because LLM responds with plain text.
   Model converges after 2 retires with BAD verdict from AST
4. **P-R1. Batch caller counts (fix N+1).** Free latency win
   (≈`topK` round-trips per retrieval), one-method PR, near-zero
   risk.
   - DONE
5. **P-G6. Render imports in prompts.** Data is already collected;
   wire it through `toPromptFormat`. Imports are critical "what types
   are in scope" context. Trivial change, immediate quality lift.
   - DONE

### Tier 2 — Reasoning quality

6. **N-1. Compile + test verifier.** Largest single quality jump,
   because analyzer LLM verdicts are heuristic and a build/test gate
   is ground truth. Higher effort (worktree management, build
   invocation) but the only path to claims about correctness rather
   than plausibility. Depends on N-8 to measure effect.
7. **P-G1. New edge types (USES_TYPE, READS_FIELD, OVERRIDES,
   TEST_FOR, …).** The graph today expresses "who calls whom" and
   class hierarchy; most refactor reasoning needs "what types flow
   through this code" and "what tests cover it." Highest semantic
   uplift among graph changes; gates N-10 and N-11.
   -DONE
8. **P-O3. Self-critique pass.** One extra LLM turn typically removes
   30–50% of analyzer round-trips in published reflexion-style
   results. Cheap drop-in between existing phases.
   -doing
9. **P-O6. Attempt log / failure memory.** Current loops can repeat
   the same broken edit because each refinement starts fresh. A
   3-line "previously tried X, failed because Y" line in the system
   prompt fixes most loops. 
10. **P-O7. Token-budget aware context assembly.** A runaway top-K
    can blow the context window or starve the actual code section.
    Budgeted assembly + topology-only fallback for overflow keeps the
    prompt useful at the edges.

### Tier 3 — Retrieval quality

11. **P-R3. MMR diversity rerank.** Prevents the "all 10 chunks from
    one class" degenerate case on broad queries. Tiny code change in
    one place.
12. **P-R7. Always-on vector supplement.** Two-line change; current
    conditional fallback hides the model from semantically related
    but structurally distant code.
13. **P-R5. Stemming + synonyms.** Easy recall lift for
    natural-language queries; the current common-word stoplist drops
    important domain terms.
14. **P-R6. Embedding cache.** Latency, not quality — but interactive
    UX matters for the retrieval-only mode. Rides along after P-R1.
15. **P-G2. Javadoc capture.** Gives embeddings a much stronger
    signal than code alone in documented codebases; essentially free
    if the docs exist.
16. **N-5. Hierarchical chunking (class summaries).** Radically
    improves "what does class X do?" type queries. Needs an offline
    LLM pass for purpose strings; deferrable until after the
    embedding cache so we don't re-embed unnecessarily.

### Tier 4 — Polish & advanced

17. **P-O2. Plan-then-execute enforcement.** Good discipline once N-8
    can measure impact; without metrics this could regress speed
    without visible gain.
18. **P-O5. Edit-region enforcement.** Catches scope drift before the
    analyzer; cheap once P-O1 gives structured edits.
19. **N-3. Tool-call budget.** Mostly a guardrail; helpful in
    production but rarely the bottleneck on quality.
20. **P-G3. AST-aware token splitting.** Small but consistent
    improvement in chunk coherence; only matters when methods exceed
    `maxTokens`, which is rare in practice.
21. **P-G4. Complexity as node properties.** Useful inputs for
    ranking and prompt cues; each individual feature contributes
    modestly.
22. **P-G5. Git-aware ranking signals.** Moderate effort (JGit dep),
    modest payoff. Useful in long-lived repos to bias toward "live"
    code.
23. **N-4. Conversation summarization.** Only matters once agent
    loops are running long enough to spill the chat-memory window;
    otherwise inert.
24. **N-7. Persistent task journal.** Powerful for offline analysis
    but needs P-O1 for structure and N-8 / N-9 to be interesting.
25. **N-9. Prompt versioning.** Organizational hygiene; depends on
    N-8 to be useful.
26. **P-R4 / P-R8 / P-R9 / P-R2 / P-O4 / P-O8 / P-G7.** Targeted
    refinements worth a few percent each. Pick up opportunistically
    once metrics (N-8) show which weakness dominates the workload.

### Tier 5 — Speculative

27. **N-6. Multi-shot ensemble.** Costly (3× generation per loop);
    only justified when a single refactor's value (or risk) is high.
28. **N-10. Intra-procedural data flow.** Heavy implementation;
    payoff depends on whether P-G1's type edges already give the
    model enough.
29. **N-11. Test impact prediction.** Depends entirely on P-G1's
    `TEST_FOR` quality; revisit after that data exists.
30. **N-12. Cross-repo retrieval.** Only after the single-repo
    product is mature.

---

## 5. Suggested first milestone

A concrete five-item sequence: **N-8 → P-O1 → N-2 → P-R1 → P-G6**.
This produces a measurable baseline (eval harness), a reliable parser
(JSON), a free correctness check (extended AST-diff, made
mandatory), an immediate latency win (batch caller counts), and one
prompt-content upgrade (imports rendered). After this, every later
proposal can be evaluated against the harness rather than argued
about.
