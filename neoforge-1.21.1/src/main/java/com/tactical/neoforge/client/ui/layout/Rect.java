package com.tactical.neoforge.client.ui.layout;

/**
 * An axis-aligned rectangle in GUI pixels with half-open bounds: {@code [x, x+w) x [y, y+h)}.
 *
 * <p>Half-open bounds are the whole point of this type: adjacent rows and cells must never both
 * claim the same pixel, and a cursor exactly on the shared edge belongs to the later cell only.</p>
 */
public record Rect(int x, int y, int w, int h) {

    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public static Rect of(int x, int y, int w, int h) {
        return new Rect(x, y, Math.max(0, w), Math.max(0, h));
    }

    public int right() {
        return x + w;
    }

    public int bottom() {
        return y + h;
    }

    /** Half-open containment. A zero-width or zero-height rectangle contains nothing. */
    public boolean contains(double px, double py) {
        return w > 0 && h > 0 && px >= x && px < right() && py >= y && py < bottom();
    }

    public boolean contains(Point point) {
        return point != null && contains(point.x(), point.y());
    }

    public boolean intersects(Rect other) {
        return other != null && w > 0 && h > 0 && other.w > 0 && other.h > 0
                && x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
    }

    /** The area two rectangles share, or {@link #EMPTY} when they do not overlap. */
    public Rect intersection(Rect other) {
        if (!intersects(other)) return EMPTY;
        int left = Math.max(x, other.x);
        int top = Math.max(y, other.y);
        int right = Math.min(right(), other.right());
        int bottom = Math.min(bottom(), other.bottom());
        return of(left, top, right - left, bottom - top);
    }

    public Rect inset(int left, int top, int right, int bottom) {
        return of(x + left, y + top, w - left - right, h - top - bottom);
    }

    public Rect inset(int all) {
        return inset(all, all, all, all);
    }

    public Rect move(int dx, int dy) {
        return new Rect(x + dx, y + dy, w, h);
    }

    /** Clamps the rectangle into {@code bounds} without changing its origin when it already fits. */
    public Rect clampX(int min, int max) {
        int clampedX = Math.max(min, Math.min(max - w, x));
        return new Rect(clampedX, y, w, h);
    }

    public Rect withHeight(int height) {
        return new Rect(x, y, w, Math.max(0, height));
    }
}
