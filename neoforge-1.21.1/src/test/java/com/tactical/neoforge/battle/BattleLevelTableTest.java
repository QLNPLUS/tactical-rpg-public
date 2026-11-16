package com.tactical.neoforge.battle;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two level sources, and the one rule that keeps them apart.
 *
 * <p>A player is answered by Tactical's own character level, so a player is always in the table - an
 * unreadable profile is the explicit unknown rather than a fabricated level 1. A creature is answered by the
 * optional Advanced-Leveling API, so a creature is in the table only when that API really returned a level;
 * nothing to show is no row at all. No third source exists here: this table cannot read vanilla experience,
 * a world name tag or another mod's storage, because it never reads anything - the caller supplies the two
 * numbers it already obtained.</p>
 */
class BattleLevelTableTest {

    @Test
    void aPlayerIsAlwaysNamedAndAnUnreadableProfileIsUnknown() {
        int[] table = new BattleLevelTable()
                .player(72, 3)
                .player(91, 0)
                .player(93, -7)
                .build();
        assertArrayEquals(new int[]{72, 3, 91, BattleLevelTable.UNKNOWN, 93, BattleLevelTable.UNKNOWN}, table,
                "a real level is kept and everything else is the explicit unknown, never a made-up level");
        assertEquals(0, BattleLevelTable.UNKNOWN, "the unknown value is the zero the wire documents");
    }

    @Test
    void aCreatureIsNamedOnlyWhenARealLevelWasRead() {
        int[] table = new BattleLevelTable()
                .creature(200, 5)
                .creature(201, OptionalInt.empty())
                .creature(202, OptionalInt.of(7))
                .creature(203, 0)
                .creature(204, -2)
                .creature(205, (OptionalInt) null)
                .build();
        assertArrayEquals(new int[]{200, 5, 202, 7}, table,
                "an absent, hidden or unusable creature level produces no row and no placeholder");
    }

    @Test
    void theTwoSourcesNeverShareAnIdentityByName() {
        // A player and a creature are entered by the caller's own iteration: a player goes through the
        // profile read, a creature through the optional bridge. The table itself refuses to invent either.
        BattleLevelTable levels = new BattleLevelTable();
        levels.player(1, 12);
        levels.creature(2, OptionalInt.of(4));
        levels.player(2, 12);
        assertArrayEquals(new int[]{1, 12, 2, 12}, levels.build(),
                "the last answer for an id wins, so one identity never carries two numbers");
        assertEquals(2, levels.count());
    }

    @Test
    void anEntityIdThatIsNotAnEntityIsIgnored() {
        int[] table = new BattleLevelTable().player(-1, 4).creature(-2, 4).player(7, 4).build();
        assertArrayEquals(new int[]{7, 4}, table, "no negative id can reach the wire");
    }

    @Test
    void anEmptyTableIsTheHonestAnswerForAnEncounterWithNothingToShow() {
        int[] table = new BattleLevelTable().build();
        assertEquals(0, table.length);
        assertEquals(0, new BattleLevelTable().count());
        // And the absence marker is not a value that could be mistaken for one of the three wire answers.
        assertTrue(BattleLevelTable.NONE < BattleLevelTable.UNKNOWN);
    }

    @Test
    void theTableStaysUsablePastItsFirstGrowth() {
        BattleLevelTable levels = new BattleLevelTable();
        int[] expected = new int[BattleStatePacketSized.PAIRS * 2];
        for (int index = 0; index < BattleStatePacketSized.PAIRS; index++) {
            levels.player(index, index + 1);
            expected[index * 2] = index;
            expected[index * 2 + 1] = index + 1;
        }
        assertArrayEquals(expected, levels.build(), "the bounded table grows without losing or reordering rows");
    }

    /** The largest table one body may carry, restated here so this test never drifts from the wire limit. */
    private static final class BattleStatePacketSized {
        static final int PAIRS = com.tactical.neoforge.network.BattleStatePacket.MAX_LEVEL_PAIRS;
    }
}
