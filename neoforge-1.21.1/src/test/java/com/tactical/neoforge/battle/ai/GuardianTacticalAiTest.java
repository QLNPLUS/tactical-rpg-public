package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.GuardianMovement;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guardian adapter's decision, decided without a world.
 *
 * <p>The dedicated-server fixture proves what the entity really does; these tests prove the part that must be
 * decidable without one: a context that carries no policy ends its turn, a body in a real firing position spends its
 * own turn on its native beam, a body that cannot fire swims instead of inventing a melee, and after a swim the beam
 * is the only answer when the water it reached can really shoot.</p>
 */
class GuardianTacticalAiTest {

    @Test
    void aContextWithoutThisFamilysPolicyEndsItsTurnInsteadOfInventingABeam() {
        GuardianTacticalAi ai = new GuardianTacticalAi();
        AiAction action = GuardianTacticalAi.plan(true, false, true, true, true, List.of());
        assertEquals(AiAction.Kind.END_TURN, action.kind(), "an old or third-party context has no beam and no melee");
        // The same answer through the real entry point: a context that carries no policy is answered before its
        // actor is ever touched, which is exactly what a third-party or older context is.
        SilentContext silent = new SilentContext();
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(silent).kind());
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(silent).kind());
    }

    @Test
    void aBodyThatCanReallyFireSpendsItsOwnTurnOnItsNativeBeam() {
        GuardianMovement.Node here = node(0, 0);
        assertEquals(AiAction.Kind.GUARDIAN_BEAM,
                GuardianTacticalAi.plan(true, true, true, true, true, List.of(here)).kind());
        assertTrue(AiAction.guardianBeam().path().isEmpty(), "a beam carries no route");
    }

    @Test
    void aBodyThatCanFireButCannotPayEndsItsTurnRatherThanWalkingAway() {
        GuardianMovement.Node here = node(0, 0);
        // In position, but the frozen quote is not affordable: no beam, and no pointless route either.
        assertEquals(AiAction.Kind.END_TURN,
                GuardianTacticalAi.plan(true, true, true, true, false, List.of(here)).kind());
    }

    @Test
    void aBodyThatCannotFireSwimsToAFiringPosition() {
        GuardianMovement.Node step = node(2, 0);
        AiAction action = GuardianTacticalAi.plan(true, true, true, false, false, List.of(step));
        assertEquals(AiAction.Kind.GUARDIAN_SWIM, action.kind());
        assertEquals(List.of(step), action.swim(), "the planned water route is the one the server re-proves");
        assertTrue(action.path().isEmpty(), "a swim is never an ordinary floor path");
    }

    @Test
    void aBodyWithNoLegalStepEndsItsTurnInsteadOfForcingOne() {
        assertEquals(AiAction.Kind.END_TURN, GuardianTacticalAi.plan(true, true, true, false, false, List.of()).kind());
        assertEquals(AiAction.Kind.END_TURN, GuardianTacticalAi.plan(true, true, true, false, false, null).kind());
    }

    @Test
    void anUnsupportedActorOrAnUnprovenNodeEndsItsTurn() {
        GuardianMovement.Node here = node(0, 0);
        assertEquals(AiAction.Kind.END_TURN, GuardianTacticalAi.plan(false, true, true, true, true, List.of(here)).kind());
        assertEquals(AiAction.Kind.END_TURN, GuardianTacticalAi.plan(true, true, false, true, true, List.of(here)).kind());
    }

    @Test
    void afterASwimTheBeamIsTheOnlyRemainingAnswer() {
        GuardianMovement.Node arrived = node(3, 0);
        assertEquals(AiAction.Kind.GUARDIAN_BEAM, GuardianTacticalAi.afterMove(true, true, true, true, true).kind());
        // Movement is spent: a water that cannot shoot ends the turn, and this entry point cannot even name a route.
        assertEquals(AiAction.Kind.END_TURN, GuardianTacticalAi.afterMove(true, true, true, false, false).kind());
        // The planning entry point may still plan a route from the same facts, which is the difference between them.
        assertEquals(AiAction.Kind.GUARDIAN_SWIM,
                GuardianTacticalAi.plan(true, true, true, false, false, List.of(arrived)).kind());
    }

    @Test
    void theBeamIsItsOwnKindAndNeverABowOrASwing() {
        AiAction action = AiAction.guardianBeam();
        assertEquals(AiAction.Kind.GUARDIAN_BEAM, action.kind());
        assertTrue(action.path().isEmpty());
        assertTrue(action.swim().isEmpty());
        assertTrue(action.surface().isEmpty());
        assertTrue(action.hop().isEmpty());
        assertNull(action.skill());
        assertNull(action.door());
        assertNull(action.brew());
        assertEquals(-1, action.attackIndex());
    }

    // ------------------------------------------------------------------ helpers

    private static GuardianMovement.Node node(int x, int y) {
        return new GuardianMovement.Node(new GridPos(x, 64 + y, 0), new Vec3(x + 0.5D, 64.4D + y, 0.5D));
    }

    /** The smallest context that carries no family policy: it answers the defaults and nothing else. */
    private static final class SilentContext implements AiContext {
        @Override public net.minecraft.world.entity.Mob actor() {
            throw new UnsupportedOperationException("Pure decision test");
        }
        @Override public GridPos actorCell() { return new GridPos(0, 64, 0); }
        @Override public GridPos playerCell() { return new GridPos(1, 64, 0); }
        @Override public int ap() { return 100; }
        @Override public int moveCost() { return 10; }
        @Override public List<GridPos> pathToPlayer() { return List.of(); }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        @Override public boolean occupied(GridPos cell) { return false; }
        @Override public boolean inMeleeRange(GridPos from, GridPos to) { return false; }
        @Override public boolean hasLineOfSight(GridPos from, GridPos to) { return false; }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }
}
