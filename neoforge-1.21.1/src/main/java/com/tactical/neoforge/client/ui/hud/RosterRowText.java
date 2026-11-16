package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;

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
 * line when it fits beside it and otherwise takes a line of its own. Health is never cut, and the chip is
 * measured with its own painted sides, so a row that claims everything fits really does fit.</p>
 *
 * <p>A display level is the readable tail of the row, not a third segment beside the name: the suffix is
 * always one unbroken piece, so a level and its number are never split across two lines. It goes beside the
 * last name line when it fits there, otherwise it takes one of the row's own permitted lines, otherwise the
 * name in front of it is shortened to make room for the whole suffix. Whenever that shortening - or the line
 * cap - really drops name content, the row says so with an explicit ellipsis instead of showing a silently
 * shortened name. The name and the level are kept as separate values everywhere else and are only joined
 * here, which is the one place that measures and paints the row.</p>
 */
public final class RosterRowText {

    public static final int MAX_NAME_LINES = 3;
    /** The chip sits this far right of the last name line's text, then the health after a wider gap. */
    public static final int CHIP_GAP = 4;
    public static final int HEALTH_GAP = 6;

    /** The one truncation marker, so an omitted name is always marked the same way. */
    private static final String ELLIPSIS = "…";

    private RosterRowText() {
    }

    /** Full clipped text stays readable even when the row also needs an unavailable reason. */
    public static String[] tooltip(String full, String shown, String blocked) {
        String reason = blocked == null ? "" : blocked;
        if (full != null && !full.equals(shown)) return new String[]{full, reason};
        return new String[]{reason, ""};
    }

    /**
     * One row's complete text layout.
     *
     * @param truncated  true when raw name content was really dropped, i.e. some of it is not on screen.
     *                   Such a row carries {@link #ELLIPSIS} in its visible text, except in the one column
     *                   that cannot hold the mark beside the whole level - there the level wins and the
     *                   tooltip is the only way back to the name.
     * @param infoMerged whether the chip and the health join the last name line; this is only true when
     *                   {@link #mergedWidth} - the exact painted width, chip sides included - fits the
     *                   budget the row was laid out for
     * @param mergedWidth the painted width of the merged line in GUI pixels, gaps and both chip sides
     *                    included; a caller that draws must not exceed the budget this was measured against
     */
    public record Arrangement(List<String> nameLines, boolean truncated, boolean infoMerged,
                              String lastLine, String chip, String health, int mergedWidth) {

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
     * the health still fit side by side and paint them on top of each other. The chip's own two sides are
     * part of that comparison, because the painter draws them.</p>
     *
     * @param maxLines how many name lines the row may use; the caller lowers it when the list area is
     *                 too short for three
     */
    public static Arrangement arrange(String name, String suffix, String chip, String health, int nameBudget,
                                      int maxLines, double scale, ToIntFunction<String> measure) {
        String text = name == null ? "" : name;
        String suffixText = suffix == null ? "" : suffix;
        String chipText = chip == null ? "" : chip;
        String healthText = health == null ? "" : health;
        int budget = Math.max(1, nameBudget);
        int allowed = Math.max(1, Math.min(MAX_NAME_LINES, maxLines));
        boolean truncated;
        List<String> lines;
        if (suffixText.isEmpty()) {
            // The unchanged row: the name alone wraps, and only a name that did not fit is cut.
            List<String> wrapped = wrapOrEmpty(text, budget, scale, measure);
            truncated = wrapped.size() > allowed;
            lines = new ArrayList<>(wrapped.subList(0, Math.min(allowed, wrapped.size())));
            if (truncated) {
                int last = lines.size() - 1;
                lines.set(last, TextMetrics.ellipsis(lines.get(last) + ELLIPSIS, budget, scale, measure));
            }
        } else {
            List<String> nameLines = wrapOrEmpty(text, budget, scale, measure);
            boolean moreNameThanCap = nameLines.size() > allowed;
            lines = new ArrayList<>(nameLines.subList(0, Math.min(allowed, nameLines.size())));
            int last = lines.size() - 1;
            if (!moreNameThanCap && width(lines.get(last) + suffixText, scale, measure) <= budget) {
                // The whole name and the whole level on the lines it already has.
                lines.set(last, lines.get(last) + suffixText);
                truncated = false;
            } else if (!moreNameThanCap && lines.size() < allowed
                    && width(suffixText.strip(), scale, measure) <= budget) {
                // No room beside the name, but a permitted line is free and the level fits it whole.
                lines.add(suffixText.strip());
                truncated = false;
            } else {
                // The level has to share the last permitted line: the whole suffix stays and the name in
                // front of it is what gives way, marked explicitly when name content is really dropped.
                lines.set(last, keepTail(lines.get(last), suffixText, budget, scale, measure, moreNameThanCap));
                truncated = true;
            }
        }
        String lastLine = lines.get(lines.size() - 1);
        int lastWidth = width(lastLine, scale, measure);
        // The chip is painted inside two scaled side pads, so its box - not just its text - is what has to fit.
        int chipBox = chipText.isEmpty() ? 0 : width(chipText, scale, measure) + px(Motion.CHIP_PAD_X, scale) * 2;
        int healthWidth = width(healthText, scale, measure);
        int needed = lastWidth + px(CHIP_GAP, scale) + (chipBox == 0 ? 0 : chipBox + px(HEALTH_GAP, scale))
                + healthWidth;
        boolean merged = needed <= budget;
        return new Arrangement(List.copyOf(lines), truncated, merged, lastLine, chipText, healthText, needed);
    }

    /**
     * The last line when the level has to share it: the whole suffix survives and the head is what is cut.
     *
     * <p>Two different reasons can shorten this line, and both end in the same explicit marker. The name may
     * have had more lines than the row permits ({@code moreName}), in which case content is missing whatever
     * this line looks like and the marker is mandatory even when the fragment itself would fit. Or this line
     * simply has to give way to the suffix, which always means it was cut. When even the suffix alone is wider
     * than the whole column, nothing can sit beside it, so the suffix is what survives - cut with the same
     * explicit marker - because the level is the value this row exists to show, and the tooltip still carries
     * the whole raw name and the exact level.</p>
     *
     * <p>One column is narrower than every real roster preset: when what is left beside the whole suffix
     * cannot hold even the marker character, the level is shown alone rather than lost, and the row is still
     * reported as truncated so the tooltip is the way to the whole name. Hiding a real level to show a mark
     * would trade the value of the row for its punctuation.</p>
     */
    private static String keepTail(String line, String tail, int budget, double scale,
                                   ToIntFunction<String> measure, boolean moreName) {
        int markerWidth = width(ELLIPSIS, scale, measure);
        int tailWidth = width(tail, scale, measure);
        int room = budget - tailWidth;
        if (room >= markerWidth) {
            String head = moreName
                    ? TextMetrics.ellipsis(line + ELLIPSIS, room, scale, measure)
                    : TextMetrics.ellipsis(line, room, scale, measure);
            return head + tail;
        }
        // Not even the marker fits beside the level: the level keeps the line whole whenever it can, and only
        // a column narrower than the level itself is cut - always with the same explicit marker. A number is
        // never quietly shortened to make room for a name.
        String whole = tail.strip();
        return width(whole, scale, measure) <= budget
                ? whole
                : TextMetrics.ellipsis(whole, budget, scale, measure);
    }

    private static List<String> wrapOrEmpty(String text, int budget, double scale, ToIntFunction<String> measure) {
        List<String> wrapped = TextMetrics.wrap(text, budget, scale, measure);
        return wrapped.isEmpty() ? new ArrayList<>(List.of("")) : wrapped;
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
