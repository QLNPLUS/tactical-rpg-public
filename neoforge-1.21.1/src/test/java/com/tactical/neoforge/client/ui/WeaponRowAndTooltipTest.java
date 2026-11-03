package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.command.CommandMenuModel;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.Scale;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage-A supplement: the weapon line copy, its truncation, the tooltip folding width and the
 * last-resort narrow-window degradation. All pure, so they hold without a game window.
 */
class WeaponRowAndTooltipTest {

    /** A stand-in for the vanilla font: every character occupies a full-width cell. */
    private static final ToIntFunction<String> FONT = text -> text.codePointCount(0, text.length()) * 9;

    private static final int STANDARD_WIDTH = 640;
    private static final int STANDARD_HEIGHT = 360;

    /** The two suffixes the panel passes in, as the Chinese language file words them. */
    private static final String MELEE = " · 近战";
    private static final String RANGE_5 = " · 射程 5";
    private static final String RANGE_9 = " · 射程 9";

    @Test
    void weaponLineKeepsTheReleasedWordingWithoutExtraWords() {
        // The weapon's own name is the server's item text; only the suffix is wording, so this function
        // stays a pure concatenation and the suffix arrives already resolved for the current language.
        assertEquals("铁剑 · 近战", CommandMenuModel.weaponLine("铁剑", 0, MELEE, RANGE_5));
        assertEquals("短弓 · 射程 5", CommandMenuModel.weaponLine("短弓", 1, MELEE, RANGE_5));
        assertEquals("重弩 · 射程 9", CommandMenuModel.weaponLine("重弩", 2, MELEE, RANGE_9),
                "a crossbow is not named on its own line: the released menu only said the reach");
        assertEquals("", CommandMenuModel.weaponLine("", 0, MELEE, RANGE_5),
                "until the equipment packet arrives there is no wording at all");
        assertEquals("", CommandMenuModel.weaponLine(null, 1, MELEE, RANGE_5));
        assertFalse(CommandMenuModel.weaponLine("短弓", 1, MELEE, RANGE_5).contains("格"),
                "the released wording never mentioned a unit");
    }

    @Test
    void theWeaponRowIsPersistentInTheStandardFormOnly() {
        CommandMenuLayout standard = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect weapon = standard.rect(CommandMenuLayout.WEAPON);
        assertEquals(9, weapon.h(), "the weapon line is one 9px line at 100%");
        double scale = standard.scale();
        assertTrue(weapon.y() >= standard.rect(CommandMenuLayout.HP_BAR).bottom() + Scale.px(5, scale),
                "the weapon line keeps the group gap below HP/MP");
        Rect firstRow = standard.rect(CommandMenuLayout.rowId(CommandMenuLayout.ACTIONS.get(0)));
        assertTrue(weapon.bottom() + Scale.px(5, scale) <= firstRow.y(),
                "the rows start one group gap below the weapon line");
        assertTrue(weapon.x() >= standard.panel().x() && weapon.right() <= standard.panel().right());
        assertNull(HitTest.topmost(standard.pageBoxes(), weapon.x() + weapon.w() / 2.0D,
                        weapon.y() + weapon.h() / 2.0D),
                "the weapon line is read-only: it must never take a click");

        CommandMenuLayout narrow = CommandMenuLayout.compute(427, 240, 1.0D, false);
        assertTrue(narrow.narrow());
        assertEquals(0, narrow.rect(CommandMenuLayout.WEAPON).h(),
                "the narrow form drops the weapon line and carries it in the attack tooltip");
    }

    @Test
    void theWeaponValueIsTruncatedWithAnEllipsisInsideItsBudget() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect row = layout.rect(CommandMenuLayout.WEAPON);
        int budget = row.w() - FONT.applyAsInt("武器") - 4;
        String value = CommandMenuModel.weaponLine("非常非常长的附魔下界合金剑", 1, MELEE,
                " · 射程 12");
        String shown = TextMetrics.ellipsis(value, budget, layout.scale(), FONT);
        assertTrue(TextMetrics.width(FONT.applyAsInt(shown), layout.scale()) <= budget);
        assertTrue(shown.endsWith("…"), "a cut value is marked, never silently clipped");
        assertFalse(shown.equals(value), "the full text lives in the tooltip instead");

        String shortValue = CommandMenuModel.weaponLine("铁剑", 0, MELEE, RANGE_5);
        assertEquals(shortValue, TextMetrics.ellipsis(shortValue, budget, layout.scale(), FONT),
                "a value that fits is drawn unchanged");
    }

    @Test
    void tooltipFoldsAtTheMaximumWidthWithoutLosingText() {
        String text = "使用主手道具并选择放置位置，远程武器按射程计算命中，近战武器按相邻格计算";
        for (double scale : new double[]{0.8D, 1.0D, 1.15D}) {
            Tooltip.Content content = Tooltip.content(text, "短弓 · 射程 5", scale, FONT);
            int maxText = Scale.px(Tooltip.MAX_WIDTH, scale) - Scale.px(Tooltip.PAD_X, scale) * 2;
            assertTrue(content.primary().size() > 1, "a long description folds at " + scale);
            for (String line : content.primary()) {
                assertTrue(TextMetrics.width(FONT.applyAsInt(line), scale) <= maxText,
                        "\"" + line + "\" fits the folded width at " + scale);
                assertFalse(line.contains("…"), "a tooltip folds, it never truncates");
            }
            for (String line : content.secondary()) {
                assertTrue(TextMetrics.width(FONT.applyAsInt(line), scale) <= maxText);
            }
            assertEquals(strip(text), strip(String.join("", content.primary())),
                    "folding keeps every character");
            assertEquals("短弓 · 射程 5", String.join("", content.secondary()));
            assertTrue(Tooltip.width(content, scale, FONT) <= Scale.px(Tooltip.MAX_WIDTH, scale));
            assertEquals(content.lineCount() * Scale.px(9, scale) + Scale.px(Tooltip.PAD_Y, scale) * 2,
                    Tooltip.height(content, scale), "padding 3 vertically, one line per row");
        }
    }

    @Test
    void tooltipFoldsLatinWordsAtSpacesAndNeverReturnsAnEmptyLineList() {
        Tooltip.Content latin = Tooltip.content("choosing a reachable target for the attack", null, 1.0D, FONT);
        assertTrue(latin.primary().size() > 1);
        for (String line : latin.primary()) assertFalse(line.startsWith(" ") || line.endsWith(" "));
        assertEquals(strip("choosing a reachable target for the attack"), strip(String.join("", latin.primary())));
        assertTrue(latin.secondary().isEmpty(), "no supplementary text means one line group only");

        assertTrue(Tooltip.content("", null, 1.0D, FONT).isEmpty(),
                "nothing to say means no box at all");
        assertTrue(TextMetrics.wrap("", 100, 1.0D, FONT).isEmpty());
        assertEquals(List.of("x"), TextMetrics.wrap("x", 1, 1.0D, FONT),
                "a single character wider than the line still gets its own line");
    }

    @Test
    void tooltipSitsBesideItsControlAndNeverUnderThePointer() {
        CommandMenuLayout layout = CommandMenuLayout.compute(STANDARD_WIDTH, STANDARD_HEIGHT, 1.0D, false);
        Rect row = layout.rect(CommandMenuLayout.rowId("tool"));
        Tooltip.Content content = Tooltip.content("使用主手道具并选择放置位置", "短弓 · 射程 5", layout.scale(), FONT);
        int boxWidth = Tooltip.width(content, layout.scale(), FONT);
        int boxHeight = Tooltip.height(content, layout.scale());
        int gap = Scale.px(Tooltip.GAP, layout.scale());
        assertTrue(boxWidth <= Scale.px(Tooltip.MAX_WIDTH, layout.scale()));

        for (int[] pointer : new int[][]{{row.x(), row.y()}, {row.right() - 1, row.y() + 1},
                {row.x() + row.w() / 2, row.bottom() - 1}}) {
            Rect box = Tooltip.place(row, boxWidth, boxHeight, STANDARD_WIDTH, STANDARD_HEIGHT,
                    pointer[0], pointer[1], gap);
            assertFalse(box.contains(pointer[0], pointer[1]),
                    "the tooltip must not cover the pointer at " + pointer[0] + "," + pointer[1]);
            assertTrue(box.x() >= Scale.EDGE && box.right() <= STANDARD_WIDTH - Scale.EDGE);
            assertTrue(box.y() >= Scale.EDGE && box.bottom() <= STANDARD_HEIGHT - Scale.EDGE);
            assertTrue(box.x() + box.w() <= row.x() || box.x() >= row.right(),
                    "the tooltip is placed beside the control, not on it");
        }
    }

    @Test
    void chargeRailHidesTheOtherPortraitsOnlyWhenTheWindowIsCramped() {
        assertFalse(Scale.crampedFor(427, 1.0D), "854x480 at GUI scale 2 still shows the whole rail");
        assertFalse(Scale.crampedFor(640, 1.0D));
        assertTrue(Scale.crampedFor(320, 1.0D), "a 320px wide GUI cannot hold roster, panel and rail");
        assertTrue(Scale.crampedFor(427, 1.15D),
                "427px of GUI at 115% is only 371px of design space, so it is cramped too");
        assertTrue(Scale.crampedFor(419, 1.0D));
        assertFalse(Scale.crampedFor(420, 1.0D), "420 of design width is the last size that fits");

        assertTrue(CommandMenuLayout.compute(320, 240, 1.0D, false).cramped());
        assertFalse(CommandMenuLayout.compute(427, 240, 1.0D, false).cramped());
        CommandMenuLayout largePreset = CommandMenuLayout.compute(427, 240, 1.15D, false);
        assertTrue(largePreset.scale() <= 1.15D, "the panel never draws above the largest preset");
        assertTrue(largePreset.cramped(),
                "the flag follows the chosen preset, not the reduced scale the rail never sees");
    }

    @Test
    void theSmallestPresetFitsTheSmallestSupportedWindowAndNeverShrinksFurther() {
        // Minecraft's smallest GUI is 320x240; the narrow form at the smallest preset fits it whole.
        CommandMenuLayout smallest = CommandMenuLayout.compute(320, 240, 0.8D, false);
        assertTrue(smallest.narrow());
        assertTrue(smallest.panel().bottom() <= 240 - Scale.EDGE, "the panel stays inside the window");
        assertTrue(smallest.panel().x() >= Scale.EDGE);

        // Below that the effective scale stops at the floor: the menu is never drawn smaller than the
        // smallest preset, so its own rows stay readable and the overflow is cropped elsewhere.
        CommandMenuLayout tooSmall = CommandMenuLayout.compute(240, 140, 0.8D, false);
        assertEquals(Scale.MIN_EFFECTIVE, tooSmall.scale(), 1.0E-9D);
        assertTrue(tooSmall.panel().x() >= Scale.EDGE);
        assertEquals(1.0D, Scale.effective(1.0D), "the saved preset itself is never rewritten");
    }

    private static String strip(String value) {
        return value.replace(" ", "");
    }
}
