package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BreezeBurst;
import com.tactical.neoforge.battle.BreezeWindCharges;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The narrow seams one marked breeze wind charge explosion needs inside vanilla's own explosion.
 *
 * <p>Nothing about the explosion is rewritten: the radius, the falloff, the exposure test, the blast resistance,
 * the knockback event, the interaction kind, the particles and the sound stay exactly vanilla's. Only four
 * questions are answered here, and each is answered for the exact {@link Explosion} object the encounter armed, so
 * a nested or unrelated explosion never answers to this encounter's frame:</p>
 *
 * <ol>
 *   <li><b>which blocks may react</b> - a charge this encounter fired interacts with no block at all. The whole
 *       native block-interaction branch is closed by emptying the explosion's own block list at the head of
 *       {@code finalizeExplosion}: that is the list both the {@code BlockState.onExplosionHit} loop and the client
 *       explosion packet are built from, so a block or mod that never consults {@code canTriggerBlocks} is not
 *       reached either, and no client can replay a trigger the server refused. The interaction kind stays
 *       {@code TRIGGER_BLOCK} and the particle choice (the large gust emitter, since the radius is three) stays
 *       vanilla's, because that is what the burst looks and sounds like;</li>
 *   <li><b>{@code canTriggerBlocks}</b> - the same answer again at vanilla's own gate, so a path that asks the
 *       question without iterating a block list is answered the same way;</li>
 *   <li><b>{@code Entity.ignoreExplosion}</b> - vanilla's own per-entity gate, asked before the damage, the
 *       enchantment effects, the velocity write and {@code onExplosionHit}. Refusing a body here is the only place
 *       no later listener can undo: an {@code ExplosionEvent.Detonate} list can be refilled, this gate is read
 *       directly in the loop;</li>
 *   <li><b>{@code Entity.setDeltaMovement}</b> - vanilla's own velocity write. The write really runs, and its real
 *       result is judged afterwards: a body whose setter does nothing accepted no displacement and is left exactly
 *       as vanilla left it, while an accepted displacement is handed to the encounter's one validated motion
 *       owner and the raw write is undone so the body is never moved twice.</li>
 * </ol>
 *
 * <p>The client knockback packets are trimmed once the explosion has finished filling them and before the server
 * builds the packets, for the members this encounter already moved itself.</p>
 */
@Mixin(Explosion.class)
public abstract class TacticalExplosionMixin {

    /** Bind this exact explosion to the frame of the charge that really produced it; nothing else is affected. */
    @Inject(method = "explode", at = @At("HEAD"))
    private void tactical$bindBurst(CallbackInfo callback) {
        BreezeBurst.begin((Explosion) (Object) this);
    }

    /** A marked breeze wind charge never triggers a block, whatever the gamerule says. */
    @Inject(method = "canTriggerBlocks", at = @At("HEAD"), cancellable = true)
    private void tactical$noBlockTriggers(CallbackInfoReturnable<Boolean> callback) {
        Explosion self = (Explosion) (Object) this;
        if (!(self.getDirectSourceEntity() instanceof BreezeWindCharge charge)) return;
        // Explosion has no public level accessor, so the charge's own side is the fact that decides this: an
        // explosion is only ever evaluated on the server anyway, and the client-side read is a defensive no-op.
        if (charge.level().isClientSide) return;
        if (!BreezeWindCharges.owns(charge)) return;
        if (!BreezeBurst.suppressesBlocks(self)) return;
        callback.setReturnValue(false);
    }

    /**
     * Close the whole native block-interaction branch for this exact marked explosion.
     *
     * <p>{@code finalizeExplosion} reads {@code interactsWithBlocks()} once into {@code flag}, chooses the particle
     * with it and then runs the {@code onExplosionHit} loop over {@code toBlow}. Emptying {@code toBlow} here keeps
     * {@code flag} - and therefore the particle choice and the interaction kind the packet carries - exactly as
     * vanilla computed them, while the loop finds nothing to call and the packet carries an empty block list.</p>
     */
    @Inject(method = "finalizeExplosion", at = @At("HEAD"))
    private void tactical$closeBlockInteraction(boolean particles, CallbackInfo callback) {
        Explosion self = (Explosion) (Object) this;
        if (!BreezeBurst.suppressesBlocks(self)) return;
        self.clearToBlow();
    }

    /** A protected body never enters the burst at all: no damage, no effects, no push, no explosion hook. */
    @Redirect(method = "explode",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;ignoreExplosion(Lnet/minecraft/world/level/Explosion;)Z"))
    private boolean tactical$guardBurstTarget(Entity entity, Explosion explosion) {
        if (BreezeBurst.protects(explosion, entity)) return true;
        return entity.ignoreExplosion(explosion);
    }

    /** Let vanilla write the velocity, then judge what the body really accepted. */
    @Redirect(method = "explode",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void tactical$handOffBurstDisplacement(Entity entity, Vec3 wanted) {
        Explosion explosion = (Explosion) (Object) this;
        if (!(entity instanceof LivingEntity body)) {
            entity.setDeltaMovement(wanted);
            return;
        }
        Vec3 before = body.getDeltaMovement();
        body.setDeltaMovement(wanted);
        Vec3 after = body.getDeltaMovement();
        // A body whose own setter does nothing (a shulker's is a no-op) accepts exactly zero here, so it keeps a
        // real zero and is never given a manufactured wind displacement.
        if (!BreezeBurst.take(explosion, body, after.subtract(before))) return;
        // The encounter owns this move now: leave vanilla's raw write undone so the body cannot move twice.
        body.setDeltaMovement(before);
    }

    /** Trim the client knockback of the members this encounter already moved, before the packets are built. */
    @Inject(method = "explode", at = @At("RETURN"))
    private void tactical$consumeOwnedPackets(CallbackInfo callback) {
        BreezeBurst.consumePackets((Explosion) (Object) this);
    }
}
