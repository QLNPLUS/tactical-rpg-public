package com.tactical.core.grid;

/**
 * Terrain queries the rules need. Implemented per loader against the level;
 * the core never sees a Minecraft type.
 */
public interface GridWorld {

    /**
     * Resolve the cell an entity could stand in for the column {@code (x, z)},
     * preferring {@code refY}, then {@code refY + 1}, then {@code refY - 1}.
     *
     * @return the standable cell, or {@code null} when the column has none in range
     */
    GridPos standCell(int x, int z, int refY);

    /** Default implementation that treats every neighbour of a valid cell as reachable. */
    default boolean isPassable(GridPos pos) {
        return true;
    }
}
