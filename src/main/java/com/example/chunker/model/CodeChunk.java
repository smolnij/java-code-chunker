package com.example.chunker.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one indexed code chunk — one method (or sub-method fragment)
 * with full structural context for graph-aware retrieval.
 *
 * <p>Each chunk carries:
 * <ul>
 *   <li>Identity: unique chunkId, file path, package</li>
 *   <li>Class context: signature, annotations, fields</li>
 *   <li>Method context: signature, annotations, line range</li>
 *   <li>Code: the actual source (possibly a sub-chunk of a large method)</li>
 *   <li>Call graph edges: calls (outgoing) and calledBy (incoming)</li>
 *   <li>Graph edges: parentClass, parentPackage for hierarchical traversal</li>
 * </ul>
 */
public class CodeChunk {

    // ── Identity ──
    private String chunkId;                          // unique: fqClass#methodSignature[#partN]
    private String filePath;                         // relative file path from repo root
    private String packageName;
    private List<String> imports = new ArrayList<>(); // only non-java.lang imports

    // ── Class context ──
    private String className;                        // simple name
    private String fullyQualifiedClassName;
    private String classSignature;                   // "public class Foo extends Bar implements Baz"
    private List<String> classAnnotations = new ArrayList<>();
    private List<String> fieldDeclarations = new ArrayList<>(); // non-getter/setter fields

    // ── Method context ──
    private String methodName;
    private String methodSignature;                  // "public void process(Record r)"
    private List<String> methodAnnotations = new ArrayList<>();
    private int startLine;
    private int endLine;

    // ── Code ──
    private String code;                             // actual method source
    private int tokenCount;

    // ── Call graph edges (for graph-aware retrieval) ──
    private List<String> calls = new ArrayList<>();      // FQ method calls made FROM this method
    private List<String> calledBy = new ArrayList<>();   // FQ methods that call THIS method

    // ── Chunking metadata ──
    private int partIndex = 0;                       // 0 = whole method; 1..N = sub-chunks
    private int totalParts = 1;
    private transient boolean isBoilerplate = false;           // true → skip indexing

    // ── Graph edges for retrieval ──
    private String parentClass;                      // edge → class node
    private String parentPackage;                    // edge → package node

    // ── Getters & Setters ──

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public List<String> getImports() {
        return imports;
    }

    public void setImports(List<String> imports) {
        this.imports = imports;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getFullyQualifiedClassName() {
        return fullyQualifiedClassName;
    }

    public void setFullyQualifiedClassName(String fullyQualifiedClassName) {
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    public String getClassSignature() {
        return classSignature;
    }

    public void setClassSignature(String classSignature) {
        this.classSignature = classSignature;
    }

    public List<String> getClassAnnotations() {
        return classAnnotations;
    }

    public void setClassAnnotations(List<String> classAnnotations) {
        this.classAnnotations = classAnnotations;
    }

    public List<String> getFieldDeclarations() {
        return fieldDeclarations;
    }

    public void setFieldDeclarations(List<String> fieldDeclarations) {
        this.fieldDeclarations = fieldDeclarations;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public void setMethodSignature(String methodSignature) {
        this.methodSignature = methodSignature;
    }

    public List<String> getMethodAnnotations() {
        return methodAnnotations;
    }

    public void setMethodAnnotations(List<String> methodAnnotations) {
        this.methodAnnotations = methodAnnotations;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public List<String> getCalls() {
        return calls;
    }

    public void setCalls(List<String> calls) {
        this.calls = calls;
    }

    public List<String> getCalledBy() {
        return calledBy;
    }

    public void setCalledBy(List<String> calledBy) {
        this.calledBy = calledBy;
    }

    public int getPartIndex() {
        return partIndex;
    }

    public void setPartIndex(int partIndex) {
        this.partIndex = partIndex;
    }

    public int getTotalParts() {
        return totalParts;
    }

    public void setTotalParts(int totalParts) {
        this.totalParts = totalParts;
    }

    public boolean isBoilerplate() {
        return isBoilerplate;
    }

    public void setBoilerplate(boolean boilerplate) {
        isBoilerplate = boilerplate;
    }

    public String getParentClass() {
        return parentClass;
    }

    public void setParentClass(String parentClass) {
        this.parentClass = parentClass;
    }

    public String getParentPackage() {
        return parentPackage;
    }

    public void setParentPackage(String parentPackage) {
        this.parentPackage = parentPackage;
    }

    /**
     * Render the chunk in the exact format the LM-Studio model expects.
     *
     * <pre>
     * Class: UserService
     * File: src/service/UserService.java
     * Method:
     *   - createUser(...)
     * Calls:
     *   - repository.save(...)
     * Called By:
     *   - controller.handleCreate(...)
     * Code:
     *   &lt;actual code&gt;
     * </pre>
     */
    public String toPromptFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(className).append("\n");
        sb.append("Fully Qualified: ").append(fullyQualifiedClassName).append("\n");
        sb.append("File: ").append(filePath).append("\n");
        sb.append("Class Signature: ").append(classSignature).append("\n");

        if (!classAnnotations.isEmpty()) {
            sb.append("Class Annotations: ").append(String.join(", ", classAnnotations)).append("\n");
        }
        if (!fieldDeclarations.isEmpty()) {
            sb.append("Fields:\n");
            fieldDeclarations.forEach(f -> sb.append("  - ").append(f).append("\n"));
        }

        sb.append("\nMethod:\n");
        sb.append("  - ").append(methodSignature).append("\n");
        if (!methodAnnotations.isEmpty()) {
            sb.append("  Annotations: ").append(String.join(", ", methodAnnotations)).append("\n");
        }
        sb.append("  Lines: ").append(startLine).append("-").append(endLine).append("\n");
        if (totalParts > 1) {
            sb.append("  (Part ").append(partIndex).append(" of ").append(totalParts).append(")\n");
        }
        sb.append("  Tokens: ").append(tokenCount).append("\n");

        if (!calls.isEmpty()) {
            sb.append("\nCalls:\n");
            calls.forEach(c -> sb.append("  - ").append(c).append("\n"));
        }

        if (!calledBy.isEmpty()) {
            sb.append("\nCalled By:\n");
            calledBy.forEach(c -> sb.append("  - ").append(c).append("\n"));
        }

        sb.append("\nCode:\n");
        sb.append(code).append("\n");

        return sb.toString();
    }
}

