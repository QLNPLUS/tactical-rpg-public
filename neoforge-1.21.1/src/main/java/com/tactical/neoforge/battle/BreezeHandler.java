package com.tactical.neoforge.battle;

import net.minecraft.world.entity.projectile.Projectile;

/**
 * The routing seam a controlled breeze's own native code answers while this encounter holds it.
 *
 * <p>Exactly one native behaviour needs to report back into the encounter: the passive deflection vanilla performs
 * when something hits the body. The projectile keeps the owner vanilla gives it, so the encounter never claims to
 * have fired the reversed shot - it only records which of its own breeze really turned that shot around, which is
 * the one fact the relation filter cannot derive from the projectile's own owner.</p>
 */
public interface BreezeHandler {

    /** One real native deflection of that exact projectile by this body. */
    void tactical$deflected(Projectile projectile);
}
