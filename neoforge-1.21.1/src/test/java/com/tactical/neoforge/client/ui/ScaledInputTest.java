package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.command.CommandMenuLayout;
import com.tactical.neoforge.client.ui.layout.HitTest;
import com.tactical.neoforge.client.ui.layout.Rect;
import com.tactical.neoforge.client.ui.layout.TextMetrics;
import com.tactical.neoforge.client.ui.widget.PointerCapture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScaledInputTest {
    @Test void scaledTextFitsTheSameGuiRectangleUsedForHits() {
        for (double scale : new double[]{0.8, 1.0, 1.15}) {
            CommandMenuLayout layout = CommandMenuLayout.compute(1280, 720, scale, false);
            Rect row = layout.rect(CommandMenuLayout.rowId("attack"));
            int budget = row.w() / 2;
            String value = TextMetrics.ellipsis("abcdefghijklmno", budget, layout.scale(), s -> s.length() * 6);
            int measured = TextMetrics.width(value.length() * 6, layout.scale());
            assertTrue(measured <= budget);
            assertEquals("row-attack", HitTest.topmost(layout.pageBoxes(), row.x() + measured / 2.0, row.y() + row.h() / 2.0).id());
        }
    }
    @Test void captureOnlyActivatesOnMatchingReleaseAndOnlyOnce() {
        PointerCapture capture = new PointerCapture();
        capture.press("attack");
        assertNull(capture.release(null));
        assertNull(capture.release("attack"));
        capture.press("attack");
        assertNull(capture.release("move"));
        capture.press("attack");
        assertEquals("attack", capture.release("attack"));
        assertNull(capture.release("attack"));
        capture.press("attack");
        capture.cancel();
        assertNull(capture.release("attack"));
    }
    @Test void smallWindowCapsEffectiveScaleWithoutChangingPreset() {
        CommandMenuLayout layout = CommandMenuLayout.compute(427, 240, 1.15, true);
        assertTrue(layout.narrow(), "854x480 at GUI scale 2 uses the narrow form");
        assertTrue(layout.scale() <= 1.15);
        assertTrue(layout.panel().bottom() <= 234);
        assertTrue(layout.rect(CommandMenuLayout.POPOVER).bottom() <= 240);
        Rect hotbar = Rect.of(427 / 2 - 95, 200, 190, 40);
        assertFalse(layout.panel().intersects(hotbar));
        for (String action : CommandMenuLayout.ACTIONS) {
            Rect row = layout.rect(CommandMenuLayout.rowId(action));
            assertTrue(row.bottom() <= layout.panel().bottom());
        }
    }
}
