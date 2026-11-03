package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Size-preset conversion, half-open hit bounds and the three window forms. */
class NativeScaleAndBoundsTest {

    @Test
    void designPixelsFollowTheInterfaceSizePreset() {
        assertEquals(22, Scale.px(22, 1.0D));
        assertEquals(18, Scale.px(22, 0.8D), "80% of a 22px row is 17.6 -> 18 whole pixels");
        assertEquals(25, Scale.px(22, 1.15D), "115% of a 22px row is 25.3 -> 25 whole pixels");
        assertEquals(18, Scale.px(22, 0.65D), "values below the smallest preset are clamped to it");
        assertEquals(10, Scale.px(9, 2.0D), "presets above 115% are clamped, so nothing is drawn twice as big");
    }

    @Test
    void fixedMetricsNeverScale() {
        assertEquals(6, Scale.EDGE, "the distance from the screen edge stays fixed at every preset");
        assertEquals(3, Scale.PANEL_CORNER);
        assertEquals(2, Scale.SMALL_CORNER);
        assertEquals(2, Scale.PANEL_SHADOW);
        assertEquals(1, Scale.SMALL_SHADOW);
    }

    @Test
    void rulesStayOnePixelAcrossTheWholeNewRange() {
        // 80-115% never reaches the 2px threshold, so every 1px rule is drawn as one pixel.
        assertEquals(1, Scale.rule(0.8D));
        assertEquals(1, Scale.rule(1.0D));
        assertEquals(1, Scale.rule(1.15D));
        assertEquals(1, Scale.rule(1.6D), "even a clamped, out-of-range value stays at one pixel");
    }

    @Test
    void aCorruptPresetFallsBackToOneAndIsClamped() {
        assertEquals(1.0D, Scale.sanitize(Double.NaN));
        assertEquals(1.0D, Scale.sanitize(0.0D));
        assertEquals(0.8D, Scale.sanitize(0.1D));
        assertEquals(1.15D, Scale.sanitize(1.6D));
        assertEquals(1.15D, Scale.sanitize(9.0D));
    }

    @Test
    void theThreeFormsAreTriedInOrderAndAgreeWithTheSpecifiedBoundaries() {
        assertEquals(Scale.Form.STANDARD, Scale.form(640, 360, 1.0D),
                "1280x720 at GUI scale 2 with 100% holds roster, rail and panel side by side");
        assertEquals(Scale.Form.STANDARD, Scale.form(640, 360, 0.8D),
                "a smaller preset leaves even more design space");
        assertEquals(Scale.Form.TIGHT, Scale.form(640, 360, 1.05D),
                "105% no longer fits 594*s + 20, but the panel keeps its height");
        assertEquals(Scale.Form.TIGHT, Scale.form(640, 360, 1.15D),
                "115% is the largest preset the standard panel's height still holds");
        assertEquals(Scale.Form.NARROW, Scale.form(427, 240, 1.0D),
                "854x480 at GUI scale 2 cannot hold the panel's 295px height");
        assertEquals(Scale.Form.STANDARD, Scale.form(1280, 720, 1.15D),
                "a 1280x720 GUI holds every form at the largest preset");
    }

    @Test
    void onlyTheNarrowFormIsNarrow() {
        assertFalse(Scale.narrow(640, 360, 1.0D), "the standard 640x360 GUI layout keeps its full form");
        assertTrue(Scale.narrow(427, 240, 1.0D), "854x480 at GUI scale 2 degrades");
        assertTrue(Scale.narrow(640, 300, 1.0D), "300px of height cannot hold the panel");
        assertTrue(Scale.narrow(460, 360, 1.0D), "the three columns do not fit 460px");
        assertFalse(Scale.narrow(590, 360, 1.0D), "590px of width still holds the tight form");
    }

    @Test
    void thePresetShrinksTheDesignSpaceBeforeTheFormIsApplied() {
        assertFalse(Scale.narrow(640, 360, 1.0D), "100% of a 640x360 GUI is the standard layout");
        assertFalse(Scale.narrow(640, 360, 0.8D), "a small preset leaves even more design space");
        assertFalse(Scale.narrow(640, 360, 1.15D),
                "115% of a 640x360 GUI still holds the panel, so it uses the tight form");
        assertTrue(Scale.narrow(1280, 720, 1.15D) == false,
                "115% of a 1280x720 GUI is 1113x626 of design space and stays standard");
    }

    @Test
    void theTightFormKeepsEveryDescriptionRowAndNeedsLessWidthThanTheStandardForm() {
        // Tight is only reachable when the standard form failed, and it needs less width than it.
        int guiWidth = 640;
        int guiHeight = 360;
        double scale = 1.15D;
        assertFalse(Scale.standard(guiWidth, guiHeight, scale));
        assertTrue(Scale.tight(guiWidth, guiHeight, scale));
        // 20 + (140 + 147 + 162) * 1.15 = 536.35 <= 640: roster, narrow rail and panel fit.
        assertTrue(20 + (Scale.ROSTER_WIDTH + Scale.RAIL_WIDTH + com.tactical.neoforge.client.ui.theme.Motion.PANEL_WIDTH) * scale <= guiWidth);
        // The panel's height at this preset plus both 6px insets stays inside the window.
        assertTrue(Scale.TIGHT_MENU_HEIGHT * scale + 2 * Scale.EDGE <= guiHeight);
    }

    @Test
    void theTightFormGivesWayToTheNarrowFormWhenThePanelCannotStayWhole() {
        assertFalse(Scale.tight(640, 300, 1.15D), "a window too short for 295*s is not tight");
        assertFalse(Scale.tight(420, 360, 1.15D), "a window too narrow for the three columns is not tight");
    }

    @Test
    void rectanglesAreHalfOpen() {
        Rect rect = Rect.of(10, 20, 5, 4);
        assertTrue(rect.contains(10, 20), "the origin pixel belongs to the rectangle");
        assertTrue(rect.contains(14.99D, 23.99D));
        assertFalse(rect.contains(15, 20), "the right edge belongs to the next cell");
        assertFalse(rect.contains(10, 24), "the bottom edge belongs to the next cell");
        assertFalse(rect.contains(9.99D, 20));
    }

    @Test
    void adjacentRowsNeverBothClaimTheSharedEdge() {
        Rect upper = Rect.of(0, 0, 20, 22);
        Rect lower = Rect.of(0, 22, 20, 22);
        assertTrue(upper.contains(10, 21.999D));
        assertFalse(upper.contains(10, 22));
        assertTrue(lower.contains(10, 22));
        for (int y = 0; y < 44; y++) {
            assertTrue(upper.contains(5, y) ^ lower.contains(5, y), "pixel row " + y + " has one owner");
        }
    }

    @Test
    void emptyRectanglesContainNothing() {
        assertFalse(Rect.EMPTY.contains(0, 0));
        assertFalse(Rect.of(5, 5, 0, 10).contains(5, 5));
        assertFalse(Rect.of(5, 5, 10, 0).contains(5, 5));
    }
}
