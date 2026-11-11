package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.SlimePoseHandler;
import com.tactical.neoforge.battle.TacticalSlimeAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuse the native gravity integration for a slime body this encounter has taken.
 *
 * <p>{@code NoAI} stops goals and navigation but not {@code travel}: with vanilla gravity still running, a held
 * slime would sink a little every tick between its own turns, and a body whose hop edge is being animated would
 * be integrated a second time in the same tick. Pinning it from the encounter tick alone would leave the body
 * oscillating inside every tick and let a client interpolate a slide, which is exactly the "moves on somebody
 * else's turn" this ruleset must not allow. The one honest seam is the movement step itself.</p>
 *
 * <p>The gate is as narrow as it can be: an exact {@link Slime} (magma cubes included, they are the same
 * vanilla class family) whose own encounter installed a pose handler and answered that it still controls this
 * exact body. It does not cancel the tick or {@code aiStep}, so burning, effects, freezing, damage, look
 * control, death handling and the native squish presentation all still run; a controlled body keeps that answer
 * while it stands, while an edge animates and while a forced descent carries it - the pin is then the real
 * sample the controller applied, never a stale node - and only a real hand-back (exit, death, removal, another
 * dimension) leaves the body to vanilla physics; a slime without a handler is never affected.</p>
 */
@Mixin(LivingEntity.class)
public abstract class SlimeTravelMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void tactical$holdSlimePose(Vec3 input, CallbackInfo callback) {
        if (!((Object) this instanceof Slime slime)) return;
        if (slime.level().isClientSide) return;
        SlimePoseHandler handler = ((TacticalSlimeAccess) slime).tactical$poseHandler();
        if (handler == null || !handler.tactical$controls(slime)) return;
        // The held position is the authoritative one: re-apply it, drop any accumulated velocity and any fall
        // damage claim, and skip only this movement step.
        Vec3 feet = handler.tactical$pinFeet(slime);
        if (feet != null) slime.setPos(feet);
        slime.setDeltaMovement(Vec3.ZERO);
        slime.fallDistance = 0.0F;
        callback.cancel();
    }
}
