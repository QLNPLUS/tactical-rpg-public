package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The door route search without a world: which of the three answers it may give, and which door a route
 * would have to break first.
 *
 * <p>Every case builds the same flat grid the encounter would describe - walkable cells, solid cells and
 * closed wooden door pairs - so a passing test means "this route question has this answer", never "a real door
 * was removed". The real removal, the protection chain and the terrain refresh belong to the dedicated-server
 * fixture.</p>
 */
class DoorRoutePlannerTest {
    private static final int FLOOR_Y = 64;

    // ---- the approved route questions ---------------------------------------------------------------

    @Test
    void aClearApproachIsFoundWithoutMeetingAnyDoor() {
        Grid grid = new Grid();
        grid.floorRow(0, 5);
        DoorRoutePlanner.Route route = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, route.status());
        assertEquals(5, route.path().size(), "every cell between the actor and the opponent's neighbour");
        assertNull(route.firstDoor(), "an open approach never names a door");
    }

    @Test
    void anAlreadyOpenDoorwayIsOrdinaryTerrain() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        // An open door: the cell is really walkable, so it is floor and no door pair at all.
        DoorRoutePlanner.Route route = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, route.status());
        assertNull(route.firstDoor());
    }

    @Test
    void aNormalDetourWinsOverTheOnlyDoor() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        // A closed door on the straight line; the way round is longer but needs no door.
        grid.door(3, 0);
        grid.floor(2, 1);
        grid.floor(3, 1);
        grid.floor(4, 1);
        DoorRoutePlanner.Route open = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, open.status());
        assertNull(open.firstDoor(), "the detour is a real route, so nothing is broken");
        assertTrue(open.path().size() > 6, "the detour is longer than the blocked straight line");
    }

    @Test
    void aRouteThatNeedsOneDoorIsReportedWithThatDoor() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        grid.door(3, 0);
        DoorRoutePlanner.Route open = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.UNREACHABLE, open.status(), "the open search never walks a closed door");
        DoorRoutePlanner.Route doors = DoorRoutePlanner.throughDoors(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, doors.status());
        assertEquals(DoorRoutePlanner.Door.of(cell(3)), doors.firstDoor());
        // The door is the third step: two real steps first, then the pair itself.
        assertEquals(cell(3), doors.path().get(2));
    }

    @Test
    void aDoorInFrontOfASolidWallIsNotARoute() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        grid.door(3, 0);
        grid.block(4, 0);
        assertEquals(DoorRoutePlanner.Status.UNREACHABLE,
                DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512).status());
        DoorRoutePlanner.Route doors = DoorRoutePlanner.throughDoors(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.UNREACHABLE, doors.status(),
                "a solid wall behind the door is no route, so the door is never touched");
        assertNull(doors.firstDoor());
    }

    @Test
    void twoDoorsOnTheOnlyWayReportTheFirstOneAndLeaveTheSecond() {
        Grid grid = new Grid();
        grid.floorRow(0, 8);
        grid.door(2, 0);
        grid.door(5, 0);
        DoorRoutePlanner.Route doors = DoorRoutePlanner.throughDoors(grid, cell(0), grid.engagement(cell(8)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, doors.status());
        assertEquals(DoorRoutePlanner.Door.of(cell(2)), doors.firstDoor(),
                "only the door this turn can reach is named");
    }

    @Test
    void aLongDoorChainIsARouteNotAnAbsentRoute() {
        Grid grid = new Grid();
        grid.floorRow(0, 12);
        for (int x = 1; x <= 5; x++) grid.door(x, 0);
        DoorRoutePlanner.Route doors = DoorRoutePlanner.throughDoors(grid, cell(0), grid.engagement(cell(12)), 4096);
        assertEquals(DoorRoutePlanner.Status.FOUND, doors.status(),
                "five doors in a row are still a route: this turn breaks the first and the next turn recomputes");
        assertEquals(DoorRoutePlanner.Door.of(cell(1)), doors.firstDoor());
    }

    @Test
    void twoAdjacentDoorsAreWalkedOneAfterTheOther() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        grid.door(2, 0);
        grid.door(3, 0);
        DoorRoutePlanner.Route doors = DoorRoutePlanner.throughDoors(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, doors.status(),
                "stepping out of one doorway into the next is legal in the virtual world the route assumes");
        assertEquals(DoorRoutePlanner.Door.of(cell(2)), doors.firstDoor(),
                "only the first door of the pair is this turn's object");
        assertEquals(cell(2), doors.path().get(1), "the first doorway is the second step of the route");
        assertEquals(cell(3), doors.path().get(2), "and the route really walks out of it into the next one");
    }

    @Test
    void aBudgetThatRunsOutIsUndecidedAndNotNoRoute() {
        Grid grid = new Grid();
        grid.floorRow(0, 40);
        DoorRoutePlanner.Route route = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(40)), 2);
        assertEquals(DoorRoutePlanner.Status.LIMIT_REACHED, route.status());
        assertTrue(route.path().isEmpty(), "an undecided search never hands out a partial route");
        assertNull(route.firstDoor());
    }

    @Test
    void theActorNeverWalksIntoTheCellTheOpponentStandsIn() {
        Grid grid = new Grid();
        grid.floorRow(0, 6);
        DoorRoutePlanner.Route route = DoorRoutePlanner.open(grid, cell(0), grid.engagement(cell(6)), 512);
        assertEquals(DoorRoutePlanner.Status.FOUND, route.status());
        assertEquals(cell(5), route.path().get(route.path().size() - 1));
    }

    // ---- the typed route snapshot -------------------------------------------------------------------

    @Test
    void theRouteSnapshotNamesTheDoorOnlyWhenTheActorStandsInFrontOfIt() {
        DoorRoutePlanner.Door door = DoorRoutePlanner.Door.of(cell(3));
        DoorRoutePlanner.Route doors = new DoorRoutePlanner.Route(DoorRoutePlanner.Status.FOUND,
                List.of(cell(1), cell(2), cell(3), cell(4), cell(5)), door);
        VindicatorRoute route = new VindicatorRoute(DoorRoutePlanner.Route.UNREACHABLE, doors, true, 10);
        assertEquals(door, route.doorAhead(cell(2)), "the cell right before the door may break it");
        assertNull(route.doorAhead(cell(1)), "two cells away is not at the door yet");
        assertNull(route.doorAhead(cell(4)), "behind the door is not the front side");
        assertEquals(2, route.stepsToFirstDoor());
    }

    @Test
    void aDoorRouteThatWasNotProvenNamesNoDoor() {
        VindicatorRoute route = new VindicatorRoute(DoorRoutePlanner.Route.UNREACHABLE,
                DoorRoutePlanner.Route.LIMIT_REACHED, true, 10);
        assertNull(route.doorAhead(cell(2)));
        assertEquals(-1, route.stepsToFirstDoor());
    }

    // ---- a flat grid the search may walk ------------------------------------------------------------

    private static GridPos cell(int x) { return new GridPos(x, FLOOR_Y, 0); }

    private static GridPos cell(int x, int z) { return new GridPos(x, FLOOR_Y, z); }

    /** A one-layer grid: walkable cells, solid cells and closed wooden door pairs, nothing else. */
    private static final class Grid implements DoorRoutePlanner.Terrain {
        private final Set<GridPos> floor = new LinkedHashSet<>();
        private final Set<GridPos> solid = new HashSet<>();
        private final Map<GridPos, DoorRoutePlanner.Door> doors = new HashMap<>();

        void floor(int x, int z) { floor.add(cell(x, z)); }

        void floorRow(int from, int to) { for (int x = from; x <= to; x++) floor(x, 0); }

        void block(int x, int z) { solid.add(cell(x, z)); }

        void door(int x, int z) {
            GridPos feet = cell(x, z);
            doors.put(feet, DoorRoutePlanner.Door.of(feet));
        }

        /** Any cell from which that opponent could be struck, i.e. one step away and never its own cell. */
        java.util.function.Predicate<GridPos> engagement(GridPos opponent) {
            return candidate -> !candidate.equals(opponent) && candidate.manhattanDistance(opponent) == 1;
        }

        @Override
        public GridPos standCell(int x, int z, int refY) {
            GridPos candidate = cell(x, z);
            if (solid.contains(candidate)) return null;
            return floor.contains(candidate) || doors.containsKey(candidate) ? candidate : null;
        }

        @Override
        public boolean canEnter(GridPos from, GridPos to) {
            if (solid.contains(to) || !floor.contains(to)) return false;
            // Standing in a door cell means that pair is already assumed gone for the rest of the route.
            return !doors.containsKey(to);
        }

        @Override
        public DoorRoutePlanner.Door closedDoorAt(GridPos cell) { return doors.get(cell); }

        @Override
        public boolean canEnterThroughDoor(GridPos from, GridPos to, DoorRoutePlanner.Door door) {
            return door != null && door.equals(doors.get(to)) && !solid.contains(to);
        }
    }
}
