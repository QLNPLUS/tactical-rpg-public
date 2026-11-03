package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;
import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.widget.PointerCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's geometry, checked as pure numbers in the sizes the acceptance list names.
 *
 * <p>Widths come from a fixed measurement function (the specification's 6 px per Latin glyph, 9 px per CJK
 * glyph), so the same numbers can be re-derived without a game window. What is pinned here is the part that
 * would otherwise only be visible on a screenshot: the interface size the player chose is never lowered, the
 * fixed parts always fit, the body - the six stat rows <em>and</em> the derived block - scrolls instead of
 * shrinking, an item that scrolled out cannot be hit, and the three buttons stack when the wording is long.</p>
 */
class CharacterLayoutTest {

    /** The specification's own estimate: 6 px per Latin glyph, 9 px per CJK glyph. */
    private static final ToIntFunction<String> MEASURE = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            char glyph = text.charAt(index);
            width += glyph < 0x2E80 ? 6 : 9;
        }
        return width;
    };

    private static final List<String> ZH = List.of("确认加点", "取消草稿", "升级");
    private static final List<String> EN = List.of("Confirm points", "Discard draft", "Level up");
    private static final List<String> POPUP = List.of("Confirm level up", "Cancel");
    private static final List<List<String>> LANGUAGES = List.of(ZH, EN);
    /** Every stat editable, which is the state most of the geometry tests describe. */
    private static final int ALL = CharacterLayout.Content.mask(true, true, true, true, true, true);

    private static CharacterLayout build(int guiWidth, int guiHeight, double scale, List<String> labels,
                                         boolean popup, int scroll) {
        return CharacterLayout.build(guiWidth, guiHeight, scale,
                new CharacterLayout.Content(true, false, false, popup, false, true, true, true, ALL, ALL),
                MEASURE, labels, POPUP, scroll);
    }

    /** A page whose per-stat flags are supplied by the caller, i.e. exactly what the model reported. */
    private static CharacterLayout withStats(int canAddMask, int canRemoveMask, boolean readOnly, boolean waiting) {
        return CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, readOnly, waiting, false, false, true, true, true,
                        canAddMask, canRemoveMask), MEASURE, ZH, POPUP, 0);
    }

    private static CharacterLayout open(int guiWidth, int guiHeight, double scale, List<String> labels) {
        return build(guiWidth, guiHeight, scale, labels, false, 0);
    }

    /**
     * The defect this pins: the page used to walk its own scale down in 5% steps until the footer fitted,
     * silently overriding the type size the player picked. The frozen specification instead gives the body a
     * scrolling viewport, so the preset must come back untouched at every size in the matrix.
     */
    @Test void theRequestedScaleIsNeverLowered() {
        int[][] windows = {{640, 360}, {427, 240}};
        for (int[] window : windows) {
            for (double scale : new double[]{1.00D, 1.15D}) {
                for (List<String> labels : LANGUAGES) {
                    String where = window[0] + "x" + window[1] + "@" + scale + " " + labels.get(0);
                    CharacterLayout layout = open(window[0], window[1], scale, labels);
                    assertEquals(scale, layout.scale(), 1.0E-9D, "the chosen interface size is kept: " + where);
                    assertTrue(layout.panel().y() >= 0, "panel stays on screen: " + where);
                    assertTrue(layout.panel().bottom() <= window[1], "panel stays inside the window: " + where);
                    assertTrue(layout.panel().right() <= window[0], "panel stays inside the window: " + where);
                    assertTrue(layout.title().h() > 0 && layout.separator().h() > 0, "title and rule exist: " + where);
                    assertEquals(3, layout.levelRows().size(), "three level rows: " + where);
                    assertEquals(CharacterStat.COUNT, layout.derivedCells().size(),
                            "the six derived cells always have an entry: " + where);
                    for (CharacterLayout.Area area : layout.buttons()) {
                        assertTrue(area.rect().w() > 0 && area.rect().h() > 0, "button has size: " + where);
                        assertTrue(area.rect().bottom() <= layout.panel().bottom(),
                                "the footer is complete inside the panel: " + where);
                        assertTrue(area.rect().h() >= 10, "the footer keeps button height: " + where);
                    }
                }
            }
        }
    }

    /** The confirmation must stay on screen even at the smallest accepted window and preset. */
    @Test void theConfirmationStaysOnScreenAndInsideThePage() {
        for (List<String> labels : LANGUAGES) {
            CharacterLayout opened = build(427, 240, 1.15D, labels, true, 0);
            assertTrue(opened.popup().w() > 0, "the confirmation has a box");
            assertTrue(opened.popup().y() >= 0 && opened.popup().bottom() <= 240,
                    "the confirmation stays inside the window: " + labels.get(0));
            assertTrue(opened.popup().x() >= 0 && opened.popup().right() <= 427);
            assertEquals(2, opened.popupButtons().size());
            for (CharacterLayout.Area area : opened.popupButtons()) {
                assertTrue(area.rect().bottom() <= opened.popup().bottom(), "its buttons are inside it");
            }
        }
    }

    /**
     * The body is one viewport: the attribute table and the derived block scroll together, and the last
     * derived cell - the skill multiplier - can always be scrolled into sight.
     */
    @Test void theWholeBodyScrollsAndTheLastDerivedCellIsReachable() {
        CharacterLayout tall = open(640, 360, 1.00D, ZH);
        assertFalse(tall.scrollable(), "640x360 at 100% shows the whole body");
        assertEquals(CharacterStat.COUNT, tall.statRows().size());
        assertEquals(CharacterStat.COUNT, visibleDerived(tall), "and all six derived cells");

        CharacterLayout shortWindow = open(427, 240, 1.15D, ZH);
        assertTrue(shortWindow.scrollable(), "427x240 at 115% scrolls the body");
        assertEquals(CharacterStat.COUNT, visibleDerived(shortWindow) + hiddenDerived(shortWindow),
                "every derived cell has exactly one state");

        // The tail of the body is reached by scrolling, and there the skill multiplier is drawn whole.
        CharacterLayout bottom = build(427, 240, 1.15D, ZH, false, shortWindow.maxScrollOffset());
        assertEquals(shortWindow.maxScrollOffset(), bottom.scrollOffset(), "the offset stops at the end");
        assertTrue(bottom.visibleItems() >= 2, "the viewport always holds at least two whole body items");
        Rect skillPower = bottom.derivedCells().get(CharacterStat.INTELLIGENCE.index());
        assertTrue(skillPower.w() > 0 && skillPower.h() > 0, "the skill multiplier is visible at the end");
        assertTrue(skillPower.bottom() <= bottom.statViewport().bottom(), "and it is inside the viewport");
        assertTrue(bottom.derivedTitle().w() > 0, "the derived heading is in the same viewport");

        // Everything the body holds is visible at some offset: attributes first, derived block last.
        java.util.Set<Integer> seenRows = new java.util.HashSet<>();
        java.util.Set<Integer> seenCells = new java.util.HashSet<>();
        for (int offset = 0; offset <= shortWindow.maxScrollOffset(); offset++) {
            CharacterLayout step = build(427, 240, 1.15D, ZH, false, offset);
            for (CharacterLayout.StatRow row : step.statRows()) seenRows.add(row.stat().index());
            for (CharacterStat stat : CharacterStat.values()) {
                if (step.derivedCells().get(stat.index()).w() > 0) seenCells.add(stat.index());
            }
        }
        assertEquals(CharacterStat.COUNT, seenRows.size(), "every attribute row is reachable");
        assertEquals(CharacterStat.COUNT, seenCells.size(), "every derived cell is reachable");
    }

    /** A row or cell the viewport did not reach has no rectangle, no hover and no hit box. */
    @Test void anItemThatScrolledOutCannotBeHitOrExplained() {
        CharacterLayout layout = open(427, 240, 1.15D, ZH);
        assertTrue(layout.scrollable());
        CharacterStat hidden = CharacterStat.of(layout.statRows().getLast().stat().index() + 1);
        assertEquals(Rect.EMPTY, layout.rectOf(CharacterLayout.minusId(hidden)), "a hidden row has no hit box");
        assertNull(layout.hit(layout.statViewport().x() + 2, layout.statViewport().bottom() + 1),
                "nothing below the viewport is a control of a hidden row");
        for (CharacterStat stat : CharacterStat.values()) {
            Rect cell = layout.derivedCells().get(stat.index());
            if (cell.w() > 0) continue;
            assertNull(layout.derivedCellAt(layout.statViewport().x() + 2, layout.statViewport().y() + 1),
                    "a hidden derived cell is not hoverable: " + stat);
        }
        CharacterStat visible = layout.statRows().getFirst().stat();
        Box plus = layout.hitEnabled(layout.statRows().getFirst().plus().x() + 1,
                layout.statRows().getFirst().plus().y() + 1);
        assertNotNull(plus, "a visible row's button is hittable");
        assertEquals(CharacterLayout.plusId(visible), plus.id());
        assertNotNull(layout.rowAt(layout.statRows().getFirst().row().x() + 2,
                layout.statRows().getFirst().row().y() + 2), "a visible row explains itself");
        assertNull(layout.rowAt(10, layout.statViewport().bottom() + 1), "nothing below the viewport is a row");
        assertNull(layout.derivedCellAt(10, layout.statViewport().bottom() + 1));
    }

    @Test void oneNotchMovesExactlyOneItemAndStopsAtTheEnds() {
        CharacterLayout layout = open(427, 240, 1.15D, ZH);
        assertEquals(layout.scrollOffset() + 1, layout.scrolled(1), "one notch moves one item");
        assertEquals(layout.scrollOffset(), layout.scrolled(-1), "the first item never goes above the top");
        int maximum = layout.maxScrollOffset();
        assertEquals(layout.scrollOffset() + 1, layout.scrolled(5), "a notch is one item, whatever the delta");
        assertEquals(maximum, build(427, 240, 1.15D, ZH, false, 99).scrollOffset(),
                "a huge request is clamped, not stepped");
        CharacterLayout atEnd = build(427, 240, 1.15D, ZH, false, maximum);
        assertEquals(maximum, atEnd.scrolled(1), "the last item stays visible");
        CharacterLayout tall = open(640, 360, 1.00D, ZH);
        assertEquals(0, tall.scrolled(1), "a page that fits does not scroll");
    }

    /** Growing the window back must clamp a scroll offset that no longer exists, leaving no blank tail. */
    @Test void aWiderWindowClampsTheScrollOffset() {
        CharacterLayout scrolled = build(427, 240, 1.15D, ZH, false, 4);
        CharacterLayout grown = build(640, 360, 1.15D, ZH, false, scrolled.scrollOffset());
        assertFalse(grown.scrollable(), "the taller window holds the whole body");
        assertEquals(0, grown.scrollOffset(), "a list that stopped scrolling returns to the top");
    }

    /** Scrolling cancels the press it interrupts, so the release that follows activates nothing. */
    @Test void aScrollCancelsThePressItInterrupts() {
        CharacterLayout layout = open(427, 240, 1.15D, ZH);
        PointerCapture capture = new PointerCapture();
        Box plus = layout.hitEnabled(layout.statRows().getFirst().plus().x() + 1,
                layout.statRows().getFirst().plus().y() + 1);
        assertNotNull(plus);
        capture.press(plus.id());
        // The page's wheel handler does exactly this before it moves the body.
        capture.cancel();
        assertNull(capture.release(plus.id()), "the release after a scroll activates nothing");
    }

    @Test void longEnglishButtonsStackVertically() {
        CharacterLayout english = open(640, 360, 1.00D, EN);
        CharacterLayout chinese = open(640, 360, 1.00D, ZH);
        assertEquals(3, english.buttons().size());
        Rect first = english.buttons().get(1).rect();
        Rect second = english.buttons().get(2).rect();
        assertTrue(second.y() > first.y(), "the English buttons are stacked, not side by side");
        Rect zhFirst = chinese.buttons().get(1).rect();
        Rect zhSecond = chinese.buttons().get(2).rect();
        assertEquals(zhFirst.y(), zhSecond.y(), "the Chinese buttons fit on one row");
        assertTrue(zhSecond.x() > zhFirst.x());
    }

    @Test void aNarrowPageSplitsRowsAndUsesOneDerivedColumn() {
        // 248 design pixels do not fit a 200 px window, so the page shrinks to the window and turns narrow.
        CharacterLayout layout = open(200, 360, 1.00D, ZH);
        assertTrue(layout.narrow(), "a window narrower than 220 design pixels uses the narrow form");
        assertTrue(layout.statRows().getFirst().row().h() > layout.statRows().getFirst().minus().h() * 1.5D,
                "narrow rows are two lines tall");
        Rect firstCell = layout.derivedCells().get(0);
        Rect secondCell = layout.derivedCells().get(1);
        assertTrue(firstCell.w() > 0 && secondCell.w() > 0, "the first derived cells are inside the viewport");
        assertEquals(firstCell.x(), secondCell.x(), "the derived grid is one column");
        assertTrue(secondCell.y() > firstCell.y());
        assertTrue(layout.panel().w() <= 200 - 12, "the page respects the window edge");
        // One column means one item per derived row: the last one is still reachable by scrolling.
        CharacterLayout bottom = build(200, 360, 1.00D, ZH, false, layout.maxScrollOffset());
        Rect last = bottom.derivedCells().get(CharacterStat.COUNT - 1);
        assertTrue(last.w() > 0, "the last derived cell is reachable in the one-column grid");
    }

    @Test void onlyTheControlThatWasPressedActivates() {
        CharacterLayout layout = open(640, 360, 1.00D, ZH);
        PointerCapture capture = new PointerCapture();
        capture.press(CharacterLayout.DISCARD);
        assertNull(capture.release(CharacterLayout.CONFIRM), "a release over another control activates nothing");
        capture.press(CharacterLayout.LEVEL_UP);
        assertEquals(CharacterLayout.LEVEL_UP, capture.release(CharacterLayout.LEVEL_UP));
        assertTrue(layout.focusOrder().contains(CharacterLayout.LEVEL_UP));
    }

    @Test void thePopupSitsAboveThePageAndOwnsTheHitOrder() {
        CharacterLayout closed = open(640, 360, 1.00D, ZH);
        CharacterLayout opened = build(640, 360, 1.00D, ZH, true, 0);
        assertEquals(0, closed.popup().w());
        assertTrue(opened.popup().w() > 0, "the confirmation has a box");
        assertTrue(opened.panel().inset(0).intersects(opened.popup()), "the popup is inside the page");
        assertEquals(2, opened.popupButtons().size());
        Box hit = opened.hit(opened.popupButtons().getLast().rect().x() + 1,
                opened.popupButtons().getLast().rect().y() + 1);
        assertNotNull(hit);
        assertTrue(hit.id().startsWith("character-popup"), "the popup is hit before the page controls");
        // A page control under the popup must not be reachable while the confirmation is open.
        for (Box box : opened.boxes()) assertTrue(box.id().startsWith("character-popup"),
                "only popup controls are interactive while it is open");
    }

    @Test void focusFollowsTheSpecifiedOrderAndWraps() {
        CharacterLayout layout = open(640, 360, 1.00D, ZH);
        String first = layout.focusOrder().getFirst();
        assertEquals(CharacterLayout.minusId(CharacterStat.VIGOR), first, "the table starts at the first row");
        assertEquals(CharacterLayout.plusId(CharacterStat.VIGOR), layout.focusOrder().get(1), "minus then plus");
        assertTrue(layout.focusOrder().containsAll(List.of(CharacterLayout.LEVEL_UP, CharacterLayout.DISCARD,
                CharacterLayout.CONFIRM, CharacterLayout.CLOSE)));
        assertEquals(CharacterLayout.CLOSE, layout.focusOrder().getLast(), "close is last");
        assertEquals(CharacterLayout.CLOSE, layout.focusAfter(null, true), "shift-tab from nothing wraps to close");
        assertEquals(first, layout.focusAfter(CharacterLayout.CLOSE, false), "tab from close wraps to the table");
    }

    @Test void aReadOnlyPageKeepsItsControlsButDisablesThem() {
        CharacterLayout readOnly = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, true, false, false, false, false, false, false, ALL, ALL), MEASURE, ZH, POPUP, 0);
        for (CharacterLayout.Area area : readOnly.buttons()) assertFalse(area.enabled(), "no button acts: " + area.id());
        for (Box box : readOnly.boxes()) {
            if (box.id().equals(CharacterLayout.CLOSE)) assertTrue(box.enabled(), "closing always works");
            else assertFalse(box.enabled(), "everything else is disabled: " + box.id());
        }
        assertFalse(readOnly.focusOrder().contains(CharacterLayout.CONFIRM), "a read-only page does not focus actions");
    }

    @Test void aWaitingPageDisablesRepeatSubmits() {
        CharacterLayout waiting = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, false, true, false, false, false, false, false, ALL, ALL), MEASURE, ZH, POPUP, 0);
        for (Box box : waiting.boxes()) {
            if (box.id().equals(CharacterLayout.CLOSE)) continue;
            assertFalse(box.enabled(), "nothing can be submitted twice: " + box.id());
        }
    }

    /**
     * The footer is gated by the model's own conditions, and the box the pointer can press is the same decision
     * as the box the page draws: a button that looks available and then does nothing is the defect this pins.
     */
    @Test void theFooterButtonsFollowTheModelsRealConditions() {
        CharacterLayout nothing = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, false, false, false, false, false, false, false, ALL, ALL), MEASURE, ZH,
                POPUP, 0);
        for (CharacterLayout.Area area : nothing.buttons()) {
            assertFalse(area.enabled(), "no XP and no draft: nothing to confirm or level up: " + area.id());
            Box box = box(nothing, area.id());
            assertNotNull(box, "the button still exists: " + area.id());
            assertFalse(box.enabled(), "hit and appearance agree: " + area.id());
        }
        assertTrue(nothing.hitEnabled(nothing.close().x() + 1, nothing.close().y() + 1) != null,
                "closing always works");

        CharacterLayout onlyDraft = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, false, false, false, false, false, true, true, ALL, ALL), MEASURE, ZH,
                POPUP, 0);
        assertTrue(box(onlyDraft, CharacterLayout.CONFIRM).enabled(), "a confirmable draft can be confirmed");
        assertTrue(box(onlyDraft, CharacterLayout.DISCARD).enabled(), "and discarded");
        assertFalse(box(onlyDraft, CharacterLayout.LEVEL_UP).enabled(), "but the level-up still needs its XP");

        CharacterLayout onlyXp = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, false, false, false, false, true, false, false, ALL, ALL), MEASURE, ZH,
                POPUP, 0);
        assertTrue(box(onlyXp, CharacterLayout.LEVEL_UP).enabled(), "enough XP can be spent");
        assertFalse(box(onlyXp, CharacterLayout.CONFIRM).enabled(), "an empty draft cannot");
        assertFalse(box(onlyXp, CharacterLayout.DISCARD).enabled(), "and there is nothing to discard");
    }

    @Test void anInertPopupConfirmIsNotPressable() {
        CharacterLayout popup = CharacterLayout.build(640, 360, 1.00D,
                new CharacterLayout.Content(true, false, false, true, false, false, false, false, ALL, ALL), MEASURE, ZH,
                POPUP, 0);
        Box confirm = box(popup, CharacterLayout.POPUP_CONFIRM);
        assertNotNull(confirm);
        assertFalse(confirm.enabled(), "the popup commits the same transaction, so it follows the same condition");
        assertTrue(box(popup, CharacterLayout.POPUP_CANCEL).enabled(), "cancelling always works");
    }

    /**
     * The per-stat controls follow the model, not only the page's read-only flag: this is the defect the
     * review found - the box could be pressed while the page drew it disabled.
     */
    @Test void anInertStatButtonIsNotPressable() {
        CharacterLayout noPoints = withStats(0, 0, false, false);
        for (CharacterStat stat : CharacterStat.values()) {
            assertFalse(box(noPoints, CharacterLayout.plusId(stat)).enabled(), "no unspent point: + is inert: " + stat);
            assertFalse(box(noPoints, CharacterLayout.minusId(stat)).enabled(), "no draft: − is inert: " + stat);
            CharacterLayout.StatRow row = rowOf(noPoints, stat);
            assertNull(noPoints.hitEnabled(row.plus().x() + 1, row.plus().y() + 1), "an inert + never captures");
            assertNull(noPoints.hitEnabled(row.minus().x() + 1, row.minus().y() + 1), "an inert − never captures");
        }

        int vigorOnly = 1 << CharacterStat.VIGOR.index();
        CharacterLayout drafted = withStats(ALL, vigorOnly, false, false);
        assertTrue(box(drafted, CharacterLayout.minusId(CharacterStat.VIGOR)).enabled(), "the drafted stat can undo");
        assertFalse(box(drafted, CharacterLayout.minusId(CharacterStat.MIND)).enabled(), "another stat cannot");

        int capped = ALL & ~(1 << CharacterStat.STRENGTH.index());
        CharacterLayout atCap = withStats(capped, ALL, false, false);
        assertFalse(box(atCap, CharacterLayout.plusId(CharacterStat.STRENGTH)).enabled(), "a stat at its cap is inert");
        assertTrue(box(atCap, CharacterLayout.plusId(CharacterStat.VIGOR)).enabled(), "the others still work");

        // The two global states still win over every per-stat flag.
        for (CharacterLayout global : new CharacterLayout[]{withStats(ALL, ALL, true, false), withStats(ALL, ALL, false, true)}) {
            for (CharacterStat stat : CharacterStat.values()) {
                assertFalse(box(global, CharacterLayout.plusId(stat)).enabled(), "read-only or waiting: + inert: " + stat);
                assertFalse(box(global, CharacterLayout.minusId(stat)).enabled(), "read-only or waiting: − inert: " + stat);
            }
        }
    }

    /** Tab order is the same decision: an inert stat button takes no stop, so Enter cannot fire it either. */
    @Test void focusSkipsInertStatButtons() {
        CharacterLayout noPoints = withStats(0, 0, false, false);
        for (CharacterStat stat : CharacterStat.values()) {
            assertFalse(noPoints.focusOrder().contains(CharacterLayout.plusId(stat)), "inert + takes no focus: " + stat);
            assertFalse(noPoints.focusOrder().contains(CharacterLayout.minusId(stat)), "inert − takes no focus: " + stat);
        }
        assertTrue(noPoints.focusOrder().contains(CharacterLayout.CLOSE), "closing stays reachable");
        CharacterLayout drafted = withStats(ALL, 1 << CharacterStat.VIGOR.index(), false, false);
        assertTrue(drafted.focusOrder().contains(CharacterLayout.minusId(CharacterStat.VIGOR)), "a usable − keeps its stop");
        assertTrue(drafted.focusOrder().contains(CharacterLayout.plusId(CharacterStat.MIND)), "a usable + keeps its stop");
    }

    /** The "+n" chip sits left of the "−" with a real gap, never over either icon button. */
    @Test void theChipSitsLeftOfTheMinus() {
        for (double scale : new double[]{1.00D, 1.15D}) {
            CharacterLayout layout = open(640, 360, scale, ZH);
            CharacterLayout.StatRow row = rowOf(layout, CharacterStat.VIGOR);
            Rect chip = layout.chipRect(row, 12);
            assertTrue(chip.w() > 0 && chip.h() > 0, "the chip has a size at " + scale);
            assertTrue(chip.right() <= row.minus().x(), "the chip ends at or before the −: " + scale);
            assertFalse(chip.intersects(row.minus()), "the chip never covers the −: " + scale);
            assertFalse(chip.intersects(row.plus()), "the chip never covers the +: " + scale);
            assertTrue(chip.right() > row.row().x() + 100, "and it stays right of the numbers: " + scale);
        }
    }

    private static CharacterLayout.StatRow rowOf(CharacterLayout layout, CharacterStat stat) {
        for (CharacterLayout.StatRow row : layout.statRows()) {
            if (row.stat() == stat) return row;
        }
        return null;
    }

    private static int visibleDerived(CharacterLayout layout) {
        int count = 0;
        for (Rect cell : layout.derivedCells()) if (cell.w() > 0) count++;
        return count;
    }

    private static int hiddenDerived(CharacterLayout layout) {
        return CharacterStat.COUNT - visibleDerived(layout);
    }

    private static Box box(CharacterLayout layout, String id) {
        for (Box box : layout.boxes()) if (box.id().equals(id)) return box;
        return null;
    }
}
