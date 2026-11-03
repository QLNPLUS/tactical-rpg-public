package com.tactical.core.progression;

/**
 * The vanilla experience curve as pure arithmetic, so a balance can be derived and re-derived exactly.
 *
 * <p>The wallet is never {@code totalExperience}: that field is a cumulative score that commands, the
 * enchanting table and third-party mods can leave inconsistent with the bar. The authoritative balance is
 * the level plus the progress inside the current level, using the same curve vanilla's
 * {@code Player#getXpNeededForNextLevel} uses on 1.21.1:</p>
 *
 * <pre>
 *   level &lt; 15 : 7 + 2 * level
 *   level &lt; 30 : 37 + 5 * (level - 15)
 *   otherwise   : 112 + 9 * (level - 30)
 * </pre>
 *
 * <p>All arithmetic is done in {@code long}/{@code double} with saturation, so an absurd level produced by
 * a command cannot wrap into a negative balance.</p>
 */
public final class ExperienceCurve {

    /** Level bound of this arithmetic; far above anything a survival game reaches. */
    public static final int MAX_LEVEL = 1_000_000;

    private ExperienceCurve() { }

    /** XP needed to advance from {@code level} to the next one. */
    public static int xpForNextLevel(int level) {
        int safe = Math.max(0, Math.min(MAX_LEVEL, level));
        if (safe >= 30) return 112 + (safe - 30) * 9;
        if (safe >= 15) return 37 + (safe - 15) * 5;
        return 7 + safe * 2;
    }

    /** Total XP accumulated when the bar reaches exactly {@code level} with empty progress. */
    public static long xpAtLevelStart(int level) {
        int safe = Math.max(0, Math.min(MAX_LEVEL, level));
        long total;
        if (safe <= 15) {
            // sum over i in [0, safe) of (7 + 2i)
            total = 7L * safe + (long) safe * (safe - 1);
        } else if (safe <= 30) {
            int above = safe - 15;
            total = 315L + 37L * above + 5L * above * (above - 1) / 2L;
        } else {
            int above = safe - 30;
            total = 1395L + 112L * above + 9L * above * (above - 1) / 2L;
        }
        return Math.max(0L, total);
    }

    /**
     * The spendable balance of one vanilla experience state.
     *
     * <p>Progress outside {@code [0, 1)} is clamped: a value produced by a broken mod is treated as the
     * nearest legal bar position instead of becoming free or negative XP.</p>
     *
     * <p>This is the continuous curve. It is what {@link #split(double)} inverts and what the round-trip
     * tests check; a wallet decision must use {@link #integerBalance(int, float)} instead, because a vanilla
     * bar is a fraction of a level and a {@code float} cannot hold every fraction exactly.</p>
     */
    public static double balance(int level, double progress) {
        int safeLevel = Math.max(0, Math.min(MAX_LEVEL, level));
        double fraction = Double.isFinite(progress) ? Math.max(0.0D, Math.min(Math.nextDown(1.0D), progress)) : 0.0D;
        return (double) xpAtLevelStart(safeLevel) + fraction * xpForNextLevel(safeLevel);
    }

    /**
     * XP points inside one level, as a whole number, recovered from the stored {@code float} bar.
     *
     * <p>Vanilla keeps the bar as {@code float}, so {@code 35/37} is stored as {@code 0.9459459185600281}
     * and multiplying it back gives {@code 34.99999898...}. A wallet must not turn that representation error
     * into one missing or one free XP point, so the value is snapped to the nearest integer when it is within
     * a few float ulps of it, and otherwise floored - never rounded up - which keeps a genuinely fractional
     * bar from growing. The result is always inside {@code [0, span-1]}, so a bar stored at or past full
     * never becomes a free level.</p>
     */
    public static long pointsWithinLevel(int level, float progress) {
        int span = xpForNextLevel(Math.max(0, Math.min(MAX_LEVEL, level)));
        if (span <= 0 || !Float.isFinite(progress) || progress <= 0.0F) return 0L;
        double raw = (double) progress * (double) span;
        if (raw >= (double) span) return span - 1L;
        long nearest = Math.round(raw);
        double tolerance = Math.max(1.0E-6D, (double) span * 1.0E-6D);
        long points = Math.abs(raw - (double) nearest) <= tolerance ? nearest : (long) Math.floor(raw);
        return Math.max(0L, Math.min((long) span - 1L, points));
    }

    /**
     * The canonical wallet: level plus whole XP points inside it.
     *
     * <p>Display, affordability and the verification after a spend all use this one function, so the number a
     * player is shown, the number a price is compared with and the number a spend is checked against can
     * never disagree. {@code totalExperience} is deliberately not consulted anywhere: commands, the
     * enchanting table and third-party mods can leave it inconsistent with the bar.</p>
     */
    public static long integerBalance(int level, float progress) {
        int safeLevel = Math.max(0, Math.min(MAX_LEVEL, level));
        return xpAtLevelStart(safeLevel) + pointsWithinLevel(safeLevel, progress);
    }

    /** A vanilla experience state: the level the bar is on plus the fraction of that level filled. */
    public record Split(int level, double progress) {
        public Split {
            if (level < 0 || level > MAX_LEVEL) throw new IllegalArgumentException("Invalid split level " + level);
            if (!Double.isFinite(progress) || progress < 0 || progress >= 1.0D)
                throw new IllegalArgumentException("Invalid split progress " + progress);
        }
    }

    /**
     * Which vanilla state holds exactly {@code balance} XP.
     *
     * <p>The split is the canonical one: the highest level whose start does not exceed the balance, with
     * the remainder expressed as the fraction of that level's own span. It is the inverse of
     * {@link #balance(int, double)} up to floating point, which is what makes a spend verifiable: the
     * caller writes the split and then checks that the recomputed balance matches.</p>
     */
    public static Split split(double balance) {
        if (!Double.isFinite(balance) || balance < 0) return new Split(0, 0.0D);
        int low = 0;
        int high = MAX_LEVEL;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (xpAtLevelStart(middle) <= balance) low = middle;
            else high = middle - 1;
        }
        int level = low;
        // A fractional remainder that reaches the next level's start belongs to the next level.
        while (level < MAX_LEVEL && (double) xpAtLevelStart(level + 1) <= balance) level++;
        double within = balance - (double) xpAtLevelStart(level);
        int span = xpForNextLevel(level);
        double progress = span <= 0 ? 0.0D : within / span;
        if (progress < 0) progress = 0;
        if (progress >= 1.0D) {
            progress = level >= MAX_LEVEL ? Math.nextDown(1.0D) : 0.0D;
            if (level < MAX_LEVEL) level++;
        }
        return new Split(level, progress);
    }
}
