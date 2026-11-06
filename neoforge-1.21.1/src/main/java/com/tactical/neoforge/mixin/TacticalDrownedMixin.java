package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.TacticalDrownedAccess;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Drowned;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keep the drowned's own swimming pose while a battle holds the unit, and borrow its pose transients instead of
 * inheriting them.
 *
 * <p>Vanilla {@code updateSwimming()} runs on every tick from {@code Entity#baseTick} and only chooses the water
 * navigation while {@code isEffectiveAi()} is true. The NoAI lock every admitted unit carries makes that false, so
 * a battle-controlled drowned would stand motionless in the middle of a lake with its walking pose, be pushed by
 * every current ({@code isPushedByFluid()} is {@code !isSwimming()}) and visually sink while the encounter pins
 * it. The injection replaces exactly that pose decision - no {@code aiStep} is cancelled, the water navigation is
 * never assigned while control holds, and no vanilla goal is unblocked.</p>
 *
 * <p>A drowned can also already be doing something when the encounter admits it: holding a throw pose, flagged
 * aggressive, swimming. Those are the entity's own transients, not the battle's, so they are recorded when control
 * is taken and given back exactly when it ends - a failed admission restores the unit it borrowed, and a normal
 * exit cannot leave this encounter's pose behind on a released unit. Nothing here touches equipment or NBT.</p>
 */
@Mixin(Drowned.class)
public abstract class TacticalDrownedMixin implements TacticalDrownedAccess {

    @Unique private boolean tactical$controlled;
    @Unique private boolean tactical$originalUsing;
    @Unique private boolean tactical$originalAggressive;
    @Unique private boolean tactical$originalSwimming;

    @Override
    public void tactical$setControlled(boolean controlled) {
        Drowned self = (Drowned) (Object) this;
        if (controlled) {
            if (tactical$controlled) return;
            tactical$originalUsing = self.isUsingItem();
            tactical$originalAggressive = self.isAggressive();
            tactical$originalSwimming = self.isSwimming();
            // A pre-battle throw pose belongs to the vanilla goal the encounter is taking over; it must not be
            // presented as this encounter's action.
            if (tactical$originalUsing) self.stopUsingItem();
            if (tactical$originalAggressive) self.setAggressive(false);
            tactical$controlled = true;
            return;
        }
        if (!tactical$controlled) return;
        tactical$controlled = false;
        // Give every borrowed transient back: this is the one place that knows what the unit looked like before.
        self.setSwimming(tactical$originalSwimming);
        self.setAggressive(tactical$originalAggressive);
        if (self.isUsingItem()) self.stopUsingItem();
        if (tactical$originalUsing) self.startUsingItem(InteractionHand.MAIN_HAND);
    }

    @Override
    public boolean tactical$isControlled() {
        return tactical$controlled;
    }

    @Inject(method = "updateSwimming", at = @At("HEAD"), cancellable = true)
    private void tactical$swimPoseUnderBattleControl(CallbackInfo callback) {
        Drowned self = (Drowned) (Object) this;
        // The client keeps vanilla's own answer: this flag only ever exists on the authoritative side.
        if (!tactical$controlled || self.level().isClientSide) return;
        self.setSwimming(self.isInWater());
        callback.cancel();
    }
}
