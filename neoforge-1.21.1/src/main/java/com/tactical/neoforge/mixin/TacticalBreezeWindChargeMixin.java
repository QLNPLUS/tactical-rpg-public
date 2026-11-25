package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.tactical.neoforge.battle.BreezeBurst;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Arm and disarm the encounter's burst scope around exactly one native breeze wind charge explosion.
 *
 * <p>The whole seam is a synchronous pair around {@code BreezeWindCharge.explode}, written as a wrap so the scope
 * is dropped on <em>every</em> exit - a normal return, an early return and a throwing native call alike. A plain
 * {@code RETURN} injector would never run on a throw, and an armed frame that survived its own call would then be
 * the innermost frame of the next marked burst on this thread, which is exactly the stale arming the frame stack
 * exists to prevent.</p>
 *
 * <p>A wild breeze's charge, a player's own wind charge and every charge another encounter fired carry no mark of
 * this one here, so nothing about them changes: {@link BreezeBurst#arm} answers {@code null} and the native call
 * runs untouched inside an empty scope.</p>
 */
@Mixin(BreezeWindCharge.class)
public abstract class TacticalBreezeWindChargeMixin {

    @WrapMethod(method = "explode")
    private void tactical$aroundBurst(Vec3 centre, Operation<Void> original) {
        BreezeWindCharge self = (BreezeWindCharge) (Object) this;
        // The scope decides for itself whether this charge is ours; a foreign charge simply arms nothing.
        BreezeBurst.arm(self);
        try {
            original.call(centre);
        } finally {
            BreezeBurst.disarm(self);
        }
    }
}
