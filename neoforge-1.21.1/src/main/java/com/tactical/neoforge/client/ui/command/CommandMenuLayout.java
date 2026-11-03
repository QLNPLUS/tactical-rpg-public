package com.tactical.neoforge.client.ui.command;

import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.theme.Motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Geometry of the command panel, computed once and then shared by drawing, hit testing and the
 * development probe.
 *
 * <p>Pure on purpose: no Minecraft types, so the size-preset conversion and the small-window
 * degradation can be tested headlessly. Everything here is in GUI pixels already rounded to whole
 * pixels, which is what keeps the stepped-corner fills free of seams.</p>
 */
public final class CommandMenuLayout {

    public static final String PANEL = "panel";
    public static final String TITLE = "title";
    public static final String GEAR = "gear";
    public static final String RULE = "rule";
    public static final String ACTOR = "actor";
    public static final String AP_VALUE = "ap";
    public static final String AP_BAR = "ap-bar";
    public static final String HP_VALUE = "hp";
    public static final String HP_BAR = "hp-bar";
    public static final String MP_VALUE = "mp";
    public static final String MP_BAR = "mp-bar";
    /** Read-only weapon line of the standard form; it sits under HP/MP and above the rows. */
    public static final String WEAPON = "weapon";
    /** The scrolling action viewport: the action rows live here and nothing else does. */
    public static final String VIEWPORT = "row-viewport";
    /** The 1px rules that mark the viewport's edges; they exist only while the rows scroll. */
    public static final String VIEWPORT_TOP_RULE = "row-viewport-top";
    public static final String VIEWPORT_BOTTOM_RULE = "row-viewport-bottom";
    public static final String FOOTER_RULE = "footer-rule";
    public static final String FOOTER = "footer";

    public static final String POPOVER = "popover";
    public static final String POP_MINUS = "pop-minus";
    public static final String POP_VALUE = "pop-value";
    public static final String POP_PLUS = "pop-plus";
    public static final String POP_RESET = "pop-reset";
    public static final String POP_LEGEND = "pop-legend";
    /** The three entries this batch adds between the range legend and Close. */
    public static final String POP_THEME = "pop-theme";
    public static final String POP_RANGE_COLOR = "pop-range-color";
    public static final String POP_RESET_SIZES = "pop-reset-sizes";
    public static final String POP_CLOSE = "pop-close";

    /**
     * The nine commands, in the order the panel shows them.
     *
     * <p>The character page comes first because reading it costs nothing: it is a view, not an action, so it
     * carries no AP price and stays reachable even while the encounter is busy or the turn belongs to
     * somebody else. After it the rows follow the order the player was given - move, attack, skill, item,
     * blocks, defend, flee, end - and the block command stays next to the item command because both act on
     * what the main hand holds.</p>
     */
    public static final List<String> ACTIONS = List.of("character", "move", "attack", "skill", "tool", "block",
            "defend", "flee", "end");

    /**
     * The five orders of a hand-commanded companion, in the approved order.
     *
     * <p>Same panel, fewer rows: the character page, the item, the block and the flee rows are absent rather
     * than disabled, because a companion has no such order. The list is a subset of {@link #ACTIONS} in the
     * same relative order, so row geometry, hover, focus and hit tests keep working unchanged.</p>
     */
    public static final List<String> COMPANION_ACTIONS = List.of("move", "attack", "skill", "defend", "end");

    private final Map<String, Rect> rects = new LinkedHashMap<>();
    /** The action rows of this frame: the nine player commands, or the five companion orders. */
    private List<String> actions = ACTIONS;
    private final boolean narrow;
    private final Scale.Form form;
    private boolean cramped;
    private final double scale;
    private boolean minusEnabled = true;
    private boolean plusEnabled = true;
    /** "Reset HUD size" is disabled when every dimension is already at its default. */
    private boolean resetSizesEnabled = true;
    private Rect panelRect = Rect.EMPTY;
    /** How the action viewport resolved for this frame. */
    private Rect viewportRect = Rect.EMPTY;
    private int visibleRows;
    private int firstRow;
    private int maxFirstRow;
    private boolean rowsScrollable;

    private CommandMenuLayout(boolean narrow, Scale.Form form, boolean cramped, double scale) {
        this.narrow = narrow;
        this.form = form;
        this.cramped = cramped;
        this.scale = scale;
    }

    public static String rowId(String action) {
        return "row-" + action;
    }

    /**
     * @param guiWidth  usable GUI width in pixels
     * @param guiHeight usable GUI height in pixels
     * @param scale     interface-size preset as 0.80 .. 1.15
     * @param popoverOpen whether the gear popover occupies the layer above the panel
     */
    public static CommandMenuLayout compute(int guiWidth, int guiHeight, double scale, boolean popoverOpen) {
        return compute(guiWidth, guiHeight, scale, popoverOpen, Scale.MIN_EFFECTIVE, 100, true, 0);
    }

    /**
     * @param floor   smallest scale this frame may draw at: {@link Scale#MIN_EFFECTIVE}, raised to 1.0
     *                when a GUI scale of 1 would render glyphs below one screen pixel
     * @param percent the saved preset percentage, which decides whether an end button is disabled
     */
    public static CommandMenuLayout compute(int guiWidth, int guiHeight, double scale, boolean popoverOpen,
                                            double floor, int percent) {
        return compute(guiWidth, guiHeight, scale, popoverOpen, floor, percent, true, 0);
    }

    /**
     * @param resetSizesEnabled whether "Reset HUD size" has anything to clear; a disabled entry keeps
     *                          its place in the popover but takes no hover and no click
     */
    public static CommandMenuLayout compute(int guiWidth, int guiHeight, double scale, boolean popoverOpen,
                                            double floor, int percent, boolean resetSizesEnabled) {
        return compute(guiWidth, guiHeight, scale, popoverOpen, floor, percent, resetSizesEnabled, 0);
    }

    /**
     * @param firstRow the action row this frame starts at: the viewport's scroll offset in whole rows.
     *                 It is clamped here against what really fits, so an offset kept from a taller
     *                 window can never leave a blank tail.
     */
    public static CommandMenuLayout compute(int guiWidth, int guiHeight, double scale, boolean popoverOpen,
                                            double floor, int percent, boolean resetSizesEnabled, int firstRow) {
        return compute(guiWidth, guiHeight, scale, popoverOpen, floor, percent, resetSizesEnabled, firstRow, ACTIONS);
    }

    /**
     * @param actions the action rows of this frame, in order: {@link #ACTIONS} for a player turn and
     *                {@link #COMPANION_ACTIONS} for a companion turn. Row ids, viewport height, scrolling and
     *                hit tests all follow this list, so a shorter command surface also means a shorter panel.
     */
    public static CommandMenuLayout compute(int guiWidth, int guiHeight, double scale, boolean popoverOpen,
                                            double floor, int percent, boolean resetSizesEnabled, int firstRow,
                                            List<String> actions) {
        List<String> rows = actions == null || actions.isEmpty() ? ACTIONS : List.copyOf(actions);
        double requested = Math.max(Scale.sanitize(scale), Scale.effective(scale, floor));
        // The cramped flag follows the preset the player chose, never the reduced scale: the charge
        // rail decides from the same value, and a flag that flipped with the effective scale would
        // make the rail and the panel disagree about the same window.
        boolean cramped = Scale.crampedFor(guiWidth, requested);
        CommandMenuLayout candidate = computeAtScale(guiWidth, guiHeight, requested, popoverOpen, floor, percent,
                resetSizesEnabled, firstRow, rows);
        if (fits(candidate, guiWidth, guiHeight)) return candidate.withCramped(cramped);
        // None of the three forms holds this preset: find the largest effective scale that does, but
        // never below the floor. The saved preset is untouched; this only affects the current layout.
        double low = Math.min(requested, Math.max(Scale.MIN_EFFECTIVE, floor));
        double high = requested;
        CommandMenuLayout best = computeAtScale(guiWidth, guiHeight, low, popoverOpen, floor, percent,
                resetSizesEnabled, firstRow, rows);
        for (int i = 0; i < 30 && high - low > 1.0E-4D; i++) {
            double mid = (low + high) / 2.0D;
            CommandMenuLayout trial = computeAtScale(guiWidth, guiHeight, mid, popoverOpen, floor, percent,
                    resetSizesEnabled, firstRow, rows);
            if (fits(trial, guiWidth, guiHeight)) { low = mid; best = trial; }
            else high = mid;
        }
        return best.withCramped(cramped);
    }

    private CommandMenuLayout withCramped(boolean value) {
        this.cramped = value;
        return this;
    }

    /** The vanilla hotbar/health/hunger strip the panel must never cover. */
    private static Rect avoidArea(int guiWidth, int guiHeight) {
        return Rect.of(guiWidth / 2 - Scale.AVOID_WIDTH / 2, guiHeight - Scale.AVOID_HEIGHT,
                Scale.AVOID_WIDTH, Scale.AVOID_HEIGHT);
    }

    /**
     * How tall this panel may become: the window less its own edge distance, and - only when the panel
     * really sits over the hotbar strip - less that strip as well.
     */
    private static int availableHeight(int guiWidth, int guiHeight, int panelLeft, int panelWidth) {
        int limit = guiHeight - Scale.EDGE;
        Rect avoid = avoidArea(guiWidth, guiHeight);
        if (panelLeft < avoid.right() && panelLeft + panelWidth > avoid.x()) {
            limit = Math.min(limit, avoid.y() - Scale.EDGE);
        }
        return Math.max(0, limit - Scale.EDGE);
    }

    private static boolean fits(CommandMenuLayout layout, int width, int height) {
        Rect panel = layout.panel();
        Rect hotbar = avoidArea(width, height);
        Rect popup = layout.rect(POPOVER);
        return panel.x() >= Scale.EDGE && panel.bottom() <= height - Scale.EDGE
                && !panel.intersects(hotbar)
                && (popup.w() == 0 || (popup.right() <= width && popup.bottom() <= height));
    }

    private static CommandMenuLayout computeAtScale(int guiWidth, int guiHeight, double requested,
                                                    boolean popoverOpen, double floor, int percent,
                                                    boolean resetSizesEnabled, int requestedFirstRow,
                                                    List<String> actions) {
        double s = Scale.effective(requested, floor);
        Scale.Form form = Scale.form(guiWidth, guiHeight, s);
        boolean narrow = form == Scale.Form.NARROW;
        boolean cramped = false;
        int inset = Scale.EDGE;
        int pad = Scale.effectivePx(Motion.PAD, s);
        int panelWidth = Scale.effectivePx(narrow ? Motion.PANEL_WIDTH_NARROW : Motion.PANEL_WIDTH, s);
        int x0 = guiWidth - inset - panelWidth;
        int y0 = inset;
        int innerX = x0 + pad;
        int innerWidth = Math.max(1, panelWidth - pad * 2);
        int ruleHeight = Scale.rule(s);
        int lineHeight = Scale.effectivePx(Motion.LINE, s);
        int barHeight = Scale.effectivePx(Motion.BAR_HEIGHT, s);
        int step = Scale.effectivePx(Motion.STEP, s);
        int groupAbove = Scale.effectivePx(Motion.GROUP_GAP_ABOVE, s);
        int groupBelow = Scale.effectivePx(Motion.GROUP_GAP_BELOW, s);

        CommandMenuLayout layout = new CommandMenuLayout(narrow, form, cramped, s);
        layout.actions = List.copyOf(actions);
        int y = y0 + pad;
        int gear = Scale.effectivePx(Motion.GEAR, s);

        if (narrow) {
            // Approved small-window form: the actor line becomes the header, the AP value is no
            // longer doubled, HP and MP stack full width on thin tracks and the footer is dropped.
            int headerHeight = Math.max(lineHeight, gear);
            layout.put(TITLE, Rect.of(innerX, y, innerWidth, headerHeight));
            layout.put(GEAR, Rect.of(innerX + innerWidth - gear, y + (headerHeight - gear) / 2, gear, gear));
            y += headerHeight + groupAbove;

            layout.put(RULE, Rect.of(innerX, y, innerWidth, ruleHeight));
            y += ruleHeight + groupBelow;

            layout.put(AP_VALUE, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight + Scale.effectivePx(3, s);
            layout.put(AP_BAR, Rect.of(innerX, y, innerWidth, barHeight));
            y += barHeight + groupAbove;

            layout.put(HP_VALUE, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight + Scale.effectivePx(1, s);
            layout.put(HP_BAR, Rect.of(innerX, y, innerWidth, Scale.effectivePx(Motion.BAR_HEIGHT_THIN, s)));
            y += Scale.effectivePx(Motion.BAR_HEIGHT_THIN, s) + step;

            layout.put(MP_VALUE, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight + Scale.effectivePx(1, s);
            layout.put(MP_BAR, Rect.of(innerX, y, innerWidth, Scale.effectivePx(Motion.BAR_HEIGHT_THIN, s)));
            y += Scale.effectivePx(Motion.BAR_HEIGHT_THIN, s) + groupAbove;
        } else {
            int titleHeight = Scale.effectivePx(Motion.LINE_2X, s);
            layout.put(TITLE, Rect.of(innerX, y, innerWidth, titleHeight));
            layout.put(GEAR, Rect.of(innerX + innerWidth - gear, y + (titleHeight - gear) / 2, gear, gear));
            y += titleHeight + groupAbove;

            layout.put(RULE, Rect.of(innerX, y, innerWidth, ruleHeight));
            y += ruleHeight + groupBelow;

            layout.put(ACTOR, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight + step;

            int apHeight = Scale.effectivePx(Motion.LINE_2X, s);
            layout.put(AP_VALUE, Rect.of(innerX, y, innerWidth, apHeight));
            y += apHeight + step;

            layout.put(AP_BAR, Rect.of(innerX, y, innerWidth, barHeight));
            y += barHeight + groupAbove;

            int columnGap = Scale.effectivePx(Motion.COLUMN_GAP, s);
            int columnWidth = Math.max(1, (innerWidth - columnGap) / 2);
            layout.put(HP_VALUE, Rect.of(innerX, y, columnWidth, lineHeight));
            layout.put(MP_VALUE, Rect.of(innerX + columnWidth + columnGap, y, columnWidth, lineHeight));
            y += lineHeight + step;
            layout.put(HP_BAR, Rect.of(innerX, y, columnWidth, barHeight));
            layout.put(MP_BAR, Rect.of(innerX + columnWidth + columnGap, y, columnWidth, barHeight));
            y += barHeight + groupBelow;

            // The persistent weapon line: a value row, no background, never clickable. It keeps the
            // group gap above and below so it reads as its own group between the vitals and the rows.
            layout.put(WEAPON, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight + groupBelow;
        }

        // ---- the action viewport ------------------------------------------------
        // Only the action rows scroll. Everything above (title, actor, AP, HP/MP, weapon) and the
        // footer are fixed: they keep their full height at every preset, so a window that cannot hold
        // all eight rows scrolls them instead of shrinking the type the player chose.
        int rowHeight = Scale.effectivePx(narrow ? Motion.ROW_HEIGHT_NARROW : Motion.ROW_HEIGHT, s);
        int rowGap = Scale.effectivePx(narrow ? Motion.ROW_GAP_NARROW : Motion.ROW_GAP, s);
        int footerReserve = narrow ? 0
                : groupAbove + ruleHeight + Scale.effectivePx(Motion.STEP, s) + lineHeight;
        int budget = availableHeight(guiWidth, guiHeight, x0, panelWidth) - (y - y0) - footerReserve - pad;
        int fitsRows = ScrollList.rows(budget, rowHeight, rowGap);
        boolean scrolls = fitsRows >= 1 && fitsRows < actions.size();
        // A window that cannot hold even one whole row keeps the old behaviour: the panel becomes taller
        // than the window and the scale falls back, which is the specification's own last resort.
        int visible = scrolls ? fitsRows : actions.size();
        int first = scrolls ? ScrollList.clamp(requestedFirstRow, actions.size(), visible) : 0;
        layout.visibleRows = visible;
        layout.firstRow = first;
        layout.rowsScrollable = scrolls;
        layout.maxFirstRow = ScrollList.maxOffset(actions.size(), visible);
        Rect viewport = Rect.of(innerX, y, innerWidth, ScrollList.height(visible, rowHeight, rowGap));
        layout.viewportRect = viewport;
        layout.put(VIEWPORT, viewport);
        // A scrolling list gives up 2px for the bar and 2px for its gap; a complete list keeps its width.
        int rowWidth = scrolls ? ScrollList.rowWidth(viewport, actions.size(), visible, s) : innerWidth;
        for (int slot = 0; slot < visible; slot++) {
            layout.put(rowId(actions.get(first + slot)),
                    Rect.of(innerX, y + slot * (rowHeight + rowGap), rowWidth, rowHeight));
        }
        y = viewport.bottom();
        if (scrolls) {
            // The 1px boundary rules sit in gaps that already exist, so scrolling costs no extra height.
            int rule = Scale.rule(s);
            layout.put(VIEWPORT_TOP_RULE, Rect.of(innerX, viewport.y() - rule, innerWidth, rule));
            layout.put(VIEWPORT_BOTTOM_RULE, Rect.of(innerX, viewport.bottom(), innerWidth, rule));
        }

        if (!narrow) {
            y += groupAbove;
            layout.put(FOOTER_RULE, Rect.of(innerX, y, innerWidth, ruleHeight));
            y += ruleHeight + Scale.effectivePx(Motion.STEP, s);
            layout.put(FOOTER, Rect.of(innerX, y, innerWidth, lineHeight));
            y += lineHeight;
        }

        layout.finish(Rect.of(x0, y0, panelWidth, y + pad - y0));
        if (popoverOpen) layout.buildPopover(s, groupBelow, percent, resetSizesEnabled);
        return layout;
    }

    private void finish(Rect panelRect) {
        this.panelRect = panelRect;
    }

    private void buildPopover(double s, int groupBelow, int percent, boolean resetSizesEnabled) {
        // The end buttons are disabled exactly at their end of the range; a disabled button has no
        // hover, no press and cannot change the preset.
        minusEnabled = percent > com.tactical.neoforge.client.CssScale.MIN_PERCENT;
        plusEnabled = percent < com.tactical.neoforge.client.CssScale.MAX_PERCENT;
        this.resetSizesEnabled = resetSizesEnabled;
        int pad = Scale.effectivePx(4, s);
        int buttonHeight = Scale.effectivePx(Motion.BUTTON_HEIGHT, s);
        int gap = Scale.effectivePx(Motion.STEP, s);
        int width = Scale.effectivePx(96, s);
        Rect gear = rects.get(GEAR);
        int x = Math.max(Scale.EDGE, panelRect.right() - width);
        int y = gear.bottom() + gap;
        int innerX = x + pad;
        int innerWidth = width - pad * 2;
        int small = Scale.effectivePx(18, s);
        put(POP_MINUS, Rect.of(innerX, y, small, buttonHeight));
        put(POP_PLUS, Rect.of(innerX + innerWidth - small, y, small, buttonHeight));
        put(POP_VALUE, Rect.of(innerX + small + gap, y, Math.max(1, innerWidth - small * 2 - gap * 2), buttonHeight));
        y += buttonHeight + gap;
        put(POP_RESET, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight + gap;
        put(POP_LEGEND, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight + gap;
        put(POP_THEME, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight + gap;
        put(POP_RANGE_COLOR, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight + gap;
        put(POP_RESET_SIZES, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight + gap;
        put(POP_CLOSE, Rect.of(innerX, y, innerWidth, buttonHeight));
        y += buttonHeight;
        put(POPOVER, Rect.of(x, gear.bottom() + gap, width, y + pad - (gear.bottom() + gap)));
    }

    private void put(String id, Rect rect) {
        rects.put(id, rect);
    }

    public Rect rect(String id) {
        return rects.getOrDefault(id, Rect.EMPTY);
    }

    public Rect panel() {
        return panelRect;
    }

    public boolean narrow() {
        return narrow;
    }

    /** Which of the three window forms this frame uses. */
    public Scale.Form form() {
        return form;
    }

    /** True in the tight form: the standard panel, a folded journal and the narrow charge rail. */
    public boolean tight() {
        return form == Scale.Form.TIGHT;
    }

    /** False at the smallest preset, where the "smaller" button is drawn disabled. */
    public boolean minusEnabled() {
        return minusEnabled;
    }

    /** False at the largest preset, where the "larger" button is drawn disabled. */
    public boolean plusEnabled() {
        return plusEnabled;
    }

    /** False when both HUDs are already at their default geometry, so a reset would change nothing. */
    public boolean resetSizesEnabled() {
        return resetSizesEnabled;
    }

    /** True when the window is so narrow that the charge rail keeps only the current actor. */
    public boolean cramped() {
        return cramped;
    }

    public double scale() {
        return scale;
    }

    /** Card row height at the current preset; the narrow layout uses a shorter row. */
    public int rowHeight() {
        return Scale.effectivePx(narrow ? Motion.ROW_HEIGHT_NARROW : Motion.ROW_HEIGHT, scale);
    }

    /**
     * Interactive page controls, highest layer first: the gear sits above the rows it overlaps.
     *
     * <p>Only the rows the viewport really shows take part. A row that is scrolled out keeps no
     * rectangle at all, so it cannot be hovered, cannot be clicked and never reaches the probe.</p>
     */
    public List<Box> pageBoxes() {
        List<Box> boxes = new ArrayList<>();
        boxes.add(Box.of(GEAR, rect(GEAR)));
        for (String action : visibleActions()) {
            boxes.add(Box.of(rowId(action), rect(rowId(action))));
        }
        return boxes;
    }

    /** The action rows this frame draws, in order. */
    public List<String> visibleActions() {
        if (visibleRows <= 0 || firstRow >= actions.size()) return List.of();
        int end = Math.min(actions.size(), firstRow + visibleRows);
        return actions.subList(firstRow, end);
    }

    /** The action rows this frame is allowed to draw, in order. */
    public List<String> actions() {
        return actions;
    }

    /** True when this action row is drawn - and therefore hit-testable - this frame. */
    public boolean rowVisible(String action) {
        return visibleActions().contains(action);
    }

    /**
     * One wheel notch over the viewport: exactly one whole row, clamped to what fits.
     *
     * <p>The offset is clamped while every row is visible, so the caller needs no branch: a complete
     * list simply answers with the offset it was given.</p>
     */
    public int scrollBy(int offset, double delta) {
        if (!rowsScrollable) return 0;
        return ScrollList.scrollBy(offset, actions.size(), Math.max(1, visibleRows), delta);
    }

    /** The viewport the action rows are clipped to. */
    public Rect viewport() {
        return viewportRect;
    }

    /** The 2px bar's track, or empty while every action row is visible. */
    public Rect scrollBar() {
        return ScrollList.track(viewportRect, actions.size(), visibleRows, scale);
    }

    /** How many action rows the viewport shows this frame. */
    public int visibleRows() {
        return visibleRows;
    }

    /** The first visible action row, i.e. the clamped scroll offset. */
    public int firstRow() {
        return firstRow;
    }

    /** The largest useful scroll offset; beyond it the list would show a blank tail. */
    public int maxFirstRow() {
        return maxFirstRow;
    }

    /** True only while some action row is out of sight. */
    public boolean rowsScrollable() {
        return rowsScrollable;
    }

    /** Popover controls, highest layer first. Empty when the popover is closed. */
    public List<Box> popoverBoxes() {
        if (rect(POPOVER).w() <= 0) return List.of();
        List<Box> boxes = new ArrayList<>();
        for (String id : new String[]{POP_CLOSE, POP_RESET_SIZES, POP_RANGE_COLOR, POP_THEME, POP_LEGEND,
                POP_RESET, POP_MINUS, POP_PLUS}) {
            // A disabled entry keeps its place in the panel but takes no hover and no click.
            if (POP_MINUS.equals(id) && !minusEnabled) continue;
            if (POP_PLUS.equals(id) && !plusEnabled) continue;
            if (POP_RESET_SIZES.equals(id) && !resetSizesEnabled) continue;
            boxes.add(Box.of(id, rect(id)));
        }
        return boxes;
    }

    /** Hit order for clicks and hover: every popover control, then the page controls. */
    public List<Box> hitOrder() {
        List<Box> boxes = new ArrayList<>(popoverBoxes());
        boxes.addAll(pageBoxes());
        return boxes;
    }

    /** Every area with an id, in layout order, for the development probe. */
    public Map<String, Rect> allRects() {
        return java.util.Collections.unmodifiableMap(rects);
    }
}
