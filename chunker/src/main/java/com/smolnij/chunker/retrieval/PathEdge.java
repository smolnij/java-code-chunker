package com.smolnij.chunker.retrieval;

import java.util.Comparator;
import java.util.Objects;

/**
 * A single directed edge in a retrieved graph path.
 *
 * <p>Carries the endpoints, relationship type (e.g. {@code CALLS}, {@code CALLED_BY}),
 * and the direction relative to how it was traversed. Rendering always reads
 * left-to-right in call direction, so an {@link Direction#IN IN} edge is flipped
 * to preserve semantics.
 */
public final class PathEdge implements Comparable<PathEdge> {

    public enum Direction { OUT, IN }

    private static final Comparator<PathEdge> ORDER = Comparator
        .comparing(PathEdge::getSourceId)
        .thenComparing(PathEdge::getTargetId)
        .thenComparing(PathEdge::getRelType)
        .thenComparing(e -> e.getDirection().name());

    private final String sourceId;
    private final String targetId;
    private final String relType;
    private final Direction direction;

    public PathEdge(String sourceId, String targetId, String relType, Direction direction) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.relType = Objects.requireNonNull(relType, "relType");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public String getRelType() { return relType; }
    public Direction getDirection() { return direction; }

    /**
     * Render as {@code "source -[TYPE]-> target"}.
     * For {@link Direction#IN IN} edges the endpoints are swapped so the
     * rendered chain always reads in call-direction order.
     */
    public String render() {
        if (direction == Direction.OUT) {
            return sourceId + " -[" + relType + "]-> " + targetId;
        }
        return targetId + " -[" + relType + "]-> " + sourceId;
    }

    @Override
    public int compareTo(PathEdge other) {
        return ORDER.compare(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PathEdge that)) return false;
        return sourceId.equals(that.sourceId)
            && targetId.equals(that.targetId)
            && relType.equals(that.relType)
            && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, targetId, relType, direction);
    }

    @Override
    public String toString() {
        return render();
    }
}
