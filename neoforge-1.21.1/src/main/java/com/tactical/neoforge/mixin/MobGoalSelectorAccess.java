package com.tactical.neoforge.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The one read-only reach into a body's own goal selector, so an encounter can prove which goal object it drives.
 *
 * <p>{@code Mob.goalSelector} is a protected final field of {@code Mob}, not of the guardian subclass, and this mod
 * only ever reads it: the identity question is "is the goal I captured the very object still installed here", which
 * is exactly what makes a borrowed attack vanilla's rather than a look-alike. Nothing is added, removed or ticked
 * through this accessor.</p>
 */
@Mixin(Mob.class)
public interface MobGoalSelectorAccess {

    /** That entity's own goal selector, as vanilla built it. */
    @Accessor("goalSelector")
    GoalSelector tactical$goalSelector();
}
