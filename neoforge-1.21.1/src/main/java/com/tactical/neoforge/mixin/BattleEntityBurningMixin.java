package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BattleBurning;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Targets the installed 1.21.1 baseTick fire branch, never lavaHurt or environment damage. */
@Mixin(Entity.class)
public abstract class BattleEntityBurningMixin {
    @Redirect(method = "baseTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tactical$freezeBurningPulse(Entity entity, DamageSource source, float amount) {
        return !BattleBurning.freezeStatusTick(entity) && entity.hurt(source, amount);
    }

    // ordinal 0 is fire-immune's native -4 countdown; ordinal 1 is normal burning's -1.
    @Redirect(method = "baseTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setRemainingFireTicks(I)V", ordinal = 1))
    private void tactical$freezeBurningCountdown(Entity entity, int ticks) {
        if (!BattleBurning.freezeStatusTick(entity)) entity.setRemainingFireTicks(ticks);
    }

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
    private void tactical$observeExtinguish(int ticks, CallbackInfo ci) {
        BattleBurning.onFireTicksChanged((Entity) (Object) this, ticks);
    }
}
