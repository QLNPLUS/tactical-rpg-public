package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.TridentThrow;
import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Drowned tactics: the one vanilla trident throw, and otherwise its own native melee.
 *
 * <p>Vanilla arms a drowned with a trident in its main hand, fires it from a goal the NoAI lock already
 * stops, and otherwise walks up and hits. This adapter therefore chooses a cell and one action per own turn;
 * the throw itself is still the entity's own {@code performRangedAttack}, with vanilla's spawn point,
 * direction, 1.6 speed, 0.2 lift, difficulty inaccuracy and 8.0 base damage.</p>
 *
 * <p>Melee really comes first: a drowned that can already reach its opponent swings instead of throwing.
 * Only when it cannot does it look for one throw - from where it stands, or after walking to a cell that can
 * really see and reach the target. A drowned that is armed with nothing, or holds the trident in the wrong
 * hand, keeps the ordinary approach-and-melee behaviour of every other mob.</p>
 */
public final class DrownedTacticalAi implements TacticalMobAi {

    /** How close the throw is still worth planning for: the vanilla radius, as a real distance. */
    static final double MAX_THROW_DISTANCE = TridentThrow.MAX_RANGE;
    /** The ruleset prefers to keep a little distance instead of touching the target. */
    private static final double PREFERRED_DISTANCE = 7.0D;
    /** Bounded, so a search over water and land never turns into a zone scan. */
    private static final int MAX_EXPANDED_CELLS = 128;

    private final BooleanSupplier armed;
    private final GenericMeleeAi melee = new GenericMeleeAi();
    private boolean throwResolved;

    public DrownedTacticalAi(Mob actor) {
        this(() -> actor != null && actor.getType() == net.minecraft.world.entity.EntityType.DROWNED
                && actor.getMainHandItem().is(Items.TRIDENT));
    }

    /** Allows deterministic decision tests without a world. */
    DrownedTacticalAi(BooleanSupplier armed) {
        this.armed = Objects.requireNonNull(armed);
    }

    @Override
    public AiAction planTurn(AiContext context) {
        throwResolved = false;
        if (meleeFirst(context)) return AiAction.of(AiAction.Kind.MELEE);
        if (!armed.getAsBoolean()) return melee.planTurn(context);
        int cost = context.tridentCost();
        if (cost > 0 && context.ap() >= cost && context.canThrowTridentFrom(context.actorCell())) {
            return AiAction.throwTrident();
        }
        if (context.moveCost() <= 0 || context.ap() < context.moveCost()) {
            return AiApproach.towardPlayer(context, cell -> context.inMeleeRange(cell, context.targetCell()));
        }
        // Walk towards a cell that can really throw this turn, keeping enough AP to pay for the throw.
        int budget = cost > 0 && context.ap() >= cost
                ? (context.ap() - cost) / context.moveCost() : 0;
        List<GridPos> route = routeToThrowingCell(context, budget);
        if (!route.isEmpty()) return AiAction.move(route);
        return melee.planTurn(context);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        if (meleeFirst(context)) return AiAction.of(AiAction.Kind.MELEE);
        if (!armed.getAsBoolean() || throwResolved) return melee.afterMovement(context);
        int cost = context.tridentCost();
        return cost > 0 && context.ap() >= cost && context.canThrowTridentFrom(context.actorCell())
                ? AiAction.throwTrident() : melee.afterMovement(context);
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        // One throw per own turn: the mapped vanilla 40 tick interval, without waiting an extra empty turn.
        if (action == AiAction.Kind.THROW_TRIDENT) throwResolved = true;
    }

    /**
     * Whether the native melee is legal right here.
     *
     * <p>Reach and line of sight are the server's own answers, so a cell the adapter cannot really attack
     * from is never planned as an attack.</p>
     */
    private static boolean meleeFirst(AiContext context) {
        // The intent names no entity: the server re-resolves the locked opponent when the action resolves. What the
        // adapter needs is therefore the cell it would attack - and the range and sight answers for that cell.
        GridPos targetCell = context.targetCell();
        if (targetCell == null) return false;
        if (context.meleeCost() <= 0 || context.ap() < context.meleeCost()) return false;
        return context.inMeleeRange(context.actorCell(), targetCell)
                && context.hasLineOfSight(context.actorCell(), targetCell);
    }

    /**
     * Bounded breadth-first walk to the nearest cell whose throw is really legal and affordable.
     *
     * <p>The search uses the context's own terrain answers, so a drowned walks through water and over a shore
     * with exactly the edges the server will validate its steps against.</p>
     */
    private static List<GridPos> routeToThrowingCell(AiContext context, int budget) {
        if (budget <= 0) return List.of();
        GridPos start = context.actorCell();
        GridPos targetCell = context.targetCell();
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depth = new HashMap<>();
        var queue = new ArrayDeque<GridPos>();
        previous.put(start, start);
        depth.put(start, 0);
        queue.add(start);
        GridPos best = null;
        double bestScore = Double.MAX_VALUE;
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < MAX_EXPANDED_CELLS) {
            GridPos cell = queue.removeFirst();
            int steps = depth.get(cell);
            if (steps > 0 && context.canThrowTridentFrom(cell)) {
                double score = Math.abs(distance(cell, targetCell) - PREFERRED_DISTANCE) + steps * 0.01D;
                if (score < bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
            if (steps >= budget) continue;
            for (GridPos next : context.standableNeighbors(cell)) {
                if (next.equals(targetCell) || context.occupied(next) || previous.containsKey(next)) continue;
                previous.put(next, cell);
                depth.put(next, steps + 1);
                queue.addLast(next);
            }
        }
        if (best == null) return List.of();
        List<GridPos> reversed = new ArrayList<>();
        for (GridPos cell = best; !cell.equals(start); cell = previous.get(cell)) reversed.add(cell);
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static double distance(GridPos from, GridPos to) {
        double dx = (double) from.x() - to.x();
        double dy = (double) from.y() - to.y();
        double dz = (double) from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
