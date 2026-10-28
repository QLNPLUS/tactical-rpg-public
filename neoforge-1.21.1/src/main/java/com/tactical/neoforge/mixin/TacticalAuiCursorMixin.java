package com.tactical.neoforge.mixin;

import com.sighs.apricityui.layout.Position;
import com.sighs.apricityui.render.Operation;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.tactical.neoforge.client.TacticalScreen;
import com.tactical.neoforge.client.UiZoom;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Use the same callback coordinates for AUI press/release as its hover and vanilla slots. */
@Mixin(value = Operation.class, remap = false)
public abstract class TacticalAuiCursorMixin {
    @Inject(method = "getMousePositionDirectly", at = @At("HEAD"), cancellable = true)
    private static void tactical$callbackCursor(CallbackInfoReturnable<Position> result) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TacticalScreen || (mc.screen instanceof ApricityContainerScreen screen
                && UiZoom.owns(screen.getLinkedDocument()))) {
            var window = mc.getWindow();
            if (window.getScreenWidth() <= 0 || window.getScreenHeight() <= 0) {
                // A minimized GLFW window may report zero dimensions. Keep pointer
                // dispatch outside the document instead of returning Infinity/NaN.
                result.setReturnValue(new Position(-1, -1));
                return;
            }
            result.setReturnValue(new Position(
                    mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth(),
                    mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight()));
        }
    }
}
