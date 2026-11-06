package com.tactical.neoforge.battle;

/**
 * The two enderman-owned facts this encounter has to hold, exposed by the mixin and nothing else.
 *
 * <p>Vanilla {@code EnderMan} relocates itself for free: it teleports when hurt by something that is not a
 * living attacker, it teleports in daylight, and its projectile branch tries up to 64 times. NoAI stops the
 * goals that would do it, but not the ones inside {@code hurt} or a projectile's damage path. The handler is
 * therefore the single routing point: while it is installed the encounter decides every landing point, every
 * reaction and the water clock; once it is cleared (death, exit, finish, failed admission) the entity is a plain
 * vanilla enderman again with no leftover state.</p>
 */
public interface TacticalEndermanAccess {

    /** Install (or clear with null) the handler this unit's native teleports must answer to. */
    void tactical$setLandingHandler(EndermanLandingHandler handler);

    /** The handler the mixins ask, or null for an enderman this mod does not hold. */
    EndermanLandingHandler tactical$landingHandler();
}
