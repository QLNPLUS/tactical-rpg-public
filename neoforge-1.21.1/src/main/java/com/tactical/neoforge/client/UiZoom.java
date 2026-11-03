package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.ui.prefs.UiPreferences;
import com.tactical.neoforge.client.ui.prefs.UiPreferencesStore;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Explicit interface sizes, with direct pixel fonts and unchanged input coordinates.
 *
 * <p>Size presets intentionally reflow the layout instead of introducing a second viewport
 * transform. Buttons, slots and geometry stay in one coordinate space.</p>
 *
 * <p>The saved values live in one place - {@link UiPreferences} through {@link UiPreferencesStore} -
 * and this class is only the facade the rest of the client already calls. It also exposes the HUD
 * sizes and the two colours the gear edits; setting them updates memory and bumps the revision, and
 * only {@link #commit()} writes the file, so a drag can change geometry every frame while the disk
 * sees one write when the pointer is released.</p>
 *
 * <p>Every screen is drawn by this mod's own native toolkit, so this class changes no document and
 * keeps no per-page state: a preference change reaches the screens through {@link #revision()} and
 * {@link #zoom()} alone.</p>
 */
public final class UiZoom {
    public static final java.util.List<Integer> PRESETS = CssScale.PRESETS;
    public static final int MIN_PERCENT = CssScale.MIN_PERCENT;
    public static final int MAX_PERCENT = CssScale.MAX_PERCENT;
    private static volatile UiPreferencesStore store;

    private UiZoom() {}

    private static UiPreferencesStore store() {
        UiPreferencesStore current = store;
        if (current == null) {
            synchronized (UiZoom.class) {
                current = store;
                if (current == null) {
                    current = new UiPreferencesStore(FMLPaths.CONFIGDIR.get().resolve(UiPreferencesStore.FILE_NAME));
                    store = current;
                }
            }
        }
        return current;
    }

    /** Every saved preference, as one immutable snapshot. */
    public static UiPreferences preferences() { return store().current(); }

    /** Bumped by every preference change, including a reload from disk; layouts cache it. */
    public static long revision() { return preferences().revision(); }

    public static double zoom() { return percent() / 100.0D; }

    /** The preset as a percentage; the native toolkit draws size percentages with it. */
    public static int percent() { return preferences().percent(); }
    public static boolean showRangeLegend() { return preferences().showRangeLegend(); }

    // ------------------------------------------------------------ HUD geometry and colours

    /** Saved roster width in 100% design GUI pixels, or {@code null} for AUTO. */
    public static Integer rosterWidth() { return preferences().rosterWidth(); }
    public static Integer rosterHeight() { return preferences().rosterHeight(); }
    public static Integer journalWidth() { return preferences().journalWidth(); }
    public static Integer journalHeight() { return preferences().journalHeight(); }
    /** Theme and range colours as RGB, or {@code null} while the confirmed palette decides. */
    public static Integer themeRgb() { return preferences().themeRgb(); }
    public static Integer rangeRgb() { return preferences().rangeRgb(); }

    /** In memory only: callers commit once when the drag ends or the value is confirmed. */
    public static void setRosterSize(Integer width, Integer height) {
        store().update(prefs -> prefs.withRosterSize(width, height));
    }

    public static void setJournalSize(Integer width, Integer height) {
        store().update(prefs -> prefs.withJournalSize(width, height));
    }

    /**
     * "Reset HUD sizes": clears both HUDs' geometry overrides - width back to the 140 default, the
     * roster height back to AUTO and the journal body back to its 75 default - and touches nothing
     * else: not the type-size preset, not the two colours, not the journal's open state.
     *
     * @return false when every dimension was already at its default, so nothing was written
     */
    public static boolean resetHudSizes() {
        if (hudSizesAreDefault()) return false;
        store().update(prefs -> prefs.withRosterSize(null, null).withJournalSize(null, null));
        commit();
        return true;
    }

    /** True when the "reset HUD sizes" entry would change no pixel, which is when it is disabled. */
    public static boolean hudSizesAreDefault() {
        return preferences().hudSizesAreDefault();
    }

    public static void setColors(Integer theme, Integer range) {
        store().update(prefs -> prefs.withColors(theme, range));
    }

    /** Writes the current preferences; the only path that touches the disk for a change. */
    public static boolean commit() {
        boolean ok = store().save();
        if (!ok) Tactical.LOGGER.warn("[Tactical UI] cannot save preferences: {}", store().lastFailure());
        return ok;
    }

    public static void toggleRangeLegend() {
        store().update(prefs -> prefs.withRangeLegend(!prefs.showRangeLegend()));
        commit();
    }
    public static int minPercent() { return MIN_PERCENT; }
    public static int maxPercent() { return MAX_PERCENT; }
    public static java.util.List<Integer> presets() { return PRESETS; }

    public static void scale(double direction) { setPercent(CssScale.adjacent(percent(), direction)); }

    public static void reset() { setPercent(100); }

    /** Index into the new public presets, not the old three-step persistence format. */
    public static void setStep(int value) {
        setPercent(PRESETS.get(Math.max(0, Math.min(PRESETS.size() - 1, value))));
    }

    public static void setPercent(int value) {
        int snapped = CssScale.snap(value);
        store().update(prefs -> prefs.withPercent(snapped));
        Tactical.LOGGER.info("[Tactical UI] interface size -> {}%", snapped);
        BattleHud.refreshScale();
        commit();
    }
}
