package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

/** Pure geometry for conservative explosion telegraphs; does not assume line of sight. */
public final class BlastWarningGeometry {
    private BlastWarningGeometry() { }

    /** Creeper stand-off uses horizontal Euclidean distance and one step of height. */
    public static boolean inPreparationRange(GridPos actor, GridPos player) {
        double dx = (double) actor.x() - player.x();
        double dz = (double) actor.z() - player.z();
        double squared = dx * dx + dz * dz;
        return squared >= 1 && squared <= 4 && Math.abs((long) actor.y() - player.y()) <= 1;
    }

    /** Vanilla entity damage reaches 2*strength; block rays travel less than that. */
    public static double warningRadius(int explosionRadius, boolean powered) {
        return 2.0 * Math.max(0, explosionRadius) * (powered ? 2 : 1);
    }

    /** Include a voxel if any part of its closed AABB intersects the blast sphere. */
    public static boolean intersectsBlock(double x, double y, double z, double radius,
                                          int blockX, int blockY, int blockZ) {
        if (!Double.isFinite(radius) || radius < 0) return false;
        double dx = axisDistance(x, blockX);
        double dy = axisDistance(y, blockY);
        double dz = axisDistance(z, blockZ);
        return dx * dx + dy * dy + dz * dz <= radius * radius + 1.0e-9;
    }

    private static double axisDistance(double value, int minimum) {
        return Math.max(Math.max(minimum - value, value - (minimum + 1.0)), 0);
    }
}
