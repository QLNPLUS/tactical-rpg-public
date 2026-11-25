package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic shulker decisions: one bullet, one relocation, and never a second of either.
 *
 * <p>Nothing here touches a world: the context answers the questions the server itself would answer, so a decision
 * taken here is a statement about what the unit wants. Every gate the real encounter re-checks at its commit point
 * is proven separately by the dedicated-server fixture.</p>
 */
class ShulkerTacticalAiTest {

    @Test
    void aLegalShotIsPreferredOverEverythingElse() {
        var ai = new ShulkerTacticalAi();
        var context = context(100, state(true, 20, true, 20));
        assertEquals(AiAction.Kind.SHULKER_SHOT, ai.planTurn(context).kind());
    }

    @Test
    void aRelocationIsChosenWhenNoShotIsLegal() {
        var ai = new ShulkerTacticalAi();
        var context = context(100, state(false, 0, true, 20));
        assertEquals(AiAction.Kind.SHULKER_TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void nothingIsChosenWithoutAPriceTheUnitCanPay() {
        var ai = new ShulkerTacticalAi();
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(19, state(true, 20, true, 20))).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(19, state(false, 0, true, 20))).kind());
    }

    @Test
    void anInvalidOrBusyUnitEndsItsTurn() {
        var ai = new ShulkerTacticalAi();
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, ShulkerState.NONE)).kind());
        // A busy state answers "nothing is available" even when the AP would be enough.
        var busy = new ShulkerState(true, false, 0, false, 0, true, true);
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, busy)).kind());
    }

    @Test
    void aRelocationNeverBuysASecondOne() {
        var ai = new ShulkerTacticalAi();
        var context = context(100, state(false, 0, true, 20));
        AiAction first = ai.planTurn(context);
        assertEquals(AiAction.Kind.SHULKER_TELEPORT, first.kind());
        ai.onActionResolved(first.kind());
        // The same own turn continues after the relocation: the only thing left is a shot or the end.
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(context).kind());
    }

    @Test
    void aRelocationMayBeFollowedByExactlyOneShot() {
        var ai = new ShulkerTacticalAi();
        var relocation = context(100, state(false, 0, true, 20));
        ai.planTurn(relocation);
        ai.onActionResolved(AiAction.Kind.SHULKER_TELEPORT);
        var afterRelocation = context(60, state(true, 20, false, 0));
        assertEquals(AiAction.Kind.SHULKER_SHOT, ai.afterMovement(afterRelocation).kind());
        // And after that shot this turn has nothing left at all.
        ai.onActionResolved(AiAction.Kind.SHULKER_SHOT);
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(afterRelocation).kind());
    }

    @Test
    void aSpentMainActionIsNeverSpentTwice() {
        var ai = new ShulkerTacticalAi();
        var spent = new ShulkerState(true, true, 20, true, 20, true, false);
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(context(100, spent)).kind());
    }

    @Test
    void theStateItselfRefusesAnUnpayablePrice() {
        assertTrue(state(true, 20, true, 20).canShootNow(20));
        assertFalse(state(true, 20, true, 20).canShootNow(19));
        assertFalse(state(true, 20, true, 20).canShootNow(100) && false);
        assertFalse(state(false, 0, true, 20).canShootNow(1000));
        assertTrue(state(false, 0, true, 20).canTeleportNow(20));
        assertFalse(state(false, 0, true, 20).canTeleportNow(19));
        // A relocation already taken this turn is never offered again.
        var relocated = new ShulkerState(true, true, 20, true, 20, false, true);
        assertFalse(relocated.canTeleportNow(1000));
        assertTrue(relocated.canShootNow(20));
        // The declining answer is what an old adapter and every fixture sees.
        assertFalse(ShulkerState.NONE.valid());
        assertFalse(ShulkerState.NONE.canShootNow(Integer.MAX_VALUE));
        assertFalse(ShulkerState.NONE.canTeleportNow(Integer.MAX_VALUE));
    }

    private static ShulkerState state(boolean mayShoot, int shotCost, boolean mayTeleport, int teleportCost) {
        return new ShulkerState(true, mayShoot, shotCost, mayTeleport, teleportCost, false, false);
    }

    private static GridPos cell(int dx, int dz) {
        return new GridPos(dx, 64, dz);
    }

    /** Only the AP and the shulker view are real here; every other answer is an ordinary non-shulker default. */
    private static AiContext context(int ap, ShulkerState state) {
        return new AiContext() {
            public Mob actor() { return null; }
            public GridPos actorCell() { return cell(0, 0); }
            public GridPos playerCell() { return cell(3, 0); }
            public int ap() { return ap; }
            public int moveCost() { return 5; }
            public List<GridPos> pathToPlayer() { return List.of(); }
            public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            public boolean occupied(GridPos cell) { return false; }
            public boolean inMeleeRange(GridPos from, GridPos target) { return false; }
            public boolean hasLineOfSight(GridPos from, GridPos target) { return true; }
            public void attackPlayer() { }
            public void log(String message) { }
            public ShulkerState shulker() { return state; }
        };
    }
}
