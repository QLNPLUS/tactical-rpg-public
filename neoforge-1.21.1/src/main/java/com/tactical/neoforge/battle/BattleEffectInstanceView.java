package com.tactical.neoforge.battle;

import net.minecraft.world.effect.MobEffectInstance;

/** Implemented by the effect instance mixin without exposing mutation of the hidden chain. */
public interface BattleEffectInstanceView {
    MobEffectInstance tactical$hiddenEffect();
}
