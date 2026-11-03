package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class CssScaleTest {
    @Test void eightEvenPresetsCoverTheReadableRange() {
        assertEquals(List.of(80, 85, 90, 95, 100, 105, 110, 115), CssScale.PRESETS);
        assertEquals(CssScale.MIN_PERCENT, CssScale.PRESETS.getFirst());
        assertEquals(CssScale.MAX_PERCENT, CssScale.PRESETS.getLast());
        for (int index = 1; index < CssScale.PRESETS.size(); index++)
            assertEquals(CssScale.STEP_PERCENT,
                    CssScale.PRESETS.get(index) - CssScale.PRESETS.get(index - 1), "one 5% step at a time");
        assertEquals("3", CssScale.VERSION);
        assertThrows(UnsupportedOperationException.class, () -> CssScale.PRESETS.add(200));
    }

    @Test void everyOldPresetMigratesOntoTheNewRange() {
        // Literal mapping of the specification: clamp to 80..115, then round to the nearest step.
        int[][] mapping = {{60, 80}, {70, 80}, {80, 80}, {85, 85}, {90, 90}, {95, 95}, {100, 100},
                {105, 105}, {110, 110}, {115, 115}, {125, 115}, {140, 115}, {160, 115}};
        for (int[] pair : mapping) {
            Properties old = new Properties();
            old.setProperty("version", "2");
            old.setProperty("percent", Integer.toString(pair[0]));
            assertEquals(pair[1], CssScale.readPercent(old), pair[0] + "% must migrate to " + pair[1] + "%");
            Properties written = CssScale.properties(old, CssScale.readPercent(old), false);
            assertEquals("3", written.getProperty("version"), "the file is rewritten as version 3");
            assertEquals(Integer.toString(pair[1]), written.getProperty("percent"));
            assertEquals(pair[1], CssScale.readPercent(written), "the migrated value is stable");
        }
    }

    @Test void legacyStepsMigrateToOriginalActualPercentagesThenSnap() {
        int[] expected = {85, 100, 115};
        for (int step = 0; step <= 2; step++) {
            Properties old = new Properties();
            old.setProperty("step", Integer.toString(step));
            assertEquals(expected[step], CssScale.readPercent(old));
            Properties migrated = CssScale.properties(CssScale.readPercent(old));
            assertEquals("3", migrated.getProperty("version"));
            assertNull(migrated.getProperty("step"));
            assertEquals(expected[step], CssScale.readPercent(migrated));
        }
    }

    @Test void aCorruptValueFallsBackToOneHundredInsteadOfAnEndOfTheRange() {
        Properties p = CssScale.properties(110);
        p.setProperty("step", "0");
        assertEquals(110, CssScale.readPercent(p));
        p.setProperty("percent", "oops");
        assertEquals(100, CssScale.readPercent(p));
        assertEquals(80, CssScale.readPercent(CssScale.properties(-50)));
        assertEquals(115, CssScale.readPercent(CssScale.properties(900)));
        assertEquals(100, CssScale.readPercent(new Properties()), "an empty file means the default");
    }

    @Test void everyWrittenValueIsAlreadyAPreset() {
        for (int raw : new int[]{-100, 0, 79, 81, 83, 87, 92, 97, 102, 108, 113, 114, 116, 400}) {
            int written = CssScale.readPercent(CssScale.properties(raw));
            assertTrue(CssScale.PRESETS.contains(written), raw + "% became " + written + "%, not a preset");
            assertEquals(0, written % CssScale.STEP_PERCENT);
        }
    }

    @Test void arrowsReachEveryPresetAndStopAtBothEnds() {
        for (int i = 0; i < CssScale.PRESETS.size(); i++) {
            int value = CssScale.PRESETS.get(i);
            assertEquals(CssScale.PRESETS.get(Math.min(i + 1, CssScale.PRESETS.size() - 1)),
                    CssScale.adjacent(value, 1));
            assertEquals(CssScale.PRESETS.get(Math.max(i - 1, 0)), CssScale.adjacent(value, -1));
        }
        assertEquals(85, CssScale.adjacent(80, 1));
        assertEquals(80, CssScale.adjacent(80, -1), "the smallest preset keeps the smaller button disabled");
        assertEquals(115, CssScale.adjacent(115, 1), "the largest preset keeps the larger button disabled");
        assertEquals(100, CssScale.adjacent(100, 0));
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
        Properties saved = CssScale.properties(110);
        saved = roundTrip(CssScale.properties(saved, CssScale.readPercent(saved), true));
        assertEquals(110, CssScale.readPercent(saved));
        assertTrue(CssScale.readRangeLegend(saved));
        saved = roundTrip(CssScale.properties(saved, 115, CssScale.readRangeLegend(saved)));
        assertEquals(115, CssScale.readPercent(saved));
        assertTrue(CssScale.readRangeLegend(saved));
        saved = roundTrip(CssScale.properties(saved, CssScale.readPercent(saved), false));
        assertEquals(115, CssScale.readPercent(saved));
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
