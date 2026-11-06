package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.EndermanLandingHandler;
import com.tactical.neoforge.battle.TacticalEndermanAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Take the enderman's water/rain damage off wall-clock ticks for a unit an encounter holds.
 *
 * <p>Vanilla calls this at the very end of {@code LivingEntity#aiStep}:
 * {@code if (!level.isClientSide && isSensitiveToWater() && isInWaterRainOrBubble()) hurt(drown, 1.0F)}. The
 * {@code EnderMan} override of {@code isSensitiveToWater} answers true forever, so a NoAI enderman standing in
 * rain or in water loses that tick damage on every game tick - including during another unit's turn.</p>
 *
 * <p>This injection answers only that one expression, and only for an enderman whose encounter installed a
 * handler: the encounter then settles one real native {@code hurt} of the same source at that unit's own turn
 * start. Nothing else about water changes: a thrown water bottle, lava, freezing and drowning still arrive
 * through their own vanilla paths, and a wild enderman is untouched.</p>
 */
@Mixin(LivingEntity.class)
public abstract class EndermanWaterClockMixin {

    @WrapOperation(method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;isSensitiveToWater()Z"))
    private boolean tactical$waterClock(LivingEntity self, Operation<Boolean> original) {
        // The captured call is an instance call, so its receiver travels with it.
        boolean sensitive = original.call(self);
        if (!sensitive || self.level().isClientSide) return sensitive;
        if (!(self instanceof EnderMan enderman)) return sensitive;
        EndermanLandingHandler handler = ((TacticalEndermanAccess) enderman).tactical$landingHandler();
        if (handler == null) return sensitive;
        // The encounter owns this damage and settles it once, at that unit's own turn start.
        return !handler.tactical$ownsWaterClock(enderman);
    }
}
