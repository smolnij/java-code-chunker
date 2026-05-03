Analysis of worklog_eval

The headline pathology: the 200-call commitPlan storm

Lines 367–768 (≈400 lines, ~16M ms / 4.5h spent) the agent emits commitPlan(0 staged op(s)) 204 consecutive times until the harness aborts with     
"exceeded 200 sequential tool invocations" (line 769). Even after the agent had already produced its final "task already completed" proposal at line
327 and emitted a self-review at line 354, it keeps firing the same no-op.

Two compounding bugs make this worse:
- No early kill-switch. [noop] is treated as benign — the limiter only fires at 200. A 4.5h waste should have died in seconds.
- Streaming output is being concatenated into log lines. Lines 627/629 read 🔨 Apply tool #135:trace commitPlan… and 🔨 ApselfReply tool #136… —    
  fragments of streamed model text (trace, selfRe…) are being interleaved into the tool-printer line. That points to a System.out race between the
  streaming token printer and the tool-call printer (no synchronized writer / line buffer).

Other concrete problems visible in the log

1. Resolver mis-anchoring kills two retrieval fixtures outright.                                                                                    
   - retrieval-callgraph-extractor (l. 42): "How does CallGraphExtractor record forward and reverse call edges?" → resolver picks #reset() because it
   has the highest fan-in among class methods. Gold anchor is extractCalls(...). Resolver fallback is purely structural; query semantics are ignored.
   - retrieval-hybrid-rerank (l. 88): picks buildEmbeddingText (fan-in 0) over retrieve(String). Same root cause.
2. Ghost vector fallback (l. 967–968): agent asks for HybridRetriever.java; resolver normalises that to HybridRetriever#java, fails to resolve, and
   silently lands on RunContext#retriever() via vector fallback — a totally unrelated method. The agent is not told this happened beyond a one-liner   
   buried in graph trace.
3. Snippet-verifier vs. project-verifier disagreement (l. 1067–1083): verifyJavaSnippet [fail] keeps reporting errors, then                         
   getCompilationErrors(staged=0) [ok] returns clean (because the change was already committed), and the agent declares "✅ All Tests Pass". The two   ─
   verifiers are answering different questions; the log doesn't surface the disagreement.
4. AST diff matcher always misses (l. 776, 1208): "no code blocks matched to graph methods" → "Worst-case AST safety: 1.00". The safety check is    
   silently a no-op for every fixture, including the SAFE pass.
5. Massive resolver redundancy. 'com.smolnij.chunker.retrieval.RetrievalResult' is re-resolved 20+ times within one fixture (every commitPlan
   re-runs the resolver), each one re-printing a [resolver] line. No request-scoped memoization.
6. Identical retrievals repeated (no cache visibility):   
   - getClassOverview("RetrievalResult") 4× (l. 991, 1010, 1064, 1153)                                                                               
   - retrieveCodeById("HybridRetriever#rerank", depth=…) cycles depth=1↔2↔3 five times (l. 911, 923, 951, 1020, 1164)                                
   - retrieveCodeById("RetrievalResult#computeFinalScore", depth=1) 3×.
7. Retrieval banner duplicated. In safeloop runs the entire ranking block prints once for the eval pre-retrieval and again for Phase 1 (l. 181–202 ≡
   213–234).
8. fetchSelfReviewContext() returns empty silently (l. 358, 1193) — agent has no way to know why it's empty.
9. [analyzer-expand] already in context (not counted as new) (l. 803, 1200): the analyzer keeps asking for context it already has; the loop         
   converges only because of the stopOnNoNew guard. The agent never gets a feedback signal back.
10. Per-fixture timing is opaque. (16118003ms) is the only timing, no breakdown — so the 4.5h commitPlan storm is invisible until you read the body.
11. Iteration count is misleading. Both safeloop fixtures finish in "Iteration 1/5" (l. 807, 1240); maxIter=5 is dead code in this run.

  ---                                                                                                                                                 
Where to add TRACE-gated logs

The flag already exists (SafeLoopConfig.trace → config.isTrace(), with traceChat() printing prompts/responses). All suggestions below extend that
gate. I've ranked by impact-per-line of code.

Tier 1 — Catch the next runaway in seconds, not hours

These few hooks would have caught this exact failure within ~1 second.

┌─────┬───────────────────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │                 Where                 │                                            Trace log                                            │
├─────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T1  │ RefactorTools.commitPlan (and every   │ After call: [trace] tool=commitPlan args.hash=… consecSameToolArgs=N consecNoops=M              │
│     │ Apply tool)                           │ sinceLastStateChange=K — and a hard-fail at consecNoops >= 3 independent of trace, not at 200   │
├─────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T2  │ Wherever the tool dispatcher prints   │ Replace String.format("…\n  └─ […] (…)") with a single atomic write (one synchronized(out){…})  │
│     │ 🔨 Apply tool #N                      │ so streamed tokens stop bleeding into log lines                                                 │   
├─────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
│ T3  │ The streaming token printer           │ Under trace: print [trace] stream tokens=N first-token=ms total=ms model=… finishReason=… once  │   
│     │                                       │ per LLM call, instead of mid-line raw spurt                                                     │   
├─────┼───────────────────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────┤
│ T4  │ Tool dispatcher                       │ Maintain a per-iteration Map<argsHash, count>; under trace, log DUPLICATE-CALL tool=X #seen=k   │   
│     │                                       │ lastResultLen=… so loops are visible from one grep                                              │   
└─────┴───────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 2 — LLM observability (currently zero)

┌─────┬──────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │                  Where                   │                                          Trace log                                           │
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤
│ T5  │ traceChat() (SafeRefactorLoop.java:1300) │ Add tokens.in= tokens.out= latencyMs= firstTokenMs= ttftMs= model= temp= line before the     │
│     │                                          │ prompt body; this is what you actually need to read first                                    │
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T6  │ Same                                     │ Print memory.size=N messages, approxTokens=…, budgetRemaining=… so context bloat is visible  │
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T7  │ Quick-analyzer / Analyzer / Self-review  │ Tag the trace line with phase + iteration: [trace:LLM phase=refactor iter=2/5 …]             │
│     │ call sites                               │                                                                                              │   
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤
│ T8  │ RetrievalRunner & embedding caller       │ [trace] embed model=… input.chars=… latencyMs=…                                              │   
└─────┴──────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 3 — Resolver visibility (silently picking wrong anchors)

┌─────┬────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │               Where                │                                             Trace log                                              │
├─────┼────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ MethodResolver / wherever 'X' →    │ Under trace, dump the ranked candidate table: each candidate's chunkId, fanIn, fanOut,             │
│ T9  │ 'Y' (CONTAINS fallback @class      │ semSimilarityToQuery, finalScore, why-rejected. The current single-line summary hides bad picks    │
│     │ boundary, …) is printed            │ like reset() or buildEmbeddingText                                                                 │   
├─────┼────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                    │ When fallback fires for a fixture whose gold.anchor is known (eval mode), log [trace]              │   
│ T10 │ Same                               │ anchor.mismatch picked=… gold=… picked.rank=… gold.rank=… reason=fallback-fan-in — this is the     │   
│     │                                    │ single most useful line for debugging the two failing retrieval fixtures                           │
├─────┼────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T11 │ Resolver entry point               │ Add per-request memo + [trace] resolver cache=hit key='…' resolvedTo='…' ageMs=… to make the       │
│     │                                    │ 20×-per-fixture redundancy visible/fixable                                                         │   
├─────┼────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                    │ [trace] vector-fallback identifier='HybridRetriever.java' parsedAs='HybridRetriever#java'          │   
│ T12 │ Vector fallback path (l. 968)      │ resolveFailed=true → anchor='RunContext#retriever' similarity=0.49 (this is likely the wrong       │   
│     │                                    │ anchor — consider asking for class overview instead)                                               │
└─────┴────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 4 — Phase / loop diagnostics

┌─────┬───────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │         Where         │                                                    Trace log                                                    │
├─────┼───────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ T13 │ Each ━━━ Phase N      │ Print elapsed when phase ends. Current code only emits the banner                                               │
│     │ printer               │                                                                                                                 │
├─────┼───────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T14 │ End of fixture        │ One-line table per iteration: iter=1 phase=refactor llm.ms=… retrieve.ms=… verify.ms=… apply.ms=…               │   
│     │                       │ tools=fwd:7,apply:204,verify:21 staged.ops.committed=0 noops=204                                                │   
├─────┼───────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T15 │ Convergence guard (l. │ Already prints CONVERGED — no new graph nodes. Add under trace: list of IDs the analyzer asked for, which were  │
│     │  805)                 │ already-in-context, which were unresolved — the agent should see this as feedback, not just the loop            │   
├─────┼───────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ T16 │ [analyzer-expand]     │ Trace the analyzer's needs[] array verbatim before dedup so we can see whether the LLM is asking for the same   │   
│     │ block                 │ thing repeatedly                                                                                                │   
└─────┴───────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 5 — Verifier / AST-diff truth-telling

┌─────┬─────────────────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │                  Where                  │                                           Trace log                                           │
├─────┼─────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ verifyJavaSnippet vs.                   │ When both run within the same iteration and disagree, log [trace] verifier.disagreement       │
│ T17 │ getCompilationErrors mismatch           │ snippet=fail project=ok stagedOps=N — likely snippet missing imports/types from project       │
│     │                                         │ context                                                                                       │   
├─────┼─────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┤
│     │ analyzeCrossMethod (l. 1331) when it    │ Trace which code blocks were extracted, which method names parsed out of each, which          │   
│ T18 │ returns CrossMethodDiff.empty()         │ resolved, which didn't — currently the log just says "no code blocks matched" with no         │
│     │                                         │ diagnostic for why, even though the agent always emits code                                   │   
├─────┼─────────────────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┤
│ T19 │ extractMethodNameFromCode               │ Trace each block's first 80 chars + parse result so you can see the regex/parse failure mode  │
└─────┴─────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 6 — Apply-tool internal state

┌─────┬──────────────────────────────────────────┬──────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │                  Where                   │                                          Trace log                                           │
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤
│     │                                          │ Trace the staged op list before commit + the diff that will be applied. Right now you only   │
│ T20 │ RefactorTools.commitPlan(stagedOps)      │ see commitPlan(N staged op(s)) and an [ok]/[unsafe]/[noop] outcome. When something is        │
│     │                                          │ [unsafe] (426 chars) (l. 1003) you can't see why without re-running                          │   
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤
│ T21 │ Same                                     │ When [unsafe] fires, trace the safety reasons (compile error? FQN drift?                     │   
│     │                                          │ caller-not-updated?)                                                                         │   
├─────┼──────────────────────────────────────────┼──────────────────────────────────────────────────────────────────────────────────────────────┤
│ T22 │ discardDraft / stageReplaceMethod /      │ Trace before.checksum → after.checksum so the rename-then-discard-then-restage churn (l.     │   
│     │ stageRenameMethod                        │ 1014–1056) becomes visibly characterised                                                     │
└─────┴──────────────────────────────────────────┴──────────────────────────────────────────────────────────────────────────────────────────────┘

Tier 7 — Retrieval cache / dedup

┌─────┬───────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────┐
│  #  │           Where           │                                       Trace log                                       │
├─────┼───────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ T23 │ retrieveCodeById entry    │ [trace] retrieveCodeById anchor=… depth=… cache=hit/miss prevCallAtTool=#… argsHash=… │
├─────┼───────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤
│ T24 │ getClassOverview          │ Same                                                                                  │                         
├─────┼───────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────┤                         
│ T25 │ At end-of-iteration table │ unique.tool.calls=K duplicate.tool.calls=L cache.hit.rate=…                           │                         
└─────┴───────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────┘

Tier 8 — Eval-harness self-checks

┌─────┬───────────────────────────┬─────────────────────────────────────────────────────────────────────────────────────────────────────────────┐   
│  #  │           Where           │                                                  Trace log                                                  │
├─────┼───────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ T26 │ EvalMain.runLive (per     │ When gold.anchor is set and retrieved anchor differs, emit one trace summary even outside debug mode —      │
│     │ fixture)                  │ currently you have to scroll through the ranking                                                            │
├─────┼───────────────────────────┼─────────────────────────────────────────────────────────────────────────────────────────────────────────────┤   
│ T27 │ Per fixture epilogue      │ Wall-clock split: retrieval.ms=… safeloop.ms=… verify.ms=… reporters.ms=…                                   │
└─────┴───────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                            
---                                                                                                                                                 
Suggested implementation order

If I were tackling this:

1. T1 + T2 + T3 — fix the runaway loop and the streaming/log interleaving. These are bugs, not just observability gaps. (~1 hour, prevents next 4.5h
   waste.)
2. T9 + T10 — instrument the resolver. Two of the five fixtures fail solely because of it.
3. T5 + T13 + T14 — LLM token/latency + phase timings + per-iteration table. This converts the opaque (16118003ms) into actionable signal.
4. T18 + T19 — make the AST-diff matcher's silence audible.
5. The rest as you encounter the next mystery.

IMPROVE FIXTURES! LLM Always says that splitAtBoundaries is already implemented.

Want me to implement any of these? I'd start with T1–T3 since they prevent another multi-hour run from being wasted on a livelocked agent.

✻ Cooked for 3m 11s                        