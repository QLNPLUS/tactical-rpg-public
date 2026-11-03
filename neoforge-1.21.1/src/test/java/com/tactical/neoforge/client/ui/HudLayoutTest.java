package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.hud.HudLayout;
import com.tactical.neoforge.client.ui.hud.JournalViewport;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry of the battle HUD, checked without a game window.
 *
 * <p>Every number here is a rule from the approved visual specification: the two bottom bounds, the
 * roster's three-step degradation, whole-row clipping, the rail's lane stacking and the rule that
 * expanding the journal never moves the roster. The class is pure, so these are the same numbers the
 * frame draws and the pointer hits.</p>
 */
class HudLayoutTest {

    private static final int[] CHARGE_IDS = {11, 12, 13};
    private static final int[] CHARGE_GAUGES = {10, 55, 100};
    private static final int ACTOR = 13;

    private static HudLayout.Input input(int width, int height, double scale, Scale.Form form, int roster,
                                         HudLayout.JournalMode journal, int[] entryLines) {
        return layoutInput(width, height, scale, form, roster, journal, entryLines, null, null, null, null, false);
    }

    /**
     * Chat history no longer reserves anything: the left column reaches over the messages. Only a
     * chat screen's edit line reserves, and that is the {@code chatInputReserve} argument below.
     */
    private static HudLayout.Input inputWithChat(int width, int height, double scale, Scale.Form form, int roster,
                                                 HudLayout.JournalMode journal, int[] entryLines,
                                                 int chatInputReserve) {
        return layoutInput2(width, height, scale, form, roster, journal, entryLines, null, null, null, null, false,
                chatInputReserve, JournalViewport.Anchor.NEWEST);
    }

    /** Sizes and measurement hooks are optional; each test passes only what it exercises. */
    private static HudLayout.Input layoutInput(int width, int height, double scale, Scale.Form form, int roster,
                                               HudLayout.JournalMode journal, int[] entryLines, Integer rosterWidth,
                                               Integer rosterHeight, Integer journalWidth, Integer journalBody,
                                               boolean menuOpen) {
        return layoutInput2(width, height, scale, form, roster, journal, entryLines, rosterWidth, rosterHeight,
                journalWidth, journalBody, menuOpen, 0, JournalViewport.Anchor.NEWEST);
    }

    private static HudLayout.Input layoutInput2(int width, int height, double scale, Scale.Form form, int roster,
                                                HudLayout.JournalMode journal, int[] entryLines, Integer rosterWidth,
                                                Integer rosterHeight, Integer journalWidth, Integer journalBody,
                                                boolean menuOpen, int chatInputReserve,
                                                JournalViewport.Anchor journalAnchor) {
        return layoutWithRosterScroll(width, height, scale, form, roster, 0, journal, entryLines, rosterWidth,
                rosterHeight, journalWidth, journalBody, menuOpen, chatInputReserve, journalAnchor);
    }

    /** The same frame with one scrolled roster offset, for the window-clamp checks. */
    private static HudLayout.Input layoutWithRoster(int width, int height, double scale, Scale.Form form, int roster,
                                                    int rosterScroll, Integer rosterHeight, int[] entryLines) {
        return layoutWithRosterScroll(width, height, scale, form, roster, rosterScroll,
                HudLayout.JournalMode.EMBEDDED, entryLines, null, rosterHeight, null, null, false, 0,
                JournalViewport.Anchor.NEWEST);
    }

    private static HudLayout.Input layoutWithRosterScroll(int width, int height, double scale, Scale.Form form,
                                                          int roster, int rosterScroll,
                                                          HudLayout.JournalMode journal, int[] entryLines,
                                                          Integer rosterWidth, Integer rosterHeight,
                                                          Integer journalWidth, Integer journalBody,
                                                          boolean menuOpen, int chatInputReserve,
                                                          JournalViewport.Anchor journalAnchor) {
        HudLayout.RosterRowLines rows = (budget, maxNameLines) -> {
            int[] lines = new int[roster];
            java.util.Arrays.fill(lines, 1);
            return lines;
        };
        HudLayout.JournalEntryLines entries = textWidth -> measure(entryLines);
        return new HudLayout.Input(width, height, scale, form, chatInputReserve, roster > 0, roster, rosterScroll,
                journal, menuOpen, CHARGE_IDS, CHARGE_GAUGES, ACTOR, false, 0, rosterWidth, rosterHeight,
                journalWidth, journalBody, rows, entries, journalAnchor);
    }

    /**
     * The journal's retained history for one text width: the line counts newest first, with sequence
     * numbers 1..n oldest first - the order the layout stacks and anchors against.
     */
    private static HudLayout.JournalMeasure measure(int[] linesNewestFirst) {
        int count = linesNewestFirst.length;
        long[] sequences = new long[count];
        for (int index = 0; index < count; index++) sequences[index] = index + 1L;
        return new HudLayout.JournalMeasure(linesNewestFirst, sequences);
    }

    private static HudLayout.Input smallTightInput(int width, int height, Integer rosterHeight, int roster) {
        HudLayout.RosterRowLines rows = (budget, maxNameLines) -> {
            int[] lines = new int[roster];
            java.util.Arrays.fill(lines, 1);
            return lines;
        };
        return new HudLayout.Input(width, height, 1.0D, Scale.Form.NARROW, 0, roster > 0, roster, 0,
                HudLayout.JournalMode.HEADER, false, CHARGE_IDS, CHARGE_GAUGES, ACTOR, false, 0,
                null, rosterHeight, null, null, rows, textWidth -> measure(new int[0]),
                JournalViewport.Anchor.NEWEST);
    }

    @Test void bottomBoundsSeparateTheAlwaysPresentPanelsFromTheFloatingBody() {
        // 1280x720 at GUI scale 2: the left column (x 6..146) stays clear of the 190-wide hotbar strip.
        HudLayout large = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, new int[0]));
        assertEquals(354, large.bres(), "Hg - 6: chat history reserves nothing any more");
        assertEquals(354, large.bflo(), "and the floating body stops at the same edge");

        // 854x480 at GUI scale 2: the left column now reaches under the hotbar strip, so 40 is avoided.
        HudLayout small = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.HEADER, new int[0]));
        assertEquals(194, small.bres(), "Hg - 6 - hotbar 40");
        assertEquals(194, small.bflo(), "Hg - 6 - hotbar 40");

        // A chat screen reserves only its edit line, and only while it is open.
        HudLayout chatting = HudLayout.compute(inputWithChat(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, new int[0], 12));
        assertEquals(342, chatting.bres(), "Hg - 6 - the 12px edit line");
        assertEquals(342, chatting.bflo(), "the floating body keeps clear of the edit line too");
    }

    @Test void theRosterTakesEveryRowThatFitsInTheLargeWindow() {
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, new int[0]));
        assertEquals(5, layout.rosterRows());
        assertFalse(layout.rosterScrollable());
        assertEquals(Rect.of(6, 6, 140, 167), layout.rosterPanel());
        // Row 0 starts under the title row, the accent rule and their gaps; rows step by 20 + 2.
        assertEquals(6 + 6 + 9 + 4 + 1 + 5, layout.rosterRow(0).y());
        assertEquals(22, layout.rosterRow(1).y() - layout.rosterRow(0).y());
    }

    /**
     * A scrolled roster is clamped to a whole window, at every scale and after any size change: the
     * frame applies {@code ScrollList.clamp} with the row count it really draws, so a notch at either
     * end can never leave a blank tail or hide the last member.
     */
    @Test void theRosterNeverScrollsPastItsLastWholeWindow() {
        int members = 12;
        int[] mixed = {2, 1, 3, 1, 2, 1, 1, 2, 3, 1, 2, 1};
        for (double scale : new double[]{0.80D, 1.0D, 1.15D}) {
            HudLayout layout = HudLayout.compute(layoutInput(640, 360, scale, Scale.Form.STANDARD, members,
                    HudLayout.JournalMode.EMBEDDED, mixed, null, null, null, null, false));
            int rows = layout.rosterRows();
            assertTrue(rows >= 1, scale + ": at least one whole row is drawn");
            assertEquals(rows < members, layout.rosterScrollable(),
                    scale + ": the bar exists exactly while a member is hidden");
            int end = ScrollList.clamp(9999, members, rows);
            assertEquals(Math.max(0, members - rows), end, scale + ": the last window is one whole window");
            assertTrue(end + rows <= members, scale + ": and it never runs past the last member");
            assertEquals(0, ScrollList.clamp(-9999, members, rows), scale + ": the first window starts at zero");
        }
        // A small window really scrolls, so the clamp above is exercised on a live offset.
        HudLayout small = HudLayout.compute(layoutInput(427, 240, 1.0D, Scale.Form.STANDARD, members,
                HudLayout.JournalMode.EMBEDDED, mixed, null, null, null, null, false));
        assertTrue(small.rosterScrollable(), "twelve mixed rows cannot all fit a 240-pixel-tall window");
        assertTrue(ScrollList.clamp(9999, members, small.rosterRows()) > 0, "and the list really has travel");
    }

    /**
     * A scrolled, short list then grows back (a real drag, or `恢复默认`): the window has to slide back
     * to the members it was hiding. Measuring "how many rows fit from the requested offset" instead let
     * the panel keep that offset, draw everything from it, and hide the first members with no bar to
     * reach them - the roster that "could not scroll back" the user reported.
     */
    @Test void aTallerRosterGivesAWaylaidOffsetBackInsteadOfHidingItsFirstMembers() {
        HudLayout shortList = HudLayout.compute(layoutWithRoster(640, 360, 1.0D, Scale.Form.STANDARD, 6, 3, 96,
                new int[]{1, 1, 1, 1, 1, 1}));
        assertTrue(shortList.rosterRows() < 6, "the short list really shows a window");
        assertTrue(shortList.rosterScrollable());
        assertTrue(shortList.rosterStart() + shortList.rosterRows() <= 6);
        HudLayout grown = HudLayout.compute(layoutWithRoster(640, 360, 1.0D, Scale.Form.STANDARD, 6, 3, null,
                new int[]{1, 1, 1, 1, 1, 1}));
        assertEquals(0, grown.rosterStart(), "every member fits again, so the window starts at the first one");
        assertEquals(6, grown.rosterRows());
        assertFalse(grown.rosterScrollable(), "and the bar is gone because nothing is hidden");
        // The same holds for a window that only grows part of the way: the offset stops where the list
        // can still be filled from the last member backwards.
        HudLayout middle = HudLayout.compute(layoutWithRoster(640, 360, 1.0D, Scale.Form.STANDARD, 6, 5, 150,
                new int[]{1, 1, 1, 1, 1, 1}));
        assertTrue(middle.rosterStart() + middle.rosterRows() <= 6, "no window runs past the last member");
        assertTrue(middle.rosterStart() <= 6 - middle.rosterRows(), "and the offset cannot strand a member");
        // The deepest window hides the first members, so it is still scrollable: reporting "nothing
        // below the window" instead made the wheel drop the notch that would have scrolled back.
        assertTrue(middle.rosterScrollable(), "hiding the first members counts as scrollable");
        assertTrue(middle.rosterRows() < 6, "and that window really does hide them");
    }

    /**
     * R2 regression: 854x480 at GUI 2 (GUI 427x240) with an explicit roster height and the large chat
     * reserve the acceptance client really had. The two footer lines used to eat the whole list, and
     * the fallback then claimed one row inside an area one pixel tall - the roster showed its title and
     * nothing else. Giving the footer back has to buy a row that is really drawn inside the list area.
     */
    @Test void aTinyWindowWithAnExplicitHeightStillDrawsOneWholeRow() {
        HudLayout layout = HudLayout.compute(smallTightInput(427, 240, 137, 5));
        assertTrue(layout.rosterRows() >= 1, "one whole row survives, or the roster is only its title");
        Rect area = layout.rosterArea();
        for (int slot = 0; slot < layout.rosterRows(); slot++) {
            Rect row = layout.rosterRow(slot);
            assertTrue(row.h() > 0, "row " + slot + " really has a height");
            assertTrue(row.y() >= area.y() && row.bottom() <= area.bottom(),
                    "the whole row is inside the list area, not clipped away by it");
            assertTrue(row.bottom() <= layout.bres(), "and it never crosses the boundary");
        }
        assertTrue(layout.rosterRow(layout.rosterRows()).h() == 0, "no row beyond the drawn ones is claimed");
        assertTrue(layout.rosterNameLineCap() >= 1 && layout.rosterNameLineCap() <= 3,
                "the name line cap stays inside the approved range");
    }

    /** The same window, but the roster got a long name: the row must fit by using fewer name lines. */
    @Test void aLongNameInATinyWindowUsesFewerLinesInsteadOfBreakingTheBoundary() {
        HudLayout.RosterRowLines longNames = (budget, maxNameLines) -> {
            int[] lines = new int[5];
            java.util.Arrays.fill(lines, 9);
            return lines;
        };
        HudLayout layout = HudLayout.compute(new HudLayout.Input(427, 240, 1.0D, Scale.Form.NARROW, 0, true, 5, 0,
                HudLayout.JournalMode.HEADER, false, CHARGE_IDS, CHARGE_GAUGES, ACTOR, false, 0,
                null, 137, null, null, longNames, textWidth -> measure(new int[0]),
                JournalViewport.Anchor.NEWEST));
        assertTrue(layout.rosterRows() >= 1, "the caller's nine lines are lowered, not obeyed");
        Rect row = layout.rosterRow(0);
        assertTrue(row.bottom() <= layout.rosterArea().bottom(), "the row stays inside the list area");
        assertTrue(row.bottom() <= layout.bres());
        assertTrue(layout.rosterNameLineCap() <= 3, "the approved three-line ceiling still applies");
    }

    /**
     * R2 second half: the same tiny window at the 115% preset. The chat history no longer reserves a
     * block, so a whole row fits inside the boundary - no last-resort crossing is needed any more.
     */
    @Test void aHigherPresetInATinyWindowStillShowsOneWholeRow() {
        HudLayout.RosterRowLines oneLine = (budget, maxNameLines) -> new int[]{1, 1, 1, 1, 1};
        HudLayout.Input tiny115 = new HudLayout.Input(427, 240, 1.15D, Scale.Form.NARROW, 0, true, 5, 0,
                HudLayout.JournalMode.HEADER, false, CHARGE_IDS, CHARGE_GAUGES, ACTOR, false, 0,
                null, 137, null, null, oneLine, textWidth -> measure(new int[0]),
                JournalViewport.Anchor.NEWEST);
        HudLayout layout = HudLayout.compute(tiny115);
        assertEquals(1, layout.rosterRows(), "one whole row survives at 115% too");
        Rect row = layout.rosterRow(0);
        Rect area = layout.rosterArea();
        assertTrue(row.h() > 0 && row.y() >= area.y() && row.bottom() <= area.bottom(),
                "the row really is inside the list area: row " + row + " area " + area);
        assertTrue(layout.rosterPanel().bottom() <= layout.bres(),
                "the panel fits inside Bres now that the chat history reserves nothing: "
                        + layout.rosterPanel().bottom() + " <= " + layout.bres());
        assertTrue(layout.rosterPanel().bottom() <= layout.bflo(),
                "and it still stops at Bflo: " + layout.rosterPanel().bottom() + " <= " + layout.bflo());
        // The journal keeps its own minimum below the roster.
        assertTrue(layout.journalPanel().y() + layout.journalPanel().h() <= layout.bflo());
    }

    @Test void theRosterGivesRowsBackInTheSmallWindowSoTheJournalStillFits() {
        HudLayout layout = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.HEADER, new int[0]));
        assertTrue(layout.rosterRows() >= 1 && layout.rosterRows() < 5,
                "the roster shrinks instead of overlapping the journal");
        // The title strip follows the roster by 4 pixels and the floating body still fits above Bflo.
        assertEquals(layout.rosterPanel().bottom() + 4, layout.journalPanel().y());
        assertTrue(layout.journalPanel().bottom() + 2 + HudLayout.JOURNAL_FLOAT_MIN <= layout.bflo(),
                "the title strip still leaves room for one complete entry plus the footer");
    }

    @Test void expandingTheStandardJournalNeverMovesTheRoster() {
        int[] entries = {2, 2, 2};
        HudLayout folded = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, entries));
        HudLayout expanded = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, entries));
        assertEquals(folded.rosterPanel(), expanded.rosterPanel(), "the roster must not move");
        assertEquals(folded.rosterRows(), expanded.rosterRows());
        assertEquals(folded.journalHeader(), expanded.journalHeader());
        assertTrue(expanded.entryRows().size() >= 1, "an expanded journal always shows a whole entry");
        assertTrue(expanded.journalPanel().bottom() <= expanded.bres(), "the panel stays above Bres");
        assertTrue(folded.journalPanel().h() < expanded.journalPanel().h());
    }

    @Test void theNarrowExpansionFloatsBelowTheSameTitleStrip() {
        int[] entries = {1, 1, 2};
        HudLayout folded = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.HEADER, entries));
        HudLayout expanded = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.FLOATING, entries));
        assertEquals(folded.rosterPanel(), expanded.rosterPanel(), "the floating body takes no roster row");
        assertEquals(folded.journalHeader(), expanded.journalHeader(), "the toggle does not move");
        assertTrue(expanded.journalFloat().h() > 0);
        assertTrue(expanded.journalFloat().y() >= expanded.journalHeader().bottom(),
                "the body starts under the title strip");
        assertTrue(expanded.journalFloat().bottom() <= expanded.bflo(), "the body stops above the hotbar");
        assertTrue(expanded.entryRows().size() >= 1);
    }

    @Test void theJournalBodyShowsAPartialEntryAtItsEdge() {
        // Seven two-line entries in a body that holds three and a half of them: the old contract
        // dropped the one that did not fit and left a blank band, which is the defect the user saw.
        int[] entries = {2, 2, 2, 2, 2, 2, 2};
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, entries));
        Rect body = layout.journalBody();
        List<HudLayout.JournalRow> rows = layout.journalRows();
        assertTrue(rows.size() >= 2, "at least two entries are on screen");
        HudLayout.JournalRow newestRow = rows.getLast();
        assertEquals(body.bottom(), newestRow.draw().bottom(),
                "the newest entry sits exactly on the body's bottom edge");
        assertFalse(newestRow.clippedBottom(), "so nothing of it is cut below the edge");
        HudLayout.JournalRow oldestRow = rows.getFirst();
        assertTrue(oldestRow.clippedTop(), "the oldest visible entry is cut by the body's top edge");
        assertTrue(oldestRow.visible().y() == body.y(), "and the scissor starts exactly at that edge");
        assertEquals(2 * 9, oldestRow.draw().h(), "the row keeps its real height, it is not squeezed");
        assertEquals(entries.length, layout.journalTotal(), "the bar still reports the retained history");
        for (HudLayout.JournalRow row : rows) {
            assertTrue(row.visible().y() >= body.y() && row.visible().bottom() <= body.bottom(),
                    "no row is painted or hit outside the body");
        }
        // Browsing one entry back moves the cut to the other edge, and the reader keeps whole lines.
        HudLayout browsed = HudLayout.compute(layoutInput(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, entries, null, null, null, null, false)
                .withJournalAnchor(new JournalViewport.Anchor(1L, 0)));
        List<HudLayout.JournalRow> browsedRows = browsed.journalRows();
        assertEquals(0, browsedRows.getFirst().index(), "the oldest entry is now inside the body");
        assertFalse(browsedRows.getFirst().clippedTop(), "and it starts at the body's top edge");
        assertTrue(browsedRows.getLast().clippedBottom(), "while the newest visible entry is cut at the bottom");
    }

    @Test void mixedLineHeightsPairWithTheEntriesTheyAreDrawnFor() {
        // The caller hands the journal the line counts newest first: newest 1 line, then 2, then 1, then 2.
        int[] newestFirst = {1, 2, 1, 2};
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, newestFirst));
        List<HudLayout.JournalRow> rows = layout.journalRows();
        assertFalse(rows.isEmpty(), "the expanded journal shows at least one entry");
        // Rows are stacked oldest first, which is the order the entries are drawn in, so the counts have
        // to be the newest-first input reversed - not the input itself.
        for (int index = 0; index < rows.size(); index++) {
            HudLayout.JournalRow row = rows.get(index);
            assertEquals(newestFirst[rows.size() - 1 - index], row.lines(),
                    "row " + index + " carries the line count of the entry drawn into it");
            assertEquals(row.lines() * 9, row.draw().h(), "and is exactly as tall as its own text");
            if (index > 0) {
                HudLayout.JournalRow previous = rows.get(index - 1);
                assertTrue(previous.draw().bottom() <= row.draw().y(), "adjacent entries never overlap");
                assertEquals(2, row.draw().y() - previous.draw().bottom(), "the approved two-pixel gap");
            }
        }
    }

    @Test void theNewestEntryHugsTheBottomAndOlderOnesFillTheBodyAboveIt() {
        // Fewer entries than the body: the newest one sits on the bottom edge and the blank space is
        // above the history, which is the console order the user asked for.
        int[] newestFirst = {2, 1, 2};
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, newestFirst));
        assertEquals(3, layout.journalDrawn());
        Rect body = layout.journalBody();
        assertEquals(body.bottom(), layout.journalRows().getLast().draw().bottom(),
                "the newest entry touches the body's bottom edge instead of floating at the top");
        assertTrue(layout.journalRows().getFirst().draw().y() > body.y(),
                "a body that is not full leaves its blank space above the history");
        // A full body starts at its own top edge: the stack is cut by the body, not pushed out of it.
        int[] manyEntries = new int[12];
        java.util.Arrays.fill(manyEntries, 1);
        HudLayout full = HudLayout.compute(layoutInput(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, manyEntries, null, null, null, 75, false));
        assertTrue(full.journalDrawn() < manyEntries.length, "this body really scrolls");
        assertEquals(0, full.journalOffsetPx(), "and it shows the newest end of the history");
        assertTrue(full.journalRows().getFirst().draw().y() <= full.journalBody().y(),
                "the oldest visible entry is cut by the body's top edge");
        assertEquals(full.journalBody().bottom(), full.journalRows().getLast().draw().bottom());
    }

    /** The thumb's bottom end is the latest entry: the newest end is the bottom of the track. */
    @Test void theJournalThumbMeasuresThePixelStackFromTheOldestEnd() {
        int[] manyEntries = new int[12];
        java.util.Arrays.fill(manyEntries, 1);
        HudLayout newest = HudLayout.compute(layoutInput(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, manyEntries, null, null, null, 75, false));
        HudLayout oldest = HudLayout.compute(layoutInput2(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, manyEntries, null, null, null, 75, false, 0,
                new JournalViewport.Anchor(1L, 0)));
        assertEquals(0, newest.journalOffsetPx(), "the newest end sits on the bottom of the stack");
        assertTrue(newest.journalMaxScrollPx() > 0, "twelve entries in a short body really scroll");
        assertEquals(newest.journalMaxScrollPx(), oldest.journalOffsetPx(),
                "browsing to the oldest entry is the far end of the travel");
        // The bar's own numbers are pixels too, so the thumb is the body's share of the stack and its
        // ends are the two ends of the history.
        Rect track = newest.journalBar();
        assertTrue(track.h() > 0, "twelve entries in a short body means a bar");
        Rect latestThumb = ScrollList.thumb(track, newest.journalTotalPx(), newest.journalBody().h(),
                JournalViewport.thumbOffset(newest.journalContent(), newest.journalView().scrollTop()), 1.0D);
        assertEquals(track.bottom(), latestThumb.bottom(), "the latest entry owns the bottom of the track");
        Rect oldestThumb = ScrollList.thumb(track, oldest.journalTotalPx(), oldest.journalBody().h(),
                JournalViewport.thumbOffset(oldest.journalContent(), oldest.journalView().scrollTop()), 1.0D);
        assertEquals(track.y(), oldestThumb.y(), "the oldest entry owns the top of the track");
        // Every position between the two ends stays inside the track, never past either end.
        int maxScroll = newest.journalMaxScrollPx();
        for (int scrollTop = 0; scrollTop <= maxScroll; scrollTop += 9) {
            Rect thumb = ScrollList.thumb(track, newest.journalTotalPx(), newest.journalBody().h(),
                    JournalViewport.thumbOffset(newest.journalContent(), scrollTop), 1.0D);
            assertTrue(thumb.y() >= track.y(), "scrollTop " + scrollTop + " keeps the thumb inside the track");
            assertTrue(thumb.bottom() <= track.bottom(), "scrollTop " + scrollTop + " keeps it inside the track");
        }
    }

    @Test void theStandardRailIsCentredAndItsCurrentActorSitsAtTheRightEnd() {
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HIDDEN, new int[0]));
        assertEquals(270, layout.rail().w());
        assertEquals((640 - 270) / 2, layout.rail().x());
        assertEquals(32, layout.rail().h());
        assertEquals(layout.rail().y() + 26, layout.railTrack().y());
        HudLayout.Portrait current = null;
        for (HudLayout.Portrait portrait : layout.portraits()) {
            if (portrait.current()) current = portrait;
        }
        assertTrue(current != null, "the acting participant is always on the rail");
        assertEquals(20, current.box().w());
        assertEquals(layout.rail().right() - 20, current.box().x());
        assertEquals(layout.rail().y() + 3, current.box().y());
    }

    @Test void overlappingPortraitsStepDownOneLane() {
        int[] ids = {21, 22};
        int[] gauges = {100, 100};
        HudLayout layout = HudLayout.compute(layoutInput(640, 360, 1.0D, Scale.Form.STANDARD, 1,
                HudLayout.JournalMode.HIDDEN, new int[0], null, null, null, null, false).withCharge(ids, gauges, -1));
        assertEquals(2, layout.portraits().size());
        assertEquals(0, layout.portraits().get(0).lane());
        assertEquals(1, layout.portraits().get(1).lane());
        assertEquals(layout.rail().y() + 8, layout.portraits().get(0).box().y());
        assertEquals(layout.rail().y() + 14, layout.portraits().get(1).box().y());
    }

    @Test void theCrampedRailKeepsOnlyTheCurrentActor() {
        HudLayout layout = HudLayout.compute(layoutInput(400, 300, 1.0D, Scale.Form.NARROW, 1,
                HudLayout.JournalMode.HIDDEN, new int[0], null, null, null, null, false).withCramped(true));
        assertEquals(1, layout.portraits().size());
        assertTrue(layout.portraits().get(0).current());
    }

    @Test void theHintIsOneLineAtTheTopCentre() {
        // The hint and the charge rail never share the top bar, so the hint is placed without one.
        HudLayout layout = HudLayout.compute(layoutInput(640, 360, 1.0D, Scale.Form.STANDARD, 0,
                HudLayout.JournalMode.HIDDEN, new int[0], null, null, null, null, false)
                .withCharge(new int[0], new int[0], -1).withHint(149));
        assertEquals(6, layout.hint().y());
        assertEquals(15, layout.hint().h());
        assertEquals((640 - 149) / 2, layout.hint().x());
        assertFalse(layout.overUi(320, 13), "the hint never owns the pointer");
    }

    @Test void theJournalBodyReservesTheChipAndItsGap() {
        assertEquals(128, HudLayout.journalInnerWidth(1.0D));
        assertEquals(128 - 34, HudLayout.journalTextWidth(1.0D));
        assertEquals(Math.round(128 * 1.15D), HudLayout.journalInnerWidth(1.15D));
    }

    /**
     * The regression this guards: the standard form used to paint the base of the title row only, so
     * its entries and footer floated straight over the battlefield. The base of that mode is the
     * whole panel - and it is one surface, never the title row plus the panel composited twice.
     */
    @Test void theStandardJournalPaintsOneBaseUnderItsBodyAndFooter() {
        int[] entries = {2, 2};
        HudLayout layout = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.EMBEDDED, entries));
        Rect base = layout.journalBase();
        assertEquals(layout.journalPanel(), base, "the body is part of the same panel, not a second surface");
        assertTrue(base.contains(layout.journalBody().x() + 1, layout.journalBody().y() + 1),
                "the base covers the first entry");
        assertTrue(base.bottom() >= layout.journalStatus().bottom(), "the base covers the footer line");
        assertTrue(base.h() > layout.journalHeader().h(), "a title-only base was the bug");
        assertFalse(layout.journalBodyFloats(), "the standard body is inline, not a floating surface");
    }

    @Test void theFoldedJournalPaintsOnlyItsTitleStrip() {
        HudLayout header = HudLayout.compute(input(640, 360, 1.0D, Scale.Form.STANDARD, 5,
                HudLayout.JournalMode.HEADER, new int[]{2}));
        assertEquals(header.journalHeader(), header.journalBase(), "a folded journal is one title strip");
        assertTrue(header.journalBase().bottom() <= header.rosterPanel().bottom() + 40);

        HudLayout floating = HudLayout.compute(input(427, 240, 1.0D, Scale.Form.NARROW, 5,
                HudLayout.JournalMode.FLOATING, new int[]{1, 1}));
        // The user's follow-up replaces the old "strip plus a second surface" rule above 100%: an
        // expanded journal is one continuous window in every form, so the base is the whole panel.
        assertEquals(floating.journalPanel(), floating.journalBase(),
                "an expanded narrow form is one window, not a strip above a second surface");
        assertTrue(floating.journalBase().y() <= floating.journalHeader().y(),
                "the one surface starts at the title row's own top edge");
        assertTrue(floating.journalBase().bottom() >= floating.journalStatus().bottom(),
                "and runs down past the footer it belongs to");
        assertTrue(floating.journalBodyFloats(), "the window is opaque FLOAT: it may cover other panels");
        assertTrue(floating.journalFloat().h() > 0);
    }

}
