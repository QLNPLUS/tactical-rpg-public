package com.tactical.core.zone;

import com.tactical.core.grid.GridPos;

/** Default cylinder or configurable box; custom implementations can supply different bounds. */
public record BoundedBattleZone(GridPos center, int radius, int halfHeight, boolean square) implements BattleZone {
    public BoundedBattleZone {
        if (radius < 1 || halfHeight < 1) throw new IllegalArgumentException("Invalid zone size");
    }
    @Override public boolean contains(GridPos cell) {
        long x = (long) cell.x() - center.x(), z = (long) cell.z() - center.z();
        return Math.abs((long) cell.y() - center.y()) <= halfHeight
                && (square ? Math.max(Math.abs(x), Math.abs(z)) <= radius
                : x*x + z*z <= (long) radius * radius);
    }
    @Override public Bounds bounds() {
        return new Bounds(center.x()-radius,center.y()-halfHeight,center.z()-radius,
                center.x()+radius,center.y()+halfHeight,center.z()+radius);
    }
    @Override public BattleZone including(GridPos cell) {
        double dx = (double) cell.x()-center.x(), dz = (double) cell.z()-center.z();
        int expanded = (int) Math.ceil(square ? Math.max(Math.abs(dx),Math.abs(dz)) : Math.hypot(dx,dz));
        return new BoundedBattleZone(center,Math.max(radius,expanded),
                Math.max(halfHeight,Math.abs(cell.y()-center.y())),square);
    }
}
