package com.tactical.neoforge.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Retains virtual dispatch to ServerPlayer's synchronization overrides. */
@Mixin(LivingEntity.class)
public interface BattleLivingEntityEffectsAccess {
    @Invoker("onEffectUpdated")
    void tactical$effectUpdated(MobEffectInstance effect, boolean attributes, Entity source);

    @Invoker("onEffectRemoved")
    void tactical$effectRemoved(MobEffectInstance effect);

    @Accessor("lastHurt")
    float tactical$lastHurt();

    @Accessor("lastHurt")
    void tactical$lastHurt(float amount);
}
