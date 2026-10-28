package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;

/** Bounded geometric reach only; visibility and projectile eligibility require native validation. */
public final class AttackRangeGeometry {
    public static final int MAX_RADIUS = 32;
    public static final int MAX_ELEVATION = 8;
    public static final int MAX_TILES = 4096;

    private AttackRangeGeometry() { }

    public static boolean contains(GridPos origin, GridPos target, int weaponKind, int nativeRange) {
        long dx = (long) target.x() - origin.x();
        long dz = (long) target.z() - origin.z();
        long dy = (long) target.y() - origin.y();
        if (Math.abs(dx) > MAX_RADIUS || Math.abs(dz) > MAX_RADIUS || Math.abs(dy) > MAX_ELEVATION) return false;
        if (weaponKind == 0) return GridMovementRules.canStep(origin, target);
        if (weaponKind != 1 && weaponKind != 2) return false;
        int range = Math.min(MAX_RADIUS, Math.max(0, nativeRange));
        return Math.hypot(dx, dz) <= range + 0.001D;
    }
}
