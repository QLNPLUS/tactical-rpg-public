package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.panel.PanelLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skeleton every replacing panel shares: right anchor, one of two widths, title, accent rule,
 * footer with right-aligned buttons, and one hit order.
 */
class PanelLayoutTest {

    @Test void thePanelSitsSixPixelsFromTheRightAndTheTop() {
        PanelLayout layout = PanelLayout.begin(640, 360, 1.0D, false);
        layout.title();
        layout.rule();
        layout.block("body", 40);
        layout.footer(List.of("ok"), List.of(46), 14);
        layout.finish();
        Rect panel = layout.panel();
        assertEquals(640 - 6 - 162, panel.x());
        assertEquals(6, panel.y());
        assertEquals(162, panel.w());
        // 6 pad + 9 title + 4 + 1 + 5 + 40 body + 4 + 1 + 5 + 14 footer + 6 pad
        assertEquals(6 + 9 + 4 + 1 + 5 + 40 + 4 + 1 + 5 + 14 + 6, panel.h());
    }

    @Test void theNarrowFormUsesTheNarrowWidth() {
        PanelLayout layout = PanelLayout.begin(427, 240, 1.0D, true);
        layout.title();
        layout.finish();
        assertEquals(112, layout.panel().w());
        assertEquals(427 - 6 - 112, layout.panel().x());
    }

    @Test void footerButtonsAreRightAlignedInTheOrderGiven() {
        PanelLayout layout = PanelLayout.begin(640, 360, 1.0D, false);
        layout.title();
        layout.footer(List.of("first", "second"), List.of(46, 46), 14);
        layout.finish();
        Rect first = layout.rect("first");
        Rect second = layout.rect("second");
        assertEquals(layout.innerX() + layout.innerWidth() - 46, second.x());
        assertEquals(46 + layout.px(2) + 46, second.right() - first.x());
        assertEquals(first.y(), second.y());
    }

    @Test void theHitOrderIsTheOrderThePageAdded() {
        PanelLayout layout = PanelLayout.begin(640, 360, 1.0D, false);
        layout.title();
        Rect a = layout.block("a", 22);
        Rect b = layout.block("b", 22);
        layout.interactive("a", a);
        layout.disabled("b", b);
        layout.finish();
        List<Box> boxes = layout.hitOrder();
        assertEquals(2, boxes.size());
        assertEquals("a", boxes.get(0).id());
        assertTrue(boxes.get(0).enabled());
        assertFalse(boxes.get(1).enabled(), "a disabled control keeps its rectangle but takes no click");
    }

    @Test void scaledValuesAreRoundedToWholePixels() {
        PanelLayout layout = PanelLayout.begin(640, 360, 1.15D, false);
        layout.title();
        layout.rule();
        layout.finish();
        assertEquals(Math.round(162 * 1.15D), layout.panel().w());
        assertEquals(Math.round(9 * 1.15D), layout.rect(PanelLayout.TITLE).h());
    }

    @Test void theBudgetStopsAboveTheHotbarStripOnlyWhenThePanelCrossesIt() {
        PanelLayout wide = PanelLayout.begin(640, 360, 1.0D, false);
        wide.title();
        wide.finish();
        // On a 640-wide GUI the 162-wide panel sits well right of the centred strip.
        assertEquals(360 - 6 - 6 - wide.cursor(), wide.budgetToBottom());

        PanelLayout narrowWindow = PanelLayout.begin(320, 240, 1.0D, true);
        narrowWindow.title();
        narrowWindow.finish();
        // At 320 wide the panel reaches over the strip, so the budget ends where the strip starts.
        assertEquals(240 - 40 - narrowWindow.cursor(), narrowWindow.budgetToBottom());
    }
}
