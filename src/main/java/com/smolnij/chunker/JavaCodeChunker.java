package com.smolnij.chunker;

import com.smolnij.chunker.callgraph.CallGraphExtractor;
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
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
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

    /**
     * @param repoRoot           root of the repository
     * @param sourceRoots        list of source directories relative to repoRoot
     *                           (e.g., ["src/main/java", "src/test/java"])
     * @param maxTokensPerChunk  max tokens per chunk before splitting (e.g., 512)
     */
    public JavaCodeChunker(Path repoRoot, List<Path> sourceRoots, int maxTokensPerChunk) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
        this.sourceRoots = sourceRoots;
        this.callGraph = new CallGraphExtractor();
        this.boilerplateDetector = new BoilerplateDetector();
        this.tokenCounter = new TokenCounter(maxTokensPerChunk);

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

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
            .setSymbolResolver(symbolSolver)
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);

        this.parser = new JavaParser(config);
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

        // ── Phase 2: Parse & extract method chunks + class/field nodes + call graph ──
        int successCount = 0;
        int failCount = 0;
        for (Path javaFile : javaFiles) {
            try {
                processFile(javaFile);
                successCount++;
            } catch (Exception e) {
                failCount++;
                System.err.println("ERROR processing " + javaFile + ": " + e.getMessage());
            }
        }
        System.out.println("Parsed " + successCount + " files successfully, " + failCount + " failures.");

        // ── Phase 3: Back-patch "calledBy" edges from the call graph ──
        for (CodeChunk chunk : allChunks) {
            Set<String> callers = callGraph.getCallersOf(chunk.getChunkId());
            if (!callers.isEmpty()) {
                chunk.setCalledBy(new ArrayList<>(callers));
            }
        }

        // ── Phase 4: Filter out boilerplate ──
        List<CodeChunk> result = allChunks.stream()
            .filter(c -> !c.isBoilerplate())
            .collect(Collectors.toList());

        System.out.println("Total chunks: " + allChunks.size()
            + " | Non-boilerplate: " + result.size()
            + " | Filtered: " + (allChunks.size() - result.size()));

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
        }

        System.out.println(graphModel.getSummary());

        return result;
    }

    /**
     * Get the full graph model (nodes + edges) after {@link #process()} has been called.
     */
    public GraphModel getGraphModel() {
        return graphModel;
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
            processClass(classDecl, relativePath, packageName, imports)
        );
    }

    /**
     * Process a single class declaration: extract ClassNode, FieldNodes, and all methods as individual chunks.
     */
    private void processClass(ClassOrInterfaceDeclaration classDecl,
                               String relativePath,
                               String packageName,
                               List<String> imports) {

        String className = classDecl.getNameAsString();
        String fqClassName = packageName.isEmpty() ? className : packageName + "." + className;

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
        for (FieldDeclaration fieldDecl : classDecl.getFields()) {
            for (VariableDeclarator var : fieldDecl.getVariables()) {
                String fieldName = var.getNameAsString();
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
        }

        // ═══════════════════════════════════════════════════════════════
        // ── Process each method (same as before) ──
        // ═══════════════════════════════════════════════════════════════
        for (MethodDeclaration method : classDecl.getMethods()) {

            boolean isBoilerplate = isDto || boilerplateDetector.isBoilerplateMethod(method);

            String methodName = method.getNameAsString();
            String methodSig = method.getDeclarationAsString(true, true, true);

            // Build fully qualified method identifier
            String methodFqn = fqClassName + "#" + methodName + "("
                + method.getParameters().stream()
                    .map(p -> p.getTypeAsString())
                    .collect(Collectors.joining(", "))
                + ")";

            // Method annotations
            List<String> methodAnnotations = method.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .collect(Collectors.toList());

            // Source code
            String code = method.toString();
            int startLine = method.getBegin().map(p -> p.line).orElse(0);
            int endLine = method.getEnd().map(p -> p.line).orElse(0);

            // ── Extract call graph edges ──
            callGraph.extractCalls(method, methodFqn);
            List<String> calls = new ArrayList<>(callGraph.getCallsFrom(methodFqn));

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
                chunk.setImports(imports);

                chunk.setClassName(className);
                chunk.setFullyQualifiedClassName(fqClassName);
                chunk.setClassSignature(classSignature);
                chunk.setClassAnnotations(classAnnotations);
                chunk.setFieldDeclarations(fields);

                chunk.setMethodName(methodName);
                chunk.setMethodSignature(methodSig);
                chunk.setMethodAnnotations(methodAnnotations);
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

