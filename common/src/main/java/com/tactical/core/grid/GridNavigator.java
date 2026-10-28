package com.tactical.core.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

/** Breadth-first reachability and shortest-AP pathfinding over {@link GridWorld}. */
public final class GridNavigator {

    private final GridWorld world;

    public GridNavigator(GridWorld world) {
        this.world = world;
    }

    /** The four orthogonal neighbours that a 1x1x2 entity can step onto. */
    public List<GridPos> neighbours(GridPos from) {
        List<GridPos> out = new ArrayList<>(4);
        for (GridDirection direction : GridDirection.values()) {
            GridPos cell = world.standCell(from.x() + direction.dx, from.z() + direction.dz, from.y());
            if (cell != null && GridMovementRules.canStep(from, cell) && world.isPassable(cell)) {
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * Cells reachable within {@code maxSteps} moves.
     *
     * @return cell to step count, excluding the start cell, in discovery order
     */
    public Map<GridPos, Integer> reachable(GridPos start, int maxSteps, Predicate<GridPos> passable) {
        return reachable(start, maxSteps, passable, (from, to) -> true);
    }

    /** Reachability using the same physical edge checks as path planning. */
    public Map<GridPos, Integer> reachable(GridPos start, int maxSteps, Predicate<GridPos> passable,
                                            BiPredicate<GridPos, GridPos> edgeAllowed) {
        Map<GridPos, Integer> distance = new LinkedHashMap<>();
        if (maxSteps <= 0) {
            return distance;
        }
        Deque<GridPos> open = new ArrayDeque<>();
        Set<GridPos> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty()) {
            GridPos current = open.poll();
            int steps = distance.getOrDefault(current, 0);
            if (steps >= maxSteps) {
                continue;
            }
            for (GridPos neighbour : neighbours(current)) {
                if (!edgeAllowed.test(current, neighbour)) continue;
                if (!seen.add(neighbour) || !passable.test(neighbour)) {
                    continue;
                }
                distance.put(neighbour, steps + 1);
                open.add(neighbour);
            }
        }
        return distance;
    }

    /**
     * Shortest-AP path from {@code start} to {@code goal}.
     *
     * @return the cells after {@code start} up to and including {@code goal},
     *         or an empty list when no path exists
     */
    public List<GridPos> path(GridPos start, GridPos goal, Predicate<GridPos> passable, int maxExpanded) {
        return path(start, goal, passable, maxExpanded, false);
    }

    /** Only AI approach paths may target an occupied cell; callers must stop before it. */
    public List<GridPos> path(GridPos start, GridPos goal, Predicate<GridPos> passable,
                              int maxExpanded, boolean allowOccupiedGoal) {
        return path(start, goal, passable, maxExpanded, allowOccupiedGoal, (from,to) -> true);
    }

    public List<GridPos> path(GridPos start, GridPos goal, Predicate<GridPos> passable,
                              int maxExpanded, boolean allowOccupiedGoal,
                              BiPredicate<GridPos,GridPos> edgeAllowed) {
        if (start.equals(goal) || (!allowOccupiedGoal && !passable.test(goal))) {
            return List.of();
        }
        // Every step costs the same AP, including a one-block ascent/descent.
        // BFS matches reachable() exactly; turn penalties used to choose longer,
        // unaffordable paths to cells that the reachable overlay advertised.
        Map<GridPos, GridPos> cameFrom = new HashMap<>();
        Set<GridPos> seen = new HashSet<>();
        Deque<GridPos> open = new ArrayDeque<>();
        open.add(start);
        seen.add(start);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < maxExpanded) {
            GridPos current = open.remove();
            for (GridPos neighbour : neighbours(current)) {
                if (!edgeAllowed.test(current, neighbour)) continue;
                if (!seen.add(neighbour) || (!passable.test(neighbour)
                        && !(allowOccupiedGoal && neighbour.equals(goal)))) {
                    continue;
                }
                cameFrom.put(neighbour, current);
                if (neighbour.equals(goal)) {
                    return rebuild(cameFrom, start, goal);
                }
                open.add(neighbour);
            }
        }
        return List.of();
    }

    private static List<GridPos> rebuild(Map<GridPos, GridPos> cameFrom, GridPos start, GridPos goal) {
        List<GridPos> reversed = new ArrayList<>();
        GridPos cursor = goal;
        while (cursor != null && !cursor.equals(start)) {
            reversed.add(cursor);
            cursor = cameFrom.get(cursor);
        }
        if (cursor == null) {
            return List.of();
        }
        Collections.reverse(reversed);
        return reversed;
    }

}
