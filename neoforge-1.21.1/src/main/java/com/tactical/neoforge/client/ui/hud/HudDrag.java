package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.layout.Scale;

/**
 * One HUD size drag, from the press that started it to the release that ends it.
 *
 * <p>Pure: it only remembers where the drag began and turns pointer positions into proposed 100%
 * design sizes, so the parts a reviewer cares about - the drag belongs to the handle it started on,
 * a cancelled drag writes nothing, the value never leaves its own domain, and an axis the user never
 * touched stays untouched - are checkable without a window. The screen-level clamp stays in the
 * layout, which is the only thing that knows how much room is left this frame.</p>
 */
public final class HudDrag {

    /** Which handle owns the drag; nothing else may act while it runs. */
    public enum Target { ROSTER, JOURNAL }

    /**
     * What the drag was measured against. A change in any of these makes the stored coordinates
     * meaningless, so the drag is cancelled rather than applied to a layout it no longer describes.
     */
    public record Start(Target target, double pointerX, double pointerY, double scale, Scale.Form form,
                        int guiWidth, int guiHeight, int widthPx, int heightPx,
                        Integer savedWidth, Integer savedHeight) {
    }

    private Start start;
    private boolean swallowRelease;
    private boolean movedWidth;
    private boolean movedHeight;

    /**
     * How far the pointer has to leave its press position before an axis counts as dragged. One GUI
     * pixel: a press that is released where it landed is a click, not a resize, and must not write.
     */
    private static final double DEADBAND = 1.0D;

    public boolean begin(Start value) {
        if (value == null) return false;
        start = value;
        swallowRelease = false;
        movedWidth = false;
        movedHeight = false;
        return true;
    }

    public boolean active() {
        return start != null;
    }

    public Target target() {
        return start == null ? null : start.target();
    }

    public Start start() {
        return start;
    }

    /** True once the pointer has really moved along one of the two axes. */
    public boolean moved() {
        return movedWidth || movedHeight;
    }

    public boolean movedWidth() {
        return movedWidth;
    }

    public boolean movedHeight() {
        return movedHeight;
    }

    /**
     * The design width this pointer position asks for, or {@code null} while the pointer has not left
     * its press position horizontally.
     *
     * <p>Returning null rather than the value the drag started with is what keeps a horizontal drag
     * from freezing the other axis: a caller that only writes a non-null result leaves an AUTO height
     * AUTO instead of turning this frame's automatic height into an explicit preference.</p>
     */
    public Integer width(double pointerX) {
        if (start == null) return null;
        if (Math.abs(pointerX - start.pointerX()) < DEADBAND) return null;
        movedWidth = true;
        return widthFor(start, pointerX);
    }

    public Integer height(double pointerY) {
        if (start == null) return null;
        if (Math.abs(pointerY - start.pointerY()) < DEADBAND) return null;
        movedHeight = true;
        return heightFor(start, pointerY);
    }

    public static Integer widthFor(Start start, double pointerX) {
        if (start == null) return null;
        double delta = (pointerX - start.pointerX()) / start.scale();
        return clamp(Math.round(start.widthPx() / (float) start.scale() + (float) delta),
                HudLayout.ROSTER_MIN_WIDTH, HudLayout.ROSTER_MAX_WIDTH);
    }

    public static Integer heightFor(Start start, double pointerY) {
        if (start == null) return null;
        double delta = (pointerY - start.pointerY()) / start.scale();
        int base = Math.round(start.heightPx() / (float) start.scale() + (float) delta);
        return start.target() == Target.JOURNAL
                ? clamp(base, HudLayout.JOURNAL_BODY_MIN, HudLayout.JOURNAL_BODY_MAX)
                : clamp(base, Math.max(1, HudLayout.ROSTER_ROW_BASE + 9), HudLayout.JOURNAL_BODY_MAX);
    }

    private static Integer clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Ends the drag without writing; the pending release must not become a world action. */
    public void cancel() {
        if (start != null) swallowRelease = true;
        start = null;
        movedWidth = false;
        movedHeight = false;
    }

    /** Ends the drag normally; there is nothing left to swallow. */
    public void end() {
        start = null;
        swallowRelease = false;
    }

    /** True once for the release that follows a cancel, so it is consumed and then forgotten. */
    public boolean takeSwallowedRelease() {
        if (!swallowRelease) return false;
        swallowRelease = false;
        return true;
    }

    public void clear() {
        start = null;
        swallowRelease = false;
        movedWidth = false;
        movedHeight = false;
    }
}
