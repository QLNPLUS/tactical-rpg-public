package com.tactical.core.grid;

/** Shared terrain-step and facing rules. A drop costs exactly one grid move. */
public final class GridMovementRules {
    private GridMovementRules() { }

    public static boolean canStep(GridPos from, GridPos to) {
        return Math.abs(from.x() - to.x()) + Math.abs(from.z() - to.z()) == 1
                && Math.abs(from.y() - to.y()) <= 1;
    }

    /** Minecraft yaw: south=0, west=90, north=180, east=-90. */
    public static float yaw(GridPos from, GridPos to) {
        return (float) Math.toDegrees(Math.atan2(from.x() - to.x(), to.z() - from.z()));
    }
}
