package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.ai.TacticalCreeperAccess;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hold only the creeper-specific fuse; super.tick and ordinary entity hooks still run. */
@Mixin(Creeper.class)
public abstract class TacticalCreeperMixin implements TacticalCreeperAccess {
    @Shadow private int swell;
    @Shadow private int oldSwell;
    @Unique private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> tactical$STATE =
            net.minecraft.network.syncher.SynchedEntityData.defineId(Creeper.class,
                    net.minecraft.network.syncher.EntityDataSerializers.BYTE);

    @Inject(method = "defineSynchedData", at = @At("RETURN"))
    private void tactical$defineState(net.minecraft.network.syncher.SynchedEntityData.Builder builder,
                                      CallbackInfo ci) {
        builder.define(tactical$STATE, (byte) 0);
    }

    @Unique
    private byte tactical$state() {
        return ((Creeper) (Object) this).getEntityData().get(tactical$STATE);
    }

    @Override
    public void tactical$setControlled(boolean controlled) {
        ((Creeper) (Object) this).getEntityData().set(tactical$STATE, (byte) (controlled ? 1 : 0));
        swell = oldSwell = 0;
    }

    @Override public boolean tactical$isControlled() { return (tactical$state() & 1) != 0; }
    @Override public void tactical$setPreparing(boolean preparing) {
        ((Creeper) (Object) this).getEntityData().set(tactical$STATE,
                (byte) ((tactical$state() & 1) | (preparing ? 2 : 0)));
    }
    @Override public boolean tactical$isPreparing() { return (tactical$state() & 2) != 0; }
    @Override @Accessor("explosionRadius") public abstract int tactical$getExplosionRadius();
    @Override @Invoker("explodeCreeper") public abstract void tactical$explodeCreeper();

    @Inject(method = "tick", at = @At("HEAD"))
    private void tactical$holdFuse(CallbackInfo ci) {
        if (tactical$isControlled()) swell = oldSwell = 0;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/monster/Creeper;getSwellDir()I"))
    private int tactical$ignoreTickFuse(Creeper creeper) {
        return tactical$isControlled() ? 0 : creeper.getSwellDir();
    }

    // Also guard unusual Fuse NBT <= 0. Explicit tactical invoker calls bypass this tick redirect.
    @Redirect(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/monster/Creeper;explodeCreeper()V"))
    private void tactical$noTickDetonation(Creeper creeper) {
        if (!tactical$isControlled()) ((TacticalCreeperAccess) creeper).tactical$explodeCreeper();
    }

    @Inject(method = "getSwelling", at = @At("HEAD"), cancellable = true)
    private void tactical$steadyWarningPose(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (tactical$isControlled()) cir.setReturnValue(tactical$isPreparing() ? 0.75F : 0.0F);
    }
}
