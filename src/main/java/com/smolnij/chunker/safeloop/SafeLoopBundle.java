package com.smolnij.chunker.safeloop;

import com.smolnij.chunker.refactor.ChatService;
import com.smolnij.chunker.refactor.LmStudioChatService;
import com.smolnij.chunker.refactor.RefactorAgent;
import com.smolnij.chunker.refactor.RefactorConfig;
import com.smolnij.chunker.refactor.RefactorTools;
import com.smolnij.chunker.refactor.diff.AstDiffEngine;
import com.smolnij.chunker.refactor.diff.DiffScorer;
import com.smolnij.chunker.retrieval.HybridRetriever;
import com.smolnij.chunker.retrieval.Neo4jGraphReader;

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
        RefactorConfig refactorConfig = new RefactorConfig()
                .withChatUrl(config.getChatUrl())
                .withChatModel(config.getRefactorModel())
                .withTemperature(config.getRefactorTemperature())
                .withTopP(config.getTopP())
                .withMaxTokens(config.getMaxTokens())
                .withMaxChunks(config.getMaxChunks())
                .withAgentMode(true)
                .withMaxToolCalls(config.getMaxToolCalls())
                .withChatMemorySize(config.getChatMemorySize());

        RefactorTools agentTools = new RefactorTools(retriever, reader, config.getMaxChunks());
        AstDiffEngine diffEngine = new AstDiffEngine();
        DiffScorer diffScorer = new DiffScorer(reader);
        RefactorAgent agent = new RefactorAgent(refactorConfig, agentTools);

        ChatService analyzerChat = new LmStudioChatService(
                config.getChatUrl(),
                config.getAnalyzerModel(),
                config.getAnalyzerTemperature(),
                config.getTopP(),
                config.getMaxTokens());

        SafeLoopTools loopTools = new SafeLoopTools(retriever, reader, config);
        SafeRefactorLoop loop = new SafeRefactorLoop(
                agent, analyzerChat, loopTools, agentTools, config,
                diffEngine, diffScorer);

        return new SafeLoopBundle(loop, analyzerChat);
    }

    public SafeRefactorLoop loop() {
        return loop;
    }

    @Override
    public void close() throws Exception {
        analyzerChat.close();
    }
}
