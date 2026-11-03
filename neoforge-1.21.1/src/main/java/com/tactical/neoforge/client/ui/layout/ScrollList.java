package com.tactical.neoforge.client.ui.layout;

/**
 * Whole-row clipping and scrollbar geometry shared by every scrollable list.
 *
 * <p>The visual specification never allows a half row: a list draws only the rows that fit
 * completely, steps one row per wheel notch, clamps its offset when the list shrinks, and shows a
 * 2px bar only while there is something to scroll. All of that is pure arithmetic so it can be
 * checked without a game window, and drawing, hit testing and the probe all read the same numbers.</p>
 */
public final class ScrollList {

    /** Scrollbar width at 100%. */
    public static final int BAR_WIDTH = 2;
    /** Gap between the bar and the rows it belongs to. */
    public static final int BAR_GAP = 2;
    /** Smallest thumb the specification allows, so the bar never disappears on a long list. */
    public static final int MIN_THUMB = 8;

    private ScrollList() {
    }

    /**
     * How many whole rows fit into {@code height}: rows occupy {@code n*row + (n-1)*gap}, and the
     * last row never carries a trailing gap.
     */
    public static int rows(int height, int rowHeight, int gap) {
        if (height <= 0 || rowHeight <= 0) return 0;
        int step = Math.max(1, rowHeight + Math.max(0, gap));
        return Math.max(0, (height + gap) / step);
    }

    /** Height actually used by {@code count} whole rows. */
    public static int height(int count, int rowHeight, int gap) {
        if (count <= 0) return 0;
        return count * rowHeight + (count - 1) * Math.max(0, gap);
    }

    public static boolean scrollable(int total, int visible) {
        return visible > 0 && total > visible;
    }

    /** Clamps a scroll offset so the list never shows a blank tail. */
    public static int clamp(int offset, int total, int visible) {
        return Math.max(0, Math.min(maxOffset(total, visible), offset));
    }

    public static int maxOffset(int total, int visible) {
        return Math.max(0, total - Math.max(0, visible));
    }

    /** One wheel notch is exactly one row; the result is clamped like {@link #clamp}. */
    public static int scrollBy(int offset, int total, int visible, double delta) {
        if (!Double.isFinite(delta) || delta == 0) return clamp(offset, total, visible);
        int steps = (int) Math.signum(delta);
        return clamp(offset - steps, total, visible);
    }

    /** The 2px bar's track inside {@code area}, or empty when the list does not scroll. */
    public static Rect track(Rect area, int total, int visible, double scale) {
        if (!scrollable(total, visible) || area.w() <= 0 || area.h() <= 0) return Rect.EMPTY;
        int width = Scale.effectivePx(BAR_WIDTH, scale);
        return Rect.of(area.right() - width, area.y(), width, area.h());
    }

    /**
     * The thumb of a scrollable list: proportional, at least {@link #MIN_THUMB} tall.
     *
     * <p>Both the offset and the height are clamped into the track here. A caller that handed over a
     * browse offset from a wider window than the one drawn now could otherwise push the thumb below
     * the track's own bottom edge, which is exactly the "thumb runs past the window" the user saw.</p>
     */
    public static Rect thumb(Rect track, int total, int visible, int offset, double scale) {
        if (track.w() <= 0 || track.h() <= 0 || !scrollable(total, visible)) return Rect.EMPTY;
        int minimum = Math.min(track.h(), Scale.effectivePx(MIN_THUMB, scale));
        int height = Math.max(minimum, (int) Math.round(track.h() * (visible / (double) total)));
        height = Math.max(1, Math.min(track.h(), height));
        int travel = track.h() - height;
        int maxOffset = maxOffset(total, visible);
        int clamped = Math.max(0, Math.min(maxOffset, offset));
        int y = track.y() + (maxOffset <= 0 ? 0 : (int) Math.round(travel * (clamped / (double) maxOffset)));
        y = Math.max(track.y(), Math.min(track.bottom() - height, y));
        return Rect.of(track.x(), y, track.w(), height);
    }

    /** Row width once the bar and its gap are reserved; the bar only exists while scrolling. */
    public static int rowWidth(Rect area, int total, int visible, double scale) {
        if (!scrollable(total, visible)) return area.w();
        return Math.max(1, area.w() - Scale.effectivePx(BAR_WIDTH + BAR_GAP, scale));
    }
}
