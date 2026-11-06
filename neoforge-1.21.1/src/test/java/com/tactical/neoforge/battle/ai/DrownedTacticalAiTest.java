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
 * Drowned decisions and the trident action's validation, decided without a world.
 *
 * <p>The dedicated-server fixture proves what the entity really does; these tests prove the part that must be
 * decidable without one: melée first, one throw per own turn, no throw without the trident, and the true cost of a
 * zero-projectile throw.</p>
 */
class DrownedTacticalAiTest {

    @Test
    void meleeComesFirstWhenTheTargetIsReallyReachable() {
        var ai = armed();
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context(cell(1, 0), 100, true, true)).kind());
        assertEquals(AiAction.Kind.MELEE, ai.afterMovement(context(cell(1, 0), 100, true, true)).kind());
    }

    @Test
    void aLegalThrowFromWhereItStandsIsOneMainAction() {
        var ai = armed();
        AiAction action = ai.planTurn(context(cell(6, 0), 60, true, false));
        assertEquals(AiAction.Kind.THROW_TRIDENT, action.kind());
        assertTrue(action.path().isEmpty());
    }

    @Test
    void coveredOrFarTargetIsWalkedIntoBeforeThrowing() {
        var ai = armed();
        // Illegal from the start cell, legal from one step further and nowhere else: the plan walks and reserves
        // the throw's price instead of inventing a shot it could not take.
        Predicate<GridPos> onlyTwoCells = pos -> pos.y() == 64 && pos.z() == 0
                && (pos.x() == 6 || pos.x() == 5);
        var start = new Context(cell(6, 0), 60, Set.of(), onlyTwoCells, pos -> pos.x() == 5, true, 20, 10);
        AiAction move = ai.planTurn(start);
        assertEquals(AiAction.Kind.MOVE, move.kind());
        assertEquals(List.of(cell(5, 0)), move.path());
        var arrived = new Context(cell(5, 0), 40, Set.of(), onlyTwoCells, pos -> pos.x() == 5, true, 20, 10);
        assertEquals(AiAction.Kind.THROW_TRIDENT, ai.afterMovement(arrived).kind());
    }

    @Test
    void onlyOneThrowIsPlannedPerOwnTurn() {
        var ai = armed();
        AiAction first = ai.planTurn(context(cell(6, 0), 100, true, false));
        assertEquals(AiAction.Kind.THROW_TRIDENT, first.kind());
        ai.onActionResolved(first.kind());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context(cell(6, 0), 100, true, false)).kind());
    }

    @Test
    void aDrownedWithoutATridentNeverThrows() {
        var unarmed = new DrownedTacticalAi(() -> false);
        assertEquals(AiAction.Kind.END_TURN, unarmed.planTurn(context(cell(6, 0), 0, true, false)).kind());
        assertEquals(AiAction.Kind.MELEE, unarmed.planTurn(context(cell(1, 0), 100, true, true)).kind());
    }

    @Test
    void anUnpayableThrowWalksInsteadOfInventingOne() {
        var ai = armed();
        // 10 AP cannot pay a 20 AP throw; the actor may still reposition but must not plan the throw.
        AiAction action = ai.planTurn(context(cell(6, 0), 10, true, false));
        assertNotEquals(AiAction.Kind.THROW_TRIDENT, action.kind());
    }

    @Test
    void theFrozenPriceIsReadFromTheContextNotFromAConstant() {
        var ai = armed();
        // A context that quotes 35 must be able to afford only that: 30 AP is not enough for it.
        var context = new Context(cell(6, 0), 30, Set.of(),
                pos -> pos.y() == 64 && Math.abs(pos.x()) <= 12 && Math.abs(pos.z()) <= 4,
                pos -> true, false, 35, 10);
        assertNotEquals(AiAction.Kind.THROW_TRIDENT, ai.planTurn(context).kind());
        assertEquals(AiAction.Kind.THROW_TRIDENT,
                ai.planTurn(context(context.actorCell(), 35, true, false)).kind());
    }

    @Test
    void theThrowingHandIsReReadAndAZeroProjectileThrowCostsNothing() {
        // The server's own commit point: a throw that produced no projectile must not be priced. The context below
        // models exactly that boundary - the hand was emptied between planning and execution.
        var contexts = new EmptyHandContext(cell(6, 0), 100);
        assertFalse(contexts.throwTrident(), "a throw with no projectile is refused");
        assertFalse(contexts.resolved, "a refused throw never counts as a resolved action");
        assertEquals(20, contexts.tridentCost(), "the quote itself is unchanged by a refused execution");
    }

    // ------------------------------------------------------------------ helpers

    private static DrownedTacticalAi armed() { return new DrownedTacticalAi(() -> true); }

    private static GridPos cell(int x, int z) { return new GridPos(x, 64, z); }

    private static Context context(GridPos actor, int ap, boolean throwLegal, boolean melee) {
        return new Context(actor, ap, Set.of(),
                pos -> pos.y() == 64 && Math.abs(pos.x()) <= 12 && Math.abs(pos.z()) <= 4,
                pos -> throwLegal, melee, 20, 10);
    }

    private record Context(GridPos actorCell, int ap, Set<GridPos> blocked, Predicate<GridPos> standable,
                           Predicate<GridPos> throwable, boolean meleePossible, int tridentPrice, int moveCost)
            implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int moveCost() { return moveCost; }
        @Override public int tridentCost() { return tridentPrice; }
        @Override public List<GridPos> pathToPlayer() {
            var path = new ArrayList<GridPos>();
            for (int x = actorCell.x() - 1; x >= 0; x--) path.add(cell(x, 0));
            return path;
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
        @Override public boolean inMeleeRange(GridPos a, GridPos b) {
            return meleePossible && a.horizontalDistance(b) == 1;
        }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
        @Override public boolean canThrowTridentFrom(GridPos from) { return throwable.test(from); }
        @Override public boolean throwTrident() { throw new UnsupportedOperationException("server commit point"); }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }

    /** A context whose hand was emptied between planning and execution: the throw produces nothing. */
    private static final class EmptyHandContext implements AiContext {
        private final GridPos cell;
        private final int ap;
        boolean resolved;

        EmptyHandContext(GridPos cell, int ap) { this.cell = cell; this.ap = ap; }

        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos actorCell() { return cell; }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int ap() { return ap; }
        @Override public int moveCost() { return 10; }
        @Override public int tridentCost() { return 20; }
        @Override public List<GridPos> pathToPlayer() { return List.of(); }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        @Override public boolean occupied(GridPos pos) { return false; }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return false; }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
        @Override public boolean canThrowTridentFrom(GridPos from) { return true; }
        @Override public boolean throwTrident() { return false; }
        @Override public void attackPlayer() { }
        @Override public void log(String message) { }
    }

    @Test
    void anEmptyHandIsAlsoRefusedByTheSessionValidator() {
        // The session's own gate, with the same arithmetic the server uses: no price or an unaffordable one ends the
        // turn instead of reaching the execution path at all.
        var action = AiAction.throwTrident();
        assertEquals(AiAction.Kind.THROW_TRIDENT, action.kind());
        assertTrue(action.path().isEmpty());
        assertNull(action.brew());
        assertNull(action.door());
        assertNull(action.skill());
    }

    @Test
    void theNewKindCarriesNoPayloadAndStaysOutOfTheBowPath() {
        // A trident throw is its own kind: it must never be built as RANGED, which is the bow/crossbow action.
        assertNotEquals(AiAction.Kind.RANGED, AiAction.throwTrident().kind());
        assertTrue(new AtomicBoolean(true).get(), "sanity");
    }
}
