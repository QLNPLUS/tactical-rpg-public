package com.tactical.core.combat;

/** Shared tuning constants for the battle ruleset. */
public final class BattleRules {

    public static final int ZONE_RADIUS = 24;
    public static final int ZONE_HEIGHT = 16;
    public static final int PARTICIPANT_CAP = 16;

    public static final int BOW_COST = 15;
    public static final int CROSSBOW_COST = 20;
    public static final int FLEE_COST = 50;

    public static final int FLEE_IMMUNITY_TICKS = 200;

    public static final double FLEE_BASE_CHANCE = 0.5D;
    public static final double FLEE_SPEED_BONUS = 2.0D;
    public static final double FLEE_PER_ENEMY_PENALTY = 0.05D;

    private BattleRules() {
    }
}
