package com.tactical.neoforge.client.ui.layout;

import java.util.function.ToIntFunction;

/**
 * Composition of a page's passive tooltip text, and the price rule that goes with it.
 *
 * <p>The shared panel hands only the first two strings to the tooltip widget and folds each of them
 * itself, honouring explicit line breaks. So a page that has more than one thing to explain builds them
 * here: one primary line group, one secondary group whose fields are separated by {@code \n}. Every
 * function is pure, so "all of it is in the tooltip" and "the price keeps its unit" are checkable
 * without a game window.</p>
 */
public final class TooltipText {

    private TooltipText() {
    }

    /** Joins what still has to be readable, one field per line, dropping the empty ones. */
    public static String join(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!joined.isEmpty()) joined.append('\n');
            joined.append(part.strip());
        }
        return joined.toString();
    }

    /**
     * The two segments the shared tooltip accepts: a primary line and one secondary group whose
     * fields are already separated by line breaks.
     */
    public static String[] row(String primary, String... secondary) {
        return new String[]{primary == null ? "" : primary, join(secondary)};
    }

    /** What a row can show of its price and the suffix after it, and whether the suffix had to go. */
    public record PriceShown(String text, boolean suffixDropped) {
    }

    /**
     * A price that must stay whole, followed by a suffix that may give way.
     *
     * <p>The price is the number the row exists for: it is never shortened to make room for the suffix,
     * so {@code 15 AP · 10 MP} keeps both units even when the cooldown line does not fit beside it. What
     * does not fit is dropped here and reaches the reader through the row's tooltip instead.</p>
     */
    public static PriceShown priceWithSuffix(String price, String suffix, int maxWidth, double scale,
                                             ToIntFunction<String> measure) {
        String value = price == null ? "" : price;
        String tail = suffix == null ? "" : suffix;
        if (maxWidth <= 0) return new PriceShown("", !tail.isBlank());
        if (width(value, scale, measure) > maxWidth) {
            return new PriceShown(TextMetrics.ellipsis(value, maxWidth, scale, measure), !tail.isBlank());
        }
        if (tail.isBlank()) return new PriceShown(value, false);
        if (width(value + tail, scale, measure) <= maxWidth) return new PriceShown(value + tail, false);
        return new PriceShown(value, true);
    }

    public static int width(String text, double scale, ToIntFunction<String> measure) {
        return TextMetrics.width(measure.applyAsInt(text == null ? "" : text), scale);
    }

    /**
     * The visible row a hover id names, or {@code -1} when the id is not one of this page's rows.
     *
     * <p>Rows are named {@code <prefix><index>}; a control that merely lives inside a row keeps its own
     * longer id and is rejected here, so a tooltip can never answer for the wrong row - nor for the page
     * footer.</p>
     */
    public static int rowIndex(String id, String prefix, int rowCount) {
        if (id == null || prefix == null || !id.startsWith(prefix) || id.length() == prefix.length()) return -1;
        try {
            int index = Integer.parseInt(id.substring(prefix.length()));
            return index >= 0 && index < rowCount ? index : -1;
        } catch (RuntimeException notANumber) {
            return -1;
        }
    }
}
