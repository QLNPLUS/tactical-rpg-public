package com.tactical.neoforge.battle;

/**
 * The encounter-side decisions the enderman mixins ask for, and nothing else.
 *
 * <p>Vanilla owns three behaviours this ruleset has to route through its own state: the private
 * {@code teleport(DDD)} landing point (which the {@code EnderEntity} event may re-point after this mod had its
 * say), the free random relocation vanilla performs on its own (daylight, unowned damage, and the 64 attempt
 * loop of the projectile branch), and the per-tick water/rain damage call. A unit in a battle answers through
 * this seam; a wild enderman has no handler and keeps every vanilla behaviour untouched.</p>
 *
 * <p>Nothing here moves an entity or charges anything: each answer is a decision about this encounter's own
 * state, and the price is charged by the battle at its single commit point.</p>
 */
public interface EndermanLandingHandler {

    /**
     * Whether the native entry may really move this body to that requested point.
     *
     * <p>Called after vanilla's own {@code EnderEntity} listeners finished re-pointing or cancelling the
     * destination and before {@code randomTeleport} moves the entity, so this is the only place where the point
     * that will really be used can be validated. A refused point never moves and never charges.</p>
     */
    boolean tactical$mayUseLanding(net.minecraft.world.entity.monster.EnderMan enderman,
                                   double x, double y, double z);

    /**
     * Carry out one reaction to that real damage and return the hurt answer the caller must use.
     *
     * <p>The supplier runs vanilla's own hurt. The implementation owns the one bounded reaction of this call:
     * at most {@code ENDERMAN_HURT_CANDIDATES} landing attempts and at most one move, shared by every native
     * teleport attempt inside that same call, with no scope left behind when it throws.</p>
     */
    boolean tactical$hurt(net.minecraft.world.entity.monster.EnderMan enderman,
                          net.minecraft.world.damagesource.DamageSource source, float amount,
                          java.util.function.BooleanSupplier vanillaHurt);

    /**
     * True when this encounter owns the per-tick water/rain clock of that unit, so the vanilla call is deferred
     * to the unit's own turn start instead of damaging it on wall-clock ticks.
     */
    boolean tactical$ownsWaterClock(net.minecraft.world.entity.monster.EnderMan enderman);

    /**
     * A vanilla free relocation was attempted (unowned damage, daylight, or the projectile loop). It never
     * happens on wall-clock time for a controlled unit: it becomes a pending escape request for its own turn.
     */
    void tactical$onFreeTeleportRefused(net.minecraft.world.entity.monster.EnderMan enderman);
}
