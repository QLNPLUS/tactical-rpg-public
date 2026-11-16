package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.battle.BattleLevelTable;
import com.tactical.neoforge.client.ui.hud.LevelSuffix;
import com.tactical.neoforge.client.ui.hud.RosterRowText;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level suffix as text and as a row: what it says, and what it is allowed to push aside.
 *
 * <p>Three answers are pinned here because the HUD must never blur them: a real level, an explicit unknown
 * for a player whose own profile could not be read, and no suffix at all for a unit this encounter has
 * nothing to say about. The layout rules are the ones the roster already lives by - the level is one
 * unbroken piece on a real line, it is measured at every one of the eight interface presets together with
 * the acting chip's own painted sides, health is never cut, and a name that is really shortened says so with
 * an explicit ellipsis instead of quietly losing its tail.</p>
 *
 * <p>The label itself is the client's own wording and is resolved per frame from the shipped language files,
 * so these tests pass it in directly rather than needing a running game.</p>
 */
class RosterLevelTextTest {

    /** Six pixels per Latin character, nine per Chinese one: the numbers the specification quotes. */
    private static final ToIntFunction<String> MEASURE = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) > 0x2E80 ? 9 : 6;
        }
        return width;
    };

    /** The interface presets the roster is measured at; all eight have to keep working. */
    private static final double[] PRESETS = {0.80D, 0.85D, 0.90D, 0.95D, 1.00D, 1.05D, 1.10D, 1.15D};

    private static final String LEVEL = LevelSuffix.of("Lv", 3);
    private static final String UNKNOWN = LevelSuffix.of("Lv", BattleLevelTable.UNKNOWN);

    // ------------------------------------------------------------------ the text itself

    @Test
    void aRealLevelIsTheCompactSuffixAndAnUnknownIsNotAZero() {
        assertEquals(" · Lv 3", LEVEL);
        assertEquals(" · Lv 12", LevelSuffix.of("Lv", 12));
        assertEquals(" · Lv " + LevelSuffix.UNKNOWN_MARK, UNKNOWN,
                "an unreadable profile is an explicit unknown, never the number zero and never a level 1");
        assertFalse(UNKNOWN.contains("0"), "the unknown marker can never be read as a level");
    }

    @Test
    void aUnitWithNothingToShowGetsNoSuffixAndNoPlaceholder() {
        assertEquals("", LevelSuffix.of("Lv", BattleLevelTable.NONE));
        assertEquals("QLN", LevelSuffix.join("QLN", "Lv", BattleLevelTable.NONE));
        assertEquals("QLN · Lv 3", LevelSuffix.join("QLN", "Lv", 3));
        assertEquals("僵尸 · Lv 5", LevelSuffix.join("僵尸", "Lv", 5));
    }

    @Test
    void theRawNameIsNeverFoldedIntoTheSuffix() {
        // The two are separate values everywhere except the drawing call, so a name that already looks like
        // a level is not "cleaned up", parsed or re-formatted.
        assertEquals("Lv 9 · Lv 3", LevelSuffix.join("Lv 9", "Lv", 3));
        assertEquals("", LevelSuffix.of("", BattleLevelTable.NONE));
        assertEquals(" · 3", LevelSuffix.of("", 3), "a language with no label still never drops the number");
    }

    @Test
    void aClippedBlockedRowKeepsItsFullNameAndItsReason() {
        assertArrayEquals(new String[]{"Ancient Skeletal Horror · Lv 1", "No command selected"},
                RosterRowText.tooltip("Ancient Skeletal Horror · Lv 1", "Ancient… · Lv 1",
                        "No command selected"));
    }

    @Test
    void anUncutBlockedRowKeepsTheExistingReasonOnly() {
        assertArrayEquals(new String[]{"No command selected", ""},
                RosterRowText.tooltip("Zombie · Lv 5", "Zombie · Lv 5", "No command selected"));
    }

    @Test
    void anUncutEligibleRowDoesNotRepeatItsVisibleName() {
        assertArrayEquals(new String[]{"", ""},
                RosterRowText.tooltip("Zombie · Lv 5", "Zombie · Lv 5", ""));
    }

    @Test
    void aClippedEligibleRowKeepsItsWholeLevelWithoutAnotherReason() {
        assertArrayEquals(new String[]{"Ancient Skeletal Horror · Lv 1", ""},
                RosterRowText.tooltip("Ancient Skeletal Horror · Lv 1", "Ancient… · Lv 1", null));
    }

    @Test
    void aMissingRecordedRowCanStillExplainWhyItCannotBeChosen() {
        assertArrayEquals(new String[]{"No command selected", ""},
                RosterRowText.tooltip(null, null, "No command selected"));
        assertArrayEquals(new String[]{"", ""}, RosterRowText.tooltip(null, null, null));
    }

    @Test
    void bothTooltipGroupsFoldWithoutLosingTheFullNameLevelOrReason() {
        String full = "Ancient Skeletal Horror of the Ashen Wastes Beyond the Ninth Gate · Lv 1";
        String reason = "No command selected";
        String[] text = RosterRowText.tooltip(full, "Ancient… · Lv 1", reason);
        for (double scale : PRESETS) {
            Tooltip.Content content = Tooltip.content(text[0], text[1], scale, MEASURE);
            assertEquals(full, String.join(" ", content.primary()));
            assertEquals(reason, String.join(" ", content.secondary()));
            assertTrue(content.lineCount() > content.primary().size());
        }
    }

    // ------------------------------------------------------------------ the level stays one piece

    /**
     * A level and its number are one value: a row may describe them on the name's line or on a permitted line
     * of its own, and never as "Lv" here and "3" there.
     */
    @Test
    void theLevelIsNeverSplitAcrossTwoRenderedLines() {
        // The exact boundary the review measured: 17 characters at six pixels is 102, one pixel over a
        // 100-pixel column, so the level moves to its own line instead of being broken after "Lv".
        RosterRowText.Arrangement boundary = RosterRowText.arrange("Vindicator", LEVEL, "", "20 / 20", 100, 3,
                1.0D, MEASURE);
        assertEquals(List.of("Vindicator", LEVEL.strip()), boundary.nameLines(),
                "the whole level moves to the next permitted line: " + boundary.nameLines());
        assertFalse(boundary.truncated(), "nothing was dropped here");

        for (double scale : PRESETS) {
            for (int budget = 40; budget <= 160; budget += 4) {
                for (int lines = 1; lines <= RosterRowText.MAX_NAME_LINES; lines++) {
                    RosterRowText.Arrangement arrangement = RosterRowText.arrange("Vindicator", LEVEL, "行动中",
                            "20 / 20", budget, lines, scale, MEASURE);
                    List<String> drawn = arrangement.nameLines();
                    assertTrue(drawn.size() <= lines);
                    for (String line : drawn) {
                        String text = line.strip();
                        assertFalse(text.equals("3"), "the number of the level must not stand alone: " + drawn);
                        assertFalse(text.endsWith("Lv"),
                                "the level label must not be separated from its number: " + drawn);
                    }
                    String visible = String.join(" ", drawn);
                    if (TextMetrics.width(MEASURE.applyAsInt(LEVEL.strip()), scale) <= budget) {
                        // The whole level fits a line of this column, so it must be shown whole somewhere.
                        assertTrue(visible.contains("Lv 3"),
                                "the level is still readable at " + scale + "x/" + budget + "px/" + lines
                                        + " lines: " + visible);
                    } else {
                        // A column narrower than the level itself only ever cuts it with the explicit marker.
                        assertTrue(visible.contains("…"),
                                "a level that cannot fit whole is explicitly cut at " + scale + "x/" + budget
                                        + "px: " + visible);
                    }
                }
            }
        }
    }

    /**
     * A name that is really shortened is marked, even when the visible fragment is short enough that a plain
     * ellipsis helper would have left it alone.
     */
    @Test
    void aShortenedNameAlwaysCarriesTheExplicitMarker() {
        // The exact second boundary of the review: one permitted line, a long name, and 42 pixels of level.
        String longName = "A " + "x".repeat(40);
        RosterRowText.Arrangement oneLine = RosterRowText.arrange(longName, LEVEL, "", "20 / 20", 100, 1,
                1.0D, MEASURE);
        assertEquals(1, oneLine.nameLines().size(), "the line cap is respected");
        String drawn = oneLine.nameLines().getFirst();
        assertTrue(drawn.endsWith(LEVEL), "the whole level survives: " + drawn);
        assertTrue(drawn.contains("…"),
                "the dropped name content is marked instead of silently disappearing: " + drawn);
        assertTrue(oneLine.truncated(), "and the row reports that it dropped name content");

        for (double scale : PRESETS) {
            for (int budget = 40; budget <= 160; budget += 4) {
                for (int lines = 1; lines <= RosterRowText.MAX_NAME_LINES; lines++) {
                    for (String name : new String[]{longName, "阿强的名字非常非常长而且还没有结束"}) {
                        RosterRowText.Arrangement arrangement = RosterRowText.arrange(name, LEVEL, "行动中",
                                "20 / 20", budget, lines, scale, MEASURE);
                        String visible = String.join(" ", arrangement.nameLines());
                        if (!arrangement.truncated()) continue;
                        // The marker is mandatory wherever the row can physically show it; only a column with
                        // no room left beside the whole level at all shows the level alone.
                        int room = budget - TextMetrics.width(MEASURE.applyAsInt(LEVEL), scale);
                        int marker = TextMetrics.width(MEASURE.applyAsInt("…"), scale);
                        if (room < marker) continue;
                        assertTrue(visible.contains("…"),
                                "a row that dropped name content must show the marker at " + scale + "x/"
                                        + budget + "px/" + lines + " lines with name '" + name + "': "
                                        + visible);
                    }
                }
            }
        }
    }

    @Test
    void aNormalSizeLevelSurvivesALongNameAtEveryPreset() {
        String[] names = {"A very long participant name", "阿强的名字非常非常长而且还没有结束"};
        // The roster's real text column, not a column narrower than the suffix itself: 80 to 110 GUI pixels.
        for (int budget : new int[]{80, 90, 110}) {
            for (double scale : PRESETS) {
                for (String name : names) {
                    for (int lines = 1; lines <= RosterRowText.MAX_NAME_LINES; lines++) {
                        RosterRowText.Arrangement arrangement = RosterRowText.arrange(name, LEVEL, "行动中",
                                "20 / 20", budget, lines, scale, MEASURE);
                        String visible = String.join(" ", arrangement.nameLines());
                        assertTrue(visible.contains("Lv 3"),
                                "the real level must survive a long name at " + scale + "x/" + budget + "px/"
                                        + lines + " lines: " + visible);
                        assertTrue(visible.endsWith("Lv 3"),
                                "the level is the tail of the row, not something in the middle: " + visible);
                    }
                }
            }
        }
    }

    @Test
    void aWideRowWithShortNamesKeepsBothPartsWhole() {
        RosterRowText.Arrangement arrangement = RosterRowText.arrange("QLN", LEVEL, "", "20 / 20", 120, 3,
                1.0D, MEASURE);
        assertEquals(List.of("QLN · Lv 3"), arrangement.nameLines(), "a short name and its level stay one line");
        assertFalse(arrangement.truncated(), "nothing was cut");
        assertTrue(arrangement.infoMerged(), "and the health still fits beside them");
    }

    @Test
    void anExtremeSuffixFallsBackToTheExplicitEllipsisRatherThanAnotherNumber() {
        // A suffix wider than the whole column cannot be shown in full; what may never happen is a silent
        // cut that reads as a different, complete number.
        String extreme = " · Lv " + "9".repeat(20);
        RosterRowText.Arrangement arrangement = RosterRowText.arrange("QLN", extreme, "", "20 / 20", 30, 3,
                1.0D, MEASURE);
        String drawn = String.join(" ", arrangement.nameLines());
        assertFalse(drawn.isEmpty(), "the row still draws something");
        assertTrue(drawn.contains("…"), "the fallback is the existing explicit ellipsis: " + drawn);
        assertTrue(drawn.length() < extreme.length() + 3, "and it really is bounded: " + drawn);
    }

    @Test
    void noSuffixLeavesTheExistingLayoutExactlyAsItWas() {
        for (double scale : PRESETS) {
            for (int budget = 20; budget <= 200; budget += 10) {
                RosterRowText.Arrangement before = RosterRowText.arrange("A very long participant name", "",
                        "行动中", "20 / 20", budget, 3, scale, MEASURE);
                RosterRowText.Arrangement after = RosterRowText.arrange("A very long participant name",
                        LevelSuffix.of("Lv", BattleLevelTable.NONE), "行动中", "20 / 20", budget, 3, scale,
                        MEASURE);
                assertEquals(before, after, "a unit with no level changes nothing about its row");
            }
        }
    }

    // ------------------------------------------------------------------ the merged line really fits

    /**
     * The row only claims the information belongs beside the name when the box the painter really draws fits.
     *
     * <p>The chip is painted as its text plus two separately scaled side pads, so a decision made from the
     * text alone let a merged row overlap the right-aligned health. The expected width is recomputed here with
     * the painter's own rounding, independently of the arrangement's own number.</p>
     */
    @Test
    void theMergedLineIsThePaintedWidthIncludingBothChipSides() {
        // The exact boundary of the review: text-only arithmetic says 130 and merges, the painted box is 134.
        RosterRowText.Arrangement boundary = RosterRowText.arrange("Ann", LEVEL, "act", "20 / 20", 132, 3,
                1.0D, MEASURE);
        assertEquals(134, boundary.mergedWidth(), "both chip sides are part of the measured line");
        assertFalse(boundary.infoMerged(), "a line that would overlap by two pixels takes its own info line");

        for (double scale : PRESETS) {
            for (int budget = 40; budget <= 220; budget += 2) {
                for (String chip : new String[]{"行动中", "Acting", ""}) {
                    RosterRowText.Arrangement arrangement = RosterRowText.arrange("Ann", LEVEL, chip, "20 / 20",
                            budget, 3, scale, MEASURE);
                    int painted = paintedMergedWidth(arrangement, scale);
                    assertEquals(painted, arrangement.mergedWidth(),
                            "the arrangement reports the painted width at " + scale + "x/" + budget + "px");
                    assertEquals(painted <= budget, arrangement.infoMerged(),
                            "the merge decision is exactly whether the painted line fits, at " + scale + "x/"
                                    + budget + "px with chip '" + chip + "'");
                    assertEquals("20 / 20", arrangement.health(),
                            "health is placed from the right edge and never cut");
                    assertEquals(chip, arrangement.chip());
                }
            }
        }
    }

    @Test
    void aRowWithoutAChipAddsNoChipSideOrChipGapToTheHealth() {
        // No chip means no pad and no health gap, exactly as before this change.
        RosterRowText.Arrangement arrangement = RosterRowText.arrange("Ann", LEVEL, "", "20 / 20", 200, 3,
                1.0D, MEASURE);
        int expected = TextMetrics.width(MEASURE.applyAsInt(arrangement.lastLine()), 1.0D)
                + Scale.effectivePx(RosterRowText.CHIP_GAP, 1.0D)
                + TextMetrics.width(MEASURE.applyAsInt("20 / 20"), 1.0D);
        assertEquals(expected, arrangement.mergedWidth());
        assertTrue(arrangement.infoMerged());
    }

    /** The width the painter really paints, rebuilt from the arrangement with the painter's own rounding. */
    private static int paintedMergedWidth(RosterRowText.Arrangement arrangement, double scale) {
        int width = TextMetrics.width(MEASURE.applyAsInt(arrangement.lastLine()), scale)
                + Scale.effectivePx(RosterRowText.CHIP_GAP, scale);
        String chip = arrangement.chip();
        if (!chip.isEmpty()) {
            width += TextMetrics.width(MEASURE.applyAsInt(chip), scale)
                    + Scale.effectivePx(Motion.CHIP_PAD_X, scale) * 2
                    + Scale.effectivePx(RosterRowText.HEALTH_GAP, scale);
        }
        return width + TextMetrics.width(MEASURE.applyAsInt(arrangement.health()), scale);
    }
}
