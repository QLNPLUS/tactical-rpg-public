package com.tactical.neoforge.battle;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Guardian;

/**
 * The two mutable facts the guardian mixins keep on the exact entity.
 *
 * <p>The motion handler is installed at admission and cleared on every exit, so a released or dead guardian is a
 * plain vanilla guardian with no leftover routing - a wild guardian and another encounter's guardian are never
 * affected. The attack goal is the object vanilla itself constructed for that body: it is captured once, in the
 * goal's own constructor, and is never a look-alike created by this mod.</p>
 */
public interface TacticalGuardianAccess {

    /** Install (or clear with null) the motion handler this guardian's own tick must answer to. */
    void tactical$setMotionHandler(GuardianMotionHandler handler);

    /** The handler the mixins ask, or null for a guardian this mod does not hold. */
    GuardianMotionHandler tactical$motionHandler();

    /**
     * Remember the native attack goal that body really installed.
     *
     * <p>Written from the goal's own constructor, which vanilla runs while the entity is still being constructed -
     * so the field must never be re-initialized afterwards, or the capture would be silently dropped.</p>
     */
    void tactical$setAttackGoal(Goal goal);

    /** That body's real native attack goal, or null when this mod cannot prove one. */
    Goal tactical$attackGoal();

    /**
     * Whether that captured goal is still the very object in this body's own goal selector.
     *
     * <p>The identity is what makes the beam vanilla's: the mod drives the instance vanilla created and added, not a
     * look-alike. A body whose selector no longer holds it - a future version that replaced the goal - answers false.
     */
    boolean tactical$attackGoalInstalled();
}
