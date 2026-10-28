package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BattleLogLayoutTest {
    @Test
    void small854By480WindowKeepsJournalBetweenHudAndVanillaChat() {
        // AUI browser width=1280 maps the 854x480 window to approximately 1280x720 CSS pixels.
        var panel = BattleLogLayout.place(1280, 720, 132, 438, false, 304);
        assertTrue(panel.visible());
        assertTrue(panel.lines() > 0 && panel.lines() < BattleLogState.VISIBLE_LINES);
        assertTrue(panel.top() >= 132 + BattleLogLayout.GAP);
        assertTrue(panel.top() + panel.height() <= 720 - 438 - BattleLogLayout.GAP);
        assertTrue(panel.width() < 430);
    }

    @Test
    void normalWindowUsesCompactWidthAndCapsHistoryHeight() {
        var panel = BattleLogLayout.place(1280, 720, 132, 180, false, 304);
        assertTrue(panel.visible());
        assertEquals(304, panel.width());
        assertEquals(BattleLogState.VISIBLE_LINES, panel.lines());
        assertTrue(panel.height() <= 240);
        assertTrue(panel.top() + panel.height() <= 720 - 180 - BattleLogLayout.GAP);
    }

    @Test
    void collapsedHeaderKeepsBottomAnchorAndDoesNotOccupyFormerMessageArea() {
        var open = BattleLogLayout.place(1280, 720, 132, 180, false, 338);
        var collapsed = BattleLogLayout.place(1280, 720, 132, 180, true, 338);
        assertTrue(collapsed.visible());
        assertEquals(open.top() + open.height(), collapsed.top() + collapsed.height());
        assertEquals(open.width(), collapsed.width());
        assertEquals(BattleLogLayout.HEADER_HEIGHT, collapsed.height());
        assertTrue(collapsed.height() < open.height());
    }

    @Test
    void everyPresetUsesItsActualFontMetricsAndSafeAreas() {
        int previousWidth = 0;
        for (int percent : CssScale.PRESETS) {
            var metrics = CssScale.journal(percent / 100.0);
            var panel = BattleLogLayout.place(1920, 1080, 220, 180, false, metrics);
            var collapsed = BattleLogLayout.place(1920, 1080, 220, 180, true, metrics);
            assertTrue(panel.visible(), "preset " + percent);
            assertEquals(metrics.preferredWidth(), panel.width());
            assertTrue(panel.width() > previousWidth);
            assertEquals(metrics.headerHeight() + metrics.bodyChrome()
                    + panel.lines() * metrics.rowHeight(), panel.height());
            assertEquals(metrics.headerHeight(), collapsed.height());
            assertEquals(panel.top() + panel.height(), collapsed.top() + collapsed.height());
            assertTrue(panel.top() >= 220 + BattleLogLayout.GAP);
            assertTrue(panel.top() + panel.height() <= 1080 - 180 - BattleLogLayout.GAP);
            previousWidth = panel.width();
        }
    }

    @Test
    void enlargedRowsReduceVisibleHistoryInsteadOfOverflowingIntoChat() {
        var small = BattleLogLayout.place(1280, 720, 132, 300, false, CssScale.journal(.6));
        var large = BattleLogLayout.place(1280, 720, 132, 300, false, CssScale.journal(1.6));
        assertTrue(small.visible());
        assertTrue(large.visible());
        assertTrue(large.lines() < small.lines());
        assertTrue(large.top() + large.height() <= 720 - 300 - BattleLogLayout.GAP);
    }

    @Test
    void noRoomForOneRowShowsHeaderWithoutMiddleColumnFallback() {
        var panel = BattleLogLayout.place(1280, 720, 430, 210, false, 304);
        assertTrue(panel.visible());
        assertEquals(0, panel.lines());
        assertEquals(BattleLogLayout.HEADER_HEIGHT, panel.height());
        assertEquals(12, panel.left());
        assertEquals(720 - 210 - 12, panel.top() + panel.height());
    }

    @Test
    void default1280And2828WidthsStayBottomLeftAtAllScales() {
        for (int width : new int[]{1280, 2828}) for (int percent : CssScale.PRESETS) {
            var metrics = CssScale.journal(percent / 100.0);
            var panel = BattleLogLayout.place(width, 720, 260, 180, false, metrics);
            assertTrue(panel.visible());
            assertEquals(12, panel.left());
            assertEquals(720 - 180 - 12, panel.top() + panel.height());
        }
    }

    @Test
    void narrowMenuFenceIsNotExpandedBackToLegacyMinimumWidth() {
        var panel = BattleLogLayout.place(854, 720, 132, 180, false, 110);
        assertEquals(110, panel.width());
        assertFalse(panel.visible(), "unreadable narrow headers must explicitly hide");
        assertFalse(BattleLogLayout.place(854, 720, 132, 180, false, 0).visible());
    }

    @Test
    void oversizedHudOrChatHidesRatherThanOverlappingOtherUi() {
        assertFalse(BattleLogLayout.place(1280, 720, 480, 240, false, 304).visible());
        assertFalse(BattleLogLayout.place(1280, 720, 132, 650, true, 304).visible());
    }
}
