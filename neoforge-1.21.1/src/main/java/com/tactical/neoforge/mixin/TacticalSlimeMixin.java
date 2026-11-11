package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.SlimeContact;
import com.tactical.neoforge.battle.SlimePoseHandler;
import com.tactical.neoforge.battle.TacticalSlimeAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Two facts a controlled slime owes the encounter: the pose seam it answers, and the result of its own
 * {@code hurt} call.
 *
 * <p>The first is the same contract the spider seam follows - the handler is installed on admission and
 * cleared on every exit, so a wild slime, another encounter's slime and every other monster are untouched.</p>
 *
 * <p>The second exists because {@code dealDamage} returns nothing: the boolean that says whether the target
 * really took the hit is only inside its {@code hurt} call. Wrapping exactly that one call reports vanilla's
 * own answer to the scope that opened it, so "was the action paid for" is decided by the same call that dealt
 * the damage - never by a health delta, which an absorbed hit would silently mis-report, and never by a log
 * line.</p>
 */
@Mixin(Slime.class)
public abstract class TacticalSlimeMixin implements TacticalSlimeAccess {

    @Unique private SlimePoseHandler tactical$handler;

    @Override
    public void tactical$setPoseHandler(SlimePoseHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public SlimePoseHandler tactical$poseHandler() {
        return tactical$handler;
    }

    @WrapOperation(method = "dealDamage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tactical$reportNativeContact(LivingEntity receiver, DamageSource source, float amount,
                                                 Operation<Boolean> original) {
        boolean hurt = original.call(receiver, source, amount);
        SlimeContact.report((Slime) (Object) this, receiver, hurt);
        return hurt;
    }
}
