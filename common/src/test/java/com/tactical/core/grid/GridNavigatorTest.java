package com.tactical.core.grid;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GridNavigatorTest {

    /** Flat ground at y = 64 with optional blocked columns. */
    private static final class FlatWorld implements GridWorld {
        final Set<GridPos> blocked = new HashSet<>();
        final Set<GridPos> raised = new HashSet<>();

        @Override
        public GridPos standCell(int x, int z, int refY) {
            int y = raised.contains(new GridPos(x, 64, z)) ? 65 : 64;
            GridPos cell = new GridPos(x, y, z);
            if (blocked.contains(new GridPos(x, 64, z))) {
                return null;
            }
            return cell;
        }
    }

    @Test
    void reachableDiamondOnOpenGround() {
        FlatWorld world = new FlatWorld();
        GridNavigator navigator = new GridNavigator(world);

        Map<GridPos, Integer> reachable = navigator.reachable(new GridPos(0, 64, 0), 3, cell -> true);

        // Manhattan ball of radius 3 has 25 cells; the start is excluded.
        assertEquals(24, reachable.size());
        assertEquals(1, reachable.get(new GridPos(1, 64, 0)));
        assertEquals(3, reachable.get(new GridPos(3, 64, 0)));
        assertTrue(reachable.values().stream().allMatch(steps -> steps >= 1 && steps <= 3));
    }

    @Test
    void pathIsShortestOnOpenGround() {
        FlatWorld world = new FlatWorld();
        GridNavigator navigator = new GridNavigator(world);

        List<GridPos> path = navigator.path(new GridPos(0, 64, 0), new GridPos(2, 64, 0), cell -> true, 512);

        assertEquals(2, path.size());
        assertEquals(new GridPos(2, 64, 0), path.get(1));
    }

    @Test
    void wallForcesDetour() {
        FlatWorld world = new FlatWorld();
        // Wall at x = 1 spanning z = -3..3
        for (int z = -3; z <= 3; z++) {
            world.blocked.add(new GridPos(1, 64, z));
        }
        GridNavigator navigator = new GridNavigator(world);

        List<GridPos> path = navigator.path(new GridPos(0, 64, 0), new GridPos(2, 64, 0), cell -> true, 512);

        assertTrue(path.size() > 2, "must walk around the wall");
        assertTrue(path.contains(new GridPos(1, 64, 4)) || path.contains(new GridPos(1, 64, -4)),
                "detour should cross the gap beside the wall: " + path);
    }

    @Test
    void blockedGoalIsUnreachable() {
        FlatWorld world = new FlatWorld();
        world.blocked.add(new GridPos(1, 64, 0));
        GridNavigator navigator = new GridNavigator(world);

        assertTrue(navigator.path(new GridPos(0, 64, 0), new GridPos(1, 64, 0), cell -> true, 64).isEmpty());
    }

    @Test
    void oneBlockDownIsAnEscapeWhenThreeOtherNeighboursAreOccupied() {
        GridPos start = new GridPos(0, 65, 0);
        GridPos exit = new GridPos(-1, 64, 0);
        Set<GridPos> occupied = Set.of(new GridPos(1, 65, 0),
                new GridPos(0, 65, 1), new GridPos(0, 65, -1));
        GridWorld ledge = (x, z, refY) -> x == -1 && z == 0
                ? exit : new GridPos(x, 65, z);
        GridNavigator navigator = new GridNavigator(ledge);

        assertEquals(Map.of(exit, 1), navigator.reachable(start, 1, cell -> !occupied.contains(cell)));
        assertEquals(List.of(exit), navigator.path(start, exit, cell -> !occupied.contains(cell), 64));
        assertEquals(List.of(start), navigator.path(exit, start, cell -> true, 64),
                "the same ledge can be climbed by one block");
    }

    @Test
    void invalidHeightChangesAndDiagonalShortcutsAreRejected() {
        GridPos start = new GridPos(0, 65, 0);
        for (int y : new int[] {63, 67}) {
            GridNavigator navigator = new GridNavigator((x, z, refY) -> new GridPos(x, y, z));
            assertTrue(navigator.neighbours(start).isEmpty());
            assertTrue(navigator.reachable(start, 2, cell -> true).isEmpty());
        }
        assertTrue(!GridMovementRules.canStep(start, new GridPos(1, 64, 1)));
    }

    @Test
    void occupiedGoalRequiresExplicitAiApproachOptIn() {
        GridNavigator navigator = new GridNavigator(new FlatWorld());
        GridPos start = new GridPos(0, 64, 0), goal = new GridPos(1, 64, 0);
        assertTrue(navigator.path(start, goal, cell -> !cell.equals(goal), 64).isEmpty());
        assertEquals(List.of(goal), navigator.path(start, goal, cell -> !cell.equals(goal), 64, true));
    }

    @Test
    void deathUnblocksReachabilityWithoutRecreatingNavigator() {
        GridNavigator navigator = new GridNavigator(new FlatWorld());
        GridPos start = new GridPos(0, 64, 0), enemy = new GridPos(1, 64, 0);
        Set<GridPos> occupied = new HashSet<>(Set.of(enemy));
        assertTrue(!navigator.reachable(start, 1, cell -> !occupied.contains(cell)).containsKey(enemy));
        occupied.remove(enemy);
        assertEquals(1, navigator.reachable(start, 1, cell -> !occupied.contains(cell)).get(enemy));
    }

    @Test
    void advertisedCellsAlwaysHaveAnAffordableShortestPathIncludingHeightChanges() {
        FlatWorld world = new FlatWorld();
        world.raised.add(new GridPos(1, 64, 0));
        world.raised.add(new GridPos(2, 64, 0));
        world.blocked.add(new GridPos(0, 64, 1));
        GridNavigator navigator = new GridNavigator(world);
        GridPos start = new GridPos(0, 64, 0);
        navigator.reachable(start, 5, cell -> true).forEach((goal, steps) ->
                assertEquals(steps, navigator.path(start, goal, cell -> true, 2048).size(), goal.toString()));
    }

    @Test
    void walkablePredicateIsRespected() {
        FlatWorld world = new FlatWorld();
        GridNavigator navigator = new GridNavigator(world);

        // Treat the whole east corridor as occupied.
        Map<GridPos, Integer> reachable = navigator.reachable(new GridPos(0, 64, 0), 2,
                cell -> cell.x() <= 0);

        assertTrue(reachable.keySet().stream().noneMatch(cell -> cell.x() > 0));
    }
}
