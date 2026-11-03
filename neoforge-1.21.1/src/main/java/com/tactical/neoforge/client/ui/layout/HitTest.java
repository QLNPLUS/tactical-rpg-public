package com.tactical.neoforge.client.ui.layout;

import java.util.List;

/**
 * Input priority for layered UI: the first matching box in an ordered list wins.
 *
 * <p>Callers pass the list ordered from the highest layer to the lowest - popovers before page
 * controls before HUD - which is exactly the order the specification requires for both clicks and
 * hover. Training the order into a helper keeps every screen from re-inventing it.</p>
 */
public final class HitTest {

    private HitTest() {
    }

    /** The first interactive box containing the point, or null. Disabled controls still block. */
    public static Box topmost(List<Box> topFirst, double x, double y) {
        if (topFirst == null) return null;
        for (Box box : topFirst) {
            if (box != null && box.interactive() && box.contains(x, y)) return box;
        }
        return null;
    }

    /** Same as {@link #topmost} but only for enabled boxes: a disabled control must not react. */
    public static Box topmostEnabled(List<Box> topFirst, double x, double y) {
        Box hit = topmost(topFirst, x, y);
        return hit != null && hit.enabled() ? hit : null;
    }

    /** True when any box in the list, enabled or not, contains the point. */
    public static boolean anyContains(List<Box> boxes, double x, double y) {
        if (boxes == null) return false;
        for (Box box : boxes) {
            if (box != null && box.contains(x, y)) return true;
        }
        return false;
    }
}
