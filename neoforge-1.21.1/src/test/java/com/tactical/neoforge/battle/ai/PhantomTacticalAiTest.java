package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.AerialMovement;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PhantomTacticalAiTest {
    private static final GridPos HOME = new GridPos(0, 4, 0);
    private static final GridPos MIDDLE = new GridPos(1, 3, 0);
    private static final GridPos ATTACK = new GridPos(2, 2, 0);
    private static final GridPos PLAYER = new GridPos(3, 1, 0);
    private static final Map<GridPos, List<GridPos>> EDGES = Map.of(HOME, List.of(MIDDLE),
            MIDDLE, List.of(HOME, ATTACK), ATTACK, List.of(MIDDLE));

    @Test void completeDiveHasSingleExplicitAttackMarkerAndPaidReturn() {
        AiAction action = new PhantomTacticalAi().planTurn(new Context(30, Set.of(), true));
        assertEquals(AiAction.Kind.DIVE, action.kind());
        assertEquals(List.of(MIDDLE, ATTACK, MIDDLE, HOME), action.path());
        assertEquals(1, action.attackIndex());
        assertTrue(BattleAiSession.validDive(new Context(30, Set.of(), true), action));
    }

    @Test void insufficientRoundTripApApproachesWithoutFreeAttackOrReturn() {
        PhantomTacticalAi ai = new PhantomTacticalAi();
        AiAction action = ai.planTurn(new Context(29, Set.of(), true));
        assertEquals(AiAction.Kind.MOVE, action.kind());
        assertEquals(List.of(MIDDLE, ATTACK), action.path());
        assertEquals(-1, action.attackIndex());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(new Context(19, Set.of(), true)).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(new Context(4, Set.of(), true)).kind());
    }

    @Test void occupiedOrOccludedAttackCannotCreateDive() {
        assertEquals(AiAction.Kind.END_TURN,
                new PhantomTacticalAi().planTurn(new Context(100, Set.of(MIDDLE), true)).kind());
        assertNotEquals(AiAction.Kind.DIVE,
                new PhantomTacticalAi().planTurn(new Context(100, Set.of(), false)).kind());
    }

    @Test void validatorRechecksApCollisionLosAndReturnHome() {
        AiAction dive = AiAction.dive(List.of(MIDDLE, ATTACK, MIDDLE, HOME), 1);
        assertFalse(BattleAiSession.validDive(new Context(29, Set.of(), true), dive));
        assertFalse(BattleAiSession.validDive(new Context(30, Set.of(MIDDLE), true), dive));
        assertFalse(BattleAiSession.validDive(new Context(30, Set.of(), false), dive));
        assertFalse(BattleAiSession.validDive(new Context(30, Set.of(), true),
                AiAction.dive(List.of(MIDDLE, ATTACK, MIDDLE), 1)));
    }

    @Test void pureSearchUsesThreeDimensionsAndHonorsBudget() {
        assertTrue(AerialMovement.findPath(HOME, ATTACK, 1, pos -> EDGES.getOrDefault(pos, List.of())).isEmpty());
        assertEquals(List.of(MIDDLE, ATTACK), AerialMovement.findPath(HOME, ATTACK, 2, pos -> EDGES.getOrDefault(pos, List.of())));
        assertFalse(AerialMovement.adjacent(HOME, ATTACK));
        assertTrue(AerialMovement.adjacent(HOME, MIDDLE));
        assertTrue(AerialMovement.findPath(HOME, ATTACK, Integer.MAX_VALUE,
                pos -> List.of(pos.offset(2, 0, 0))).isEmpty(), "invalid graph edges cannot bypass segment adjacency");
    }

    @Test void constructionAndCostArithmeticRejectMalformedOrOverflowingActions() {
        assertThrows(IllegalArgumentException.class, () -> AiAction.dive(List.of(MIDDLE, ATTACK), 1));
        assertThrows(IllegalArgumentException.class, () -> new AiAction(AiAction.Kind.MOVE, List.of(MIDDLE), 0));
        assertThrows(IllegalArgumentException.class, () -> AiAction.of(AiAction.Kind.DIVE));
        assertEquals(-1, new AiAction(AiAction.Kind.MOVE, List.of(MIDDLE)).attackIndex());
        assertFalse(PhantomTacticalAi.affordable(Integer.MAX_VALUE, Integer.MAX_VALUE, 30, 100));
        assertFalse(PhantomTacticalAi.affordable(2, 0, 30, 100));
        assertFalse(PhantomTacticalAi.affordable(2, 5, 0, 100));
        assertEquals(List.of(), PhantomTacticalAi.roundTrip(HOME, List.of()));
    }

    private record Context(int ap, Set<GridPos> blocked, boolean clear) implements AiContext {
        public Mob actor() { throw new UnsupportedOperationException("Pure helper test has no entity"); }
        public boolean flying() { return true; }
        public GridPos actorCell() { return HOME; }
        public GridPos playerCell() { return PLAYER; }
        public int moveCost() { return 5; }
        public List<GridPos> pathToPlayer() { return List.of(); }
        public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        public List<GridPos> airNeighbors(GridPos from) { return EDGES.getOrDefault(from, List.of()); }
        public boolean occupied(GridPos cell) { return cell.equals(PLAYER) || blocked.contains(cell); }
        public boolean inMeleeRange(GridPos from, GridPos target) { return from.equals(ATTACK); }
        public boolean hasLineOfSight(GridPos from, GridPos target) { return clear; }
        public void attackPlayer() { fail("Planning must not inflict damage"); }
        public void log(String message) { }
    }
}
