package com.tactical.neoforge.client.mixin;

import com.tactical.neoforge.client.CameraDirector;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Scoped render-only proxy, so LocalPlayer networking still sees its real camera entity. */
@Mixin(GameRenderer.class)
public abstract class BattleCameraRenderMixin {
    @Redirect(method = "renderLevel", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"))
    private void tactical$setupBattleCamera(Camera camera, BlockGetter level, Entity viewSource,
                                            boolean detached, boolean mirrored, float partialTick) {
        CameraDirector.setupCamera(camera, level, viewSource, detached, mirrored, partialTick);
    }
}
