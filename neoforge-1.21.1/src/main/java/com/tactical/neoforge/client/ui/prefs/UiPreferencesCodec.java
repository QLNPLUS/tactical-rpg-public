package com.tactical.neoforge.client.ui.prefs;

import com.tactical.neoforge.client.CssScale;

import java.util.Locale;
import java.util.Properties;

/**
 * The one place that turns the preference file into {@link UiPreferences} and back.
 *
 * <p>It reuses the size policy's own reader and writer instead of restating that migration: an old
 * {@code step}/{@code version} file still lands on a preset, a corrupt percentage still falls back to
 * 100 rather than to an end of the range, and every unknown key survives a save. The HUD dimensions
 * and the two colours are additive keys with an "absent" meaning - AUTO and "the palette decides" -
 * so a file written by an older build keeps exactly today's appearance.</p>
 */
public final class UiPreferencesCodec {

    /** Two HUD sizes, stored at 100% design GUI pixels; absent means the HUD sizes itself. */
    public static final String ROSTER_WIDTH = "hudRosterWidth";
    public static final String ROSTER_HEIGHT = "hudRosterHeight";
    public static final String JOURNAL_WIDTH = "hudJournalWidth";
    public static final String JOURNAL_HEIGHT = "hudJournalHeight";
    /** Theme and range colours as RGB; absent means the confirmed palette token is used. */
    public static final String THEME_RGB = "themeRgb";
    public static final String RANGE_RGB = "rangeRgb";

    private UiPreferencesCodec() {
    }

    /** Reads every preference; a missing or broken field degrades to its own default, nothing else. */
    public static UiPreferences read(Properties properties) {
        Properties source = properties == null ? new Properties() : properties;
        return new UiPreferences(CssScale.readPercent(source), CssScale.readRangeLegend(source),
                readSize(source, ROSTER_WIDTH), readSize(source, ROSTER_HEIGHT),
                readSize(source, JOURNAL_WIDTH), readBody(source, JOURNAL_HEIGHT),
                readRgb(source, THEME_RGB), readRgb(source, RANGE_RGB), 0L);
    }

    /** Writes the whole preference set, keeping keys this build does not know about. */
    public static Properties write(Properties existing, UiPreferences preferences) {
        UiPreferences prefs = preferences == null ? UiPreferences.defaults() : preferences;
        Properties result = CssScale.properties(existing == null ? new Properties() : existing,
                prefs.percent(), prefs.showRangeLegend());
        putSize(result, ROSTER_WIDTH, prefs.rosterWidth());
        putSize(result, ROSTER_HEIGHT, prefs.rosterHeight());
        putSize(result, JOURNAL_WIDTH, prefs.journalWidth());
        putBody(result, JOURNAL_HEIGHT, prefs.journalHeight());
        putRgb(result, THEME_RGB, prefs.themeRgb());
        putRgb(result, RANGE_RGB, prefs.rangeRgb());
        return result;
    }

    private static void putSize(Properties result, String key, Integer value) {
        if (value == null) result.remove(key);
        else result.setProperty(key, Integer.toString(value));
    }

    /** The journal's own value domain: the body reaches down to one two-line entry (18). */
    private static void putBody(Properties result, String key, Integer value) {
        putSize(result, key, value);
    }

    private static void putRgb(Properties result, String key, Integer value) {
        if (value == null) result.remove(key);
        else result.setProperty(key, String.format(Locale.ROOT, "%06X", value));
    }

    /** A dimension is a plain integer inside {@link UiPreferences#MIN_SIZE}..{@link UiPreferences#MAX_SIZE}. */
    public static Integer readSize(Properties properties, String key) {
        return readNumber(properties, key, UiPreferences::size);
    }

    /**
     * The journal's body height, inside {@link UiPreferences#MIN_BODY}..{@link UiPreferences#MAX_BODY}.
     *
     * <p>It is a separate reader on purpose: one accepted range for every key would either refuse the
     * approved 18 body floor or silently accept a 20-pixel panel width that no label fits in.</p>
     */
    public static Integer readBody(Properties properties, String key) {
        return readNumber(properties, key, UiPreferences::bodyHeight);
    }

    private static Integer readNumber(Properties properties, String key, java.util.function.IntFunction<Integer> domain) {
        String raw = properties.getProperty(key);
        if (raw == null) return null;
        try {
            return domain.apply(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Integer readRgb(Properties properties, String key) {
        return parseRgb(properties.getProperty(key));
    }

    /**
     * Accepts {@code RRGGBB}, {@code #RRGGBB}, {@code 0xRRGGBB} and an {@code AARRGGBB} with alpha.
     *
     * <p>Every digit is validated <em>before</em> the alpha pair is dropped: stripping first would turn
     * {@code GG112233} or {@code --112233} into a perfectly valid {@code 112233}, which is exactly the
     * "a broken field must fall back" rule this codec exists for. The alpha value itself is only
     * tolerated, never interpreted - the settings UI of a later batch writes six hex digits only.</p>
     */
    public static Integer parseRgb(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("#")) text = text.substring(1);
        else if (text.length() > 1 && (text.startsWith("0x") || text.startsWith("0X"))) text = text.substring(2);
        if (!isHex(text)) return null;
        if (text.length() == 8) text = text.substring(2);
        if (text.length() != 6) return null;
        return UiPreferences.rgb(Integer.parseInt(text, 16));
    }

    private static boolean isHex(String text) {
        if (text.isEmpty()) return false;
        for (int index = 0; index < text.length(); index++) {
            if (Character.digit(text.charAt(index), 16) < 0) return false;
        }
        return true;
    }

    /** How a colour is stored, for the settings controls a later batch adds. */
    public static String formatRgb(Integer value) {
        return value == null ? "" : String.format(Locale.ROOT, "%06X", value);
    }
}
