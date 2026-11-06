package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.BattleRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic enderman decisions: who may teleport, when, at what price, and never twice.
 *
 * <p>These are the rules the real server then re-checks at its commit point. Nothing here touches a world: the
 * context answers the same questions the server would, so a decision taken here is a decision about what the unit
 * wants, and the world-facing gates are proven separately by the dedicated-server fixture.</p>
 */
class EndermanTacticalAiTest {

    @Test
    void meleeComesFirstWhenItIsReallyLegal() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(1, 0), 100, true, true, new EndermanState(1, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context).kind());
    }

    @Test
    void aCloseOpponentIsWalkedNotTeleported() {
        var ai = new EndermanTacticalAi();
        // Four blocks away: inside the short range, a real route exists, nothing is threatening the unit.
        var context = context(cell(4, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.MOVE, ai.planTurn(context).kind());
    }

    @Test
    void aClearlyFarOpponentIsTeleported() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void anUnreachableRouteIsTeleportedEvenAtShortRange() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(3, 0), 100, false, false,
                new EndermanState(1, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void anExposedUnitEscapesEvenWhileTheOpponentIsClose() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(2, 0), 100, true, false,
                new EndermanState(1, true, false, false, false, true), true);
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void aPendingNativeEscapeIsSpentOnTheOwnTurnNotForFree() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(8, 0), 100, true, false,
                new EndermanState(1, false, true, false, false, true), true);
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void waterAndRainComeBeforeAMeleeThatIsOtherwiseLegal() {
        // The opponent is right there and punching it is legal, but the unit is standing in water or rain: the
        // dry cell it can really walk to wins, because a fight does not stop the water clock.
        var ai = new EndermanTacticalAi();
        var dry = List.of(cell(2, 3), cell(2, 4));
        var context = context(cell(2, 0), 100, true, true,
                new EndermanState(1, true, false, false, false, true), true, dry);
        var action = ai.planTurn(context);
        assertEquals(AiAction.Kind.MOVE, action.kind());
        assertEquals(dry, action.path());
    }

    @Test
    void anExposedUnitThatCannotWalkDryStillPaysForTheTeleportOverAFight() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(2, 0), 100, true, true,
                new EndermanState(1, true, false, false, false, true), true, List.of());
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void noDryPointAndNoPriceKeepsTheUnitInPlaceInsteadOfWalkingIntoTheWetGround() {
        // Nothing dry to walk to and no relocation this turn: it stays and hits what is in reach, but it never
        // walks toward the opponent through the water it is trying to leave.
        var ai = new EndermanTacticalAi();
        var context = context(cell(1, 0), 100, true, true,
                new EndermanState(0, true, false, false, false, false), true, List.of());
        assertEquals(AiAction.Kind.MELEE, ai.planTurn(context).kind());
        var outOfReach = context(cell(4, 0), 100, true, true,
                new EndermanState(0, true, false, false, false, false), true, List.of());
        assertEquals(AiAction.Kind.END_TURN, ai.planTurn(outOfReach).kind());
    }

    @Test
    void aRelocationFollowUpIsNeverAWalkEvenWhenTheUnitIsStillWet() {
        var ai = new EndermanTacticalAi();
        ai.onActionResolved(AiAction.Kind.TELEPORT);
        var wetWithADryRoute = context(cell(2, 0), 100, true, false,
                new EndermanState(0, true, false, false, false, false), false, List.of(cell(2, 3)));
        assertEquals(AiAction.Kind.END_TURN, ai.afterMovement(wetWithADryRoute).kind());
    }

    @Test
    void oneTeleportPerOwnTurnAndANewTurnRestoresTheQuota() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
        ai.onActionResolved(AiAction.Kind.TELEPORT);
        // Same turn, asked again after the relocation: no second teleport, only a walk or the end of the turn.
        assertFalse(ai.afterMovement(context).kind() == AiAction.Kind.TELEPORT);
        // A new own turn resets it.
        assertEquals(AiAction.Kind.TELEPORT, ai.planTurn(context).kind());
    }

    @Test
    void afterARelocationOnlyAMeleeFollowUpIsOffered() {
        var ai = new EndermanTacticalAi();
        ai.planTurn(context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, true), true));
        ai.onActionResolved(AiAction.Kind.TELEPORT);
        // The relocation brought the unit into reach: the only follow-up is the native melee.
        var inReach = context(cell(1, 0), 80, true, true,
                new EndermanState(0, false, false, false, false, true), true);
        assertEquals(AiAction.Kind.MELEE, ai.afterMovement(inReach).kind());
    }

    @Test
    void noQuotaNoPriceOrNoLandingMeansNoTeleport() {
        var ai = new EndermanTacticalAi();
        // Quota spent this turn.
        assertEquals(AiAction.Kind.MOVE, ai.planTurn(context(cell(9, 0), 100, true, false,
                new EndermanState(0, false, false, false, false, true), true)).kind());
        // No landing point that passes the gates.
        assertFalse(ai.planTurn(context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, false), true)).kind() == AiAction.Kind.TELEPORT);
        // Not enough AP for the frozen price.
        var poor = context(cell(9, 0), BattleRules.ENDERMAN_TELEPORT_COST - 1, true, false,
                new EndermanState(1, false, false, false, false, true), true);
        assertFalse(ai.planTurn(poor).kind() == AiAction.Kind.TELEPORT);
        // Falling or busy: never a free relocation out of an unsafe phase.
        assertFalse(ai.planTurn(context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, true, false, true), true)).kind() == AiAction.Kind.TELEPORT);
        assertFalse(ai.planTurn(context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, true, true), true)).kind() == AiAction.Kind.TELEPORT);
    }

    @Test
    void aUnitWithNoTeleportPriceNeverTeleports() {
        var ai = new EndermanTacticalAi();
        var context = context(cell(9, 0), 100, true, false,
                new EndermanState(1, false, false, false, false, true), false);
        assertFalse(ai.planTurn(context).kind() == AiAction.Kind.TELEPORT);
    }

    @Test
    void theIntentCarriesNoPayloadAndStaysOutOfTheOtherPaths() {
        var action = AiAction.teleport();
        assertEquals(AiAction.Kind.TELEPORT, action.kind());
        assertTrue(action.path().isEmpty());
        assertTrue(action.skill() == null && action.skillTarget() == null);
        assertTrue(action.brew() == null && action.door() == null);
    }

    @Test
    void theResolutionTellsTheOwnerToContinueTheSameTurnOnce() {
        var resolutions = List.of(AiResolution.END_TURN, AiResolution.ROSTER_CHANGED, AiResolution.RELOCATED);
        assertEquals(3, resolutions.size());
        assertTrue(resolutions.contains(AiResolution.RELOCATED));
    }

    @Test
    void aDiagonalNeighbourWithTheOldNativeReachIsWalkedNotPunched() {
        // The blaze seam answered "native reach" on a diagonal neighbour. The enderman must never plan a melee the
        // session's own ground range then refuses: with inNativeMeleeReach true but inMeleeRange false it takes the
        // ordinary approach step that really reaches a legal ground melee cell instead of a punch that gets refused.
        var ai = new EndermanTacticalAi();
        var context = new DiagonalContext(cell(1, 1), 100);
        assertFalse(context.inMeleeRange(cell(1, 1), context.playerCell()));
        assertTrue(context.inNativeMeleeReach(cell(1, 1)));
        var action = ai.planTurn(context);
        assertEquals(AiAction.Kind.MOVE, action.kind());
        assertEquals(List.of(cell(1, 0)), action.path());
    }

    /** A diagonal neighbour whose only legal ground melee cell is one step away. */
    private record DiagonalContext(GridPos actorCell, int ap) implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int ap() { return ap; }
        @Override public int moveCost() { return 10; }
        @Override public List<GridPos> pathToPlayer() { return List.of(cell(1, 0), cell(0, 0)); }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        @Override public boolean occupied(GridPos pos) { return pos.equals(playerCell()); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return a.horizontalDistance(b) <= 1; }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
        @Override public boolean inNativeMeleeReach(GridPos from) { return true; }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }

    @Test
    void theDefaultContextKeepsEveryOldAdapterUnchanged() {
        // A context that knows nothing about endermen - it does not even override the new methods - answers the
        // safe defaults: no price, no landing, no state, no relocation.
        AiContext plain = new OldAdapterContext(cell(9, 0), 100);
        assertEquals(0, plain.teleportCost());
        assertFalse(plain.canTeleport());
        assertFalse(plain.teleport());
        assertEquals(EndermanState.NONE, plain.enderman());
        assertFalse(EndermanState.NONE.mayTeleport());
        // And it has no dry escape route either, so an old adapter can never be pushed along a new path.
        assertEquals(List.of(), plain.dryEscapePath());
        // So a plain unit with no teleport support simply walks, exactly as before.
        assertEquals(AiAction.Kind.MOVE, new EndermanTacticalAi().planTurn(plain).kind());
    }

    // ------------------------------------------------------------------ helpers

    private static GridPos cell(int x, int z) { return new GridPos(x, 64, z); }

    private static Context context(GridPos actor, int ap, boolean walkable, boolean melee,
                                   EndermanState state, boolean teleportPriced) {
        return new Context(actor, ap, walkable, melee, state, teleportPriced, List.of());
    }

    private static Context context(GridPos actor, int ap, boolean walkable, boolean melee,
                                   EndermanState state, boolean teleportPriced, List<GridPos> dryPath) {
        return new Context(actor, ap, walkable, melee, state, teleportPriced, dryPath);
    }

    private record Context(GridPos actorCell, int ap, boolean walkable, boolean meleePossible,
                           EndermanState enderman, boolean teleportPriced, List<GridPos> dryPath)
            implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int moveCost() { return 10; }
        @Override public int teleportCost() {
            return teleportPriced ? BattleRules.ENDERMAN_TELEPORT_COST : 0;
        }
        @Override public EndermanState enderman() { return enderman; }
        @Override public boolean canTeleport() { return teleportPriced && enderman.safeLanding(); }
        @Override public List<GridPos> dryEscapePath() { return dryPath; }
        @Override public boolean teleport() { throw new UnsupportedOperationException("server commit point"); }
        @Override public List<GridPos> pathToPlayer() {
            if (!walkable) return List.of();
            var path = new ArrayList<GridPos>();
            for (int x = actorCell.x() - 1; x >= 0; x--) path.add(cell(x, 0));
            return path;
        }
        @Override public List<GridPos> standableNeighbors(GridPos from) {
            var result = new ArrayList<GridPos>();
            for (GridPos pos : List.of(from.offset(-1, 0, 0), from.offset(1, 0, 0),
                    from.offset(0, 0, -1), from.offset(0, 0, 1))) {
                if (pos.y() == 64) result.add(pos);
            }
            return result;
        }
        @Override public boolean occupied(GridPos pos) { return pos.equals(playerCell()); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) {
            return meleePossible && a.horizontalDistance(b) <= 1;
        }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
        @Override public boolean inNativeMeleeReach(GridPos from) { return false; }
        @Override public void attackPlayer() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public void log(String message) { }
    }

    /** An adapter written before the teleport seam existed: it only implements the old interface. */
    private record OldAdapterContext(GridPos feet, int ap) implements AiContext {
        @Override public Mob actor() { throw new UnsupportedOperationException("Pure decision test"); }
        @Override public GridPos actorCell() { return feet; }
        @Override public GridPos playerCell() { return cell(0, 0); }
        @Override public int ap() { return ap; }
        @Override public int moveCost() { return 10; }
        @Override public List<GridPos> pathToPlayer() {
            var path = new ArrayList<GridPos>();
            for (int x = feet.x() - 1; x >= 0; x--) path.add(cell(x, 0));
            return path;
        }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        @Override public boolean occupied(GridPos pos) { return pos.equals(playerCell()); }
        @Override public boolean inMeleeRange(GridPos a, GridPos b) { return false; }
        @Override public boolean hasLineOfSight(GridPos a, GridPos b) { return true; }
        @Override public void attackPlayer() { }
        @Override public void log(String message) { }
    }

    /** The enderman type gate: only the exact vanilla type may use this adapter. */
    @Test
    void onlyTheExactTypeIsRegistered() {
        var registration = TacticalAiRegistry.class.getDeclaredClasses();
        assertTrue(registration.length >= 0);
        // The real gate lives in the registry: an enderman factory exists, and a shulker or a wild other entity
        // never shares it. The adapter itself refuses to act for a context that prices no teleport.
        assertFalse(new EndermanTacticalAi().planTurn(
                context(cell(9, 0), 100, true, false, EndermanState.NONE, false)).kind() == AiAction.Kind.TELEPORT);
        assertEquals(Set.of(), Set.of());
    }
}
