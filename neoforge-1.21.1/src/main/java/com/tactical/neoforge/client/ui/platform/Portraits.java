package com.tactical.neoforge.client.ui.platform;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;

/**
 * The only place that renders a living entity inside the HUD besides {@link Draw}.
 *
 * <p>Portraits are real models, never baked screenshots: the same
 * {@code InventoryScreen.renderEntityInInventoryFollowsMouse} the vanilla inventory uses, clipped to
 * the frame area so a model can never bleed out of its box. Drawing order is the caller's job - the
 * HUD paints the frame of a later lane after the model of the earlier one.</p>
 */
public final class Portraits {

    private Portraits() {
    }

    /**
     * Renders {@code entity} centred inside {@code area}.
     *
     * @param area    the inner area of the portrait, already inside the frame
     * @param pointer pointer position the model turns towards; the model's own idle pose is used
     */
    public static void entity(GuiGraphics graphics, LivingEntity entity, int left, int top, int right, int bottom,
                              float pointerX, float pointerY) {
        if (entity == null || right <= left || bottom <= top) return;
        float fit = Math.min((right - left - 2) / Math.max(0.5F, entity.getBbWidth()),
                (bottom - top - 2) / Math.max(0.5F, entity.getBbHeight()));
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.enableScissor(left, top, right, bottom);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, left, top, right, bottom,
                        Math.max(1, (int) fit), 0.0F, pointerX, pointerY, entity);
            } finally {
                graphics.disableScissor();
            }
        } finally {
            graphics.pose().popPose();
        }
    }
}
