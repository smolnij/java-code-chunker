# Agentic Refactoring System — User Guide

**Graph-aware, safety-gated, LLM-driven code refactoring** with support for
single-machine and multi-machine (distributed) deployments.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Prerequisites & Infrastructure](#prerequisites--infrastructure)
  - [1. Neo4j Graph Database](#1-neo4j-graph-database)
  - [2. LM-Studio (LLM Server)](#2-lm-studio-llm-server)
  - [3. Embedding Model](#3-embedding-model)
  - [4. Java & Maven](#4-java--maven)
- [Step 0 — Build the Project](#step-0--build-the-project)
- [Step 1 — Index Your Codebase](#step-1--index-your-codebase)
- [Entry Points (Modes of Operation)](#entry-points-modes-of-operation)
  - [Mode 1: Retrieval Only (`RetrievalMain`)](#mode-1-retrieval-only)
  - [Mode 2: Pipeline Refactoring (`RefactorMain`)](#mode-2-pipeline-refactoring)
  - [Mode 3: Agentic Refactoring (`AgentRefactorMain`)](#mode-3-agentic-refactoring)
  - [Mode 4: Worker/Judge Loop (`RalphMain`)](#mode-4-workerjudge-loop)
  - [Mode 5: Safe Refactoring Loop — Single Machine (`SafeLoopMain`)](#mode-5-safe-refactoring-loop--single-machine)
  - [Mode 6: Distributed Safe Refactoring — Multi-Machine (`DistributedSafeLoopMain`)](#mode-6-distributed-safe-refactoring--multi-machine)
- [Non-Distributed Deployment (Single Machine)](#non-distributed-deployment-single-machine)
- [Distributed Deployment (Multi-Machine)](#distributed-deployment-multi-machine)
  - [Machine Layout](#machine-layout)
  - [Network Requirements](#network-requirements)
  - [Deploying the Generator (REFACTOR_MACHINE)](#deploying-the-generator-refactor_machine)
  - [Deploying the Planner–Analyzer (S_ANALYZE_MACHINE)](#deploying-the-planneranalyzer-s_analyze_machine)
  - [Running the Distributed Loop](#running-the-distributed-loop)
- [Environment Variable Reference](#environment-variable-reference)
- [LLM Model Requirements](#llm-model-requirements)
- [How the Safety Loop Works](#how-the-safety-loop-works)
- [Troubleshooting](#troubleshooting)

---

## Architecture Overview

The system progressively evolves through six modes, from simple retrieval to
fully autonomous planner-driven distributed refactoring:

```
Level 1: Retrieval Only        → RetrievalMain
Level 2: Pipeline Refactoring  → RefactorMain
Level 3: Agentic Refactoring   → AgentRefactorMain / RefactorMain --agent
Level 4: Worker/Judge Loop     → RalphMain
Level 5: Safe Agentic Loop     → SafeLoopMain          (single machine)
Level 6: Distributed Planner   → DistributedSafeLoopMain (multi-machine)
```

All modes share the same underlying infrastructure:

```
  ┌───────────────────────────────┐
  │     Your Java Codebase        │
  └──────────┬────────────────────┘
             │ ChunkerMain (Step 1)
             ▼
  ┌───────────────────────────────┐
  │  Neo4j  (code graph + vectors)│
  └──────────┬────────────────────┘
             │
  ┌──────────┴──────────────────────────────────────────┐
  │                 Retrieval Layer                       │
  │  HybridRetriever = Graph traversal + Vector search   │
  │  Neo4jGraphReader = Call graph / subgraph expansion   │
  │  LmStudioEmbeddingService = Embedding vectors        │
  └──────────┬──────────────────────────────────────────┘
             │
  ┌──────────┴──────────────────────────────────────────┐
  │           Refactoring / Analysis Layer                │
  │  LLM (via LM-Studio OpenAI-compatible API)           │
  │  LangChain4j tool calling (agent / planner modes)    │
  └─────────────────────────────────────────────────────┘
```

---

## Prerequisites & Infrastructure

### 1. Neo4j Graph Database

The code graph (methods, classes, packages, call edges, embeddings) is stored
in Neo4j. You need a running instance.

```powershell
# Option A: Docker (recommended)
docker run -d --name neo4j `
  -p 7474:7474 -p 7687:7687 `
  -e NEO4J_AUTH=neo4j/your-password `
  -e NEO4J_PLUGINS='["apoc"]' `
  neo4j:5

# Option B: Neo4j Desktop
# Download from https://neo4j.com/download/ and create a local database.
```

Verify connectivity: open `http://localhost:7474` in a browser.

### 2. LM-Studio (LLM Server)

Download [LM-Studio](https://lmstudio.ai/) and load a model. The server
exposes an OpenAI-compatible API at `http://localhost:1234/v1/`.

**For non-distributed mode**, a single LM-Studio instance serves all roles
(refactorer + analyzer).

**For distributed mode**, you run two LM-Studio instances on separate machines
(see [Distributed Deployment](#distributed-deployment-multi-machine)).

### 3. Embedding Model

LM-Studio can also serve an embedding model. Load an embedding model such as
`nomic-embed-text-v1.5` alongside your chat model. The embedding endpoint is:

```
http://localhost:1234/v1/embeddings
```

### 4. Java & Maven

- **Java 17+** (required)
- **Maven 3.8+** (for building)

---

## Step 0 — Build the Project

```powershell
cd C:\dev\src\java-code-chunker
mvn clean package -q
```

This produces a fat JAR at `target\java-code-chunker-1.0-SNAPSHOT.jar`.

All entry points are invoked via:

```powershell
java -cp target\java-code-chunker-1.0-SNAPSHOT.jar <MainClass> [args...]
```

---

## Step 1 — Index Your Codebase

Before any refactoring mode can work, you must parse your Java repository and
load the code graph into Neo4j.

```powershell
# Set Neo4j credentials
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"
$env:NEO4J_CLEAN = "true"          # Wipe existing data (first run)
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

# Index your repository
java -jar target\java-code-chunker-1.0-SNAPSHOT.jar "C:\path\to\your\java\repo" "chunker-output" 512
```

**Entry point:** `com.smolnij.chunker.ChunkerMain`

This does:
1. Parses all Java files using JavaParser with Symbol Solver
2. Extracts method-level chunks with call graph edges
3. Writes `chunks.json`, `graph.json`, `chunks_readable.txt` to the output directory
4. Persists the graph to Neo4j (packages → classes → methods + CALLS edges)
5. Computes embeddings and stores them as vector properties in Neo4j

---

## Entry Points (Modes of Operation)

### Mode 1: Retrieval Only

**Class:** `retrieval.com.smolnij.chunker.RetrievalMain`

Runs hybrid Graph-RAG retrieval and outputs LLM-ready context. No refactoring.

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  retrieval.com.smolnij.chunker.RetrievalMain `
  "Find all methods related to user creation" `
  --output context.txt --debug
```

| Flag | Description |
|---|---|
| `--output / -o <file>` | Write context to file |
| `--debug` | Print full ranking info |

---

### Mode 2: Pipeline Refactoring

**Class:** `refactor.com.smolnij.chunker.RefactorMain`

Fixed pipeline: Retrieve → Analyze → Refactor → Safety check. The LLM does
not call tools; context is pre-fetched.

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  refactor.com.smolnij.chunker.RefactorMain `
  "Refactor createUser to use async" `
  --output result.txt
```

| Flag | Description |
|---|---|
| `--output / -o <file>` | Write result to file |
| `--no-stream` | Disable SSE streaming |
| `--agent` | Switch to agentic mode (same as Mode 3) |
| `--debug` | Print raw LLM responses |

---

### Mode 3: Agentic Refactoring

**Class:** `refactor.com.smolnij.chunker.AgentRefactorMain`

The LLM autonomously decides when to call retrieval tools using LangChain4j
function calling. It reasons about what context it needs.

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  refactor.com.smolnij.chunker.AgentRefactorMain `
  "Refactor createUser to use CompletableFuture" `
  --output result.txt --debug
```

**Tools available to the LLM:**
- `retrieveCode(query, maxResults)` — hybrid graph + vector search
- `retrieveCodeById(methodId, depth)` — targeted graph expansion
- `getMethodCallers(methodId)` — impact analysis
- `getMethodCallees(methodId)` — dependency analysis

**⚠ Requires a tool-calling-capable model** (Qwen 2.5, Mistral, Llama 3.1+).

---

### Mode 4: Worker/Judge Loop

**Class:** `ralph.com.smolnij.chunker.RalphMain`

A "Ralph Wiggum" loop: a naive worker attempts refactoring, a strict judge
evaluates it, and the worker retries with judge feedback until approved.

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  ralph.com.smolnij.chunker.RalphMain `
  "Refactor createUser to async" `
  --output result.txt
```

| Environment Variable | Description | Default |
|---|---|---|
| `RALPH_CHAT_URL` | LLM endpoint | `http://localhost:1234/v1/chat/completions` |
| `RALPH_WORKER_MODEL` | Worker model name | (loaded model) |
| `RALPH_JUDGE_MODEL` | Judge model name | (same as worker) |
| `RALPH_WORKER_TEMP` | Worker temperature | `0.3` |
| `RALPH_JUDGE_TEMP` | Judge temperature | `0.1` |
| `RALPH_MAX_ITERATIONS` | Max retries | `5` |
| `RALPH_MAX_CHUNKS` | Context chunks | `6` |

---

### Mode 5: Safe Refactoring Loop — Single Machine

**Class:** `safeloop.com.smolnij.chunker.SafeLoopMain`

The full self-improving safe refactoring loop. Combines agentic tool-calling
with a confidence-gated safety analyzer. Both LLMs run on the same machine.

```
  User query
      │
      ▼
  Phase 1: PLANNING — initial retrieval + target identification
      │
      ▼
  Phase 2: GRAPH PRE-FETCH — ensure caller/callee coverage
      │
      ▼
  ┌─────────────────────────────────────────────────────┐
  │  LOOP (max N iterations)                             │
  │                                                      │
  │  Phase 3: REFACTOR  ← Agent produces code            │
  │      │                                               │
  │      ▼                                               │
  │  Phase 4: VALIDATE  ← Analyzer checks safety         │
  │      │                                               │
  │      ▼                                               │
  │  Phase 5: DECIDE                                     │
  │    SAFE      → return ✓                              │
  │    UNSAFE    → expand graph → agent refines → loop   │
  │    CONVERGED → return (no new graph nodes)            │
  │    STAGNANT  → return (same risks repeated)           │
  └─────────────────────────────────────────────────────┘
```

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

# Optional: separate models for refactorer vs analyzer
$env:SAFELOOP_REFACTOR_MODEL = "qwen2.5-coder-32b"
$env:SAFELOOP_ANALYZER_MODEL = "qwen2.5-coder-32b"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  safeloop.com.smolnij.chunker.SafeLoopMain `
  "Refactor createUser to async" `
  --threshold 0.85 --max-iterations 5 --output result.txt
```

| Flag | Description |
|---|---|
| `--output / -o <file>` | Write result to file |
| `--max-iterations <N>` | Override max loop iterations (default: 5) |
| `--threshold <0.0-1.0>` | Override safety threshold (default: 0.9) |
| `--no-stream` | Disable SSE streaming |
| `--debug` | Print full verdict history and raw responses |

**Exit codes:** `0` = safe, `1` = unsafe or error.

**⚠ Requires a tool-calling-capable model** for the refactorer agent.

#### SafeLoop Environment Variables

| Variable | System Property | Default | Description |
|---|---|---|---|
| `SAFELOOP_CHAT_URL` | `safeloop.chatUrl` | `http://localhost:1234/v1/chat/completions` | LLM endpoint (shared) |
| `SAFELOOP_REFACTOR_MODEL` | `safeloop.refactorModel` | (loaded model) | Refactorer model |
| `SAFELOOP_ANALYZER_MODEL` | `safeloop.analyzerModel` | (loaded model) | Analyzer model |
| `SAFELOOP_REFACTOR_TEMP` | `safeloop.refactorTemp` | `0.3` | Refactorer temperature (creative) |
| `SAFELOOP_ANALYZER_TEMP` | `safeloop.analyzerTemp` | `0.1` | Analyzer temperature (precise) |
| `SAFELOOP_SAFETY_THRESHOLD` | `safeloop.safetyThreshold` | `0.9` | Min confidence to declare SAFE |
| `SAFELOOP_MAX_ITERATIONS` | `safeloop.maxIterations` | `5` | Max refine iterations |
| `SAFELOOP_MAX_CHUNKS` | `safeloop.maxChunks` | `8` | Context chunks per retrieval |
| `SAFELOOP_CHAT_MEMORY_SIZE` | `safeloop.chatMemorySize` | `30` | Agent sliding memory window |
| `SAFELOOP_MAX_TOOL_CALLS` | `safeloop.maxToolCalls` | `15` | Safety cap on tool invocations |
| `SAFELOOP_MIN_CALLER_DEPTH` | `safeloop.minCallerDepth` | `1` | Min hops of callers to retrieve |
| `SAFELOOP_MIN_CALLEE_DEPTH` | `safeloop.minCalleeDepth` | `1` | Min hops of callees to retrieve |

---

### Mode 6: Distributed Safe Refactoring — Multi-Machine

**Class:** `distributed.safeloop.com.smolnij.chunker.DistributedSafeLoopMain`

Planner-driven distributed refactoring across **two separate LLM machines**.
The Planner–Analyzer has full authority and drives everything autonomously
using LangChain4j tool calling.

```
  ┌──────────────────────────┐        ┌──────────────────────────┐
  │  REFACTOR_MACHINE        │        │  S_ANALYZE_MACHINE       │
  │  http://REFACTORM:1234   │        │  http://SANALYZEM:1234   │
  │                          │        │                          │
  │  🟦 Generator LLM        │        │  🟩 Planner–Analyzer     │
  │  Role: Senior Java Eng.  │        │  Role: Senior Architect  │
  │  Temp: 0.3 (creative)    │        │  + Static Analyzer       │
  │  Tool calling: NO        │        │  Temp: 0.1 (precise)     │
  │  Decision authority: NO  │        │  Tool calling: YES       │
  │                          │        │  Decision authority: FULL │
  └──────────┬───────────────┘        └──────────┬───────────────┘
             │                                    │
             └──────── Planner tool calls ────────┘
```

**Planner tools (called autonomously by the Planner LLM):**

| Tool | Purpose | Runs on |
|---|---|---|
| `retrieveCode(query, depth)` | Hybrid graph + vector search | Local (Neo4j) |
| `retrieveCodeById(id, depth)` | Targeted graph expansion | Local (Neo4j) |
| `getMethodCallers(id)` | Impact analysis | Local (Neo4j) |
| `getMethodCallees(id)` | Dependency analysis | Local (Neo4j) |
| `refactorCode(prompt)` | Delegate refactoring to Generator | Remote (REFACTOR_MACHINE) |

```powershell
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "your-password"
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

# Machine endpoints
$env:REFACTOR_MACHINE_URL = "http://REFACTORM:1234/v1/chat/completions"
$env:ANALYZER_MACHINE_URL = "http://SANALYZEM:1234/v1/chat/completions"

# Optional: model names
$env:REFACTOR_MACHINE_MODEL = "qwen2.5-coder-32b"
$env:ANALYZER_MACHINE_MODEL = "qwen2.5-coder-32b"

java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  distributed.safeloop.com.smolnij.chunker.DistributedSafeLoopMain `
  "Refactor createUser to async" `
  --threshold 0.9 --output result.txt --debug
```

| Flag | Description |
|---|---|
| `--output / -o <file>` | Write result to file |
| `--json-log <file>` | Write full JSON protocol log |
| `--max-iterations <N>` | Override max iterations |
| `--threshold <0.0-1.0>` | Override safety threshold |
| `--no-stream` | Disable SSE streaming |
| `--debug` | Print planner stats & raw responses |

---

## Non-Distributed Deployment (Single Machine)

Everything runs on one machine. This is the simplest setup.

### What you need running:

| Component | Port | Purpose |
|---|---|---|
| **Neo4j** | `7687` (bolt), `7474` (http) | Code graph + vector index |
| **LM-Studio** | `1234` | Chat LLM + Embedding model |

### Steps:

```powershell
# 1. Start Neo4j
docker run -d --name neo4j -p 7474:7474 -p 7687:7687 `
  -e NEO4J_AUTH=neo4j/password neo4j:5

# 2. Start LM-Studio
#    Load a chat model (e.g. Qwen 2.5 Coder 32B)
#    Load an embedding model (e.g. nomic-embed-text-v1.5)
#    Start the local server on port 1234

# 3. Build
cd C:\dev\src\java-code-chunker
mvn clean package -q

# 4. Index your codebase
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "password"
$env:NEO4J_CLEAN = "true"
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

java -jar target\java-code-chunker-1.0-SNAPSHOT.jar "C:\your\repo" "chunker-output"

# 5. Run the safe refactoring loop
java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  safeloop.com.smolnij.chunker.SafeLoopMain `
  "Refactor createUser to use CompletableFuture" `
  --threshold 0.85
```

---

## Distributed Deployment (Multi-Machine)

### Machine Layout

| Machine | Hostname | Role | LM-Studio model | Tool calling |
|---|---|---|---|---|
| **REFACTOR_MACHINE** | `REFACTORM` | 🟦 Generator — writes code | Creative coder (temp=0.3) | **NO** |
| **S_ANALYZE_MACHINE** | `SANALYZEM` | 🟩 Planner–Analyzer — controls everything | Precise architect (temp=0.1) | **YES** |
| **Orchestrator** | (your workstation) | Runs the Java process, Neo4j, embeddings | N/A | N/A |

### Network Requirements

The **Orchestrator** machine must be able to reach:
- Neo4j on `bolt://localhost:7687` (or remote)
- REFACTOR_MACHINE on `http://REFACTORM:1234`
- S_ANALYZE_MACHINE on `http://SANALYZEM:1234`

> Both LM-Studio instances only need to accept incoming HTTP requests from the
> Orchestrator. They do **not** need to talk to each other or to Neo4j.

### Deploying the Generator (REFACTOR_MACHINE)

1. Install [LM-Studio](https://lmstudio.ai/) on the Generator machine
2. Load a code-generation model (e.g. Qwen 2.5 Coder 32B, CodeLlama 34B)
3. Start the local server:
   - Port: `1234` (default)
   - Enable: *Local Server → Start*
4. **No tool-calling support required** — the Generator only receives plain chat
   prompts from the Planner

```
Generator endpoint: http://REFACTORM:1234/v1/chat/completions
```

### Deploying the Planner–Analyzer (S_ANALYZE_MACHINE)

1. Install [LM-Studio](https://lmstudio.ai/) on the Analyzer machine
2. Load a model with **tool-calling / function-calling support**:
   - ✅ Qwen 2.5 (any size ≥ 7B)
   - ✅ Mistral / Mixtral
   - ✅ Llama 3.1+ (8B, 70B)
   - ❌ Models without tool-use will fail silently
3. Start the local server on port `1234`

```
Planner endpoint: http://SANALYZEM:1234/v1/chat/completions
```

### Running the Distributed Loop

On the **Orchestrator** machine (your workstation):

```powershell
# --- Infrastructure (must already be running) ---
# Neo4j: bolt://localhost:7687
# LM-Studio on REFACTORM: http://REFACTORM:1234
# LM-Studio on SANALYZEM: http://SANALYZEM:1234

# --- Environment ---
$env:NEO4J_URI = "bolt://localhost:7687"
$env:NEO4J_PASSWORD = "password"
$env:EMBEDDING_URL = "http://localhost:1234/v1/embeddings"

$env:REFACTOR_MACHINE_URL = "http://REFACTORM:1234/v1/chat/completions"
$env:REFACTOR_MACHINE_MODEL = "qwen2.5-coder-32b"
$env:REFACTOR_MACHINE_TEMP = "0.3"

$env:ANALYZER_MACHINE_URL = "http://SANALYZEM:1234/v1/chat/completions"
$env:ANALYZER_MACHINE_MODEL = "qwen2.5-coder-32b"
$env:ANALYZER_MACHINE_TEMP = "0.1"

$env:DIST_SAFETY_THRESHOLD = "0.9"
$env:DIST_MAX_PLANNER_STEPS = "8"

# --- Run ---
java -cp target\java-code-chunker-1.0-SNAPSHOT.jar `
  distributed.safeloop.com.smolnij.chunker.DistributedSafeLoopMain `
  "Refactor createUser to async" `
  --threshold 0.9 --output result.txt --debug
```

---

## Environment Variable Reference

All environment variables can also be set as Java system properties
(`-Dproperty=value`). System properties take precedence.

### Core (all modes)

| Variable | System Property | Default | Description |
|---|---|---|---|
| `NEO4J_URI` | `neo4j.uri` | — | Neo4j bolt URI (required) |
| `NEO4J_USER` | `neo4j.user` | `neo4j` | Neo4j username |
| `NEO4J_PASSWORD` | `neo4j.password` | — | Neo4j password (required) |
| `EMBEDDING_URL` | `embedding.url` | `http://localhost:1234/v1/embeddings` | Embedding endpoint |
| `EMBEDDING_MODEL` | `embedding.model` | `text-embedding-nomic-embed-text-v1.5` | Embedding model |

### Distributed-mode specific

| Variable | System Property | Default | Description |
|---|---|---|---|
| `REFACTOR_MACHINE_URL` | `dist.refactorUrl` | `http://REFACTORM:1234/v1/chat/completions` | Generator endpoint |
| `REFACTOR_MACHINE_MODEL` | `dist.refactorModel` | (loaded model) | Generator model name |
| `REFACTOR_MACHINE_TEMP` | `dist.refactorTemp` | `0.3` | Generator temperature |
| `ANALYZER_MACHINE_URL` | `dist.analyzerUrl` | `http://SANALYZEM:1234/v1/chat/completions` | Planner endpoint |
| `ANALYZER_MACHINE_MODEL` | `dist.analyzerModel` | (loaded model) | Planner model name |
| `ANALYZER_MACHINE_TEMP` | `dist.analyzerTemp` | `0.1` | Planner temperature |
| `DIST_SAFETY_THRESHOLD` | `dist.safetyThreshold` | `0.9` | Min confidence for SAFE |
| `DIST_MAX_ITERATIONS` | `dist.maxIterations` | `5` | Max loop iterations |
| `DIST_MAX_PLANNER_STEPS` | `dist.maxPlannerSteps` | `8` | Max planner tool calls |
| `DIST_MAX_CHUNKS_PER_RETRIEVAL` | `dist.maxChunksPerRetrieval` | `10` | Chunks per retrieval call |
| `DIST_MAX_RETRIEVAL_DEPTH` | `dist.maxRetrievalDepth` | `2` | Max graph hops per retrieval |
| `DIST_MAX_CHUNKS` | `dist.maxChunks` | `8` | Context chunks |
| `DIST_CHAT_MEMORY_SIZE` | `dist.chatMemorySize` | `30` | Agent memory window |
| `DIST_MAX_TOOL_CALLS` | `dist.maxToolCalls` | `15` | Tool call cap |
| `DIST_STREAM` | `dist.stream` | `true` | SSE streaming |

---

## LLM Model Requirements

| Role | Tool calling needed? | Recommended temp | Recommended models |
|---|---|---|---|
| **Pipeline refactorer** (Mode 2) | ❌ No | 0.1 | Any code model |
| **Agentic refactorer** (Mode 3) | ✅ Yes | 0.1 | Qwen 2.5, Mistral, Llama 3.1+ |
| **SafeLoop refactorer** (Mode 5) | ✅ Yes | 0.3 | Qwen 2.5, Mistral, Llama 3.1+ |
| **SafeLoop analyzer** (Mode 5) | ❌ No | 0.1 | Any code model |
| **Distributed Generator** (Mode 6) | ❌ No | 0.3 | Any code model |
| **Distributed Planner** (Mode 6) | ✅ Yes | 0.1 | Qwen 2.5, Mistral, Llama 3.1+ |

> **Tip:** For single-machine setups, you can use the same model for all roles.
> Just make sure it supports tool calling if you're using Modes 3, 5, or 6.

---

## How the Safety Loop Works

### Non-Distributed (SafeLoopMain)

```
┌───────────┐    ┌──────────────┐    ┌───────────────┐
│ SafeLoop  │───▶│ RefactorAgent│───▶│ LM-Studio     │
│ Tools     │    │ (LangChain4j)│    │ (local:1234)  │
│ (graph    │    │ + @Tool      │    │               │
│  mgmt)    │    │ methods      │    │ Refactorer    │
└───────────┘    └──────────────┘    │ temp=0.3      │
      │                              └───────────────┘
      │          ┌──────────────┐    ┌───────────────┐
      └─────────▶│ ChatService  │───▶│ LM-Studio     │
                 │ (plain chat) │    │ (local:1234)  │
                 │              │    │               │
                 │ Analyzer     │    │ Analyzer      │
                 │ prompt       │    │ temp=0.1      │
                 └──────────────┘    └───────────────┘
```

1. **SafeLoopTools** pre-fetches callers + callees from Neo4j
2. **RefactorAgent** (LangChain4j) proposes a refactoring (may call tools autonomously)
3. **Analyzer** (separate ChatService, no tools) evaluates safety → confidence + risks
4. If unsafe → expand the graph → inject new context → agent refines → loop
5. Stops when: SAFE (≥ threshold), CONVERGED (no new nodes), STAGNANT (same risks), or MAX_ITERATIONS

### Distributed (DistributedSafeLoopMain)

```
  Orchestrator (your machine)
       │
       ▼
  PlannerAgent.plan(query)
       │
       ├──▶ 🟩 S_ANALYZE_MACHINE (Planner–Analyzer)
       │        LangChain4j AI Service with tools:
       │        │
       │        ├── retrieveCode()      → local Neo4j
       │        ├── retrieveCodeById()  → local Neo4j
       │        ├── getMethodCallers()  → local Neo4j
       │        ├── getMethodCallees()  → local Neo4j
       │        └── refactorCode()      → remote 🟦 REFACTOR_MACHINE
       │
       │    Planner autonomously loops:
       │    1. Plan → 2. Retrieve → 3. Refactor → 4. Validate → 5. Iterate
       │    Until confidence ≥ 0.9 or max steps reached
       │
       ▼
  Parse final JSON verdict → SafeLoopResult
```

The key difference: the **Planner drives everything** in a single LangChain4j
call. The orchestrator just invokes `plannerAgent.plan(query)` and waits. The
Planner autonomously decides what context to retrieve, when to delegate
refactoring to the Generator, and when to stop.

---

## Troubleshooting

### "Method not found" errors
- Make sure you indexed your codebase first (`ChunkerMain`)
- Check that the method name matches what's in Neo4j (use `http://localhost:7474` to browse)

### Tool calling not working
- Ensure your LM-Studio model supports function/tool calling
- Qwen 2.5, Mistral, Llama 3.1+ are recommended
- Check the LM-Studio server console for errors

### "Empty analyzer response"
- The analyzer model may be too small or the context too large
- Try increasing `maxTokens` or reducing `maxChunks`

### Connection refused to remote machines
- Ensure LM-Studio is running and the server is started on both machines
- Check firewall rules — port `1234` must be open
- Verify hostnames resolve correctly (try `ping REFACTORM`)

### Loop never reaches SAFE
- Lower the safety threshold: `--threshold 0.8`
- Increase max iterations: `--max-iterations 8`
- Increase context: `DIST_MAX_CHUNKS_PER_RETRIEVAL=15`
- Check that enough code is indexed in Neo4j

### Neo4j "vector index not found"
- Ensure `EMBEDDING_URL` was set when running `ChunkerMain`
- Re-run indexing with `NEO4J_CLEAN=true` and `EMBEDDING_URL` set

---

## Quick Reference — Entry Point Summary

| Class | Mode | Machines | Tool calling? |
|---|---|---|---|
| `ChunkerMain` | Index codebase → Neo4j | 1 | N/A |
| `RetrievalMain` | Retrieval only | 1 | No |
| `RefactorMain` | Pipeline refactoring | 1 | No (or `--agent`) |
| `AgentRefactorMain` | Agentic refactoring | 1 | Yes |
| `RalphMain` | Worker/Judge loop | 1 | No |
| `SafeLoopMain` | Safe agentic loop | 1 | Yes (refactorer) |
| `DistributedSafeLoopMain` | Distributed planner loop | 2+ | Yes (planner) |

All classes are in the `com.example.chunker` package hierarchy.

