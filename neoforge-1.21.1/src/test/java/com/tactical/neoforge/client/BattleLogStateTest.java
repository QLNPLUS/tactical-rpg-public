package com.tactical.neoforge.client;

import com.tactical.neoforge.network.BattleLogPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class BattleLogStateTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

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
    void latestSevenOfBoundedHistoryRemainInServerOrder() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        assertEquals(64, state.history().size());
        assertEquals(7, state.visibleHistory().size());
        assertEquals(94, state.visibleHistory().getFirst().sequence());
        assertEquals(100, state.visibleHistory().getLast().sequence());
        assertThrows(UnsupportedOperationException.class, () -> state.history().clear());
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
    void wheelBrowsesBoundedHistoryAndConsumesAtBothEnds() {
        BattleLogState state = new BattleLogState();
        assertFalse(state.scroll(1));
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        assertTrue(state.scroll(0.1));
        assertEquals(1, state.historyOffset());
        assertEquals(99, state.visibleHistory().getLast().sequence());
        assertTrue(state.scroll(1000));
        assertEquals(57, state.historyOffset());
        assertEquals(37, state.visibleHistory().getFirst().sequence());
        state.markClean();
        assertTrue(state.scroll(1));
        assertFalse(state.dirty());
        assertTrue(state.scroll(-1000));
        assertEquals(0, state.historyOffset());
        assertTrue(state.scroll(-1));
        assertFalse(state.scroll(0));
        assertFalse(state.scroll(Double.NaN));
        assertFalse(state.scroll(Double.POSITIVE_INFINITY));
    }

    @Test
    void incomingEntriesAnchorBrowsingButLatestKeepsFollowing() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        state.scroll(5);
        var before = state.visibleHistory();
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 103), 1);
        assertEquals(8, state.historyOffset());
        assertEquals(before, state.visibleHistory());
        state.scroll(-1000);
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 104), 2);
        assertEquals(0, state.historyOffset());
        assertEquals(104, state.visibleHistory().getLast().sequence());
    }

    @Test
    void evictionClampsOldestViewAndSmallViewportCanReachEveryEntry() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 100), 0);
        state.setVisibleLines(2);
        state.scroll(1000);
        assertEquals(62, state.historyOffset());
        assertEquals(37, state.visibleHistory().getFirst().sequence());
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 110), 1);
        assertEquals(62, state.historyOffset());
        assertEquals(47, state.visibleHistory().getFirst().sequence());
        assertEquals(48, state.visibleHistory().getLast().sequence());
        state.setVisibleLines(7);
        assertEquals(57, state.historyOffset());
    }

    @Test
    void collapsePreservesOffsetAndNewBattleAndClearResetIt() {
        BattleLogState state = new BattleLogState();
        state.accept(packet(FIRST, BattleLogPacket.START, 20), 0);
        state.scroll(4);
        state.toggleCollapsed();
        assertFalse(state.scroll(-1));
        assertEquals(4, state.historyOffset());
        state.accept(packet(FIRST, BattleLogPacket.UPDATE, 21), 1);
        assertEquals(5, state.historyOffset());
        state.toggleCollapsed();
        assertEquals(5, state.historyOffset());
        state.accept(packet(SECOND, BattleLogPacket.START, 20), 2);
        assertEquals(0, state.historyOffset());
        state.scroll(4);
        state.clear();
        assertEquals(0, state.historyOffset());
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
