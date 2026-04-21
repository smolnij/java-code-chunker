package com.smolnij.chunker.apply;

/**
 * One deterministic, typed edit operation emitted by the LLM for the
 * {@link PatchApplier} to execute.
 *
 * <p>The loop never sees raw text diffs. Each op carries everything the
 * applier needs to locate the target structurally (fully-qualified class
 * name, method name, original signature) and a replacement body or import.
 * The applier resolves the target file via the Neo4j graph, rewrites the
 * JavaParser AST in place, and re-prints with {@code LexicalPreservingPrinter}
 * so surrounding formatting is preserved.
 */
public sealed interface EditOp
        permits EditOp.ReplaceMethod,
                EditOp.AddMethod,
                EditOp.DeleteMethod,
                EditOp.AddImport,
                EditOp.CreateFile {

    /**
     * Replace the body + signature of an existing method with {@code newCode}.
     *
     * @param fqClassName        fully qualified owning class (e.g. {@code com.example.UserService})
     * @param methodName         simple method name
     * @param originalSignature  the method signature recorded in the graph, used to
     *                           disambiguate overloads. May be empty; the applier
     *                           then matches by name only and fails if ambiguous.
     * @param newCode            the full replacement method source (modifiers, signature, body)
     */
    record ReplaceMethod(String fqClassName,
                         String methodName,
                         String originalSignature,
                         String newCode) implements EditOp { }

    /**
     * Add a new method to a class. The body is a full Java method declaration.
     */
    record AddMethod(String fqClassName,
                     String newCode) implements EditOp { }

    /**
     * Delete an existing method. Matched by name + original signature.
     */
    record DeleteMethod(String fqClassName,
                        String methodName,
                        String originalSignature) implements EditOp { }

    /**
     * Add a single import declaration to a file identified by repo-relative path.
     * The applier is a no-op when the import already exists.
     *
     * @param filePath    repo-relative path of the .java file
     * @param importDecl  the import body, e.g. {@code java.util.List} or {@code static java.util.Arrays.asList}
     */
    record AddImport(String filePath,
                     String importDecl) implements EditOp { }

    /**
     * Create a brand-new file at {@code relPath} (repo-relative) with the given content.
     * Fails if the file already exists.
     */
    record CreateFile(String relPath,
                      String content) implements EditOp { }
}
