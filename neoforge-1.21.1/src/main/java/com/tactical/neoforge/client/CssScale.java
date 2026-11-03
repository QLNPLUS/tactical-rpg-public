package com.tactical.neoforge.client;

import java.util.List;
import java.util.Properties;

/** Pure CSS sizing policy. No viewport transform or Minecraft dependency. */
public final class CssScale {
    /** Eight presets, 5% apart: both ends stay readable and the tight form is the only degradation. */
    public static final List<Integer> PRESETS = List.of(80, 85, 90, 95, 100, 105, 110, 115);
    public static final int MIN_PERCENT = 80;
    public static final int MAX_PERCENT = 115;
    public static final int STEP_PERCENT = 5;
    /** Bumped to 3 with the 80-115 range; older files are migrated and written back immediately. */
    public static final String VERSION = "3";

    private CssScale() { }

    public static int clamp(int percent) { return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent)); }

    /** The preset a raw percentage maps to: clamped to the range, then rounded to the nearest step. */
    public static int snap(int percent) {
        int clamped = clamp(percent);
        int snapped = Math.round(clamped / (float) STEP_PERCENT) * STEP_PERCENT;
        return clamp(snapped);
    }

    /**
     * Reads the saved percentage, migrating every older format onto the new range: clamp to
     * 80..115 and round to the nearest step (60/70/80 -> 80, 90 -> 90, 110 -> 110, 125/140/160 ->
     * 115). Legacy step zero really was 85%, and a corrupt value falls back to 100 rather than
     * silently landing on an end of the range.
     */
    public static int readPercent(Properties properties) {
        try {
            if (VERSION.equals(properties.getProperty("version")) || properties.containsKey("percent"))
                return snap(Integer.parseInt(properties.getProperty("percent", "100")));
            return snap(switch (Integer.parseInt(properties.getProperty("step", "1"))) {
                case 0 -> 85;
                case 2 -> 125;
                default -> 100;
            });
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
        result.setProperty("percent", Integer.toString(snap(percent)));
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

    /** Width the remaining AUI pages use for the journal column, in design pixels. */
    public static final int HUD_WIDTH = 290;

    /** Metrics of the remaining AUI panels; the battle HUD itself is native and lays itself out. */
    public static JournalMetrics journal(double scale) {
        // 14px of layout borders plus scaled 13px top/bottom padding and 28px header.
        return new JournalMetrics((int) Math.ceil(14 + 54 * scale), (int) Math.ceil(2 + 28 * scale),
                (int) Math.ceil(21 * scale), Math.max(180, (int) Math.ceil(HUD_WIDTH * scale)));
    }
}
