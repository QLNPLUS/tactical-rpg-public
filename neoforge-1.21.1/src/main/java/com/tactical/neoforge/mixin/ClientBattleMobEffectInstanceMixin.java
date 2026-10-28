package com.tactical.neoforge.mixin;

import com.tactical.neoforge.client.BattleClientState;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps vanilla HUD timers aligned with server turn-time; loaded on the client only. */
@Mixin(MobEffectInstance.class)
public abstract class ClientBattleMobEffectInstanceMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tactical$freezeDisplayedEffect(LivingEntity entity, Runnable promoted, CallbackInfoReturnable<Boolean> cir) {
        MobEffectInstance instance = (MobEffectInstance) (Object) this;
        if (entity.level().isClientSide && BattleClientState.active
                && BattleClientState.participantHealth.containsKey(entity.getId())
                && !instance.getEffect().value().isInstantenous()) cir.setReturnValue(true);
    }
}
