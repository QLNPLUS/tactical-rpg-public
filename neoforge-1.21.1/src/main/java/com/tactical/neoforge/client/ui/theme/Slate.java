package com.tactical.neoforge.client.ui.theme;

/**
 * Slate colour tokens (B 方案, ARGB).
 *
 * <p>Every value is a literal here on purpose: rendering code must never invent a colour, and a
 * re-theme is a single-file change. Values come from the approved visual specification; do not
 * tune them locally.</p>
 */
public final class Slate {

    /** Panel base, used only for panels that sit straight on the battlefield. The one translucent face. */
    public static final int SLAB = 0xE61E1D1C;
    /**
     * Floating surface: same colour as {@link #SLAB} but fully opaque. Everything that can come to
     * rest on top of another panel uses it - the gear popover, tooltips, the range legend, an expanded
     * journal in the narrow form, and every future dropdown or dialog - so nothing below shows through.
     */
    public static final int FLOAT = 0xFF1E1D1C;
    /** Rows and buttons, resting state. */
    public static final int RAISE = 0xFF2A2927;
    /** Rows and buttons, hovered. */
    public static final int HOVER = 0xFF3A3834;
    /** Grooves, tracks, pressed state, target wells. */
    public static final int SUNK = 0xFF161514;
    /** Separators and portrait frames. */
    public static final int LINE = 0xFF3A3835;

    public static final int TEXT = 0xFFECEAE4;
    /** Secondary text: descriptions, secondary numbers. */
    public static final int TEXT2 = 0xFFA9A69D;
    /** Disabled, footers, empty states. */
    public static final int TEXT3 = 0xFF7C796F;

    /** Vermilion accent: leading rule segment, current actor frame, focus ring, hover bar. */
    public static final int ACCENT = 0xFFC4473D;
    /** Segments 2 and 3 of the accent rule. The fourth quarter is not drawn at all. */
    public static final int ACCENT_55 = 0x8CC4473D;
    public static final int ACCENT_25 = 0x40C4473D;
    /** Selected/hovered icon boxes, "attacking"/"acting" chips. */
    public static final int ACCENT_D = 0xFF6E2622;

    public static final int AP = 0xFFE5BC57;
    /** The cost currently being spent, drawn inside the AP bar. */
    public static final int AP_PENDING = 0x73E5BC57;
    public static final int HP = 0xFFE05A5A;
    public static final int MP = 0xFF6AA8E8;
    public static final int ALLY = 0xFF70BD90;
    public static final int ENEMY = 0xFFE87878;

    /** Panel shadow. */
    public static final int SHADOW = 0x73000000;
    /** Tooltip and popover shadow. */
    public static final int SHADOW_SM = 0x8C000000;

    private Slate() {
    }
}
