package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.command.CommandMenuModel;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second review round plus the rescaled range: the tooltip geometry follows the effective scale
 * (never below the smallest preset), the two-column vitals value drops the spaces around its slash
 * yet still fits, and a one-glyph icon button is never folded into an ellipsis.
 */
class VitalsAndTooltipScaleTest {

    /** Stand-in font: digits and Latin signs are 6px, CJK glyphs are 9px. */
    private static final ToIntFunction<String> FONT = text -> {
        int width = 0;
        for (int index = 0; index < text.length(); index++) {
            width += text.charAt(index) < 0x2E80 ? 6 : 9;
        }
        return width;
    };

    @Test
    void tooltipGeometryUsesTheEffectiveScaleThatWasReallyDrawn() {
        // The effective scale never falls below the smallest preset any more, and a corrupt preset is
        // clamped onto the same floor instead of shrinking the text further.
        assertEquals(0.8D, Scale.effective(0.5D), "the effective scale floors at the smallest preset");
        assertEquals(0.8D, Scale.sanitize(0.5D), "the preset sanitizer floors at 0.8");
        double effective = Scale.effective(0.5D);

        Tooltip.Content content = Tooltip.content("使用主手道具并选择放置位置", null, effective, FONT);
        int maxText = Scale.effectivePx(Tooltip.MAX_WIDTH, effective) - Scale.effectivePx(Tooltip.PAD_X, effective) * 2;
        for (String line : content.primary()) {
            assertTrue(TextMetrics.width(FONT.applyAsInt(line), effective) <= maxText,
                    "\"" + line + "\" fits the effective folded width");
        }
        Rect box = Rect.of(100, 100, Tooltip.width(content, effective, FONT),
                Tooltip.height(content, effective));
        assertEquals(Scale.effectivePx(Motion.LINE, effective) * content.lineCount()
                        + Scale.effectivePx(Tooltip.PAD_Y, effective) * 2,
                box.h(), "the box height is the effective line height plus effective padding");
        assertEquals(box.y() + Scale.effectivePx(Tooltip.PAD_Y, effective), Tooltip.textTop(box, effective));
        assertEquals(box.x() + Scale.effectivePx(Tooltip.PAD_X, effective), Tooltip.textLeft(box, effective));
        assertTrue(Tooltip.width(content, effective, FONT) <= Scale.effectivePx(Tooltip.MAX_WIDTH, effective),
                "the box never exceeds the effective maximum width");
        assertEquals(Scale.px(Motion.LINE, effective), Scale.effectivePx(Motion.LINE, effective),
                "inside the new range the two conversions agree, so nothing is padded for a lower scale");
    }

    @Test
    void theTwoColumnVitalsDropTheSpacesAroundTheSlash() {
        assertEquals("100/100", CommandMenuModel.compactValue("100 / 100"));
        assertEquals("7/150", CommandMenuModel.compactValue("7 / 150"));
        assertEquals("", CommandMenuModel.compactValue(""));
        assertEquals("100", CommandMenuModel.compactValue("100"), "a value without a slash is untouched");
    }

    @Test
    void theCompactVitalsValueFitsItsColumnAtEveryPresetAndFallsBackToATooltipWhenItCannot() {
        for (double preset : new double[]{0.8D, 1.0D, 1.15D}) {
            CommandMenuLayout layout = CommandMenuLayout.compute(1280, 720, preset, false);
            assertFalse(layout.narrow(), "1280x720 at " + preset + " keeps the standard two-column form");
            Rect column = layout.rect(CommandMenuLayout.HP_VALUE);
            int labelWidth = TextMetrics.width(FONT.applyAsInt("生命"), layout.scale());
            TextMetrics.Fitted full = TextMetrics.fitAfterLabel("100/100", column.w(), labelWidth,
                    Scale.effectivePx(4, layout.scale()), layout.scale(), FONT);
            assertEquals("100/100", full.shown(), "a full vitals value is shown whole at " + preset);
            assertFalse(full.truncated(), "nothing is cut at " + preset);
            assertTrue(TextMetrics.width(FONT.applyAsInt(full.shown()), layout.scale()) <= column.w() - labelWidth,
                    "the value plus its label stay inside the column at " + preset);

            // Four digits do not fit any more: the value is cut and the whole text stays for a tooltip.
            TextMetrics.Fitted huge = TextMetrics.fitAfterLabel("1000/1000", column.w(), labelWidth,
                    Scale.effectivePx(4, layout.scale()), layout.scale(), FONT);
            assertTrue(huge.truncated(), "a 4-digit value is marked as cut at " + preset);
            assertTrue(huge.shown().endsWith("…"));
            assertEquals("1000/1000", huge.full(), "the tooltip keeps the whole text");
        }
    }

    @Test
    void theSingleColumnVitalsKeepTheSpacedWording() {
        CommandMenuLayout narrow = CommandMenuLayout.compute(427, 240, 1.0D, false);
        assertTrue(narrow.narrow());
        Rect row = narrow.rect(CommandMenuLayout.HP_VALUE);
        int labelWidth = TextMetrics.width(FONT.applyAsInt("生命"), narrow.scale());
        TextMetrics.Fitted fitted = TextMetrics.fitAfterLabel("100 / 100", row.w(), labelWidth,
                Scale.effectivePx(4, narrow.scale()), narrow.scale(), FONT);
        assertEquals("100 / 100", fitted.shown(), "the narrow form has room for the released wording");
        assertFalse(fitted.truncated());
    }

    @Test
    void aOneGlyphIconButtonIsNeverFoldedIntoAnEllipsis() {
        assertEquals("-", TextMetrics.iconOrEllipsis("-", 4, 1.0D, FONT),
                "a sign stays a sign even when the button is narrower than the glyph");
        assertEquals("+", TextMetrics.iconOrEllipsis("+", 4, 1.0D, FONT));
        assertTrue(TextMetrics.iconOrEllipsis("重置大小", 4, 1.0D, FONT).isEmpty(),
                "prose still folds, so the same call cannot silently overflow");
        assertTrue(TextMetrics.iconOrEllipsis("重置大小", 20, 1.0D, FONT).endsWith("…"));
        assertTrue(TextMetrics.iconOrEllipsis("关闭", 40, 1.0D, FONT).equals("关闭"),
                "a label that already fits is drawn unchanged");
    }

    @Test
    void theNarrowApValueKeepsOneSpaceBeforeItsSlashLabel() {
        CommandMenuLayout narrow = CommandMenuLayout.compute(427, 240, 1.0D, false);
        Rect ap = narrow.rect(CommandMenuLayout.AP_VALUE);
        int maxLabel = TextMetrics.width(FONT.applyAsInt("/ 150"), narrow.scale());
        int space = TextMetrics.width(FONT.applyAsInt(" "), narrow.scale());
        assertTrue(space > 0, "the space is a real glyph width, not a hard-coded pixel");
        int valueRight = ap.right() - maxLabel - space;
        assertEquals(space, ap.right() - maxLabel - valueRight,
                "the current value keeps exactly one space before \"/ max\", like the vitals line");
        assertTrue(valueRight > ap.x() + TextMetrics.width(FONT.applyAsInt("行动点 AP"), narrow.scale()),
                "the label and the value still do not overlap at 100%");
    }
}
