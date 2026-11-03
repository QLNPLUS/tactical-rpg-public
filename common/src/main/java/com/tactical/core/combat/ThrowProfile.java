package com.tactical.core.combat;

/**
 * One thrown projectile's flight configuration, stated once so planning and the real entity agree.
 *
 * <p>A splash potion is not an arrow. It leaves at 0.75 blocks per tick instead of a bow's 1.6, its own
 * gravity is 0.05, and the vanilla throwable keeps 0.8 of its velocity per tick in water instead of an
 * arrow's 0.6. Reusing the bow numbers would let a plan that says "this lands on the target" and the potion
 * that really lands somewhere else be two different computations. The numbers here are read from
 * {@code ThrownPotion#getDefaultGravity} and {@code ThrowableProjectile#tick} for 1.21.1.</p>
 *
 * <p>Legacy raw-parameter callers keep their own values; nothing here rewrites an arrow shot.</p>
 */
public record ThrowProfile(float speed, boolean gravity, double drag, double waterDrag) {
    /** Vanilla splash potion thrown by a witch: 0.75 speed, 0.05 gravity, 0.99 air / 0.8 water drag. */
    public static final ThrowProfile SPLASH_POTION = new ThrowProfile(0.75F, true, 0.99D, 0.8D);

    public ThrowProfile {
        if (!(speed > 0.0F)) throw new IllegalArgumentException("A thrown projectile needs a launch speed");
        if (!(drag > 0.0D && drag <= 1.0D)) throw new IllegalArgumentException("Drag must be in (0, 1]");
        if (!(waterDrag > 0.0D && waterDrag <= 1.0D)) throw new IllegalArgumentException("Water drag must be in (0, 1]");
    }
}
