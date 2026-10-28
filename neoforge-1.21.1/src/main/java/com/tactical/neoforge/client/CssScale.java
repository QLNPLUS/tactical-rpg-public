package com.tactical.neoforge.client;

import java.util.List;
import java.util.Properties;

/** Pure CSS sizing policy. No viewport transform or Minecraft dependency. */
public final class CssScale {
    public static final List<Integer> PRESETS = List.of(60, 70, 80, 90, 100, 110, 125, 140, 160);
    public static final int MIN_PERCENT = 60;
    public static final int MAX_PERCENT = 160;
    public static final String VERSION = "2";

    private CssScale() { }

    public static int clamp(int percent) { return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent)); }

    /** Legacy step zero really was 85%, and must not silently become 60%. */
    public static int readPercent(Properties properties) {
        try {
            if (VERSION.equals(properties.getProperty("version")) || properties.containsKey("percent"))
                return clamp(Integer.parseInt(properties.getProperty("percent", "100")));
            return switch (Integer.parseInt(properties.getProperty("step", "1"))) {
                case 0 -> 85;
                case 2 -> 125;
                default -> 100;
            };
        } catch (NumberFormatException ignored) { return 100; }
    }

    public static Properties properties(int percent) {
        return properties(new Properties(), percent, false);
    }

    /** Missing keys in pre-toggle configurations intentionally mean hidden. */
    public static boolean readRangeLegend(Properties properties) {
        return Boolean.parseBoolean(properties.getProperty("showRangeLegend", "false"));
    }

    /** Both preferences are persisted together; unrelated future keys survive saves. */
    public static Properties properties(Properties existing, int percent, boolean showRangeLegend) {
        Properties result = new Properties();
        result.putAll(existing);
        result.remove("step");
        result.setProperty("version", VERSION);
        result.setProperty("percent", Integer.toString(clamp(percent)));
        result.setProperty("showRangeLegend", Boolean.toString(showRangeLegend));
        return result;
    }

    public static int adjacent(int percent, double direction) {
        if (direction > 0) {
            for (int preset : PRESETS) if (preset > percent) return preset;
            return MAX_PERCENT;
        }
        if (direction < 0) {
            for (int i = PRESETS.size() - 1; i >= 0; i--) if (PRESETS.get(i) < percent) return PRESETS.get(i);
            return MIN_PERCENT;
        }
        return clamp(percent);
    }

    public static String px(double base, double scale) {
        return Double.toString(Math.round(base * scale * 100.0) / 100.0) + "px";
    }

    /** Metrics include border/padding, and grow with the actual rendered type. */
    public record JournalMetrics(int headerHeight, int bodyChrome, int rowHeight, int preferredWidth) { }

    public static JournalMetrics journal(double scale) {
        // 14px of layout borders plus scaled 13px top/bottom padding and 28px header.
        return new JournalMetrics((int) Math.ceil(14 + 54 * scale), (int) Math.ceil(2 + 28 * scale),
                (int) Math.ceil(21 * scale), Math.max(180, (int) Math.ceil(JournalUiLayout.HUD_WIDTH * scale)));
    }
}
