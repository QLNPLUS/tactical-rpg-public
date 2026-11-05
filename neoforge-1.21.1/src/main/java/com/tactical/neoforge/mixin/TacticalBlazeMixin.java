package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.ai.TacticalBlazeAccess;
import net.minecraft.world.entity.monster.Blaze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes exactly the two blaze charge entries this mod needs, and nothing else.
 *
 * <p>Vanilla declares {@code void setCharged(boolean)} package-private and {@code boolean isCharged()} private,
 * so neither is reachable from this mod's own packages. Both are invoked directly rather than reimplemented, so
 * the battle drives the same synched bit the entity's own {@code isOnFire()} reads - there is no parallel state
 * and nothing to keep in sync.</p>
 */
@Mixin(Blaze.class)
public interface TacticalBlazeMixin extends TacticalBlazeAccess {

    @Override
    @Invoker("setCharged")
    void tactical$setCharged(boolean charged);

    @Override
    @Invoker("isCharged")
    boolean tactical$isCharged();
}
