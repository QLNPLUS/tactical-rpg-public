package com.tactical.core.combat;

/** Pure AP math shared by authoritative actions and read-only cost previews. */
public final class ApCalculator {
    public static final int DEFAULT_MAX_AP = 150;
    public static final int INITIAL_AP = 100;
    public static final int AP_RECOVERY = 50;
    public static final int MELEE_COST = 10;
    public static final int ITEM_COST = 8;
    public static final int PLACE_COST = 10;
    public static final int MIN_MOVE_COST = 4;
    public static final int MAX_MOVE_COST = 24;
    private static final double DEFAULT_MOVE_SPEED = 0.1D;

    private ApCalculator() { }

    /**
     * Since 0.20.0 the cap is independent of movement speed for every actor.
     * The retained argument keeps callers source compatible; native max_ap stays a
     * percentage with 100 meaning this new 150 baseline, including saved entities.
     */
    public static int maxAp(double moveSpeed) { return DEFAULT_MAX_AP; }

    public static int moveCost(double moveSpeed) {
        if (moveSpeed <= 0.0D) return MAX_MOVE_COST;
        return clamp((int) Math.round(1.2D / moveSpeed), MIN_MOVE_COST, MAX_MOVE_COST);
    }

    /** Attack speed still governs vanilla combat and initiative, not AP pricing. */
    public static int meleeCost(double attackSpeed) { return MELEE_COST; }

    /** Exact ceil(base * (1 + .25 * successful operations)); saturates safely. */
    public static int paidActionCost(int base, int successfulOperations) {
        long cost = ((long) Math.max(0, base) * (4L + Math.max(0, successfulOperations)) + 3L) / 4L;
        return (int) Math.min(Integer.MAX_VALUE, cost);
    }

    public static int initialAp(int cap, int initial) { return Math.max(0, Math.min(cap, initial)); }

    /** First own turn skips recovery; subsequent turns carry the actual remainder. */
    public static int beginOwnTurnAp(int remaining, int cap, int recovery, boolean firstOwnTurn) {
        return (int) Math.max(0L, Math.min(Math.max(0, cap),
                (long) Math.max(0, remaining) + (firstOwnTurn ? 0L : Math.max(0, recovery))));
    }

    public static int initiative(double attackSpeed, double moveSpeed, int roll) {
        return (int) Math.round(attackSpeed * 10.0D + moveSpeed * 20.0D + roll);
    }
    public static int defaultMaxAp() { return DEFAULT_MAX_AP; }
    public static int defaultMoveCost() { return moveCost(DEFAULT_MOVE_SPEED); }
    public static int defaultMeleeCost() { return MELEE_COST; }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
