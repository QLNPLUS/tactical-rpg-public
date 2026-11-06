package com.tactical.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The drowned's trident throw: native launch numbers, native range, and the ruleset's own price. */
class TridentThrowTest {

    @Test
    void launchNumbersAreTheOnesTheNativeCallUses() {
        assertEquals(1.6F, TridentThrow.SPEED);
        assertEquals(0.05D, TridentThrow.GRAVITY);
        assertEquals(0.99D, TridentThrow.AIR_DRAG);
        assertEquals(0.99D, TridentThrow.WATER_DRAG);
        // A thrown trident keeps its speed in water; that is not an arrow's 0.6.
        assertNotEquals(TridentThrow.WATER_DRAG, RangedWeapon.BOW.waterDrag());
    }

    @Test
    void theIndependentProfileCarriesThoseNumbers() {
        assertEquals(TridentThrow.SPEED, ThrowProfile.TRIDENT.speed());
        assertTrue(ThrowProfile.TRIDENT.gravity());
        assertEquals(TridentThrow.AIR_DRAG, ThrowProfile.TRIDENT.drag());
        assertEquals(TridentThrow.WATER_DRAG, ThrowProfile.TRIDENT.waterDrag());
        // A trident is not a potion either: the two profiles must never be interchangeable by accident.
        assertNotEquals(ThrowProfile.SPLASH_POTION.speed(), ThrowProfile.TRIDENT.speed());
        assertNotEquals(ThrowProfile.SPLASH_POTION.waterDrag(), ThrowProfile.TRIDENT.waterDrag());
    }

    @Test
    void theNativeAimNormalizesBeforeScalingSoLiftNeverChangesSpeed() {
        // A flat 10 block shot: vanilla adds horizontal * 0.2 to the vertical delta and normalizes afterwards.
        TridentThrow.Velocity velocity = TridentThrow.nominal(10.0D, 0.0D, 0.0D, 10.0D);
        assertNotNull(velocity);
        assertEquals(TridentThrow.SPEED, velocity.length(), 1.0E-9D);
        assertTrue(velocity.y() > 0.0D, "the native aim lifts a flat throw");
        assertEquals(velocity.y() / velocity.x(), 2.0D / 10.0D, 1.0E-9D);
    }

    @Test
    void aDownwardAimStaysDownwardAndStillLeavesAtLaunchSpeed() {
        TridentThrow.Velocity velocity = TridentThrow.nominal(4.0D, -6.0D, 3.0D, 5.0D);
        assertNotNull(velocity);
        assertEquals(TridentThrow.SPEED, velocity.length(), 1.0E-9D);
        // dy + 5 * 0.2 is still negative, so the arc really starts downwards.
        assertTrue(velocity.y() < 0.0D);
        assertEquals(velocity.x() / velocity.z(), 4.0D / 3.0D, 1.0E-9D);
    }

    @Test
    void aZeroLengthAimHasNoVelocityInsteadOfAVanishingThrow() {
        assertNull(TridentThrow.nominal(0.0D, 0.0D, 0.0D, 0.0D));
    }

    @Test
    void inaccuracyFollowsTheDifficultyIdTheNativeCallReads() {
        assertEquals(14.0D, TridentThrow.inaccuracy(0));
        assertEquals(10.0D, TridentThrow.inaccuracy(1));
        assertEquals(6.0D, TridentThrow.inaccuracy(2));
        assertEquals(2.0D, TridentThrow.inaccuracy(3));
    }

    @Test
    void theRangeIsVanillasTenRealBlocks() {
        assertEquals(10.0D, TridentThrow.MAX_RANGE);
        assertEquals(1.0D / 3.0D, TridentThrow.TARGET_AIM_FRACTION);
    }

    @Test
    void oneThrowIsOneMainActionAtTheRulesetsPrice() {
        assertEquals(20, BattleRules.DROWNED_TRIDENT_COST);
        // The same successful-action surcharge every other main action pays.
        assertEquals(20, ApCalculator.paidActionCost(BattleRules.DROWNED_TRIDENT_COST, 0));
        assertEquals(25, ApCalculator.paidActionCost(BattleRules.DROWNED_TRIDENT_COST, 1));
        assertEquals(30, ApCalculator.paidActionCost(BattleRules.DROWNED_TRIDENT_COST, 2));
    }
}
