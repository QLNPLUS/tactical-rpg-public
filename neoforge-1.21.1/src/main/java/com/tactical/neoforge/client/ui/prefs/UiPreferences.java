package com.tactical.neoforge.client.ui.prefs;

import com.tactical.neoforge.client.CssScale;

/**
 * Every saved UI preference in one immutable value, in one place.
 *
 * <p>Pure on purpose: no file, no Minecraft and no drawing reads it directly, so the whole preference
 * policy - what a missing key means, what a broken value falls back to, how a write keeps unrelated
 * keys - can be checked without a game window. {@link UiPreferencesCodec} owns the file format and
 * {@link UiPreferencesStore} the only IO; {@link com.tactical.neoforge.client.UiZoom} stays the
 * legacy facade the rest of the client already calls.</p>
 *
 * <p>The two HUDs keep their own width and height, stored at 100% design GUI pixels and multiplied by
 * the size preset when a layout runs, so a later drag changes geometry only and never the type size.
 * {@code null} means AUTO - the HUD keeps deciding that dimension itself - which is also the meaning
 * of "the user has never set it", so an untouched old file keeps today's appearance exactly.</p>
 *
 * <p>{@code revision} is an in-memory change counter, never part of the file: a layout that cached
 * the revision it was built for can notice any preference change, including a reload from disk.</p>
 */
public record UiPreferences(int percent, boolean showRangeLegend,
                            Integer rosterWidth, Integer rosterHeight,
                            Integer journalWidth, Integer journalHeight,
                            Integer themeRgb, Integer rangeRgb,
                            long revision) {

    /** Accepted range of a stored HUD dimension, in 100% design GUI pixels; outside it means AUTO. */
    public static final int MIN_SIZE = 40;
    public static final int MAX_SIZE = 400;
    /**
     * The journal stores its <em>body</em> height, not the panel's, so it has its own accepted range:
     * the approved floor is one two-line entry (18), which the generic 40 would have turned into AUTO.
     */
    public static final int MIN_BODY = 18;
    public static final int MAX_BODY = 400;
    /** What a HUD width means when the user has never dragged it, and what makes a reset invisible. */
    public static final int DEFAULT_WIDTH = com.tactical.neoforge.client.ui.layout.Scale.ROSTER_WIDTH;
    /** The journal's default body height: seven single-line entries. */
    public static final int DEFAULT_BODY = com.tactical.neoforge.client.ui.hud.HudLayout.JOURNAL_BODY_DEFAULT;

    public UiPreferences {
        percent = CssScale.snap(percent);
        rosterWidth = size(rosterWidth);
        rosterHeight = size(rosterHeight);
        journalWidth = size(journalWidth);
        journalHeight = bodyHeight(journalHeight);
        themeRgb = rgb(themeRgb);
        rangeRgb = rgb(rangeRgb);
        revision = Math.max(0L, revision);
    }

    /** A file that does not exist yet, and the fallback for a file that cannot be read at all. */
    public static UiPreferences defaults() {
        return new UiPreferences(100, false, null, null, null, null, null, null, 1L);
    }

    /** A dimension is kept only when it is a usable design-pixel size; everything else means AUTO. */
    public static Integer size(Integer value) {
        return value == null || value < MIN_SIZE || value > MAX_SIZE ? null : value;
    }

    /** The journal's body height has its own domain; outside it means AUTO, exactly like a size. */
    public static Integer bodyHeight(Integer value) {
        return value == null || value < MIN_BODY || value > MAX_BODY ? null : value;
    }

    /** Colours are stored as RGB; an out-of-range channel value is not a colour and means "unset". */
    public static Integer rgb(Integer value) {
        return value == null || value < 0 || value > 0xFFFFFF ? null : value;
    }

    public UiPreferences nextRevision() {
        return new UiPreferences(percent, showRangeLegend, rosterWidth, rosterHeight, journalWidth, journalHeight,
                themeRgb, rangeRgb, revision + 1L);
    }

    /** Carries the change counter across a re-read: the counter belongs to the session, not to the file. */
    public UiPreferences withRevision(long value) {
        return new UiPreferences(percent, showRangeLegend, rosterWidth, rosterHeight, journalWidth, journalHeight,
                themeRgb, rangeRgb, value);
    }

    public UiPreferences withPercent(int value) {
        return new UiPreferences(value, showRangeLegend, rosterWidth, rosterHeight, journalWidth, journalHeight,
                themeRgb, rangeRgb, revision + 1L);
    }

    public UiPreferences withRangeLegend(boolean value) {
        return new UiPreferences(percent, value, rosterWidth, rosterHeight, journalWidth, journalHeight,
                themeRgb, rangeRgb, revision + 1L);
    }

    /** One HUD's size; {@code null} keeps AUTO, which is how "clear my override" is expressed. */
    public UiPreferences withRosterSize(Integer width, Integer height) {
        return new UiPreferences(percent, showRangeLegend, width, height, journalWidth, journalHeight,
                themeRgb, rangeRgb, revision + 1L);
    }

    public UiPreferences withJournalSize(Integer width, Integer height) {
        return new UiPreferences(percent, showRangeLegend, rosterWidth, rosterHeight, width, height,
                themeRgb, rangeRgb, revision + 1L);
    }

    public UiPreferences withColors(Integer theme, Integer range) {
        return new UiPreferences(percent, showRangeLegend, rosterWidth, rosterHeight, journalWidth, journalHeight,
                theme, range, revision + 1L);
    }

    /** AUTO means the HUD decides this dimension itself. */
    public boolean rosterWidthAuto() {
        return rosterWidth == null;
    }

    public boolean rosterHeightAuto() {
        return rosterHeight == null;
    }

    public boolean journalWidthAuto() {
        return journalWidth == null;
    }

    public boolean journalHeightAuto() {
        return journalHeight == null;
    }

    /**
     * True when nothing is left to clear: every HUD dimension is either absent or already carries the
     * value it would have by default. That is the judgement the "reset HUD sizes" entry is disabled on,
     * and it is deliberately about the <em>effective</em> value, so dragging a width back to 140 does
     * not leave a button enabled that would change no pixel.
     *
     * <p>A saved roster height always counts as a change: its default is AUTO, i.e. "as tall as the
     * content", and whether a stored number happens to equal that today is a property of the roster,
     * not of the preference.</p>
     */
    public boolean hudSizesAreDefault() {
        return widthIsDefault(rosterWidth) && rosterHeight == null
                && widthIsDefault(journalWidth) && (journalHeight == null || journalHeight == DEFAULT_BODY);
    }

    private static boolean widthIsDefault(Integer value) {
        return value == null || value == DEFAULT_WIDTH;
    }
}
