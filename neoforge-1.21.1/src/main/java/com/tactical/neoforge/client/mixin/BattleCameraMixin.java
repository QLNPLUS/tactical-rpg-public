package com.tactical.neoforge.client.mixin;

import com.tactical.neoforge.client.CameraDirector;
import com.tactical.neoforge.client.CameraViewEntityAccess;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Changes the eye focus before vanilla/Dungeons zoom, collision, and culling. */
@Mixin(Camera.class)
public abstract class BattleCameraMixin implements CameraViewEntityAccess {
    @Override
    @Accessor("entity")
    public abstract void tactical$setViewEntity(Entity entity);

    @ModifyArgs(method = "setup", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V"))
    private void tactical$useAnchorEyePosition(Args args) {
        Vec3 position = CameraDirector.cameraAnchorPosition(((Camera) (Object) this).getEntity());
        if (position != null) {
            args.set(0, position.x);
            args.set(1, position.y);
            args.set(2, position.z);
        }
    }
}
