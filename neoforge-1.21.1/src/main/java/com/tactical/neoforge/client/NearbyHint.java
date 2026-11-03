package com.tactical.neoforge.client;

/**
 * The passive "a battle is running nearby" hint, now drawn natively.
 *
 * <p>It is part of the same HUD snapshot as the roster and the rail: one line at the top centre, a
 * floating opaque surface, no icon, no button, no hit area. It never takes the cursor, never consumes
 * a click or a wheel notch, and disappears while a tactical screen is open.</p>
 */
public final class NearbyHint {

    private NearbyHint() {
    }

    /** The panel state follows the client tick; the hint itself is painted with the rest of the HUD. */
    public static void tick() {
        com.tactical.neoforge.client.ui.hud.NativeHud.tick();
    }

    public static void close() {
        com.tactical.neoforge.client.ui.hud.NativeHud.close();
    }
}
