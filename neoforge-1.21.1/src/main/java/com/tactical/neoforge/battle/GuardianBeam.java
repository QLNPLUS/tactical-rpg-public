package com.tactical.neoforge.battle;

import java.util.Objects;
import java.util.UUID;

/**
 * One guardian beam's bounded stage state: a launched native attack goal, its frozen price and its one settlement.
 *
 * <p>Vanilla drives the beam from {@code Guardian.GuardianAttackGoal}: {@code start()} pre-heats the count at
 * {@code -10}, the visible charge is {@link #CHARGE_SLACK_TICKS} further ticks and then {@code getAttackDuration()}
 * (80 ticks for the guardian, 60 for the elder) of real charge, and the end of it really calls
 * {@code indirectMagic} hurt and {@code doHurtTarget}. A turn-based ruleset keeps every one of those numbers as the
 * creature's own presentation; what it changes is only who ticks the goal and when the price is committed.</p>
 *
 * <p>This class owns no world and no entities, so the timeline, the frozen price and the single settlement can be
 * decided in a pure test while the server stays the single commit point. Every path is bounded by
 * {@link #DEADLINE_TICKS}: a beam that cannot finish inside it ends instead of holding the turn. That ceiling is
 * deliberately above the longest normal flow so it can never shorten a real charge - it only reclaims a goal that
 * stopped making progress.</p>
 */
public final class GuardianBeam {

    /** Vanilla's {@code attackTime} starts at {@code -10}; ten ticks pass before the visible charge begins. */
    public static final int CHARGE_SLACK_TICKS = 10;
    /** The longest native charge either exact type has: an ordinary guardian's {@code getAttackDuration()}. */
    public static final int LONGEST_NATIVE_CHARGE = 80;
    /**
     * The abnormal ceiling for any path: the pre-heat, the longest native charge and slack for the settlement tick.
     *
     * <p>It is deliberately larger than {@code 10 + 80} and {@code 10 + 60}, so the ordinary flows are never cut
     * short by it; only a beam whose goal stopped advancing is ended here.</p>
     */
    public static final int DEADLINE_TICKS = CHARGE_SLACK_TICKS + LONGEST_NATIVE_CHARGE + 30;

    private final UUID actorId;
    private final UUID targetId;
    private final long epoch;
    private final int price;
    private final int startTick;
    private final int chargeTicks;
    private final int deadlineTick;
    private boolean settled;

    public GuardianBeam(UUID actorId, UUID targetId, long epoch, int price, int startTick, int chargeTicks) {
        this.actorId = Objects.requireNonNull(actorId, "beam actor");
        this.targetId = Objects.requireNonNull(targetId, "beam target");
        if (price <= 0) throw new IllegalArgumentException("A beam must have a positive frozen price");
        if (chargeTicks <= 0) throw new IllegalArgumentException("A beam must have a positive native charge");
        this.epoch = epoch;
        this.price = price;
        this.startTick = startTick;
        this.chargeTicks = chargeTicks;
        this.deadlineTick = startTick + DEADLINE_TICKS;
    }

    public UUID actorId() { return actorId; }

    public UUID targetId() { return targetId; }

    /** The turn generation this beam was launched in; a beam never survives into another own turn. */
    public long epoch() { return epoch; }

    /** The price frozen before the goal was started; never re-quoted and never charged twice. */
    public int price() { return price; }

    public int startTick() { return startTick; }

    /** That exact body's own native charge length, read from {@code getAttackDuration()} at the launch. */
    public int chargeTicks() { return chargeTicks; }

    /** The tick this body's native settlement is due, counting the pre-heat and the real charge. */
    public int settleTick() { return startTick + CHARGE_SLACK_TICKS + chargeTicks; }

    public int deadlineTick() { return deadlineTick; }

    public boolean expired(int now) { return now >= deadlineTick; }

    public boolean settled() { return settled; }

    /** Whether this is the body and opponent this beam was launched for. */
    public boolean names(UUID actor, UUID target) {
        return actorId.equals(actor) && targetId.equals(target);
    }

    /**
     * Close the beam exactly once and report what to do with the price.
     *
     * <p>Returned instead of applied so the caller owns the one charge: a beam that already settled answers
     * {@code false} here, which is what makes a duplicate hand-back - a death scan racing the goal's own end, a
     * timer and a refusal on the same tick - unable to charge a second time.</p>
     *
     * @param reallyHurt whether at least one of the two native calls reported a real hit
     * @return true exactly once when this close is the one that owes the frozen price
     */
    public boolean settle(boolean reallyHurt) {
        if (settled) return false;
        settled = true;
        return reallyHurt;
    }
}
