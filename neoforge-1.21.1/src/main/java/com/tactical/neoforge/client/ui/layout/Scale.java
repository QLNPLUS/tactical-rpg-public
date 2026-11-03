package com.tactical.neoforge.client.ui.layout;

import com.tactical.neoforge.client.ui.theme.Motion;

/**
 * Converts the fixed 100% design values into the pixels of the current interface-size preset, and
 * decides which of the three window forms fits the current window.
 *
 * <p>The rules are fixed by the visual specification:
 * <ul>
 *   <li>panel and row widths, heights, padding, gaps, icon sizes, bar heights and text scale with the
 *       preset; every rectangle is rounded to whole GUI pixels before it is drawn or hit-tested;</li>
 *   <li>the distance from the screen edge, the stepped-corner insets, shadow offsets, the avoidance
 *       area and every animation duration stay fixed;</li>
 *   <li>one-pixel rules stay 1px: the new 80-115% range never reaches the 2px threshold.</li>
 * </ul></p>
 *
 * <p>Window forms, in the order they are tried: standard, then the new tight form (the menu keeps its
 * description rows, the journal folds to its title row and the charge rail narrows), then narrow.
 * The effective scale only drops when none of the three fits, and never below {@link #MIN_EFFECTIVE}
 * of the current frame.</p>
 */
public final class Scale {

    /** Distance from the screen edge. Fixed: it does not scale with the preset. */
    public static final int EDGE = 6;
    /** Smallest and largest preset, mirrored from the saved range. */
    public static final double MIN_PRESET = 0.80D;
    public static final double MAX_PRESET = 1.15D;
    /**
     * The effective scale may not fall below the smallest preset when a preset does not fit: the
     * specification forbids shrinking the text into unreadable pixels. A GUI scale of 1 raises the
     * floor to 1.0, because a glyph below one screen pixel loses its strokes.
     */
    public static final double MIN_EFFECTIVE = MIN_PRESET;
    /**
     * Design width below which the charge rail keeps only the current actor. It is the
     * specification's last-resort degradation, applied after the three forms have failed.
     */
    public static final int CRAMPED_WIDTH = 420;
    /** Stepped-corner insets (panel: 3/1, small parts: 2/1) and shadow offsets, all fixed. */
    public static final int PANEL_CORNER = 3;
    public static final int SMALL_CORNER = 2;
    public static final int PANEL_SHADOW = 2;
    public static final int SMALL_SHADOW = 1;

    /** The roster and the tight/narrow charge rail at 100%; the command panel is {@link Motion#PANEL_WIDTH}. */
    public static final int ROSTER_WIDTH = 140;
    public static final int RAIL_WIDTH = 147;

    /** Standard form: {@code Wg >= 594*s + 20} and {@code Hg >= 320*s}. */
    public static final int STANDARD_WIDTH = 594;
    public static final int STANDARD_SLACK = 20;
    public static final int STANDARD_HEIGHT = 320;
    /** The standard form's panel height at 100%, the basis of the tight form's vertical check. */
    public static final int TIGHT_MENU_HEIGHT = 295;

    /** Vanilla hotbar/health/hunger avoidance area: bottom centre, 190 wide and 40 tall. */
    public static final int AVOID_WIDTH = 190;
    public static final int AVOID_HEIGHT = 40;

    /** Which layout the current window and preset use, tried in this order. */
    public enum Form { STANDARD, TIGHT, NARROW }

    private Scale() {
    }

    /** The design value at 100% rendered at the given preset, rounded to whole GUI pixels. */
    public static int px(double designValue, double scale) {
        return (int) Math.round(designValue * sanitize(scale));
    }

    /** A line width that stays 1px until the preset makes 1px invisible, then becomes 2px. */
    public static int rule(double scale) {
        return sanitize(scale) > 1.5D ? 2 : 1;
    }

    /** Interface size presets may only be 0.80 .. 1.15; anything else is a corrupt value. */
    public static double sanitize(double scale) {
        if (!Double.isFinite(scale) || scale <= 0.0D) return 1.0D;
        return Math.max(MIN_PRESET, Math.min(MAX_PRESET, scale));
    }

    /**
     * The scale actually used to draw one frame: the preset, except that a window too small to hold
     * even the smallest preset keeps shrinking down to {@link #MIN_EFFECTIVE}. The saved preset is
     * never modified, so nothing about this is persistent.
     */
    public static double effective(double scale) {
        return effective(scale, MIN_EFFECTIVE);
    }

    /** {@link #effective} with the current frame's floor (a GUI scale of 1 raises it to 1.0). */
    public static double effective(double scale, double floor) {
        double used = Double.isFinite(scale) && scale > 0.0D ? scale : 1.0D;
        double low = Math.max(MIN_EFFECTIVE, Math.min(MAX_PRESET, floor));
        return Math.max(low, Math.min(MAX_PRESET, used));
    }

    /** {@link #px} for an effective scale, which may sit below the presets. */
    public static int effectivePx(double designValue, double scale) {
        return (int) Math.round(designValue * effective(scale));
    }

    /**
     * The form this window uses at this preset: standard, then tight, then narrow, first that fits.
     *
     * <p>{@code Wg} and {@code Hg} are the usable GUI size, i.e. the window in GUI pixels; the preset
     * is the saved interface size.</p>
     */
    public static Form form(int guiWidth, int guiHeight, double scale) {
        double s = sanitize(scale);
        if (standard(guiWidth, guiHeight, s)) return Form.STANDARD;
        if (tight(guiWidth, guiHeight, s)) return Form.TIGHT;
        return Form.NARROW;
    }

    /**
     * Standard form: the roster, the centred charge rail and the command panel fit side by side with
     * at least 4 pixels between them ({@code Wg >= 594*s + 20}), and the window is tall enough for the
     * full panel with its description rows ({@code Hg >= 320*s}).
     */
    public static boolean standard(int guiWidth, int guiHeight, double scale) {
        double s = sanitize(scale);
        return guiHeight >= STANDARD_HEIGHT * s && guiWidth >= STANDARD_WIDTH * s + STANDARD_SLACK;
    }

    /**
     * Tight form: the standard side-by-side arrangement does not fit, but the standard panel still
     * fits vertically and the three columns fit across the window. The panel keeps its description
     * rows, weapon row and footer; only the journal folds and the rail narrows.
     */
    public static boolean tight(int guiWidth, int guiHeight, double scale) {
        double s = sanitize(scale);
        boolean menuFitsVertically = TIGHT_MENU_HEIGHT * s + 2 * EDGE <= guiHeight;
        boolean columnsFit = STANDARD_SLACK + (ROSTER_WIDTH + RAIL_WIDTH + Motion.PANEL_WIDTH) * s <= guiWidth;
        return menuFitsVertically && columnsFit && clearOfAvoidArea(guiWidth, guiHeight, s);
    }

    /** The standard-form panel must not intersect the vanilla hotbar/health/hunger strip. */
    private static boolean clearOfAvoidArea(int guiWidth, int guiHeight, double s) {
        int panelLeft = guiWidth - EDGE - (int) Math.round(Motion.PANEL_WIDTH * s);
        int panelRight = guiWidth - EDGE;
        int avoidLeft = guiWidth / 2 - AVOID_WIDTH / 2;
        int avoidRight = avoidLeft + AVOID_WIDTH;
        int avoidTop = guiHeight - AVOID_HEIGHT;
        int panelBottom = EDGE + (int) Math.round(TIGHT_MENU_HEIGHT * s);
        return panelRight <= avoidLeft || panelLeft >= avoidRight || panelBottom <= avoidTop;
    }

    /** True when the window only fits the narrow form. */
    public static boolean narrow(int guiWidth, int guiHeight, double scale) {
        return form(guiWidth, guiHeight, scale) == Form.NARROW;
    }

    /** {@link #narrow} for an effective scale, so the threshold follows the scale really used. */
    public static boolean narrowForEffective(int guiWidth, int guiHeight, double scale) {
        return narrow(guiWidth, guiHeight, effective(scale));
    }

    /**
     * True when the window is too narrow for the roster, the panel and the full charge rail: the
     * specification's last-resort degradation, which first reduces the rail to the current actor.
     */
    public static boolean cramped(double designWidth) {
        return designWidth < CRAMPED_WIDTH;
    }

    /** {@link #cramped} for callers that only have GUI pixels and the preset. */
    public static boolean crampedFor(int guiWidth, double scale) {
        return cramped(guiWidth / effective(scale));
    }
}
