package com.tactical.core.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vanilla curve as arithmetic, including its segment boundaries and its float tolerance. */
class ExperienceCurveTest {

    @Test void perLevelSpanMatchesTheVanillaSegments() {
        int[][] expected = {{0, 7}, {1, 9}, {5, 17}, {14, 35}, {15, 37}, {16, 42}, {29, 107}, {30, 112}, {31, 121}, {40, 202}};
        for (int[] row : expected) assertEquals(row[1], ExperienceCurve.xpForNextLevel(row[0]), "span at " + row[0]);
    }

    @Test void cumulativeStartsMatchTheVanillaSegments() {
        assertEquals(0L, ExperienceCurve.xpAtLevelStart(0));
        assertEquals(7L, ExperienceCurve.xpAtLevelStart(1));
        assertEquals(16L, ExperienceCurve.xpAtLevelStart(2));
        assertEquals(315L, ExperienceCurve.xpAtLevelStart(15));
        assertEquals(352L, ExperienceCurve.xpAtLevelStart(16));
        assertEquals(1395L, ExperienceCurve.xpAtLevelStart(30));
        assertEquals(1507L, ExperienceCurve.xpAtLevelStart(31));
        assertEquals(1395L + 112L * 10 + 9L * 10L * 9L / 2L, ExperienceCurve.xpAtLevelStart(40));
    }

    @Test void cumulativeGrowsMonotonicallyAcrossEveryBoundary() {
        long previous = -1;
        for (int level = 0; level <= 400; level++) {
            long start = ExperienceCurve.xpAtLevelStart(level);
            assertTrue(start > previous, "start must grow at level " + level);
            assertEquals(ExperienceCurve.xpAtLevelStart(level) + ExperienceCurve.xpForNextLevel(level),
                    ExperienceCurve.xpAtLevelStart(level + 1), "step at level " + level);
            previous = start;
        }
    }

    @Test void balanceUsesLevelAndBarProgressOnly() {
        assertEquals(0.0D, ExperienceCurve.balance(0, 0), 1.0E-9);
        assertEquals(7.0D, ExperienceCurve.balance(1, 0.0), 1.0E-9);
        assertEquals(11.5D, ExperienceCurve.balance(1, 0.5), 1.0E-9);
        assertEquals(1507.0D + 121.0D * 0.25D, ExperienceCurve.balance(31, 0.25D), 1.0E-9);
        // A broken bar is treated as the nearest legal position instead of free or negative XP.
        assertEquals(7.0D, ExperienceCurve.balance(1, -3.0D), 1.0E-9);
        assertTrue(ExperienceCurve.balance(1, 5.0D) < 16.0D);
        assertEquals(7.0D, ExperienceCurve.balance(1, Double.NaN), 1.0E-9);
    }

    @Test void splitInvertsBalance() {
        for (double balance : new double[]{0, 1, 6.5, 7, 15.999, 16, 314.5, 315, 1394, 1395, 1506, 1507, 12345.75}) {
            ExperienceCurve.Split split = ExperienceCurve.split(balance);
            assertEquals(balance, ExperienceCurve.balance(split.level(), split.progress()), 0.01D,
                    "round trip at " + balance);
        }
    }

    @Test void splitPutsAWholeLevelAtTheStartOfTheNextOne() {
        assertEquals(new ExperienceCurve.Split(1, 0.0D), ExperienceCurve.split(7));
        assertEquals(new ExperienceCurve.Split(15, 0.0D), ExperienceCurve.split(315));
        assertEquals(new ExperienceCurve.Split(30, 0.0D), ExperienceCurve.split(1395));
        ExperienceCurve.Split justBelow = ExperienceCurve.split(6.999);
        assertEquals(0, justBelow.level());
        assertTrue(justBelow.progress() > 0.99);
    }

    @Test void splitRefusesToInventStateForImpossibleBalances() {
        assertEquals(new ExperienceCurve.Split(0, 0.0D), ExperienceCurve.split(-1));
        assertEquals(new ExperienceCurve.Split(0, 0.0D), ExperienceCurve.split(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCurve.Split(0, 1.0D));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceCurve.Split(-1, 0.0D));
    }

    @Test void wholePointBalanceRecoversTheVanillaFloatBar() {
        // The exact case reported in review: 35 points inside a 37-point level is stored as this float.
        float bar = (float) (35.0D / 37.0D);
        assertEquals(350L, ExperienceCurve.integerBalance(15, bar));
        assertEquals(349L, ExperienceCurve.integerBalance(15, (float) (34.0D / 37.0D)));
        assertEquals(315L, ExperienceCurve.integerBalance(15, 0.0F));
        assertEquals(351L, ExperienceCurve.integerBalance(15, 1.0F), "a full bar is still inside that level");
        assertEquals(7L, ExperienceCurve.integerBalance(1, 0.0F));
        assertEquals(16L, ExperienceCurve.integerBalance(2, 0.0F));
        assertEquals(352L, ExperienceCurve.integerBalance(16, 0.0F));
        assertEquals(1395L, ExperienceCurve.integerBalance(30, 0.0F));
        assertEquals(1507L, ExperienceCurve.integerBalance(31, 0.0F));
    }

    @Test void everyLegalFloatBarPositionRoundTripsToItsOwnPointCount() {
        for (int level = 0; level <= 40; level++) {
            int span = ExperienceCurve.xpForNextLevel(level);
            for (int point = 0; point < span; point++) {
                float bar = (float) ((double) point / (double) span);
                assertEquals(point, ExperienceCurve.pointsWithinLevel(level, bar),
                        "level " + level + " point " + point);
                assertEquals(ExperienceCurve.xpAtLevelStart(level) + point,
                        ExperienceCurve.integerBalance(level, bar));
            }
        }
    }

    @Test void aFractionalBarNeverGrowsIntoAFullPoint() {
        // Half of a 7-point level is 3.5: the wallet must keep 3, never round up to 4.
        assertEquals(3L, ExperienceCurve.pointsWithinLevel(0, 0.5F));
        assertEquals(3L, ExperienceCurve.integerBalance(0, 0.5F));
        assertEquals(0L, ExperienceCurve.pointsWithinLevel(0, -1.0F));
        assertEquals(0L, ExperienceCurve.pointsWithinLevel(0, Float.NaN));
        // A bar just under full stays one point short of the level; it never rounds the level away.
        assertEquals(6L, ExperienceCurve.pointsWithinLevel(0, 0.999F));
        assertEquals(6L, ExperienceCurve.integerBalance(0, 0.999F));
    }

    @Test void absurdLevelsSaturateInsteadOfWrapping() {
        long start = ExperienceCurve.xpAtLevelStart(Integer.MAX_VALUE);
        assertTrue(start > 0, "saturated cumulative XP stays positive");
        ExperienceCurve.Split split = ExperienceCurve.split(1.0E18D);
        assertEquals(ExperienceCurve.MAX_LEVEL, split.level());
        assertTrue(split.progress() >= 0 && split.progress() < 1);
    }
}
