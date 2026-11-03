package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vindicator decisions without a world: strike, walk round, or break the one door in the way.
 *
 * <p>Every case reads only what the server would hand the adapter - the quoted prices, the opponent's cell and
 * the two route answers - so a passing test means "this situation chooses this action", never "a real door
 * came down". The real axe damage, the real removal and the protection chain belong to the dedicated-server
 * fixture.</p>
 */
class VindicatorTacticalAiTest {
    private static final int MOVE_COST = 5;
    private static final int MELEE_COST = 10;
    private static final int BREAK_COST = 10;

    // ---- the approved order ------------------------------------------------------------------------

    @Test
    void anOpponentInReachIsHitWithTheNativeAxe() {
        Action action = new Action(cell(5), 100).opponent(cell(6));
        assertEquals(AiAction.Kind.MELEE, new VindicatorTacticalAi().planTurn(action).kind());
        assertEquals(AiAction.Kind.MELEE, new VindicatorTacticalAi().afterMovement(action).kind());
    }

    @Test
    void anAdjacentOpponentBehindAWallIsWalkedToInsteadOfStoodInFrontOf() {
        // Geometrically in reach, but the line is blocked: standing still would waste the turn, so the actor
        // keeps planning along the route it was given.
        Action blocked = new Action(cell(5), 100).opponent(cell(6));
        blocked.lineOfSight = false;
        blocked.openRoute(List.of(cell(4), cell(4).offset(0, 0, 1), cell(5).offset(0, 0, 1)), null);
        AiAction plan = new VindicatorTacticalAi().planTurn(blocked);
        assertEquals(AiAction.Kind.MOVE, plan.kind(), "a blocked line is a reason to walk, not to give up");

        // With no legal route at all the turn still ends, but only because there is nowhere to go.
        Action nowhere = new Action(cell(5), 100).opponent(cell(6));
        nowhere.lineOfSight = false;
        nowhere.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        nowhere.doorRoute(10, null, null);
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().planTurn(nowhere).kind());
    }

    @Test
    void aNormalRouteIsWalkedEvenWhenThisTurnCannotFinishIt() {
        Action action = new Action(cell(0), 15);
        action.openRoute(List.of(cell(1), cell(2), cell(3), cell(4), cell(5), cell(6)), null);
        AiAction plan = new VindicatorTacticalAi().planTurn(action);
        assertEquals(AiAction.Kind.MOVE, plan.kind());
        // 15 AP pays three 5-AP steps; the detour continues on a later turn rather than breaking a door.
        assertEquals(List.of(cell(1), cell(2), cell(3)), plan.path());
    }

    @Test
    void aDoorIsBrokenOnlyWhenTheOpenRouteIsProvenAbsent() {
        Action action = new Action(cell(0), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(1), cell(2), cell(3), cell(4), cell(5), cell(6)), cell(3));
        AiAction plan = new VindicatorTacticalAi().planTurn(action);
        assertEquals(AiAction.Kind.MOVE, plan.kind());
        // 100 - 10 reserved for the break / 5 per step: plenty of AP, but the walk still stops in front of
        // the door instead of stepping into a door that is still closed.
        assertEquals(List.of(cell(1), cell(2)), plan.path());
    }

    @Test
    void standingInFrontOfTheDoorBreaksIt() {
        Action action = new Action(cell(2), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(3));
        AiAction plan = new VindicatorTacticalAi().planTurn(action);
        assertEquals(AiAction.Kind.BREAK_DOOR, plan.kind());
        assertEquals(DoorRoutePlanner.Door.of(cell(3)), plan.door());
    }

    @Test
    void afterWalkingToTheDoorTheSameTurnMayBreakIt() {
        Action action = new Action(cell(2), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(3));
        AiAction plan = new VindicatorTacticalAi().afterMovement(action);
        assertEquals(AiAction.Kind.BREAK_DOOR, plan.kind(), "the price was reserved during the walk");
    }

    @Test
    void withoutEnoughApTheDoorIsLeftAlone() {
        Action action = new Action(cell(2), 9);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(3));
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().planTurn(action).kind());
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().afterMovement(action).kind());
    }

    @Test
    void aForbiddenBreakEndsTheTurnWithoutWalkingToTheDoor() {
        Action denied = new Action(cell(2), 100);
        denied.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        denied.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(3));
        denied.breakAllowed = false;
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().planTurn(denied).kind(),
                "when this encounter forbids breaking, the door route is not walked at all");
    }

    @Test
    void aDoorFurtherAlongTheRouteIsNotBrokenFromWhereTheActorStands() {
        // The only door on this route is two cells ahead: the actor walks up to it, but a break needs it to be
        // the door it really stands in front of.
        Action action = new Action(cell(2), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(5));
        AiAction plan = new VindicatorTacticalAi().planTurn(action);
        assertEquals(AiAction.Kind.MOVE, plan.kind());
        assertEquals(List.of(cell(3), cell(4)), plan.path(), "the walk stops at the front of that door");
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().afterMovement(action).kind());
    }

    @Test
    void anUndecidedSearchNeverBreaksAnything() {
        Action action = new Action(cell(0), 100);
        action.openRoute(null, DoorRoutePlanner.Status.LIMIT_REACHED);
        action.doorRoute(10, List.of(cell(1), cell(2), cell(3), cell(4), cell(5)), cell(2));
        AiAction plan = new VindicatorTacticalAi().planTurn(action);
        assertEquals(AiAction.Kind.MOVE, plan.kind(), "an unknown map still allows closing the distance");
        assertEquals(1, plan.path().size(), "one bounded step, no route committed from an unknown map");
    }

    @Test
    void noRouteAtAllEndsTheTurn() {
        Action action = new Action(cell(0), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, null, null);
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().planTurn(action).kind());
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().afterMovement(action).kind());
    }

    @Test
    void noOpponentAndNoRouteJustEndsTheTurn() {
        // A context without a door route answers VindicatorRoute.NONE, which is also what an actor with no
        // legal opponent receives: nothing is invented from the absence of data.
        Action action = new Action(cell(5), 100);
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().planTurn(action).kind());
        assertEquals(AiAction.Kind.END_TURN, new VindicatorTacticalAi().afterMovement(action).kind());
    }

    @Test
    void breakingEndsTheTurnSoNoFreeAxeSwingFollows() {
        VindicatorTacticalAi adapter = new VindicatorTacticalAi();
        Action action = new Action(cell(2), 100);
        action.openRoute(null, DoorRoutePlanner.Status.UNREACHABLE);
        action.doorRoute(10, List.of(cell(3), cell(4), cell(5), cell(6)), cell(3));
        adapter.onActionResolved(AiAction.Kind.BREAK_DOOR);
        assertEquals(AiAction.Kind.END_TURN, adapter.afterMovement(action).kind());
    }

    // ---- the typed payload --------------------------------------------------------------------------

    @Test
    void onlyTheBreakActionCarriesADoor() {
        DoorRoutePlanner.Door door = DoorRoutePlanner.Door.of(cell(3));
        assertEquals(door, AiAction.breakDoor(door).door());
        assertNull(AiAction.of(AiAction.Kind.MELEE).door());
        assertThrows(IllegalArgumentException.class, () -> new AiAction(AiAction.Kind.BREAK_DOOR, List.of(), -1,
                null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AiAction(AiAction.Kind.MELEE, List.of(), -1,
                null, null, null, door));
        assertTrue(new VindicatorTacticalAi().breaksDoors(), "only the vindicator declares door breaking");
    }

    // ---- context ------------------------------------------------------------------------------------

    private static GridPos cell(int x) { return new GridPos(x, 64, 0); }

    /**
     * The snapshot the server would hand the adapter, with a straight corridor and one opponent.
     *
     * <p>The opponent stands one cell past the last column the route reaches, so "engagement" is the cell right
     * before it.</p>
     */
    private static final class Action implements AiContext {
        private final GridPos actorCell;
        private final int ap;
        private GridPos opponent = cell(20);
        private DoorRoutePlanner.Route open = DoorRoutePlanner.Route.UNREACHABLE;
        private DoorRoutePlanner.Route throughDoors = DoorRoutePlanner.Route.UNREACHABLE;
        private int breakCost = BREAK_COST;
        boolean breakAllowed = true;
        /** The real sight line the server would report for this cell pair. */
        boolean lineOfSight = true;

        Action(GridPos actorCell, int ap) {
            this.actorCell = actorCell;
            this.ap = ap;
        }

        /** The opponent this own turn locked; the actor's own cell never holds one. */
        Action opponent(GridPos opponentCell) {
            this.opponent = opponentCell;
            return this;
        }

        void openRoute(List<GridPos> path, DoorRoutePlanner.Status status) {
            open = path == null ? new DoorRoutePlanner.Route(status, List.of(), null)
                    : new DoorRoutePlanner.Route(DoorRoutePlanner.Status.FOUND, path, null);
        }

        void doorRoute(int cost, List<GridPos> path, GridPos doorLower) {
            breakCost = cost;
            throughDoors = path == null ? DoorRoutePlanner.Route.UNREACHABLE
                    : new DoorRoutePlanner.Route(DoorRoutePlanner.Status.FOUND, path,
                    doorLower == null ? null : DoorRoutePlanner.Door.of(doorLower));
        }

        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos actorCell() { return actorCell; }
        @Override public GridPos playerCell() { return opponent; }
        @Override public GridPos targetCell() { return opponent; }
        @Override public int ap() { return ap; }
        @Override public int moveCost() { return MOVE_COST; }
        @Override public int meleeCost() { return MELEE_COST; }
        @Override public VindicatorRoute vindicator() {
            return new VindicatorRoute(open, throughDoors, breakAllowed, breakCost);
        }
        @Override public boolean breakDoor(DoorRoutePlanner.Door door) { return true; }
        @Override public List<GridPos> pathToPlayer() { return List.of(); }
        @Override public List<GridPos> standableNeighbors(GridPos from) {
            List<GridPos> result = new ArrayList<>();
            for (GridPos next : List.of(from.offset(1, 0, 0), from.offset(-1, 0, 0),
                    from.offset(0, 0, 1), from.offset(0, 0, -1))) {
                if (Math.abs(next.x() - from.x()) <= 1 && next.x() >= 0 && next.x() < opponent.x()) result.add(next);
            }
            return result;
        }
        @Override public boolean occupied(GridPos cell) { return false; }
        @Override public boolean inMeleeRange(GridPos from, GridPos to) {
            return from.manhattanDistance(to) == 1;
        }
        @Override public boolean hasLineOfSight(GridPos from, GridPos to) { return lineOfSight; }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }
}
