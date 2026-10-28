package com.tactical.core.zone;

import com.tactical.core.grid.GridPos;

/** Encounter membership is independent of its presentation or shape implementation. */
public interface BattleZone {
    boolean contains(GridPos cell);
    Bounds bounds();
    BattleZone including(GridPos cell);
    GridPos center();
    int radius();
    int halfHeight();

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
}
