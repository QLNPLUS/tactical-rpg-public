package com.tactical.neoforge.mixin;

import com.tactical.core.combat.BreezeRules;
import com.tactical.neoforge.battle.BreezeDeflections;
import com.tactical.neoforge.battle.BreezeHandler;
import com.tactical.neoforge.battle.TacticalBreezeAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Report a real native deflection performed by a controlled breeze, and change nothing about the deflection.
 *
 * <p>The seam is {@code Projectile.deflect}: vanilla reaches it from {@code hitTargetOrDeflectSelf} only after the
 * hit entity answered a deflection other than {@code NONE}, and only when that entity is not the one that deflected
 * this projectile last. The call then rewrites the velocity on the server and keeps {@code this.getOwner()} as the
 * projectile's owner - which is exactly why this encounter needs its own record of who really deflected it.</p>
 *
 * <p>The deflection <em>identity</em> is deliberately not compared against {@code ProjectileDeflection.REVERSE}:
 * a breeze answers with its own wrapper that plays the native sound and then applies {@code REVERSE}, so the object
 * a listener sees is not the constant. What is compared instead is the native result: the velocity really became
 * the previous velocity scaled by {@link BreezeRules#REVERSE_VELOCITY_SCALE}. A deflection of any other kind leaves
 * that comparison false and is therefore never recorded, so no projectile gains a relation filter it did not
 * earn.</p>
 */
@Mixin(Projectile.class)
public abstract class TacticalProjectileDeflectionMixin {

    @Unique private Vec3 tactical$velocityBeforeDeflection;
    @Unique private boolean tactical$deflectedByBreeze;

    @Inject(method = "deflect", at = @At("HEAD"))
    private void tactical$captureBeforeDeflection(ProjectileDeflection deflection,
                                                 @javax.annotation.Nullable Entity deflecting,
                                                 @javax.annotation.Nullable Entity owner,
                                                 boolean byAim,
                                                 CallbackInfoReturnable<Boolean> callback) {
        tactical$deflectedByBreeze = false;
        tactical$velocityBeforeDeflection = null;
        Projectile self = (Projectile) (Object) this;
        if (self.level().isClientSide) return;
        if (!(deflecting instanceof Breeze breeze)) return;
        if (BreezeHandlerMissing(breeze)) return;
        tactical$velocityBeforeDeflection = self.getDeltaMovement();
        tactical$deflectedByBreeze = true;
    }

    @Inject(method = "deflect", at = @At("RETURN"))
    private void tactical$recordBreezeDeflection(ProjectileDeflection deflection,
                                                 @javax.annotation.Nullable Entity deflecting,
                                                 @javax.annotation.Nullable Entity owner,
                                                 boolean byAim,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (!tactical$deflectedByBreeze || tactical$velocityBeforeDeflection == null) return;
        tactical$deflectedByBreeze = false;
        Projectile self = (Projectile) (Object) this;
        Vec3 before = tactical$velocityBeforeDeflection;
        tactical$velocityBeforeDeflection = null;
        if (self.level().isClientSide) return;
        if (!callback.getReturnValueZ()) return;
        if (!(deflecting instanceof Breeze breeze)) return;
        Vec3 after = self.getDeltaMovement();
        if (!BreezeDeflections.isNativeReverse(before, after)) return;
        BreezeHandler handler = ((TacticalBreezeAccess) breeze).tactical$handler();
        if (handler == null) return;
        handler.tactical$deflected(self);
    }

    /** Whether that body carries this encounter's routing seam at all; a wild breeze answers nothing. */
    @Unique
    private static boolean BreezeHandlerMissing(Breeze breeze) {
        return ((TacticalBreezeAccess) breeze).tactical$handler() == null;
    }
}
