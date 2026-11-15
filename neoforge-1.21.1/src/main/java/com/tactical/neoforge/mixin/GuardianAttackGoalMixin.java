package com.tactical.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.tactical.neoforge.battle.GuardianAttackGoalAccess;
import com.tactical.neoforge.battle.NativeBeamBridge;
import com.tactical.neoforge.battle.TacticalGuardianAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The two facts an encounter needs about the vanilla attack goal it borrows: the object itself, and what its own two
 * native hits really did.
 *
 * <p>The goal class is package-private, so it cannot be named from this mod. It is therefore reached exactly where
 * vanilla creates it - its own constructor - and the instance is stored on its guardian. That is the object the
 * native selector holds: no look-alike goal is created, no goal is removed from the selector, and the selector is
 * never ticked as a whole. A guardian whose goal was never constructed (a future version, a body from a proxy)
 * simply has none and the encounter declines to fire instead of inventing a beam.</p>
 *
 * <p>Both wrapped calls are the goal's <em>own</em> settlement step, in vanilla's order: the
 * {@code indirectMagic} hurt first, then {@code doHurtTarget}. Each report carries the exact actor, this very goal
 * object and the target, because two guardians may aim at one body and a nested goal tick inside a damage listener
 * must never fill this drive's receipt. The boolean is the very one vanilla got - an absorbed hit that changes no
 * health is still a hit, a cancelled one is still not, and neither is inferred from a health delta or a log
 * line.</p>
 *
 * <p>The charge counter itself is exposed read/write for exactly one purpose: an admission that borrows the body has
 * to be able to snapshot and restore it, so a rolled-back admission is the same creature it was.</p>
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Guardian$GuardianAttackGoal")
public abstract class GuardianAttackGoalMixin implements GuardianAttackGoalAccess {

    @Shadow @Final private Guardian guardian;

    @Shadow private int attackTime;

    /** Store the real goal on the body that created it, before anything can borrow it. */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void tactical$captureAttackGoal(CallbackInfo callback) {
        if (guardian == null) return;
        ((TacticalGuardianAccess) guardian).tactical$setAttackGoal((Goal) (Object) this);
    }

    @Override
    public int tactical$attackTime() {
        return attackTime;
    }

    @Override
    public void tactical$attackTime(int value) {
        attackTime = value;
    }

    /** The magic part of the settlement: vanilla's own {@code indirectMagic} hurt and its own boolean. */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;"
                            + "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean tactical$reportMagicHit(LivingEntity receiver, DamageSource source, float amount,
                                            Operation<Boolean> original) {
        boolean hurt = original.call(receiver, source, amount);
        NativeBeamBridge.reportMagic(guardian, (Goal) (Object) this, receiver, hurt);
        return hurt;
    }

    /** The physical part of the settlement: vanilla's own {@code doHurtTarget} and its own boolean. */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/monster/Guardian;"
                            + "doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean tactical$reportMeleeHit(Guardian self, Entity receiver, Operation<Boolean> original) {
        boolean hurt = NativeBeamBridge.callPhysical(self, (Goal) (Object) this, receiver,
                () -> original.call(self, receiver));
        NativeBeamBridge.reportMelee(self, (Goal) (Object) this, receiver, hurt);
        return hurt;
    }
}
