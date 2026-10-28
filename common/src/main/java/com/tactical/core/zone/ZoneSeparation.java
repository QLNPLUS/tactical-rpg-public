package com.tactical.core.zone;

/**
 * Spatial separation between two concurrently running encounters.
 *
 * <p>The approved first version keeps encounters independent: two encounters in the same
 * dimension may not share space, so a second encounter is refused instead of merging or
 * claiming the other's units. Different dimensions are always independent.</p>
 *
 * <p>Conflict is decided on the zones' closed integer {@link BattleZone.Bounds}, not on the
 * exact cylinder/box volume. That is deliberately conservative: two round zones whose
 * bounding boxes overlap in one corner are reported as conflicting even though the two round
 * areas miss each other. Refusing a start is recoverable; two overlapping encounters are not.</p>
 */
public final class ZoneSeparation {

    private ZoneSeparation() { }

    /**
     * True when the two closed integer boxes cover at least one common cell.
     *
     * <p>Bounds are inclusive, so boxes that only touch on a face (for example
     * {@code maxX == other.minX}) do share that layer of cells and do conflict.</p>
     */
    public static boolean overlap(BattleZone.Bounds first, BattleZone.Bounds second) {
        if (first == null || second == null) return false;
        return first.minX() <= second.maxX() && second.minX() <= first.maxX()
                && first.minY() <= second.maxY() && second.minY() <= first.maxY()
                && first.minZ() <= second.maxZ() && second.minZ() <= first.maxZ();
    }

    /** Same dimension plus overlapping bounds; separate dimensions never conflict. */
    public static boolean conflicts(boolean sameDimension, BattleZone.Bounds first, BattleZone.Bounds second) {
        return sameDimension && overlap(first, second);
    }
}
