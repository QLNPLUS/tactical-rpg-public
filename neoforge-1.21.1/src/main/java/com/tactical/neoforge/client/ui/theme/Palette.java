package com.tactical.neoforge.client.ui.theme;

import java.util.List;

/**
 * The derivations of the two user colours, and the fixed palette they may never touch.
 *
 * <p>Pure on purpose: the interface accent, its darker and lighter forms, the four accent-rule
 * segments, and the range fill and outline are all computed here, so a unit test can pin both the
 * derivations and the promise that no semantic token or alpha value moves when the user picks a
 * colour. Drawing code reads the result and never invents a colour itself.</p>
 *
 * <p>Only RGB is user-controlled. Alpha is a property of the visual specification: the accent rule
 * keeps 8C / 40 on its middle segments, the range quad keeps 0.27 and its outline keeps 0.8, and the
 * panel, text, HP, MP, AP, ally and enemy tokens do not change at all.</p>
 */
public final class Palette {

    /** Default theme: the vermilion that was the fixed accent before it became a preference. */
    public static final int DEFAULT_THEME = 0xC4473D;
    /** Default range colour: the orange the attack overlay always drew. */
    public static final int DEFAULT_RANGE = 0xFF781F;

    /** Eight theme presets, all dark-background legible and hue-separated from the semantic colours. */
    public static final List<Integer> THEME_PRESETS = List.of(
            0xC4473D, 0xC7743A, 0x7E9A4A, 0x3E9A8C, 0x5468B8, 0x8A5BB0, 0xB84A7A, 0x8C8A84);
    /** Eight range presets, the first one being the previous fixed orange. */
    public static final List<Integer> RANGE_PRESETS = List.of(
            0xFF781F, 0xE8A33A, 0xE0D04A, 0x3EC4C0, 0x4A9EE8, 0xA070E0, 0xE070B0, 0xE8E6E0);

    /** The mid accent rule segments' alphas, unchanged by the theme, and the leading one's. */
    public static final int RULE_LEADING_ALPHA = 0xFF;
    public static final int RULE_SECOND_ALPHA = 0x8C;
    public static final int RULE_THIRD_ALPHA = 0x40;
    /** How far the dark accent moves each channel (C4473D -> 6E2622). */
    public static final double DARK_FACTOR = 0.56D;
    /** How far the light accent moves towards the text colour. */
    public static final double LIGHT_MIX = 0.35D;
    /** How far the range outline moves towards white (FF781F -> close to the old 1.0/0.52/0.16). */
    public static final double RANGE_OUTLINE_MIX = 0.08D;

    private Palette() {
    }

    /** A stored colour is only a colour when it is an opaque-candidate RGB value. */
    public static int rgb(Integer value, int fallback) {
        return value == null || value < 0 || value > 0xFFFFFF ? fallback : value;
    }

    public static int accent(int themeRgb) {
        return 0xFF000000 | (themeRgb & 0xFFFFFF);
    }

    /** Channel of the theme scaled by {@link #DARK_FACTOR}; used for selected and acting surfaces. */
    public static int accentDark(int themeRgb) {
        // The default theme keeps its approved literal: the original vermilion's dark form is
        // 6E2622, which is the 0.56 ratio rounded by hand, not by this formula. Every other theme
        // follows the formula so the ratio is the rule rather than a table.
        if ((themeRgb & 0xFFFFFF) == DEFAULT_THEME) return 0xFF6E2622;
        return 0xFF000000 | scale(themeRgb, DARK_FACTOR);
    }

    /** The theme mixed towards the text colour; used for the drag handle and picker selection ring. */
    public static int accentLight(int themeRgb) {
        return 0xFF000000 | mix(themeRgb, Slate.TEXT & 0xFFFFFF, LIGHT_MIX);
    }

    /** One of the accent rule's four segments: leading, 8C, 40, then not drawn at all (alpha 0). */
    public static int ruleSegment(int themeRgb, int index) {
        int alpha = switch (index) {
            case 0 -> RULE_LEADING_ALPHA;
            case 1 -> RULE_SECOND_ALPHA;
            case 2 -> RULE_THIRD_ALPHA;
            default -> 0;
        };
        return alpha << 24 | (themeRgb & 0xFFFFFF);
    }

    /** The attack range fill: the user colour, with its alpha supplied by the renderer. */
    public static int rangeFill(int rangeRgb) {
        return 0xFF000000 | (rangeRgb & 0xFFFFFF);
    }

    /** The attack range outline: the user colour moved a little towards white. */
    public static int rangeOutline(int rangeRgb) {
        return 0xFF000000 | mix(rangeRgb, 0xFFFFFF, RANGE_OUTLINE_MIX);
    }

    public static float red(int rgb) {
        return (rgb >> 16 & 0xFF) / 255.0F;
    }

    public static float green(int rgb) {
        return (rgb >> 8 & 0xFF) / 255.0F;
    }

    public static float blue(int rgb) {
        return (rgb & 0xFF) / 255.0F;
    }

    /** True when the value is a legal six-digit colour: the only thing a HEX field may save. */
    public static boolean validHex(String text) {
        if (text == null || text.length() != 6) return false;
        for (int index = 0; index < 6; index++) {
            if (Character.digit(text.charAt(index), 16) < 0) return false;
        }
        return true;
    }

    public static int parseHex(String text) {
        return validHex(text) ? Integer.parseInt(text, 16) : -1;
    }

    public static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    /** HSV (hue 0..360, saturation and value 0..1) to an RGB colour. */
    public static int fromHsv(double hue, double saturation, double value) {
        double h = ((hue % 360.0D) + 360.0D) % 360.0D;
        double s = clamp01(saturation);
        double v = clamp01(value);
        int sector = (int) Math.floor(h / 60.0D) % 6;
        double fraction = h / 60.0D - Math.floor(h / 60.0D);
        double p = v * (1.0D - s);
        double q = v * (1.0D - s * fraction);
        double t = v * (1.0D - s * (1.0D - fraction));
        double r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    /** RGB to hue in degrees, saturation and value in 0..1. A grey reports hue 0. */
    public static double[] toHsv(int rgb) {
        double r = (rgb >> 16 & 0xFF) / 255.0D;
        double g = (rgb >> 8 & 0xFF) / 255.0D;
        double b = (rgb & 0xFF) / 255.0D;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double hue;
        if (delta < 1.0E-9D) hue = 0.0D;
        else if (max == r) hue = 60.0D * (((g - b) / delta) % 6.0D);
        else if (max == g) hue = 60.0D * ((b - r) / delta + 2.0D);
        else hue = 60.0D * ((r - g) / delta + 4.0D);
        if (hue < 0.0D) hue += 360.0D;
        return new double[]{hue, max <= 1.0E-9D ? 0.0D : delta / max, max};
    }

    private static int scale(int rgb, double factor) {
        int r = channel(((rgb >> 16 & 0xFF) / 255.0D) * factor);
        int g = channel(((rgb >> 8 & 0xFF) / 255.0D) * factor);
        int b = channel(((rgb & 0xFF) / 255.0D) * factor);
        return r << 16 | g << 8 | b;
    }

    private static int mix(int from, int to, double t) {
        double clamped = clamp01(t);
        int r = channel(lerp(from >> 16 & 0xFF, to >> 16 & 0xFF, clamped) / 255.0D);
        int g = channel(lerp(from >> 8 & 0xFF, to >> 8 & 0xFF, clamped) / 255.0D);
        int b = channel(lerp(from & 0xFF, to & 0xFF, clamped) / 255.0D);
        return r << 16 | g << 8 | b;
    }

    private static double lerp(int from, int to, double t) {
        return from + (to - from) * t;
    }

    private static int channel(double value) {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, value)) * 255.0D);
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
