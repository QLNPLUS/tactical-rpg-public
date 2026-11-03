package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * How one roster row's text lays out at the width the panel currently has.
 *
 * <p>Pure, and used by both the layout and the painter: the layout asks for the line count so it can
 * size the row, the painter asks for the same arrangement so it can draw it. Because both call this
 * one function with the same width, a row can never be measured one way and painted another.</p>
 *
 * <p>The rules are the approved ones: the name wraps by character in Chinese and at spaces in Latin,
 * at most three lines, the third cut with an ellipsis when even that is not enough; the information
 * segment - the "acting" chip when there is one, plus the right-aligned health - joins the last name
 * line when it fits beside it and otherwise takes a line of its own. Health is never cut.</p>
 */
public final class RosterRowText {

    public static final int MAX_NAME_LINES = 3;
    /** The chip sits this far right of the last name line's text, then the health after a wider gap. */
    public static final int CHIP_GAP = 4;
    public static final int HEALTH_GAP = 6;

    private RosterRowText() {
    }

    public record Arrangement(List<String> nameLines, boolean truncated, boolean infoMerged,
                              String lastLine, String chip, String health) {

        /** Text lines the row needs: the name lines, plus a line of their own for the information. */
        public int textLines() {
            return nameLines.size() + (infoMerged ? 0 : 1);
        }
    }

    /**
     * Lays the row out for {@code nameBudget} pixels of text column.
     *
     * <p>Every width here is in the same unit the budget is in - GUI pixels. The measuring function
     * reports raw glyph widths at 100%, so each measurement is converted with the preset scale before
     * it is compared; comparing raw widths against a scaled budget let 115% think a name, its chip and
     * the health still fit side by side and paint them on top of each other.</p>
     *
     * @param maxLines how many name lines the row may use; the caller lowers it when the list area is
     *                 too short for three
     */
    public static Arrangement arrange(String name, String chip, String health, int nameBudget, int maxLines,
                                      double scale, ToIntFunction<String> measure) {
        String text = name == null ? "" : name;
        String chipText = chip == null ? "" : chip;
        String healthText = health == null ? "" : health;
        int budget = Math.max(1, nameBudget);
        int allowed = Math.max(1, Math.min(MAX_NAME_LINES, maxLines));
        List<String> wrapped = TextMetrics.wrap(text, budget, scale, measure);
        if (wrapped.isEmpty()) wrapped = new ArrayList<>(List.of(""));
        boolean truncated = wrapped.size() > allowed;
        List<String> lines = new ArrayList<>(wrapped.subList(0, Math.min(allowed, wrapped.size())));
        if (truncated) {
            int last = lines.size() - 1;
            lines.set(last, TextMetrics.ellipsis(lines.get(last) + "…", budget, scale, measure));
        }
        String lastLine = lines.get(lines.size() - 1);
        int lastWidth = width(lastLine, scale, measure);
        int chipWidth = chipText.isEmpty() ? 0 : width(chipText, scale, measure);
        int healthWidth = width(healthText, scale, measure);
        int needed = lastWidth + px(CHIP_GAP, scale) + (chipWidth == 0 ? 0 : chipWidth + px(HEALTH_GAP, scale))
                + healthWidth;
        boolean merged = needed <= budget;
        return new Arrangement(List.copyOf(lines), truncated, merged, lastLine, chipText, healthText);
    }

    /** Raw glyph width at 100% turned into the GUI pixels this frame really uses. */
    private static int width(String text, double scale, ToIntFunction<String> measure) {
        return TextMetrics.width(measure.applyAsInt(text == null ? "" : text), scale);
    }

    /** A design-pixel gap turned into this frame's GUI pixels. */
    private static int px(int design, double scale) {
        return Scale.effectivePx(design, scale);
    }

    /** The arrangement for a row whose name may take at most {@code maxLines} lines. */
    public static List<String> lines(RosterRowText.Arrangement arrangement) {
        return arrangement.nameLines();
    }
}
