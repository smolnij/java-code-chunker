package com.smolnij.chunker.model.graph;

/**
 * Represents a typed, directed edge in the code graph.
 *
 * <p>Edge types map directly to Neo4j relationship types:
 * <ul>
 *   <li>{@code CALLS}       — Method → Method (forward call)</li>
 *   <li>{@code CALLED_BY}   — Method → Method (reverse call, inverse of CALLS)</li>
 *   <li>{@code BELONGS_TO}  — Method → Class  (method is declared in class)</li>
 *   <li>{@code HAS_FIELD}   — Class → Field   (class declares field)</li>
 *   <li>{@code IMPLEMENTS}  — Class → Interface</li>
 *   <li>{@code EXTENDS}     — Class → Class (or Interface → Interface)</li>
 *   <li>{@code CONTAINS}    — Package → Class/Interface</li>
 * </ul>
 */
public class GraphEdge {

    public enum EdgeType {
        CALLS,
        CALLED_BY,
        BELONGS_TO,
        HAS_FIELD,
        IMPLEMENTS,
        EXTENDS,
        CONTAINS
    }

    private final EdgeType type;
    private final String sourceFqn;
    private final String targetFqn;

    public GraphEdge(EdgeType type, String sourceFqn, String targetFqn) {
        this.type = type;
        this.sourceFqn = sourceFqn;
        this.targetFqn = targetFqn;
    }

    public EdgeType getType() {
        return type;
    }

    public String getSourceFqn() {
        return sourceFqn;
    }

    public String getTargetFqn() {
        return targetFqn;
    }

    @Override
    public String toString() {
        return sourceFqn + " -[" + type + "]-> " + targetFqn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphEdge that)) return false;
        return type == that.type
            && sourceFqn.equals(that.sourceFqn)
            && targetFqn.equals(that.targetFqn);
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + sourceFqn.hashCode();
        result = 31 * result + targetFqn.hashCode();
        return result;
    }
}

