package com.tactical.neoforge.battle;

import com.tactical.neoforge.battle.multi.BattleEligibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Authority rules that must hold for a waiting battle member without a running server:
 * the vanilla inventory/container gate and the safe join boundary.
 */
class WaitingMemberAuthorityTest {

    // ---- vanilla inventory / container gate ------------------------------

    @Test
    void playerOutsideAnyBattleKeepsVanillaBehaviour() {
        assertTrue(VanillaInventoryGuard.allowed(false, false, false),
                "a non-participant may always use its inventory and containers");
        assertTrue(VanillaInventoryGuard.allowed(false, false, true));
    }

    @Test
    void onlyOwnUnbusyTurnMayMutateItems() {
        assertTrue(VanillaInventoryGuard.allowed(true, true, false),
                "the acting member keeps native pickup/drop/right-click");
        assertFalse(VanillaInventoryGuard.allowed(true, false, false),
                "a waiting member may not move items through vanilla packets");
        assertFalse(VanillaInventoryGuard.allowed(true, false, false),
                "an unresolved projectile or animation also denies the click");
        assertTrue(VanillaInventoryGuard.allowed(true, false, true),
                "an already finished encounter no longer blocks anything");
    }

    @Test
    void onlyItemTransportActionsAreGated() {
        for (var action : net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.values()) {
            boolean expected = switch (action) {
                case DROP_ITEM, DROP_ALL_ITEMS, SWAP_ITEM_WITH_OFFHAND -> true;
                default -> false;
            };
            assertEquals(expected, VanillaInventoryGuard.guardsVanillaAction(action),
                    action + " gating must match whether it moves items");
        }
        assertFalse(VanillaInventoryGuard.guardsVanillaAction(null));
    }

    // ---- safe action boundary for joining --------------------------------

    @Test
    void joinIsRefusedWhileAnActionIsInFlight() {
        assertEquals(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY,
                BattleEligibility.evaluateJoinBoundary(true, true),
                "a moving actor, unresolved projectile or hand-over defers the join");
    }

    @Test
    void joinIsRefusedIntoAnOccupiedOrReservedCell() {
        assertEquals(BattleEligibility.Denial.OCCUPIED,
                BattleEligibility.evaluateJoinBoundary(false, false),
                "a candidate cannot be inserted onto a taken cell");
    }

    @Test
    void joinIsAdmittedOnlyAtAFreeCellWithNothingAnimating() {
        assertEquals(BattleEligibility.Denial.NONE, BattleEligibility.evaluateJoinBoundary(false, true));
    }

    @Test
    void boundaryDenialsAreHumanReadable() {
        assertFalse(BattleEligibility.describe(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY).isBlank());
        assertFalse(BattleEligibility.describe(BattleEligibility.Denial.OCCUPIED).isBlank());
    }
}
