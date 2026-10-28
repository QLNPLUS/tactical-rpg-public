package com.tactical.neoforge.client.mixin;

import com.tactical.neoforge.client.BattleScreenInput;
import com.tactical.neoforge.client.DungeonsInputBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional raw callback bridge: AUI screens never receive camera gestures. */
@Mixin(MouseHandler.class)
public abstract class DungeonsScreenMouseMixin {
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Unique private double tactical$dx;
    @Unique private double tactical$dy;

    @Inject(method = "onMove", at = @At("HEAD"), require = 0)
    private void tactical$capture(long window, double x, double y, CallbackInfo ci) {
        tactical$dx = x - xpos;
        tactical$dy = y - ypos;
    }

    @Inject(method = "onMove", at = @At("TAIL"), require = 0)
    private void tactical$move(long window, double x, double y, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // A grabbed world cursor already executes Dungeons' normal vanilla hook.
        if (window == mc.getWindow().getWindow() && (mc.screen != null || !mc.mouseHandler.isMouseGrabbed()))
            DungeonsInputBridge.move(tactical$dx, tactical$dy);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void tactical$wheel(long window, double x, double y, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().getWindow() && BattleScreenInput.scroll(y)) ci.cancel();
    }
}
