package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Bow tactics use the owner's world/ballistic checks, never vanilla facing-dependent sight. */
public final class SkeletonTacticalAi implements TacticalMobAi {
    private static final double COMFORTABLE_DISTANCE = 4.0;
    private static final double PREFERRED_DISTANCE = 8.0;
    private static final double MAXIMUM_DISTANCE = 16.0;
    private static final int MAX_EXPANDED_CELLS = 128;
    private final BooleanSupplier hasBow;
    private final GenericMeleeAi melee = new GenericMeleeAi();
    private boolean shotResolved;

    public SkeletonTacticalAi(Mob actor) {
        this(() -> actor.isHolding(Items.BOW));
    }

    /** Allows decision tests without constructing a Minecraft world or mob. */
    SkeletonTacticalAi(BooleanSupplier hasBow) {
        this.hasBow = Objects.requireNonNull(hasBow);
    }

    @Override
    public AiAction planTurn(AiContext context) {
        shotResolved = false;
        if (!hasBow.getAsBoolean()) return melee.planTurn(context);
        boolean clearHere = context.canRangedAttack(context.actorCell());
        boolean canShootHere = context.rangedCost() > 0 && context.ap() >= context.rangedCost() && clearHere;
        if (canShootHere && distance(context.actorCell(), context.targetCell()) >= COMFORTABLE_DISTANCE) {
            return AiAction.of(AiAction.Kind.RANGED);
        }
        if (context.moveCost() <= 0 || context.ap() < context.moveCost()) {
            return canShootHere ? AiAction.of(AiAction.Kind.RANGED) : AiAction.endTurn();
        }

        // BFS finds shortest routes around cover, including lateral and retreat routes.
        // The context owns terrain/zone validity; do not assume a flat or unobstructed grid.
        int budget = context.ap() / context.moveCost();
        int shotBudget = context.rangedCost() > 0 && context.ap() >= context.rangedCost()
                ? (context.ap() - context.rangedCost()) / context.moveCost() : -1;
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depths = new HashMap<>();
        var queue = new ArrayDeque<GridPos>();
        GridPos start = context.actorCell();
        previous.put(start, start);
        depths.put(start, 0);
        queue.add(start);
        GridPos firingCell = canShootHere ? start : null;
        GridPos stagingCell = null;
        GridPos approachCell = start;
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < MAX_EXPANDED_CELLS) {
            GridPos cell = queue.removeFirst();
            int depth = depths.get(cell);
            if (betterPosition(cell, approachCell, context.targetCell(), depths)) approachCell = cell;
            boolean improvesShot = depth <= shotBudget && (firingCell == null
                    || betterPosition(cell, firingCell, context.targetCell(), depths));
            boolean improvesStaging = stagingCell == null
                    || betterPosition(cell, stagingCell, context.targetCell(), depths);
            if ((improvesShot || improvesStaging) && distance(cell, context.targetCell()) <= MAXIMUM_DISTANCE
                    && (cell.equals(start) ? clearHere : context.canRangedAttack(cell))) {
                if (improvesStaging) stagingCell = cell;
                if (improvesShot) firingCell = cell;
            }
            if (depth >= budget) continue;
            for (GridPos next : context.standableNeighbors(cell)) {
                if (next.equals(context.targetCell()) || context.occupied(next) || previous.containsKey(next)) continue;
                previous.put(next, cell);
                depths.put(next, depth + 1);
                queue.addLast(next);
            }
        }
        if (firingCell != null) {
            return firingCell.equals(start) ? AiAction.of(AiAction.Kind.RANGED)
                    : AiAction.move(path(previous, start, firingCell));
        }
        // No shot is affordable this turn: reach a clear future firing cell if possible.
        GridPos destination = stagingCell != null ? stagingCell : approachCell;
        return destination.equals(start) ? AiAction.endTurn() : AiAction.move(path(previous, start, destination));
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        if (!hasBow.getAsBoolean()) return melee.afterMovement(context);
        return canShoot(context) ? AiAction.of(AiAction.Kind.RANGED) : AiAction.endTurn();
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.RANGED) shotResolved = true;
    }

    private boolean canShoot(AiContext context) {
        return !shotResolved && context.rangedCost() > 0 && context.ap() >= context.rangedCost()
                && context.canRangedAttack(context.actorCell());
    }

    private static boolean betterPosition(GridPos candidate, GridPos current, GridPos target,
                                          Map<GridPos, Integer> depths) {
        double score = Math.abs(distance(candidate, target) - PREFERRED_DISTANCE);
        double oldScore = Math.abs(distance(current, target) - PREFERRED_DISTANCE);
        int comparison = Double.compare(score, oldScore);
        return comparison < 0 || comparison == 0 && depths.get(candidate) < depths.get(current);
    }

    private static double distance(GridPos from, GridPos to) {
        double dx = (double) from.x() - to.x();
        double dy = (double) from.y() - to.y();
        double dz = (double) from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<GridPos> path(Map<GridPos, GridPos> previous, GridPos start, GridPos end) {
        var reversed = new ArrayList<GridPos>();
        for (GridPos cell = end; !cell.equals(start); cell = previous.get(cell)) reversed.add(cell);
        java.util.Collections.reverse(reversed);
        return reversed;
    }
}
