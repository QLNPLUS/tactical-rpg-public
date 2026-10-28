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

class SkeletonTacticalAiTest {
    @Test
    void clearBowShotIgnoresVanillaFacingSight() {
        var context = context(cell(8, 0), 35, pos -> true);
        assertEquals(AiAction.Kind.RANGED, bow().planTurn(context).kind());
        assertEquals(AiAction.Kind.RANGED, bow().planTurn(context(cell(-8, 0), 35, pos -> true)).kind());
    }

    @Test
    void outOfRangeMovesIntoRangeAndReservesShotAp() {
        var ai = bow();
        var context = context(cell(18, 0), 55, pos -> pos.x() <= 16);
        AiAction move = ai.planTurn(context);
        assertEquals(List.of(cell(17, 0), cell(16, 0)), move.path());
        assertEquals(AiAction.Kind.RANGED, ai.afterMovement(context(cell(16, 0), 35, pos -> true)).kind());
        ai.onActionResolved(AiAction.Kind.RANGED);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(16, 0), 35, pos -> true)).kind());
    }

    @Test
    void coveredSkeletonFindsLateralShotInsteadOfWalkingAtPlayer() {
        var context = context(cell(8, 0), 45, pos -> pos.equals(cell(8, 1)));
        assertEquals(List.of(cell(8, 1)), bow().planTurn(context).path());
    }

    @Test
    void routeAvoidsOccupiedAndUnstandableCells() {
        Set<GridPos> walkable = Set.of(cell(8, 0), cell(8, -1), cell(9, -1), cell(9, 0), cell(9, 1), cell(8, 1));
        var context = new Context(cell(8, 0), 85, Set.of(cell(9, 0)),
                walkable::contains, pos -> pos.equals(cell(8, 1)));
        // Occupying the north cell forces the shortest route through the east branch.
        var blocked = new Context(cell(8, 0), 85, Set.of(cell(8, 1)), walkable::contains,
                pos -> pos.equals(cell(9, 1)));
        assertEquals(List.of(cell(8, 1)), bow().planTurn(context).path());
        assertEquals(List.of(cell(9, 0), cell(9, 1)), bow().planTurn(blocked).path());
    }

    @Test
    void insufficientShotApAllowsPositioningButNoShot() {
        var ai = bow();
        assertEquals(List.of(cell(8, 1)), ai.planTurn(context(cell(8, 0), 10,
                pos -> pos.equals(cell(8, 1)))).path());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 1), 0, pos -> true)).kind());
        assertEquals(AiAction.Kind.END_TURN, bow().planTurn(context(cell(8, 0), 9, pos -> true)).kind());
    }

    @Test
    void closeSkeletonRetreatsAndStillShootsButNeverSwitchesToMelee() {
        var ai = bow();
        AiAction move = ai.planTurn(context(cell(1, 0), 65, pos -> true));
        assertEquals(3, move.path().size());
        GridPos destination = move.path().getLast();
        assertTrue(Math.hypot(destination.x(), destination.z()) >= 4);
        assertEquals(AiAction.Kind.RANGED, ai.afterMovement(context(destination, 35, pos -> true)).kind());
        assertEquals(AiAction.Kind.RANGED, bow().planTurn(context(cell(1, 0), 35, pos -> true)).kind());
    }

    @Test
    void postMovementRechecksCoverAndAp() {
        var ai = bow();
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 0), 35, pos -> false)).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(8, 0), 34, pos -> true)).kind());
        assertEquals(AiAction.Kind.RANGED, ai.afterMovement(context(cell(8, 0), 35, pos -> true)).kind());
    }

    @Test
    void nonBowEquipmentUsesGenericMeleeAndEquipmentChangesAreObserved() {
        var hasBow = new AtomicBoolean(false);
        var ai = new SkeletonTacticalAi(hasBow::get);
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context(cell(1, 0), 0, pos -> true)).kind());
        assertEquals(List.of(cell(3, 0), cell(2, 0), cell(1, 0)),
                ai.planTurn(context(cell(4, 0), 100, pos -> true)).path());
        hasBow.set(true);
        assertEquals(AiAction.Kind.RANGED, ai.planTurn(context(cell(8, 0), 35, pos -> true)).kind());
    }

    @Test
    void oldContextsHaveSafeRangedDefaults() {
        AiContext old = new AiContext() {
            @Override public Mob actor() { return null; }
            @Override public GridPos actorCell() { return cell(8, 0); }
            @Override public GridPos playerCell() { return cell(0, 0); }
            @Override public int ap() { return 0; }
            @Override public int moveCost() { return 10; }
            @Override public List<GridPos> pathToPlayer() { return List.of(); }
            @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            @Override public boolean occupied(GridPos pos) { return false; }
            @Override public boolean inMeleeRange(GridPos a, GridPos b) { return false; }
            @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
            @Override public void attackPlayer() { }
            @Override public void log(String message) { }
        };
        assertFalse(old.canRangedAttack(old.actorCell()));
        assertEquals(15, old.rangedCost());
        assertEquals(10, old.meleeCost());
        assertDoesNotThrow(old::rangedAttackPlayer);
        assertEquals(AiAction.Kind.END_TURN, bow().planTurn(old).kind());
    }

    private static SkeletonTacticalAi bow() { return new SkeletonTacticalAi(() -> true); }
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
        @Override public int rangedCost() { return 35; }
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
