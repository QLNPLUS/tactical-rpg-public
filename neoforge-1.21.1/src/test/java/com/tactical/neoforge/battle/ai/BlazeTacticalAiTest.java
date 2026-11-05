package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure decisions of the blaze adapter. No world, no entity: the context answers the same questions the server
 * answers, so "melee needs real reach" and "a salvo needs a legal native line" are decidable here.
 */
class BlazeTacticalAiTest {
    private static final GridPos HOME = new GridPos(0, 0, 0);
    private static final GridPos NEAR = new GridPos(1, 0, 0);
    private static final GridPos FIRING = new GridPos(4, 0, 0);
    private static final GridPos PLAYER = new GridPos(9, 0, 0);
    private static final Map<GridPos, List<GridPos>> EDGES = Map.of(
            HOME, List.of(NEAR),
            NEAR, List.of(HOME, FIRING),
            FIRING, List.of(NEAR));

    @Test void realReachIsRequiredForMeleeEvenWhenGridAdjacent() {
        // Cube-adjacent diagonal in an aerial grid: reachable for movement, but the body cannot touch anybody.
        Fake cubeAdjacentOnly = new Fake(100, Set.of(), true, false, true);
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, new BlazeTacticalAi().planTurn(cubeAdjacentOnly).kind(),
                "grid adjacency must not be read as melee reach");
        Fake reallyInReach = new Fake(100, Set.of(), true, true, true);
        assertEquals(AiAction.Kind.MELEE, new BlazeTacticalAi().planTurn(reallyInReach).kind());
    }

    @Test void legalLineInPlaceFiresWithoutSpendingMovement() {
        AiAction action = new BlazeTacticalAi().planTurn(new Fake(100, Set.of(), true, false, true));
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, action.kind());
        assertEquals(List.of(), action.path());
    }

    @Test void occludedOrTooDistantActorMovesToACellItCanReallyShootFrom() {
        // Blocked at home, legal from FIRING: the route is the bounded search, not a straight line.
        Fake occluded = new Fake(100, Set.of(), false, false, true, Set.of(FIRING));
        AiAction action = new BlazeTacticalAi().planTurn(occluded);
        assertEquals(AiAction.Kind.MOVE, action.kind());
        assertEquals(List.of(NEAR, FIRING), action.path());
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, new BlazeTacticalAi().afterMovement(
                new Fake(70, Set.of(), true, false, true)).kind());
    }

    @Test void noLegalCellAndNoAffordableReserveEndsTheTurnInsteadOfWalkingForever() {
        // 19 AP cannot pay the 20 AP salvo: nothing is spent and no position is invented.
        Fake starving = new Fake(19, Set.of(), false, false, true, Set.of(FIRING));
        assertEquals(AiAction.Kind.END_TURN, new BlazeTacticalAi().planTurn(starving).kind());
        // Exactly enough for the salvo but nothing legal to shoot from, and no step can be paid on top.
        Fake noRoute = new Fake(20, Set.of(NEAR), false, false, true, Set.of());
        assertEquals(AiAction.Kind.END_TURN, new BlazeTacticalAi().planTurn(noRoute).kind());
        Fake nothingAtAll = new Fake(0, Set.of(), true, true, false);
        assertEquals(AiAction.Kind.END_TURN, new BlazeTacticalAi().planTurn(nothingAtAll).kind());
    }

    @Test void meleeIsStillPreferredWhenItsOwnPriceIsAffordableButTheSalvoIsNot() {
        // 12 AP: no salvo, but a real melee in reach is paid for at its own price.
        Fake meleeOnly = new Fake(12, Set.of(), true, true, false);
        assertEquals(AiAction.Kind.MELEE, new BlazeTacticalAi().planTurn(meleeOnly).kind());
    }

    @Test void oneSalvoPerOwnTurnAndNoSecondActionAfterItResolved() {
        BlazeTacticalAi ai = new BlazeTacticalAi();
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, ai.planTurn(new Fake(100, Set.of(), true, false, true)).kind());
        ai.onActionResolved(AiAction.Kind.FIREBALL_VOLLEY);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(new Fake(80, Set.of(), true, false, true)).kind(),
                "a second salvo in the same own turn would be a second paid action");
        // A new own turn clears the marker.
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, ai.planTurn(new Fake(100, Set.of(), true, false, true)).kind());
    }

    @Test void constructionOfTheIntentCarriesNothingTheServerWouldTrust() {
        AiAction action = AiAction.fireballVolley();
        assertEquals(AiAction.Kind.FIREBALL_VOLLEY, action.kind());
        assertEquals(List.of(), action.path());
        assertEquals(-1, action.attackIndex());
        assertTrue(action.skill() == null && action.skillTarget() == null
                && action.brew() == null && action.door() == null);
        assertFalse(action.equals(AiAction.endTurn()));
    }

    /** Context with configurable geometry: no entity is needed to decide these questions. */
    private record Fake(int ap, Set<GridPos> blocked, boolean clearAtHome, boolean nativeReach,
                        boolean volleyAvailable, Set<GridPos> legalFiringCells) implements AiContext {
        Fake(int ap, Set<GridPos> blocked, boolean clearAtHome, boolean nativeReach, boolean volleyAvailable) {
            this(ap, blocked, clearAtHome, nativeReach, volleyAvailable, Set.of());
        }

        public net.minecraft.world.entity.Mob actor() { throw new UnsupportedOperationException("pure test"); }
        public GridPos actorCell() { return HOME; }
        public GridPos playerCell() { return PLAYER; }
        public int ap() { return ap; }
        public int moveCost() { return 5; }
        public int meleeCost() { return 10; }
        public int fireballCost() { return volleyAvailable ? 20 : 0; }
        public boolean flying() { return true; }
        public List<GridPos> pathToPlayer() {
            return List.of(NEAR, FIRING, new GridPos(5, 0, 0), new GridPos(6, 0, 0), new GridPos(7, 0, 0),
                    new GridPos(8, 0, 0), PLAYER);
        }
        public List<GridPos> standableNeighbors(GridPos from) { return EDGES.getOrDefault(from, List.of()); }
        public List<GridPos> airNeighbors(GridPos from) { return EDGES.getOrDefault(from, List.of()); }
        public boolean occupied(GridPos cell) { return cell.equals(PLAYER) || blocked.contains(cell); }
        public boolean inMeleeRange(GridPos from, GridPos to) { return from.equals(NEAR) || from.equals(HOME); }
        public boolean inNativeMeleeReach(GridPos from) {
            return nativeReach && from.equals(HOME) && clearAtHome;
        }
        public boolean hasLineOfSight(GridPos from, GridPos to) { return clearAtHome; }
        public boolean canFireballFrom(GridPos from) {
            return volleyAvailable && (from.equals(HOME) && clearAtHome || legalFiringCells.contains(from));
        }
        public void attackPlayer() { throw new AssertionError("Planning must not inflict damage"); }
        public void log(String message) { }
    }
}
