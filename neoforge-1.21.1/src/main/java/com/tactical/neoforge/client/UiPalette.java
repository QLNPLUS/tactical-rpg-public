package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.theme.Palette;
import com.tactical.neoforge.client.ui.theme.Slate;

/**
 * The applied colour palette: the one place drawing code asks for the accent and the range colour.
 *
 * <p>It is a thin adapter over {@link UiZoom}: the saved preferences are the only source, so a colour
 * that was saved is the colour the next frame uses, in the HUD and in the world at once, without any
 * cache to keep in step. Everything derived - the dark and light accents, the four rule segments, the
 * range outline - comes from {@link Palette}, which is pure and unit-tested; semantic colours never
 * pass through here.</p>
 */
public final class UiPalette {

    private UiPalette() {
    }

    /** The applied theme as RGB, i.e. what is saved, not what a picker is previewing. */
    public static int theme() {
        return Palette.rgb(UiZoom.themeRgb(), Palette.DEFAULT_THEME);
    }

    public static int range() {
        return Palette.rgb(UiZoom.rangeRgb(), Palette.DEFAULT_RANGE);
    }

    public static int accent() {
        return Palette.accent(theme());
    }

    public static int accentDark() {
        return Palette.accentDark(theme());
    }

    public static int accentLight() {
        return Palette.accentLight(theme());
    }

    /** Accent rule segment {@code index}; the fourth is alpha 0, i.e. not drawn. */
    public static int rule(int index) {
        return Palette.ruleSegment(theme(), index);
    }

    public static int rangeFill() {
        return Palette.rangeFill(range());
    }

    public static int rangeOutline() {
        return Palette.rangeOutline(range());
    }

    /** Text colour, exposed so callers mix against the same value the palette used. */
    public static int text() {
        return Slate.TEXT;
    }
}
