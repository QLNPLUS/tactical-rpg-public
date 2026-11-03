package com.tactical.core.progression;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The character record's invariants and its two atomic transitions. */
class CharacterProfileTest {

    private static final ProgressionRules RULES = ProgressionRules.DEFAULTS;

    @Test void freshCharacterStartsAtLevelOneWithNothingInvested() {
        CharacterProfile profile = CharacterProfile.fresh();
        assertEquals(1, profile.level());
        assertEquals(0, profile.granted());
        assertEquals(0, profile.unspent());
        assertEquals(0, profile.totalInvested());
        assertEquals(0L, profile.revision());
        for (CharacterStat stat : CharacterStat.values()) {
            // Displayed 10 comes from the baseline, never from a granted bonus.
            assertEquals(10, profile.display(stat, RULES));
            assertEquals(0, profile.invested(stat));
        }
    }

    @Test void ledgerMustStayConserved() {
        // A granted point that is neither invested nor unspent, and one that is counted twice.
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(2, 1, 0, new int[6], 0L),
                "one granted point is missing from the ledger");
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(2, 1, 1, one(VIGOR()), 0L),
                "a single granted point cannot be both invested and unspent");
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(1, 0, 0, new int[5], 0L));
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(0, 0, 0, new int[6], 0L));
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(1, 0, 0, new int[6], -1L));
        assertThrows(IllegalArgumentException.class, () -> new CharacterProfile(1, 1, 1, negative(VIGOR()), 0L));
    }

    @Test void levelUpGrantsConfiguredPointsAndBumpsRevisionOnce() {
        CharacterProfile next = CharacterProfile.fresh().levelUp(RULES);
        assertEquals(2, next.level());
        assertEquals(1, next.granted());
        assertEquals(1, next.unspent());
        assertEquals(1L, next.revision());
        assertEquals(0, next.totalInvested());
        assertArrayEquals(new int[6], next.invested());

        ProgressionRules twoPoints = new ProgressionRules(100, 99, 2, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile doubled = CharacterProfile.fresh().levelUp(twoPoints);
        assertEquals(2, doubled.granted());
        assertEquals(2, doubled.unspent());
    }

    @Test void aChangedPointsPerLevelNeverRewritesTheHistoricalLedger() {
        CharacterProfile level3 = CharacterProfile.fresh().levelUp(RULES).levelUp(RULES);
        assertEquals(2, level3.granted());
        // The configuration now grants three points per level: the two already granted stay exactly two.
        ProgressionRules generous = new ProgressionRules(100, 99, 3, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile level4 = level3.levelUp(generous);
        assertEquals(5, level4.granted());
        assertEquals(5, level4.unspent());
        assertEquals(4, level4.level());
    }

    @Test void levelCapRefusesFurtherLevels() {
        ProgressionRules tiny = new ProgressionRules(2, 99, 1, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile capped = CharacterProfile.fresh().levelUp(tiny);
        assertEquals(Optional.of(ProgressionReason.LEVEL_CAP), capped.levelUpFailure(tiny));
        ProgressionRefusedException refused = assertThrows(ProgressionRefusedException.class, () -> capped.levelUp(tiny));
        assertEquals(ProgressionReason.LEVEL_CAP, refused.reason());
    }

    @Test void allocationSpendsExactlyTheSubmittedPoints() {
        CharacterProfile profile = CharacterProfile.fresh().levelUp(RULES).levelUp(RULES).levelUp(RULES);
        assertEquals(3, profile.unspent());
        CharacterProfile allocated = profile.allocate(increments(3, 0, 0, 0, 0, 0), RULES);
        assertEquals(0, allocated.unspent());
        assertEquals(3, allocated.totalInvested());
        assertEquals(3, allocated.granted());
        assertEquals(13, allocated.display(CharacterStat.VIGOR, RULES));
        assertEquals(10, allocated.display(CharacterStat.MIND, RULES));
        assertEquals(profile.revision() + 1, allocated.revision());
    }

    @Test void allocationIncrementsAreNonNegativeSoCommittedPointsNeverMove() {
        CharacterProfile profile = CharacterProfile.fresh().levelUp(RULES).allocate(increments(1, 0, 0, 0, 0, 0), RULES);
        assertEquals(Optional.of(ProgressionReason.INVALID_INCREMENT),
                profile.allocationFailure(increments(-1, 0, 0, 0, 0, 0), RULES));
        assertEquals(Optional.of(ProgressionReason.INVALID_INCREMENT),
                profile.allocationFailure(new int[5], RULES));
        assertEquals(Optional.of(ProgressionReason.INVALID_INCREMENT),
                profile.allocationFailure(null, RULES));
        assertEquals(1, profile.invested(CharacterStat.VIGOR));
    }

    @Test void allocationRefusalsFollowTheDocumentedOrder() {
        CharacterProfile broke = CharacterProfile.fresh();
        // Nothing unspent and nothing submitted: "nothing to confirm" wins over "not enough points".
        assertEquals(Optional.of(ProgressionReason.NOTHING_TO_ALLOCATE),
                broke.allocationFailure(new int[6], RULES));
        // A real intent without points is an insufficient balance.
        assertEquals(Optional.of(ProgressionReason.INSUFFICIENT_POINTS),
                broke.allocationFailure(increments(1, 0, 0, 0, 0, 0), RULES));
    }

    @Test void statCapBoundsTheDisplayedValue() {
        ProgressionRules smallCap = new ProgressionRules(100, 12, 40, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile profile = new CharacterProfile(2, 40, 38, increments(2, 0, 0, 0, 0, 0), 1L);
        assertEquals(12, smallCap.statCap());
        assertEquals(2, smallCap.maximumInvested());
        assertEquals(Optional.of(ProgressionReason.STAT_CAP),
                profile.allocationFailure(increments(1, 0, 0, 0, 0, 0), smallCap));
        // Five more points exactly reach that cap and are therefore legal.
        ProgressionRules widerCap = new ProgressionRules(100, 15, 40, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile full = profile.allocate(increments(0, 5, 0, 0, 0, 0), widerCap);
        assertEquals(15, full.display(CharacterStat.MIND, widerCap));
        assertEquals(Optional.of(ProgressionReason.STAT_CAP),
                full.allocationFailure(increments(0, 1, 0, 0, 0, 0), widerCap));
    }

    @Test void aLoweredCapOnlyRefusesGrowthOfTheStatThatIsOverIt() {
        // A profile whose vigor is at 4 while a new rule set only allows 2 invested points: the points stay,
        // they stay visible, they block nothing else, and they cannot grow.
        ProgressionRules lowered = new ProgressionRules(100, 12, 40, 100, 50, 2, 5, 2, .5, .01, .02);
        CharacterProfile profile = new CharacterProfile(2, 40, 36, increments(4, 0, 0, 0, 0, 0), 3L);
        assertEquals(14, profile.display(CharacterStat.VIGOR, lowered));
        assertEquals(Optional.of(ProgressionReason.STAT_CAP),
                profile.allocationFailure(increments(1, 0, 0, 0, 0, 0), lowered));
        // Another stat that is still under the cap can grow normally.
        CharacterProfile other = profile.allocate(increments(0, 2, 0, 0, 0, 0), lowered);
        assertEquals(4, other.invested(CharacterStat.VIGOR));
        assertEquals(2, other.invested(CharacterStat.MIND));
        assertEquals(36 - 2, other.unspent());
    }

    @Test void arraysNeverLeakOutOfTheRecord() {
        int[] submitted = increments(1, 0, 0, 0, 0, 0);
        CharacterProfile profile = CharacterProfile.fresh().levelUp(RULES).allocate(submitted, RULES);
        submitted[0] = 99;
        assertEquals(1, profile.invested(CharacterStat.VIGOR));
        int[] read = profile.invested();
        read[0] = 42;
        assertEquals(1, profile.invested(CharacterStat.VIGOR));
    }

    @Test void structuralEqualityCoversTheInvestedArray() {
        CharacterProfile a = CharacterProfile.fresh().levelUp(RULES);
        CharacterProfile b = CharacterProfile.fresh().levelUp(RULES);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.allocate(increments(1, 0, 0, 0, 0, 0), RULES));
        assertTrue(a.withRevision(7L).revision() == 7L);
    }

    private static CharacterStat VIGOR() { return CharacterStat.VIGOR; }

    private static int[] increments(int... values) { return values; }

    private static int[] one(CharacterStat stat) {
        int[] values = new int[6];
        values[stat.index()] = 1;
        return values;
    }

    private static int[] negative(CharacterStat stat) {
        int[] values = new int[6];
        values[stat.index()] = -1;
        return values;
    }
}
