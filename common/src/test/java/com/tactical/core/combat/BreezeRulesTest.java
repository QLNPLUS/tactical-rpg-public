package com.tactical.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breeze's frozen numbers and pure decisions, asserted without a world.
 *
 * <p>Each case pins something a runtime fixture could only observe indirectly: the native firing band, the native
 * difficulty spread, the native five launch angles, the native take-off gates, the arc integration and the
 * integral move quotas that must not be confused with the shared surcharge clock.</p>
 */
class BreezeRulesTest {

    @Test
    void pricesAndQuotasAreTheDesignValues() {
        assertEquals(20, BreezeRules.WIND_CHARGE_COST);
        assertEquals(20, BreezeRules.JUMP_COST);
        assertEquals(1, BreezeRules.SHOT_QUOTA);
        assertEquals(1, BreezeRules.MANEUVER_QUOTA);
    }

    @Test
    void oneShotAndOneJumpPerTurnAndNoSecondOfEither() {
        assertTrue(BreezeRules.withinQuota(0, BreezeRules.SHOT_QUOTA));
        assertFalse(BreezeRules.withinQuota(1, BreezeRules.SHOT_QUOTA));
        assertTrue(BreezeRules.withinQuota(0, BreezeRules.MANEUVER_QUOTA));
        assertFalse(BreezeRules.withinQuota(1, BreezeRules.MANEUVER_QUOTA));
        // A spent quota is spent whatever the shared paid-action clock says: the two counters are independent.
        assertFalse(BreezeRules.withinQuota(1, BreezeRules.SHOT_QUOTA));
        assertTrue(BreezeRules.withinQuota(0, BreezeRules.SHOT_QUOTA));
        // A negative quota is treated as no quota rather than as an unbounded allowance.
        assertFalse(BreezeRules.withinQuota(0, -3));
    }

    @Test
    void theNativeFiringBandIsExclusiveAtBothEnds() {
        assertFalse(BreezeRules.withinShotBand(4.0D));
        assertTrue(BreezeRules.withinShotBand(4.0001D));
        assertTrue(BreezeRules.withinShotBand(255.9999D));
        assertFalse(BreezeRules.withinShotBand(256.0D));
        assertFalse(BreezeRules.withinShotBand(0.0D));
        assertFalse(BreezeRules.withinShotBand(1024.0D));
    }

    @Test
    void theNativeSpreadFollowsDifficultyAndKeepsVanillasSign() {
        assertEquals(5, BreezeRules.shotSpread(0));
        assertEquals(1, BreezeRules.shotSpread(1));
        // Vanilla hands 5 - 4 * difficulty straight to shoot(), so hard really is negative and is mirrored.
        assertEquals(-7, BreezeRules.shotSpread(3));
        // Only the difficulty index is clamped, to the four values vanilla has.
        assertEquals(-7, BreezeRules.shotSpread(9));
        assertEquals(5, BreezeRules.shotSpread(-2));
    }

    @Test
    void theOnlyFiveNativeAnglesAreKeptAndTheCopiesStayIndependent() {
        int[] angles = BreezeRules.jumpAngles();
        assertEquals(5, angles.length);
        assertEquals(40, angles[0]);
        assertEquals(55, angles[1]);
        assertEquals(60, angles[2]);
        assertEquals(75, angles[3]);
        assertEquals(80, angles[4]);
        angles[0] = 1;
        assertEquals(40, BreezeRules.jumpAngles()[0], "the constant must not be mutated through a copy");
    }

    @Test
    void theNativeTakeOffGatesAreAllRequired() {
        assertTrue(BreezeRules.jumpAllowed(true, false, false, 4, 8.0D));
        assertTrue(BreezeRules.jumpAllowed(false, true, false, 4, 8.0D));
        assertFalse(BreezeRules.jumpAllowed(false, false, false, 4, 8.0D), "mid-air is not a take-off");
        assertFalse(BreezeRules.jumpAllowed(true, true, true, 4, 8.0D), "the water-swim state refuses the jump");
        assertFalse(BreezeRules.jumpAllowed(true, false, false, 3, 8.0D), "head room is measured in whole blocks");
        assertFalse(BreezeRules.jumpAllowed(true, false, false, 4, BreezeRules.JUMP_TOO_CLOSE),
                "a target nearer than the native minimum leaves no room for the arc");
        assertFalse(BreezeRules.jumpAllowed(true, false, false, 4, BreezeRules.JUMP_AGGRO_RANGE),
                "a target at the aggro bound is already out of range");
        assertTrue(BreezeRules.jumpAllowed(true, false, false, 4, BreezeRules.JUMP_AGGRO_RANGE - 0.01D));
    }

    @Test
    void theArcIntegrationMirrorsTheNativeDiscardFrictionStep() {
        // LongJump.start sets discardFriction(true) with the launch vector, and LivingEntity.travel then writes
        // (dx, dy - gravity, dz) without any friction factor: the horizontal step is carried over un-dragged.
        double[] position = {0.0D, 10.0D, 0.0D};
        double[] velocity = {0.4D, 0.5D, 0.0D};
        double[] first = BreezeRules.stepAirborne(position, velocity, 0.08D);
        assertEquals(0.4D, first[0], 1.0E-12D);
        assertEquals(10.5D, first[1], 1.0E-12D);
        assertEquals(0.0D, first[2], 1.0E-12D);
        // The horizontal velocity is not scaled by any drag at all, in either direction.
        assertEquals(0.4D, first[3], 1.0E-12D);
        assertEquals(0.5D - 0.08D, first[4], 1.0E-12D);
        assertEquals(0.0D, first[5], 1.0E-12D);
        // Every further step keeps the same horizontal displacement and loses exactly one gravity step vertically.
        double[] second = BreezeRules.stepAirborne(new double[]{first[0], first[1], first[2]},
                new double[]{first[3], first[4], first[5]}, 0.08D);
        assertEquals(0.4D, second[0] - first[0], 1.0E-12D);
        assertEquals(first[4], second[1] - first[1], 1.0E-12D);
        assertEquals(0.4D, second[3], 1.0E-12D);
        assertEquals(first[4] - 0.08D, second[4], 1.0E-12D);
        assertTrue(second[1] > first[1], "a rising body still climbs on the second step");
        // The arc is symmetric and comes back to its own start height: with a 0.4 launch and 0.08 gravity the
        // eleven-step integral lands on exactly the height it left, and the seventh step is the fifth one mirrored.
        double[] arcPosition = {0.0D, 0.0D, 0.0D};
        double[] arcVelocity = {0.0D, 0.4D, 0.0D};
        double[] heights = new double[12];
        for (int index = 0; index < 11; index++) {
            double[] step = BreezeRules.stepAirborne(arcPosition, arcVelocity, 0.08D);
            arcPosition = new double[]{step[0], step[1], step[2]};
            arcVelocity = new double[]{step[3], step[4], step[5]};
            heights[index + 1] = arcPosition[1];
        }
        assertEquals(0.0D, heights[11], 1.0E-12D, "the same un-dragged arc returns to its start height");
        assertEquals(heights[5], heights[6], 1.0E-12D, "the arc is highest on both sides of its own apex");
        assertEquals(heights[4], heights[7], 1.0E-12D, "the arc keeps its own symmetry");
        assertEquals(1.2D, heights[5], 1.0E-12D);
    }

    @Test
    void anArcEndsOnSpeedOrOnItsFrameBudget() {
        assertTrue(BreezeRules.airborneFinished(new double[]{0.0D, 0.0D, 0.0D}, 0, 40));
        assertFalse(BreezeRules.airborneFinished(new double[]{0.4D, 0.0D, 0.0D}, 0, 40));
        assertTrue(BreezeRules.airborneFinished(new double[]{0.4D, 0.0D, 0.0D}, 40, 40));
    }

    @Test
    void theNativeFacingThresholdIsTheDocumentedDotProduct() {
        // Straight ahead: the body is facing its target.
        assertTrue(BreezeRules.isFacingTarget(0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 1.0D));
        // Well outside the threshold - roughly 69 degrees off - is refused.
        assertFalse(BreezeRules.isFacingTarget(0.0D, 0.0D, 1.0D, Math.sin(1.2D), 0.0D, Math.cos(1.2D)));
        // Slightly inside the threshold is accepted.
        assertTrue(BreezeRules.isFacingTarget(0.0D, 0.0D, 1.0D, Math.sin(0.9D), 0.0D, Math.cos(0.9D)));
        // A zero-length direction is never "facing" anything.
        assertFalse(BreezeRules.isFacingTarget(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 1.0D));
        assertFalse(BreezeRules.isFacingTarget(0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 0.0D));
    }

    @Test
    void theReverseDeflectionConstantsAreTheNativeOnes() {
        assertEquals(-0.5D, BreezeRules.REVERSE_VELOCITY_SCALE);
        assertEquals(170.0F, BreezeRules.REVERSE_YAW_MIN);
        assertEquals(190.0F, BreezeRules.REVERSE_YAW_MAX);
        // The native direct hit is one point and the native burst itself adds no entity damage.
        assertEquals(1.0F, BreezeRules.DIRECT_HIT_DAMAGE);
        assertEquals(3.0F, BreezeRules.BURST_RADIUS);
    }

    @Test
    void aBurstOnlyDisplacesWhatTheNativeSetterReallyWrote() {
        assertFalse(BreezeRules.burstDisplaces(0.0D, 0.0D, 0.0D));
        // The shulker's own setter is a no-op, so its accepted change stays exactly zero and it is never pushed.
        assertFalse(BreezeRules.burstDisplaces(0.0D, 1.0E-12D, 0.0D));
        assertTrue(BreezeRules.burstDisplaces(0.0D, 0.25D, 0.0D));
    }
}
