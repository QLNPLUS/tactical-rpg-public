package com.tactical.neoforge.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The one native entry an encounter may use to land a slime's own contact damage.
 *
 * <p>Vanilla's {@code Slime#dealDamage(LivingEntity)} is protected, and it is the only path that keeps what a
 * slime's damage really is: the alive check, the native attack box (the body widened by vanilla's own reach),
 * the line-of-sight test, the slime attack sound, the enchantment post-effects and the {@code hurt} call with
 * this body's own attack damage - which a magma cube answers with {@code +2}. Writing the damage by hand or
 * routing it through {@code doHurtTarget} would be different behaviour wearing the same name.</p>
 *
 * <p>The descriptor is fixed bytecode fact, not an inherited guess: {@code protected void
 * dealDamage(LivingEntity)} in {@code net.minecraft.world.entity.monster.Slime}. A changed signature fails the
 * game's own mixin application instead of silently doing nothing.</p>
 */
@Mixin(Slime.class)
public interface SlimeDealDamageInvoker {

    /** Drive one native contact attempt at that exact target; the damage is vanilla's own. */
    @Invoker("dealDamage")
    void tactical$dealDamage(LivingEntity target);
}
