package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.ui.debug.DevProbe;

/**
 * Development-only record of what one wheel notch really did.
 *
 * <p>Three hooks can carry a notch: the raw {@code MouseHandler.onScroll} bridge, the loader's
 * {@code ScreenEvent.MouseScrolled.Pre}, and its world scroll event. Which of them ran for one physical
 * notch - and whether more than one did - cannot be read off the source, so the roster's direction can
 * neither be blamed on nor cleared by reading it. This prints one line per delivery while the
 * development probe is enabled and does nothing at all in a release, where {@code DevProbe.enabled()}
 * is a single constant load.</p>
 */
public final class WheelTrace {

    private WheelTrace() {
    }

    /** {@code path} names the hook, {@code outcome} what the one chain decided. */
    public static void notch(String path, double delta, String outcome) {
        if (!DevProbe.enabled()) return;
        Tactical.LOGGER.info("[Tactical] wheel path={} delta={} {}", path, delta, outcome);
    }
}
