package com.smolnij.chunker;

import com.smolnij.chunker.callgraph.CallGraphExtractor;
import com.smolnij.chunker.callgraph.MethodId;
import com.smolnij.chunker.filter.BoilerplateDetector;
import com.smolnij.chunker.model.CodeChunk;
import com.smolnij.chunker.model.graph.ClassNode;
import com.smolnij.chunker.model.graph.FieldNode;
import com.smolnij.chunker.model.graph.GraphEdge;
import com.smolnij.chunker.model.graph.GraphModel;
import com.smolnij.chunker.tokenizer.TokenCounter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Main orchestrator: parses a Java repository, produces method-level chunks
 * with class context, call graph edges, and token-aware splitting.
 *
 * <h3>Pipeline phases:</h3>
 * <ol>
 *   <li><b>Phase 1:</b> Collect all .java files from source roots</li>
 *   <li><b>Phase 2:</b> Parse each file → extract method chunks, class/field nodes, and call graph edges</li>
 *   <li><b>Phase 3:</b> Back-patch "calledBy" reverse edges from the call graph</li>
 *   <li><b>Phase 4:</b> Filter out boilerplate (getters/setters/DTOs)</li>
 *   <li><b>Phase 5:</b> Assemble the full {@link GraphModel} with all nodes and edges</li>
 * </ol>
 */
public class JavaCodeChunker {

    private final Path repoRoot;
    private final List<Path> sourceRoots;
    private final JavaParser parser;
    private final CallGraphExtractor callGraph;
    private final BoilerplateDetector boilerplateDetector;
    private final TokenCounter tokenCounter;

    // All chunks, keyed by method FQN for calledBy back-patching
    private final Map<String, CodeChunk> chunkIndex = new LinkedHashMap<>();
    private final List<CodeChunk> allChunks = new ArrayList<>();

    // ── Graph model collections (populated during Phase 2) ──
    private final GraphModel graphModel = new GraphModel();

    // When true, narrow each chunk's field declarations + imports to only those the
    // method actually references (READS_FIELD/WRITES_FIELD + identifier match). Opt-in
    // via chunker.relevantFieldsOnly; default false keeps the full-context behavior.
    private boolean relevantFieldsOnly = false;

    /**
     * @param repoRoot           root of the repository
     * @param sourceRoots        list of source directories relative to repoRoot
     *                           (e.g., ["src/main/java", "src/test/java"])
     * @param maxTokensPerChunk  max tokens per chunk before splitting (e.g., 512)
     */
    public JavaCodeChunker(Path repoRoot, List<Path> sourceRoots, int maxTokensPerChunk) {
        this(repoRoot, sourceRoots, maxTokensPerChunk, List.of());
    }

    /**
     * @param repoRoot           root of the repository
     * @param sourceRoots        list of source directories relative to repoRoot
     *                           (e.g., ["src/main/java", "src/test/java"])
     * @param maxTokensPerChunk  max tokens per chunk before splitting (e.g., 512)
     * @param classpath          dependency jars (or directories containing jars)
     *                           to add to the type solver so calls into external
     *                           libraries resolve to fully-qualified targets
     *                           instead of dead-end unresolved edges
     */
    public JavaCodeChunker(Path repoRoot, List<Path> sourceRoots, int maxTokensPerChunk, List<Path> classpath) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sourceRoots = sourceRoots;
        this.callGraph = new CallGraphExtractor();
        this.boilerplateDetector = new BoilerplateDetector();

        // ── Configure JavaParser with Symbol Solver ──
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());  // JDK types

        for (Path srcRoot : sourceRoots) {
            Path resolvedSrcRoot = this.repoRoot.resolve(srcRoot);
            if (Files.isDirectory(resolvedSrcRoot)) {
                typeSolver.add(new JavaParserTypeSolver(resolvedSrcRoot));
            }
        }

        // Fallback: add repoRoot itself as a type solver source if it contains .java files
        if (sourceRoots.stream().noneMatch(sr -> Files.isDirectory(this.repoRoot.resolve(sr)))) {
            typeSolver.add(new JavaParserTypeSolver(this.repoRoot));
        }

        // ── Dependency jars: lets calls into third-party libraries resolve ──
        int jarCount = addClasspathSolvers(typeSolver, classpath);
        if (jarCount > 0) {
            System.out.println("Type solver: added " + jarCount + " dependency jar(s) from chunker.classpath.");
        }

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
            .setSymbolResolver(symbolSolver)
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        this.parser = new JavaParser(config);

        // Now that the project-configured parser is available, create TokenCounter
        this.tokenCounter = new TokenCounter(maxTokensPerChunk, this.parser);
    }

    /**
     * Register dependency jars with the type solver. Each entry may be a {@code .jar}
     * file or a directory; directories are scanned recursively for {@code .jar} files.
     * Failures to read an individual jar are logged and skipped (best-effort).
     *
     * @return the number of jars successfully added
     */
    private static int addClasspathSolvers(CombinedTypeSolver typeSolver, List<Path> classpath) {
        int count = 0;
        for (Path entry : classpath) {
            if (entry == null) continue;
            try {
                if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                    typeSolver.add(new JarTypeSolver(entry));
                    count++;
                } else if (Files.isDirectory(entry)) {
                    List<Path> jars;
                    try (var stream = Files.walk(entry)) {
                        jars = stream.filter(pp -> pp.toString().endsWith(".jar")).collect(Collectors.toList());
                    }
                    for (Path jar : jars) {
                        try {
                            typeSolver.add(new JarTypeSolver(jar));
                            count++;
                        } catch (IOException ex) {
                            System.err.println("WARN: could not add jar to type solver: " + jar + " — " + ex.getMessage());
                        }
                    }
                } else {
                    System.err.println("WARN: classpath entry not found or not a jar/dir: " + entry);
                }
            } catch (IOException ex) {
                System.err.println("WARN: could not add classpath entry to type solver: " + entry + " — " + ex.getMessage());
            }
        }
        return count;
    }

    /**
     * Run the full chunking pipeline and return the list of non-boilerplate chunks.
     */
    public List<CodeChunk> process() throws IOException {

        // ── Phase 1: Collect all Java files ──
        List<Path> javaFiles = new ArrayList<>();
        for (Path srcRoot : sourceRoots) {
            Path resolvedSrcRoot = repoRoot.resolve(srcRoot).toAbsolutePath().normalize();
            if (!Files.isDirectory(resolvedSrcRoot)) {
                System.err.println("WARN: Source root not found: " + resolvedSrcRoot);
                continue;
            }

            Files.walkFileTree(resolvedSrcRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Fallback: if no files found from specified source roots, scan repoRoot directly
        if (javaFiles.isEmpty()) {
            System.out.println("No files found in specified source roots; scanning repoRoot: " + repoRoot.toAbsolutePath());
            Files.walkFileTree(repoRoot.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) {
                        javaFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        System.out.println("Found " + javaFiles.size() + " Java files to process.");

        // processFilesInternal already strips boilerplate before populating
        // GraphModel.methodNodes — no need to filter again here.
        return new ArrayList<>(processFilesInternal(javaFiles, /*reportSummary=*/true).getMethodNodes());
    }

    /**
     * Re-run the chunking pipeline (phases 2–5) over an explicit list of
     * {@code .java} files only, returning a {@link GraphModel} that contains
     * exactly the nodes and edges discovered in those files. Used by the
     * post-apply Neo4j delta re-indexer so the graph stays consistent with
     * disk after {@link com.smolnij.chunker.apply.PatchApplier} commits.
     *
     * <p>Internal state (allChunks, chunkIndex, graphModel, callGraph) is
     * reset on entry so the returned model is bounded by the input files.
     * Files not ending in {@code .java} are silently skipped.
     *
     * @param javaFiles absolute paths of .java files to (re-)process
     * @return a fresh GraphModel for the given files; never null
     */
    public GraphModel processFiles(Collection<Path> javaFiles) throws IOException {
        return processFilesInternal(new ArrayList<>(javaFiles), /*reportSummary=*/false);
    }

    /**
     * Shared phase 2–5 driver. Resets internal state, parses each file,
     * back-patches calledBy edges, and assembles the GraphModel.
     */
    private GraphModel processFilesInternal(List<Path> javaFiles, boolean reportSummary) {
        // Reset state so repeated calls produce independent models.
        allChunks.clear();
        chunkIndex.clear();
        graphModel.reset();
        callGraph.reset();

        // ── Phase 2: Parse & extract method chunks + class/field nodes + call graph ──
        int successCount = 0;
        int failCount = 0;
        for (Path javaFile : javaFiles) {
            if (!javaFile.toString().endsWith(".java")) continue;
            try {
                processFile(javaFile);
                successCount++;
            } catch (Exception e) {
                failCount++;
                System.err.println("ERROR processing " + javaFile + ": " + e.getMessage());
            }
        }
        if (reportSummary) {
            System.out.println("Parsed " + successCount + " files successfully, " + failCount + " failures.");
        }

        // ── Phase 3: Back-patch "calledBy" edges from the call graph ──
        // Callers reference the base method FQN (no "#partN"), so split-method
        // part chunks must be looked up by their base id too.
        for (CodeChunk chunk : allChunks) {
            String baseFqn = chunk.getChunkId().split("#part")[0];
            Set<String> callers = callGraph.getCallersOf(baseFqn);
            if (!callers.isEmpty()) {
                chunk.setCalledBy(new ArrayList<>(callers));
            }
        }

        // ── Phase 3b: Attach each neighbor's full signature (improvements.txt #5) ──
        // So a chunk shows "Calls: com.x.Repo#save(User) ⇒ public User save(User u)" instead
        // of a bare FQN the LLM must guess the contract for. Built from every known method
        // (including filtered boilerplate getters) so neighbor coverage is maximal.
        Map<String, String> sigByBaseFqn = new HashMap<>();
        for (CodeChunk chunk : allChunks) {
            String baseFqn = chunk.getChunkId().split("#part")[0];
            sigByBaseFqn.putIfAbsent(baseFqn, chunk.getMethodSignature());
        }
        for (CodeChunk chunk : allChunks) {
            Map<String, String> neighborSigs = new LinkedHashMap<>();
            for (String callee : chunk.getCalls()) {
                String sig = sigByBaseFqn.get(callee);
                if (sig != null) neighborSigs.put(callee, sig);
            }
            for (String caller : chunk.getCalledBy()) {
                String sig = sigByBaseFqn.get(caller);
                if (sig != null) neighborSigs.put(caller, sig);
            }
            chunk.setNeighborSignatures(neighborSigs);
        }

        if (reportSummary) {
            int resolved = callGraph.getResolvedCallCount();
            int unresolved = callGraph.getUnresolvedCallCount();
            int totalCalls = resolved + unresolved;
            double rate = totalCalls == 0 ? 0.0 : (100.0 * resolved / totalCalls);
            System.out.printf(
                "Call resolution: %d/%d resolved (%.1f%%), %d unresolved dead-end edges.%n",
                resolved, totalCalls, rate, unresolved);
            if (unresolved > 0) {
                System.out.println("  (high unresolved counts usually mean dependency jars are missing — "
                    + "set chunker.classpath to point at them.)");
            }
        }

        // ── Phase 4: Filter out boilerplate ──
        List<CodeChunk> result = allChunks.stream()
            .filter(c -> !c.isBoilerplate())
            .collect(Collectors.toList());

        if (reportSummary) {
            System.out.println("Total chunks: " + allChunks.size()
                + " | Non-boilerplate: " + result.size()
                + " | Filtered: " + (allChunks.size() - result.size()));
        }

        // ── Phase 5: Assemble the GraphModel ──
        // Add method nodes
        for (CodeChunk chunk : result) {
            graphModel.addMethodNode(chunk);
        }

        // Add CALLS / CALLED_BY edges from the processed chunks
        for (CodeChunk chunk : result) {
            String chunkId = chunk.getChunkId();
            for (String callee : chunk.getCalls()) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.CALLS, chunkId, callee));
            }
            for (String caller : chunk.getCalledBy()) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.CALLED_BY, chunkId, caller));
            }

            // BELONGS_TO: method → class
            graphModel.addEdge(new GraphEdge(
                GraphEdge.EdgeType.BELONGS_TO,
                chunkId,
                chunk.getFullyQualifiedClassName()
            ));

            // Additional P-G1 edges (best-effort). Map chunkId parts back to base method FQN.
            String baseMethodFqn = chunkId.split("#part")[0];

            // USES_TYPE
            for (String t : callGraph.getUsesTypesFrom(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.USES_TYPE, chunkId, t));
            }

            // RETURNS_TYPE
            for (String t : callGraph.getReturnsTypesFrom(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.RETURNS_TYPE, chunkId, t));
            }

            // THROWS
            for (String t : callGraph.getThrowsTypesFrom(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.THROWS, chunkId, t));
            }

            // READS_FIELD / WRITES_FIELD (method -> field)
            for (String f : callGraph.getReadsFieldFrom(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.READS_FIELD, chunkId, f));
            }
            for (String f : callGraph.getWritesFieldFrom(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.WRITES_FIELD, chunkId, f));
            }

            // TEST_FOR (test method -> callees)
            for (String t : callGraph.getTestForTargets(baseMethodFqn)) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.TEST_FOR, chunkId, t));
            }
        }

        if (reportSummary) {
            System.out.println(graphModel.getSummary());
        }

        return graphModel;
    }

    /**
     * Get the full graph model (nodes + edges) after {@link #process()} has been called.
     */
    public GraphModel getGraphModel() {
        return graphModel;
    }

    /**
     * When enabled, each method chunk carries only the field declarations it
     * references and only the imports whose simple type name appears in the method
     * body, instead of the full class field list and file import list. Must be set
     * before {@link #process()}.
     */
    public void setRelevantFieldsOnly(boolean relevantFieldsOnly) {
        this.relevantFieldsOnly = relevantFieldsOnly;
    }

    /** Method calls resolved to a fully-qualified target during the last {@link #process()} run. */
    public int getResolvedCallCount() {
        return callGraph.getResolvedCallCount();
    }

    /** Method calls that fell back to an unresolved (dead-end) representation. */
    public int getUnresolvedCallCount() {
        return callGraph.getUnresolvedCallCount();
    }

    /**
     * Parse a single Java file and extract method-level chunks.
     */
    private void processFile(Path javaFile) throws IOException {
        ParseResult<CompilationUnit> result = parser.parse(javaFile);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            System.err.println("WARN: Failed to parse " + javaFile
                + " — problems: " + result.getProblems());
            return;
        }

        CompilationUnit cu = result.getResult().get();
        String relativePath = repoRoot.relativize(javaFile).toString().replace('\\', '/');

        // Package & imports
        String packageName = cu.getPackageDeclaration()
            .map(PackageDeclaration::getNameAsString)
            .orElse("");

        List<String> imports = cu.getImports().stream()
            .map(ImportDeclaration::toString)
            .map(String::trim)
            .collect(Collectors.toList());

        // Register package node
        graphModel.addPackage(packageName);

        // Process each class/interface in the file
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl ->
            processClass(classDecl, relativePath, packageName, imports, cu)
        );
    }

    /**
     * Canonical FQN for a type declaration, byte-matching the form
     * {@link com.smolnij.chunker.callgraph.CallGraphExtractor} emits for call-edge
     * targets ({@code resolved.declaringType().getQualifiedName()}). Nested types use
     * dot separators ({@code com.x.Outer.Inner}), so a nested class no longer collides
     * with a top-level class of the same simple name, and its chunk id matches the
     * call-edge targets that reference it.
     */
    private String classFqn(TypeDeclaration<?> decl, String packageName) {
        try {
            // Same API the resolver uses for edge targets — guarantees a byte-match.
            return decl.resolve().getQualifiedName();
        } catch (Exception e) {
            // Fallback: walk enclosing TypeDeclaration ancestors, dot-joined.
            List<String> parts = new ArrayList<>();
            Node n = decl;
            while (n != null) {
                if (n instanceof TypeDeclaration<?> td) parts.add(0, td.getNameAsString());
                n = n.getParentNode().orElse(null);
            }
            String nested = String.join(".", parts);
            return packageName.isEmpty() ? nested : packageName + "." + nested;
        }
    }

    /** Matches Java identifiers, used to scan a method body for referenced type names. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /**
     * Narrow the full class field-declaration list to only those declarations the
     * method actually reads or writes (per the READS_FIELD/WRITES_FIELD detection).
     * A multi-variable declaration is kept when any of its variables is referenced.
     * Declaration order is preserved.
     *
     * @param methodFqn      the method's canonical id
     * @param fields         full class field declarations (parallel to {@code fieldVarNames})
     * @param fieldVarNames  the set of variable names declared by each entry in {@code fields}
     */
    private List<String> narrowFields(String methodFqn, List<String> fields, List<Set<String>> fieldVarNames) {
        Set<String> referenced = new HashSet<>();
        for (String f : callGraph.getReadsFieldFrom(methodFqn)) referenced.add(simpleFieldName(f));
        for (String f : callGraph.getWritesFieldFrom(methodFqn)) referenced.add(simpleFieldName(f));
        if (referenced.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        for (int i = 0; i < fields.size() && i < fieldVarNames.size(); i++) {
            if (fieldVarNames.get(i).stream().anyMatch(referenced::contains)) {
                out.add(fields.get(i));
            }
        }
        return out;
    }

    /** Last dot-separated segment of a field FQN ({@code com.x.Foo.bar} → {@code bar}). */
    private static String simpleFieldName(String fieldFqn) {
        int dot = fieldFqn.lastIndexOf('.');
        return dot >= 0 ? fieldFqn.substring(dot + 1) : fieldFqn;
    }

    /**
     * Narrow the full file import list to imports whose simple type name appears as an
     * identifier in the method source. Wildcard imports ({@code a.b.*}) are kept
     * unconditionally since their members can't be matched by simple name.
     */
    private List<String> narrowImports(List<String> imports, String methodSource) {
        if (imports.isEmpty()) return imports;
        Set<String> tokens = new HashSet<>();
        Matcher m = IDENTIFIER.matcher(methodSource);
        while (m.find()) tokens.add(m.group());

        List<String> out = new ArrayList<>();
        for (String imp : imports) {
            String simple = importSimpleName(imp);
            if (simple == null || simple.equals("*") || tokens.contains(simple)) {
                out.add(imp);
            }
        }
        return out;
    }

    /** Simple name an import introduces: {@code import a.b.C;} → {@code C}, {@code a.b.*} → {@code *}. */
    private static String importSimpleName(String importStmt) {
        String s = importStmt.replaceFirst("^import\\s+", "").replaceFirst("^static\\s+", "")
            .replace(";", "").trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    /**
     * Process a single class declaration: extract ClassNode, FieldNodes, and all methods as individual chunks.
     */
    private void processClass(ClassOrInterfaceDeclaration classDecl,
                               String relativePath,
                               String packageName,
                               List<String> imports,
                               CompilationUnit cu) {

        String className = classDecl.getNameAsString();
        String fqClassName = classFqn(classDecl, packageName);

        // Check if the entire class is a DTO
        boolean isDto = boilerplateDetector.isDtoClass(classDecl);

        // Build class signature: "public class Foo extends Bar implements Baz"
        String classSignature = buildClassSignature(classDecl);

        // Class annotations
        List<String> classAnnotations = classDecl.getAnnotations().stream()
            .map(AnnotationExpr::toString)
            .collect(Collectors.toList());

        // Field declarations (included as context in each method chunk)
        List<String> fields = classDecl.getFields().stream()
            .map(FieldDeclaration::toString)
            .map(String::trim)
            .collect(Collectors.toList());

        // Class-level Javadoc (cleaned intent text); shared across all chunks of this class
        String classJavadoc = classDecl.getJavadoc().map(Javadoc::toText).map(String::trim).orElse(null);

        // ═══════════════════════════════════════════════════════════════
        // ── Build ClassNode for the graph model ──
        // ═══════════════════════════════════════════════════════════════
        ClassNode classNode = new ClassNode();
        classNode.setFqName(fqClassName);
        classNode.setSimpleName(className);
        classNode.setSignature(classSignature);
        classNode.setAnnotations(classAnnotations);
        classNode.setFilePath(relativePath);
        classNode.setPackageName(packageName);
        classNode.setInterface(classDecl.isInterface());

        // ── Resolve EXTENDS types ──
        List<String> extendedFqns = new ArrayList<>();
        for (ClassOrInterfaceType extType : classDecl.getExtendedTypes()) {
            extendedFqns.add(resolveTypeReference(extType, fqClassName));
        }
        classNode.setExtendedTypes(extendedFqns);

        // ── Resolve IMPLEMENTS types ──
        List<String> implementedFqns = new ArrayList<>();
        for (ClassOrInterfaceType implType : classDecl.getImplementedTypes()) {
            implementedFqns.add(resolveTypeReference(implType, fqClassName));
        }
        classNode.setImplementedTypes(implementedFqns);

        graphModel.addClassNode(classNode);

        // ── IMPORTS: record explicit import edges (normalize import strings)
        for (String impRaw : imports) {
            if (impRaw == null || impRaw.isBlank()) continue;
            String imp = impRaw.replaceFirst("^import\\s+", "").replace(";", "").replaceFirst("static\\s+", "").trim();
            if (!imp.isEmpty()) {
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.IMPORTS, fqClassName, imp));
            }
        }

        // ── INNER_CLASS_OF: if this class is nested, emit inner->outer edge
        classDecl.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(outer ->
            graphModel.addEdge(new GraphEdge(
                GraphEdge.EdgeType.INNER_CLASS_OF, fqClassName, classFqn(outer, packageName))));

        // ── CONTAINS edge: package → class ──
        if (!packageName.isEmpty()) {
            graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.CONTAINS, packageName, fqClassName));
        }

        // ── EXTENDS edges ──
        for (String superFqn : extendedFqns) {
            graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.EXTENDS, fqClassName, superFqn));
        }

        // ── IMPLEMENTS edges ──
        for (String ifaceFqn : implementedFqns) {
            graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.IMPLEMENTS, fqClassName, ifaceFqn));
        }

        // ═══════════════════════════════════════════════════════════════
        // ── Build FieldNodes for the graph model ──
        // ═══════════════════════════════════════════════════════════════
        // Simple names of all fields declared on this class, and the variable-name set
        // per declaration (parallel to `fields`) — both used for relevant-fields narrowing
        // and to scope READS_FIELD/WRITES_FIELD detection to this class's own fields.
        Set<String> classFieldNames = new HashSet<>();
        List<Set<String>> fieldVarNames = new ArrayList<>();
        for (FieldDeclaration fieldDecl : classDecl.getFields()) {
            Set<String> declVars = new LinkedHashSet<>();
            for (VariableDeclarator var : fieldDecl.getVariables()) {
                String fieldName = var.getNameAsString();
                declVars.add(fieldName);
                classFieldNames.add(fieldName);
                String fieldFqn = fqClassName + "." + fieldName;

                FieldNode fieldNode = new FieldNode();
                fieldNode.setFqName(fieldFqn);
                fieldNode.setName(fieldName);
                fieldNode.setDeclaration(fieldDecl.toString().trim());
                fieldNode.setType(var.getTypeAsString());
                fieldNode.setOwningClassFqn(fqClassName);

                graphModel.addFieldNode(fieldNode);

                // HAS_FIELD edge: class → field
                graphModel.addEdge(new GraphEdge(GraphEdge.EdgeType.HAS_FIELD, fqClassName, fieldFqn));
            }
            fieldVarNames.add(declVars);
        }

        // ═══════════════════════════════════════════════════════════════
        // ── Process each method (same as before) ──
        // ═══════════════════════════════════════════════════════════════
        for (MethodDeclaration method : classDecl.getMethods()) {

            boolean isBoilerplate = isDto || boilerplateDetector.isBoilerplateMethod(method);

            String methodName = method.getNameAsString();
            String methodSig = method.getDeclarationAsString(true, true, true);

            // Build fully qualified method identifier (canonical form shared with
            // the call-graph edge targets — see MethodId).
            String methodFqn = MethodId.of(fqClassName, methodName,
                method.getParameters().stream()
                    .map(p -> p.isVarArgs() ? p.getTypeAsString() + "[]" : p.getTypeAsString())
                    .collect(Collectors.toList()));

            // Method annotations
            List<String> methodAnnotations = method.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .collect(Collectors.toList());

            // Method Javadoc (cleaned intent text); same value on every part
            String methodJavadoc = method.getJavadoc().map(Javadoc::toText).map(String::trim).orElse(null);

            // Source code
            String code = method.toString();
            int startLine = method.getBegin().map(p -> p.line).orElse(0);
            int endLine = method.getEnd().map(p -> p.line).orElse(0);

            // ── Extract call graph edges ──
            callGraph.extractCalls(method, methodFqn);
            // ── Extract type / throws / import / test heuristics (best-effort)
            callGraph.extractTypeInfo(method, methodFqn, cu);
            // ── Extract field reads/writes (READS_FIELD/WRITES_FIELD edges)
            callGraph.extractFieldAccess(method, methodFqn, fqClassName, classFieldNames);
            List<String> calls = new ArrayList<>(callGraph.getCallsFrom(methodFqn));

            // Relevant-fields narrowing (opt-in): only the fields/imports this method uses.
            List<String> chunkFields = relevantFieldsOnly ? narrowFields(methodFqn, fields, fieldVarNames) : fields;
            List<String> chunkImports = relevantFieldsOnly ? narrowImports(imports, code) : imports;

            // ── Token-aware splitting ──
            List<String> codeParts = tokenCounter.splitIfNeeded(code);

            for (int i = 0; i < codeParts.size(); i++) {
                CodeChunk chunk = new CodeChunk();

                String chunkId = methodFqn;
                if (codeParts.size() > 1) {
                    chunkId += "#part" + (i + 1);
                }

                chunk.setChunkId(chunkId);
                chunk.setFilePath(relativePath);
                chunk.setPackageName(packageName);
                chunk.setImports(chunkImports);

                chunk.setClassName(className);
                chunk.setFullyQualifiedClassName(fqClassName);
                chunk.setClassSignature(classSignature);
                chunk.setClassAnnotations(classAnnotations);
                chunk.setFieldDeclarations(chunkFields);
                chunk.setClassJavadoc(classJavadoc);

                chunk.setMethodName(methodName);
                chunk.setMethodSignature(methodSig);
                chunk.setMethodAnnotations(methodAnnotations);
                chunk.setMethodJavadoc(methodJavadoc);
                chunk.setStartLine(startLine);
                chunk.setEndLine(endLine);

                chunk.setCode(codeParts.get(i));
                chunk.setTokenCount(tokenCounter.countTokens(codeParts.get(i)));

                chunk.setCalls(calls);
                // calledBy will be back-patched in Phase 3

                chunk.setPartIndex(i + 1);
                chunk.setTotalParts(codeParts.size());
                chunk.setBoilerplate(isBoilerplate);

                chunk.setParentClass(fqClassName);
                chunk.setParentPackage(packageName);

                chunkIndex.put(chunkId, chunk);
                allChunks.add(chunk);
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // ── Process each constructor (chunkId uses '<init>') ──
        // Constructors are emitted as their own chunks (one per ctor, split
        // into parts when long) so refactors that change DI, config wiring,
        // or initialization can find them. Marked boilerplate only when the
        // owning class is a DTO, to mirror existing method-level behaviour.
        // ═══════════════════════════════════════════════════════════════
        for (ConstructorDeclaration ctor : classDecl.getConstructors()) {
            String methodName = "<init>";
            String methodSig = ctor.getDeclarationAsString(true, true, true);

            String methodFqn = MethodId.of(fqClassName, "<init>",
                ctor.getParameters().stream()
                    .map(p -> p.isVarArgs() ? p.getTypeAsString() + "[]" : p.getTypeAsString())
                    .collect(Collectors.toList()));

            List<String> methodAnnotations = ctor.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .collect(Collectors.toList());

            String methodJavadoc = ctor.getJavadoc().map(Javadoc::toText).map(String::trim).orElse(null);

            String code = ctor.toString();
            int startLine = ctor.getBegin().map(p -> p.line).orElse(0);
            int endLine = ctor.getEnd().map(p -> p.line).orElse(0);

            callGraph.extractCalls(ctor, methodFqn);
            callGraph.extractTypeInfo(ctor, methodFqn, cu);
            callGraph.extractFieldAccess(ctor, methodFqn, fqClassName, classFieldNames);
            List<String> calls = new ArrayList<>(callGraph.getCallsFrom(methodFqn));

            List<String> chunkFields = relevantFieldsOnly ? narrowFields(methodFqn, fields, fieldVarNames) : fields;
            List<String> chunkImports = relevantFieldsOnly ? narrowImports(imports, code) : imports;

            List<String> codeParts = tokenCounter.splitIfNeeded(code);

            for (int i = 0; i < codeParts.size(); i++) {
                CodeChunk chunk = new CodeChunk();

                String chunkId = methodFqn;
                if (codeParts.size() > 1) {
                    chunkId += "#part" + (i + 1);
                }

                chunk.setChunkId(chunkId);
                chunk.setFilePath(relativePath);
                chunk.setPackageName(packageName);
                chunk.setImports(chunkImports);

                chunk.setClassName(className);
                chunk.setFullyQualifiedClassName(fqClassName);
                chunk.setClassSignature(classSignature);
                chunk.setClassAnnotations(classAnnotations);
                chunk.setFieldDeclarations(chunkFields);
                chunk.setClassJavadoc(classJavadoc);

                chunk.setMethodName(methodName);
                chunk.setMethodSignature(methodSig);
                chunk.setMethodAnnotations(methodAnnotations);
                chunk.setMethodJavadoc(methodJavadoc);
                chunk.setStartLine(startLine);
                chunk.setEndLine(endLine);

                chunk.setCode(codeParts.get(i));
                chunk.setTokenCount(tokenCounter.countTokens(codeParts.get(i)));

                chunk.setCalls(calls);

                chunk.setPartIndex(i + 1);
                chunk.setTotalParts(codeParts.size());
                chunk.setBoilerplate(isDto);

                chunk.setParentClass(fqClassName);
                chunk.setParentPackage(packageName);

                chunkIndex.put(chunkId, chunk);
                allChunks.add(chunk);
            }
        }
    }

    /**
     * Attempt to resolve a type reference (extends/implements) to its fully qualified name
     * using the Symbol Solver. Falls back to the simple name if resolution fails.
     */
    private String resolveTypeReference(ClassOrInterfaceType type, String contextClass) {
        try {
            var resolved = type.resolve();
            if (resolved.isReferenceType()) {
                return resolved.asReferenceType().getQualifiedName();
            }
            return type.getNameWithScope();
        } catch (Exception e) {
            // Symbol resolution failed — use unresolved name as-is
            return type.getNameAsString();
        }
    }

    /**
     * Build a human-readable class signature string.
     * Example: "public class MainPhaseService extends Object implements Serializable"
     */
    private String buildClassSignature(ClassOrInterfaceDeclaration classDecl) {
        StringBuilder sb = new StringBuilder();
        classDecl.getModifiers().forEach(m -> sb.append(m.getKeyword().asString()).append(" "));
        sb.append(classDecl.isInterface() ? "interface " : "class ");
        sb.append(classDecl.getNameAsString());

        if (!classDecl.getExtendedTypes().isEmpty()) {
            sb.append(" extends ").append(
                classDecl.getExtendedTypes().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", "))
            );
        }
        if (!classDecl.getImplementedTypes().isEmpty()) {
            sb.append(" implements ").append(
                classDecl.getImplementedTypes().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", "))
            );
        }

        return sb.toString().trim();
    }
}

