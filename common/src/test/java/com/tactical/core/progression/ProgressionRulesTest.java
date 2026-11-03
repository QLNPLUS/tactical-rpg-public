package com.tactical.core.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The configured price, cap and coefficient rules, and the identity that makes an old quote stale. */
class ProgressionRulesTest {

    @Test void approvedDefaultsMatchThePlan() {
        ProgressionRules rules = ProgressionRules.DEFAULTS;
        assertEquals(100, rules.maxLevel());
        assertEquals(99, rules.statCap());
        assertEquals(1, rules.pointsPerLevel());
        assertEquals(100L, rules.xpToNextLevel(1));
        assertEquals(150L, rules.xpToNextLevel(2));
        assertEquals(5050L, rules.xpToNextLevel(100));
        assertEquals(89, rules.maximumInvested());
        for (CharacterStat stat : CharacterStat.values()) {
            assertEquals(CharacterStat.BASE, rules.displayValue(0));
            assertTrue(rules.bonus(stat, 0) == 0.0D, "baseline display is not a bonus: " + stat);
        }
    }

    @Test void perPointBonusesFollowTheApprovedCoefficients() {
        ProgressionRules rules = ProgressionRules.DEFAULTS;
        assertEquals(2.0D, rules.bonus(CharacterStat.VIGOR, 1));
        assertEquals(5.0D, rules.bonus(CharacterStat.MIND, 1));
        assertEquals(2.0D, rules.bonus(CharacterStat.ENDURANCE, 1));
        assertEquals(0.5D, rules.bonus(CharacterStat.STRENGTH, 1));
        assertEquals(0.01D, rules.bonus(CharacterStat.DEXTERITY, 1));
        assertEquals(0.02D, rules.bonus(CharacterStat.INTELLIGENCE, 1));
        assertEquals(20.0D, rules.bonus(CharacterStat.VIGOR, 10), 1.0E-9);
        assertEquals(0.2D, rules.bonus(CharacterStat.INTELLIGENCE, 10), 1.0E-9);
        // Negative or absent investment is never a negative bonus.
        assertEquals(0.0D, rules.bonus(CharacterStat.VIGOR, -5));
    }

    @Test void skillPowerScalesDamageExactlyOnce() {
        ProgressionRules rules = ProgressionRules.DEFAULTS;
        assertEquals(1.0D, rules.skillPower(0), 1.0E-12);
        assertEquals(1.02D, rules.skillPower(1), 1.0E-12);
        assertEquals(1.2D, rules.skillPower(10), 1.0E-12);
        assertEquals(10.0D, rules.skillDamage(10.0D, 0), 1.0E-9);
        assertEquals(10.2D, rules.skillDamage(10.0D, 1), 1.0E-9);
        // Two applications are visibly different from one, which is what the "multiply once" rule means.
        assertEquals(10.404D, rules.skillDamage(rules.skillDamage(10.0D, 1), 1), 1.0E-9);
    }

    @Test void displayedValueKeepsHistoricalInvestmentVisible() {
        // The cap decides whether another point may be added; it never hides points that are already there.
        ProgressionRules rules = new ProgressionRules(100, 20, 1, 100, 50, 2, 5, 2, .5, .01, .02);
        assertEquals(10, rules.maximumInvested());
        assertEquals(60, rules.displayValue(50));
        assertEquals(20, rules.displayValue(10));
        assertEquals(10, rules.displayValue(0));
    }

    @Test void fingerprintChangesWithAnyPricedRule() {
        ProgressionRules base = ProgressionRules.DEFAULTS;
        assertEquals(base.fingerprint(), ProgressionRules.DEFAULTS.fingerprint());
        ProgressionRules moreExpensive = new ProgressionRules(100, 99, 1, 200, 50, 2, 5, 2, .5, .01, .02);
        ProgressionRules morePoints = new ProgressionRules(100, 99, 2, 100, 50, 2, 5, 2, .5, .01, .02);
        ProgressionRules higherCap = new ProgressionRules(101, 99, 1, 100, 50, 2, 5, 2, .5, .01, .02);
        ProgressionRules strongerStat = new ProgressionRules(100, 99, 1, 100, 50, 3, 5, 2, .5, .01, .02);
        assertNotEquals(base.fingerprint(), moreExpensive.fingerprint());
        assertNotEquals(base.fingerprint(), morePoints.fingerprint());
        assertNotEquals(base.fingerprint(), higherCap.fingerprint());
        assertNotEquals(base.fingerprint(), strongerStat.fingerprint());
        assertTrue(base.fingerprint().contains("xp=100+50"));
    }

    @Test void impossibleRulesAreRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(0, 99, 1, 100, 50, 2, 5, 2, .5, .01, .02));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 9, 1, 100, 50, 2, 5, 2, .5, .01, .02));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 99, -1, 100, 50, 2, 5, 2, .5, .01, .02));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 99, 1, -1, 50, 2, 5, 2, .5, .01, .02));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 99, 1, 100, 50, 2, 5, 2, .5, .01, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 99, 1, 100, 50, 2, 5, 2, .5, Double.POSITIVE_INFINITY, .02));
        assertThrows(IllegalArgumentException.class, () -> new ProgressionRules(100, 99, 1, 100, 50, -2, 5, 2, .5, .01, .02));
    }

    @Test void aZeroPointLevelGrantsNothingAndThereforeCannotLevelUp() {
        // points-per-level 0 is a legal configuration, but it can never hand out a level.
        ProgressionRules noPoints = new ProgressionRules(100, 99, 0, 100, 50, 2, 5, 2, .5, .01, .02);
        assertFalse(CharacterProfile.fresh().levelUpFailure(noPoints).isEmpty());
        assertEquals(ProgressionReason.SERVER_ERROR, CharacterProfile.fresh().levelUpFailure(noPoints).orElseThrow());
    }
}
