package com.tactical.neoforge.client;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/** Battle targeting replaces only the crosshair; the entire vanilla status HUD stays visible. */
public final class VanillaHudGuard {
    private static final ResourceLocation CROSSHAIR = ResourceLocation.withDefaultNamespace("crosshair");
    private VanillaHudGuard() {}

    public static void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (BattleClientState.active && CROSSHAIR.equals(event.getName())) event.setCanceled(true);
    }
}
