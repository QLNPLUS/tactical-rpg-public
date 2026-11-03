package com.tactical.neoforge.client.ui.widget;

import com.tactical.neoforge.client.ui.theme.Motion;

/**
 * Per-control hover / press progress, advanced once per frame.
 *
 * <p>Only drawing depends on this state: an animation never blocks a click and never changes the
 * underlying battle data. Progress is stored per control id so rows cannot borrow each other's
 * fade, and every control returns to zero when the pointer leaves.</p>
 */
public final class Tween {

    private final java.util.Map<String, Double> values = new java.util.HashMap<>();
    private String lastHovered;
    private String lastPressed;
    private long hoverSince;
    private long pressSince;

    /** Advances every known control towards its target and returns the current progress. */
    public double progress(String id, boolean target, long nowMs, int durationMs, double deltaMs) {
        double current = values.getOrDefault(id, target ? 1.0D : 0.0D);
        double step = durationMs <= 0 ? 1.0D : Math.max(0.0D, deltaMs) / durationMs;
        double next = target ? Math.min(1.0D, current + step) : Math.max(0.0D, current - step);
        values.put(id, next);
        return next;
    }

    public double value(String id) {
        return values.getOrDefault(id, 0.0D);
    }

    /** Records the hovered control and when it became hovered; null clears the hovered control. */
    public void hovered(String id, long nowMs) {
        if (id == null ? lastHovered == null : id.equals(lastHovered)) return;
        lastHovered = id;
        hoverSince = nowMs;
    }

    public String hoveredId() {
        return lastHovered;
    }

    public long hoveredSince() {
        return hoverSince;
    }

    /** True when the hovered control has been hovered long enough for its tooltip. */
    public boolean tooltipDue(long nowMs) {
        return lastHovered != null && nowMs - hoverSince >= Motion.TOOLTIP_DELAY_MS;
    }

    public void pressed(String id, long nowMs) {
        lastPressed = id;
        pressSince = nowMs;
    }

    public String pressedId() {
        return lastPressed;
    }

    public long pressedSince() {
        return pressSince;
    }

    /** True while the press animation of {@code id} is still running. */
    public boolean pressActive(String id, long nowMs) {
        return id != null && id.equals(lastPressed) && nowMs - pressSince < Motion.PRESS_MS;
    }

    public void clear() {
        values.clear();
        lastHovered = null;
        lastPressed = null;
    }
}
