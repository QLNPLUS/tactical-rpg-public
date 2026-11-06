package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.EndermanLandingHandler;
import com.tactical.neoforge.battle.TacticalEndermanAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.EnderMan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Route a controlled enderman's native relocations and its hurt reaction through the encounter's own state.
 *
 * <p>Three vanilla seams, each checked against the real build bytecode instead of a guess:</p>
 * <ol>
 *   <li>{@code EnderMan#teleport(DDD)} really calls {@code randomTeleport(DDDZ)} on its own class (the
 *       Methodref owner is {@code net/minecraft/world/entity/monster/EnderMan}, not {@code Entity}), and it does
 *       so <em>after</em> the {@code EnderEntityEvent.EnderEntity} listeners had their chance to veto or re-point
 *       the destination. The landing gate therefore sits on exactly that call: a refused point returns false,
 *       vanilla's own sound and particle path stays untouched, and the body is never moved first and pulled back
 *       afterwards.</li>
 *   <li>{@code teleport()} and {@code teleportTowards(Entity)} are the free relocations vanilla performs on its
 *       own - daylight, unowned damage, and the 64 attempt projectile loop. For a controlled unit they never run:
 *       refusing them here also means vanilla never scans a support column for a destination this encounter
 *       would not use.</li>
 *   <li>{@code hurt} is wrapped, not replaced: the encounter runs vanilla's own damage path inside one bounded
 *       reaction scope and still answers the projectile branch with vanilla's immunity.</li>
 * </ol>
 */
@Mixin(EnderMan.class)
public abstract class TacticalEndermanMixin implements TacticalEndermanAccess {

    @Unique private EndermanLandingHandler tactical$handler;

    @Override
    public void tactical$setLandingHandler(EndermanLandingHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public EndermanLandingHandler tactical$landingHandler() {
        return tactical$handler;
    }

    /**
     * The landing gate: the point vanilla is about to use, after every event listener re-pointed it, validated
     * against the live world before the entity is moved.
     */
    @WrapOperation(method = "teleport(DDD)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/EnderMan;randomTeleport(DDDZ)Z"))
    private boolean tactical$landingGate(EnderMan self, double x, double y, double z, boolean broadcast,
                                         Operation<Boolean> original) {
        // The captured call is an instance call, so its receiver travels with it.
        if (self.level().isClientSide) return original.call(self, x, y, z, broadcast);
        EndermanLandingHandler handler = tactical$handler;
        if (handler == null) return original.call(self, x, y, z, broadcast);
        if (!handler.tactical$mayUseLanding(self, x, y, z)) return false;
        return original.call(self, x, y, z, broadcast);
    }

    /** A free self-teleport is never performed for a controlled unit; it becomes a pending escape request. */
    @Inject(method = "teleport()Z", at = @At("HEAD"), cancellable = true)
    private void tactical$refuseFreeTeleport(CallbackInfoReturnable<Boolean> callback) {
        tactical$refuse(callback);
    }

    /** The same for the teleport that closes in on a target. */
    @Inject(method = "teleportTowards(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void tactical$refuseFreeTeleportTowards(CallbackInfoReturnable<Boolean> callback) {
        tactical$refuse(callback);
    }

    @Unique
    private void tactical$refuse(CallbackInfoReturnable<Boolean> callback) {
        EnderMan self = (EnderMan) (Object) this;
        if (self.level().isClientSide) return;
        EndermanLandingHandler handler = tactical$handler;
        if (handler == null) return;
        handler.tactical$onFreeTeleportRefused(self);
        callback.setReturnValue(false);
    }

    /**
     * One real damage call, one bounded reaction.
     *
     * <p>The supplier runs vanilla's own hurt, so the plain melee path, the clean-water path, the damage
     * reduction and the death handling all stay vanilla's. What the encounter owns is the single reaction this
     * call is allowed and the answer to the projectile branch.</p>
     */
    @WrapMethod(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z")
    private boolean tactical$hurtWithReaction(DamageSource source, float amount, Operation<Boolean> original) {
        EnderMan self = (EnderMan) (Object) this;
        EndermanLandingHandler handler = tactical$handler;
        if (handler == null || self.level().isClientSide) return original.call(source, amount);
        return handler.tactical$hurt(self, source, amount, () -> original.call(source, amount));
    }
}
