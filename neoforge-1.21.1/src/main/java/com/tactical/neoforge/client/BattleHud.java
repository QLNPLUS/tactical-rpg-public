package com.tactical.neoforge.client;

/**
 * The battle roster, now drawn natively.
 *
 * <p>The old class owned an AUI overlay document and rewrote its styles on every frame. The panel
 * itself is {@link com.tactical.neoforge.client.ui.hud.NativeHud}: roster, journal, charge rail and
 * the passive hint are one layout snapshot, painted once per frame, and the world hover box is fed
 * by the same snapshot. This class therefore only keeps the entry points the rest of the client
 * already calls.</p>
 */
public final class BattleHud {

    private BattleHud() {
    }

    /** Called every client tick: hover, the entity locator and the scroll clamps follow the frame. */
    public static void tick() {
        com.tactical.neoforge.client.ui.hud.NativeHud.tick();
    }

    /** A preset change only needs the next frame; the layout is recomputed from the new scale. */
    public static void refreshScale() {
        com.tactical.neoforge.client.ui.hud.NativeHud.tick();
    }

    /** Battle over, disconnect or world change: the HUD keeps no scroll position past the fight. */
    public static void close() {
        com.tactical.neoforge.client.ui.hud.NativeHud.close();
    }
}
