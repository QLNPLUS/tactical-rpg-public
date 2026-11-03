package com.tactical.neoforge.battle.terrain;

import com.tactical.core.grid.GridPos;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pure landing resolution for a unit that lost the block it was standing on.
 *
 * <p>Nothing here touches a world: the caller supplies "is this cell standable", "is it still in
 * this battle zone" and "is somebody already there", so the same arithmetic decides the pre-check
 * before a block is broken and the re-check while a unit is already falling. That is deliberate -
 * a landing that is legal at the pre-check and impossible a tick later must be recomputed by the
 * same rule, never by a second implementation.</p>
 *
 * <p>The scan is a straight column, one cell at a time, exactly what gravity does. It stops at the
 * first standable cell; a column that leaves the battle zone before it finds one has no legal
 * landing at all, and the caller refuses the break instead of letting the unit fall out of the
 * battle.</p>
 */
public final class FallPlanner {

    /** Hard ceiling of one assured fall, in cells; deeper than any zone this mod can be configured with. */
    public static final int MAX_CELLS = 128;

    private FallPlanner() {
    }

    /** Where a falling unit would come to rest, and how far it drops. */
    public record Landing(GridPos cell, int cells) {
        /** Vanilla fall distance of this drop, in blocks: one grid cell is one block. */
        public double fallDistance() {
            return cells;
        }
    }

    /** What the unit's own cell allows on the next tick of a fall. */
    public enum Step {
        /** The block under this cell is solid: the unit has arrived. */
        LAND,
        /** Nothing holds the unit yet and the cell below can be occupied: keep descending. */
        DESCEND,
        /**
         * Nothing holds the unit and the cell below refuses entry: the unit keeps its place in the air
         * and the fall waits.
         *
         * <p>It is deliberately not a landing. Treating it as one would re-pin a body that has nothing
         * under it and restart the fall on the next tick - a permanent hover that reports itself as a
         * successful drop. The caller keeps its own deadline running instead, so a column that stays
         * blocked ends in the explicit exception path.</p>
         */
        STUCK
    }

    /**
     * The landing of a unit falling from {@code from}.
     *
     * @param standable whether a unit can stand in that cell at all (free feet and head, solid floor)
     * @param inZone    whether the cell still belongs to this battle zone
     * @param occupied  whether another unit already holds that cell
     * @return the landing, or empty when the column leaves the zone or ends on a taken cell. A taken
     *         landing is refused rather than stacked, which is what keeps two units from arriving in
     *         the same place.
     */
    public static Optional<Landing> resolve(GridPos from, Predicate<GridPos> standable,
                                            Predicate<GridPos> inZone, Predicate<GridPos> occupied) {
        return resolve(from, standable, inZone, occupied, MAX_CELLS);
    }

    public static Optional<Landing> resolve(GridPos from, Predicate<GridPos> standable,
                                            Predicate<GridPos> inZone, Predicate<GridPos> occupied, int maxCells) {
        if (from == null || maxCells < 1) return Optional.empty();
        int limit = Math.min(MAX_CELLS, maxCells);
        for (int cells = 1; cells <= limit; cells++) {
            GridPos cell = new GridPos(from.x(), from.y() - cells, from.z());
            if (!inZone.test(cell)) return Optional.empty();
            if (!standable.test(cell)) continue;
            if (occupied.test(cell)) return Optional.empty();
            return Optional.of(new Landing(cell, cells));
        }
        return Optional.empty();
    }

    /**
     * One tick of a fall in progress.
     *
     * @param hasFloorBelow whether the block under {@code cell} has collision
     * @param enterable     whether a unit's body fits in that cell (used for the cell below)
     */
    public static Step next(GridPos cell, Predicate<GridPos> hasFloorBelow, Predicate<GridPos> enterable) {
        if (cell == null) return Step.STUCK;
        if (hasFloorBelow.test(cell)) return Step.LAND;
        GridPos below = new GridPos(cell.x(), cell.y() - 1, cell.z());
        return enterable.test(below) ? Step.DESCEND : Step.STUCK;
    }

    /**
     * The fall distance the vanilla chain must be asked to settle.
     *
     * <p>It is the real drop in blocks, never a threshold: the native calculation already subtracts
     * safe-fall-distance and multiplies by the fall-damage multiplier, so this class must not decide
     * that a short fall is free or that a long one hurts.</p>
     */
    public static double settlementDistance(int cells, boolean immune) {
        return immune ? 0.0D : Math.max(0, cells);
    }

    /**
     * Whether this unit's landing must not cost health at all.
     *
     * <p>Vanilla does not decide this inside the damage calculation: slow falling and levitation
     * reset the fall distance while travelling, and a flying player never takes fall damage. A
     * scripted descent has no vanilla travel, so the same three states are tested here instead of
     * being silently lost.</p>
     */
    public static boolean immuneToFallDamage(boolean slowFalling, boolean levitation, boolean flight,
                                             boolean fallFlying) {
        return slowFalling || levitation || flight || fallFlying;
    }
}
