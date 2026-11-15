package com.tactical.neoforge.battle;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Guardian;

/**
 * The one mutable fact the guardian mixins ask, exposed by the mixin and nothing else.
 *
 * <p>Installed when this encounter admits the exact unit and cleared on death, exit, release, finish and a failed
 * admission, so a released guardian is a plain vanilla one with no leftover movement state - the same contract
 * the spider, slime and enderman seams follow.</p>
 *
 * <p>It answers two questions. Owning the body means owning its <em>displacement</em>: the native water travel must
 * not integrate a second time beside the encounter's own frames, and the native land flop must be this encounter's
 * decision rather than vanilla's. Owning it also decides who may take its passive spikes: vanilla's own reaction is
 * kept, but only for a real opposed participant of this same encounter. Everything else about the creature -
 * effects, burning, damage, death, look control, the tail and spike presentation - keeps running untouched.</p>
 */
public interface GuardianMotionHandler {

    /** Whether this encounter really owns that exact body's movement right now. */
    boolean tactical$controls(Guardian guardian);

    /**
     * Whether vanilla's own thorn reaction may run for that attacker.
     *
     * <p>The default refuses, so a seam that only knows how to move a body can never silently hand its spikes to an
     * arbitrary attacker; the encounter that holds the body answers with its own opposition test.</p>
     */
    default boolean tactical$allowsThorns(Guardian guardian, LivingEntity attacker) {
        return false;
    }
}
