package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crossbow decisions: the same stand-off framework as the bow, but its own 20 AP action, one salvo per own
 * turn and an original melee fallback when no crossbow is held.
 */
class PillagerTacticalAiTest {
    private static final int CROSSBOW_COST = 20;

    @Test
    void clearCrossbowShotAtComfortableRangeDoesNotSpendMovement() {
        assertEquals(AiAction.Kind.RANGED, crossbow().planTurn(context(cell(8, 0), 35, pos -> true)).kind());
        assertEquals(AiAction.Kind.RANGED, crossbow().planTurn(context(cell(-8, 0), 35, pos -> true)).kind());
    }

    @Test
    void wholeSalvoIsPricedAsOneCrossbowActionRatherThanOneBowAction() {
        // 15 AP pays a bow but not a crossbow, and the crossbow price must not silently become the bow price.
        assertEquals(AiAction.Kind.END_TURN, crossbow().planTurn(context(cell(8, 0), CROSSBOW_COST - 1, pos -> true)).kind());
        assertEquals(AiAction.Kind.RANGED, crossbow().planTurn(context(cell(8, 0), CROSSBOW_COST, pos -> true)).kind());
        assertEquals(CROSSBOW_COST, context(cell(8, 0), 100, pos -> true).rangedCost());
    }

    @Test
    void movementReservesTheCrossbowActionBeforeChoosingACell() {
        int ap = 55;
        var context = context(cell(18, 0), ap, pos -> pos.x() <= 16);
        AiAction move = crossbow().planTurn(context);
        assertTrue(move.kind() == AiAction.Kind.MOVE, "the crossbow user moves into range first");
        // The chosen route plus the salvo has to fit inside this turn, so the walk can never eat the shot.
        assertTrue(move.path().size() * context.moveCost() + context.rangedCost() <= ap,
                "the route reserves one crossbow action, path=" + move.path());
        assertEquals(cell(15, 0), move.path().getLast());
        assertEquals(AiAction.Kind.RANGED, crossbow().afterMovement(context(cell(15, 0), 25, pos -> true)).kind());
    }

    @Test
    void onlyOneSalvoPerOwnTurn() {
        var ai = crossbow();
        assertEquals(AiAction.Kind.RANGED, ai.planTurn(context(cell(8, 0), 100, pos -> true)).kind());
        ai.onActionResolved(AiAction.Kind.RANGED);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 0), 100, pos -> true)).kind());
        // A new own turn may fire again; the flag is per turn, not a permanent lock.
        assertEquals(AiAction.Kind.RANGED, ai.planTurn(context(cell(8, 0), 100, pos -> true)).kind());
    }

    @Test
    void crossbowStateIsNotSharedBetweenActors() {
        var first = crossbow();
        var second = crossbow();
        first.onActionResolved(AiAction.Kind.RANGED);
        assertEquals(AiAction.Kind.END_TURN, first.afterMovement(context(cell(8, 0), 100, pos -> true)).kind());
        assertEquals(AiAction.Kind.RANGED, second.afterMovement(context(cell(8, 0), 100, pos -> true)).kind());
    }

    @Test
    void noCrossbowFallsBackToOriginalMeleeInsteadOfAnInventedWeapon() {
        var armed = new AtomicBoolean(false);
        var ai = new PillagerTacticalAi(armed::get);
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context(cell(1, 0), 0, pos -> true)).kind());
        assertEquals(List.of(cell(3, 0), cell(2, 0), cell(1, 0)),
                ai.planTurn(context(cell(4, 0), 100, pos -> true)).path());
        armed.set(true);
        assertEquals(AiAction.Kind.RANGED, ai.planTurn(context(cell(8, 0), 35, pos -> true)).kind());
    }

    @Test
    void coveredShooterRepositionsBeforeFiring() {
        var context = context(cell(8, 0), 65, pos -> pos.equals(cell(8, 1)));
        assertEquals(List.of(cell(8, 1)), crossbow().planTurn(context).path());
        assertEquals(AiAction.Kind.RANGED, crossbow().afterMovement(context(cell(8, 1), 45, pos -> true)).kind());
    }

    @Test
    void insufficientApEndsTheTurnWithoutACheapShot() {
        var ai = crossbow();
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 0), CROSSBOW_COST - 1, pos -> true)).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 0), 35, pos -> false)).kind());
    }

    private static PillagerTacticalAi crossbow() { return new PillagerTacticalAi(() -> true); }
    private static GridPos cell(int x, int z) { return new GridPos(x, 64, z); }

    private static Context context(GridPos actor, int ap, Predicate<GridPos> clear) {
        return new Context(actor, ap, Set.of(), pos -> pos.y() == 64 && Math.abs(pos.x()) <= 30
                && Math.abs(pos.z()) <= 4, clear);
    }

    private record Context(GridPos actorCell, int ap, Set<GridPos> blocked,
                           Predicate<GridPos> standable, Predicate<GridPos> clear) implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int moveCost() { return 10; }
        @Override public int rangedCost() { return CROSSBOW_COST; }
        @Override public List<GridPos> pathToPlayer() {
            return java.util.stream.IntStream.iterate(actorCell.x() - 1, x -> x >= 0, x -> x - 1)
                    .mapToObj(x -> cell(x, 0)).toList();
        }
        @Override public List<GridPos> standableNeighbors(GridPos from) {
            var result = new ArrayList<GridPos>();
            for (GridPos pos : List.of(from.offset(-1, 0, 0), from.offset(1, 0, 0),
                    from.offset(0, 0, -1), from.offset(0, 0, 1))) {
                if (standable.test(pos)) result.add(pos);
            }
            return result;
        }
        @Override public boolean occupied(GridPos pos) { return pos.equals(playerCell()) || blocked.contains(pos); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return a.horizontalDistance(b) == 1; }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return false; }
        @Override public boolean canRangedAttack(GridPos from) { return clear.test(from); }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }
}
