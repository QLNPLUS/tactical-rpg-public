package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Vindicator tactics: the vanilla axe, the normal way round, and a wooden door only when there is no other way.
 *
 * <p>The axe is not re-implemented here. A melee action resolves through the server's native
 * {@code doHurtTarget}, so the real iron axe, its real item attributes, its enchantments and the real swing are
 * what hurt the opponent. This adapter only decides <em>which</em> opponent cell to walk to and whether a
 * wooden door has to go.</p>
 *
 * <p>The approved order is the whole point of the class:</p>
 * <ol>
 *   <li>strike when already in reach;</li>
 *   <li>otherwise walk the normal, door-free route - even when this turn's AP cannot finish it. A detour that
 *       needs another turn is still cheaper than a broken door;</li>
 *   <li>only when that route is <em>proven</em> absent may a complete wooden-door route be considered, and then
 *       the turn walks to the first such door and breaks that one;</li>
 *   <li>an undecided search (budget or loaded-area limit) never justifies breaking anything.</li>
 * </ol>
 *
 * <p>Breaking the door is this turn's main action and ends the turn with the remaining AP kept. The route is
 * recomputed on the next own turn, so nobody gets a free axe swing after walking through the hole in the same
 * turn.</p>
 *
 * <p>Every decision comes from the {@link VindicatorRoute} snapshot, never from reaching into the world: a
 * context with no legal opponent or no door terrain answers {@link VindicatorRoute#NONE}, which is a route
 * snapshot that proves nothing, so this adapter simply ends its turn instead of inventing a victim or a door.</p>
 */
public final class VindicatorTacticalAi implements TacticalMobAi {
    private boolean resolved;

    /**
     * The only adapter that declares door breaking, so the server hands it a door-aware terrain view and a
     * door-route snapshot. Every other melee actor keeps the untouched navigator.
     */
    @Override
    public boolean breaksDoors() { return true; }

    @Override
    public AiAction planTurn(AiContext context) {
        resolved = false;
        // "Reach" is not the same thing as "can hit": standing next to the opponent with a wall or a door leaf
        // between them is a blocked line, and the answer to a blocked line is to walk - not to stand still and
        // give up the turn. Only a real, legal melee ends the turn here.
        if (canStrike(context)) return AiAction.of(AiAction.Kind.MELEE);
        if (context.moveCost() <= 0) return AiAction.endTurn();

        VindicatorRoute route = context.vindicator();
        DoorRoutePlanner.Route open = route.open();
        if (open.found()) return walk(context, open.path(), 0, open.path().size());
        if (open.status() == DoorRoutePlanner.Status.LIMIT_REACHED) {
            // Undecided, not "no way": close the distance a step at a time and never touch a door on a map the
            // search could not finish.
            return cautiousStep(context);
        }
        if (!route.breakAllowed() || route.breakCost() <= 0) return AiAction.endTurn();
        if (!route.throughDoors().found()) return AiAction.endTurn();

        DoorRoutePlanner.Door door = route.doorAhead(context.actorCell());
        if (door != null) {
            return context.ap() >= route.breakCost() ? AiAction.breakDoor(door) : AiAction.endTurn();
        }
        int steps = route.stepsToFirstDoor();
        if (steps <= 0) return AiAction.endTurn();
        // Reserve the break price: this turn may pay the real steps and then break the door at its front.
        return walk(context, route.throughDoors().path(), route.breakCost(), steps);
    }

    @Override
    public AiAction afterMovement(AiContext context) {
        if (resolved) return AiAction.endTurn();
        // After the one movement of this turn there is no second walk: either the opponent can really be hit
        // from here, or the door in front is broken, or the turn ends.
        if (canStrike(context)) return AiAction.of(AiAction.Kind.MELEE);
        VindicatorRoute route = context.vindicator();
        DoorRoutePlanner.Door door = route.doorAhead(context.actorCell());
        if (door == null || !route.breakAllowed() || route.breakCost() <= 0) return AiAction.endTurn();
        return context.ap() >= route.breakCost() ? AiAction.breakDoor(door) : AiAction.endTurn();
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        if (action == AiAction.Kind.BREAK_DOOR) resolved = true;
    }

    /**
     * Walk as much of {@code path} as this turn can pay for, keeping {@code reserved} AP for the main action.
     *
     * <p>Movement is charged per real step, so the walk never plans more cells than the remaining AP can pay.
     * {@code limit} is the number of cells this walk may use at most, which is how a door route stops at the
     * front of its first door instead of stepping into the door cell that is still closed.</p>
     */
    private static AiAction walk(AiContext context, List<GridPos> path, int reserved, int limit) {
        int moveCost = context.moveCost();
        if (moveCost <= 0) return AiAction.endTurn();
        int budget = (context.ap() - reserved) / moveCost;
        if (budget <= 0) return AiAction.endTurn();
        List<GridPos> steps = new ArrayList<>();
        for (int index = 0; index < path.size() && index < limit && steps.size() < budget; index++) {
            GridPos cell = path.get(index);
            if (cell.equals(context.targetCell()) || context.occupied(cell)) break;
            steps.add(cell);
        }
        return steps.isEmpty() ? AiAction.endTurn() : AiAction.move(steps);
    }

    /** One bounded step toward the opponent for a map the route search could not decide. */
    private static AiAction cautiousStep(AiContext context) {
        if (context.ap() < context.moveCost()) return AiAction.endTurn();
        GridPos start = context.actorCell();
        GridPos target = context.targetCell();
        GridPos best = null;
        for (GridPos next : context.standableNeighbors(start)) {
            if (next.equals(target) || context.occupied(next)) continue;
            if (best == null || next.manhattanDistance(target) < best.manhattanDistance(target)) best = next;
        }
        return best == null ? AiAction.endTurn() : AiAction.move(List.of(best));
    }

    private static boolean canStrike(AiContext context) {
        return context.meleeCost() > 0 && context.ap() >= context.meleeCost()
                && context.inMeleeRange(context.actorCell(), context.targetCell())
                && context.hasLineOfSight(context.actorCell(), context.targetCell());
    }
}
