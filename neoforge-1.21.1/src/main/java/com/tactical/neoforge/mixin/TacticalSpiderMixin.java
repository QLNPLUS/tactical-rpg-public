package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.SpiderPoseHandler;
import com.tactical.neoforge.battle.TacticalSpiderAccess;
import net.minecraft.world.entity.monster.Spider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep a controlled spider's climbing bit on the surface the encounter really holds.
 *
 * <p>Vanilla's {@code Spider#tick} ends with {@code setClimbing(horizontalCollision)}: the bit follows a push,
 * not a surface. That is right for a wild spider and wrong for one this encounter holds, in both directions -
 * a wall position whose last movement did not collide would lose its support, and a floor-standing spider
 * shoved against a wall during somebody else's turn would silently start climbing.</p>
 *
 * <p>The injection is one tail call on that one method: vanilla computes the bit exactly as before and only a
 * spider this encounter admitted gets it restated from the encounter's own wall answer. The control answer and
 * the wall answer are two different questions, so a moving or floor-standing body is still controlled without
 * being declared on a wall. A wild spider, another encounter's spider and every other monster are never
 * touched, and no tick, {@code aiStep}, effect, burning pulse or damage path is skipped.</p>
 */
@Mixin(Spider.class)
public abstract class TacticalSpiderMixin implements TacticalSpiderAccess {

    @Unique private SpiderPoseHandler tactical$handler;

    @Override
    public void tactical$setPoseHandler(SpiderPoseHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public SpiderPoseHandler tactical$poseHandler() {
        return tactical$handler;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void tactical$restateClimbing(CallbackInfo callback) {
        Spider self = (Spider) (Object) this;
        if (self.level().isClientSide) return;
        SpiderPoseHandler handler = tactical$handler;
        if (handler == null || !handler.tactical$controls(self)) return;
        self.setClimbing(handler.tactical$wallPose(self));
    }
}
