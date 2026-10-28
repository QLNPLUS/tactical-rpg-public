package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Geometry is supplementary; real mouse callbacks and captures accept the UI. */
class JournalUiLayoutTest {
    @Test void normalSizeMatchesApprovedJournalAndHudSpace() {
        var rail = JournalUiLayout.rail(1, 1280);
        assertEquals(318, rail.left());
        assertEquals(506, rail.width());
        assertEquals(36, rail.portraitWidth());
        assertEquals(42, rail.portraitHeight());
        assertEquals(56, rail.minimumHeight());
        assertEquals(420, JournalUiLayout.menuWidth(1, 1280));
        assertEquals(290, JournalUiLayout.hudWidth(1, 1280));
    }

    @Test void everyPresetKeepsRailOutsideRosterAndJournal() {
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            var rail = JournalUiLayout.rail(scale, 1280);
            assertEquals(16, rail.left() - (12 + JournalUiLayout.hudWidth(scale, 1280)), .001);
            assertTrue(rail.contentWidth() >= rail.portraitWidth());
            assertTrue(rail.left() + rail.width() <= 1280 - JournalUiLayout.menuWidth(scale, 1280) - 36 + .001);
            assertEquals(rail.inset() + rail.portraitWidth() / 2, rail.trackInset(), .001);
            assertEquals(rail.minimumHeight(), rail.top() * 2 + rail.portraitHeight(), .001);
        }
    }

    @Test void sizeControlsStayInTheViewportAtEveryPreset() {
        for (int height : new int[]{720, 1080}) for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            double top = JournalUiLayout.controlsTop(scale, height);
            assertTrue(top >= 0);
            assertTrue(top + 140 * scale <= height - 16 * scale + .001);
        }
        assertEquals(78, JournalUiLayout.controlsTop(1, 720));
        assertEquals(124.8, JournalUiLayout.controlsTop(1.6, 720), .001);
    }

    @Test void realChargeCoordinatesKeepTheirMeaningInsideDecoratedRail() {
        var rail = JournalUiLayout.rail(1, 1280);
        var p = TurnChargeRailLayout.place(new int[]{7, 8, 9}, new int[]{0, 50, 12},
                9, rail.contentWidth(), rail.portraitWidth());
        assertEquals(0, p[0].left());
        assertEquals((rail.contentWidth() - rail.portraitWidth()) / 2, p[1].left(), .001);
        assertEquals(rail.contentWidth() - rail.portraitWidth(), p[2].left(), .001);
    }

    @Test void portraitDimensionsAndInsetAgreeWithNativeFourTimesTexture() {
        assertEquals(JournalUiLayout.PORTRAIT_WIDTH * 4, JournalUiLayout.FRAME_TEXTURE_WIDTH);
        assertEquals(JournalUiLayout.PORTRAIT_HEIGHT * 4, JournalUiLayout.FRAME_TEXTURE_HEIGHT);
        assertEquals(20, JournalUiLayout.FRAME_INSET * 4);
    }

    @Test void sharedBudgetKeepsFourJournalRowsAndAtLeastThreeRosterRowsWhenFeasible() {
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            var budget = JournalUiLayout.stack(scale, 1080, 180, true, 40);
            assertTrue(budget.journalVisible(), "preset " + percent);
            assertEquals(4, budget.journalLines());
            assertTrue(budget.rosterHeight() >= 3 * Math.ceil(31 * scale));
            var panel = BattleLogLayout.place(1920, 1080, budget.hudBottom(), 180, false, CssScale.journal(scale));
            assertTrue(panel.visible());
            assertTrue(panel.lines() >= 4, "preset " + percent);
            assertTrue(panel.top() >= budget.hudBottom() + BattleLogLayout.GAP);
        }
    }

    @Test void normalViewportNoLongerReservesOnlyOneRosterRow() {
        var budget = JournalUiLayout.stack(1, 720, 180, true, 40);
        assertEquals(4, budget.journalLines());
        assertTrue(budget.rosterHeight() >= 3 * 31);
        assertTrue(budget.rosterHeight() < 40 * 31, "long roster must scroll");
        assertTrue(JournalUiLayout.stack(1, 720, 180, false, 40).rosterHeight() > budget.rosterHeight());
    }

    @Test void tinyViewportDegradesRowsThenHeaderThenHidesWithoutEatingSafeArea() {
        var reduced = JournalUiLayout.stack(1, 720, 300, true, 40);
        assertTrue(reduced.journalLines() > 0 && reduced.journalLines() < 4);
        assertTrue(reduced.rosterHeight() >= 3 * 31);
        var header = JournalUiLayout.stack(1, 720, 438, true, 40);
        assertTrue(header.journalVisible());
        assertEquals(0, header.journalLines());
        assertTrue(header.rosterHeight() >= 2 * 31);
        var hidden = JournalUiLayout.stack(1.6, 720, 500, true, 40);
        assertFalse(hidden.journalVisible());
        assertTrue(hidden.rosterHeight() >= 0);
    }

    @Test void widenedJournalNeverReachesOperationPanelAtAnyPreset() {
        for (int width : new int[]{320, 854, 1280, 1920, 2828}) for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            int journal = JournalUiLayout.journalWidth(scale, width);
            assertTrue(journal >= 0);
            if (journal > 0) assertTrue(BattleLogLayout.GAP + journal
                    <= width - JournalUiLayout.menuWidth(scale, width) - 40 + .001);
        }
        assertTrue(JournalUiLayout.journalWidth(1, 1280) > JournalUiLayout.HUD_WIDTH);
    }

    @Test void sharedBudgetIsIndependentOfHugeRosterMembership() {
        assertEquals(JournalUiLayout.stack(1, 720, 180, true, 40),
                JournalUiLayout.stack(1, 720, 180, true, 4000));
        assertEquals(0, JournalUiLayout.stack(1, 720, 180, true, 0).rosterHeight());
    }

    @Test void journalHeaderMetricsIncludeFrameAndContentPadding() {
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            var metric = CssScale.journal(scale);
            assertTrue(metric.headerHeight() >= 14 + (13 + 13 + 28) * scale);
            assertEquals(Math.max(180, (int) Math.ceil(JournalUiLayout.HUD_WIDTH * scale)), metric.preferredWidth());
        }
    }
}
