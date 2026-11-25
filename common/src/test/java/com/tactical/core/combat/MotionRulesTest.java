package com.tactical.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules of the controlled body motion: no world, no entity, no clock. */
class MotionRulesTest {

    @Test
    void levitationRiseIsOneBlockPerLevelAndBounded() {
        assertEquals(1, MotionRules.levitationRiseBlocks(0));
        assertEquals(2, MotionRules.levitationRiseBlocks(1));
        assertEquals(3, MotionRules.levitationRiseBlocks(2));
        assertEquals(MotionRules.LEVITATION_MAX_RISE_BLOCKS, MotionRules.levitationRiseBlocks(9));
        // A negative amplifier (a modded effect) never asks for less than one block.
        assertEquals(1, MotionRules.levitationRiseBlocks(-3));
    }

    @Test
    void levitationTargetNeverRisesThroughACeiling() {
        // Free space is the smaller answer: a body under a low ceiling stops below its wanted height.
        assertEquals(65.0D, MotionRules.levitationTargetFeet(64.0D, 2, 1), 1.0E-9D);
        assertEquals(64.0D, MotionRules.levitationTargetFeet(64.0D, 2, 0), 1.0E-9D);
        // A negative free-space measurement can never lower the body.
        assertEquals(64.0D, MotionRules.levitationTargetFeet(64.0D, 1, -5), 1.0E-9D);
        assertEquals(68.5D, MotionRules.levitationTargetFeet(64.5D, 4, 9), 1.0E-9D);
    }

    @Test
    void impulseSettlesOnlyWhenRestingAndStill() {
        assertTrue(MotionRules.impulseSettled(true, 0.0D));
        assertTrue(MotionRules.impulseSettled(true, 0.0008D));
        assertFalse(MotionRules.impulseSettled(true, 0.01D));
        assertFalse(MotionRules.impulseSettled(false, 0.0D));
    }

    @Test
    void impulseBudgetIsFinite() {
        assertFalse(MotionRules.impulseExpired(MotionRules.PUSH_MAX_TICKS - 1));
        assertTrue(MotionRules.impulseExpired(MotionRules.PUSH_MAX_TICKS));
        assertTrue(MotionRules.impulseExpired(MotionRules.PUSH_MAX_TICKS + 5));
    }

    @Test
    void nativeBoundsAreFrozen() {
        // The native entry really makes five attempts of eight blocks; a later change must be deliberate.
        assertEquals(5, MotionRules.SHULKER_TELEPORT_ATTEMPTS);
        assertEquals(8, MotionRules.SHULKER_TELEPORT_RANGE);
        assertEquals(400, MotionRules.SHULKER_SHOT_RANGE_SQ);
        assertEquals(100, MotionRules.SHULKER_PEEK_OPEN);
        assertEquals(0, MotionRules.SHULKER_PEEK_CLOSED);
        assertEquals(200, MotionRules.SHULKER_BULLET_FLIGHT_TICKS);
        // The extended lifetime is what keeps a paid, curving native shot from being deleted early.
        assertTrue(MotionRules.SHULKER_BULLET_FLIGHT_TICKS > 80);
        assertEquals(20, MotionRules.SHULKER_BULLET_COST);
        assertEquals(20, MotionRules.SHULKER_TELEPORT_COST);
        // A reaction is priced at the base only, so it can never inherit a paid-action surcharge.
        assertEquals(MotionRules.SHULKER_TELEPORT_COST, MotionRules.SHULKER_REACTION_COST);
    }

    @Test
    void eachKindHasItsOwnQuotaSoARelocationCanBeFollowedByOneShot() {
        // One main attack and one maneuver per own turn, one reaction per own cycle: three separate counts.
        assertEquals(1, MotionRules.SHULKER_SHOT_QUOTA);
        assertEquals(1, MotionRules.SHULKER_MANEUVER_QUOTA);
        assertEquals(1, MotionRules.SHULKER_REACTION_QUOTA);
        assertTrue(MotionRules.withinQuota(0, MotionRules.SHULKER_SHOT_QUOTA));
        assertFalse(MotionRules.withinQuota(1, MotionRules.SHULKER_SHOT_QUOTA));
        // A spent maneuver must not close the shot quota: that was the defect this rule replaced.
        assertTrue(MotionRules.withinQuota(0, MotionRules.SHULKER_SHOT_QUOTA));
        // A zero quota is never available, whatever the used count says.
        assertFalse(MotionRules.withinQuota(0, 0));
    }

    @Test
    void openWindowIsMeasuredInTurnOpportunitiesNotWallClockTicks() {
        // A window shorter than one curving native flight would expire before the blocked opponent could act.
        assertEquals(1, MotionRules.SHULKER_OPEN_WINDOW_TURNS);
        assertTrue(MotionRules.SHULKER_OPEN_WINDOW_TURNS > 0);
    }

    @Test
    void impulseIsClippedFromTheWholeSegmentDownToNoDisplacement() {
        double[] fractions = MotionRules.impulseClipFractions(4);
        assertEquals(5, fractions.length);
        assertEquals(1.0D, fractions[0], 1.0E-12D);
        assertEquals(0.75D, fractions[1], 1.0E-12D);
        assertEquals(0.5D, fractions[2], 1.0E-12D);
        assertEquals(0.25D, fractions[3], 1.0E-12D);
        // The last sample is "do not move": a push the world refuses leaves the body where it really is.
        assertEquals(0.0D, fractions[4], 1.0E-12D);
        for (int index = 1; index < fractions.length; index++) {
            assertTrue(fractions[index] < fractions[index - 1], "the walk is strictly descending");
        }
        assertEquals(2, MotionRules.impulseClipFractions(0).length, "a zero request still samples all and none");
    }

    /** The single-writer decision has to be exactly as narrow as the situation it describes. */
    @Test
    void onlyTheActiveBattleOwnsTheOwnLocalPlayerPhysics() {
        // The one case the seam exists for: this client's own body, inside an active battle it is part of.
        assertTrue(MotionRules.battleOwnsLocalTravel(true, true, true, true));

        // A dedicated server never owns a local player's integration: it has none.
        assertFalse(MotionRules.battleOwnsLocalTravel(false, true, true, true));
        // A remote member or a mob is never the local player, and their motion must stay native.
        assertFalse(MotionRules.battleOwnsLocalTravel(true, false, true, true));
        // No active stamped battle means no ownership, whatever else is true.
        assertFalse(MotionRules.battleOwnsLocalTravel(true, true, false, true));
        assertFalse(MotionRules.battleOwnsLocalTravel(true, true, false, false));
        // A local player that is not one of its participants keeps vanilla physics: leaving or a stale stamp
        // must hand the body straight back.
        assertFalse(MotionRules.battleOwnsLocalTravel(true, true, true, false));

        // Every remaining combination is false, so no wider reading can sneak in.
        for (int mask = 0; mask < 16; mask++) {
            boolean client = (mask & 1) != 0;
            boolean local = (mask & 2) != 0;
            boolean active = (mask & 4) != 0;
            boolean participant = (mask & 8) != 0;
            assertEquals(client && local && active && participant,
                    MotionRules.battleOwnsLocalTravel(client, local, active, participant),
                    "combination " + mask);
        }
    }
}
