package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.Box;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.widget.PopupStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Popover layers win over page controls, and Escape closes them last-in-first-out. */
class NativeLayerPriorityTest {

    @Test
    void theTopmostLayerOwnsThePointer() {
        Box popover = Box.of("popover-close", Rect.of(100, 100, 40, 14));
        Box row = Box.of("row-attack", Rect.of(80, 90, 120, 22));
        List<Box> topFirst = List.of(popover, row);
        assertEquals("popover-close", HitTest.topmost(topFirst, 110, 105).id(),
                "the popover is drawn above the page, so it must also be hit above it");
        assertEquals("row-attack", HitTest.topmost(topFirst, 90, 95).id(),
                "outside the popover the page row still reacts");
        assertNull(HitTest.topmost(topFirst, 0, 0));
    }

    @Test
    void aDisabledControlBlocksButDoesNotReact() {
        Box disabled = new Box("pop-plus", Rect.of(0, 0, 10, 10), false, true);
        List<Box> topFirst = List.of(disabled);
        assertEquals("pop-plus", HitTest.topmost(topFirst, 5, 5).id());
        assertNull(HitTest.topmostEnabled(topFirst, 5, 5));
        assertTrue(HitTest.anyContains(topFirst, 5, 5), "a disabled control still belongs to the panel");
    }

    @Test
    void escapeClosesThePopoverBeforeThePanel() {
        PopupStack popups = new PopupStack();
        assertFalse(popups.escape(), "with nothing open Escape must reach the panel itself");

        popups.toggle("settings");
        assertTrue(popups.isOpen("settings"));
        assertTrue(popups.escape(), "the first Escape closes the settings popover");
        assertFalse(popups.isOpen("settings"));
        assertFalse(popups.escape(), "the second Escape closes the panel");

        popups.toggle("settings");
        popups.toggle("settings");
        assertTrue(popups.isEmpty(), "toggling twice closes the popover again");
    }

    @Test
    void nestedPopoversCloseInReverseOrder() {
        PopupStack popups = new PopupStack();
        popups.push("settings");
        popups.push("submenu");
        assertTrue(popups.top("submenu"));
        assertTrue(popups.escape());
        assertTrue(popups.top("settings"), "the parent popover is still open");
        assertTrue(popups.escape());
        assertTrue(popups.isEmpty());
    }

    @Test
    void reopeningTheTopPopoverDoesNotStackDuplicates() {
        PopupStack popups = new PopupStack();
        popups.push("settings");
        popups.push("settings");
        assertEquals(1, popups.depth());
    }
}
