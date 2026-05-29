package com.smolnij.chunker.model.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Class or Interface node in the code graph.
 *
 * <p>Neo4j labels: {@code :Class} or {@code :Interface}
 * <p>Key property: {@code fqName} (fully qualified name, unique)
 */
public class ClassNode {

    private String fqName;                                   // e.g. "com.example.service.UserService"
    private String simpleName;                               // e.g. "UserService"
    private String signature;                                // e.g. "public class UserService extends BaseService implements Serializable"
    private List<String> annotations = new ArrayList<>();    // e.g. ["@Service", "@Transactional"]
    private String filePath;                                 // relative path from repo root
    private String packageName;                              // e.g. "com.example.service"
    private boolean isInterface;                             // true if interface, false if class

    // ── Resolved type references (used to build EXTENDS / IMPLEMENTS edges) ──
    private List<String> extendedTypes = new ArrayList<>();    // FQ names of superclasses
    private List<String> implementedTypes = new ArrayList<>(); // FQ names of implemented interfaces

    // ── Getters & Setters ──

    public String getFqName() {
        return fqName;
    }

    public void setFqName(String fqName) {
        this.fqName = fqName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public void setSimpleName(String simpleName) {
        this.simpleName = simpleName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(List<String> annotations) {
        this.annotations = annotations;
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

    public boolean isInterface() {
        return isInterface;
    }

    public void setInterface(boolean anInterface) {
        isInterface = anInterface;
    }

    public List<String> getExtendedTypes() {
        return extendedTypes;
    }

    public void setExtendedTypes(List<String> extendedTypes) {
        this.extendedTypes = extendedTypes;
    }

    public List<String> getImplementedTypes() {
        return implementedTypes;
    }

    public void setImplementedTypes(List<String> implementedTypes) {
        this.implementedTypes = implementedTypes;
    }

    @Override
    public String toString() {
        return (isInterface ? "Interface" : "Class") + "[" + fqName + "]";
    }
}

