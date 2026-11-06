package com.tactical.core.combat;

/**
 * The trident throw a drowned really performs, stated once as pure arithmetic.
 *
 * <p>Vanilla {@code Drowned#performRangedAttack} builds a fresh {@code ThrownTrident} and calls
 * {@code shoot(dx, dy + horizontal * 0.2, dz, 1.6F, 14 - difficulty * 4)}. Everything that decides
 * whether that throw can really reach its target therefore has to start from the same numbers: the aim
 * vector is normalized and only then scaled, so the lift changes the angle and never the launch speed.</p>
 *
 * <p>These are read from this version's sources, not chosen by the ruleset: the launch speed and the
 * one-third target height from {@code Drowned}, gravity, air drag and the water inertia from
 * {@code AbstractArrow} / {@code ThrownTrident}, the 10 block radius from the drowned's own
 * {@code RangedAttackGoal}, and the inaccuracy from the difficulty id. The AP price of one throw is a
 * ruleset decision and lives in {@link BattleRules} instead.</p>
 */
public final class TridentThrow {

    /** Launch speed in blocks per tick, as {@code Drowned#performRangedAttack} applies it. */
    public static final float SPEED = 1.6F;
    /** {@code AbstractArrow#getDefaultGravity()}: 0.05 blocks per tick, per tick. */
    public static final double GRAVITY = 0.05D;
    /** {@code AbstractArrow#tick}: velocity multiplier in air. */
    public static final double AIR_DRAG = 0.99D;
    /** {@code ThrownTrident#getWaterInertia()}: velocity multiplier in liquid. */
    public static final double WATER_DRAG = 0.99D;
    /** The native aim adds {@code horizontal * 0.2} to the vertical delta before normalizing. */
    public static final double LIFT = 0.2D;
    /** Vanilla's {@code RangedAttackGoal} attack radius for this throw: real, three-dimensional blocks. */
    public static final double MAX_RANGE = 10.0D;
    /** Vanilla aims at {@code target.getY(1.0F / 3.0F)}, not at the target's feet or eye. */
    public static final double TARGET_AIM_FRACTION = 1.0D / 3.0D;
    /** Vanilla never aims above this pitch, and a throw beyond it has no meaning. */
    private static final double MIN_LENGTH_SQR = 1.0E-8D;

    private TridentThrow() { }

    /**
     * The inaccuracy in degrees the native call passes for one difficulty id.
     *
     * <p>Peaceful 14, easy 10, normal 6, hard 2. Dispersion is vanilla's, so a prediction that must stay
     * honest about "can this really land" is the nominal arc: the noise widens it, it never makes a
     * blocked arc reachable.</p>
     */
    public static double inaccuracy(int difficultyId) {
        return 14.0D - 4.0D * Math.max(0, difficultyId);
    }

    /** One throw's initial velocity, in blocks per tick. */
    public record Velocity(double x, double y, double z) {
        /** The launch speed is preserved by the native normalization; callers can assert exactly that. */
        public double length() { return Math.sqrt(x * x + y * y + z * z); }
    }

    /**
     * The nominal launch velocity of the native throw.
     *
     * @param dx         target X minus shooter X
     * @param dy         the target's one-third height minus the projectile's spawn height
     * @param dz         target Z minus shooter Z
     * @param horizontal horizontal distance between shooter and target, as vanilla's {@code d3}
     * @return the velocity the entity really gets, or {@code null} for a zero-length aim
     */
    public static Velocity nominal(double dx, double dy, double dz, double horizontal) {
        double aimY = dy + horizontal * LIFT;
        double lengthSqr = dx * dx + aimY * aimY + dz * dz;
        if (!(lengthSqr > MIN_LENGTH_SQR)) return null;
        double scale = SPEED / Math.sqrt(lengthSqr);
        return new Velocity(dx * scale, aimY * scale, dz * scale);
    }
}
