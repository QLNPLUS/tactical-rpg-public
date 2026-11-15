package com.tactical.neoforge.mixin;

import com.tactical.neoforge.battle.GuardianMotionHandler;
import com.tactical.neoforge.battle.TacticalGuardianAccess;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Guardian;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * The routing seam one controlled guardian answers, plus the native attack goal that body really owns.
 *
 * <p>Installed on admission and cleared by every exit path, so nothing of this encounter survives on a released
 * body: a wild guardian has no handler and is never touched by the mixins that ask this question.</p>
 *
 * <p>Neither field carries an initializer on purpose. Vanilla's {@code registerGoals} runs inside the entity's
 * constructor, before any subclass body executes, so a field initializer here would be written <em>after</em> the
 * goal had already been captured and would erase it. Declaring the fields bare leaves the JVM default and is what
 * makes the capture survive construction.</p>
 */
@Mixin(Guardian.class)
public abstract class TacticalGuardianMixin implements TacticalGuardianAccess {

    @Unique private GuardianMotionHandler tactical$handler;

    @Unique private Goal tactical$attackGoal;

    @Override
    public void tactical$setMotionHandler(GuardianMotionHandler handler) {
        tactical$handler = handler;
    }

    @Override
    public GuardianMotionHandler tactical$motionHandler() {
        return tactical$handler;
    }

    @Override
    public void tactical$setAttackGoal(Goal goal) {
        tactical$attackGoal = goal;
    }

    @Override
    public Goal tactical$attackGoal() {
        return tactical$attackGoal;
    }

    @Override
    public boolean tactical$attackGoalInstalled() {
        if (tactical$attackGoal == null) return false;
        for (WrappedGoal wrapped : ((MobGoalSelectorAccess) this).tactical$goalSelector().getAvailableGoals()) {
            if (wrapped.getGoal() == tactical$attackGoal) return true;
        }
        return false;
    }
}
