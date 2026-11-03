package com.tactical.neoforge.client;

/**
 * The turn charge rail, now drawn natively.
 *
 * <p>The rail is the only place the participant charge list exists: the command panel never repeats
 * it. Its geometry (width 270 or 147, track at y=26, 18px frames and a 20px current-actor frame,
 * fixed six-pixel lanes) lives in {@link com.tactical.neoforge.client.ui.hud.HudLayout}; the models
 * are real entities rendered through the vanilla inventory renderer, never baked pictures.</p>
 */
public final class TurnChargeHud {

    private TurnChargeHud() {
    }

    /** Kept for the client tick: the HUD repaints from live state every frame, so this only polls. */
    public static void tick() {
        com.tactical.neoforge.client.ui.hud.NativeHud.tick();
    }

    public static void refreshScale() {
        com.tactical.neoforge.client.ui.hud.NativeHud.tick();
    }

    public static void close() {
        com.tactical.neoforge.client.ui.hud.NativeHud.close();
    }
}
