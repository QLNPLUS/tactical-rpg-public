package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BattleEffectInstanceView;
import com.tactical.neoforge.battle.BattleEffects;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MobEffectInstance.class)
public abstract class BattleMobEffectInstanceMixin implements BattleEffectInstanceView {
    @Override
    @Accessor("hiddenEffect")
    public abstract MobEffectInstance tactical$hiddenEffect();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tactical$freezeEffectClock(LivingEntity entity, Runnable promoted, CallbackInfoReturnable<Boolean> cir) {
        if (BattleEffects.freezeVanillaTick(entity, (MobEffectInstance) (Object) this)) cir.setReturnValue(true);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffect;shouldApplyEffectTickThisTick(II)Z"))
    private boolean tactical$oncePerOwnCycle(MobEffect effect, int time, int amplifier) {
        return BattleEffects.shouldApplyPeriodic(effect, time, amplifier, (MobEffectInstance) (Object) this);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffect;applyEffectTick(Lnet/minecraft/world/entity/LivingEntity;I)Z"))
    private boolean tactical$scopeEffectDamage(MobEffect effect, LivingEntity entity, int amplifier) {
        return BattleEffects.applyPeriodic(effect, entity, amplifier, (MobEffectInstance) (Object) this);
    }
}
