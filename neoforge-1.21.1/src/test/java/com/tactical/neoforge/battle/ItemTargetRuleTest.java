package com.tactical.neoforge.battle;

import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-on-entity branch: who may be the target, and the one condition under which it spends AP.
 *
 * <p>Both are pure rules on purpose - reachability and sight are measured on the real level by the
 * caller, and the item's own answer comes from the native interaction, so nothing here is a prediction
 * standing in for a server result. The rule answers with a stable code; the player-facing wording is
 * created at the display boundary, so this rules layer carries no language at all.</p>
 */
class ItemTargetRuleTest {

    private static boolean eligible(boolean sameLevel, boolean alive, boolean removed, boolean self,
                                    boolean otherBattle, boolean notParticipant, boolean foreignPlayer,
                                    boolean reachable, boolean sight) {
        return ItemTargetRule.refusal(sameLevel, alive, removed, self, otherBattle, notParticipant, foreignPlayer,
                reachable, sight) == ItemTargetRule.Denial.NONE;
    }

    @Test
    void aLiveReachableParticipantOfThisEncounterIsEligible() {
        assertTrue(eligible(true, true, false, false, false, false, false, true, true));
    }

    @Test
    void everyRefusalNamesItsOwnReason() {
        assertEquals(ItemTargetRule.Denial.DIFFERENT_DIMENSION,
                ItemTargetRule.refusal(false, true, false, false, false, false, false, true, true));
        assertEquals(ItemTargetRule.Denial.TARGET_GONE,
                ItemTargetRule.refusal(true, false, false, false, false, false, false, true, true));
        assertEquals(ItemTargetRule.Denial.TARGET_GONE,
                ItemTargetRule.refusal(true, true, true, false, false, false, false, true, true));
        assertEquals(ItemTargetRule.Denial.SELF,
                ItemTargetRule.refusal(true, true, false, true, false, false, false, true, true));
        assertEquals(ItemTargetRule.Denial.OTHER_BATTLE,
                ItemTargetRule.refusal(true, true, false, false, true, false, false, true, true));
        assertEquals(ItemTargetRule.Denial.NOT_PARTICIPANT,
                ItemTargetRule.refusal(true, true, false, false, false, true, false, true, true));
        // A foreign player and an unaffiliated unit share one code: both mean "not in this battle".
        assertEquals(ItemTargetRule.Denial.NOT_PARTICIPANT,
                ItemTargetRule.refusal(true, true, false, false, false, false, true, true, true));
        assertEquals(ItemTargetRule.Denial.TOO_FAR,
                ItemTargetRule.refusal(true, true, false, false, false, false, false, false, true));
        assertEquals(ItemTargetRule.Denial.BLOCKED,
                ItemTargetRule.refusal(true, true, false, false, false, false, false, true, false));
    }

    @Test
    void beingInsideTheZoneIsNotEnoughToBeATarget() {
        // A bystander in the zone, or this encounter's own unit after it left, is not a participant: the
        // rule refuses it before reach is even considered.
        assertEquals(ItemTargetRule.Denial.NOT_PARTICIPANT,
                ItemTargetRule.refusal(true, true, false, false, false, true, false, false, false));
    }

    @Test
    void anotherEncountersMemberIsRefusedBeforeReachIsEvenConsidered() {
        // Cross-encounter reach is not a distance problem: it is a different fight's unit.
        assertEquals(ItemTargetRule.Denial.OTHER_BATTLE,
                ItemTargetRule.refusal(true, true, false, false, true, true, true, false, false));
    }

    @Test
    void theConditionOrderIsUnchanged() {
        // The first matching condition decides, exactly as before the codes were introduced.
        assertEquals(ItemTargetRule.Denial.DIFFERENT_DIMENSION,
                ItemTargetRule.refusal(false, false, true, true, true, true, true, false, false));
        assertEquals(ItemTargetRule.Denial.TARGET_GONE,
                ItemTargetRule.refusal(true, false, true, true, true, true, true, false, false));
        assertEquals(ItemTargetRule.Denial.SELF,
                ItemTargetRule.refusal(true, true, false, true, true, true, true, false, false));
        assertEquals(ItemTargetRule.Denial.OTHER_BATTLE,
                ItemTargetRule.refusal(true, true, false, false, true, true, true, false, false));
        assertEquals(ItemTargetRule.Denial.NOT_PARTICIPANT,
                ItemTargetRule.refusal(true, true, false, false, false, true, true, false, false));
        assertEquals(ItemTargetRule.Denial.TOO_FAR,
                ItemTargetRule.refusal(true, true, false, false, false, false, false, false, false));
    }

    @Test
    void aCancelledInteractionNeverSpends() {
        assertTrue(NativeEntityUseReceipt.commits(InteractionResult.SUCCESS, false));
        assertTrue(NativeEntityUseReceipt.commits(InteractionResult.CONSUME, false));
        // The cancellation result may look like a success; the cancel still wins.
        assertFalse(NativeEntityUseReceipt.commits(InteractionResult.SUCCESS, true));
        assertFalse(NativeEntityUseReceipt.commits(InteractionResult.CONSUME, true));
        assertFalse(NativeEntityUseReceipt.commits(InteractionResult.PASS, false));
        assertFalse(NativeEntityUseReceipt.commits(InteractionResult.FAIL, false));
        assertFalse(NativeEntityUseReceipt.commits(null, false));
    }

    @Test
    void theReceiptListenerReallyReceivesCancelledEvents() throws NoSuchMethodException {
        // The bus annotation defaults receiveCanceled to false, which would hide the very event a
        // higher-priority listener cancelled. Pinned here so the flag cannot be dropped silently.
        var listener = NativeEntityUseReceipt.class.getMethod("entityInteract",
                net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract.class);
        var annotation = listener.getAnnotation(net.neoforged.bus.api.SubscribeEvent.class);
        assertTrue(annotation != null && annotation.receiveCanceled(),
                "the receipt listener must receive already cancelled events");
        assertEquals(net.neoforged.bus.api.EventPriority.LOWEST, annotation.priority(),
                "and it must still read the final state after every other listener");
    }
}
