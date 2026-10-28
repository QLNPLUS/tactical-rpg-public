package com.tactical.core.grid;

/** Four-direction movement on the block grid. */
public enum GridDirection {

    NORTH(0, -1),
    SOUTH(0, 1),
    EAST(1, 0),
    WEST(-1, 0);

    public final int dx;
    public final int dz;

    GridDirection(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public GridDirection opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }
}
