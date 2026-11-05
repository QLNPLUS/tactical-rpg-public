package com.tactical.neoforge.battle;

import com.tactical.core.combat.RangedWeapon;

import java.util.Objects;
import java.util.UUID;

/**
 * One blaze salvo's bounded stage state: the vanilla charge, up to three native fireballs, and one hand-back.
 *
 * <p>Vanilla fires its three small fireballs from {@code BlazeAttackGoal}: 60 ticks of charge, then a fireball
 * every 6 ticks, then a 100 tick wall-clock cooldown. A turn-based ruleset keeps the charge and the spacing as
 * the entity's own presentation, but the 100 tick cooldown becomes "once per own turn" - no second volley is
 * inserted into the same turn, and nothing waits on wall-clock time.</p>
 *
 * <p>This class owns no world and no entities: it is the stage arithmetic only, so the timeline can be decided
 * in a pure test and the server can stay the single commit point. Every path is bounded by
 * {@link #HARD_DEADLINE_TICKS}; a salvo that cannot finish inside it ends instead of holding the turn.</p>
 */
public final class BlazeVolley {
    /** Vanilla charge length before the first fireball. */
    public static final int CHARGE_TICKS = 60;
    /** Vanilla spacing between the three fireballs. */
    public static final int SHOT_INTERVAL_TICKS = 6;
    /** Vanilla fires at most three small fireballs per volley. */
    public static final int MAX_SHOTS = 3;
    /**
     * Charge, the two gaps, one full flight lifetime and slack: no path may hold the turn longer.
     *
     * <p>The flight allowance is the same 80 tick cap the projectile tracker already enforces, so the deadline
     * can never be shorter than the slowest projectile this mod tracks.</p>
     */
    public static final int HARD_DEADLINE_TICKS = CHARGE_TICKS + (MAX_SHOTS - 1) * SHOT_INTERVAL_TICKS
            + RangedWeapon.MAX_FLIGHT_TICKS + 8;
    /** The ruleset's own base price for one salvo; configurable, and not a vanilla fact. */
    public static final int DEFAULT_AP_COST = com.tactical.core.combat.BattleRules.BLAZE_VOLLEY_COST;

    private final UUID actorId;
    private final UUID targetId;
    private final long epoch;
    private final int price;
    private final int startTick;
    private final int deadlineTick;
    private int shotsFired;
    private int nextShotTick;
    private boolean chargeSettled;

    public BlazeVolley(UUID actorId, UUID targetId, long epoch, int price, int startTick) {
        this.actorId = Objects.requireNonNull(actorId, "actor id");
        this.targetId = Objects.requireNonNull(targetId, "target id");
        if (price <= 0) throw new IllegalArgumentException("A salvo must have a positive frozen price");
        this.epoch = epoch;
        this.price = price;
        this.startTick = startTick;
        this.deadlineTick = startTick + HARD_DEADLINE_TICKS;
        this.nextShotTick = startTick + CHARGE_TICKS;
    }

    public UUID actorId() { return actorId; }

    public UUID targetId() { return targetId; }

    /** The turn epoch this salvo was planned in; a salvo never survives into another own turn. */
    public long epoch() { return epoch; }

    /** The price frozen before the first real fireball; never re-quoted and never charged twice. */
    public int price() { return price; }

    public int shotsFired() { return shotsFired; }

    public int shotsRemaining() { return MAX_SHOTS - shotsFired; }

    public boolean charging() { return !chargeSettled; }

    /** True from the first tick the vanilla charge has run its course. */
    public boolean chargeDue(int now) { return !chargeSettled && now >= startTick + CHARGE_TICKS; }

    /** Settle the charge exactly once; the server owns what the charge looks like. */
    public void settleCharge() { chargeSettled = true; }

    /** True when the next fireball is due now and the volley still has one to fire. */
    public boolean shotDue(int now) {
        return chargeSettled && shotsRemaining() > 0 && now >= nextShotTick;
    }

    /** Count one real fireball that really joined the world and schedule the next slot. */
    public void onShotFired(int now) {
        if (shotsFired >= MAX_SHOTS) throw new IllegalStateException("A salvo fires at most " + MAX_SHOTS);
        shotsFired++;
        nextShotTick = now + SHOT_INTERVAL_TICKS;
    }

    public int deadlineTick() { return deadlineTick; }

    public boolean expired(int now) { return now >= deadlineTick; }

    /**
     * True when the salvo is over: no shot is left and every projectile has settled, or the deadline passed.
     *
     * <p>A salvo that stops early (target lost, spawn refused) is ended by the caller and never waits here.</p>
     */
    public boolean complete(int now, boolean projectilesPending) {
        return expired(now) || shotsRemaining() == 0 && !projectilesPending;
    }

    /** Stop the remaining shots; the ones already in the world stay final. */
    public void stopRemaining() { shotsFired = MAX_SHOTS; }
}
