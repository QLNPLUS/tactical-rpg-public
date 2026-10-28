package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRangeGeometryTest {
    private static final GridPos ORIGIN = new GridPos(10, 64, -10);

    @Test void meleeUsesNativeCardinalAdjacencyRatherThanADiscOrWeaponDisplayRadius() {
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 64, -10), 0, 0));
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(10, 65, -11), 0, 0));
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(9, 63, -10), 0, 0));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, ORIGIN, 0, 32));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 64, -9), 0, 32));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 66, -10), 0, 32));
    }

    @Test void rangedDistanceIsHorizontalWithAnInclusiveCircularBoundary() {
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(22, 72, 6), 1, 20));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(23, 64, 6), 1, 20));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(26, 64, 6), 1, 20));
    }

    @Test void perTargetNativeRangeControlsHeightBonusRatherThanAFixedBowConstant() {
        GridPos downhill = new GridPos(30, 60, -10);
        assertFalse(AttackRangeGeometry.contains(ORIGIN, downhill, 1, 16));
        assertTrue(AttackRangeGeometry.contains(ORIGIN, downhill, 1, 20));
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(34, 64, -10), 2, 24));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(35, 64, -10), 2, 24));
    }

    @Test void previewBoundsRemainFiniteEvenForCustomHugeRangesAndExtremeCoordinates() {
        assertTrue(AttackRangeGeometry.contains(ORIGIN, new GridPos(42, 56, -10), 2, Integer.MAX_VALUE));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(43, 64, -10), 2, Integer.MAX_VALUE));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 55, -10), 2, Integer.MAX_VALUE));
        assertFalse(AttackRangeGeometry.contains(new GridPos(Integer.MIN_VALUE, 64, 0),
                new GridPos(Integer.MAX_VALUE, 64, 0), 2, Integer.MAX_VALUE));
    }

    @Test void unsupportedWeaponModesAndNegativeRangesNeverProduceDistantTiles() {
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 64, -10), 3, 24));
        assertFalse(AttackRangeGeometry.contains(ORIGIN, new GridPos(11, 64, -10), 1, -1));
    }
}
