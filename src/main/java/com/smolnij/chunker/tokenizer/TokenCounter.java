package com.smolnij.chunker.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-aware chunking: counts tokens using cl100k_base (GPT-4 / LLaMA-compatible)
 * and splits large code blocks at line boundaries when they exceed the configured
 * maximum token limit.
 *
 * <p>The cl100k_base tokenizer is a good proxy for most LLM tokenizers used by
 * LM-Studio models (LLaMA, Mistral, CodeLlama, etc.).
 */
public class TokenCounter {

    private final Encoding encoding;
    private final int maxTokensPerChunk;
    private final JavaParser parser;
    // Lock to protect shared JavaParser when used concurrently
    private final Object parserLock = new Object();

    /**
     * @param maxTokensPerChunk maximum tokens allowed per chunk before splitting
     * @param parser project-configured JavaParser (with symbol solver) to use for AST parsing
     */
    public TokenCounter(int maxTokensPerChunk, JavaParser parser) {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
        this.maxTokensPerChunk = maxTokensPerChunk;
        this.parser = parser;
    }

    /**
     * Count the number of tokens in the given text.
     */
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /**
     * Split a code string into sub-chunks of at most {@code maxTokensPerChunk} tokens.
     * Splits at line boundaries to preserve readability.
     *
     * @param code the source code string to potentially split
     * @return list of code fragments (single element if no split needed)
     */
    public List<String> splitIfNeeded(String code) {
        if (code == null || code.isEmpty()) {
            return List.of("");
        }

        int totalTokens = countTokens(code);
        if (totalTokens <= maxTokensPerChunk) {
            return List.of(code);
        }
        // Attempt AST-aware splitting: parse as a MethodDeclaration and split at statement
        // boundaries (nearest at-or-before budget). If parsing fails or there is no
        // body, fall back to line-aware splitting.
        try {
            ParseResult<MethodDeclaration> pr;
            // JavaParser instances are not guaranteed to be thread-safe for concurrent
            // parse operations in all versions; synchronize on a private lock to ensure
            // only one thread uses the shared parser at a time. If higher throughput is
            // needed, consider a ThreadLocal<JavaParser> or a small parser pool.
            synchronized (parserLock) {
                pr = parser.parseMethodDeclaration(code);
            }

            if (pr.isSuccessful() && pr.getResult().isPresent()) {
                MethodDeclaration method = pr.getResult().get();
                if (method.getBody().isPresent()) {
                    BlockStmt body = method.getBody().get();
                    return splitByStatementsPreservingSignature(method, body, code);
                }
            }
        } catch (Exception ignored) {
            // Parsing failed; fall back to line-based splitting below
        }

        return splitAtLineBoundaries(code);
    }

    /**
     * Split using statement boundaries. Each chunk is returned as a complete method
     * string: annotations + signature + opening brace + selected statements + closing brace.
     */
    private List<String> splitByStatementsPreservingSignature(MethodDeclaration method, BlockStmt body, String originalCode) {
        List<String> chunks = new ArrayList<>();

        // Build method header: annotations (if any) + declaration + opening brace
        StringBuilder headerBuilder = new StringBuilder();
        method.getAnnotations().forEach(a -> headerBuilder.append(a.toString()).append("\n"));
        headerBuilder.append(method.getDeclarationAsString(true, true, true));
        headerBuilder.append(" {");
        String header = headerBuilder.toString();
        String footer = "\n}"; // closing brace on its own line

        StringBuilder currentBody = new StringBuilder();
        int currentTokens = 0;

        for (Statement stmt : body.getStatements()) {
            String stmtText = stmt.toString();
            int stmtTokens = countTokens(stmtText + "\n");

            // If a single statement exceeds the budget, split it by lines as fallback
            if (stmtTokens > maxTokensPerChunk) {
                List<String> parts = splitAtLineBoundaries(stmtText);
                for (String part : parts) {
                    int partTokens = countTokens(part);
                    if (!currentBody.isEmpty() && currentTokens + partTokens > maxTokensPerChunk) {
                        // flush current chunk
                        String chunk = assembleMethodChunk(header, currentBody.toString(), footer);
                        chunks.add(chunk);
                        currentBody = new StringBuilder();
                        currentTokens = 0;
                    }
                    currentBody.append(part);
                    if (!part.endsWith("\n")) currentBody.append("\n");
                    currentTokens += partTokens;
                }
                continue;
            }

            if (!currentBody.isEmpty() && currentTokens + stmtTokens > maxTokensPerChunk) {
                // flush current chunk
                String chunk = assembleMethodChunk(header, currentBody.toString(), footer);
                chunks.add(chunk);
                currentBody = new StringBuilder();
                currentTokens = 0;
            }

            currentBody.append(stmtText);
            if (!stmtText.endsWith("\n")) currentBody.append("\n");
            currentTokens += stmtTokens;
        }

        if (!currentBody.isEmpty()) {
            String chunk = assembleMethodChunk(header, currentBody.toString(), footer);
            chunks.add(chunk);
        }

        // If for some reason no chunks were produced, fall back to line-based splitting of whole method
        if (chunks.isEmpty()) {
            return splitAtLineBoundaries(originalCode);
        }

        return chunks;
    }

    private String assembleMethodChunk(String header, String bodyContent, String footer) {
        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n");
        sb.append(bodyContent);
        // Ensure a newline before closing brace
        if (!bodyContent.endsWith("\n")) sb.append("\n");
        sb.append(footer);
        return sb.toString();
    }

    /**
     * Split a code string at line boundaries (previous behavior). Preserves newlines.
     */
    private List<String> splitAtLineBoundaries(String code) {
        List<String> chunks = new ArrayList<>();
        String[] lines = code.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String line : lines) {
            String lineWithNewline = line + "\n";
            int lineTokens = countTokens(lineWithNewline);

            // If adding this line would exceed the limit, flush the current chunk
            if (currentTokens + lineTokens > maxTokensPerChunk && !currentChunk.isEmpty()) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            currentChunk.append(lineWithNewline);
            currentTokens += lineTokens;
        }

        // Flush remaining content
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * @return the configured maximum tokens per chunk
     */
    public int getMaxTokensPerChunk() {
        return maxTokensPerChunk;
    }
}

