package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.hud.HudLayout;
import com.tactical.neoforge.client.ui.hud.JournalViewport;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal window, the three handle positions and the charged rail's pointer ownership.
 *
 * <p>These are the three defects the user reported on the 20:42 build, checked against the same pure
 * layout the frame paints and the pointer hits: the drawn entries and the scroll bar must describe
 * the same window, an expanded journal carries its grip on its own bottom-right corner, and the top
 * portraits are part of the interface that owns the pointer.</p>
 */
class HudJournalWindowTest {

    private static final int[] CHARGE_IDS = {11, 12, 13};
    private static final int[] CHARGE_GAUGES = {10, 55, 100};
    private static final int ACTOR = 13;

    /** Twelve retained entries, newest first, mixing one, two and three text lines. */
    private static final int[] MIXED = {2, 1, 3, 1, 2, 1, 1, 2, 3, 1, 2, 1};

    private static HudLayout.Input input(int width, int height, double scale, Scale.Form form, int roster,
                                         HudLayout.JournalMode journal, int[] entryLines, Integer journalBody,
                                         int chatInputReserve, JournalViewport.Anchor journalAnchor) {
        HudLayout.RosterRowLines rows = (budget, maxNameLines) -> {
            int[] lines = new int[roster];
            java.util.Arrays.fill(lines, 1);
            return lines;
        };
        HudLayout.JournalEntryLines entries = textWidth -> measure(entryLines);
        return new HudLayout.Input(width, height, scale, form, chatInputReserve, roster > 0, roster, 0, journal,
                false, CHARGE_IDS, CHARGE_GAUGES, ACTOR, false, 0, null, null, null, journalBody, rows, entries,
                journalAnchor);
    }

    /** The retained history for one text width: lines newest first, sequences 1..n oldest first. */
    private static HudLayout.JournalMeasure measure(int[] linesNewestFirst) {
        long[] sequences = new long[linesNewestFirst.length];
        for (int index = 0; index < sequences.length; index++) sequences[index] = index + 1L;
        return new HudLayout.JournalMeasure(linesNewestFirst, sequences);
    }

    /** The anchor of the retained entry carrying this sequence, at its own top edge. */
    private static JournalViewport.Anchor anchor(long sequence) {
        return new JournalViewport.Anchor(sequence, 0);
    }

    @Test
    void mixedEntriesProduceRowsTheBarAndTheCountOnTheSameFrame() {
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        Rect body = layout.journalBody();
        assertEquals(MIXED.length, layout.journalTotal(), "the bar's total is the retained history");
        assertEquals(0, layout.journalOffsetPx(), "the newest end is where the journal starts");
        assertEquals(Math.max(0, layout.journalTotalPx() - body.h()), layout.journalMaxScrollPx());
        List<HudLayout.JournalRow> rows = layout.journalRows();
        assertTrue(rows.size() >= 2, "a mixed history fills the body with several entries");
        assertEquals(rows.size(), layout.journalDrawn());
        assertEquals(rows.getFirst().index(), layout.journalFirstIndex());
        assertEquals(body.bottom(), rows.getLast().draw().bottom(),
                "the newest entry sits on the body's bottom edge");
        // Rows are stacked oldest first; each is exactly as tall as its own wrapped text, so a long
        // entry never paints into a short rectangle and never stops the next one from being drawn.
        for (int index = 0; index < rows.size(); index++) {
            HudLayout.JournalRow row = rows.get(index);
            int newestFirst = MIXED.length - 1 - row.index();
            assertEquals(MIXED[newestFirst] * 9, row.draw().h(),
                    "row " + index + " carries its own entry's line count");
            assertEquals(MIXED[newestFirst], row.lines());
            if (index > 0) {
                HudLayout.JournalRow previous = rows.get(index - 1);
                assertEquals(previous.draw().bottom() + 2, row.draw().y(), "the approved two-pixel gap");
            }
            assertTrue(body.contains(row.visible().x() + 1, row.visible().y() + 1),
                    "row " + index + " is hit where the body shows it");
        }
        assertTrue(rows.getFirst().clippedTop(),
                "the oldest visible entry is cut by the body's top edge, not dropped whole");
        assertFalse(rows.getLast().clippedBottom(), "the newest entry ends on the body's bottom edge");
        Rect track = layout.journalBar();
        assertTrue(track.w() > 0 && track.bottom() <= body.bottom(),
                "the track never leaves the body or presses the footer");
    }

    @Test
    void browsingTheAnchorMovesTheStackWithoutReorderingTheHistory() {
        HudLayout newest = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        HudLayout older = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, anchor(5)));
        assertEquals(MIXED.length, older.journalTotal(), "browsing never rewrites the retained history");
        assertTrue(older.journalOffsetPx() > newest.journalOffsetPx(),
                "the browsed position is further from the newest end");
        assertTrue(older.journalFirstIndex() < newest.journalFirstIndex(),
                "and it shows older entries than the newest end does");
        assertEquals(5, older.journalAnchor().sequence(), "the anchor is the entry the reader asked for");
        // A position the history no longer holds is not a place: it clamps to the nearest end.
        HudLayout stale = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, anchor(0)));
        assertEquals(0, stale.journalFirstIndex(), "a stale position clamps to the oldest entry");
        assertEquals(stale.journalMaxScrollPx(), stale.journalOffsetPx(),
                "which is the far end of the travel");
        assertEquals(1, stale.journalAnchor().sequence(), "and the resolved anchor is the oldest entry");
    }

    @Test
    void aTallerBodyShowsMoreThanSevenEntries() {
        HudLayout shortBody = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        int[] twelveSingleLines = new int[12];
        java.util.Arrays.fill(twelveSingleLines, 1);
        HudLayout tallBody = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, twelveSingleLines, 130, 0, JournalViewport.Anchor.NEWEST));
        assertTrue(tallBody.journalDrawn() > 7,
                "the old cap of seven is gone, drawn " + tallBody.journalDrawn());
        assertTrue(tallBody.journalDrawn() > shortBody.journalDrawn());
        // The body is limited by the frame's own bound between the rule and the footer, never by a
        // seven-entry cap, so a full body still shows more rows than the old window could.
        assertTrue(tallBody.journalDrawn() <= 12, "and never more than the retained history");
        assertTrue(tallBody.journalDrawn() >= 11, "eleven single-line rows fit the body the frame allows");
        assertTrue(tallBody.journalBody().h() > 7 * (9 + 2), "which is more than seven single-line rows");
        assertTrue(tallBody.journalBody().bottom() <= tallBody.bres(), "the body stays inside the bound");
    }

    @Test
    void theExpandedHandleSitsOnTheSurfaceThatIsReallyDrawn() {
        HudLayout folded = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        assertHandleAtCorner(folded.journalHeader(), folded, "a folded journal keeps the title-strip corner");

        HudLayout embedded = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        assertHandleAtCorner(embedded.journalPanel(), embedded, "the inline body's own corner");
        assertFalse(embedded.journalHandleHit().contains(
                        embedded.journalHeader().right() - 3, embedded.journalHeader().bottom() - 3),
                "the title-strip corner no longer resizes an expanded journal");
        assertTrue(embedded.journalPanel().h() > embedded.journalHeader().h(),
                "and the surface really is taller than the title strip");

        HudLayout floating = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.FLOATING, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        assertHandleAtCorner(floating.journalFloat(), floating, "the floating body's own corner");
        assertFalse(floating.journalHandleHit().contains(
                        floating.journalHeader().right() - 3, floating.journalHeader().bottom() - 3),
                "only one handle exists while the body floats, and it is not beside the toggle");
        assertTrue(floating.journalCount().right() <= floating.journalFloat().right() - 6 - 8,
                "the footer count stays clear of the reserved corner");
    }

    private static void assertHandleAtCorner(Rect surface, HudLayout layout, String message) {
        assertTrue(surface.w() > 0 && surface.h() > 0, message + ": the surface exists");
        Rect handle = layout.journalHandle();
        Rect hit = layout.journalHandleHit();
        assertTrue(handle.right() <= surface.right() && handle.bottom() <= surface.bottom(), message);
        assertTrue(handle.bottom() > surface.bottom() - 8 && handle.right() > surface.right() - 8,
                message + ": the grip is inside the reserved corner");
        assertEquals(surface.right(), hit.right(), message + ": the hit area shares that corner");
        assertEquals(surface.bottom(), hit.bottom(), message + ": the hit area shares that corner");
    }

    @Test
    void theLeftColumnReachesOverTheChatAndOnlyTheEditLineIsAvoided() {
        // 1280x720 at GUI 2: the panel used to stop 68 pixels early for chat history.
        HudLayout noChat = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        HudLayout chatting = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 12, JournalViewport.Anchor.NEWEST));
        assertEquals(354, noChat.bres(), "no chat history block is reserved");
        assertTrue(noChat.journalPanel().bottom() > 360 - 6 - 68,
                "the journal reaches into the area chat history used to keep");
        assertEquals(342, chatting.bres(), "only the 12px edit line is avoided while chat is open");
        assertTrue(chatting.journalPanel().bottom() <= chatting.bres(),
                "and the panel stops above it: " + chatting.journalPanel().bottom() + " <= " + chatting.bres());
        assertTrue(chatting.journalBody().bottom() < chatting.journalPanel().bottom(),
                "the body stays above its own footer, so nothing is painted into the edit line");
    }

    @Test
    void theRailAndItsPortraitsOwnThePointer() {
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, MIXED, null, 0, JournalViewport.Anchor.NEWEST));
        assertTrue(layout.rail().w() > 0);
        assertTrue(layout.overUi(layout.rail().x() + 1, layout.rail().y() + 1),
                "the charge rail owns the pointer, not only the roster and the journal");
        for (HudLayout.Portrait portrait : layout.portraits()) {
            Rect box = portrait.box();
            assertTrue(layout.overUi(box.x() + box.w() / 2.0D, box.y() + box.h() / 2.0D),
                    "portrait " + portrait.entityId() + " is hit where it is drawn");
        }
        HudLayout folded = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HIDDEN, new int[0], null, 0, JournalViewport.Anchor.NEWEST));
        assertTrue(folded.overUi(folded.rail().x() + 1, folded.rail().y() + 1),
                "a hidden journal must not short-circuit the rail hit - the original bug");
        assertFalse(folded.overUi(320, 200), "the empty middle of the screen stays the battlefield's");
    }
}
