package com.example.chunker.model.graph;

/**
 * Represents a Field node in the code graph.
 *
 * <p>Neo4j label: {@code :Field}
 * <p>Key property: {@code fqName} (e.g. "com.example.Foo.myField")
 */
public class FieldNode {

    private String fqName;           // e.g. "com.example.service.UserService.userRepository"
    private String name;             // e.g. "userRepository"
    private String declaration;      // e.g. "private final UserRepository userRepository;"
    private String type;             // e.g. "UserRepository"
    private String owningClassFqn;   // e.g. "com.example.service.UserService"

    // ── Getters & Setters ──

    public String getFqName() {
        return fqName;
    }

    public void setFqName(String fqName) {
        this.fqName = fqName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDeclaration() {
        return declaration;
    }

    public void setDeclaration(String declaration) {
        this.declaration = declaration;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOwningClassFqn() {
        return owningClassFqn;
    }

    public void setOwningClassFqn(String owningClassFqn) {
        this.owningClassFqn = owningClassFqn;
    }

    @Override
    public String toString() {
        return "Field[" + fqName + " : " + type + "]";
    }
}

