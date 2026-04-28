package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.apply.ApplyTools;
import com.smolnij.chunker.apply.GraphReindexer;
import com.smolnij.chunker.apply.verify.ClasspathResolver;
import com.smolnij.chunker.apply.verify.CompilationRequest;
import com.smolnij.chunker.apply.verify.CompilationVerifier;
import com.smolnij.chunker.apply.verify.JavacVerifier;
import com.smolnij.chunker.apply.verify.LayeredCompilationVerifier;
import com.smolnij.chunker.apply.verify.MavenVerifier;
import com.smolnij.chunker.apply.verify.VerifyTools;
import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.refactor.RefactorAgent;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Holds a fully wired {@link SafeRefactorLoop} and its analyzer {@link ChatService},
 * built once from an existing {@link Neo4jGraphReader}, {@link HybridRetriever},
 * and {@link SafeLoopConfig}.
 *
 * <p>Use via try-with-resources so the analyzer chat service is closed after
 * {@link SafeRefactorLoop#run(String)} returns. The reader and retriever are not
 * owned by this bundle — the caller retains their lifecycle.
 */
public final class SafeLoopBundle implements AutoCloseable {

    private final SafeRefactorLoop loop;
    private final ChatService analyzerChat;

    private SafeLoopBundle(SafeRefactorLoop loop, ChatService analyzerChat) {
        this.loop = loop;
        this.analyzerChat = analyzerChat;
    }

    public static SafeLoopBundle build(Neo4jGraphReader reader,
                                       HybridRetriever retriever,
                                       SafeLoopConfig config) {
        return build(reader, retriever, config, null);
    }

    /**
     * @param reindexer optional Neo4j delta re-indexer. When non-null, the agent's
     *                  {@code commitPlan} tool and the prose-extracted apply
     *                  fallback both refresh Neo4j after writing files so subsequent
     *                  retrievals see the updated code (and newly created classes).
     */
    public static SafeLoopBundle build(Neo4jGraphReader reader,
                                       HybridRetriever retriever,
                                       SafeLoopConfig config,
                                       GraphReindexer reindexer) {
        // Pull verify-related fields from env/sysprops without touching the LLM
        // endpoint config, which the SafeLoopConfig already owns.
        RefactorConfig envDefaults = RefactorConfig.fromEnvironment();
        RefactorConfig refactorConfig = new RefactorConfig()
                .withChatUrl(config.getChatUrl())
                .withChatModel(config.getRefactorModel())
                .withTemperature(config.getRefactorTemperature())
                .withTopP(config.getTopP())
                .withMaxTokens(config.getMaxTokens())
                .withMaxChunks(config.getMaxChunks())
                .withAgentMode(true)
                .withMaxToolCalls(config.getMaxToolCalls())
                .withChatMemorySize(config.getChatMemorySize())
                .withStructuredOutput(config.getStructuredOutput())
                .withRequireCompile(envDefaults.isRequireCompile())
                .withVerifyMode(envDefaults.getVerifyMode())
                .withVerifyMaxErrors(envDefaults.getVerifyMaxErrors())
                .withClasspathCacheDir(envDefaults.getClasspathCacheDir());

        RefactorTools agentTools = new RefactorTools(retriever, reader, config.getMaxChunks());
        AstDiffEngine diffEngine = new AstDiffEngine();
        DiffScorer diffScorer = new DiffScorer(reader);

        ChatService analyzerChat = new LmStudioChatService(
                config.getChatUrl(),
                config.getAnalyzerModel(),
                config.getAnalyzerTemperature(),
                config.getTopP(),
                config.getMaxTokens());

        if (config.isApply() && config.getRepoRoot() == null || config.getRepoRoot().isEmpty()) {
            throw new IllegalStateException("No repo root configured");
        }

        Path repoRoot = Paths.get(config.getRepoRoot());

        // Compile verifier (layered: javac fast + mvn fallback). Used both as
        // a LangChain4j tool (VerifyTools) and as the auto-gate inside
        // ApplyTools.commitPlan, plus pre-computed COMPILATION_STATUS injected
        // into analyzer prompts (see SafeRefactorLoop / SafeLoopApplyGate).
        Path classpathCache = (refactorConfig.getClasspathCacheDir() == null
                || refactorConfig.getClasspathCacheDir().isBlank())
            ? null
            : Paths.get(refactorConfig.getClasspathCacheDir());
        CompilationVerifier verifier = new LayeredCompilationVerifier(
                new JavacVerifier(new ClasspathResolver(repoRoot, classpathCache)),
                new MavenVerifier());

        CompilationRequest.Mode verifyMode = parseVerifyMode(refactorConfig.getVerifyMode());
        CompilationVerifier gatingVerifier = refactorConfig.isRequireCompile() ? verifier : null;

        SafeLoopApplyGate gate = new SafeLoopApplyGate(
                analyzerChat, config,
                gatingVerifier, verifyMode, refactorConfig.getVerifyMaxErrors(),
                repoRoot, reader);

        ApplyTools applyTools = new ApplyTools(
                repoRoot,
                reader,
                config.isDryRun(),
                config.isBackup(),
                gate,
                reindexer,
                gatingVerifier,
                verifyMode,
                refactorConfig.getVerifyMaxErrors());

        VerifyTools verifyTools = new VerifyTools(applyTools, verifier,
                refactorConfig.getVerifyMaxErrors());

        RefactorAgent agent = new RefactorAgent(refactorConfig, agentTools, applyTools, verifyTools);

        SafeLoopTools loopTools = new SafeLoopTools(retriever, reader, config);
        SafeRefactorLoop loop = new SafeRefactorLoop(
                agent, analyzerChat, loopTools, agentTools, config,
                diffEngine, diffScorer, reindexer,
                gatingVerifier, verifyMode, refactorConfig.getVerifyMaxErrors());

        return new SafeLoopBundle(loop, analyzerChat);
    }

    public SafeRefactorLoop loop() {
        return loop;
    }

    private static CompilationRequest.Mode parseVerifyMode(String s) {
        if (s == null) return CompilationRequest.Mode.AUTO;
        return switch (s.trim().toLowerCase()) {
            case "fast" -> CompilationRequest.Mode.FAST;
            case "full" -> CompilationRequest.Mode.FULL;
            default -> CompilationRequest.Mode.AUTO;
        };
    }

    @Override
    public void close() throws Exception {
        analyzerChat.close();
    }
}
