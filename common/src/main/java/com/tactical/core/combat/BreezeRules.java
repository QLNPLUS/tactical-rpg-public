package com.tactical.core.combat;

/**
 * Prices, bounds and native constants of the breeze's own vocabulary.
 *
 * <p>Everything here is either this ruleset's own tactical value or a frozen figure read from the exact
 * Minecraft 1.21.1 sources of {@code Breeze}, {@code Shoot}, {@code LongJump}, {@code LongJumpUtil},
 * {@code BreezeUtil}, {@code BreezeWindCharge} and {@code ProjectileDeflection}. The numbers are collected in one
 * pure class - no world, no entity, no clock - so the native limits can be asserted in a plain unit test instead
 * of being scattered through the encounter and re-guessed in a fixture.</p>
 *
 * <p>Two different kinds of number live here and they are never mixed:</p>
 * <ul>
 *   <li><b>native facts</b> - the firing band, the difficulty spread, the projectile speed, the inhale and
 *       recovery lengths, the allowed jump angles, the outward candidate habit, the reverse deflection and the
 *       burst radius. These are read from vanilla and are only mirrored, never re-tuned.</li>
 *   <li><b>this ruleset's design values</b> - what one wind charge and one jump cost in AP, and how many of each
 *       one own turn may take. Vanilla has no AP and no per-turn quota at all, so these are decisions of this
 *       mod and are not claimed to be vanilla numbers.</li>
 * </ul>
 */
public final class BreezeRules {

    private BreezeRules() {}

    // ------------------------------------------------------------------ this ruleset's prices and quotas

    /** Base AP of one native wind charge; the ordinary paid-action surcharge applies on top. */
    public static final int WIND_CHARGE_COST = 20;

    /** Base AP of one native long jump; the ordinary paid-action surcharge applies on top. */
    public static final int JUMP_COST = 20;

    /**
     * Per-kind action quotas of one own turn.
     *
     * <p>Deliberately <em>not</em> the shared {@code paidActions} counter. That counter is this ruleset's
     * surcharge clock: a jump increments it, and reading it as "the shot is already spent" would make the one
     * follow-up shot a jump exists for impossible - which is exactly the bug the shell family already had to
     * repair. Each quota below counts only its own kind of action.</p>
     */
    public static final int SHOT_QUOTA = 1;
    public static final int MANEUVER_QUOTA = 1;

    /** True while one more action of that kind is still inside its quota. */
    public static boolean withinQuota(int used, int quota) {
        return used < Math.max(0, quota);
    }

    // ------------------------------------------------------------------ native: the wind charge

    /** {@code Shoot.ATTACK_RANGE_MIN_SQRT}: the shot is refused at or below this squared distance. */
    public static final int SHOT_RANGE_MIN_SQ = 4;

    /** {@code Shoot.ATTACK_RANGE_MAX_SQRT}: the shot is refused at or above this squared distance. */
    public static final int SHOT_RANGE_MAX_SQ = 256;

    /** {@code Shoot.PROJECTILE_MOVEMENT_SCALE}: the native {@code shoot(..., speed, spread)} speed. */
    public static final float SHOT_SPEED = 0.7F;

    /** {@code Shoot.UNCERTAINTY_BASE - difficulty * UNCERTAINTY_MULTIPLIER}. */
    public static final int SHOT_UNCERTAINTY_BASE = 5;
    public static final int SHOT_UNCERTAINTY_MULTIPLIER = 4;

    /** {@code Shoot.SHOOT_INITIAL_DELAY_TICKS}: the native inhale before the charge leaves. */
    public static final int INHALE_TICKS = 15;

    /** {@code Shoot.SHOOT_RECOVER_DELAY_TICKS}: the native recovery after the charge left. */
    public static final int RECOVER_TICKS = 4;

    /** The native {@code isFacingTarget} dot-product threshold: the shot needs the body looking at the target. */
    public static final double FACING_DOT = 0.5D;

    /** The native facing test's normalisation guard: a zero-length look vector never counts as facing. */
    public static final double FACING_EPSILON = 1.0E-7D;

    /** Native direct-hit damage of one wind charge ({@code entity.hurt(..., 1.0F)}). */
    public static final float DIRECT_HIT_DAMAGE = 1.0F;

    /** Native burst radius of a breeze wind charge. */
    public static final float BURST_RADIUS = 3.0F;

    /** How long one controlled wind charge may keep the encounter busy, in ticks. */
    public static final int WIND_CHARGE_FLIGHT_TICKS = 100;

    /**
     * Native inaccuracy for one difficulty id, exactly as {@code Shoot.tick} computes it.
     *
     * <p>Deliberately not clamped to a positive number: vanilla hands {@code 5 - 4 * difficulty} straight to
     * {@code shoot}, so hard difficulty really asks for a negative spread, and a ruleset that "fixed" that would
     * be firing a different projectile than the one the game fires. Only the difficulty index itself is clamped,
     * to the four values vanilla has.</p>
     *
     * @param difficulty vanilla difficulty id, {@code 0} peaceful through {@code 3} hard
     */
    public static int shotSpread(int difficulty) {
        return SHOT_UNCERTAINTY_BASE - clampDifficulty(difficulty) * SHOT_UNCERTAINTY_MULTIPLIER;
    }

    private static int clampDifficulty(int difficulty) {
        return Math.max(0, Math.min(3, difficulty));
    }

    /**
     * The native {@code isTargetWithinRange} band: strictly inside {@code (2, 16]} blocks.
     *
     * <p>Vanilla asks {@code d0 > 4.0 && d0 < 256.0} on the squared distance, so the lower bound is exclusive and
     * the upper bound is too. A body at the exact boundary is out of band in vanilla as well.</p>
     */
    public static boolean withinShotBand(double distanceSq) {
        return distanceSq > (double) SHOT_RANGE_MIN_SQ && distanceSq < (double) SHOT_RANGE_MAX_SQ;
    }

    /**
     * The native {@code Shoot.isFacingTarget} answer, from the already normalised look and target vectors.
     *
     * <p>{@code vec3.dot(vec31) > 0.5} in vanilla. The two vectors are supplied by the caller because producing
     * them needs the live entity; the comparison itself is the frozen native rule.</p>
     */
    public static boolean isFacingTarget(double lookX, double lookY, double lookZ,
                                         double toTargetX, double toTargetY, double toTargetZ) {
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double toTargetLength = Math.sqrt(toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ);
        if (lookLength < FACING_EPSILON || toTargetLength < FACING_EPSILON) return false;
        double dot = (lookX * toTargetX + lookY * toTargetY + lookZ * toTargetZ) / (lookLength * toTargetLength);
        return dot > FACING_DOT;
    }

    // ------------------------------------------------------------------ native: the long jump

    /** {@code LongJump.MAX_JUMP_VELOCITY}: the native ceiling the requested vector is checked against. */
    public static final float JUMP_MAX_VELOCITY = 1.4F;

    /** {@code LongJump.INHALING_DURATION_TICKS}: the native charge pose before the body leaves the ground. */
    public static final int JUMP_INHALE_TICKS = 10;

    /** {@code LongJump.REQUIRED_AIR_BLOCKS_ABOVE}: head room the native take-off test demands. */
    public static final int JUMP_REQUIRED_AIR_BLOCKS = 4;

    /** {@code LongJump.ALLOWED_ANGLES}: the only five launch angles vanilla will ever use. */
    private static final int[] JUMP_ANGLES = {40, 55, 60, 75, 80};

    /** A defensive copy of the native angle list; the encounter shuffles this copy, never the constant. */
    public static int[] jumpAngles() {
        return JUMP_ANGLES.clone();
    }

    /** {@code LongJumpUtil}'s candidate habit: {@code Mth.lerp(random, 4.0F, 8.0F)} blocks behind the target. */
    public static final double JUMP_BEHIND_MIN = 4.0D;
    public static final double JUMP_BEHIND_MAX = 8.0D;

    /** {@code BreezeUtil.randomPointBehindTarget}: {@code 90} degrees of gaussian spread off the target's head. */
    public static final float JUMP_BEHIND_SPREAD_DEGREES = 90.0F;

    /**
     * How many landing candidates one own turn may consider.
     *
     * <p>This is this ruleset's own bound, not a vanilla one: vanilla asks the world for a single random point
     * behind its target and gives up when that one does not work. A turn-based planner may look at a few more
     * places, but never at an unbounded scan, so the whole proposal stays one cached decision of one own turn.</p>
     */
    public static final int JUMP_MAX_CANDIDATES = 16;

    /** {@code LongJump.outOfAggroRange}: a target further than this is dropped instead of jumped after. */
    public static final double JUMP_AGGRO_RANGE = 24.0D;

    /** {@code LongJump.tooCloseForJump}: a target closer than this leaves no room for the native arc. */
    public static final double JUMP_TOO_CLOSE = 4.0D;

    /**
     * The horizontal pad {@code LongJumpUtil} subtracts from the requested destination before it solves the arc
     * ({@code .normalize().scale(0.5)}), so a requested landing point is really the last half block of the jump.
     */
    public static final double JUMP_LANDING_PAD = 0.5D;

    /**
     * The drag vanilla's ordinary ground/air step would apply, and which the launch's own friction discard removes.
     *
     * <p>{@code LongJump.start} calls {@code setDiscardFriction(true)} together with the launch vector, and
     * {@code LivingEntity.travel} then writes the next velocity as {@code (dx, dy - gravity, dz)} without
     * multiplying any component by a friction factor. This constant is kept only as the named answer the native
     * branch does <em>not</em> use; the arc below never applies it.</p>
     */
    public static final double AIR_DRAG = 0.98D;

    /** Ceiling on the frames one controlled jump may fly before the body is handed back to the fall rule. */
    public static final int JUMP_MAX_TICKS = 40;

    /** A horizontal/vertical speed below this is treated as stopped when the arc is integrated. */
    public static final double JUMP_STOP_SPEED_SQ = 1.0E-4D;

    /**
     * Whether the native take-off conditions may hold at all.
     *
     * <p>{@code LongJump.canRun} asks, in this order: the body stands on ground or in water; it is not in the
     * water-swim state; the target is inside the aggro range, further than {@link #JUMP_TOO_CLOSE} away, and there
     * are {@link #JUMP_REQUIRED_AIR_BLOCKS} blocks of room above the body. A body that carries the native
     * levitation effect is not standing on ground by vanilla's own test, so it is refused by the same first gate
     * and is never jumped out of a levitation hold by this ruleset.</p>
     *
     * @param onGround        the body's real {@code onGround()} answer
     * @param inWater         the body's real {@code isInWater()} answer
     * @param shouldSwim      vanilla's {@code Swim.shouldSwim} answer for that body
     * @param freeBlocksAbove whole free blocks measured above the body, up to the required count
     * @param targetDistance  the real distance to the locked opponent
     */
    public static boolean jumpAllowed(boolean onGround, boolean inWater, boolean shouldSwim,
                                      int freeBlocksAbove, double targetDistance) {
        if (!onGround && !inWater) return false;
        if (shouldSwim) return false;
        if (!(targetDistance < JUMP_AGGRO_RANGE)) return false;
        if (!(targetDistance > JUMP_TOO_CLOSE)) return false;
        return freeBlocksAbove >= JUMP_REQUIRED_AIR_BLOCKS;
    }

    /**
     * One integrated airborne step of the native launch vector, in its own friction-discard branch.
     *
     * <p>The vector itself comes from {@code LongJumpUtil}, which is the native solver, and the body it is written
     * to is the one {@code LongJump.start} has just marked {@code setDiscardFriction(true)}. That branch of
     * {@code LivingEntity.travel} is not the ordinary mob step: the position advances by the current velocity,
     * the horizontal components of the velocity are carried over <b>un-dragged</b>, and only the vertical one
     * loses the body's own gravity ({@code setDeltaMovement(dx, dy - gravity, dz)}). Applying the usual
     * {@code 0.98} here would fold a drag the launch really discards into the arc, which is exactly how a proved
     * arc can end up short of the ground it was solved for.</p>
     *
     * <p>The physics is mirrored, not re-derived, and it is done in one place so the whole arc can be proved
     * before the body is displaced at all.</p>
     *
     * @param position current feet position
     * @param velocity current velocity
     * @param gravity  the body's own gravity
     * @return the next {@code {x, y, z}} position, then velocity, in one array of six numbers
     */
    public static double[] stepAirborne(double[] position, double[] velocity, double gravity) {
        double[] next = new double[6];
        next[0] = position[0] + velocity[0];
        next[1] = position[1] + velocity[1];
        next[2] = position[2] + velocity[2];
        next[3] = velocity[0];
        next[4] = velocity[1] - gravity;
        next[5] = velocity[2];
        return next;
    }

    /** True when an integrated arc has stopped making progress: no speed left or the frame budget is gone. */
    public static boolean airborneFinished(double[] velocity, int frames, int maxFrames) {
        if (frames >= Math.max(1, maxFrames)) return true;
        double speedSq = velocity[0] * velocity[0] + velocity[1] * velocity[1] + velocity[2] * velocity[2];
        return speedSq < JUMP_STOP_SPEED_SQ;
    }

    // ------------------------------------------------------------------ native: the passive deflection

    /** {@code ProjectileDeflection.REVERSE}: the velocity is scaled by this before the body sets it back. */
    public static final double REVERSE_VELOCITY_SCALE = -0.5D;

    /** {@code ProjectileDeflection.REVERSE} turns the projectile by this many degrees, uniformly. */
    public static final float REVERSE_YAW_MIN = 170.0F;
    public static final float REVERSE_YAW_MAX = 190.0F;

    /** The burst may never push a body the explosion itself wrote no displacement into. */
    public static final double NO_DISPLACEMENT = 1.0E-8D;

    // ------------------------------------------------------------------ native: burst relations

    /**
     * Whether a body the burst really accepted may be moved by it at all.
     *
     * @param acceptedDelta the real change the native setter produced on that body
     */
    public static boolean burstDisplaces(double acceptedDeltaX, double acceptedDeltaY, double acceptedDeltaZ) {
        double lengthSq = acceptedDeltaX * acceptedDeltaX + acceptedDeltaY * acceptedDeltaY
                + acceptedDeltaZ * acceptedDeltaZ;
        return lengthSq > NO_DISPLACEMENT;
    }
}
