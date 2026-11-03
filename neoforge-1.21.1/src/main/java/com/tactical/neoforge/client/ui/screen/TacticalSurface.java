package com.tactical.neoforge.client.ui.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A tactical screen: the battlefield stays visible and interactive behind it, so it is not a modal
 * vanilla screen and it must never pause the game or hide the world.
 *
 * <p>Both the legacy AUI screens and the native ones report this marker, which lets HUD, camera
 * gesture and input-ownership checks stay identical during the migration instead of listing both
 * concrete types at every call site.</p>
 */
public interface TacticalSurface {

    /**
     * The deepest battle-HUD geometry. The charge rail's portraits are real entity models:
     * {@code Portraits.entity} pushes +400 and the vanilla renderer it calls pushes another +50, so
     * nothing the HUD draws in its own plane can be in front of a model it overlaps.
     */
    int PORTRAIT_DEPTH = 450;

    /**
     * The HUD's own floating layers - a hover tooltip and the size tag of a running drag.
     *
     * <p>They must clear {@link #PORTRAIT_DEPTH}, because a tooltip raised from a rail portrait lands
     * exactly where that model is; and they must stay under {@link #TOP_OVERLAY_DEPTH}, so an open
     * modal popover still covers them instead of being painted behind a tooltip. Both are drawn
     * through {@code Draw.atDepth}, which flushes before and after the raised plane.</p>
     */
    int HUD_FLOAT_DEPTH = 520;

    /**
     * Depth every top-overlay drawing uses: a panel tooltip, the command page's popovers and the
     * colour picker, all above {@link #HUD_FLOAT_DEPTH} and therefore above the whole battle HUD.
     */
    int TOP_OVERLAY_DEPTH = 600;

    /**
     * Drawn once per frame <em>after</em> the battle HUD, for the layer that must stay on top of it.
     *
     * <p>The HUD paints from {@code ScreenEvent.Render.Post}, which runs after the screen's own
     * content; anything a screen draws inside its content is therefore under the roster and the
     * charge rail. An open modal popover - the colour picker - belongs above both, so it is drawn
     * here instead. The default does nothing.</p>
     */
    default void renderTopOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
}
