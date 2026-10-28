package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TacticalAiBehaviorTest {
    @Test
    void creeperStopsAtTwoCellsAndPreparesOnlyAfterMovementResolves() {
        var ai = new CreeperTacticalAi();
        var context = context(5, 100, true);
        AiAction movement = ai.planTurn(context);
        assertEquals(AiAction.Kind.MOVE, movement.kind());
        assertEquals(List.of(cell(4), cell(3), cell(2)), movement.path());
        assertEquals(CreeperTacticalAi.State.APPROACHING, ai.state());
        AiAction prepare = ai.afterMovement(context(2, 70, true));
        assertEquals(AiAction.Kind.PREPARE_EXPLOSION, prepare.kind());
        ai.onActionResolved(prepare.kind());
        assertEquals(CreeperTacticalAi.State.PREPARING, ai.state());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(2, 70, true)).kind());
    }

    @Test
    void preparedCreeperDetonatesNextOwnTurnEvenAfterPlayerEscapesOrBreaksLos() {
        var ai = new CreeperTacticalAi();
        var prepare = ai.planTurn(context(1, 100, true));
        assertEquals(AiAction.Kind.PREPARE_EXPLOSION, prepare.kind());
        ai.onActionResolved(prepare.kind());
        assertEquals(AiAction.Kind.DETONATE, ai.planTurn(context(20, 0, false)).kind());
        ai.onActionResolved(AiAction.Kind.DETONATE);
        assertEquals(CreeperTacticalAi.State.DETONATED, ai.state());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(1, 100, true)).kind());
    }

    @Test
    void preparingStateDoesNotLeakBetweenCreepers() {
        var first = new CreeperTacticalAi();
        var second = new CreeperTacticalAi();
        first.onActionResolved(AiAction.Kind.PREPARE_EXPLOSION);
        assertEquals(AiAction.Kind.DETONATE, first.planTurn(context(2, 100, true)).kind());
        assertEquals(AiAction.Kind.PREPARE_EXPLOSION, second.planTurn(context(2, 100, true)).kind());
    }

    @Test
    void approachRespectsApOccupiedCellsAndLos() {
        var ai = new CreeperTacticalAi();
        assertEquals(List.of(cell(4)), ai.planTurn(context(5, 10, true)).path());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(5, 9, true)).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(1, 100, false)).kind());
        var blocked = new Context(cell(5), 100, true, cell(4));
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(blocked).kind());
    }

    @Test
    void genericAdapterPreservesApproachThenSingleMelee() {
        var ai = new GenericMeleeAi();
        assertEquals(List.of(cell(3), cell(2), cell(1)), ai.planTurn(context(4, 100, true)).path());
        assertEquals(AiAction.Kind.MELEE, ai.afterMovement(context(1, 0, true)).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(2, 0, true)).kind());
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context(1, 0, true)).kind());
    }

    @Test
    void actionsCannotCarryPathsOnInstantActionsOrEmptyMoves() {
        assertThrows(IllegalArgumentException.class, () -> AiAction.move(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AiAction(AiAction.Kind.DETONATE, List.of(cell(1))));
    }

    private static GridPos cell(int x) { return new GridPos(x, 64, 0); }
    private static Context context(int x, int ap, boolean los) { return new Context(cell(x), ap, los, null); }

    private record Context(GridPos actorCell, int ap, boolean los, GridPos blocked) implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos playerCell() { return cell(0); }
        @Override public int moveCost() { return 10; }
        @Override public List<GridPos> pathToPlayer() {
            return java.util.stream.IntStream.iterate(actorCell.x() - 1, x -> x >= 0, x -> x - 1)
                    .mapToObj(TacticalAiBehaviorTest::cell).toList();
        }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(cell(from.x() - 1)); }
        @Override public boolean occupied(GridPos pos) { return pos.equals(playerCell()) || pos.equals(blocked); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return GridMovementRules.canStep(a, b); }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return los; }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }
}
