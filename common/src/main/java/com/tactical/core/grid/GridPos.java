package com.tactical.core.grid;

import java.util.Objects;

/**
 * Integer block-grid position. A combatant standing in this cell occupies the
 * block at {@code y} (feet) and {@code y + 1} (head).
 */
public record GridPos(int x, int y, int z) implements Comparable<GridPos> {

    public GridPos {
        Objects.requireNonNull(x);
    }

    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    public GridPos above() {
        return new GridPos(x, y + 1, z);
    }

    public GridPos below() {
        return new GridPos(x, y - 1, z);
    }

    /** Four-direction adjacency on the same layer. */
    public boolean isOrthogonallyAdjacent(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z) == 1 && y == other.y;
    }

    public int horizontalDistance(GridPos other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z);
    }

    public int manhattanDistance(GridPos other) {
        return horizontalDistance(other) + Math.abs(y - other.y);
    }

    public int heightDifference(GridPos other) {
        return y - other.y;
    }

    @Override
    public int compareTo(GridPos other) {
        int c = Integer.compare(y, other.y);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(x, other.x);
        return c != 0 ? c : Integer.compare(z, other.z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
