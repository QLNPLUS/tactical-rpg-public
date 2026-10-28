package com.tactical.core.grid;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GridTrajectoryTest {
    @Test void interpolatesEachCornerAndDownhillStepWithoutTeleporting() {
        GridTrajectory path = new GridTrajectory(new GridPos(0, 65, 0),
                List.of(new GridPos(1, 65, 0), new GridPos(1, 64, 1)), 4);
        assertEquals(8, path.duration());
        assertEquals(new GridTrajectory.Position(0.5, 65, 0.5), path.sample(0));
        assertEquals(new GridTrajectory.Position(1, 65, 0.5), path.sample(2));
        assertEquals(new GridTrajectory.Position(1.5, 65, 0.5), path.sample(4));
        assertEquals(new GridTrajectory.Position(1.5, 65, 1.5), path.sample(6));
        assertEquals(new GridTrajectory.Position(1.5, 64, 1.5), path.sample(8));
        assertEquals(path.sample(8), path.sample(100));
        assertEquals(path.sample(0), path.sample(-100));
        assertEquals(-90F, path.yaw(0));
        assertEquals(0F, path.yaw(4));
        assertEquals(0F, path.yaw(100));
    }

    @Test void facesAllFourDirectionsAndRejectsNonAdjacentMovement() {
        GridPos origin = new GridPos(0, 64, 0);
        assertEquals(-90F, GridMovementRules.yaw(origin, new GridPos(1, 64, 0)));
        assertEquals(90F, GridMovementRules.yaw(origin, new GridPos(-1, 64, 0)));
        assertEquals(180F, GridMovementRules.yaw(origin, new GridPos(0, 64, -1)));
        assertEquals(0F, GridMovementRules.yaw(origin, new GridPos(0, 63, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new GridTrajectory(origin, List.of(new GridPos(2, 64, 0)), 3));
    }

    @Test void ordinaryVerticalStepsClearTheHigherSupportInEveryDirectionWithoutExtraHeadroom() {
        GridPos high = new GridPos(0, 10, 0);
        for (GridDirection direction : GridDirection.values()) {
            GridPos low = new GridPos(direction.dx, 9, direction.dz);
            for (int i = 0; i <= 100; i++) {
                double progress = i / 100.0;
                for (var position : List.of(GridTrajectory.sampleStep(high, low, progress),
                        GridTrajectory.sampleStep(low, high, progress))) {
                    // A standing player's feet must never enter the solid higher support block.
                    assertFalse(intersectsSupport(position, high), "support collision at " + progress);
                    assertTrue(position.y() >= 9 && position.y() <= 10);
                    assertTrue(position.y() + 1.8 <= 12, "ordinary steps need no jump headroom");
                }
            }
            assertEquals(new GridTrajectory.Position(0.5, 10, 0.5), GridTrajectory.sampleStep(high, low, 0));
            assertEquals(new GridTrajectory.Position(low.x() + 0.5, 9, low.z() + 0.5),
                    GridTrajectory.sampleStep(high, low, 1));
            assertEquals(new GridTrajectory.Position(low.x() + 0.5, 9, low.z() + 0.5),
                    GridTrajectory.sampleStep(low, high, 0));
            assertEquals(new GridTrajectory.Position(0.5, 10, 0.5), GridTrajectory.sampleStep(low, high, 1));
        }
    }

    private static boolean intersectsSupport(GridTrajectory.Position feet, GridPos support) {
        return feet.x() + 0.3 > support.x() && feet.x() - 0.3 < support.x() + 1
                && feet.z() + 0.3 > support.z() && feet.z() - 0.3 < support.z() + 1
                && feet.y() < support.y() && feet.y() + 1.8 > support.y() - 1;
    }

    @Test void directDropKeepsItsHopWhileOrdinaryDescentDoesNot() {
        var high = new GridPos(0, 10, 0);
        var low = new GridPos(1, 9, 0);
        assertEquals(10, GridTrajectory.sampleStep(high, low, 0.5).y());
        assertEquals(10.25, new GridTrajectory(high, List.of(low), 8, true).sample(4).y());
        assertEquals(GridTrajectory.sampleStep(high, low, 0), GridTrajectory.sampleStep(high, low, -1));
        assertEquals(GridTrajectory.sampleStep(high, low, 1), GridTrajectory.sampleStep(high, low, 2));
    }

    @Test void explicitDropRejectsEmptyFlatUphillLongAndMultipleSegments() {
        var origin = new GridPos(0, 64, 0);
        for (var invalid : List.of(List.<GridPos>of(), List.of(new GridPos(1, 64, 0)),
                List.of(new GridPos(1, 65, 0)), List.of(new GridPos(1, 47, 0)),
                List.of(new GridPos(1, 63, 0), new GridPos(2, 63, 0)))) {
            assertThrows(IllegalArgumentException.class, () -> new GridTrajectory(origin, invalid, 3, true));
        }
        assertDoesNotThrow(() -> new GridTrajectory(origin, List.of(new GridPos(1, 48, 0)), 48, true));
    }
}
