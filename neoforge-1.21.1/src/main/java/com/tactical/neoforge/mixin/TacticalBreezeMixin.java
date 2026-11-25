package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BreezeHandler;
import com.tactical.neoforge.battle.TacticalBreezeAccess;
import net.minecraft.world.entity.monster.breeze.Breeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The one piece of mixin state a controlled breeze carries: the seam its own passive deflection reports through.
 *
 * <p>Nothing about vanilla behaviour is replaced here. The encounter never runs the Brain, never touches the
 * sensors and never widens {@code canAttackType}; the deflection itself stays the native one - including the fact
 * that a reversed projectile keeps the owner vanilla gives it, which is exactly why this encounter needs its own
 * separate record of who really deflected it.</p>
 */
@Mixin(Breeze.class)
public abstract class TacticalBreezeMixin implements TacticalBreezeAccess {

    @Unique private BreezeHandler tactical$handler;

    @Override
    public void tactical$setHandler(BreezeHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public BreezeHandler tactical$handler() {
        return tactical$handler;
    }
}
