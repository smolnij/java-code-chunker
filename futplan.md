1. Retrieval + SafeLoop only (Recommended)                                                                                   
   Leanest footprint. Retrieval scored without LLM; SafeLoop has the richest verdict output. Refactor/Ralph/Agent runners
   stubbed as TODO, added once the harness is proven.
   ❯ 2. All five modes (retrieval/refactor/safeloop/ralph/agent)
   Every existing entry point gets a runner on day 1. More code, but every mode is measurable immediately.
3. Retrieval-only
   Pure retrieval precision/recall. No LLM integration at all in the first PR. Fastest to ship; LLM modes are a follow-up.
4. Type something.
   ─────────────────────


The useful refactor, if anything, is to extract SafeLoopMain's wiring (config load → reader/retriever/agent/analyzer/loop    
construction) into a reusable builder so EvalMain can instantiate the same loop per-fixture without duplicating setup. But   
SafeLoopMain itself shouldn't pull in eval scoring.


Ultraplan approved in browser. Here is the plan:

  <ultraplan>                                                                                                                  
  # Plan: Write `future_plan.md` for java-code-chunker                                                                         

## Context

User wants a thorough analysis of the LLM-orchestration layer in                                                             
`java-code-chunker` and a single deliverable — `future_plan.md` at repo                                                      
root — containing (a) improvements to existing features and (b) new                                                          
features that help the LLM "think better", ending with a prioritized                                                         
list of all proposals with justifications.

This is an analysis-writing task, not a code change. The only file I                                                         
create is the deliverable.

## Corrections to the initial draft (verified in the codebase)

Before writing the doc, I validated the draft against the repo. The                                                          
following claims in the draft are wrong or stale and must be fixed in                                                        
`future_plan.md`:

| Draft claim | Reality | Source |                                                                                           
  |---|---|---|                                                                                                                
| `SafeRefactorLoop` and `SafetyVerdict` live in `refactor/safe/` | they live in `safeloop/` |
  `src/main/java/com/smolnij/chunker/safeloop/` |
| N-2 (AST-diff verifier) is a **new** feature | `AstDiffEngine`, `MethodDiff`, `DiffScorer`, `ScoredDiff` already exist and 
   `SafeRefactorLoop` already calls them in Phase 4a **when a non-null engine is passed** |
  `refactor/diff/AstDiffEngine.java`, `safeloop/SafeRefactorLoop.java:142-179, 276-303, 704-739` |
| P-O5 asks for line-ranges to be exposed in the prompt | `CodeChunk.toPromptFormat` already emits `Lines: start-end` |
  `model/CodeChunk.java:281` |
| `CallGraphExtractor` is at `callgraph/` (used to justify P-G1) | Correct, but `RalphLoop` uses `ChatService` (no           
  LangChain4j tool-calling) — agent-style tool enforcement only applies to `RefactorAgent` / `SafeRefactorLoop` |
  `ralph/RalphLoop.java`, `refactor/RefactorAgent.java:103-122` |
| Agent sliding window = 40 msgs (hard-coded) | Configurable via `config.getChatMemorySize()` |
  `refactor/RefactorAgent.java:111-113` |

What this reshapes in the output:

- **N-2 demotes to "finish what's started"**: make the AST-diff engine a                                                     
  non-optional dependency of `SafeRefactorLoop`, invoke it from                                                              
  `RefactorLoop` / `RalphLoop` as well, and broaden its checks                                                               
  (currently it compares a single method's AST — extend to                                                                   
  cross-method: deleted methods, renamed methods, new public members).
- **P-O5 reframes**: lines are already shown; the missing piece is                                                           
  *enforcement* — parse the LLM output and verify it only touches                                                            
  methods whose `chunkId`s appear in the prompt context.
- **Package paths** everywhere are corrected (`safeloop/` not                                                                
  `refactor/safe/`).

Confirmed true (important anchors for the doc):

- The N+1 pattern at `HybridRetriever.java:355` (`graphReader.getCallerCount(chunkId)` inside the per-candidate rerank loop)
  — `Neo4jGraphReader` has no batch variant today.
- `SafetyVerdict.parse` is regex-based with heuristic fallbacks and defaults to UNSAFE if unparseable                        
  (`safeloop/SafetyVerdict.java:117-195`).
- `HybridRetriever` only supplements with vector search when graph yields `< topK` (`retrieval/HybridRetriever.java:249`).
- `RetrievalConfig` structural bonus is binary (1.0 / 0.5 / 0.3 with `fanInThreshold=3`)                                     
  (`retrieval/RetrievalConfig.java:29-38`, `retrieval/RetrievalResult.java:66-75`).
- Imports are collected in `CodeChunk` but never rendered in `toPromptFormat` (`model/CodeChunk.java:261-301`).
- No evaluation harness, no compile/test verifier, no prompt versioning, no attempt log — all are genuine green-field        
  opportunities.

## Deliverable

**Create:** `/home/user/repo/future_plan.md`

No code changes.

## Document structure (what goes into `future_plan.md`)

  ```                                                                                                                          
  1. Current architecture (snapshot, corrected)                                                                                
     1.1 Six entry points + memory shape per loop                                                                              
     1.2 Retrieval pipeline (HybridRetriever, 5-step)                                                                          
     1.3 Graph model (current edge types)                                                                                      
     1.4 Agent tools (RefactorTools: 8 @Tool methods)                                                                          
     1.5 Response parsing (regex-based; SafetyVerdict, LlmResponseParser)                                                      
     1.6 Already-present AST diff scoring (scope + current gaps)                                                               
                                                                                                                               
  2. Improvements to existing features                                                                                         
     2.1 Orchestration & prompting  (P-O1..P-O8)                                                                               
     2.2 Retrieval & ranking        (P-R1..P-R9)                                                                               
     2.3 Graph & indexing           (P-G1..P-G7)                                                                               
                                                                                                                               
  3. New features                                                                                                              
     3.1 Reasoning aids             (N-1..N-7)                                                                                 
     3.2 Evaluation infrastructure  (N-8, N-9)                                                                                 
     3.3 Type/data-flow (mid-term)  (N-10..N-12)                                                                               
                                                                                                                               
  4. Prioritized roadmap (the list the user asked for)                                                                         
     Tier 1  Foundation                                                                                                        
     Tier 2  Reasoning quality                                                                                                 
     Tier 3  Retrieval quality                                                                                                 
     Tier 4  Polish & advanced                                                                                                 
     Tier 5  Speculative                                                                                                       
                                                                                                                               
  5. Suggested first milestone (concrete 5-item sequence)                                                                      
  ```                                                                                                                          

The prioritized list at the end is the spine of the doc: every proposal                                                      
from §2–§3 appears once in §4 with a one-paragraph justification                                                             
explaining *effect / effort*.

## Dependency / tier diagram of the proposed roadmap

This is the shape the reviewer should verify — later tiers build on                                                          
earlier ones, and the diagram shows why.

  ```mermaid                                                                                                                   
  flowchart TD                                                                                                                 
      subgraph T1["Tier 1 — Foundation"]                                                                                       
          N8[N-8 Golden-task eval harness]                                                                                     
          PO1[P-O1 JSON structured output]                                                                                     
          N2[N-2 Make AST-diff non-optional + extend]                                                                          
          PR1[P-R1 Batch caller-count - fix N+1]                                                                               
          PG6[P-G6 Render imports in prompt]                                                                                   
      end                                                                                                                      
                                                                                                                               
      subgraph T2["Tier 2 — Reasoning quality"]                                                                                
          N1[N-1 Compile+test verifier]                                                                                        
          PG1[P-G1 New edges: USES_TYPE, READS_FIELD, OVERRIDES, TEST_FOR ...]                                                 
          PO3[P-O3 Self-critique pass]                                                                                         
          PO6[P-O6 Attempt log]                                                                                                
          PO7[P-O7 Token-budget aware context]                                                                                 
      end                                                                                                                      
                                                                                                                               
      subgraph T3["Tier 3 — Retrieval"]                                                                                        
          PR3[P-R3 MMR diversity]                                                                                              
          PR7[P-R7 Always-on vector supplement]                                                                                
          PR5[P-R5 Stemming + synonyms]                                                                                        
          PR6[P-R6 Embedding cache]                                                                                            
          PG2[P-G2 Javadoc capture]                                                                                            
          N5[N-5 Class summaries]                                                                                              
      end                                                                                                                      
                                                                                                                               
      subgraph T4["Tier 4 — Polish"]                                                                                           
          PO2[P-O2 Plan-then-execute enforcement]                                                                              
          N3[N-3 Tool-call budget]                                                                                             
          PG3[P-G3 AST-aware splitting]                                                                                        
          PG4[P-G4 Complexity as node props]                                                                                   
          PG5[P-G5 Git-aware ranking]                                                                                          
          N4[N-4 Memory summarization]                                                                                         
          N7[N-7 Persistent journal]                                                                                           
          N9[N-9 Prompt versioning]                                                                                            
      end                                                                                                                      
                                                                                                                               
      subgraph T5["Tier 5 — Speculative"]                                                                                      
          N6[N-6 Multi-shot ensemble]                                                                                          
          N10[N-10 Intra-procedural data flow]                                                                                 
          N11[N-11 Test impact prediction]                                                                                     
          N12[N-12 Cross-repo retrieval]                                                                                       
      end                                                                                                                      
                                                                                                                               
      N8 --> N1                                                                                                                
      N8 --> PO1                                                                                                               
      N8 --> PO3                                                                                                               
      N8 --> N7                                                                                                                
      N8 --> N9                                                                                                                
                                                                                                                               
      PG1 --> N11                                                                                                              
      PG1 --> N10                                                                                                              
      N1 --> N11                                                                                                               
      PG2 --> N5                                                                                                               
      PR6 --> N5                                                                                                               
      PO1 --> N7                                                                                                               
      N7 --> N9                                                                                                                
                                                                                                                               
      T1 --> T2                                                                                                                
      T2 --> T3                                                                                                                
      T3 --> T4                                                                                                                
      T4 --> T5                                                                                                                
  ```                                                                                                                          

Key dependencies the reviewer should sanity-check:

- **N-8 (eval harness) before almost everything**: without metrics,                                                          
  "this prompt is better" is unfalsifiable. Every tier-2+ item depends                                                       
  on it to prove value.
- **P-G1 (new edges) gates N-11 (test impact) and N-10 (data-flow)**:                                                        
  these reuse `USES_TYPE` / `READS_FIELD` / `TEST_FOR`.
- **N-1 (compile/test verifier) depends on N-8** only for measurement,                                                       
  not for function — it can ship earlier but its value is invisible                                                          
  without the harness.
- **P-O1 (JSON output) before N-7 (journal) and N-9 (prompt versioning)**:                                                   
  structured logs only pay off when inputs/outputs are structured.

## Critical files the doc will reference (so paths are accurate)

- `retrieval/HybridRetriever.java` — 5-step pipeline; `rerank()` is the                                                      
  N+1 site
- `retrieval/Neo4jGraphReader.java` — add `getCallerCountsBatch` here
- `retrieval/RetrievalResult.java:66-75` — structural bonus (binary                                                          
  today)
- `retrieval/RetrievalConfig.java` — weights + bonuses
- `model/CodeChunk.java:261-301` — `toPromptFormat` where imports are                                                        
  missing; line ranges are already printed
- `model/graph/GraphEdge.java` — enum extension site for P-G1
- `store/Neo4jGraphStore.java` — edge-creation + schema
- `callgraph/CallGraphExtractor.java` — AST traversal where new                                                              
  edges (USES_TYPE, READS_FIELD, OVERRIDES) plug in
- `tokenizer/TokenCounter.java` — AST-aware split (P-G3)
- `refactor/RefactorAgent.java` — LangChain4j AI Service wiring,                                                             
  sliding-window memory
- `refactor/RefactorTools.java` — `@Tool` definitions; tool-call                                                             
  budget (N-3) attaches here
- `refactor/RefactorLoop.java` — single-turn refactor; candidate for                                                         
  integrating AST-diff (N-2 extension)
- `refactor/LlmResponseParser.java` — regex parser to replace with                                                           
  schema validation (P-O1)
- `refactor/diff/AstDiffEngine.java`, `MethodDiff.java`, `DiffScorer.java`,                                                  
  `ScoredDiff.java` — **existing** AST diff infra to extend, not                                                             
  rebuild
- `safeloop/SafeRefactorLoop.java` — 5-phase self-improving loop                                                             
  (phases 3/4/5 in `run()` lines 245-371)
- `safeloop/SafetyVerdict.java` — the other regex-based parser to                                                            
  replace (P-O1)
- `safeloop/SafeLoopTools.java` — graph-coverage enforcer
- `ralph/RalphLoop.java`, `RalphConfig.java` — worker@0.3/judge@0.1                                                          
  loop; candidate for N-6 ensemble
- `safeloop/distributed/DistributedSafeRefactorLoop.java` — remote                                                           
  generator variant

## Verification

1. Read the written `future_plan.md` end-to-end and check:
    - every file path listed resolves to an existing file (verify                                                             
      against the repo);
    - every "already exists" claim (AST diff, line ranges in prompt,  