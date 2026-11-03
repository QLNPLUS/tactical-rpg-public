package com.tactical.neoforge.client;

import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The screen half of the single wheel chain.
 *
 * <p>{@link BattleScreenInput#scroll} already decides what a notch means (journal history, the open
 * page's scrollable list, the hotbar); a consumed notch is cancelled here so the screen's own
 * {@code mouseScrolled} cannot apply it twice. The world half stays in
 * {@link BattleScreenInput#onWorldScroll}. Every screen is native now, so there is no per-document
 * installation left to do.</p>
 */
public final class WheelGuard {
    private WheelGuard() {}

    public static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        boolean consumed = BattleScreenInput.scroll(event.getScrollDeltaY());
        WheelTrace.notch("screen-event", event.getScrollDeltaY(), consumed ? "consumed" : "passed");
        if (consumed) event.setCanceled(true);
    }
}
