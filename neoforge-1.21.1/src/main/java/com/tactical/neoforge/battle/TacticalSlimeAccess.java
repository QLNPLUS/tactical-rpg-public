package com.tactical.neoforge.battle;

import net.minecraft.world.entity.monster.Slime;

/**
 * The one mutable fact the slime mixins ask, exposed by the mixin and nothing else.
 *
 * <p>Installed when this encounter admits the exact unit and cleared on death, exit, release, finish and a
 * failed admission, so a released slime is a plain vanilla one with no leftover pose state - the same contract
 * the spider and enderman seams follow.</p>
 */
public interface TacticalSlimeAccess {

    /** Install (or clear with null) the pose handler this slime's own tick must answer to. */
    void tactical$setPoseHandler(SlimePoseHandler handler);

    /** The handler the mixins ask, or null for a slime this mod does not hold. */
    SlimePoseHandler tactical$poseHandler();
}
