package com.tactical.core.combat;

/** Shared tuning constants for the battle ruleset. */
public final class BattleRules {

    public static final int ZONE_RADIUS = 24;
    public static final int ZONE_HEIGHT = 16;
    public static final int PARTICIPANT_CAP = 16;

    public static final int BOW_COST = 15;
    public static final int CROSSBOW_COST = 20;
    /**
     * Base price of one blaze fireball salvo: the mod's own design value, not a vanilla fact.
     *
     * <p>A blaze fires up to three native fireballs in one own turn, but they are one main action, so the
     * price is one salvo price and never a per-projectile price.</p>
     */
    public static final int BLAZE_VOLLEY_COST = 20;
    /**
     * Base price of one drowned trident throw: this ruleset's value, not a vanilla fact.
     *
     * <p>Vanilla fires its trident on a fixed 40 tick interval; a turn-based encounter maps that interval to
     * "once per own turn", and one throw is one main action, so it shares the same successful-action
     * surcharge as every other main action.</p>
     */
    public static final int DROWNED_TRIDENT_COST = 20;
    /**
     * Base price of one enderman teleport: this ruleset's value, not a vanilla fact.
     *
     * <p>Vanilla's enderman teleports for free whenever it is hurt or lost; an encounter prices one real native
     * teleport as a main action, so the same successful-action surcharge applies. A reaction teleport pays the
     * same base price out of the unit's own AP and never counts as a main action.</p>
     */
    public static final int ENDERMAN_TELEPORT_COST = 20;
    /** How far a teleport search looks from the unit, in blocks of real distance. */
    public static final double ENDERMAN_TELEPORT_RANGE = 8.0D;
    /** Bounded candidate columns per teleport search, so one action is never a zone scan. */
    public static final int ENDERMAN_TELEPORT_CANDIDATES = 32;
    /** Bounded candidates per single hurt, shared by every one of the native attempts it drives. */
    public static final int ENDERMAN_HURT_CANDIDATES = 16;
    /** Hard cap on native teleport attempts for one hurt, shared result and all. */
    public static final int ENDERMAN_NATIVE_CALLS = 64;
    /** Below this real distance the unit walks instead of teleporting, unless the environment forces it out. */
    public static final double ENDERMAN_SHORT_RANGE = 6.0D;
    public static final int FLEE_COST = 50;

    public static final int FLEE_IMMUNITY_TICKS = 200;

    public static final double FLEE_BASE_CHANCE = 0.5D;
    public static final double FLEE_SPEED_BONUS = 2.0D;
    public static final double FLEE_PER_ENEMY_PENALTY = 0.05D;

    private BattleRules() {
    }
}
