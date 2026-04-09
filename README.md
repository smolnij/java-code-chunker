# Java Code Chunker for LM-Studio

Hierarchical Java code chunking and indexing tool that produces **method-level chunks** with **call graph edges** for **graph-aware LLM retrieval**.

## Features

| Feature | Implementation |
|---|---|
| **Parser (not regex)** | JavaParser 3.26 AST parsing |
| **Deduplicate boilerplate** | Detects getters/setters/DTOs by AST structure + Lombok annotations |
| **Chunk per method** | Each `MethodDeclaration` → one `CodeChunk`, with class signature + fields as context |
| **Call graph extraction** | Forward + reverse edges with fully qualified method names |
| **FQ resolution** | JavaParser Symbol Solver (`JavaParserTypeSolver` + `ReflectionTypeSolver`) |
| **Token-aware splitting** | jtokkit `cl100k_base` tokenizer; splits large methods at line boundaries |
| **Graph-aware retrieval** | `GraphIndex` with `getContextExpanded(method, depth)` for N-hop context |
| **Hierarchical index** | Package → Class → Method node hierarchy |

## Project Structure

```
java-code-chunker/
├── pom.xml
├── README.md
└── src/main/java/com/example/chunker/
    ├── ChunkerMain.java            # CLI entry point
    ├── JavaCodeChunker.java        # Main orchestrator (parse → chunk → graph → filter)
    ├── callgraph/
    │   └── CallGraphExtractor.java # FQ call graph with Symbol Solver
    ├── filter/
    │   └── BoilerplateDetector.java# Skip getters/setters/DTOs
    ├── index/
    │   └── GraphIndex.java         # Graph-aware retrieval index
    ├── model/
    │   └── CodeChunk.java          # Chunk data model + prompt formatter
    └── tokenizer/
        └── TokenCounter.java       # Token counting + splitting (cl100k_base)
```

## Output Format

Each chunk is formatted as:

```
Class: MainPhase
Fully Qualified: com.xxx.xx.MainPhase
File: plugins/src/main/java/.../MainPhase.java
Class Signature: public class MainPhase
Class Annotations: @RequiredArgsConstructor
Fields:
  - private final ContractService contractService;
  - private final SegmentationService segmentationService;

Method:
  - public EnrichedRecord process(CreditRecord creditRecord)
  Lines: 19-40
  Tokens: 187

Calls:
  - com.xxx...ContractService#computeMaxContracts(CreditRecord)
  - com.xxx...ContractService#computeContractsToUse(CreditRecord)
  - com.xxx...SService#computeSRecord(...)
  - this.isToFilter(...)
  - this.buildStep2Output(...)

Called By:
  - com.xxx...MainPhase#execute(...)

Code:
public EnrichedRecord process(...) {
    ...
}
```

## Output Files

| File | Purpose |
|---|---|
| `chunks.json` | Full structured data for embedding / vector DB |
| `graph.json` | Hierarchical + call graph structure for graph DB |
| `chunks_readable.txt` | Human/LLM-readable prompt-formatted chunks |

## Build & Run

```powershell
# Build
cd C:\dev\src\java-code-chunker
mvn clean package -q

# Run 
java -jar target\java-code-chunker-1.0-SNAPSHOT.jar

# Run with custom arguments
java -jar target\java-code-chunker-1.0-SNAPSHOT.jar "C:\path\to\repo" "output-dir" 512
```

### Arguments

| Arg | Default               | Description |
|---|-----------------------|---|
| `repoRoot` | `C:/dev/src/reporoot` | Path to Java repository |
| `outputDir` | `chunker-output`      | Where to write output files |
| `maxTokens` | `512`                 | Max tokens per chunk before splitting |

## Using with LM-Studio

### Direct Context Injection
Feed `chunks_readable.txt` as system context to your LM-Studio model.

### RAG Pipeline
1. **Embed** each chunk from `chunks.json` (use `toPromptFormat()` text)
2. **On query**, retrieve top-K chunks by similarity
3. **Expand** each hit using `GraphIndex.getContextExpanded(methodFqn, depth=1)` to pull in callers/callees
4. **Concatenate** and send to LM-Studio as context

### Graph DB Integration
Load `graph.json` into Neo4j or NetworkX for sophisticated graph traversal queries.

## Tuning

- **`maxTokensPerChunk`**: Set to match your model's effective context window. 512 is good for embedding, 1024+ for direct prompting.
- **Source roots**: Edit `ChunkerMain.java` to add/remove source directories.
- **Boilerplate detection**: Customize `BoilerplateDetector.java` to adjust what gets filtered.

## Requirements

- Java 17+
- Maven 3.8+

