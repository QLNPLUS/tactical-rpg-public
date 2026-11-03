package com.tactical.neoforge.client.ui.widget;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Geometry of one tooltip: the folded text, its box and where that box goes.
 *
 * <p>Pure on purpose - no Minecraft types - so the folding width, the padding and the rule that a
 * tooltip never sits under the pointer are all checkable without a game window. Drawing lives in
 * the screen, which owns the font and the graphics context.</p>
 *
 * <p>The specification fixes the shape: {@code FLOAT} surface (floating, opaque), padding 3 inside /
 * 4 at the sides, a maximum width of 160 at 100% that folds instead of truncating, first line
 * {@code TEXT} and the supplementary lines {@code TEXT2}.</p>
 */
public final class Tooltip {

    /** Design width the text may occupy, padding included, at 100%. Folds, never truncates. */
    public static final int MAX_WIDTH = 160;
    /** Horizontal padding at 100%. */
    public static final int PAD_X = 4;
    /** Vertical padding at 100%. */
    public static final int PAD_Y = 3;
    /** Distance kept from the control that triggered the tooltip. */
    public static final int GAP = 4;
    /** Extra distance kept from the pointer when the preferred side would cover it. */
    public static final int CURSOR_CLEARANCE = 8;

    private Tooltip() {
    }

    /**
     * Folded tooltip text.
     *
     * @param primary   the first line group, drawn in {@code TEXT}
     * @param secondary the supplementary line group, drawn in {@code TEXT2}; may be empty
     */
    public record Content(List<String> primary, List<String> secondary) {

        public int lineCount() {
            return primary.size() + secondary.size();
        }

        public boolean isEmpty() {
            return lineCount() == 0;
        }
    }

    /** Folds both texts to the scaled maximum width. */
    public static Content content(String primary, String secondary, double scale,
                                  ToIntFunction<String> measure) {
        int textWidth = Scale.effectivePx(MAX_WIDTH, scale) - Scale.effectivePx(PAD_X, scale) * 2;
        List<String> first = TextMetrics.wrap(primary, textWidth, scale, measure);
        List<String> second = secondary == null || secondary.isBlank()
                ? List.of() : TextMetrics.wrap(secondary, textWidth, scale, measure);
        return new Content(first, second);
    }

    /** Rendered width of the box, padding included. */
    public static int width(Content content, double scale, ToIntFunction<String> measure) {
        int widest = 0;
        for (String line : content.primary()) widest = Math.max(widest, measure.applyAsInt(line));
        for (String line : content.secondary()) widest = Math.max(widest, measure.applyAsInt(line));
        return TextMetrics.width(widest, scale) + Scale.effectivePx(PAD_X, scale) * 2;
    }

    /** Rendered height of the box, padding included. */
    public static int height(Content content, double scale) {
        return content.lineCount() * Scale.effectivePx(Motion.LINE, scale) + Scale.effectivePx(PAD_Y, scale) * 2;
    }

    /** Y of the first text line's top edge inside the box. */
    public static int textTop(Rect box, double scale) {
        return box.y() + Scale.effectivePx(PAD_Y, scale);
    }

    /** X of every text line inside the box. */
    public static int textLeft(Rect box, double scale) {
        return box.x() + Scale.effectivePx(PAD_X, scale);
    }

    /**
     * Places the tooltip beside the control that triggered it, inside the screen, and never under
     * the pointer.
     *
     * <p>The preferred side is the left of the control, because every tactical panel hugs the right
     * edge; the right side is the fallback. Vertically it is centred on the control. If either the
     * preferred or the clamped position would cover the pointer, the box moves below the pointer and
     * then above it.</p>
     */
    public static Rect place(Rect trigger, int width, int height, int screenWidth, int screenHeight,
                             double pointerX, double pointerY, int gap) {
        int used = Math.min(width, Math.max(1, screenWidth));
        int maxX = Math.max(Scale.EDGE, screenWidth - Scale.EDGE - used);
        int x = trigger.x() - used - gap;
        if (x < Scale.EDGE) x = trigger.right() + gap;
        x = Math.max(Scale.EDGE, Math.min(maxX, x));
        int maxY = Math.max(Scale.EDGE, screenHeight - Scale.EDGE - height);
        int y = trigger.y() + (trigger.h() - height) / 2;
        y = Math.max(Scale.EDGE, Math.min(maxY, y));
        Rect box = Rect.of(x, y, used, height);
        if (box.contains(pointerX, pointerY)) {
            int below = (int) Math.round(pointerY) + CURSOR_CLEARANCE;
            box = Rect.of(x, Math.max(Scale.EDGE, Math.min(maxY, below)), used, height);
        }
        if (box.contains(pointerX, pointerY)) {
            int above = (int) Math.round(pointerY) - height - CURSOR_CLEARANCE;
            box = Rect.of(x, Math.max(Scale.EDGE, Math.min(maxY, above)), used, height);
        }
        return box;
    }
}
