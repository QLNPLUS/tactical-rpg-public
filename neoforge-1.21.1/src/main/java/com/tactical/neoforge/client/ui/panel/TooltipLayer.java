package com.tactical.neoforge.client.ui.panel;

import com.tactical.neoforge.client.ui.screen.TacticalSurface;

/**
 * Which pass of the frame draws a panel's tooltip.
 *
 * <p>The battle HUD paints from {@code ScreenEvent.Render.Post}, i.e. <em>after</em> a screen's own
 * content, so a tooltip drawn with the panel ends up under the roster and the charge rail - the tooltip's
 * own coordinates can be covered by a portrait. A tactical surface therefore paints its tooltip from
 * {@code renderTopOverlay}, which runs after the HUD at {@link TacticalSurface#TOP_OVERLAY_DEPTH}.</p>
 *
 * <p>That hook only runs while the HUD itself is drawn. With the HUD hidden ({@code hideGui}) the panel
 * is still on screen, so the tooltip falls back to the content pass: exactly one of the two passes draws
 * it in every case, never neither and never both.</p>
 *
 * <p>Pure, so "the tooltip is drawn once, and above the HUD whenever the HUD is there" is checkable
 * without a game window.</p>
 */
public enum TooltipLayer {

    /** Drawn with the panel, inside the screen's own content - the fallback when no HUD is drawn. */
    CONTENT,
    /** Drawn by the surface's top overlay, after the HUD and above its depth. */
    TOP_OVERLAY;

    /** The pass that draws this frame's tooltip for a screen whose HUD visibility is {@code hideGui}. */
    public static TooltipLayer forFrame(boolean hideGui) {
        return hideGui ? CONTENT : TOP_OVERLAY;
    }

    /** True when the content pass has to draw it (and the top overlay must stay silent). */
    public boolean drawsInContent(boolean hideGui) {
        return forFrame(hideGui) == CONTENT;
    }
}
