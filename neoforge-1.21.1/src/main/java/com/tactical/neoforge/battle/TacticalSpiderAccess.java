package com.tactical.neoforge.battle;

/**
 * The one mutable fact the two spider mixins ask, exposed by the mixin and nothing else.
 *
 * <p>Installed when this encounter admits the exact unit and cleared on death, exit, release, finish and a
 * failed admission, so a cleared spider is a plain vanilla one with no leftover pose state - the same contract
 * the enderman handler follows.</p>
 */
public interface TacticalSpiderAccess {

    /** Install (or clear with null) the pose handler this spider's own tick must answer to. */
    void tactical$setPoseHandler(SpiderPoseHandler handler);

    /** The handler the mixins ask, or null for a spider this mod does not hold. */
    SpiderPoseHandler tactical$poseHandler();
}
