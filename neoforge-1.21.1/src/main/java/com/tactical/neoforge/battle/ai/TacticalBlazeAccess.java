package com.tactical.neoforge.battle.ai;

/**
 * The one narrow handle the battle needs on a vanilla blaze: its own charge flag.
 *
 * <p>Vanilla keeps the "charging" bit in synched entity data and reads it through {@code isCharged()} to drive
 * {@code isOnFire()}. The setter is package-private and the getter is private, so a battle that wants the real
 * vanilla charge presentation needs exactly these two entries - no second flag, no client-side imitation.</p>
 *
 * <p>Nothing else on the entity is touched: not health, not position, not equipment, not the fire ticks the
 * client draws.</p>
 */
public interface TacticalBlazeAccess {

    /** Set vanilla's own charging bit; the visual is the entity's existing {@code isOnFire()}. */
    void tactical$setCharged(boolean charged);

    /** Read-only: vanilla's own charging bit, for diagnostics and fixtures. Changes nothing. */
    boolean tactical$isCharged();
}
