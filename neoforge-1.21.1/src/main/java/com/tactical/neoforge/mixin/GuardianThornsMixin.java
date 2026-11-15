package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.GuardianThorns;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * One call in one vanilla method: the guardian's own passive spike reaction.
 *
 * <p>{@code Guardian.hurt} hurts the direct attacker for 2 with its {@code thorns} source <em>before</em>
 * {@code super.hurt}, gated by {@code !isMoving}, the two damage-type tags and a living direct entity. The main
 * damage is a different call ({@code Monster.hurt}) and is untouched here, so a cancelled main hit still leaves the
 * spike exactly where vanilla put it - before the hit, not after it.</p>
 *
 * <p>Only that one inner call is wrapped. A body this encounter really holds asks the encounter whether the attacker
 * is a legitimate opposed participant of the same encounter; a wild guardian, another encounter's body and a
 * released unit answer vanilla and are never affected. Nothing new is written: no damage number, no second hurt and
 * no charge - the spikes cost no AP and never advance the paid-action counter.</p>
 */
@Mixin(Guardian.class)
public abstract class GuardianThornsMixin {

    @WrapOperation(method = "hurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tactical$thornsOnlyForThisEncountersOpponent(LivingEntity attacker, DamageSource source,
                                                                 float amount, Operation<Boolean> original) {
        Guardian self = (Guardian) (Object) this;
        if (!GuardianThorns.allowsNativeThorns(self, attacker)) return false;
        return original.call(attacker, source, amount);
    }
}
