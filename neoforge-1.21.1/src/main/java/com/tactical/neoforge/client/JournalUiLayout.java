package com.tactical.neoforge.client;

/** Shared logical-pixel dimensions for the journal, roster and native portrait rail. */
final class JournalUiLayout {
    static final double MENU_WIDTH = 420;
    static final double HUD_WIDTH = 290;
    static final double PORTRAIT_WIDTH = 36;
    static final double PORTRAIT_HEIGHT = 42;
    static final double FRAME_INSET = 5;
    static final int FRAME_TEXTURE_WIDTH = 144;
    static final int FRAME_TEXTURE_HEIGHT = 168;

    record Rail(double left, double width, double inset, double portraitWidth,
                double portraitHeight, double top, double minimumHeight) {
        double contentWidth() { return width - 2 * inset; }
        double trackInset() { return inset + portraitWidth / 2; }
    }

    /** One budget used by both overlays; neither reserves space from the other's stale layout. */
    record StackBudget(int rosterHeight, int hudBottom, int journalLines, boolean journalVisible) { }

    private JournalUiLayout() { }

    static StackBudget stack(double scale, int viewportHeight, int bottomSafeArea,
                             boolean expanded, int rosterCount) {
        var journal = CssScale.journal(scale);
        int row = (int) Math.ceil(31 * scale);
        // Borders + padding + top rule + AP/weapon footer; deliberately round upwards.
        int chrome = (int) Math.ceil(16 + 98 * scale);
        int space = Math.max(0, viewportHeight - bottomSafeArea - 3 * BattleLogLayout.GAP);
        int minimumRoster = Math.min(3, Math.max(0, rosterCount)) * row;
        int lines = expanded ? 4 : 0;
        int journalHeight = journal.headerHeight() + (lines == 0 ? 0
                : journal.bodyChrome() + lines * journal.rowHeight());
        // Small viewports lose journal rows first, not the entire scrollable roster.
        while (lines > 0 && chrome + minimumRoster + journalHeight > space) {
            lines--;
            journalHeight = journal.headerHeight() + (lines == 0 ? 0
                    : journal.bodyChrome() + lines * journal.rowHeight());
        }
        boolean visible = chrome + Math.min(row, minimumRoster) + journalHeight <= space;
        int room = Math.max(0, space - chrome - (visible ? journalHeight : 0));
        int roster = Math.min(Math.max(0, rosterCount) * row,
                Math.min((int) Math.floor(viewportHeight * .4), room));
        return new StackBudget(roster, BattleLogLayout.GAP + chrome + roster,
                visible ? lines : 0, visible);
    }

    /** Widen prose only into genuinely free space left of the right operation panel. */
    static int journalWidth(double scale, int viewportWidth) {
        double rightLimit = viewportWidth - menuWidth(scale, viewportWidth) - 40;
        return Math.max(0, (int) Math.floor(Math.min(390 * scale, rightLimit - BattleLogLayout.GAP)));
    }

    static double hudWidth(double scale, double viewportWidth) {
        return Math.min(HUD_WIDTH * scale, viewportWidth * .34);
    }

    static double menuWidth(double scale, double viewportWidth) {
        return Math.min(MENU_WIDTH * scale, Math.max(0, viewportWidth - 40));
    }

    /** Controls are outside the scrolling page so AUI hit tests stay unscrolled. */
    static double controlsTop(double scale, double viewportHeight) {
        return Math.max(0, Math.min(78 * scale, viewportHeight - 156 * scale));
    }

    static Rail rail(double scale, double viewportWidth) {
        double left = 12 + hudWidth(scale, viewportWidth) + 16;
        double inset = 14 * scale;
        double width = Math.max((PORTRAIT_WIDTH + 28) * scale,
                viewportWidth - left - menuWidth(scale, viewportWidth) - 36);
        return new Rail(left, width, inset, PORTRAIT_WIDTH * scale,
                PORTRAIT_HEIGHT * scale, 7 * scale, 56 * scale);
    }
}
