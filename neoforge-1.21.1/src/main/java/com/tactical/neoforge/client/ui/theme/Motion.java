package com.tactical.neoforge.client.ui.theme;

/** Spacing steps, line metrics and animation durations. Pure values, no Minecraft types. */
public final class Motion {

    /** Base grid step in GUI pixels at 100%. */
    public static final int STEP = 2;
    /** Panel padding and panel-to-screen distance at 100%; the screen edge stays fixed when scaling. */
    public static final int PAD = 6;
    /** Row-to-row gap inside one group. */
    public static final int ROW_GAP = 2;
    /** The narrow panel packs its rows one pixel apart. */
    public static final int ROW_GAP_NARROW = 1;
    /** Group gap: 4 above a separator line, 5 below it. */
    public static final int GROUP_GAP_ABOVE = 4;
    public static final int GROUP_GAP_BELOW = 5;
    /** One text line of the vanilla font: 8px glyph plus 1px leading. */
    public static final int LINE = 9;
    /** Two-times text line, only for the panel title and the current AP value. */
    public static final int LINE_2X = 18;
    /** Icon and portrait boxes. */
    public static final int ICON = 16;
    public static final int ICON_BOX_INSET = 3;
    public static final int ICON_TEXT_LEFT = 23;
    /** The narrow row tucks its icon one pixel from the top and two from the left. */
    public static final int ICON_BOX_INSET_NARROW = 1;
    public static final int ICON_LEFT_NARROW = 2;
    public static final int ICON_TEXT_LEFT_NARROW = 22;
    public static final int GEAR = 11;
    public static final int GEAR_ICON = 9;

    public static final int ROW_HEIGHT = 22;
    public static final int ROW_HEIGHT_NARROW = 18;
    public static final int BUTTON_HEIGHT = 14;
    public static final int BUTTON_PAD_X = 5;
    public static final int CHIP_HEIGHT = 9;
    public static final int CHIP_PAD_X = 2;
    public static final int BAR_HEIGHT = 4;
    /** HP and MP tracks are thin, both in the roster and in the narrow panel. */
    public static final int BAR_HEIGHT_THIN = 2;
    public static final int ROSTER_BAR_HEIGHT = 2;
    public static final int PANEL_WIDTH = 162;
    public static final int PANEL_WIDTH_NARROW = 112;
    public static final int COLUMN_GAP = 10;

    public static final int HOVER_MS = 140;
    public static final int PRESS_MS = 90;
    public static final int FOCUS_MS = 160;
    public static final int PANEL_ENTER_MS = 220;
    public static final int PANEL_SWAP_MS = 140;
    public static final int ROW_STAGGER_MS = 18;
    public static final int ROW_STAGGER_MAX_MS = 200;
    public static final int TOOLTIP_DELAY_MS = 350;
    public static final int TOOLTIP_FADE_MS = 90;

    private Motion() {
    }

    /** easeOutCubic. Durations above are the only animation shapes this UI uses. */
    public static double easeOutCubic(double t) {
        double clamped = Math.max(0.0D, Math.min(1.0D, t));
        double inverse = 1.0D - clamped;
        return 1.0D - inverse * inverse * inverse;
    }

    /** Linear progress of {@code elapsedMs} through {@code durationMs}, clamped to [0,1]. */
    public static double progress(long elapsedMs, int durationMs) {
        if (durationMs <= 0) return 1.0D;
        return Math.max(0.0D, Math.min(1.0D, elapsedMs / (double) durationMs));
    }

    /** Linear per-channel interpolation, including alpha. */
    public static int lerpColor(int from, int to, double t) {
        double clamped = Math.max(0.0D, Math.min(1.0D, t));
        int a = lerpChannel(from >>> 24, to >>> 24, clamped);
        int r = lerpChannel(from >> 16 & 0xFF, to >> 16 & 0xFF, clamped);
        int g = lerpChannel(from >> 8 & 0xFF, to >> 8 & 0xFF, clamped);
        int b = lerpChannel(from & 0xFF, to & 0xFF, clamped);
        return a << 24 | r << 16 | g << 8 | b;
    }

    /** Multiplies a colour's alpha; used for whole-panel entrance fades. */
    public static int withAlpha(int color, double alpha) {
        int a = (int) Math.round((color >>> 24) * Math.max(0.0D, Math.min(1.0D, alpha)));
        return a << 24 | color & 0xFFFFFF;
    }

    private static int lerpChannel(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }
}
