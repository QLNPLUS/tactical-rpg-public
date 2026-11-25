package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tactical.neoforge.battle.BreezeHitOrigins;
import com.tactical.neoforge.battle.BreezeWindCharges;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keep one marked wind charge's owned origin alive across exactly its own native direct-hit call.
 *
 * <p>The native {@code onHitEntity} hurts the body, runs the post-attack enchantments and then - on the same stack -
 * bursts. Everything the encounter really does when that hurt lands (a locked target that dies or leaves, a firing
 * body that dies, an encounter that ends) removes the charge's mark and discards the projectile, and none of that can
 * stop a method that is already executing. The snapshot taken at the head of this call is what still says "this burst
 * is ours" a few instructions later.</p>
 *
 * <p>The seam is a wrap, not a {@code RETURN} injector, so the token is dropped on every exit - a normal return, an
 * early return and a throwing call alike. Only a marked {@code BreezeWindCharge} records anything: another wind
 * charge kind, and a charge this encounter never fired, run with an empty stack exactly as before.</p>
 */
@Mixin(AbstractWindCharge.class)
public abstract class TacticalWindChargeHitMixin {

    @WrapMethod(method = "onHitEntity")
    private void tactical$keepDirectHitOrigin(EntityHitResult hit, Operation<Void> original) {
        if (!((Object) this instanceof BreezeWindCharge charge)) {
            original.call(hit);
            return;
        }
        // An unmarked nested call did not push a token and must not pop an outer call's saved origin.
        if (!BreezeWindCharges.owns(charge)) {
            original.call(hit);
            return;
        }
        BreezeHitOrigins.begin(charge);
        try {
            original.call(hit);
        } finally {
            BreezeHitOrigins.end(charge);
        }
    }

}
