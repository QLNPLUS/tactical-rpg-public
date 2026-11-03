package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Witch tactics: keep herself alive with one drink, then throw one real splash potion per own turn.
 *
 * <p>A witch does not chase anything with her fists. She drinks only when the drink really buys something
 * (which is what the fixed priority order below decides), and otherwise works for a legal throwing position
 * inside 10 horizontal blocks, preferring the comfortable 4-8 band, and throws the one brew that still has an
 * effect on the opponent she already locked.</p>
 *
 * <p>The adapter holds no world state and no cooldown: the shared 3-own-turn drink cooldown, the quoted prices
 * and the "would this really work" checks all arrive in {@link WitchState}, filled from the same server state
 * that charges the action. That is also why every branch here is decidable in a pure test.</p>
 *
 * <p>Nothing here imitates vanilla's per-tick random pick: the order is deterministic, so a witch in the same
 * situation always chooses the same brew. Vanilla's own {@code aiStep} potion segment is skipped by a mixin
 * while this mod controls her, so no wall-clock {@code usingTime} can settle a second dose.</p>
 */
public final class WitchTacticalAi implements TacticalMobAi {
    /** Plan §3.2 step 1: below this fraction she heals before anything else. */
    public static final double EMERGENCY_HEALTH = 0.25D;
    /** Plan §3.3/#3.2 configurable ordinary heal threshold, default 0.5. */
    public static final double DEFAULT_HEAL_THRESHOLD = 0.5D;
    /** Plan §3.2 step 4: swifter legs are worth a drink only beyond this distance. */
    public static final double SWIFTNESS_DISTANCE = 11.0D;
    /** Plan §3.3: maximum horizontal throwing range. */
    public static final double MAX_THROW_DISTANCE = 10.0D;
    public static final double COMFORTABLE_MIN = 4.0D;
    public static final double COMFORTABLE_MAX = 8.0D;
    /** Plan §3.3 step 1: slowness only once the opponent is at least this far away. */
    public static final double SLOWNESS_DISTANCE = 8.0D;
    /** Plan §3.3 step 3: weakness only in the opponent's face. */
    public static final double WEAKNESS_DISTANCE = 3.0D;
    /** Plan §3.3 step 2: poison only against an opponent that can lose at least that much health. */
    public static final float POISON_MIN_HEALTH = 8.0F;

    private static final int MAX_EXPANDED_CELLS = 128;
    private static final double COMFORTABLE_MIDDLE = (COMFORTABLE_MIN + COMFORTABLE_MAX) / 2.0D;

    private boolean actionResolved;

    @Override
    public AiAction planTurn(AiContext context) {
        actionResolved = false;
        AiAction drink = planDrink(context);
        if (drink != null) return drink;
        if (!context.witch().targetPresent()) return AiAction.endTurn();

        WitchBrew brew = chooseThrow(context);
        if (brew == null) return AiAction.endTurn();
        if (canThrowHere(context)) {
            // Already in a legal spot and not nose to nose: throw without spending movement.
            if (horizontal(context.actorCell(), context.targetCell()) >= COMFORTABLE_MIN) {
                return AiAction.throwPotion(brew);
            }
            // Too close to be comfortable: back off if this turn can still pay for the throw.
            List<GridPos> retreat = searchThrowingCell(context, true);
            if (!retreat.isEmpty()) return AiAction.move(retreat);
            return AiAction.throwPotion(brew);
        }
        List<GridPos> approach = searchThrowingCell(context, false);
        return approach.isEmpty() ? AiAction.endTurn() : AiAction.move(approach);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        if (actionResolved) return AiAction.endTurn();
        AiAction drink = planDrink(context);
        if (drink != null) return drink;
        if (!context.witch().targetPresent() || !canThrowHere(context)) return AiAction.endTurn();
        WitchBrew brew = chooseThrow(context);
        return brew == null ? AiAction.endTurn() : AiAction.throwPotion(brew);
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.DRINK_POTION || action == AiAction.Kind.THROW_POTION) actionResolved = true;
    }

    /**
     * The one self-drink for this own turn, or null when nothing she could drink really buys anything.
     *
     * <p>A drink already in progress at admission is honoured first, but it has to pass the same benefit test
     * as any other drink: it is this turn's action and must be paid for like one. If it cannot be paid for, or
     * would now be wasted, it is dropped - a drink nobody paid for never took effect.</p>
     */
    private AiAction planDrink(AiContext context) {
        WitchState state = context.witch();
        if (!state.ready() || state.drinkCost() <= 0) return null;
        WitchBrew brew = state.pending();
        if (brew != null) {
            if (!brew.selfDrink() || !state.beneficial(brew) || context.ap() < state.drinkCost()) {
                context.clearPendingDrink();
                brew = null;
            }
            if (brew != null) return AiAction.drink(brew);
        }
        brew = chooseDrink(context, state);
        return brew == null || context.ap() < state.drinkCost() ? null : AiAction.drink(brew);
    }

    /**
     * The fixed self-drink order from plan §3.2, first action that really helps wins.
     *
     * <p>{@code healThreshold} arrives from configuration; everything else is the approved constant order.
     * A same-or-better effect that is still fully effective is never re-drunk, and a heal is never started
     * unless a whole dose would land.</p>
     */
    public static WitchBrew chooseDrink(AiContext context, WitchState state) {
        if (state.healthFraction() <= EMERGENCY_HEALTH) return WitchBrew.HEALING;
        if (state.eyeInWater() && !state.hasSelf(WitchBrew.WATER_BREATHING)) return WitchBrew.WATER_BREATHING;
        if (state.burning() && !state.hasSelf(WitchBrew.FIRE_RESISTANCE)) return WitchBrew.FIRE_RESISTANCE;
        double threshold = state.healThreshold() > 0.0F ? state.healThreshold() : DEFAULT_HEAL_THRESHOLD;
        if (state.healthFraction() <= threshold && state.healDose() > 0.0F
                && state.missingHealth() >= state.healDose()) {
            return WitchBrew.HEALING;
        }
        if (state.targetPresent()
                && horizontal(context.actorCell(), context.targetCell()) > SWIFTNESS_DISTANCE
                && !state.hasSelf(WitchBrew.SWIFTNESS)) {
            return WitchBrew.SWIFTNESS;
        }
        return null;
    }

    /**
     * The deterministic thrown-brew order from plan §3.3, first brew that still works on the locked
     * opponent wins.
     *
     * <p>Instant harming is the fallback, but it is never chosen against a target it would heal: the same
     * order is then re-walked for a debuff that really lands, and if there is none this witch simply does not
     * throw (walking away is better than handing the opponent health back).</p>
     */
    public static WitchBrew chooseThrow(AiContext context) {
        WitchState state = context.witch();
        if (!state.targetPresent()) return null;
        double distance = horizontal(context.actorCell(), context.targetCell());
        if (distance >= SLOWNESS_DISTANCE && !state.hasTarget(WitchBrew.SLOWNESS)
                && state.receptiveTo(WitchBrew.SLOWNESS)) {
            return WitchBrew.SLOWNESS;
        }
        if (state.targetHealth() >= POISON_MIN_HEALTH && !state.hasTarget(WitchBrew.POISON)
                && state.receptiveTo(WitchBrew.POISON)) {
            return WitchBrew.POISON;
        }
        if (distance <= WEAKNESS_DISTANCE && !state.hasTarget(WitchBrew.WEAKNESS)
                && state.receptiveTo(WitchBrew.WEAKNESS)) {
            return WitchBrew.WEAKNESS;
        }
        if (state.receptiveTo(WitchBrew.HARMING)) return WitchBrew.HARMING;
        // Harming would heal this opponent, so fall back to any debuff that is not already fully in place.
        if (!state.hasTarget(WitchBrew.SLOWNESS) && state.receptiveTo(WitchBrew.SLOWNESS)) {
            return WitchBrew.SLOWNESS;
        }
        if (!state.hasTarget(WitchBrew.POISON) && state.receptiveTo(WitchBrew.POISON)) {
            return WitchBrew.POISON;
        }
        if (!state.hasTarget(WitchBrew.WEAKNESS) && state.receptiveTo(WitchBrew.WEAKNESS)) {
            return WitchBrew.WEAKNESS;
        }
        return null;
    }

    private static boolean canThrowHere(AiContext context) {
        return context.witch().canThrow(context.ap())
                && horizontal(context.actorCell(), context.targetCell()) <= MAX_THROW_DISTANCE
                && context.canThrowFrom(context.actorCell());
    }

    /**
     * Bounded search for a cell a potion can be thrown from, reserving this turn's throw price.
     *
     * <p>The search never walks through an occupied cell or the opponent, never assumes flat terrain and
     * never exceeds what the remaining AP can pay for. It prefers the middle of the comfortable band, so a
     * witch standing in the opponent's face steps back and a distant one closes in, while a blocked line
     * becomes a lateral move instead of a wall-hug.</p>
     */
    private static List<GridPos> searchThrowingCell(AiContext context, boolean retreatOnly) {
        WitchState state = context.witch();
        int moveCost = context.moveCost();
        if (moveCost <= 0 || state.throwCost() <= 0 || context.ap() < state.throwCost()) return List.of();
        int budget = (context.ap() - state.throwCost()) / moveCost;
        GridPos start = context.actorCell();
        if (budget <= 0) return List.of();
        double startDistance = horizontal(start, context.targetCell());
        Map<GridPos, GridPos> previous = new HashMap<>();
        Map<GridPos, Integer> depths = new HashMap<>();
        var queue = new ArrayDeque<GridPos>();
        previous.put(start, start);
        depths.put(start, 0);
        queue.add(start);
        GridPos best = null;
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < MAX_EXPANDED_CELLS) {
            GridPos cell = queue.removeFirst();
            int depth = depths.get(cell);
            if (!cell.equals(start) && withinThrow(cell, context)
                    && (!retreatOnly || horizontal(cell, context.targetCell()) > startDistance)
                    && (best == null || better(cell, best, context, depths))) {
                best = cell;
            }
            if (depth >= budget) continue;
            for (GridPos next : context.standableNeighbors(cell)) {
                if (next.equals(context.targetCell()) || context.occupied(next) || previous.containsKey(next)) continue;
                previous.put(next, cell);
                depths.put(next, depth + 1);
                queue.addLast(next);
            }
        }
        return best == null ? List.of() : path(previous, start, best);
    }

    private static boolean withinThrow(GridPos cell, AiContext context) {
        return horizontal(cell, context.targetCell()) <= MAX_THROW_DISTANCE && context.canThrowFrom(cell);
    }

    private static boolean better(GridPos candidate, GridPos current, AiContext context,
                                 Map<GridPos, Integer> depths) {
        double score = Math.abs(horizontal(candidate, context.targetCell()) - COMFORTABLE_MIDDLE);
        double oldScore = Math.abs(horizontal(current, context.targetCell()) - COMFORTABLE_MIDDLE);
        int comparison = Double.compare(score, oldScore);
        return comparison < 0 || comparison == 0 && depths.get(candidate) < depths.get(current);
    }

    private static double horizontal(GridPos from, GridPos to) {
        double dx = (double) from.x() - to.x();
        double dz = (double) from.z() - to.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static List<GridPos> path(Map<GridPos, GridPos> previous, GridPos start, GridPos end) {
        var reversed = new ArrayList<GridPos>();
        for (GridPos cell = end; !cell.equals(start); cell = previous.get(cell)) reversed.add(cell);
        Collections.reverse(reversed);
        return reversed;
    }
}
