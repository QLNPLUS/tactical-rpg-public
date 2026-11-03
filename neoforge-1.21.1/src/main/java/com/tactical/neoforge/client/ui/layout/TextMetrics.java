package com.tactical.neoforge.client.ui.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Shared GUI-pixel measurement, truncation and wrapping for scaled text. */
public final class TextMetrics {
    private TextMetrics() { }

    /** The whole text of a row value, the text actually drawn and whether they differ. */
    public record Fitted(String full, String shown, boolean truncated) {
    }

    /**
     * A row value placed right of a label: the leftover width is the row minus the label and the
     * gap. A value that does not fit is cut with an ellipsis; the whole text stays available so the
     * screen can show it in a tooltip instead of silently hiding the ceiling.
     */
    public static Fitted fitAfterLabel(String full, int rowWidth, int labelWidth, int gap, double scale,
                                       ToIntFunction<String> measure) {
        String value = full == null ? "" : full;
        int budget = Math.max(1, rowWidth - labelWidth - gap);
        String shown = ellipsis(value, budget, scale, measure);
        return new Fitted(value, shown, !shown.equals(value));
    }

    /**
     * Text of a one-glyph icon button. Such a button carries a sign, not prose: cutting it would
     * turn "-" into an ellipsis, so it is drawn as it is. Every other label folds to its width.
     */
    public static String iconOrEllipsis(String label, int maxWidth, double scale, ToIntFunction<String> measure) {
        if (label != null && label.codePointCount(0, label.length()) == 1) return label;
        return ellipsis(label, maxWidth, scale, measure);
    }

    /**
     * Two-part text in one rectangle where the tail may never be cut - a hint with its own cost, a
     * label with its own number. The head is shortened until {@code head + tail} fits, so the number and
     * the unit survive and only the wording gives way; when even the tail alone does not fit, the tail is
     * returned on its own because dropping it would hide the value the row exists for.
     */
    public static String ellipsisKeepingTail(String head, String tail, int maxWidth, double scale,
                                             ToIntFunction<String> measure) {
        String lead = head == null ? "" : head;
        String keep = tail == null ? "" : tail;
        if (maxWidth <= 0) return "";
        int tailWidth = width(measure.applyAsInt(keep), scale);
        if (tailWidth >= maxWidth) return keep;
        int budget = maxWidth - tailWidth;
        String shownLead = ellipsis(lead, budget, scale, measure);
        return shownLead + keep;
    }

    public static int width(int rawWidth, double scale) {
        return (int) Math.ceil(rawWidth * scale);
    }
    public static String ellipsis(String value, int maxWidth, double scale, ToIntFunction<String> measure) {
        if (value == null || value.isEmpty() || maxWidth <= 0) return "";
        if (width(measure.applyAsInt(value), scale) <= maxWidth) return value;
        String suffix = "…";
        if (width(measure.applyAsInt(suffix), scale) > maxWidth) return "";
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            String next = prefix.toString() + new String(Character.toChars(cp));
            if (width(measure.applyAsInt(next + suffix), scale) > maxWidth) break;
            prefix.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return prefix + suffix;
    }

    /**
     * Folds text into lines no wider than {@code maxWidth} at the given scale.
     *
     * <p>Unlike {@link #ellipsis} this never loses characters, which is what a tooltip needs: the
     * whole value has to stay readable. Explicit line breaks are honoured; within a paragraph the
     * break falls on the last space of the line when there is one, so Latin words stay whole, and
     * falls between characters otherwise, which is what a Chinese sentence requires. A single
     * character wider than the whole line still gets its own line, so the loop always advances.</p>
     */
    public static List<String> wrap(String value, int maxWidth, double scale, ToIntFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isEmpty()) return lines;
        for (String paragraph : value.split("\n", -1)) {
            wrapParagraph(paragraph, maxWidth, scale, measure, lines);
        }
        return lines;
    }

    private static void wrapParagraph(String text, int maxWidth, double scale, ToIntFunction<String> measure,
                                      List<String> lines) {
        if (text.isEmpty() || maxWidth <= 0) {
            lines.add("");
            return;
        }
        int start = 0;
        while (start < text.length()) {
            int end = start;
            int lastSpace = -1;
            int cursor = start;
            while (cursor < text.length()) {
                int cp = text.codePointAt(cursor);
                int next = cursor + Character.charCount(cp);
                if (width(measure.applyAsInt(text.substring(start, next)), scale) > maxWidth) break;
                if (cp == ' ') lastSpace = cursor;
                end = next;
                cursor = next;
            }
            if (end == start) {
                end = start + Character.charCount(text.codePointAt(start));
            } else if (end < text.length() && lastSpace > start) {
                end = lastSpace;
            }
            lines.add(text.substring(start, end).strip());
            start = end;
            while (start < text.length() && text.charAt(start) == ' ') start++;
        }
    }
}
