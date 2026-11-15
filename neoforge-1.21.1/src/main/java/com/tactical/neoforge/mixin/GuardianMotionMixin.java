package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.GuardianMotion;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two native movement steps a held guardian must not run on its own.
 *
 * <p>{@code NoAI} stops goals, navigation and the target selector, but it does not stop movement: {@code
 * Guardian.travel} still integrates water physics every tick. Beside an encounter that writes its own animated
 * frames, that is a second integration of the same body - it would drift the unit off the point its turn is priced
 * on, in front of a client that interpolates the slide. The one honest seam is the movement step itself, so
 * {@code travel} is skipped for a body this encounter really holds; the encounter writes the position, the delta
 * and the fall claim on the same tick.</p>
 *
 * <p>{@code Guardian.aiStep}'s other motion is the land flop: a guardian that is out of water while standing adds a
 * random impulse and a random yaw and clears its own ground flag. A guardian the encounter holds is never legally
 * on land - its held node requires water at the real feet and at the real eye - so the flop is this encounter's
 * decision, not vanilla's. The single {@code onGround()} call inside that branch is wrapped, and answering
 * {@code false} while the body is held skips exactly that branch: no impulse, no random yaw, no cleared ground
 * flag, and no early return anywhere else in {@code aiStep}.</p>
 *
 * <p>Neither injection cancels the tick, {@code aiStep} as a whole, the environment, death handling or the native
 * tail, spike and particle presentation; a guardian without a handler - a wild one, another encounter's, a
 * released one - takes the original path in both places.</p>
 */
@Mixin(Guardian.class)
public abstract class GuardianMotionMixin {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void tactical$ownWaterTravel(Vec3 input, CallbackInfo callback) {
        Guardian self = (Guardian) (Object) this;
        if (!GuardianMotion.controls(self)) return;
        self.setDeltaMovement(Vec3.ZERO);
        self.fallDistance = 0.0F;
        callback.cancel();
    }

    @WrapOperation(method = "aiStep",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Guardian;onGround()Z"))
    private boolean tactical$noLandFlopWhenHeld(Guardian self, Operation<Boolean> original) {
        if (GuardianMotion.controls(self)) return false;
        return original.call(self);
    }
}
