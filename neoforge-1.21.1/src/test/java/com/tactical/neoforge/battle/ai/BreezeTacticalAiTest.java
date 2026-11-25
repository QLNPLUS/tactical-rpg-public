package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic breeze decisions: one wind charge, one long jump, ordinary ground edges, and nothing invented.
 *
 * <p>Nothing here touches a world: the context answers exactly what the server would answer, so a decision taken
 * here is a statement about what the unit wants. Every gate the real encounter re-checks at its commit point - the
 * native band, the facing decision, the arc proof and the price - is proven separately by the dedicated-server
 * fixture.</p>
 */
class BreezeTacticalAiTest {

    @Test
    void aLegalChargeIsPreferredOverEverythingElse() {
        var ai = new BreezeTacticalAi();
        assertEquals(AiAction.Kind.BREEZE_SHOT, ai.planTurn(context(100, state(true, 20, true, 20))).kind());
    }

    @Test
    void aJumpIsChosenWhenNoChargeIsLegal() {
        var ai = new BreezeTacticalAi();
        assertEquals(AiAction.Kind.BREEZE_JUMP, ai.planTurn(context(100, state(false, 0, true, 20))).kind());
    }

    @Test
    void ordinaryGroundEdgesAreWalkedWhenNeitherActionIsLegal() {
        var ai = new BreezeTacticalAi();
        var context = context(100, state(false, 0, false, 0), List.of(cell(0, 2), cell(0, 3)));
        AiAction action = ai.planTurn(context);
        assertEquals(AiAction.Kind.MOVE, action.kind());
        assertEquals(List.of(cell(0, 2), cell(0, 3)), action.path());
    }

    @Test
    void aBreezeNeverMeleesEvenWhenItsOpponentIsAdjacent() {
        var ai = new BreezeTacticalAi();
        // Adjacent opponent, no legal charge, no legal jump and no legal edge: the turn simply ends. A breeze has
        // no swing at all, so nothing here may invent one.
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, state(false, 0, false, 0))).kind());
    }

    @Test
    void nothingIsChosenWithoutAPriceTheUnitCanPay() {
        var ai = new BreezeTacticalAi();
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(19, state(true, 20, false, 0))).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(19, state(false, 0, true, 20))).kind());
    }

    @Test
    void anInvalidOrBusyUnitEndsItsTurn() {
        var ai = new BreezeTacticalAi();
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, BreezeState.NONE)).kind());
        // A busy state answers "nothing is available" even when the AP would be enough.
        var busy = new BreezeState(true, false, 0, false, 0, true, true);
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, busy)).kind());
    }

    @Test
    void aSpentMainActionIsNeverSpentTwice() {
        var ai = new BreezeTacticalAi();
        assertEquals(AiAction.Kind.END_TURN,
                ai.planTurn(context(100, new BreezeState(true, true, 20, true, 20, true, false))).kind());
    }

    @Test
    void aJumpNeverBuysASecondOne() {
        var ai = new BreezeTacticalAi();
        var context = context(100, state(false, 0, true, 20));
        AiAction first = ai.planTurn(context);
        assertEquals(AiAction.Kind.BREEZE_JUMP, first.kind());
        ai.onActionResolved(first.kind());
        // The same own turn continues after the jump: the only thing left is a newly legal shot or the end.
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context).kind());
    }

    @Test
    void aJumpMayBeFollowedByExactlyOneShot() {
        var ai = new BreezeTacticalAi();
        ai.planTurn(context(100, state(false, 0, true, 20)));
        ai.onActionResolved(AiAction.Kind.BREEZE_JUMP);
        var afterJump = context(60, state(true, 20, false, 0));
        assertEquals(AiAction.Kind.BREEZE_SHOT, ai.afterMovement(afterJump).kind());
        // And after that shot this turn has nothing left at all.
        ai.onActionResolved(AiAction.Kind.BREEZE_SHOT);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(afterJump).kind());
    }

    @Test
    void aJumpIsNeverFollowedByAWalkEvenWhenOneIsOffered() {
        var ai = new BreezeTacticalAi();
        ai.planTurn(context(100, state(false, 0, true, 20)));
        ai.onActionResolved(AiAction.Kind.BREEZE_JUMP);
        // Even with a legal slide path and no legal shot, the post-jump follow-up is the end of the turn.
        var afterJump = context(100, state(false, 0, false, 0), List.of(cell(0, 2)));
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(afterJump).kind());
    }

    @Test
    void theStateItselfRefusesAnUnpayablePrice() {
        assertTrue(state(true, 20, true, 20).canShootNow(20));
        assertFalse(state(true, 20, true, 20).canShootNow(19));
        assertFalse(state(false, 0, true, 20).canShootNow(1000));
        assertTrue(state(false, 0, true, 20).canJumpNow(20));
        assertFalse(state(false, 0, true, 20).canJumpNow(19));
        // A jump already taken this turn is never offered again, while the shot it bought still is.
        var jumped = new BreezeState(true, true, 20, true, 20, false, true);
        assertFalse(jumped.canJumpNow(1000));
        assertTrue(jumped.canShootNow(20));
        // The declining answer is what an old adapter and every fixture sees.
        assertFalse(BreezeState.NONE.valid());
        assertFalse(BreezeState.NONE.canShootNow(Integer.MAX_VALUE));
        assertFalse(BreezeState.NONE.canJumpNow(Integer.MAX_VALUE));
    }

    private static BreezeState state(boolean mayShoot, int shotCost, boolean mayJump, int jumpCost) {
        return new BreezeState(true, mayShoot, shotCost, mayJump, jumpCost, false, false);
    }

    private static GridPos cell(int dx, int dz) {
        return new GridPos(dx, 64, dz);
    }

    private static AiContext context(int ap, BreezeState state) {
        return context(ap, state, List.of());
    }

    /** Only the AP, the breeze view and any offered slide are real; every other answer is an ordinary default. */
    private static AiContext context(int ap, BreezeState state, List<GridPos> slide) {
        return new AiContext() {
            public Mob actor() { return null; }
            public GridPos actorCell() { return cell(0, 0); }
            public GridPos playerCell() { return cell(1, 0); }
            public int ap() { return ap; }
            public int moveCost() { return 5; }
            public List<GridPos> pathToPlayer() { return List.of(); }
            public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            public boolean occupied(GridPos cell) { return false; }
            public boolean inMeleeRange(GridPos from, GridPos target) { return true; }
            public boolean hasLineOfSight(GridPos from, GridPos target) { return true; }
            public void attackPlayer() { }
            public void log(String message) { }
            public BreezeState breeze() { return state; }
            public List<GridPos> breezeSlide() { return slide; }
        };
    }
}
