package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Where every part of the character page sits, in GUI pixels, for one frame.
 *
 * <p>Pure arithmetic over the window, the preset and the page's content flags: the same snapshot feeds
 * drawing, hit testing, the wheel, the focus ring and the development probe, so a control can never be drawn
 * in one place and clicked in another. No Minecraft type is used, which is what lets the sizes in the
 * acceptance matrix be checked without a game window.</p>
 *
 * <p>Design values are the specification's 100% numbers; everything is scaled once through {@link Scale} and
 * rounded to whole pixels. The three forms are fixed: the standard form is one column of six tall rows, the
 * narrow form splits each row into two lines and one derived column, and a page that is too tall for the
 * window scrolls its body instead of shrinking any text.</p>
 *
 * <p>The body is <em>one</em> viewport: the six stat rows and the derived block beneath them scroll together,
 * exactly as the frozen specification says ("属性表与派生区合成一个滚动视口"). The requested interface size is
 * never lowered: a short window gives the body fewer pixels, and the rest is reached with the wheel.</p>
 */
public record CharacterLayout(int guiWidth, int guiHeight, double scale, boolean narrow, Rect panel,
                              Rect title, Rect close, Rect accentRule, List<Rect> levelRows, Rect unspentRow,
                              Rect statViewport, List<StatRow> statRows, boolean scrollable, int totalRows,
                              int visibleItems, int scrollOffset, int maxScrollOffset, Rect scrollBar,
                              Rect viewportTopRule, Rect viewportBottomRule, Rect derivedTitle,
                              List<Rect> derivedCells, Rect statusRow, Rect separator, List<Area> buttons,
                              Rect popup, Rect popupTitle, List<Rect> popupRows, List<Rect> popupNote,
                              List<Area> popupButtons, List<Box> boxes, List<String> focusOrder) {

    /** Design width of the page at 100%. */
    public static final int DESIGN_WIDTH = 248;
    /** Design width below which the table turns into two-line rows and the derived grid becomes one column. */
    public static final int NARROW_WIDTH = 220;
    /** Design width of the level-up confirmation. */
    public static final int POPUP_WIDTH = 168;
    /** Design height of one title line inside the 11px title row. */
    public static final int TITLE_HEIGHT = 11;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_HEIGHT_NARROW = 27;
    private static final int ROW_PAD = 3;
    private static final int ICON_BUTTON = 14;
    private static final int DERIVED_ROW_HEIGHT = 11;
    private static final int DERIVED_GAP = 2;
    /** Distance between the level-up button and the two allocation buttons: they are different transactions. */
    private static final int LEVEL_UP_SEPARATION = 8;

    /** One stat row: the box and its {@code −}/{@code +} buttons in the same coordinate space. */
    public record StatRow(CharacterStat stat, Rect row, Rect minus, Rect plus) { }

    /** One named control of this page. */
    public record Area(String id, Rect rect, boolean enabled) {
        public Box box() { return new Box(id, rect, enabled, true); }
    }

    /** What the page currently contains; a change in any of these rebuilds the layout. */
    /**
     * Everything the geometry depends on, as the page's model really reports it.
     *
     * <p>The three action flags are the model's own answers - "the level-up would be accepted", "the draft
     * could be confirmed", "there is a draft to discard" - so the box the pointer can press and the box the
     * page draws enabled are the same decision. A footer button that looks available and then does nothing is
     * exactly what these flags exist to prevent.</p>
     *
     * <p>There is deliberately no "has a status line" flag: the status row is always laid out, so a status
     * that appears or disappears (a refusal, a preview acknowledgement, a read-only reason) can never change
     * the panel's height, its centring, its button rectangles or its scroll offset. Otherwise every preview
     * round trip would move the buttons under the pointer.</p>
     */
    public record Content(boolean hasState, boolean readOnly, boolean waiting, boolean popup,
                          boolean needsRefresh, boolean canLevelUp, boolean canConfirm, boolean canDiscard,
                          int canAddMask, int canRemoveMask) {

        /** Whether {@code stat} may be drafted on this frame, by the page's own rules. */
        public boolean canAdd(CharacterStat stat) { return bit(canAddMask, stat); }

        /** Whether {@code stat} keeps a drafted point that may be taken back on this frame. */
        public boolean canRemove(CharacterStat stat) { return bit(canRemoveMask, stat); }

        /** Packs the six per-stat flags into the mask this record carries. */
        public static int mask(boolean... flags) {
            int packed = 0;
            for (int index = 0; index < flags.length && index < Integer.SIZE; index++) {
                if (flags[index]) packed |= 1 << index;
            }
            return packed;
        }

        private static boolean bit(int mask, CharacterStat stat) {
            return stat != null && (mask & 1 << stat.index()) != 0;
        }
    }

    /** Identifiers shared by the layout, the screen, the probe and the tests. */
    public static final String CLOSE = "character-close";
    public static final String CONFIRM = "character-confirm";
    public static final String DISCARD = "character-discard";
    public static final String LEVEL_UP = "character-level-up";
    public static final String POPUP_CONFIRM = "character-popup-confirm";
    public static final String POPUP_CANCEL = "character-popup-cancel";
    public static final String STAT_VIEWPORT = "character-table";
    public static final String VIEWPORT_TOP_RULE = "character-table-top";
    public static final String VIEWPORT_BOTTOM_RULE = "character-table-bottom";

    public static String minusId(CharacterStat stat) { return "character-minus-" + stat.id(); }

    public static String plusId(CharacterStat stat) { return "character-plus-" + stat.id(); }

    public static String rowId(CharacterStat stat) { return "character-row-" + stat.id(); }

    public static String derivedCellId(CharacterStat stat) { return "character-derived-" + stat.id(); }

    /**
     * Builds one frame's layout at exactly the interface size the player chose.
     *
     * <p>@param measure raw (unscaled) text width of a label in the current language; @param labels the three
     * footer button labels, in the order confirm, discard, level-up; @param popupLabels the two confirmation
     * labels, in the order confirm, cancel.</p>
     *
     * <p>There is deliberately no scale fallback: the frozen specification gives this page a scrolling body,
     * not permission to override the type size the player picked. A 427x240 window at 115% therefore stays at
     * 1.15 and scrolls its body, keeping the footer complete.</p>
     */
    public static CharacterLayout build(int guiWidth, int guiHeight, double requestedScale, Content content,
                                        ToIntFunction<String> measure, List<String> labels,
                                        List<String> popupLabels, int requestedScroll) {
        return buildAt(guiWidth, guiHeight, Scale.sanitize(requestedScale), content, measure, labels, popupLabels,
                requestedScroll);
    }

    /** True when the whole panel sits on screen, which is the promise the short-window rule keeps. */
    public boolean fitsOnScreen() {
        return panel.y() >= 0 && panel.bottom() <= guiHeight && panel.x() >= 0 && panel.right() <= guiWidth;
    }

    private static CharacterLayout buildAt(int guiWidth, int guiHeight, double scale, Content content,
                                           ToIntFunction<String> measure, List<String> labels,
                                           List<String> popupLabels, int requestedScroll) {
        double s = Scale.sanitize(scale);
        Content state = content == null
                ? new Content(false, true, false, false, true, false, false, false, 0, 0) : content;
        int edge = Scale.EDGE;
        int pad = px(Motion.PAD, s);
        int width = Math.min(px(DESIGN_WIDTH, s), Math.max(px(80, s), guiWidth - edge * 2));
        boolean narrow = width < px(NARROW_WIDTH, s);
        int inner = width - pad * 2;

        int rowHeight = px(narrow ? ROW_HEIGHT_NARROW : ROW_HEIGHT, s);
        int rowGap = px(Motion.ROW_GAP, s);
        int line = px(Motion.LINE, s);
        int levelBlock = line * 3 + rowGap * 2;
        int unspent = line;
        int ruleHeight = Scale.rule(s);
        // Reserved unconditionally: the status row is part of the fixed frame, so writing into it never
        // re-centres the page and never moves a button.
        int statusHeight = line;
        int buttonHeight = px(Motion.BUTTON_HEIGHT, s);
        int groupGap = px(Motion.GROUP_GAP_ABOVE, s);
        int separatorArea = px(Motion.GROUP_GAP_ABOVE, s) + Scale.rule(s) + px(Motion.GROUP_GAP_BELOW, s);

        // Footer: equal-width buttons, horizontal when the three fit with the level-up separation.
        int buttonWidth = 0;
        for (String label : safe(labels)) {
            buttonWidth = Math.max(buttonWidth, TextMetrics.width(measure.applyAsInt(label), s)
                    + px(Motion.BUTTON_PAD_X, s) * 2);
        }
        int gap = px(Motion.STEP + 2, s);
        boolean stacked = labels == null || labels.size() < 3
                || buttonWidth * 3 + gap * 2 + px(LEVEL_UP_SEPARATION, s) > inner;
        int footerHeight = stacked ? buttonHeight * 3 + gap * 2 : buttonHeight;

        // The fixed frame is everything except the scrolling body: title, level block, the unspent row, the
        // status line, the separator and the footer. Neither the table nor the derived block is part of it any
        // more - that is exactly the defect this layout used to have, which forced it to lower the player's
        // interface size to make the page fit.
        int fixed = pad + px(TITLE_HEIGHT, s) + px(Motion.GROUP_GAP_ABOVE, s) + ruleHeight
                + px(Motion.GROUP_GAP_BELOW, s) + levelBlock + px(Motion.GROUP_GAP_ABOVE, s) + unspent
                + groupGap
                + rowGap + statusHeight
                + separatorArea + footerHeight + pad;

        // ---- the body: six stat rows, then the derived title and its cells, as one list of items ----------
        // Every item is laid out whole; each wheel notch moves exactly one item. The derived grid is two
        // columns in the standard form and one column in the narrow form.
        int derivedColumns = narrow ? 1 : 2;
        int derivedRowCount = (CharacterStat.COUNT + derivedColumns - 1) / derivedColumns;
        int derivedTitleHeight = line;
        int derivedCellHeight = px(DERIVED_ROW_HEIGHT, s);
        int derivedGap = px(DERIVED_GAP, s);
        int itemCount = CharacterStat.COUNT + 1 + derivedRowCount;

        int[] itemHeight = new int[itemCount];
        int[] itemStart = new int[itemCount];
        for (int index = 0; index < CharacterStat.COUNT; index++) itemHeight[index] = rowHeight;
        itemHeight[CharacterStat.COUNT] = derivedTitleHeight;
        for (int index = 0; index < derivedRowCount; index++) {
            itemHeight[CharacterStat.COUNT + 1 + index] = derivedCellHeight;
        }
        int tracked = 0;
        for (int index = 0; index < itemCount; index++) {
            itemStart[index] = tracked;
            tracked += itemHeight[index];
            if (index + 1 < itemCount) tracked += gapAfterItem(index, rowGap, groupGap, derivedGap);
        }
        int contentHeight = tracked;

        // The window gives the body what is left, floored to whole items; two stat rows are the minimum the
        // specification allows before the panel itself is allowed to grow past the 12px margin.
        int budget = guiHeight - edge * 2 - fixed;
        int minimumBody = Math.min(contentHeight, rowHeight * 2 + rowGap);
        int viewportHeight = minimumBody;
        for (int index = 0; index < itemCount; index++) {
            int bottom = itemStart[index] + itemHeight[index];
            if (bottom <= budget) viewportHeight = bottom;
            else break;
        }
        boolean scrollable = contentHeight > viewportHeight;
        int maxScrollOffset = 0;
        if (scrollable) {
            // The furthest position is the first one whose window still holds the last item whole, so the
            // skill multiplier at the bottom of the derived block can always be scrolled into sight.
            for (int index = 0; index < itemCount; index++) {
                if (contentHeight - itemStart[index] <= viewportHeight) {
                    maxScrollOffset = index;
                    break;
                }
            }
        }
        int scrollOffset = Math.max(0, Math.min(maxScrollOffset, requestedScroll));

        // Only items that fit completely are drawn and hit: a row is either on screen or it is not, which is
        // what keeps "what is visible" and "what can be clicked" one decision.
        int windowTop = itemStart[scrollOffset];
        int windowBottom = windowTop + viewportHeight;
        List<Integer> drawnItems = new ArrayList<>();
        for (int index = scrollOffset; index < itemCount; index++) {
            int top = itemStart[index];
            if (top >= windowBottom) break;
            if (top + itemHeight[index] > windowBottom) break;
            drawnItems.add(index);
        }

        int height = fixed + viewportHeight;
        // The page may exceed the design height on a very short window, but never the screen edge.
        height = Math.min(height, guiHeight - edge);
        int x = (guiWidth - width) / 2;
        int y = Math.max(edge, (guiHeight - height) / 2);

        List<Rect> levelRows = new ArrayList<>();
        List<Rect> popupRows = new ArrayList<>();
        List<Rect> popupNote = new ArrayList<>();
        List<Rect> derivedCells = new ArrayList<>(Collections.nCopies(CharacterStat.COUNT, Rect.EMPTY));
        List<StatRow> statRows = new ArrayList<>();
        List<Area> buttons = new ArrayList<>();
        List<Area> popupButtons = new ArrayList<>();
        List<Box> boxes = new ArrayList<>();
        List<String> focus = new ArrayList<>();

        int cursor = y + pad;
        Rect title = Rect.of(x + pad, cursor, inner, px(TITLE_HEIGHT, s));
        Rect close = Rect.of(title.right() - px(TITLE_HEIGHT, s), cursor, px(TITLE_HEIGHT, s), px(TITLE_HEIGHT, s));
        cursor += px(TITLE_HEIGHT, s) + px(Motion.GROUP_GAP_ABOVE, s);
        Rect rule = Rect.of(x + pad, cursor, inner, ruleHeight);
        cursor += ruleHeight + px(Motion.GROUP_GAP_BELOW, s);
        for (int index = 0; index < 3; index++) {
            levelRows.add(Rect.of(x + pad, cursor + index * (line + rowGap), inner, line));
        }
        cursor += levelBlock + px(Motion.GROUP_GAP_ABOVE, s);
        Rect unspentRow = Rect.of(x + pad, cursor, inner, unspent);
        cursor += unspent + groupGap;

        // ---- the one scrolling viewport ----------------------------------------------------------------
        Rect statViewport = Rect.of(x + pad, cursor, inner, viewportHeight);
        int rowWidth = inner - (scrollable ? px(ScrollList.BAR_WIDTH + ScrollList.BAR_GAP, s) : 0);
        Rect derivedTitle = Rect.EMPTY;
        int cellGap = px(8, s);
        int cellWidth = (inner - cellGap * (derivedColumns - 1)) / derivedColumns;
        for (int item : drawnItems) {
            int top = cursor + (itemStart[item] - windowTop);
            if (item < CharacterStat.COUNT) {
                CharacterStat stat = CharacterStat.of(item);
                Rect row = Rect.of(statViewport.x(), top, rowWidth, rowHeight);
                int button = Math.min(px(ICON_BUTTON, s), Math.max(1, rowHeight - px(ROW_PAD, s) * 2));
                Rect plus = Rect.of(row.right() - px(ROW_PAD, s) - button, row.y() + (rowHeight - button) / 2,
                        button, button);
                Rect minus = Rect.of(plus.x() - px(Motion.ROW_GAP, s) - button, plus.y(), button, button);
                statRows.add(new StatRow(stat, row, minus, plus));
            } else if (item == CharacterStat.COUNT) {
                derivedTitle = Rect.of(statViewport.x(), top, rowWidth, derivedTitleHeight);
            } else {
                int cellRow = item - CharacterStat.COUNT - 1;
                for (int column = 0; column < derivedColumns; column++) {
                    int dataIndex = cellRow * derivedColumns + column;
                    if (dataIndex >= CharacterStat.COUNT) break;
                    derivedCells.set(dataIndex, Rect.of(statViewport.x() + column * (cellWidth + cellGap), top,
                            cellWidth, derivedCellHeight));
                }
            }
        }
        Rect scrollBar = scrollable
                ? Rect.of(statViewport.right() - px(ScrollList.BAR_WIDTH, s), statViewport.y(),
                px(ScrollList.BAR_WIDTH, s), statViewport.h())
                : Rect.EMPTY;
        // The 1px boundary rules sit in gaps that already exist, so scrolling costs no extra height.
        Rect topRule = scrollable
                ? Rect.of(statViewport.x(), statViewport.y() - ruleHeight, statViewport.w(), ruleHeight)
                : Rect.EMPTY;
        Rect bottomRule = scrollable
                ? Rect.of(statViewport.x(), statViewport.bottom(), statViewport.w(), ruleHeight)
                : Rect.EMPTY;
        cursor += viewportHeight;

        Rect statusRow;
        cursor += rowGap;
        statusRow = Rect.of(x + pad, cursor, inner, statusHeight);
        cursor += statusHeight;
        cursor += separatorArea;
        Rect separator = Rect.of(x + pad, cursor - px(Motion.GROUP_GAP_BELOW, s) - Scale.rule(s), inner, Scale.rule(s));

        List<String> ids = List.of(CONFIRM, DISCARD, LEVEL_UP);
        boolean[] enabled = {state.canConfirm(), state.canDiscard(), state.canLevelUp()};
        if (stacked) {
            for (int index = 0; index < 3; index++) {
                Rect rect = Rect.of(x + pad, cursor + index * (buttonHeight + gap), inner, buttonHeight);
                buttons.add(new Area(ids.get(index), rect, enabled[index]));
            }
            cursor += footerHeight;
        } else {
            int levelUpX = x + pad;
            int confirmX = x + pad + inner - buttonWidth;
            int discardX = confirmX - gap - buttonWidth;
            buttons.add(new Area(LEVEL_UP, Rect.of(levelUpX, cursor, buttonWidth, buttonHeight), enabled[2]));
            buttons.add(new Area(DISCARD, Rect.of(discardX, cursor, buttonWidth, buttonHeight), enabled[1]));
            buttons.add(new Area(CONFIRM, Rect.of(confirmX, cursor, buttonWidth, buttonHeight), enabled[0]));
            cursor += buttonHeight;
        }
        Rect panel = Rect.of(x, y, width, cursor + pad - y);

        // The confirmation popup: centred on the page, one layer above everything else.
        Rect popup = Rect.EMPTY;
        Rect popupTitle = Rect.EMPTY;
        if (state.popup()) {
            int popupWidth = Math.min(px(POPUP_WIDTH, s), Math.max(px(120, s), panel.w() - px(12, s)));
            int popupPad = px(Motion.PAD, s);
            int popupInner = popupWidth - popupPad * 2;
            int noteLines = 2;
            // The two buttons stack when the longer wording does not fit beside the other one, and the popup
            // must be tall enough for whichever arrangement it uses - otherwise the second button hangs out
            // of its own panel on a short window in English.
            int popupButtonWidth = 0;
            for (String label : safe(popupLabels))
                popupButtonWidth = Math.max(popupButtonWidth, TextMetrics.width(measure.applyAsInt(label), s)
                        + px(Motion.BUTTON_PAD_X, s) * 2);
            boolean popupStacked = popupButtonWidth * 2 + gap > popupInner;
            int popupButtonBlock = popupStacked ? buttonHeight * 2 + gap : buttonHeight;
            int popupHeight = popupPad + line + px(Motion.GROUP_GAP_ABOVE, s) + Scale.rule(s)
                    + px(Motion.GROUP_GAP_BELOW, s) + line * 3 + rowGap * 2 + groupGap + line * noteLines
                    + separatorArea + popupButtonBlock + popupPad;
            int popupX = panel.x() + (panel.w() - popupWidth) / 2;
            // Centred on the page, but never pushed off the screen by a page that is itself very short.
            int popupY = Math.max(edge, Math.min(guiHeight - edge - popupHeight,
                    panel.y() + (panel.h() - popupHeight) / 2));
            popup = Rect.of(popupX, popupY, popupWidth, popupHeight);
            int popupCursor = popupY + popupPad;
            popupTitle = Rect.of(popupX + popupPad, popupCursor, popupInner, line);
            popupCursor += line + px(Motion.GROUP_GAP_ABOVE, s) + Scale.rule(s) + px(Motion.GROUP_GAP_BELOW, s);
            for (int index = 0; index < 3; index++)
                popupRows.add(Rect.of(popupX + popupPad, popupCursor + index * (line + rowGap), popupInner, line));
            popupCursor += line * 3 + rowGap * 2 + groupGap;
            for (int index = 0; index < noteLines; index++)
                popupNote.add(Rect.of(popupX + popupPad, popupCursor + index * line, popupInner, line));
            popupCursor += line * noteLines + separatorArea;
            if (popupStacked) {
                popupButtons.add(new Area(POPUP_CONFIRM, Rect.of(popupX + popupPad, popupCursor, popupInner,
                        buttonHeight), state.canLevelUp()));
                popupButtons.add(new Area(POPUP_CANCEL, Rect.of(popupX + popupPad, popupCursor + buttonHeight + gap,
                        popupInner, buttonHeight), true));
            } else {
                int confirmX = popupX + popupPad + popupInner - popupButtonWidth;
                popupButtons.add(new Area(POPUP_CANCEL, Rect.of(confirmX - gap - popupButtonWidth, popupCursor,
                        popupButtonWidth, buttonHeight), true));
                popupButtons.add(new Area(POPUP_CONFIRM, Rect.of(confirmX, popupCursor, popupButtonWidth,
                        buttonHeight), state.canLevelUp()));
            }
        }

        // Hit order is top-most first: the popup, then the page's own controls.
        //
        // A stat button is pressable exactly when the page draws it enabled: the two per-stat flags come from
        // the model's own canAdd/canRemove, so a "−" with nothing drafted, or a "+" with no point left or a stat
        // at its cap, is neither hit nor captured nor sent - the press never starts, instead of reaching the
        // model and being ignored there.
        boolean editable = !state.readOnly() && !state.waiting();
        if (state.popup()) {
            for (Area area : popupButtons) boxes.add(area.box());
        } else {
            for (StatRow row : statRows) {
                boolean minusEnabled = editable && state.canRemove(row.stat());
                boolean plusEnabled = editable && state.canAdd(row.stat());
                boxes.add(new Box(minusId(row.stat()), row.minus(), minusEnabled, true));
                boxes.add(new Box(plusId(row.stat()), row.plus(), plusEnabled, true));
                // The focus order is the same decision: a control that cannot act takes no Tab stop either.
                if (minusEnabled) focus.add(minusId(row.stat()));
                if (plusEnabled) focus.add(plusId(row.stat()));
            }
            for (Area area : buttons) boxes.add(area.box());
            boxes.add(new Box(CLOSE, close, true, true));
        }

        if (!state.readOnly()) {
            focus.add(LEVEL_UP);
            focus.add(DISCARD);
            focus.add(CONFIRM);
        }
        focus.add(CLOSE);

        return new CharacterLayout(guiWidth, guiHeight, s, narrow, panel, title, close, rule, List.copyOf(levelRows),
                unspentRow, statViewport, List.copyOf(statRows), scrollable, itemCount, drawnItems.size(),
                scrollOffset, maxScrollOffset, scrollBar, topRule, bottomRule, derivedTitle,
                List.copyOf(derivedCells), statusRow, separator, List.copyOf(buttons), popup, popupTitle,
                List.copyOf(popupRows), List.copyOf(popupNote), List.copyOf(popupButtons), List.copyOf(boxes),
                List.copyOf(focus));
    }

    /**
     * The gap that follows one body item: rows are spaced by the row gap, the table is separated from the
     * derived block by the group gap, and the derived cells use their own grid gap.
     */
    private static int gapAfterItem(int index, int rowGap, int groupGap, int derivedGap) {
        if (index < CharacterStat.COUNT - 1) return rowGap;
        if (index == CharacterStat.COUNT - 1) return groupGap;
        return derivedGap;
    }

    private static List<String> safe(List<String> labels) { return labels == null ? List.of() : labels; }

    private static int px(double design, double scale) { return Scale.effectivePx(design, scale); }

    /** The control at a point, popup first; null when the point is on no control. */
    public Box hit(double guiX, double guiY) {
        for (Box box : boxes) {
            if (box.contains(guiX, guiY)) return box;
        }
        return null;
    }

    /** True when the point would press an enabled control. */
    public Box hitEnabled(double guiX, double guiY) {
        Box box = hit(guiX, guiY);
        return box != null && box.enabled() ? box : null;
    }

    /**
     * The visible stat row at a point, or null.
     *
     * <p>A row is passive: hovering it explains the stat, it does not press anything. Only rows this frame
     * really drew can be found, so a row that scrolled out has neither a hover nor a hit.</p>
     */
    public StatRow rowAt(double guiX, double guiY) {
        for (StatRow row : statRows) {
            if (row.row().contains(guiX, guiY)) return row;
        }
        return null;
    }

    /** The stat whose derived cell is at a point, or null; hidden cells are {@link Rect#EMPTY}. */
    public CharacterStat derivedCellAt(double guiX, double guiY) {
        for (CharacterStat stat : CharacterStat.values()) {
            Rect cell = derivedCells.get(stat.index());
            if (cell.w() > 0 && cell.h() > 0 && cell.contains(guiX, guiY)) return stat;
        }
        return null;
    }

    /** One scroll notch: exactly one body item, and only while the body really scrolls. */
    public int scrolled(int direction) {
        if (!scrollable || direction == 0) return scrollOffset;
        return Math.max(0, Math.min(maxScrollOffset, scrollOffset + Integer.signum(direction)));
    }

    /** The focusable control after (or before) {@code current}, wrapping; null when nothing is focusable. */
    public String focusAfter(String current, boolean backwards) {
        if (focusOrder.isEmpty()) return null;
        int index = current == null ? -1 : focusOrder.indexOf(current);
        if (index < 0) return backwards ? focusOrder.getLast() : focusOrder.getFirst();
        int step = backwards ? -1 : 1;
        int next = Math.floorMod(index + step, focusOrder.size());
        return focusOrder.get(next);
    }

    /** The rectangle of a control by id, or {@link Rect#EMPTY}. */
    public Rect rectOf(String id) {
        for (Box box : boxes) {
            if (box.id().equals(id)) return box.rect();
        }
        return Rect.EMPTY;
    }

    /**
     * Where one row's "+n" chip goes: left of the "−" with the specification's 4px gap.
     *
     * <p>Measuring it from the "+" instead made the chip reach across the "−" and cover a control the player
     * still has to be able to read; the geometry lives here so the drawing and the check use one rule.</p>
     */
    public Rect chipRect(StatRow row, int chipWidth) {
        if (row == null) return Rect.EMPTY;
        int width = Math.max(1, chipWidth);
        int height = px(Motion.CHIP_HEIGHT, scale);
        int x = row.minus().x() - px(4, scale) - width;
        return Rect.of(x, row.row().y() + (row.row().h() - height) / 2, width, height);
    }

    /**
     * Whether the control with this id would act right now.
     *
     * <p>Drawing reads this instead of asking the model again: the page paints the button disabled exactly
     * when the pointer cannot press it, so appearance and hit test can never drift apart.</p>
     */
    public boolean enabled(String id) {
        for (Box box : boxes) {
            if (box.id().equals(id)) return box.enabled();
        }
        return false;
    }
}
