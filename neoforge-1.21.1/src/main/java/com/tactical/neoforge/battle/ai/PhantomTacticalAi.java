package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.AerialMovement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Own-turn-only approach/dive. The battle owner executes and charges a complete flight atomically. */
public final class PhantomTacticalAi implements TacticalMobAi {
    @Override
    public AiAction planTurn(AiContext context) {
        if (!context.flying() || context.moveCost() <= 0 || context.meleeCost() <= 0) return AiAction.endTurn();
        GridPos home = context.actorCell(), target = context.targetCell();
        if (context.ap() >= context.meleeCost() && canStrike(context, home)) return AiAction.of(AiAction.Kind.MELEE);
        int steps = context.ap() / context.moveCost();
        if (steps <= 0) return AiAction.endTurn();
        GridPos preferred = target.above();
        Comparator<GridPos> priority = Comparator.comparingInt((GridPos cell) -> cell.manhattanDistance(preferred))
                .thenComparing(Comparator.naturalOrder());
        List<GridPos> outbound = AerialMovement.approach(home, steps, from -> {
            List<GridPos> neighbors = new ArrayList<>(context.airNeighbors(from));
            neighbors.removeIf(cell -> cell.equals(target) || context.occupied(cell));
            neighbors.sort(priority);
            return neighbors;
        }, cell -> canStrike(context, cell), cell -> cell.manhattanDistance(preferred));
        if (outbound.isEmpty()) return AiAction.endTurn();
        GridPos attackCell = outbound.get(outbound.size() - 1);
        List<GridPos> roundTrip = roundTrip(home, outbound);
        if (canStrike(context, attackCell) && roundTrip.size() <= AerialMovement.MAX_PATH_STEPS
                && affordable(roundTrip.size(), context.moveCost(), context.meleeCost(), context.ap())
                && validReturn(context, roundTrip, outbound.size())) {
            return AiAction.dive(roundTrip, outbound.size() - 1);
        }
        // No free return and no partially committed dive. Spend only actual approach movement;
        // another turn can dive from this nearer airborne home after AP is replenished.
        return AiAction.move(outbound);
    }

    /** Approach movement is not a free attack; only a validated DIVE contains a mid-path strike. */
    @Override
    public AiAction afterMovement(AiContext context) { return AiAction.endTurn(); }

    private static boolean canStrike(AiContext context, GridPos cell) {
        return context.inMeleeRange(cell, context.targetCell()) && context.hasLineOfSight(cell, context.targetCell());
    }

    private static boolean validReturn(AiContext context, List<GridPos> path, int outboundSize) {
        for (int i = outboundSize; i < path.size(); i++) {
            GridPos next = path.get(i);
            if (context.occupied(next) || !context.airNeighbors(path.get(i - 1)).contains(next)) return false;
        }
        return true;
    }

    /** Pure helper: source excluded, turnaround occurs once, original home included exactly last. */
    public static List<GridPos> roundTrip(GridPos home, List<GridPos> outbound) {
        if (outbound.isEmpty()) return List.of();
        List<GridPos> result = new ArrayList<>(outbound);
        for (int i = outbound.size() - 2; i >= 0; i--) result.add(outbound.get(i));
        result.add(home);
        return List.copyOf(result);
    }

    /** Use long arithmetic so malicious adapter/input sizes cannot overflow AP validation. */
    public static boolean affordable(int pathSteps, int moveCost, int meleeCost, int ap) {
        return pathSteps > 0 && moveCost > 0 && meleeCost > 0
                && (long) pathSteps * moveCost + meleeCost <= ap;
    }
}
