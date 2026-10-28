package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlastWarningGeometryTest {
    @Test
    void standOffIncludesOneTwoAndDiagonalButExcludesSameCellAndHighLedges() {
        GridPos player = new GridPos(0, 64, 0);
        assertTrue(BlastWarningGeometry.inPreparationRange(new GridPos(1, 64, 0), player));
        assertTrue(BlastWarningGeometry.inPreparationRange(new GridPos(2, 65, 0), player));
        assertTrue(BlastWarningGeometry.inPreparationRange(new GridPos(1, 64, 1), player));
        assertFalse(BlastWarningGeometry.inPreparationRange(player, player));
        assertFalse(BlastWarningGeometry.inPreparationRange(new GridPos(2, 64, 1), player));
        assertFalse(BlastWarningGeometry.inPreparationRange(new GridPos(1, 66, 0), player));
    }

    @Test
    void poweredAndNbtRadiusUseVanillaStrengthWithoutTruncation() {
        assertEquals(6, BlastWarningGeometry.warningRadius(3, false));
        assertEquals(12, BlastWarningGeometry.warningRadius(3, true));
        assertEquals(20, BlastWarningGeometry.warningRadius(5, true));
        assertEquals(508, BlastWarningGeometry.warningRadius(127, true));
        assertEquals(0, BlastWarningGeometry.warningRadius(-1, true));
    }

    @Test
    void intersectingVoxelCornersAreIncludedEvenWhenBlockCenterIsOutside() {
        assertTrue(BlastWarningGeometry.intersectsBlock(0.5, 64, 0.5, 6, 6, 63, 0));
        assertFalse(BlastWarningGeometry.intersectsBlock(0.5, 64, 0.5, 6, 7, 63, 0));
        assertTrue(BlastWarningGeometry.intersectsBlock(0, 0, 0, Math.sqrt(3), 1, 1, 1));
        assertFalse(BlastWarningGeometry.intersectsBlock(0, 0, 0, 1.7, 1, 1, 1));
        assertFalse(BlastWarningGeometry.intersectsBlock(0, 0, 0, Double.NaN, 0, 0, 0));
    }

    @Test
    void distanceArithmeticDoesNotOverflowNearWorldBorder() {
        assertFalse(BlastWarningGeometry.inPreparationRange(new GridPos(30_000_000, 64, 0),
                new GridPos(-30_000_000, 64, 0)));
    }
}
