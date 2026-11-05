package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blaze tactics: native melee in real reach, otherwise its own native fireball salvo, otherwise a bounded move
 * to a cell it can really shoot from.
 *
 * <p>A blaze is not a bow user. It holds nothing, vanilla has no callable ranged method for it, and its own
 * distance gate is the entity-to-entity one from {@code BlazeAttackGoal} - not grid adjacency, which an aerial
 * actor would otherwise satisfy diagonally from a cell it cannot touch anybody from. So this adapter asks the
 * context two separate questions: "can the body really melee from here" and "is a real fireball legal from
 * here".</p>
 *
 * <p>The adapter owns no world state and no timer. The charge, the three shots, the price and every refusal
 * arrive from the same server state that charges the action, so a plan can be decided in a pure test and can
 * never price a salvo the execution path then prices differently.</p>
 */
public final class BlazeTacticalAi implements TacticalMobAi {
    /** Mod design ceiling for this ruleset; the real follow range is applied on top of it, never instead of it. */
    public static final double MAXIMUM_RANGE = 16.0D;
    /** Comfortable band: close enough to land, far enough not to be traded blow for blow. */
    public static final double PREFERRED_MIN = 6.0D;
    public static final double PREFERRED_MAX = 12.0D;
    private static final double PREFERRED_MIDDLE = (PREFERRED_MIN + PREFERRED_MAX) / 2.0D;
    /** Same bounded search budget the other aerial/ranged adapters use. */
    private static final int MAX_EXPANDED_CELLS = 128;

    private boolean volleyResolved;

    @Override
    public AiAction planTurn(AiContext context) {
        volleyResolved = false;
        if (canMelee(context, context.actorCell())) return AiAction.of(AiAction.Kind.MELEE);
        if (canVolley(context, context.actorCell())) return AiAction.fireballVolley();
        return reposition(context);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        if (volleyResolved) return AiAction.endTurn();
        if (canMelee(context, context.actorCell())) return AiAction.of(AiAction.Kind.MELEE);
        return canVolley(context, context.actorCell()) ? AiAction.fireballVolley() : AiAction.endTurn();
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.FIREBALL_VOLLEY) volleyResolved = true;
    }

    /** Real reach, real line of sight: grid adjacency alone never extends a blaze's melee. */
    private static boolean canMelee(AiContext context, GridPos cell) {
        return context.meleeCost() > 0 && context.ap() >= context.meleeCost()
                && context.inMeleeRange(cell, context.targetCell()) && context.inNativeMeleeReach(cell);
    }

    private static boolean canVolley(AiContext context, GridPos cell) {
        return context.fireballCost() > 0 && context.ap() >= context.fireballCost()
                && context.canFireballFrom(cell);
    }

    /**
     * One bounded search for a cell a salvo is legal from, reserving this turn's price.
     *
     * <p>Nothing is spent when the salvo cannot be paid for: an under-priced actor keeps its resources and ends
     * its own turn instead of wandering into a position it still could not shoot from. The search never passes
     * through the opponent or an occupied cell and never exceeds the normal air/ground neighbour rule of the
     * battle, so no route can leave the battlefield or loop.</p>
     */
    private static AiAction reposition(AiContext context) {
        int moveCost = context.moveCost();
        int volleyCost = context.fireballCost();
        if (moveCost <= 0 || volleyCost <= 0 || context.ap() < volleyCost) return AiAction.endTurn();
        int budget = (context.ap() - volleyCost) / moveCost;
        List<GridPos> firing = firingRoute(context, budget);
        return firing.isEmpty() ? AiAction.endTurn() : AiAction.move(firing);
    }

    private static List<GridPos> firingRoute(AiContext context, int budget) {
        if (budget <= 0) return List.of();
        GridPos start = context.actorCell();
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depth = new HashMap<>();
        var queue = new ArrayDeque<GridPos>();
        previous.put(start, start);
        depth.put(start, 0);
        queue.add(start);
        GridPos best = null;
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < MAX_EXPANDED_CELLS) {
            GridPos cell = queue.removeFirst();
            int steps = depth.get(cell);
            if (!cell.equals(start) && context.canFireballFrom(cell)
                    && (best == null || better(cell, best, context, depth))) best = cell;
            if (steps >= budget) continue;
            for (GridPos next : context.standableNeighbors(cell)) {
                if (next.equals(context.targetCell()) || context.occupied(next)
                        || previous.containsKey(next)) continue;
                previous.put(next, cell);
                depth.put(next, steps + 1);
                queue.addLast(next);
            }
        }
        return best == null ? List.of() : path(previous, start, best);
    }

    private static boolean better(GridPos candidate, GridPos current, AiContext context,
                                  Map<GridPos, Integer> depth) {
        int comparison = Double.compare(score(candidate, context), score(current, context));
        return comparison < 0 || comparison == 0 && depth.get(candidate) < depth.get(current);
    }

    /** Comfortable band first, then the closest distance to its middle; nearer-in-steps wins a tie. */
    private static double score(GridPos cell, AiContext context) {
        double distance = distance(cell, context.targetCell());
        double middle = Math.abs(distance - PREFERRED_MIDDLE);
        return distance >= PREFERRED_MIN && distance <= PREFERRED_MAX ? middle : 100.0D + middle;
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
        Collections.reverse(reversed);
        return reversed;
    }
}
