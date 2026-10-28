package com.tactical.neoforge.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Explicit setter for {@code Mob#persistenceRequired}.
 *
 * <p>Vanilla exposes only {@code setPersistenceRequired()} ("set true") plus a getter, so a
 * battle that temporarily makes a unit persistent could not be undone exactly. Admission
 * rollbacks must restore the original value in both directions, hence this accessor. The field was
 * verified against the installed version's sources: {@code Mob.persistenceRequired} (private).</p>
 */
@Mixin(Mob.class)
public interface BattleMobPersistenceAccess {

    @Accessor("persistenceRequired")
    void tactical$persistenceRequired(boolean required);
}
