package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.layout.TooltipText;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import com.tactical.neoforge.client.ui.theme.Motion;
import com.tactical.neoforge.client.ui.widget.Tooltip;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The readability round: a price keeps its units, a shortened row explains every field it had to cut,
 * a passive tooltip answers for exactly the row it names, and a single footer button wider than the
 * panel still stays inside it. All pure, so they hold without a game window.
 */
class PassiveTooltipTest {

    /** A stand-in for the vanilla font: every character occupies a full-width cell. */
    private static final ToIntFunction<String> FONT = text -> text.codePointCount(0, text.length()) * 9;

    /** The English skill row, as the page composes it. */
    private static final String NAME = "Backstab · Lv.1 / 5";
    private static final String DESCRIPTION = "Triggers a native melee attack from behind; the skill multiplier grows with level.";
    private static final String PRICE = "15 AP · 10 MP";
    private static final String COOLDOWN = " · 0 turns of cooldown left";
    private static final String AIM = "Unit target · point at a participant";

    @Test
    void thePriceKeepsBothUnitsHoweverTightTheRowIs() {
        int priceWidth = TooltipText.width(PRICE, 1.0D, FONT);
        // The old row concatenated price and cooldown and then ellipsised the result, which turned
        // "15 AP · 10 MP" into "15 AP · 10 M…" and lost the MP unit. Every budget from the price's own
        // width upwards has to give back the whole price, never a cut one.
        for (int budget = priceWidth; budget <= priceWidth + 40; budget++) {
            TooltipText.PriceShown shown = TooltipText.priceWithSuffix(PRICE, COOLDOWN, budget, 1.0D, FONT);
            assertEquals(PRICE, shown.text(), "budget " + budget + " must keep the whole price");
            assertTrue(shown.suffixDropped(), "the cooldown does not fit beside the price at " + budget);
        }
        TooltipText.PriceShown roomy = TooltipText.priceWithSuffix(PRICE, COOLDOWN,
                TooltipText.width(PRICE + COOLDOWN, 1.0D, FONT), 1.0D, FONT);
        assertEquals(PRICE + COOLDOWN, roomy.text(), "a row with room still shows the cooldown");
        assertFalse(roomy.suffixDropped());
    }

    @Test
    void aBudgetSmallerThanThePriceStillSaysWhatItCan() {
        int priceWidth = TooltipText.width(PRICE, 1.0D, FONT);
        TooltipText.PriceShown cramped = TooltipText.priceWithSuffix(PRICE, COOLDOWN, priceWidth - 10, 1.0D, FONT);
        assertTrue(cramped.text().endsWith("…"), cramped.text());
        assertTrue(cramped.suffixDropped(), "the cooldown never takes room from the price");
        assertEquals("", TooltipText.priceWithSuffix(PRICE, COOLDOWN, 0, 1.0D, FONT).text());
    }

    @Test
    void aShortenedRowExplainsEveryFieldInTwoSegments() {
        String[] row = TooltipText.row(NAME, DESCRIPTION, PRICE + COOLDOWN, AIM);
        assertEquals(2, row.length, "the shared tooltip reads exactly two segments");
        assertEquals(NAME, row[0]);
        // Every field survives, one per line: the parent folds each segment itself, so the fields may not
        // be concatenated into one paragraph.
        String secondary = row[1];
        for (String field : List.of(DESCRIPTION, PRICE + COOLDOWN, AIM)) {
            assertTrue(secondary.contains(field), "the tooltip lost \"" + field + "\"");
        }
        assertTrue(secondary.contains("\n"), "the fields are separate lines, not one paragraph");
        assertEquals(3, secondary.split("\n", -1).length, secondary);
        // Folding keeps all of it readable even at the tooltip's fixed width.
        Tooltip.Content content = Tooltip.content(row[0], row[1], 1.0D, FONT);
        assertFalse(content.isEmpty());
        assertTrue(String.join("", content.primary()).replace(" ", "").contains("Backstab"));
        String folded = String.join("", content.secondary()).replace("\n", "").replace(" ", "");
        assertTrue(folded.contains("multipliergrowswithlevel."), folded);
        assertTrue(folded.contains("0turnsofcooldownleft"), folded);
        assertTrue(folded.contains("pointataparticipant"), folded);
    }

    @Test
    void aBlankFieldNeverBecomesAnEmptyTooltipLine() {
        assertEquals("manager\n3 blocks", TooltipText.join("manager", "", "   ", "3 blocks"));
        assertEquals("", TooltipText.join("", null));
        assertEquals(2, TooltipText.row("reason").length);
        assertEquals("", TooltipText.row("reason")[1]);
    }

    @Test
    void aPassiveTooltipAnswersForExactlyTheRowItNames() {
        assertEquals(0, TooltipText.rowIndex("row-0", "row-", 2));
        assertEquals(1, TooltipText.rowIndex("row-1", "row-", 2));
        // A control inside a row keeps its own longer id: it must never inherit the row's wording.
        assertEquals(-1, TooltipText.rowIndex("row-0-join", "row-", 2));
        assertEquals(-1, TooltipText.rowIndex("row-0-dismiss", "row-", 2));
        // Nor may a hover anywhere else - the footer, the page title - produce row 0's reason.
        assertEquals(-1, TooltipText.rowIndex("nearby-refresh", "row-", 2));
        assertEquals(-1, TooltipText.rowIndex("nearby-close", "row-", 2));
        assertEquals(-1, TooltipText.rowIndex("footer", "row-", 2));
        // A row that scrolled away is not on the page any more.
        assertEquals(-1, TooltipText.rowIndex("row-4", "row-", 2));
        assertEquals(-1, TooltipText.rowIndex("row-", "row-", 2));
        assertEquals(-1, TooltipText.rowIndex(null, "row-", 2));
        assertEquals(3, TooltipText.rowIndex("skill-row-3", "skill-row-", 8));
    }

    @Test
    void aSingleFooterButtonWiderThanThePanelStaysInsideIt() {
        // The narrow move-choice page: 100px of inner width, one 112px button. It used to be drawn
        // straight past the panel's right edge because only rows of two or more buttons stacked.
        PanelLayout layout = PanelLayout.begin(427, 241, 1.0D, true);
        layout.footer(List.of("route-cancel"), List.of(112), Motion.BUTTON_HEIGHT);
        layout.finish();
        Rect button = layout.rect("route-cancel");
        Rect panel = layout.panel();
        assertEquals(layout.innerWidth(), button.w(), "the one button takes the panel's inner width");
        assertTrue(button.right() <= panel.right(), "the button ends inside the panel");
        assertTrue(button.x() >= panel.x(), "and starts inside it");
        assertTrue(layout.footerStacked(), "an over-wide footer button is laid out as the stacked form");
    }

    @Test
    void aSingleFooterButtonThatFitsKeepsItsMeasuredWidth() {
        PanelLayout layout = PanelLayout.begin(640, 360, 1.0D, false);
        layout.footer(List.of("back"), List.of(40), Motion.BUTTON_HEIGHT);
        Rect button = layout.rect("back");
        assertEquals(40, button.w(), "a label that fits must not be stretched to the whole width");
        assertFalse(layout.footerStacked());
        assertTrue(TextMetrics.width(FONT.applyAsInt("Back to landing spot"), 1.0D) > 40,
                "the fixture label really is wider than that button");
    }
}
