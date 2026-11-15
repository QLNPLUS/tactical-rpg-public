package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.NativeBeamBridge;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The physical part's real return, tapped where it is decided rather than where it is returned.
 *
 * <p>{@code Mob.doHurtTarget} calls the target's own {@code hurt} and then runs enchantment post-attack effects. If
 * those post-effects throw, the caller never sees a boolean at all - and the hit that already landed would be lost.
 * The tap therefore sits on the inner {@code hurt} call itself and only <em>reports</em> its return: the wrapper
 * returns exactly the original value, no behaviour changes, and nothing outside one of this mod's own authorised
 * guardian goal drives is recorded. Other mobs' attacks are untouched in result and in shape.</p>
 *
 * <p>The descriptor is a fixed bytecode fact of this version: the receiver's static type is {@code Entity}, so the
 * call resolves to {@code Entity.hurt(DamageSource, float)}.</p>
 */
@Mixin(Mob.class)
public abstract class MobAttackHurtReceiptMixin {

    @WrapOperation(method = "doHurtTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tactical$reportInnerPhysicalHurt(Entity receiver, DamageSource source, float amount,
                                                     Operation<Boolean> original) {
        boolean hurt = original.call(receiver, source, amount);
        NativeBeamBridge.reportInnerHurt((Mob) (Object) this, receiver, hurt);
        return hurt;
    }
}
