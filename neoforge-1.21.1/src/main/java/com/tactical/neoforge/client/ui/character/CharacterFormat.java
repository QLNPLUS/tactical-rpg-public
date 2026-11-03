package com.tactical.neoforge.client.ui.character;

import java.util.Locale;

/**
 * Every number the character page shows, formatted once so a value never changes shape between the
 * current column, the draft column and a tooltip.
 *
 * <p>The specification fixes the units: AP is a whole number, HP / MP / attack damage are two decimals, the
 * turn charge and the skill multiplier are two decimals with a {@code ×} prefix, and anything the server did
 * not actually send is an em dash - never a zero that would read as a real balance.</p>
 */
public final class CharacterFormat {

    /** Drawn when a number is not known: a placeholder must never look like a value. */
    public static final String UNKNOWN = "—";

    private CharacterFormat() { }

    public static String ap(double value) {
        return Double.isFinite(value) ? Long.toString(Math.round(value)) : UNKNOWN;
    }

    public static String ap(long value) { return Long.toString(value); }

    public static String amount(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f", value) : UNKNOWN;
    }

    /**
     * One rule coefficient, e.g. {@code 2}, {@code 0.5} or {@code 0.01}.
     *
     * <p>Trailing zeros are dropped because a coefficient is a rule number, not a measured value: a tooltip
     * that says "2.00 hit points per point" reads like a stat readout. Values are still capped at two
     * decimals, the same precision the page uses everywhere.</p>
     */
    public static String coefficient(double value) {
        if (!Double.isFinite(value)) return UNKNOWN;
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "");
            if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    public static String multiplier(double value) {
        return Double.isFinite(value) ? "×" + String.format(Locale.ROOT, "%.2f", value) : UNKNOWN;
    }

    public static String integer(long value) { return Long.toString(value); }

    /** {@code n} XP, with the unit the specification uses for a price. */
    public static String xp(long value) { return value + " XP"; }

    /** A signed point chip, e.g. {@code +2}; zero has no chip at all. */
    public static String chip(int value) { return value > 0 ? "+" + value : ""; }

    /** {@code Lv 3 / 100}. */
    public static String level(int current, int cap) { return "Lv " + current + " / " + cap; }

    /** The level-up result of the confirmation popup: {@code Lv 3 → Lv 4}. */
    public static String levelStep(int current) { return "Lv " + current + " → Lv " + (current + 1); }

    /** Truncation is the screen's job; this only says which marker the specification uses. */
    public static String ellipsis() { return "…"; }
}
