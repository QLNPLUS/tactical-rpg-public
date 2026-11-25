package com.tactical.core.combat;

/**
 * Prices and bounds for the shulker's own actions and for the encounter's controlled body motion.
 *
 * <p>Every number here is this ruleset's own tactical value or a frozen bound, never a claim about a vanilla
 * damage figure. The native mechanics themselves are not re-tuned: a shulker bullet still deals its native hit
 * and its native levitation, and a shell still applies its native armour. What is bounded here is how much of
 * that a turn may buy ({@link #SHULKER_BULLET_COST}), how long a native-born projectile may hold the encounter
 * ({@link #SHULKER_BULLET_FLIGHT_TICKS}), and how far one controlled turn may raise or carry a body
 * ({@link #LEVITATION_MAX_RISE_BLOCKS}, {@link #PUSH_MAX_TICKS}).</p>
 *
 * <p>Pure arithmetic only: no world, no entity, no clock. The world-dependent half lives in the encounter's own
 * motion driver, which asks these functions instead of hard-coding the same limits a second time.</p>
 */
public final class MotionRules {

    private MotionRules() {}

    /** Base AP of one shulker bullet; the ordinary paid-action surcharge applies on top. */
    public static final int SHULKER_BULLET_COST = 20;

    /** Base AP of one shulker relocation; the ordinary paid-action surcharge applies on top. */
    public static final int SHULKER_TELEPORT_COST = 20;

    /** Base AP of a shulker reaction relocation: a reaction never reads or advances the surcharge. */
    public static final int SHULKER_REACTION_COST = SHULKER_TELEPORT_COST;

    /**
     * How long this type's projectile may keep the encounter busy.
     *
     * <p>Deliberately longer than the arrow rule: a native shulker bullet curving around cover legitimately
     * outlives a straight arrow, and cutting it off at the arrow bound would delete a shot the actor paid for.
     * Only this type is extended; every other projectile keeps its own bound.</p>
     */
    public static final int SHULKER_BULLET_FLIGHT_TICKS = 200;

    /** Native attempt count and reach of one relocation, kept because the native entry is what really runs. */
    public static final int SHULKER_TELEPORT_ATTEMPTS = 5;
    public static final int SHULKER_TELEPORT_RANGE = 8;

    /**
     * Per-type action quotas of one own turn, and of one own cycle for the reaction.
     *
     * <p>These are deliberately <em>not</em> the shared {@code paidActions} counter. That counter is this
     * ruleset's surcharge clock, and reading it as "the main attack is spent" made the approved follow-up
     * impossible: a relocation that incremented it could never be followed by the shot the relocation was
     * made for. Each quota below counts only its own kind of action.</p>
     */
    public static final int SHULKER_SHOT_QUOTA = 1;
    public static final int SHULKER_MANEUVER_QUOTA = 1;
    public static final int SHULKER_REACTION_QUOTA = 1;

    /** True while one more action of that kind is still inside its quota. */
    public static boolean withinQuota(int used, int quota) {
        return used < Math.max(0, quota);
    }

    /** Native firing band of the shell: the visible entrance refuses a target beyond this squared distance. */
    public static final int SHULKER_SHOT_RANGE_SQ = 400;

    /** Raw peek values: the native open-attack window and the native idle peek. */
    public static final int SHULKER_PEEK_OPEN = 100;
    public static final int SHULKER_PEEK_IDLE = 30;
    public static final int SHULKER_PEEK_CLOSED = 0;

    /** Native replication scan radius and divisor, used only to describe what vanilla really did. */
    public static final int SHULKER_REPLICATION_RADIUS = 8;
    public static final int SHULKER_REPLICATION_DIVISOR = 5;

    /**
     * How long the shell stays visibly open after a shot, expressed in <em>turn opportunities</em>.
     *
     * <p>Not a wall clock. A native shulker bullet may legitimately hold the encounter for up to
     * {@link #SHULKER_BULLET_FLIGHT_TICKS} ticks, and a fixed wall-clock window shorter than that would expire
     * before the opponent whose turn it blocks ever got to attack the open shell - which would delete the whole
     * point of opening it. The window therefore lasts until this unit's own next turn begins, or until it ends a
     * turn on purpose with nothing to shoot. It is presentation and timing, never an extra action.</p>
     */
    public static final int SHULKER_OPEN_WINDOW_TURNS = 1;

    /** Ticks the controlled driver waits for one accepted impulse to settle before handing the body back. */
    public static final int PUSH_MAX_TICKS = 40;

    /** Hard ceiling on one turn's controlled rise, whatever the native effect level says. */
    public static final int LEVITATION_MAX_RISE_BLOCKS = 4;

    /**
     * Blocks one own turn raises a body that still carries the native levitation effect.
     *
     * <p>Vanilla levitation at level zero lifts about one block per second; a turn has no seconds, so the
     * translation is one block per effect level - {@code amplifier + 1} - bounded by
     * {@link #LEVITATION_MAX_RISE_BLOCKS}. This is the encounter's own rule and is not claimed to be vanilla's
     * per-tick motion.</p>
     */
    public static int levitationRiseBlocks(int amplifier) {
        int blocks = Math.max(1, amplifier + 1);
        return Math.min(blocks, LEVITATION_MAX_RISE_BLOCKS);
    }

    /**
     * The feet height one rise may really reach, given the free space above the body.
     *
     * @param feetY        the body's real feet height now
     * @param wantedBlocks what {@link #levitationRiseBlocks(int)} asked for
     * @param freeBlocks   whole blocks of free body space measured above the body in the live world
     * @return the feet height after the rise; never below {@code feetY}
     */
    public static double levitationTargetFeet(double feetY, int wantedBlocks, int freeBlocks) {
        int blocks = Math.max(0, Math.min(wantedBlocks, freeBlocks));
        return feetY + blocks;
    }

    /**
     * Whether one accepted impulse has settled: the body is on the ground and carries no meaningful motion.
     *
     * <p>Shared by every body the driver carries so a push cannot hold the encounter for its whole budget when
     * the impact stopped it on the first tick.</p>
     */
    public static boolean impulseSettled(boolean onGround, double speedSq) {
        return onGround && speedSq < 0.0009D;
    }

    /** True when that budget is exhausted; a push that never settles is still handed back here. */
    public static boolean impulseExpired(int elapsedTicks) {
        return elapsedTicks >= PUSH_MAX_TICKS;
    }

    /**
     * How many fractions of one accepted impulse segment are sampled, from all of it down to none of it.
     *
     * <p>A push is not its two endpoints. The encounter has to know the <em>last real point</em> along the
     * segment the whole body can occupy, so the segment is walked in small steps and the first fraction that
     * passes every gate wins. Stepping from {@code 1.0} downwards is what makes the answer "as far as the world
     * really allows" instead of "all of it or nothing".</p>
     */
    public static final int IMPULSE_CLIP_SAMPLES = 24;

    /**
     * The fractions of one impulse to try, longest first, ending at {@code 0} (no displacement at all).
     *
     * @param samples how many intermediate fractions to try; values below one are raised to one
     */
    public static double[] impulseClipFractions(int samples) {
        int count = Math.max(1, samples);
        double[] fractions = new double[count + 1];
        for (int index = 0; index < count; index++) {
            fractions[index] = (double) (count - index) / (double) count;
        }
        fractions[count] = 0.0D;
        return fractions;
    }

    /**
     * Whether this encounter is the sole physics writer for one body's own client.
     *
     * <p>An encounter that pays for a body's displacement must be the only thing moving it. Vanilla integrates
     * levitation and gravity inside {@code LivingEntity.travel}, and for the <em>owning</em> player that local
     * integration is what fights the server: the client rises by itself, the server snaps it back, and the body
     * visibly ramps and resets inside its own turn. The answer therefore has to be exactly as narrow as the
     * situation it describes:</p>
     *
     * <ul>
     *   <li>client side only - a dedicated server has no local player and its own bodies stay native;</li>
     *   <li>the body really is the local player of this client, never a remote member or a mob;</li>
     *   <li>the stamped client state really says a battle is active;</li>
     *   <li>and that exact body is one of its participants, so leaving, ending or a stale stamp falls back to
     *       native physics immediately.</li>
     * </ul>
     *
     * <p>Pure on purpose: every input is a fact the caller has already read, so the whole decision is testable
     * without a world, a client or a clock.</p>
     */
    public static boolean battleOwnsLocalTravel(boolean clientSide, boolean localPlayer, boolean battleActive,
                                               boolean participant) {
        return clientSide && localPlayer && battleActive && participant;
    }
}
