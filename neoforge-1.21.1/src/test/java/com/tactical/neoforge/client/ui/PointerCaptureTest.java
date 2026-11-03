package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.layout.ScrollList;
import com.tactical.neoforge.client.ui.widget.PointerCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The two things a page does when its content changes under the pointer: a press that no longer
 * belongs to a row must not become a click on its replacement, and a shrinking list must not keep an
 * offset that would leave a blank tail.
 */
class PointerCaptureTest {

    @Test void onlyTheSameControlActivatesOnRelease() {
        PointerCapture capture = new PointerCapture();
        capture.press("skill-row-2");
        assertEquals("skill-row-2", capture.release("skill-row-2"));
        assertNull(capture.pressed());
    }

    @Test void aReleaseOverAnotherRowActivatesNothing() {
        PointerCapture capture = new PointerCapture();
        capture.press("skill-row-2");
        assertNull(capture.release("skill-row-3"), "dropping on a neighbour is not a click on the neighbour");
        assertNull(capture.release("skill-row-2"), "the capture was used up");
    }

    @Test void aCancelledPressNeverActivatesAnything() {
        PointerCapture capture = new PointerCapture();
        capture.press("skill-row-2");
        capture.cancel();
        assertNull(capture.release("skill-row-2"),
                "the content changed under the pointer, so the release belongs to the old list");
    }

    @Test void aShrinkingListClampsItsOffsetAndLeavesNoBlankTail() {
        assertEquals(2, ScrollList.clamp(6, 5, 3), "ten rows browsed down to the end, then five rows left");
        assertEquals(0, ScrollList.clamp(6, 2, 3), "a list that fits starts at the top again");
        assertEquals(3, ScrollList.clamp(9, 6, 3));
    }
}
