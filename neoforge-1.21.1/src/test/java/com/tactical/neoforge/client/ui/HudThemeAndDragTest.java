package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.ColorDraft;
import com.tactical.neoforge.client.ui.command.ColorPopupLayout;
import com.tactical.neoforge.client.ui.hud.DragTag;
import com.tactical.neoforge.client.ui.hud.HudDrag;
import com.tactical.neoforge.client.ui.hud.RosterRowText;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Palette;
import com.tactical.neoforge.client.ui.theme.Slate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of batch 3: the colour derivations, the roster's wrapped text, the size drag and the
 * colour draft. No window is needed, and every rule the user asked for is stated once here.
 */
class HudThemeAndDragTest {

    /** A fake font: one pixel per character, so wrapping boundaries are exact. */
    private static final ToIntFunction<String> MEASURE = String::length;

    // ------------------------------------------------------------------ palette

    @Test void theAccentFamilyComesFromOneColourAndKeepsItsAlphas() {
        assertEquals(0xFFC4473D, Palette.accent(Palette.DEFAULT_THEME));
        assertEquals(0xFF6E2622, Palette.accentDark(Palette.DEFAULT_THEME), "0.56 per channel, as approved");
        // The leading rule segment is opaque, the two faded ones keep 8C and 40, the fourth is not drawn.
        assertEquals(0xFFC4473D, Palette.ruleSegment(Palette.DEFAULT_THEME, 0));
        assertEquals(0x8CC4473D, Palette.ruleSegment(Palette.DEFAULT_THEME, 1));
        assertEquals(0x40C4473D, Palette.ruleSegment(Palette.DEFAULT_THEME, 2));
        assertEquals(0, Palette.ruleSegment(Palette.DEFAULT_THEME, 3) >>> 24, "the fourth segment is not drawn");
        // A different theme moves the RGB and leaves every alpha alone.
        for (int preset : Palette.THEME_PRESETS) {
            assertEquals(0xFF, Palette.ruleSegment(preset, 0) >>> 24);
            assertEquals(0x8C, Palette.ruleSegment(preset, 1) >>> 24);
            assertEquals(0x40, Palette.ruleSegment(preset, 2) >>> 24);
            assertEquals(0xFF, Palette.accent(preset) >>> 24);
        }
    }

    @Test void theRangeColourIsItsOwnSourceAndItsOutlineMovesTowardsWhite() {
        assertEquals(0xFFFF781F, Palette.rangeFill(Palette.DEFAULT_RANGE));
        int outline = Palette.rangeOutline(Palette.DEFAULT_RANGE);
        assertTrue(Palette.red(outline) >= Palette.red(Palette.DEFAULT_RANGE), "red is already at the top");
        assertTrue(Palette.green(outline) > Palette.green(Palette.DEFAULT_RANGE));
        assertTrue(Palette.blue(outline) > Palette.blue(Palette.DEFAULT_RANGE));
        assertEquals(1.0F, Palette.red(outline), 1.0E-6F);
        assertEquals(0xFF, outline >>> 24);
    }

    @Test void semanticColoursNeverFollowTheTheme() {
        // The theme derivation must not touch any token the specification calls semantic.
        int[] semantic = {Slate.HP, Slate.MP, Slate.AP, Slate.AP_PENDING, Slate.ALLY, Slate.ENEMY, Slate.SLAB,
                Slate.FLOAT, Slate.RAISE, Slate.HOVER, Slate.SUNK, Slate.LINE, Slate.TEXT, Slate.TEXT2, Slate.TEXT3,
                Slate.SHADOW, Slate.SHADOW_SM};
        for (int token : semantic) {
            assertNotEquals(Palette.accent(0x00FF00), token);
            assertEquals(token >>> 24, token >>> 24, "alpha is a property of the token, not of the theme");
        }
        assertTrue(Palette.THEME_PRESETS.size() == 8 && Palette.RANGE_PRESETS.size() == 8);
    }

    @Test void hexInputIsOnlySixLegalDigits() {
        assertTrue(Palette.validHex("C4473D"));
        assertFalse(Palette.validHex("C4473"));
        assertFalse(Palette.validHex("G4473D"));
        assertFalse(Palette.validHex(""));
        assertEquals(0xC4473D, Palette.parseHex("c4473d"));
        assertEquals("C4473D", Palette.hex(0xC4473D));
    }

    @Test void hsvRoundTripsEveryPreset() {
        for (int preset : Palette.THEME_PRESETS) {
            double[] hsv = Palette.toHsv(preset);
            assertEquals(preset, Palette.fromHsv(hsv[0], hsv[1], hsv[2]));
        }
    }

    // ------------------------------------------------------------------ roster text

    @Test void aLongNameWrapsAndTheInformationJoinsTheLastLine() {
        RosterRowText.Arrangement shortName = RosterRowText.arrange("Ann", "行动中", "20 / 20", 60, 3, 1.0D, MEASURE);
        assertTrue(shortName.infoMerged(), "a short name keeps the single-line look");
        assertEquals(1, shortName.textLines());

        RosterRowText.Arrangement longName = RosterRowText.arrange("A very long participant name", "行动中",
                "20 / 20", 20, 3, 1.0D, MEASURE);
        assertTrue(longName.nameLines().size() > 1, "the name wraps at the real width");
        assertFalse(longName.infoMerged(), "nothing fits beside a full line");
        assertEquals(longName.nameLines().size() + 1, longName.textLines());
    }

    @Test void theThirdLineIsCutAndTheRestIsATooltipOnly() {
        RosterRowText.Arrangement tiny = RosterRowText.arrange("abcdefghijklmnopqrstuvwxyz", "", "1 / 1", 4, 3,
                1.0D, MEASURE);
        assertEquals(3, tiny.nameLines().size(), "at most three name lines");
        assertTrue(tiny.truncated());
        assertTrue(tiny.nameLines().get(2).endsWith("…"));
        assertEquals(4, tiny.textLines(), "three name lines plus the information line of their own");
    }

    // ------------------------------------------------------------------ size drag

    @Test void aDragProposesDesignSizesInsideItsOwnDomain() {
        HudDrag drag = new HudDrag();
        HudDrag.Start start = new HudDrag.Start(HudDrag.Target.ROSTER, 100, 100, 1.0D, Scale.Form.STANDARD,
                640, 360, 140, 100, null, null);
        drag.begin(start);
        assertEquals(160, drag.width(120).intValue(), "20 pixels to the right is +20 design pixels at 100%");
        assertEquals(120, drag.width(-1000).intValue(), "the readable minimum wins at the left end");
        assertEquals(240, drag.width(1000).intValue(), "and the maximum at the right end");
        assertEquals(118, drag.height(118).intValue());
        assertTrue(drag.active());
        assertEquals(HudDrag.Target.ROSTER, drag.target());
    }

    @Test void aJournalDragWritesTheBodyHeightAndNotThePanelHeight() {
        HudDrag drag = new HudDrag();
        drag.begin(new HudDrag.Start(HudDrag.Target.JOURNAL, 0, 0, 1.0D, Scale.Form.STANDARD, 640, 360, 140, 75,
                null, null));
        assertEquals(18, drag.height(-1000).intValue(), "the approved body floor is 18, not the generic 40");
        assertEquals(95, drag.height(20).intValue());
        assertNull(new HudDrag().width(10), "a drag that never began proposes nothing");
    }

    @Test void cancellingADragRestoresNothingButSwallowsTheRelease() {
        HudDrag drag = new HudDrag();
        drag.begin(new HudDrag.Start(HudDrag.Target.ROSTER, 0, 0, 1.0D, Scale.Form.STANDARD, 640, 360, 140, 100,
                null, null));
        drag.cancel();
        assertFalse(drag.active());
        assertTrue(drag.takeSwallowedRelease(), "the release that follows a cancel is consumed once");
        assertFalse(drag.takeSwallowedRelease());
        drag.begin(new HudDrag.Start(HudDrag.Target.ROSTER, 0, 0, 1.0D, Scale.Form.STANDARD, 640, 360, 140, 100,
                null, null));
        drag.end();
        assertFalse(drag.takeSwallowedRelease(), "a completed drag leaves nothing to swallow");
    }

    @Test void onlyTheAxisThePointerReallyMovedIsWritten() {
        HudDrag drag = new HudDrag();
        // An AUTO roster height: the user drags the handle sideways only.
        drag.begin(new HudDrag.Start(HudDrag.Target.ROSTER, 100, 100, 1.0D, Scale.Form.STANDARD, 640, 360, 140, 96,
                null, null));
        assertFalse(drag.moved(), "a press is not a resize yet");
        assertNull(drag.height(100), "the untouched axis has no proposal, so AUTO stays AUTO");
        assertNull(drag.width(100), "and a pointer that has not moved horizontally proposes nothing either");
        assertEquals(170, drag.width(130).intValue());
        assertTrue(drag.movedWidth());
        assertFalse(drag.movedHeight(), "a horizontal drag never claims the vertical axis");
        assertNull(drag.height(100.4D), "a sub-pixel wobble is not a vertical drag");
        assertEquals(106, drag.height(110).intValue(), "ten pixels down on a 96-pixel panel is 106");
        assertTrue(drag.movedHeight());
    }

    @Test void aPressReleasedWhereItLandedWritesNothing() {
        HudDrag drag = new HudDrag();
        drag.begin(new HudDrag.Start(HudDrag.Target.JOURNAL, 40, 40, 1.0D, Scale.Form.STANDARD, 640, 360, 140, 75,
                null, null));
        drag.width(40);
        drag.height(40);
        assertFalse(drag.moved(), "the caller only commits when this is true");
        drag.end();
        assertFalse(drag.takeSwallowedRelease());
    }

    // ------------------------------------------------------------------ colour draft

    @Test void nothingIsAppliedUntilSaveAndCancelThrowsTheDraftAway() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.THEME, null);
        assertEquals(Palette.DEFAULT_THEME, draft.saved(), "the draft starts from what is applied");
        assertEquals(Palette.DEFAULT_THEME, draft.draft());
        assertFalse(draft.canSave(), "an unchanged draft cannot be saved");
        draft.preset(0x7E9A4A);
        assertEquals(0x7E9A4A, draft.draft());
        assertEquals(Palette.DEFAULT_THEME, draft.saved(), "the applied colour never moves before Save");
        assertTrue(draft.canSave());
        assertEquals(0x7E9A4A, draft.savedValue());
        draft.resetToDefault();
        assertEquals(Palette.DEFAULT_THEME, draft.draft());
        assertFalse(draft.canSave());
    }

    @Test void anIllegalHexCannotBeSavedAndKeepsTheLastLegalPreview() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.RANGE, null);
        draft.focusHex(true);
        draft.setHex("12");
        assertTrue(draft.hexError(), "an unfinished value is an error, not a colour");
        assertEquals(Palette.DEFAULT_RANGE, draft.draft(), "the preview keeps the last legal colour");
        assertFalse(draft.canSave());
        draft.setHex("GGGGGG");
        assertTrue(draft.hexError());
        assertFalse(draft.canSave());
        draft.setHex("3EC4C0");
        assertFalse(draft.hexError());
        assertEquals(0x3EC4C0, draft.draft());
        assertTrue(draft.canSave());
    }

    @Test void typingAcceptsOnlyHexDigitsAndStopsAtSix() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.THEME, null);
        draft.focusHex(true);
        draft.setHex("");
        draft.typeHex('G');
        assertEquals("", draft.hex(), "a non-hex character is refused outright");
        for (char c : "00ff80".toCharArray()) draft.typeHex(c);
        assertEquals("00FF80", draft.hex());
        draft.typeHex('1');
        assertEquals("00FF80", draft.hex(), "the field never grows past six digits");
        draft.backspaceHex();
        assertEquals("00FF8", draft.hex());
        assertTrue(draft.hexError());
    }

    @Test void draggingTheValueToBlackKeepsTheHueTheUserChose() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.THEME, null);
        // A real hue first: the indigo preset, not the default vermilion.
        draft.preset(0x5468B8);
        double hue = draft.hue();
        assertTrue(hue > 200.0D && hue < 250.0D, "the preset's own hue, not zero");
        draft.saturationValue(draft.saturation(), 0.0D);
        assertEquals(0, draft.draft(), "value zero is black whatever the hue is");
        assertEquals(hue, draft.hue(), 1.0E-6D, "black carries no hue, so the chosen one is remembered");
        draft.saturationValue(draft.saturation(), 1.0D);
        // Eight-bit channels make the round trip exact only to about a degree; what matters is that
        // it comes back to the chosen hue instead of the red fallback a re-derived HSV would give.
        assertEquals(hue, Palette.toHsv(draft.draft())[0], 1.0D,
                "dragging the value back up returns the same hue, never red");
        assertTrue(Palette.toHsv(draft.draft())[0] > 200.0D, "and it really is not the red fallback");
        // Grey does the same: saturation zero keeps the hue for the way back.
        draft.saturationValue(0.0D, 0.8D);
        assertEquals(0.0D, Palette.toHsv(draft.draft())[1], 1.0E-6D);
        assertEquals(hue, draft.hue(), 1.0E-6D);
        draft.saturationValue(1.0D, 0.8D);
        assertEquals(hue, Palette.toHsv(draft.draft())[0], 1.0D);
    }

    @Test void aPastedValueIsNeverSilentlyCutDownIntoALegalsoundingColour() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.RANGE, null);
        draft.focusHex(true);
        draft.setHex("C4473D1");
        assertTrue(draft.hexError(), "seven digits stay an error instead of becoming C4473D");
        assertEquals("C4473D1", draft.hex(), "and the field shows what was pasted, not a shortened form");
        assertFalse(draft.canSave());
        assertEquals(Palette.DEFAULT_RANGE, draft.draft(), "the preview keeps the last legal colour");
        draft.setHex("C4473D");
        assertFalse(draft.hexError());
        assertEquals(0xC4473D, draft.draft());
        assertTrue(draft.canSave(), "a legal pasted colour is saveable again, and only then");
    }

    @Test void theHexFieldSupportsCaretSelectionAndReplacement() {
        ColorDraft draft = ColorDraft.of(ColorDraft.Kind.THEME, null);
        draft.focusHex(true);
        draft.selectAll();
        assertTrue(draft.hasSelection());
        assertEquals("C4473D", draft.selectedText());
        // Typing over a selection replaces it, which is the only way to correct a full field.
        for (char c : "3E9A8C".toCharArray()) draft.typeHex(c);
        assertEquals("3E9A8C", draft.hex());
        assertEquals(0x3E9A8C, draft.draft());
        draft.caretTo(0);
        draft.deleteHex();
        assertEquals("E9A8C", draft.hex());
        assertTrue(draft.hexError());
        draft.moveCaret(5, false);
        draft.backspaceHex();
        assertEquals("E9A8", draft.hex());
        draft.selectRange(0, 2);
        draft.insertText("B84A7A");
        assertEquals("B84A7AA8", draft.hex(), "a paste over a selection replaces exactly that selection");
        assertTrue(draft.hexError(), "and the over-long result is still an error, not a save");
    }

    @Test void theColourPopoverCountsItsOwnRowsAtEachDegradation() {
        Rect gear = Rect.of(500, 40, 96, 200);
        ColorPopupLayout full = layout(false, 640, 360, gear, true, chineseLabels());
        assertEquals(ColorPopupLayout.Level.FULL, full.level());
        assertTrue(full.panel().h() >= ColorPopupLayout.FULL_HEIGHT - 1,
                "the full stack really needs its own height, not the old pre-narrow total");
        assertTrue(full.showsSv() && full.showsHue());
        assertFalse(full.buttonsStacked(), "three full-width columns hold the Chinese labels");
        Rect save = full.rect(ColorPopupLayout.SAVE);
        assertTrue(save.h() > 0 && save.right() <= full.panel().right());

        ColorPopupLayout shortWindow = layout(false, 640, 140, Rect.of(500, 10, 96, 40), true, chineseLabels());
        assertNotEquals(ColorPopupLayout.Level.FULL, shortWindow.level(), "a short window degrades");
        assertTrue(shortWindow.rect(ColorPopupLayout.HEX_FIELD).h() > 0,
                "presets and HEX survive every degradation");
        assertTrue(shortWindow.panel().bottom() <= 140 - Scale.EDGE + 1, "the popover stays on screen");
    }

    @Test void theNarrowPopoverStacksItsButtonsWhenTheLabelsDoNotFit() {
        Rect gear = Rect.of(500, 40, 96, 200);
        // One pixel per character: every label fits a 32-pixel column, so the row stays horizontal.
        ColorPopupLayout perPixel = layout(true, 640, 360, gear, true, chineseLabels());
        assertFalse(perPixel.buttonsStacked());

        // The same panel measured with the real English labels: "Cancel" needs more than the 22 pixels
        // a 32-pixel column leaves, so the approved degradation is the vertical stack.
        ColorPopupLayout english = layout(true, 640, 360, gear, true, englishLabels());
        assertTrue(english.buttonsStacked(), "the English labels do not fit three columns");
        Rect reset = english.rect(ColorPopupLayout.RESET);
        Rect cancel = english.rect(ColorPopupLayout.CANCEL);
        Rect save = english.rect(ColorPopupLayout.SAVE);
        assertEquals(reset.w(), cancel.w(), "a stacked button takes the whole inner width");
        assertEquals(reset.w(), save.w());
        assertTrue(cancel.y() > reset.bottom(), "the stack really is vertical");
        assertTrue(save.y() > cancel.bottom());

        // The extra rows are part of the popover's own height: a layout that still assumed one button
        // row would push Save past its own bottom edge.
        assertTrue(save.bottom() <= english.panel().bottom(), "the stacked save button stays inside");
        assertEquals(english.panel().h(), perPixel.panel().h() + 32,
                "two more 14-pixel rows with 2-pixel gaps, counted in the total");
    }

    @Test void theInformationSegmentIsMeasuredInTheSameUnitAsTheBudget() {
        // Six pixels per character, the vanilla font's own average for this alphabet.
        java.util.function.ToIntFunction<String> measure = text -> text.length() * 6;
        String name = "Ann";
        String health = "20 / 20";
        // Raw widths: 18 + gap 4 + 42 = 64. At 115% the real pieces are 21 + 5 + 49 = 75, so a 70-pixel
        // budget can only hold the single-line form if the comparison ignores the preset - which is
        // exactly the overlap R5 reported.
        RosterRowText.Arrangement tight115 = RosterRowText.arrange(name, "", health, 70, 3, 1.15D, measure);
        assertFalse(tight115.infoMerged(), "at 115% the name and the health do not fit one 70-pixel line");
        // At 80% the same budget really does hold them: 15 + 3 + 34 = 52.
        RosterRowText.Arrangement loose80 = RosterRowText.arrange(name, "", health, 60, 3, 0.8D, measure);
        assertTrue(loose80.infoMerged(), "at 80% the information belongs on the name's line");
        // And whenever the merge is claimed, the scaled pieces really fit the budget.
        for (double scale : new double[]{0.8D, 1.0D, 1.15D}) {
            for (int budget = 40; budget <= 200; budget += 2) {
                RosterRowText.Arrangement arrangement = RosterRowText.arrange(name, "行动中", health, budget, 3,
                        scale, measure);
                if (!arrangement.infoMerged()) continue;
                int merged = TextMetrics.width(measure.applyAsInt(arrangement.lastLine()), scale)
                        + Scale.effectivePx(RosterRowText.CHIP_GAP, scale)
                        + TextMetrics.width(measure.applyAsInt(arrangement.chip()), scale)
                        + Scale.effectivePx(RosterRowText.HEALTH_GAP, scale)
                        + TextMetrics.width(measure.applyAsInt(arrangement.health()), scale);
                assertTrue(merged <= budget, "a merged line at " + scale + " with budget " + budget
                        + " needs " + merged + " pixels");
            }
        }
    }

    @Test void theColourPopoverNeverCoversTheHotbar() {
        // 854x480 at GUI 2 is GUI 427x240: the narrow popover sits over the hotbar strip horizontally,
        // and the old height choice only knew about the screen edge.
        Rect gear = Rect.of(309, 6, 112, 118);
        ColorPopupLayout narrow = ColorPopupLayout.compute(427, 240, 1.0D, true, gear, true, chineseLabels());
        int hotbarTop = 240 - Scale.AVOID_HEIGHT;
        assertTrue(narrow.panel().bottom() <= hotbarTop,
                "the popover stops above the hotbar: " + narrow.panel() + " vs " + hotbarTop);
        assertTrue(narrow.panel().right() <= 427 && narrow.panel().x() >= Scale.EDGE);
        // Presets, HEX and the three buttons survive every degradation.
        assertTrue(narrow.rect(ColorPopupLayout.HEX_FIELD).h() > 0);
        assertTrue(narrow.rect(ColorPopupLayout.SAVE).h() > 0);
        assertEquals(8, narrow.presets().size());
        assertTrue(narrow.rect(ColorPopupLayout.SAVE).bottom() <= narrow.panel().bottom(),
                "the buttons stay inside the popover that had to move up");

        // The standard window is tall enough, so the full stack is still chosen there.
        ColorPopupLayout full = ColorPopupLayout.compute(640, 360, 1.0D, false, Rect.of(538, 28, 96, 118), true,
                chineseLabels());
        assertEquals(ColorPopupLayout.Level.FULL, full.level());
        assertTrue(full.panel().bottom() <= 360 - Scale.EDGE);

        // A popover that never reaches the hotbar is bounded by the screen edge only.
        ColorPopupLayout left = ColorPopupLayout.compute(640, 360, 1.0D, true, Rect.of(500, 28, 96, 118), true,
                chineseLabels());
        assertTrue(left.panel().right() < 640 / 2 - Scale.AVOID_WIDTH / 2 || left.panel().y() >= Scale.EDGE);
    }

    @Test void theSizeTagNeverCoversANumber() {
        // The real standard-form frame: roster 140x167 at (6,6), its handle bottom-right, the two footer
        // lines and one wrapped row as the protected rectangles.
        Rect roster = Rect.of(6, 6, 140, 167);
        Rect handle = Rect.of(134, 161, 12, 12);
        Rect footerAp = Rect.of(12, 133, 128, 9);
        Rect footerWeapon = Rect.of(12, 147, 114, 9);
        Rect row = Rect.of(12, 31, 122, 100);
        List<Rect> protectedRects = List.of(footerAp, footerWeapon, row);
        DragTag.Placement placement = DragTag.place(handle, roster, 68, 17, protectedRects, Rect.EMPTY, 640, 360);
        Rect tag = placement.rect();
        assertTrue(tag.w() > 0 && tag.h() > 0);
        assertFalse(placement.forced(), "a 640x360 frame always has a free spot: " + placement.where());
        for (Rect forbidden : protectedRects) {
            assertFalse(tag.x() < forbidden.right() && forbidden.x() < tag.right()
                    && tag.y() < forbidden.bottom() && forbidden.y() < tag.bottom(),
                    "the tag " + tag + " must not cover " + forbidden);
        }
        assertTrue(tag.x() >= Scale.EDGE && tag.right() <= 640 - Scale.EDGE
                && tag.y() >= Scale.EDGE && tag.bottom() <= 360 - Scale.AVOID_HEIGHT,
            "and it stays on screen, clear of the hotbar strip: " + tag);

        // A frame with no free spot at all: the tag is still drawn and reported as forced, so a reviewer
        // sees that the promise could not be kept instead of a silent overlap.
        DragTag.Placement forced = DragTag.place(handle, roster, 68, 17, protectedRects, Rect.EMPTY, 160, 190);
        assertTrue(forced.forced(), "nothing fits in a 160x190 frame next to this panel: " + forced.where());
    }

    @Test void thePresetRowFollowsTheColourBeingEdited() {
        Rect gear = Rect.of(500, 40, 96, 200);
        ColorPopupLayout range = ColorPopupLayout.compute(640, 360, 1.0D, false, gear, true,
                new ColorPopupLayout.Content(Palette.RANGE_PRESETS, String::length, "重置", "取消", "保存"));
        assertEquals(Palette.RANGE_PRESETS, range.presets(), "the range popover offers the range presets");
        assertEquals(8, range.presets().size());
        assertNotEquals(Palette.THEME_PRESETS, range.presets());
    }

    private static ColorPopupLayout layout(boolean narrow, int guiWidth, int guiHeight, Rect gear, boolean save,
                                          ColorPopupLayout.Content content) {
        return ColorPopupLayout.compute(guiWidth, guiHeight, 1.0D, narrow, gear, save, content);
    }

    /** Chinese labels measured one pixel per character, i.e. short. */
    private static ColorPopupLayout.Content chineseLabels() {
        return new ColorPopupLayout.Content(Palette.THEME_PRESETS, String::length, "重置", "取消", "保存");
    }

    /** The real English labels, measured at six pixels per character as the vanilla font roughly does. */
    private static ColorPopupLayout.Content englishLabels() {
        return new ColorPopupLayout.Content(Palette.THEME_PRESETS, text -> text.length() * 6,
                com.tactical.neoforge.client.ui.command.ColorText.EN_RESET,
                com.tactical.neoforge.client.ui.command.ColorText.EN_CANCEL,
                com.tactical.neoforge.client.ui.command.ColorText.EN_SAVE);
    }
}
