package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.SpiderPoseHandler;
import com.tactical.neoforge.battle.TacticalSpiderAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuse the native gravity integration for a spider body this encounter has taken.
 *
 * <p>NoAI stops goals and navigation but not {@code travel}: with vanilla gravity still running, a held spider
 * would fall a little every tick between its own turns, and a body whose own edge is being animated would be
 * integrated a second time in the same tick. Pinning it from the encounter tick alone would leave the body
 * oscillating inside every tick and let a client interpolate a slide, which is exactly the "climbs on somebody
 * else's turn" this ruleset must not allow. The one honest seam is the movement step itself.</p>
 *
 * <p>The gate is as narrow as it can be: an exact {@link Spider} whose own encounter installed a pose handler
 * and answered that it still controls this exact body. It does not cancel the tick or {@code aiStep}, so
 * burning, effects, freezing, damage, look control and death handling all still run; a spider whose surface the
 * encounter can no longer prove is not controlled, so the existing fall rule moves it; and a spider without a
 * handler - a wild one, a riding one, one an encounter already released, or another type - is never affected.</p>
 */
@Mixin(LivingEntity.class)
public abstract class SpiderTravelMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void tactical$holdSpiderPose(Vec3 input, CallbackInfo callback) {
        if (!((Object) this instanceof Spider spider)) return;
        if (spider.level().isClientSide) return;
        SpiderPoseHandler handler = ((TacticalSpiderAccess) spider).tactical$poseHandler();
        if (handler == null || !handler.tactical$controls(spider)) return;
        // The held position is the authoritative one: re-apply it, drop any accumulated velocity and any fall
        // damage claim, and skip only this movement step. A controlled body with no pin of its own is still
        // refused the step, so nothing integrates it behind the encounter's back.
        Vec3 feet = handler.tactical$pinFeet(spider);
        if (feet != null) spider.setPos(feet);
        spider.setDeltaMovement(Vec3.ZERO);
        spider.fallDistance = 0.0F;
        callback.cancel();
    }
}
