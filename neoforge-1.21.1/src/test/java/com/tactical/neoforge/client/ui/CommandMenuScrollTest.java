package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command panel's action viewport.
 *
 * <p>Nine rows no longer have to fit for the panel to work: when the window cannot hold all of them,
 * the rows scroll and everything else stays exactly where it was. These checks pin the three things
 * that decision can get wrong - the type size must not be silently reduced instead of scrolling, the
 * fixed area must not move, and a row that is scrolled out must not be hoverable, clickable or
 * reported. All of it is pure layout arithmetic, so it runs without a game window.</p>
 */
class CommandMenuScrollTest {

    private static final int STANDARD_WIDTH = 640;
    private static final int STANDARD_HEIGHT = 360;
    /** 854x480 at GUI scale 2: the small-window reference of the visual specification. */
    private static final int SMALL_WIDTH = 427;
    private static final int SMALL_HEIGHT = 240;

    private static Rect hotbar(int width, int height) {
        return Rect.of(width / 2 - Scale.AVOID_WIDTH / 2, height - Scale.AVOID_HEIGHT,
                Scale.AVOID_WIDTH, Scale.AVOID_HEIGHT);
    }

    private static CommandMenuLayout layout(int width, int height, double preset, int firstRow) {
        return CommandMenuLayout.compute(width, height, preset, false, Scale.MIN_EFFECTIVE, (int) Math.round(preset * 100),
                true, firstRow);
    }

    // ------------------------------------------------------------------ when nothing scrolls

    @Test
    void aWindowThatHoldsEveryRowShowsNoViewportAtAll() {
        // 80% and 100% of the reference window hold all nine rows: no bar, no boundary rules, and the rows
        // keep their full width. (110% is one row short, which the scrolled cases below cover.)
        for (double preset : new double[]{0.80D, 1.00D}) {
            CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, preset, 0);
            assertFalse(layout.rowsScrollable(), preset + ": every row fits");
            assertEquals(CommandMenuLayout.ACTIONS.size(), layout.visibleRows(), preset + ": all rows drawn");
            assertEquals(0, layout.scrollBar().w(), preset + ": no scrollbar without scrolling");
            assertEquals(0, layout.rect(CommandMenuLayout.VIEWPORT_TOP_RULE).h(),
                    preset + ": no top boundary rule without scrolling");
            assertEquals(0, layout.rect(CommandMenuLayout.VIEWPORT_BOTTOM_RULE).h(),
                    preset + ": no bottom boundary rule without scrolling");
            for (String action : CommandMenuLayout.ACTIONS) {
                Rect row = layout.rect(CommandMenuLayout.rowId(action));
                assertTrue(row.w() > 0 && row.h() > 0, preset + ": " + action + " is drawn");
                assertEquals(layout.viewport().w(), row.w(), preset + ": a complete list keeps its full width");
            }
        }
    }

    // ------------------------------------------------------------------ when it does scroll

    @Test
    void theTallestPresetScrollsInsteadOfShrinkingTheType() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        // The whole point of the change: the player asked for 115% and gets 115%, not 109%.
        assertEquals(1.15D, layout.scale(), 1.0E-9D, "the preset is honoured, not silently reduced");
        assertTrue(layout.rowsScrollable(), "the row that does not fit scrolls");
        assertEquals(7, layout.visibleRows(), "seven whole rows fit at 115%");
        assertEquals(2, layout.maxFirstRow(), "two rows are out of sight with nine commands");
        assertEquals(CommandMenuLayout.ACTIONS.get(0), layout.visibleActions().get(0));
        assertEquals(CommandMenuLayout.ACTIONS.get(6), layout.visibleActions().get(6));
        assertFalse(layout.rowVisible("flee"), "the last row starts out of sight");
        assertFalse(layout.rowVisible("end"), "and so does the one after it");
        assertTrue(layout.rect(CommandMenuLayout.rowId("flee")).w() == 0, "and owns no rectangle at all");
    }

    @Test
    void aSmallWindowKeepsItsPresetAndScrollsTheRows() {
        for (double preset : new double[]{1.00D, 1.15D}) {
            CommandMenuLayout layout = layout(SMALL_WIDTH, SMALL_HEIGHT, preset, 0);
            assertTrue(layout.narrow(), preset + ": 427x240 uses the narrow form");
            assertEquals(Scale.sanitize(preset), layout.scale(), 1.0E-9D,
                    preset + ": the preset survives instead of collapsing to fit the rows");
            assertTrue(layout.rowsScrollable(), preset + ": the rows scroll");
            assertTrue(layout.visibleRows() >= 4 && layout.visibleRows() < CommandMenuLayout.ACTIONS.size(),
                    preset + ": a partial viewport of " + layout.visibleRows() + " rows");
            assertTrue(layout.maxFirstRow() >= 1);
            assertTrue(layout.panel().bottom() <= SMALL_HEIGHT - Scale.EDGE, preset + ": the panel stays inside");
        }
    }

    @Test
    void theNarrowViewportShowsFewerRowsAtTheLargerPreset() {
        int atHundred = layout(SMALL_WIDTH, SMALL_HEIGHT, 1.00D, 0).visibleRows();
        int atHundredFifteen = layout(SMALL_WIDTH, SMALL_HEIGHT, 1.15D, 0).visibleRows();
        assertTrue(atHundredFifteen <= atHundred,
                "a bigger type never buys more rows: " + atHundredFifteen + " <= " + atHundred);
    }

    // ------------------------------------------------------------------ the fixed area

    @Test
    void theFixedAreaNeverMovesWhenTheRowsScroll() {
        CommandMenuLayout first = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        CommandMenuLayout second = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 1);
        for (String id : new String[]{CommandMenuLayout.TITLE, CommandMenuLayout.GEAR, CommandMenuLayout.RULE,
                CommandMenuLayout.ACTOR, CommandMenuLayout.AP_VALUE, CommandMenuLayout.AP_BAR,
                CommandMenuLayout.HP_VALUE, CommandMenuLayout.HP_BAR, CommandMenuLayout.MP_VALUE,
                CommandMenuLayout.MP_BAR, CommandMenuLayout.WEAPON, CommandMenuLayout.FOOTER_RULE,
                CommandMenuLayout.FOOTER}) {
            assertEquals(first.rect(id), second.rect(id), id + " is part of the fixed area");
        }
        assertEquals(first.viewport(), second.viewport(), "the viewport itself does not move either");
        assertEquals(first.panel(), second.panel(), "and neither does the panel");
    }

    @Test
    void theHotbarStripIsNeverCovered() {
        double[][] windows = {{STANDARD_WIDTH, STANDARD_HEIGHT}, {SMALL_WIDTH, SMALL_HEIGHT}};
        for (double[] window : windows) {
            int width = (int) window[0];
            int height = (int) window[1];
            for (double preset : new double[]{0.80D, 1.00D, 1.15D}) {
                CommandMenuLayout layout = layout(width, height, preset, 0);
                assertFalse(layout.panel().intersects(hotbar(width, height)),
                        width + "x" + height + "@" + preset + ": the panel must stay clear of the hotbar");
                assertTrue(layout.panel().bottom() <= height - Scale.EDGE,
                        width + "x" + height + "@" + preset + ": and inside the window");
            }
        }
    }

    // ------------------------------------------------------------------ the offset

    @Test
    void theOffsetIsClampedToWhatReallyFits() {
        CommandMenuLayout past = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 99);
        assertEquals(past.maxFirstRow(), past.firstRow(), "asking past the end lands on the last page");
        assertEquals(2, past.firstRow());
        assertTrue(past.rowVisible("flee"), "the last row is reachable");
        CommandMenuLayout before = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, -5);
        assertEquals(0, before.firstRow(), "a negative offset is the top");
        CommandMenuLayout complete = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.00D, 3);
        assertEquals(0, complete.firstRow(), "a complete list ignores any offset");
    }

    @Test
    void aResizeClampsAnOffsetThatNoLongerHasAnythingToShow() {
        CommandMenuLayout tall = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 1);
        assertEquals(1, tall.firstRow(), "the tall window can scroll");
        // The same offset applied to a window that holds every row must come back to the top.
        CommandMenuLayout shortWindow = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.00D, tall.firstRow());
        assertEquals(0, shortWindow.firstRow(), "a complete list has no offset to keep");
        assertEquals(CommandMenuLayout.ACTIONS.size(), shortWindow.visibleRows());
    }

    // ------------------------------------------------------------------ hit testing

    @Test
    void aScrolledOutRowCannotBeHoveredOrClicked() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        Rect hidden = layout.rect(CommandMenuLayout.rowId("flee"));
        assertEquals(0, hidden.w() + hidden.h(), "a hidden row has no rectangle");
        assertFalse(layout.allRects().containsKey(CommandMenuLayout.rowId("flee")),
                "and is absent from the probe as well");
        assertFalse(layout.pageBoxes().stream().anyMatch(box -> box.id().equals(CommandMenuLayout.rowId("flee"))),
                "and from the hit order");
        // Nothing anywhere in the viewport answers with the hidden row's id.
        Rect viewport = layout.viewport();
        for (int y = viewport.y(); y < viewport.bottom(); y += 2) {
            Box hit = HitTest.topmost(layout.hitOrder(), viewport.x() + 4, y + 0.5D);
            if (hit == null) continue;
            assertFalse(hit.id().equals(CommandMenuLayout.rowId("flee")),
                    "a hidden row must not answer a click at y=" + y);
        }
    }

    @Test
    void theLastRowCanBeScrolledToAndThenClicked() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 1);
        assertTrue(layout.rowVisible("flee"), "scrolled to the bottom, the last row is drawn");
        Rect row = layout.rect(CommandMenuLayout.rowId("flee"));
        assertTrue(row.h() > 0);
        assertTrue(layout.viewport().y() <= row.y() && row.bottom() <= layout.viewport().bottom(),
                "and it stays inside the viewport");
        Box hit = HitTest.topmost(layout.hitOrder(), row.x() + row.w() / 2.0D, row.y() + row.h() / 2.0D);
        assertNotNull(hit, "the last row really answers a click");
        assertEquals(CommandMenuLayout.rowId("flee"), hit.id());
        // Every visible row is inside the viewport at every offset, which is what makes the clip honest.
        for (int offset = 0; offset <= layout.maxFirstRow(); offset++) {
            CommandMenuLayout page = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, offset);
            for (String action : page.visibleActions()) {
                Rect cell = page.rect(CommandMenuLayout.rowId(action));
                assertTrue(cell.y() >= page.viewport().y() && cell.bottom() <= page.viewport().bottom(),
                        offset + "/" + action + " must sit inside the viewport");
            }
        }
    }

    @Test
    void theNewBlockRowIsReachableWithoutScrolling() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.00D, 0);
        assertTrue(layout.rowVisible("block"), "the block command is one of the rows");
        Rect row = layout.rect(CommandMenuLayout.rowId("block"));
        assertTrue(row.h() > 0);
        Box hit = HitTest.topmost(layout.hitOrder(), row.x() + row.w() / 2.0D, row.y() + row.h() / 2.0D);
        assertEquals(CommandMenuLayout.rowId("block"), hit == null ? null : hit.id());
        // It sits between the item row and the facing row, as the visual specification asks.
        assertEquals(CommandMenuLayout.ACTIONS.indexOf("tool") + 1, CommandMenuLayout.ACTIONS.indexOf("block"));
        assertEquals(CommandMenuLayout.ACTIONS.indexOf("block") + 1, CommandMenuLayout.ACTIONS.indexOf("defend"));
    }

    @Test
    void aCompleteListLeavesTheHitOrderExactlyAsItWas() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.00D, 0);
        List<Box> boxes = layout.pageBoxes();
        assertEquals(1 + CommandMenuLayout.ACTIONS.size(), boxes.size(), "the gear plus every row");
        assertEquals(CommandMenuLayout.GEAR, boxes.get(0).id());
        assertNull(HitTest.topmost(layout.hitOrder(), layout.panel().x() - 2, layout.panel().y() + 2),
                "nothing outside the panel answers");
    }

    // ------------------------------------------------------------------ indicators

    @Test
    void theScrollIndicatorsExistOnlyWhileScrolling() {
        CommandMenuLayout scrolls = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        assertEquals(Scale.effectivePx(com.tactical.neoforge.client.ui.layout.ScrollList.BAR_WIDTH,
                        scrolls.scale()), scrolls.scrollBar().w(), "a 2px bar at this preset");
        assertEquals(1, scrolls.rect(CommandMenuLayout.VIEWPORT_TOP_RULE).h(), "a 1px rule marks the top edge");
        assertEquals(1, scrolls.rect(CommandMenuLayout.VIEWPORT_BOTTOM_RULE).h(), "and the bottom edge");
        Rect viewport = scrolls.viewport();
        assertEquals(viewport.right(), scrolls.scrollBar().right(), "the bar sits on the viewport's inner edge");
        assertTrue(scrolls.rect(CommandMenuLayout.VIEWPORT_TOP_RULE).bottom() <= viewport.y(),
                "the rule is above the rows, never over them");
        assertTrue(scrolls.rect(CommandMenuLayout.VIEWPORT_BOTTOM_RULE).y() >= viewport.bottom(),
                "the rule is below the rows");
        // A scrolling list gives up the bar and its gap; a complete one keeps its full width.
        int reserved = Scale.effectivePx(
                com.tactical.neoforge.client.ui.layout.ScrollList.BAR_WIDTH
                        + com.tactical.neoforge.client.ui.layout.ScrollList.BAR_GAP, scrolls.scale());
        for (String action : scrolls.visibleActions()) {
            assertEquals(viewport.w() - reserved, scrolls.rect(CommandMenuLayout.rowId(action)).w(),
                    action + " leaves room for the bar and its gap");
        }
    }

    @Test
    void theThumbFollowsTheOffsetAndNeverLeavesItsTrack() {
        CommandMenuLayout top = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        CommandMenuLayout bottom = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, top.maxFirstRow());
        Rect topThumb = com.tactical.neoforge.client.ui.layout.ScrollList.thumb(top.scrollBar(),
                CommandMenuLayout.ACTIONS.size(), top.visibleRows(), top.firstRow(), top.scale());
        Rect bottomThumb = com.tactical.neoforge.client.ui.layout.ScrollList.thumb(bottom.scrollBar(),
                CommandMenuLayout.ACTIONS.size(), bottom.visibleRows(), bottom.firstRow(), bottom.scale());
        assertTrue(topThumb.h() > 0 && bottomThumb.h() > 0);
        assertEquals(top.scrollBar().y(), topThumb.y(), "at the top the thumb starts at the track's top");
        assertEquals(bottom.scrollBar().bottom(), bottomThumb.bottom(), "at the end it ends at the bottom");
    }

    // ------------------------------------------------------------------ one notch, one row

    @Test
    void oneNotchMovesExactlyOneRowAndStopsAtTheEdges() {
        CommandMenuLayout layout = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.15D, 0);
        assertEquals(1, layout.scrollBy(0, -1.0D), "a notch moves one row");
        assertEquals(0, layout.scrollBy(0, 1.0D), "the top is the top");
        assertEquals(layout.maxFirstRow(), layout.scrollBy(layout.maxFirstRow(), -1.0D),
                "the end is the end: the offset never runs past the last page");
        assertEquals(0, layout.scrollBy(0, 0.0D), "a zero delta changes nothing");
        CommandMenuLayout complete = layout(STANDARD_WIDTH, STANDARD_HEIGHT, 1.00D, 0);
        assertEquals(0, complete.scrollBy(2, -1.0D), "a complete list has nothing to scroll");
    }
}
