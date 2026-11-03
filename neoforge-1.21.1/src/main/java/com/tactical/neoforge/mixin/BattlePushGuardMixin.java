package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BattleContactGuard;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the physical half of intrusion: a hostile that a full encounter refuses may not displace
 * a participant, and a participant may not displace it.
 *
 * <p>The damage gate already cancels the refused mob's hits, so this is not a damage rule - it is
 * the collision rule that damage cancellation never covered. {@code Entity.push(Entity)} is the
 * single collision entry point in 1.21.1 ({@code LivingEntity.pushEntities} calls
 * {@code other.push(this)} for every pushable neighbour in either direction), so one injection
 * covers both sides of the pair.</p>
 *
 * <p>Scope is one pair at a time and is decided by {@link BattleContactGuard}: participants,
 * players, unowned non-hostiles, other encounters' units and every non-battle collision keep
 * vanilla behaviour. Nothing global is disabled - knockback, explosions, vehicles and mob-to-mob
 * pushing are untouched.</p>
 */
@Mixin(Entity.class)
public abstract class BattlePushGuardMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void tactical$guardIsolationPush(Entity other, CallbackInfo info) {
        if (BattleContactGuard.blocksPush((Entity) (Object) this, other)) info.cancel();
    }
}
