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
                EditOp.CreateFile,
                EditOp.AddMavenDependency,
                EditOp.RenameMethod,
                EditOp.RenameClass,
                EditOp.RenameField {

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

    /**
     * Add a Maven dependency to the project's {@code pom.xml}.
     *
     * <p>The applier inserts a properly indented {@code <dependency>} block
     * just before the closing {@code </dependencies>} tag of the first
     * {@code <dependencies>} section it finds. Idempotent: if a dependency
     * with the same {@code groupId} + {@code artifactId} already exists,
     * the op succeeds as a no-op.
     *
     * @param groupId     Maven groupId (required)
     * @param artifactId  Maven artifactId (required)
     * @param version     version string; may be empty when supplied by a BOM / dependencyManagement
     * @param scope       Maven scope (compile, test, provided, ...); may be empty for default
     */
    record AddMavenDependency(String groupId,
                              String artifactId,
                              String version,
                              String scope) implements EditOp { }

    /**
     * Rename a method in place (same class). The applier renames the
     * declaration and rewrites every {@code this.oldName(...)} or
     * {@code oldName(...)} call site within the same compilation unit.
     *
     * <p>Cross-file callers are repaired by the post-apply
     * {@code GraphReindexer}: this op also acts as an authoritative seed
     * for the rename map so callers in unchanged files get their CALLS
     * edges re-pointed (and, when within cascade budget, their
     * {@code :Method.code} text refreshed by re-parse).
     *
     * @param fqClassName     owning class FQN
     * @param oldMethodName   current method name
     * @param newMethodName   replacement method name
     * @param paramSignature  param-type signature (e.g. {@code "(java.lang.String, int)"});
     *                        empty disables overload disambiguation
     */
    record RenameMethod(String fqClassName,
                        String oldMethodName,
                        String newMethodName,
                        String paramSignature) implements EditOp { }

    /**
     * Rename a class (or interface). The applier rewrites the type
     * declaration and any {@code new OldName(...)} / {@code OldName.foo}
     * references in the same file. When the class is the file's primary
     * type, the file itself is also renamed on disk.
     *
     * <p>Acts as an authoritative seed for cross-file repair.
     */
    record RenameClass(String oldFqName,
                       String newFqName) implements EditOp { }

    /**
     * Rename a field within its owning class. The applier rewrites the
     * declaration and intra-file references.
     *
     * <p>Acts as an authoritative seed for cross-file repair.
     *
     * @param owningClassFqn  FQN of the class that declares the field
     * @param oldFieldName    current field name
     * @param newFieldName    replacement field name
     */
    record RenameField(String owningClassFqn,
                       String oldFieldName,
                       String newFieldName) implements EditOp { }
}
