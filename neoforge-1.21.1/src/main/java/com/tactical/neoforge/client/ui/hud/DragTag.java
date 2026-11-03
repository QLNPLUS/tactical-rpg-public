package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;

import java.util.List;

/**
 * Where the size tag of a running drag is drawn.
 *
 * <p>Pure, because "the tag covers no number" is a geometric promise that must be checkable without a
 * window. The tag is drawn last, on an opaque floating chip, so it is never hidden - which means the
 * only way to keep it off the footer's AP, recovery and weapon lines, the journal's count and the row
 * texts is to choose a position that does not intersect them.</p>
 *
 * <p>Order of preference, straight from the visual specification: above the handle it belongs to; then
 * just outside the panel (right, left, above, below); then a free band of world beside the charge
 * rail. A candidate is accepted only when it is fully on screen, clear of the hotbar strip and clear of
 * every protected rectangle. If nothing is clear, the preferred spot is used and reported as forced, so
 * a reviewer sees that the promise could not be kept in that frame instead of a silent overlap.</p>
 */
public final class DragTag {

    /** Which candidate won; the probe reports it so an acceptance run can tell them apart. */
    public enum Where { ABOVE_HANDLE, ABOVE_PANEL, RIGHT_OF_PANEL, LEFT_OF_PANEL, BELOW_PANEL, BESIDE_RAIL, FORCED }

    /** The chosen rectangle and why it was chosen. */
    public record Placement(Rect rect, Where where) {
        public boolean forced() {
            return where == Where.FORCED;
        }
    }

    private DragTag() {
    }

    /**
     * @param handle    the 12x12 hit area of the handle being dragged
     * @param panel     the panel the handle belongs to
     * @param width     tag width in GUI pixels
     * @param height    tag height in GUI pixels
     * @param protectedRects every rectangle that carries a number or text the tag must not cover
     * @param rail      the charge rail, or an empty rectangle when there is none
     */
    public static Placement place(Rect handle, Rect panel, int width, int height, List<Rect> protectedRects,
                                  Rect rail, int guiWidth, int guiHeight) {
        int edge = Scale.EDGE;
        int hotbarTop = guiHeight - Scale.AVOID_HEIGHT;
        int tagWidth = Math.max(1, width);
        int tagHeight = Math.max(1, height);
        int x = clamp(handle.right() - tagWidth, edge, Math.max(edge, guiWidth - edge - tagWidth));
        int centred = clamp(handle.y() + handle.h() / 2 - tagHeight / 2, edge,
                Math.max(edge, guiHeight - edge - tagHeight));

        Rect aboveHandle = Rect.of(x, handle.y() - 4 - tagHeight, tagWidth, tagHeight);
        Rect abovePanel = Rect.of(x, panel.y() - 4 - tagHeight, tagWidth, tagHeight);
        Rect rightOfPanel = Rect.of(panel.right() + 4, centred, tagWidth, tagHeight);
        Rect leftOfPanel = Rect.of(panel.x() - 4 - tagWidth, centred, tagWidth, tagHeight);
        Rect belowPanel = Rect.of(x, panel.bottom() + 4, tagWidth, tagHeight);
        Rect besideRail = rail.w() > 0
                ? Rect.of(rail.x(), rail.bottom() + 6, tagWidth, tagHeight)
                : Rect.EMPTY;

        Rect[] candidates = {aboveHandle, abovePanel, rightOfPanel, leftOfPanel, belowPanel, besideRail};
        Where[] names = {Where.ABOVE_HANDLE, Where.ABOVE_PANEL, Where.RIGHT_OF_PANEL, Where.LEFT_OF_PANEL,
                Where.BELOW_PANEL, Where.BESIDE_RAIL};
        for (int index = 0; index < candidates.length; index++) {
            Rect candidate = candidates[index];
            if (candidate.w() <= 0 || candidate.h() <= 0) continue;
            if (!onScreen(candidate, guiWidth, guiHeight, edge)) continue;
            if (candidate.bottom() > hotbarTop) continue;
            if (overlapsAny(candidate, protectedRects)) continue;
            return new Placement(candidate, names[index]);
        }
        // Nothing is clear in this frame: the preferred spot still tells the player the size, and the
        // frame is reported as forced rather than pretending the promise held.
        return new Placement(aboveHandle, Where.FORCED);
    }

    private static boolean onScreen(Rect rect, int guiWidth, int guiHeight, int edge) {
        return rect.x() >= edge - 1 && rect.right() <= guiWidth - edge + 1
                && rect.y() >= edge - 1 && rect.bottom() <= guiHeight - edge + 1;
    }

    private static boolean overlapsAny(Rect rect, List<Rect> others) {
        if (others == null) return false;
        for (Rect other : others) {
            if (other == null || other.w() <= 0 || other.h() <= 0) continue;
            if (rect.x() < other.right() && other.x() < rect.right()
                    && rect.y() < other.bottom() && other.y() < rect.bottom()) return true;
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
