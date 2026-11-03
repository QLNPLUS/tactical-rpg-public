package com.tactical.neoforge.client.ui.panel;

/**
 * What a cached panel layout was built for: the GUI size, the size preset and one content
 * fingerprint (the page's own content plus language, fonts and saved preferences).
 *
 * <p>Pure, so "rebuild or not" is a decision that can be checked without a window. The window size
 * and the preset are compared exactly; the preset uses an epsilon because it arrives as a double from
 * a percentage. A different fingerprint - a new skill, a language change, a saved size - is a rebuild
 * even when the window never moved, which is what stops a page from keeping the empty list it built
 * before the first server snapshot arrived.</p>
 */
public record LayoutKey(int guiWidth, int guiHeight, double scale, long fingerprint) {

    private static final double SCALE_EPSILON = 1.0E-4D;

    public boolean matches(LayoutKey other) {
        return other != null && guiWidth == other.guiWidth && guiHeight == other.guiHeight
                && Math.abs(scale - other.scale) <= SCALE_EPSILON && fingerprint == other.fingerprint;
    }
}
