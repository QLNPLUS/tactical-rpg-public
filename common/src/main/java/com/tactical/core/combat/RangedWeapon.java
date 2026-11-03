package com.tactical.core.combat;

/**
 * One native ranged weapon as an explicit ballistic configuration.
 *
 * <p>Aim planning and the real projectile have to agree on the same numbers, so the numbers are stated
 * once here instead of being re-typed as literals at each call site. A bow arrow and a crossbow arrow are
 * the same vanilla entity, but they leave the weapon at different speeds, so the launch speed belongs to
 * the weapon and not to the projectile type.</p>
 */
public enum RangedWeapon {
    /** Vanilla bow shot: 1.6 blocks per tick, ordinary arrow gravity and drag. */
    BOW(1.6F, true, 0.99D, 0.6D, BattleRules.BOW_COST),
    /** Vanilla crossbow arrow power (3.15): the same arrow entity, launched heavier than from a bow. */
    CROSSBOW(3.15F, true, 0.99D, 0.6D, BattleRules.CROSSBOW_COST);

    /** Upper bound on one flight, shared with the projectile tracker so a shot cannot hold a turn forever. */
    public static final int MAX_FLIGHT_TICKS = 80;

    private final float speed;
    private final boolean gravity;
    private final double drag;
    private final double waterDrag;
    private final int apCost;

    RangedWeapon(float speed, boolean gravity, double drag, double waterDrag, int apCost) {
        this.speed = speed;
        this.gravity = gravity;
        this.drag = drag;
        this.waterDrag = waterDrag;
        this.apCost = apCost;
    }

    /** Launch speed in blocks per tick, as the vanilla item really applies it. */
    public float speed() { return speed; }

    public boolean gravity() { return gravity; }

    /** Per-tick velocity multiplier in air. */
    public double drag() { return drag; }

    /** Per-tick velocity multiplier while the projectile is in liquid. */
    public double waterDrag() { return waterDrag; }

    /** Base main-action price before the paid-action ramp; never a player inventory cost. */
    public int apCost() { return apCost; }
}
