package com.tactical.neoforge.client.ui;

import com.tactical.neoforge.client.ui.panel.TooltipLayer;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tooltip layer: the battle HUD paints after a screen's content, so a tooltip drawn with the panel
 * can be covered by the charge rail's portraits. It is drawn by the surface's top overlay instead -
 * and by the content pass only when no HUD is drawn at all, so it is never missing and never drawn
 * twice.
 */
class TooltipLayerTest {

    /** The deepest HUD geometry: the charge rail's entity portraits (Portraits.entity +400, vanilla +50). */
    private static final int DEEPEST_HUD_DEPTH = 450;

    @Test
    void withTheHudOnTheTooltipIsDrawnAfterIt() {
        assertEquals(TooltipLayer.TOP_OVERLAY, TooltipLayer.forFrame(false),
                "a visible HUD paints after the panel: the tooltip has to come from the top overlay");
        assertFalse(TooltipLayer.TOP_OVERLAY.drawsInContent(false), "and the content pass stays silent");
    }

    @Test
    void withTheHudHiddenTheContentPassStillDrawsIt() {
        assertEquals(TooltipLayer.CONTENT, TooltipLayer.forFrame(true),
                "hideGui stops the top-overlay hook, so the content pass owes the tooltip");
        assertTrue(TooltipLayer.CONTENT.drawsInContent(true));
    }

    @Test
    void exactlyOnePassDrawsItInEitherState() {
        for (boolean hideGui : new boolean[]{true, false}) {
            int draws = 0;
            if (TooltipLayer.CONTENT.drawsInContent(hideGui)) draws++;
            if (TooltipLayer.forFrame(hideGui) == TooltipLayer.TOP_OVERLAY) draws++;
            assertEquals(1, draws, "hideGui=" + hideGui + " must draw the tooltip exactly once");
        }
    }

    @Test
    void theTopOverlayDepthClearsTheDeepestHudLayer() {
        assertTrue(TacticalSurface.TOP_OVERLAY_DEPTH > DEEPEST_HUD_DEPTH,
                "the tooltip must be above the portrait models, not merely above the HUD's plain fills");
        assertEquals(600, TacticalSurface.TOP_OVERLAY_DEPTH,
                "the existing convention for the top overlay is +600");
    }
}
