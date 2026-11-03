package com.tactical.neoforge.client.ui.layout;

/**
 * One named area of a computed layout.
 *
 * <p>The same list feeds drawing, hit testing, scrolling and the development probe, so a control
 * can never be drawn in one place and clicked in another.</p>
 */
public record Box(String id, Rect rect, boolean enabled, boolean interactive) {

    public static Box of(String id, Rect rect) {
        return new Box(id, rect, true, true);
    }

    public static Box display(String id, Rect rect) {
        return new Box(id, rect, true, false);
    }

    public Box disabled() {
        return new Box(id, rect, false, interactive);
    }

    public boolean contains(double x, double y) {
        return rect.contains(x, y);
    }
}
