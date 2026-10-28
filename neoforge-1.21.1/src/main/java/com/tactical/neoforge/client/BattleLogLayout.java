package com.tactical.neoforge.client;

/** Bottom-left journal geometry in AUI CSS pixels; no alternate middle column. */
final class BattleLogLayout {
    static final int HEADER_HEIGHT = 44;
    static final int BODY_CHROME = 30;
    static final int LINE_HEIGHT = 21;
    static final int GAP = 12;

    record Placement(int left, int top, int width, int height, int lines, boolean visible) { }

    private BattleLogLayout() { }

    static Placement place(int viewportWidth, int viewportHeight, int hudBottom,
                           int bottomSafeArea, boolean collapsed, int preferredWidth) {
        return place(viewportWidth, viewportHeight, hudBottom, bottomSafeArea, collapsed,
                new CssScale.JournalMetrics(HEADER_HEIGHT, BODY_CHROME, LINE_HEIGHT, preferredWidth));
    }

    static Placement place(int viewportWidth, int viewportHeight, int hudBottom,
                           int bottomSafeArea, boolean collapsed, CssScale.JournalMetrics metrics) {
        int width = Math.min(Math.max(0, metrics.preferredWidth()), Math.max(0, viewportWidth - GAP * 2));
        int topLimit = Math.max(GAP, hudBottom + GAP);
        int bottomLimit = Math.max(0, viewportHeight - bottomSafeArea - GAP);
        int available = Math.max(0, bottomLimit - topLimit);
        int lines = Math.min(BattleLogState.VISIBLE_LINES,
                Math.max(0, (available - metrics.headerHeight() - metrics.bodyChrome()) / Math.max(1, metrics.rowHeight())));
        // Automatic compact mode does not overwrite the user's collapse preference/history offset.
        int height = collapsed || lines == 0 ? metrics.headerHeight()
                : metrics.headerHeight() + metrics.bodyChrome() + lines * metrics.rowHeight();
        // A tiny header cannot fit its title and real toggle: hide rather than overlap controls.
        int readableWidth = Math.max(120, (int) Math.ceil(180.0 * metrics.rowHeight() / LINE_HEIGHT));
        boolean visible = width >= readableWidth && available >= height;
        return new Placement(GAP, Math.max(topLimit, bottomLimit - height), width, height, lines, visible);
    }
}
