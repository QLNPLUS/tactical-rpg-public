package com.tactical.neoforge.client.ui.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development-only record of the text the client really drew, for the read-only probe.
 *
 * <p>It exists because a rectangle proves where a control is, never what it says. A driver that has to
 * check a language needs the strings the frame actually handed to the font, together with the width the
 * font measured for them; those are what {@link com.tactical.neoforge.client.ui.platform.Draw} reports
 * here. Nothing is drawn differently, nothing reads this outside the probe, and with the probe off
 * ({@code tactical.devprobe.port} unset) every call returns on its first branch.</p>
 */
public final class DrawnText {

    /** A frame draws a few dozen strings; the cap only stops a runaway loop from growing the map. */
    private static final int CAPACITY = 512;

    private static final Map<String, Entry> LAST = new LinkedHashMap<>();

    private DrawnText() {
    }

    /** One string as it was handed to the font: the measured width is the width the layout used. */
    public record Entry(String text, int width, int x, int y) {
    }

    /**
     * Called by the drawing layer for every string it paints.
     *
     * <p>The probe flag is checked <em>before</em> anything is measured, so a release build pays one
     * volatile read per drawn string and never a {@code font.width} it would throw away.</p>
     */
    public static void record(String text, net.minecraft.client.gui.Font font, int x, int y) {
        if (!DevProbe.enabled() || text == null || text.isEmpty() || font == null) return;
        int width = font.width(text);
        synchronized (LAST) {
            if (LAST.size() >= CAPACITY && !LAST.containsKey(text)) return;
            LAST.putIfAbsent(text, new Entry(text, width, x, y));
        }
    }

    /** Kept for a caller that already knows the width (a scaled draw measures it itself). */
    public static void recordMeasured(String text, int width, int x, int y) {
        if (!DevProbe.enabled() || text == null || text.isEmpty()) return;
        synchronized (LAST) {
            if (LAST.size() >= CAPACITY && !LAST.containsKey(text)) return;
            LAST.putIfAbsent(text, new Entry(text, width, x, y));
        }
    }

    /** Discards what was collected so far: a driver clears this right after changing pages. */
    public static void clear() {
        synchronized (LAST) {
            LAST.clear();
        }
    }

    /**
     * The strings drawn since the previous drain, as a JSON array. Draining keeps the report the size
     * of one screen's worth of text instead of the whole session's history.
     */
    public static String drainJson() {
        StringBuilder builder = new StringBuilder("[");
        synchronized (LAST) {
            boolean first = true;
            for (Entry entry : LAST.values()) {
                if (!first) builder.append(',');
                first = false;
                builder.append("{\"text\":\"").append(escape(entry.text()))
                        .append("\",\"w\":").append(entry.width())
                        .append(",\"x\":").append(entry.x())
                        .append(",\"y\":").append(entry.y()).append('}');
            }
            LAST.clear();
        }
        return builder.append(']').toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.toString();
    }
}
