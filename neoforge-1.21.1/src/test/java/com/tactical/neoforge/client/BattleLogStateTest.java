package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.hud.JournalFixture;
import com.tactical.neoforge.client.ui.hud.JournalViewport;
import com.tactical.neoforge.network.BattleLogPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class BattleLogStateTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);
    /** One text line in GUI pixels, and the gap between two entries: what a notch browses. */
    private static final int LINE = 9;
    private static final int GAP = 2;

    @Test
    void duplicateAndOutOfOrderSnapshotsDoNotDirtyOrReplaceHistory() {
        BattleLogState state = new BattleLogState();
        assertTrue(state.accept(packet(FIRST, BattleLogPacket.START, 1), 0));
        assertTrue(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 3), 1));
        state.markClean();
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 3), 2));
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 2), 3));
        assertFalse(state.dirty());
        assertEquals(3, state.sequence());
        assertEquals(3, state.history().size());
    }

    @Test
    void aNewEncounterReplacesHistoryAndRetiredSessionCannotReturn() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 4), 0);
        // A player who joins an encounter already in progress receives an UPDATE first; every snapshot
        // carries the complete bounded history, so the journal must adopt it instead of showing nothing.
        assertTrue(state.accept(packet(SECOND, BattleLogPacket.UPDATE, 5), 1));
        assertEquals(SECOND, state.battleId());
        assertEquals(5, state.sequence());
        assertEquals(5, state.history().size(), "the joiner shows the full history, not just the new row");
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.START, 6), 3),
                "a retired encounter cannot return");
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.FINISH, 7), 4));
    }

    @Test
    void finishHidesImmediatelyAndQueuedSnapshotsCannotReviveJournal() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 1), 0);
        assertTrue(state.visible());
        state.markClean();
        assertTrue(state.accept(packet(FIRST, BattleLogPacket.FINISH, 2), 1000));
        assertTrue(state.finished());
        assertTrue(state.dirty());
        // No tick, clock advance, or twenty-second timeout is needed to hide it.
        assertFalse(state.visible());
        state.markClean();
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.FINISH, 2), 1000));
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 3), 1000));
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.START, 4), 1000));
        assertFalse(state.visible());
        assertFalse(state.dirty());
        assertFalse(state.toggleCollapsed());
        assertTrue(state.accept(packet(SECOND, BattleLogPacket.START, 1), 1001));
        assertTrue(state.visible());
        assertFalse(state.finished());
    }

    @Test
    void collapseMarksDirtyAndSurvivesNewMessagesUntilExplicitlyExpanded() {
        BattleLogState state = new BattleLogState();
        assertFalse(state.toggleCollapsed());
        state.accept(packet(FIRST, BattleLogPacket.START, 1), 0);
        state.markClean();
        assertTrue(state.toggleCollapsed());
        assertTrue(state.collapsed());
        assertTrue(state.visible());
        assertTrue(state.dirty());
        state.markClean();
        assertTrue(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 2), 1));
        assertTrue(state.collapsed());
        assertEquals(2, state.history().size());
        state.markClean();
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 2), 2));
        assertFalse(state.dirty());
        assertTrue(state.collapsed());
        assertTrue(state.toggleCollapsed());
        assertFalse(state.collapsed());
        assertTrue(state.dirty());
    }

    @Test
    void newEncounterAndWorldClearResetCollapseWithoutRevivingOldEncounter() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 1), 0);
        state.toggleCollapsed();
        state.accept(packet(SECOND, BattleLogPacket.START, 1), 1);
        assertFalse(state.collapsed());
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.START, 2), 2));
        state.toggleCollapsed();
        state.clear();
        assertFalse(state.collapsed());
        assertFalse(state.visible());
        assertFalse(state.toggleCollapsed());
    }

    @Test
    void worldClearRejectsQueuedOldSessionsAndAcceptsNextEncounter() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 1), 0);
        state.clear();
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.UPDATE, 2), 1));
        assertFalse(state.accept(packet(FIRST, BattleLogPacket.START, 1), 1));
        assertTrue(state.accept(packet(SECOND, BattleLogPacket.START, 1), 1));
        assertFalse(state.accept(new BattleLogPacket(FIRST, BattleLogPacket.CLEAR, 99, List.of()), 2));
        assertTrue(state.visible());
        assertTrue(state.accept(new BattleLogPacket(SECOND, BattleLogPacket.CLEAR, 1, List.of()), 3));
        assertFalse(state.visible());
    }

    @Test
    void boundedHistoryKeepsTheNewestRetainedEntriesInServerOrder() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        assertEquals(64, state.history().size());
        assertEquals(37, state.history().getFirst().sequence(), "the oldest retained entry, in server order");
        assertEquals(100, state.history().getLast().sequence());
        assertThrows(UnsupportedOperationException.class, () -> state.history().clear());
    }

    /** One text line per retained entry, as the layout would stack them. */
    private static JournalViewport.Content content(BattleLogState state, int bodyHeight) {
        int count = state.history().size();
        long last = count == 0 ? 0 : state.history().getLast().sequence();
        return JournalFixture.uniform(count, last, LINE, GAP, bodyHeight);
    }

    /** The viewport's own scroll position: pixels from the newest end, 0 = following it. */
    private static int offsetPx(JournalViewport.Content content, JournalViewport.Anchor anchor) {
        return content.maxScroll() - JournalViewport.scrollTop(content, anchor);
    }

    @Test
    void malformedSnapshotsCannotAllocateUnboundedOrUnorderedHistory() {
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket(FIRST,
                BattleLogPacket.START, 65, LongStream.rangeClosed(1, 65).mapToObj(BattleLogStateTest::entry).toList()));
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket(FIRST,
                BattleLogPacket.UPDATE, 2, List.of(entry(2), entry(1))));
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket.Entry(1, "damage", "x".repeat(241)));
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket.Entry(0, "damage", "text"));
    }

    @Test
    void wheelBrowsesThePixelStackAndConsumesAtBothEnds() {
        BattleLogState state = new BattleLogState();
        assertFalse(state.scroll(1, content(state, 40)), "nothing retained, nothing to browse");
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        JournalViewport.Content content = content(state, 40);
        assertTrue(state.journalAnchor().newest(), "the journal starts following the newest entry");

        assertTrue(state.scroll(0.1, content), "a notch up browses older history");
        assertFalse(state.journalAnchor().newest());
        assertEquals(LINE, offsetPx(content, state.journalAnchor()), "one notch is one text line");
        assertTrue(state.scroll(2, content));
        assertEquals(3 * LINE, offsetPx(content, state.journalAnchor()), "three notches, three lines");
        // The user's requirement: the newest content lives at the bottom, so a notch down returns
        // towards it instead of walking further back.
        assertTrue(state.scroll(-1, content), "a notch down returns towards the newest entry");
        assertEquals(2 * LINE, offsetPx(content, state.journalAnchor()));

        // Browsing past the top clamps to the oldest retained entry and still consumes the notch.
        for (int notch = 0; notch < 200; notch++) assertTrue(state.scroll(1, content));
        assertEquals(content.maxScroll(), offsetPx(content, state.journalAnchor()),
                "the oldest end of the stack is as far as it goes");
        assertEquals(state.history().getFirst().sequence(), state.journalAnchor().sequence(),
                "and the body's top edge lands on the oldest retained entry");
        assertFalse(state.journalAnchor().newest(), "browsing history is not following the newest entry");

        // Coming back down reaches the newest end, which resumes following the log.
        for (int notch = 0; notch < 200; notch++) assertTrue(state.scroll(-1, content));
        assertEquals(0, offsetPx(content, state.journalAnchor()));
        assertTrue(state.journalAnchor().newest());
        assertTrue(state.scroll(-1, content),
                "the journal consumes the notch at its end so the camera never gets it");
        assertFalse(state.scroll(0, content));
        assertFalse(state.scroll(Double.NaN, content));
        assertFalse(state.scroll(Double.POSITIVE_INFINITY, content));
    }

    @Test
    void browsingWorksWhenTheFormShowsTheBodyDespiteACollapsedPreference() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        JournalViewport.Content content = content(state, 40);
        state.toggleCollapsed();
        assertTrue(state.collapsed(), "the ordinary preference is collapsed");
        // The tight/narrow form's explicit expand draws the body without touching that preference.
        assertFalse(state.scroll(1, content), "the preference alone still refuses the wheel");
        assertTrue(state.scroll(1, true, content));
        assertEquals(LINE, offsetPx(content, state.journalAnchor()));
        assertFalse(state.scroll(1, false, content));
        assertEquals(LINE, offsetPx(content, state.journalAnchor()), "a hidden body consumes nothing");
        assertTrue(state.collapsed(), "browsing does not rewrite the preference");
    }

    @Test
    void incomingEntriesAnchorBrowsingButTheNewestEndKeepsFollowing() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        JournalViewport.Content content = content(state, 40);
        assertTrue(state.scroll(5, content));
        JournalViewport.Anchor browsed = state.journalAnchor();
        assertEquals(5 * LINE, offsetPx(content, state.journalAnchor()));

        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 103), 1);
        assertEquals(browsed, state.journalAnchor(), "an appended snapshot never re-anchors the reader");
        JournalViewport.Content after = content(state, 40);
        // Three newer entries were appended and three old ones evicted by the bounded history, so the
        // same place on screen is a little further from the newest end - the reader's line never moved.
        assertEquals(5 * LINE + 3 * (LINE + GAP), offsetPx(after, state.journalAnchor()),
                "the appended entries push the newest end down, not the reader");
        assertEquals(browsed.sequence(), JournalViewport.anchorAt(after,
                JournalViewport.scrollTop(after, state.journalAnchor())).sequence(),
                "and the same entry stays under the body's top edge, not the newest one");

        for (int notch = 0; notch < 200; notch++) state.scroll(-1, after);
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 104), 2);
        assertTrue(state.journalAnchor().newest(), "at the newest end the journal keeps following new entries");
    }

    @Test
    void evictionClampsTheBrowsedPlaceToWhatStillExists() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        JournalViewport.Content content = content(state, 40);
        for (int notch = 0; notch < 200; notch++) state.scroll(1, content);
        assertEquals(37, state.journalAnchor().sequence(), "the reader is at the oldest retained entry");

        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 110), 1);
        JournalViewport.Content after = content(state, 40);
        JournalViewport.Anchor resolved = JournalViewport.anchorAt(after,
                JournalViewport.scrollTop(after, state.journalAnchor()));
        assertEquals(47, resolved.sequence(),
                "the evicted anchor clamps to the oldest entry the client still keeps");
        state.setJournalAnchor(resolved);
        assertEquals(resolved, state.journalAnchor(), "adopting the resolved place keeps the state honest");
    }

    @Test
    void collapsePreservesTheBrowsedPlaceAndNewBattleAndClearResetIt() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 20), 0);
        JournalViewport.Content content = content(state, 40);
        state.scroll(4, content);
        JournalViewport.Anchor browsed = state.journalAnchor();
        assertEquals(4 * LINE, offsetPx(content, browsed));
        state.toggleCollapsed();
        assertFalse(state.scroll(-1, content), "a folded body takes no wheel");
        assertEquals(browsed, state.journalAnchor());
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 21), 1);
        assertEquals(browsed, state.journalAnchor(), "a snapshot does not move a folded journal");
        state.toggleCollapsed();
        assertEquals(browsed, state.journalAnchor());
        state.accept(packet(SECOND, BattleLogPacket.START, 20), 2);
        assertTrue(state.journalAnchor().newest(), "a new encounter starts at the newest end");
        state.scroll(4, content(state, 40));
        state.clear();
        assertTrue(state.journalAnchor().newest(), "a world change forgets the browsed place");
    }

    private static BattleLogPacket packet(UUID id, int lifecycle, long sequence) {
        return new BattleLogPacket(id, lifecycle, sequence,
                LongStream.rangeClosed(Math.max(1, sequence - 63), sequence)
                        .mapToObj(BattleLogStateTest::entry).toList());
    }

    private static BattleLogPacket.Entry entry(long sequence) {
        return new BattleLogPacket.Entry(sequence, "action", "事件 " + sequence);
    }
}
