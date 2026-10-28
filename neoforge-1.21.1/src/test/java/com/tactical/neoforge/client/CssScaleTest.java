package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class CssScaleTest {
    @Test void nineDistinctPresetsHavePublicBoundsAndNormalSize() {
        assertEquals(List.of(60, 70, 80, 90, 100, 110, 125, 140, 160), CssScale.PRESETS);
        assertEquals(CssScale.MIN_PERCENT, CssScale.PRESETS.getFirst());
        assertEquals(CssScale.MAX_PERCENT, CssScale.PRESETS.getLast());
        assertThrows(UnsupportedOperationException.class, () -> CssScale.PRESETS.add(200));
    }

    @Test void legacyStepsMigrateToOriginalActualPercentages() {
        for (int step = 0; step <= 2; step++) {
            Properties old = new Properties();
            old.setProperty("step", Integer.toString(step));
            int expected = new int[]{85, 100, 125}[step];
            assertEquals(expected, CssScale.readPercent(old));
            Properties migrated = CssScale.properties(CssScale.readPercent(old));
            assertEquals("2", migrated.getProperty("version"));
            assertNull(migrated.getProperty("step"));
            assertEquals(expected, CssScale.readPercent(migrated));
        }
    }

    @Test void newPercentWinsAndCorruptConfigIsSafe() {
        Properties p = CssScale.properties(140);
        p.setProperty("step", "0");
        assertEquals(140, CssScale.readPercent(p));
        p.setProperty("percent", "oops");
        assertEquals(100, CssScale.readPercent(p));
        assertEquals(60, CssScale.readPercent(CssScale.properties(-50)));
        assertEquals(160, CssScale.readPercent(CssScale.properties(900)));
    }

    @Test void arrowsReachEveryPresetAndHandleMigrated85WithoutJumpingToEnds() {
        assertEquals(80, CssScale.adjacent(85, -1));
        assertEquals(90, CssScale.adjacent(85, 1));
        assertEquals(85, CssScale.adjacent(85, 0));
        for (int i = 0; i < CssScale.PRESETS.size(); i++) {
            int value = CssScale.PRESETS.get(i);
            assertEquals(CssScale.PRESETS.get(Math.min(i + 1, 8)), CssScale.adjacent(value, 1));
            assertEquals(CssScale.PRESETS.get(Math.max(i - 1, 0)), CssScale.adjacent(value, -1));
        }
    }

    @Test void rangeLegendDefaultsOffWithoutResettingLegacyOrCurrentSize() {
        Properties legacy = new Properties();
        legacy.setProperty("step", "0");
        legacy.setProperty("futurePreference", "keep");
        assertFalse(CssScale.readRangeLegend(legacy));
        Properties migrated = CssScale.properties(legacy, CssScale.readPercent(legacy), true);
        assertEquals(85, CssScale.readPercent(migrated));
        assertTrue(CssScale.readRangeLegend(migrated));
        assertEquals("keep", migrated.getProperty("futurePreference"));
        assertNull(migrated.getProperty("step"));
        assertEquals("0", legacy.getProperty("step"));
        for (int percent : CssScale.PRESETS) {
            Properties current = CssScale.properties(percent);
            assertFalse(CssScale.readRangeLegend(current));
            assertEquals(percent, CssScale.readPercent(CssScale.properties(current, percent, true)));
        }
    }

    @Test void alternatingToggleAndScaleSavesRoundTripWithoutClobberingEither() throws Exception {
        Properties saved = CssScale.properties(140);
        saved = roundTrip(CssScale.properties(saved, CssScale.readPercent(saved), true));
        assertEquals(140, CssScale.readPercent(saved));
        assertTrue(CssScale.readRangeLegend(saved));
        saved = roundTrip(CssScale.properties(saved, 160, CssScale.readRangeLegend(saved)));
        assertEquals(160, CssScale.readPercent(saved));
        assertTrue(CssScale.readRangeLegend(saved));
        saved = roundTrip(CssScale.properties(saved, CssScale.readPercent(saved), false));
        assertEquals(160, CssScale.readPercent(saved));
        assertFalse(CssScale.readRangeLegend(saved));
        saved = roundTrip(CssScale.properties(saved, 100, CssScale.readRangeLegend(saved)));
        assertEquals(100, CssScale.readPercent(saved));
        assertFalse(CssScale.readRangeLegend(saved));
    }

    private static Properties roundTrip(Properties properties) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        properties.store(bytes, "preferences test");
        Properties loaded = new Properties();
        loaded.load(new java.io.ByteArrayInputStream(bytes.toByteArray()));
        return loaded;
    }

    @Test void actualPixelFontsRowsAndWidthsGrowAcrossEveryLevel() {
        int previousWidth = 0;
        int previousRow = 0;
        for (int percent : CssScale.PRESETS) {
            double scale = percent / 100.0;
            var metrics = CssScale.journal(scale);
            assertTrue(metrics.preferredWidth() > previousWidth);
            assertTrue(metrics.rowHeight() > previousRow);
            assertTrue(metrics.headerHeight() >= 28 * scale + 10 * scale);
            assertEquals(Double.parseDouble(CssScale.px(13, scale).replace("px", "")), 13 * scale, 0.011);
            previousWidth = metrics.preferredWidth();
            previousRow = metrics.rowHeight();
        }
    }
}
