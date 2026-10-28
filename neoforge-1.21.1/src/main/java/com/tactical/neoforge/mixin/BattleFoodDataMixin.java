package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.BattleEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Food consumption, natural regeneration and starvation share the own-cycle clock. */
@Mixin(FoodData.class)
public abstract class BattleFoodDataMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void tactical$foodClock(Player player, CallbackInfo ci) {
        if (BattleEffects.freezeFoodTick(player)) ci.cancel();
    }

    // Installed 1.21.1 FoodData has natural thresholds 10 and 80 plus starvation
    // 80. Each branch gets at most one pulse from one native food tick per cycle.
    // The exhaustion branch still consumes at most one vanilla four-point unit.
    @ModifyConstant(method = "tick", constant = {@Constant(intValue = 10), @Constant(intValue = 80)})
    private int tactical$oneFoodPulse(int original) {
        return BattleEffects.isSettlingFood((FoodData) (Object) this) ? 1 : original;
    }
}
