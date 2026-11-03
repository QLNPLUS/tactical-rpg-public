package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.BattleLogState;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.network.BattleLogPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wheel on the two HUD lists. They share one direction, which is what the user asked for last: the
 * roster turns the wheel exactly like the battle journal. A notch down browses towards the newer end, so
 * the entries already on screen move up, and a notch up browses back, so they move down. Both therefore
 * receive the raw notch, nothing inverts a sign, and the journal still stores its history oldest first.
 *
 * <p>The earlier "the roster is inverted on purpose" contract is gone rather than kept side by side, and
 * one test compares the two HUDs directly: a roster that was inverted while every journal assertion
 * passed is exactly what a single-HUD suite cannot catch.</p>
 */
class HudWheelTest {

    private static final UUID BATTLE = new UUID(0, 7);
    /** One text line in GUI pixels, and the gap between two entries. */
    private static final int LINE = 9;
    private static final int GAP = 2;

    @Test void theRosterTakesTheRawNotchLikeEveryOtherList() {
        // Nothing in the roster chain flips the sign: it does exactly what the shared list does.
        assertEquals(4, ScrollList.scrollBy(3, 10, 4, -1.0D), "a notch down steps the window forward");
        assertEquals(2, ScrollList.scrollBy(3, 10, 4, 1.0D), "a notch up steps it back");
        assertEquals(4, HudWheel.rosterOffset(3, 10, 4, -1.0D));
        assertEquals(2, HudWheel.rosterOffset(3, 10, 4, 1.0D));
    }

    @Test void aNotchDownMovesTheRosterTowardsLaterRows() {
        // offset 3 is the middle of a 10-row roster that draws 4 rows.
        assertEquals(4, HudWheel.rosterOffset(3, 10, 4, -1.0D),
                "wheel down -> later rows -> the entries on screen move up");
        assertEquals(2, HudWheel.rosterOffset(3, 10, 4, 1.0D),
                "wheel up -> earlier rows -> the entries on screen move down");
        assertEquals(3, HudWheel.rosterOffset(3, 10, 4, 0.0D));
        assertEquals(6, HudWheel.rosterOffset(6, 10, 4, -1.0D), "no window past the last four rows");
        assertEquals(5, HudWheel.rosterOffset(6, 10, 4, 1.0D), "a notch up browses back from the end");
        assertEquals(0, HudWheel.rosterOffset(0, 10, 4, 1.0D), "the first window cannot go further back");
        assertEquals(1, HudWheel.rosterOffset(0, 10, 4, -1.0D), "a notch down still browses forward from the top");
        assertEquals(0, HudWheel.rosterOffset(0, 3, 4, -1.0D), "a roster that fits does not scroll");
    }

    /**
     * The direction as one property over every window a roster can have: a notch down moves the window
     * one row towards the newer end, a notch up one row back, and neither ever overflows the list. A
     * broken delta (zero, NaN, infinite) is not a notch at all. Stated this way, "the direction happens
     * to be right at the one offset I tried" cannot pass.
     */
    @Test void everyNotchMovesTheRosterOneRowTowardsTheNewerEnd() {
        double[] notANotch = {0.0D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int total = 1; total <= 12; total++) {
            for (int visible = 1; visible <= total; visible++) {
                int last = Math.max(0, total - visible);
                for (int offset = 0; offset <= last; offset++) {
                    String where = "offset " + offset + " of " + total + " rows, " + visible + " visible";
                    int down = HudWheel.rosterOffset(offset, total, visible, -1.0D);
                    int up = HudWheel.rosterOffset(offset, total, visible, 1.0D);
                    assertEquals(Math.min(last, offset + 1), down,
                            "a notch down moves one row towards the newer end: " + where);
                    assertEquals(Math.max(0, offset - 1), up,
                            "a notch up moves one row back: " + where);
                    // Repeating the same notch keeps going the same way until the list ends.
                    assertEquals(Math.min(last, down + 1), HudWheel.rosterOffset(down, total, visible, -1.0D),
                            "a second notch down keeps going the same way: " + where);
                    // Nowhere in the list does a notch leave the window outside the list.
                    for (int result : new int[]{down, up}) {
                        assertTrue(result >= 0 && result <= last,
                                "a notch never overflows the list (" + result + "): " + where);
                    }
                    for (double delta : notANotch) {
                        assertEquals(offset, HudWheel.rosterOffset(offset, total, visible, delta),
                                "a broken delta (" + delta + ") is not a notch: " + where);
                    }
                }
            }
        }
        // A large step is still one row: the sign is what a notch carries, not its magnitude.
        assertEquals(4, HudWheel.rosterOffset(3, 10, 4, -9999.0D));
        assertEquals(2, HudWheel.rosterOffset(3, 10, 4, 9999.0D));
    }

    /**
     * The user's requirement, compared across the two HUDs instead of asserted one at a time: with both
     * lists parked in a scrollable middle position, the same notch must move the entries already on
     * screen the same way in both. The roster reports a row window that moves towards the newer end, the
     * journal reports a viewport top that moves the same way; both mean "the entries move up".
     */
    @Test void bothHudsMoveTheirEntriesTheSameWayForTheSameNotch() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(12), 0);
        JournalViewport.Content content = JournalFixture.uniform(state.history().size(), 12, LINE, GAP, 40);
        // Two notches into the history: far enough from the newest end that a notch down is not clamped.
        assertTrue(state.scroll(1.0D, true, content));
        assertTrue(state.scroll(1.0D, true, content));
        int rosterOffset = 3;
        for (double delta : new double[]{-1.0D, 1.0D}) {
            int rosterAfter = HudWheel.rosterOffset(rosterOffset, 10, 4, delta);
            int journalTopBefore = JournalViewport.scrollTop(content, state.journalAnchor());
            assertTrue(state.scroll(delta, true, content), "the journal consumes the notch: " + delta);
            int journalTopAfter = JournalViewport.scrollTop(content, state.journalAnchor());
            // A row window that starts later, and a viewport top that moved down the stack, both mean the
            // entries already on screen moved up.
            boolean rosterEntriesUp = rosterAfter > rosterOffset;
            boolean journalEntriesUp = journalTopAfter > journalTopBefore;
            assertEquals(rosterEntriesUp, journalEntriesUp,
                    "the two HUDs must move their entries the same way for delta " + delta);
            assertEquals(delta < 0.0D, rosterEntriesUp,
                    "a notch down moves the entries up in both lists, a notch up moves them down: " + delta);
            rosterOffset = rosterAfter;
        }
    }

    @Test void theJournalBrowsesNewerHistoryOnANotchDown() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(12), 0);
        JournalViewport.Content content = JournalFixture.uniform(state.history().size(), 12, LINE, GAP, 40);
        assertTrue(state.journalAnchor().newest(), "the journal starts at the newest end");
        assertTrue(state.scroll(1.0D, true, content), "a notch up goes into older history");
        assertEquals(LINE, offsetPx(content, state), "one notch browses one text line");
        assertTrue(state.scroll(1.0D, true, content));
        assertEquals(2 * LINE, offsetPx(content, state));
        // The latest content lives at the bottom, so a notch down returns towards it instead of walking
        // further back, and the entries on screen move up.
        assertTrue(state.scroll(-1.0D, true, content), "a notch down returns towards the newest entry");
        assertEquals(LINE, offsetPx(content, state));
        assertTrue(state.scroll(-1.0D, true, content));
        assertTrue(state.journalAnchor().newest(), "the bottom of the stack is the newest entry again");
        assertTrue(state.scroll(-1.0D, true, content),
                "the journal consumes the notch at its end so the camera never gets it");
    }

    @Test void theJournalStoresItsHistoryInTheOriginalOrder() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(12), 0);
        JournalViewport.Content content = JournalFixture.uniform(state.history().size(), 12, LINE, GAP, 40);
        state.scroll(1.0D, true, content);
        assertEquals(12, state.history().getLast().sequence(), "browsing never reorders the data");
        // The browsed place is an entry of that same list, named by its own sequence number.
        int index = state.history().size() - 1 - (int) (12 - state.journalAnchor().sequence());
        assertEquals(state.history().get(index).sequence(), state.journalAnchor().sequence(),
                "the anchor is one of the stored entries, found by sequence and not by an index");
        assertTrue(state.journalAnchor().sequence() < 12, "browsing back leaves the newest entry behind");
    }

    @Test void aCollapsedBodyIgnoresTheNotchEntirely() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(12), 0);
        JournalViewport.Content content = JournalFixture.uniform(state.history().size(), 12, LINE, GAP, 40);
        assertFalse(state.scroll(-1.0D, false, content));
        assertTrue(state.journalAnchor().newest());
    }

    /** Pixels from the newest end, which is what the state's anchor resolves to. */
    private static int offsetPx(JournalViewport.Content content, BattleLogState state) {
        return content.maxScroll() - JournalViewport.scrollTop(content, state.journalAnchor());
    }

    private static BattleLogPacket packet(long sequence) {
        return new BattleLogPacket(BATTLE, BattleLogPacket.UPDATE, sequence,
                LongStream.rangeClosed(1, sequence)
                        .mapToObj(value -> new BattleLogPacket.Entry(value, "action", "事件 " + value)).toList());
    }
}
