package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridDirection;
import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Bounded route search that can tell "no way at all" apart from "the only way is through a closed wooden door".
 *
 * <p>The general {@code GridNavigator.path} cannot answer this question: an empty list there also means "the
 * search budget ran out", and the terrain adapter refuses every non-empty collision shape including the thin
 * leaf of an already open door. This planner therefore asks three separate questions and reports which one it
 * answered:</p>
 * <ol>
 *   <li><b>open route</b> - a complete route that never crosses a closed door (an already open door is ordinary
 *       terrain, decided by the real body sweep in {@link Terrain#canEnter});</li>
 *   <li><b>door route</b> - a complete route that may cross closed wooden doors, each edge still keeping walls,
 *       head clearance, liquid, support, step height, the battle zone and other units;</li>
 *   <li><b>nothing</b> - either proven absent ({@link Status#UNREACHABLE}) or simply not decided inside the
 *       budget ({@link Status#LIMIT_REACHED}), which must never be read as "the only way is a door".</li>
 * </ol>
 *
 * <p>Nothing here touches a world: the terrain, the zone, the occupants and the door pairs all arrive through
 * {@link Terrain}, so every branch is decidable in a pure test. The search is AP-independent on purpose - a
 * route that this turn's AP cannot walk yet still counts as a route, and only the door search explains which
 * door a turn would have to break first.</p>
 */
public final class DoorRoutePlanner {
    /** What the search really answered. */
    public enum Status {
        /** A complete route to an engagement cell exists. */
        FOUND,
        /** The exhaustive local search finished and there is no such route. */
        UNREACHABLE,
        /** The search hit its budget or the edge of the loaded area; nothing was proven. */
        LIMIT_REACHED
    }

    /** One closed door pair: the lower block a unit stands in and the upper block over its head. */
    public record Door(GridPos lower, GridPos upper) {
        public Door {
            Objects.requireNonNull(lower, "lower");
            Objects.requireNonNull(upper, "upper");
            if (!upper.equals(lower.above())) {
                throw new IllegalArgumentException("a door pair is two vertically adjacent blocks");
            }
        }

        public static Door of(GridPos lower) { return new Door(lower, lower.above()); }

        /** True when this cell is one of the two blocks of the pair. */
        public boolean holds(GridPos cell) { return lower.equals(cell) || upper.equals(cell); }
    }

    /**
     * The answer of one search.
     *
     * @param status    what was really proven
     * @param path      the cells after the start, up to and including the engagement cell; empty unless found
     * @param firstDoor the first closed door the route crosses, or {@code null} when it crosses none
     */
    public record Route(Status status, List<GridPos> path, Door firstDoor) {
        public static final Route UNREACHABLE = new Route(Status.UNREACHABLE, List.of(), null);
        public static final Route LIMIT_REACHED = new Route(Status.LIMIT_REACHED, List.of(), null);

        public Route {
            Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
        }

        public boolean found() { return status == Status.FOUND && !path.isEmpty(); }
    }

    /**
     * Everything the search may know about the world, and nothing more.
     *
     * <p>{@link #canEnter} and {@link #canEnterThroughDoor} carry the whole physical decision - block
     * collisions of the real body including the leaf of an open door, the swept step between two cells, fluid,
     * support, step height, the battle zone and other units - so the planner stays a pure graph search.</p>
     */
    public interface Terrain {
        /**
         * The cell in column {@code (x, z)} this actor could occupy nearest {@code refY}, or {@code null}.
         *
         * <p>A cell whose feet are the lower half of a closed wooden door is reported as well: the caller asks
         * {@link #closedDoorAt} before treating it as walkable.</p>
         */
        GridPos standCell(int x, int z, int refY);

        /** Can this actor really make the one-cell step from {@code from} to {@code to}? */
        boolean canEnter(GridPos from, GridPos to);

        /** The closed wooden door standing in this cell, or {@code null} when it is not one. */
        Door closedDoorAt(GridPos cell);

        /** Could this actor make the step if exactly that closed door pair were gone, and nothing else changed? */
        boolean canEnterThroughDoor(GridPos from, GridPos to, Door door);
    }

    /** Default local search budget; the same order as the battle's own approach search. */
    public static final int DEFAULT_BUDGET = 4096;

    private DoorRoutePlanner() { }

    /** A complete route that crosses no closed door. */
    public static Route open(Terrain terrain, GridPos start, Predicate<GridPos> engagementCell, int budget) {
        return search(terrain, start, engagementCell, budget, false);
    }

    /** A complete route that may cross closed wooden doors, reporting the first one it meets. */
    public static Route throughDoors(Terrain terrain, GridPos start, Predicate<GridPos> engagementCell, int budget) {
        return search(terrain, start, engagementCell, budget, true);
    }

    /**
     * The single breadth-first search behind both questions.
     *
     * <p>Breadth first keeps the answer deterministic and makes the first door the one a turn would really
     * reach first. Cells are visited once, so an open route that exists is preferred automatically: a door edge
     * costs a door, but the search never needs a metric to prefer the free one.</p>
     */
    private static Route search(Terrain terrain, GridPos start, Predicate<GridPos> engagementCell,
                                int budget, boolean crossDoors) {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(engagementCell, "engagementCell");
        if (budget <= 0) return Route.LIMIT_REACHED;
        Map<GridPos, GridPos> cameFrom = new HashMap<>();
        Set<GridPos> seen = new HashSet<>();
        Deque<GridPos> open = new ArrayDeque<>();
        seen.add(start);
        open.add(start);
        int expanded = 0;
        while (!open.isEmpty()) {
            // The visited set and this budget are the only bounds: there is no extra door count, because a
            // route that crosses several doors is still a route - this turn would break the first one and the
            // next own turn would recompute. Inventing a cap here would both lose real routes and, if it
            // silently stopped the search, claim "no way" for a way that exists.
            if (expanded++ >= budget) return Route.LIMIT_REACHED;
            GridPos current = open.removeFirst();
            for (GridDirection direction : GridDirection.values()) {
                GridPos next = terrain.standCell(current.x() + direction.dx, current.z() + direction.dz, current.y());
                if (next == null || !GridMovementRules.canStep(current, next) || next.equals(start)) continue;
                Door door = terrain.closedDoorAt(next);
                if (door != null) {
                    if (!crossDoors || !terrain.canEnterThroughDoor(current, next, door)) continue;
                } else if (!terrain.canEnter(current, next)) {
                    continue;
                }
                if (!seen.add(next)) continue;
                cameFrom.put(next, current);
                if (engagementCell.test(next)) {
                    List<GridPos> path = rebuild(cameFrom, start, next);
                    return new Route(Status.FOUND, path, firstDoor(terrain, path));
                }
                open.addLast(next);
            }
        }
        return Route.UNREACHABLE;
    }

    private static List<GridPos> rebuild(Map<GridPos, GridPos> cameFrom, GridPos start, GridPos goal) {
        List<GridPos> reversed = new ArrayList<>();
        for (GridPos cursor = goal; cursor != null && !cursor.equals(start); cursor = cameFrom.get(cursor)) {
            reversed.add(cursor);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /** The door a turn would have to break first, i.e. the first closed door along the route. */
    private static Door firstDoor(Terrain terrain, List<GridPos> path) {
        for (GridPos cell : path) {
            Door door = terrain.closedDoorAt(cell);
            if (door != null) return door;
        }
        return null;
    }
}
