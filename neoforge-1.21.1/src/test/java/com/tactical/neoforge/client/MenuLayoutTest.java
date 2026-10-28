package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuLayoutTest {
    @Test void everyPresetFitsSmallWindowsWithAnIndependentActionViewport() {
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            for (int[] viewport : new int[][]{{854, 480}, {1280, 720}, {1920, 1080}, {640, 360}}) {
                var menu = MenuLayout.calculate(viewport[0], viewport[1], scale);
                assertTrue(menu.width() + 2 * menu.inset() <= viewport[0] + .01);
                assertTrue(menu.height() + 2 * menu.inset() <= viewport[1] + .01);
                assertEquals(menu.width(), menu.pageLeft() + menu.pageWidth(), .01);
                assertTrue(menu.actionsHeight() > 0);
                assertEquals(menu.height() - 7 - menu.paddingY(),
                        menu.actionsTop() + menu.actionsHeight(), .01);
                assertTrue(menu.sideTop() + menu.sideHeight() <= menu.height() + .01);
            }
        }
    }

    @Test void maximumSizeAt480pKeepsTwoWholeCompactCommandsAndAllFixedChrome() {
        var menu = MenuLayout.calculate(854, 480, 1.6);
        assertTrue(menu.compact());
        assertTrue(menu.actionsHeight() >= 2 * 54 * 1.6);
        assertEquals(32 * 1.6, menu.headingHeight(), .01);
        assertEquals(35 * 1.6, menu.apHeight(), .01);
        assertEquals(30 * 1.6, menu.weaponHeight(), .01);
    }

    @Test void onlyActionViewportAbsorbsHeightChangesWithinCompactMode() {
        var small = MenuLayout.calculate(1280, 480, 1.4);
        var taller = MenuLayout.calculate(1280, 720, 1.4);
        assertTrue(small.compact());
        assertTrue(taller.compact());
        assertEquals(small.actionsTop(), taller.actionsTop(), .01);
        assertEquals(240, taller.actionsHeight() - small.actionsHeight(), .01);
        assertEquals(small.paddingY(), taller.paddingY(), .01);
    }

    @Test void everySidebarBudgetFitsItsActualRoundedPixelContentIncludingLongActors() {
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            for (int[] viewport : new int[][]{{1280, 360}, {854, 480}, {1280, 720}, {1920, 1080}}) {
                var menu = MenuLayout.calculate(viewport[0], viewport[1], scale);
                var side = menu.sideContent(scale);
                double allocated = px(menu.sideHeight()) - 14
                        - px(side.paddingTop()) - px(side.paddingBottom());
                assertEquals(allocated, side.contentHeight(), .02);
                assertTrue(allocated >= side.estimatedContentHeight(),
                        viewport[1] + "p at " + percent + "%: pure content estimate");
                // Independently sum the metrics actually written to CSS, including
                // all five block margins and the optional extra recovery line.
                double content = 5 * (px(side.labelLineHeight()) + px(side.labelGap())
                        + px(side.blockGap())) + px(side.actorHeight())
                        + (side.showStance() ? 4 : 3) * px(side.valueLineHeight())
                        + px(side.roundLineHeight());
                assertTrue(allocated >= content,
                        viewport[1] + "p at " + percent + "%: rounded rendered content");
                String longActor = "超长执行者名字".repeat(1000);
                double availableWidth = menu.sideWidth() - 14 - 24 * scale;
                long naturalLines = (long) Math.ceil(longActor.codePointCount(0, longActor.length())
                        * side.fontSize() / availableWidth);
                assertTrue(naturalLines > side.actorLines());
                // Explicit actor height/max-height clips rather than allowing the
                // long name to push the final MP group below the leather frame.
                double clippedActorHeight = Math.min(naturalLines * px(side.valueLineHeight()),
                        px(side.actorHeight()));
                assertEquals(px(side.actorHeight()), clippedActorHeight, .01);
                assertTrue(allocated >= content - px(side.actorHeight()) + clippedActorHeight);
            }
        }
    }

    @Test void short360pAt160PercentFoldsStanceAndCompressesOnlySidebarText() {
        var menu = MenuLayout.calculate(1280, 360, 1.6);
        var side = menu.sideContent(1.6);
        assertEquals(304, menu.sideHeight(), .01);
        assertEquals(264.4, side.contentHeight(), .01);
        assertFalse(side.showStance());
        assertEquals(1, side.actorLines());
        assertTrue(side.fontSize() < 12 * 1.6);
        assertTrue(side.fontSize() >= 16, "local text remains readable at the regression viewport");
        assertTrue(side.contentHeight() >= side.estimatedContentHeight());
        assertEquals(32 * 1.6, menu.headingHeight(), .01);
    }

    @Test void ordinary720pAnd1080pKeepSidebarFontsAndSecondaryInformation() {
        for (int height : new int[]{720, 1080}) {
            for (int percent : CssScale.PRESETS) {
                double scale = percent / 100.0;
                var side = MenuLayout.calculate(1280, height, scale).sideContent(scale);
                assertEquals(12 * scale, side.fontSize(), .01);
                assertEquals(24 * scale, side.roundFontSize(), .01);
                assertEquals(2, side.actorLines());
                assertTrue(side.showStance());
            }
        }
    }

    private static double px(double value) { return Math.round(value * 100) / 100.0; }

    @Test void unconstrainedBookRetainsOriginal420By670Proportions() {
        var menu = MenuLayout.calculate(1920, 1400, 1.6);
        assertFalse(menu.compact());
        assertEquals(420 * 1.6, menu.width(), .01);
        assertEquals(670 * 1.6, menu.height(), .01);
        assertEquals(320 * 1.6, menu.pageWidth(), .01);
        assertTrue(menu.actionsHeight() >= 7 * 62 * 1.6);
    }
}
