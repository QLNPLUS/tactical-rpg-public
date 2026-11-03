package com.tactical.core.progression;

import java.util.Arrays;
import java.util.Optional;

/**
 * One character's growth record: level, the historical points ledger and the six invested values.
 *
 * <p>Immutable and self-validating. The ledger invariant is {@code granted == totalInvested + unspent}:
 * {@code granted} is what the server has handed out over the character's whole life, so a configuration
 * change that alters the points granted per level can neither add nor take back points retroactively.
 * Every array crossing this boundary is copied in and copied out, so no caller can reach the stored
 * values.</p>
 *
 * <p>Both mutations - a level-up and an allocation - are single atomic value transitions that bump
 * {@code revision}. The store compares that revision with the one a client request expected, which is how
 * a duplicate or a stale intent is refused without spending XP or handing out points twice.</p>
 */
public record CharacterProfile(int level, int granted, int unspent, int[] invested, long revision) {

    /** Level cap of the storage format itself; the configured cap is usually far lower. */
    public static final int MAX_STORED_LEVEL = 1_000_000;
    /** Point ceiling of the storage format, so a corrupt file cannot produce an unbounded ledger. */
    public static final int MAX_STORED_POINTS = 100_000_000;

    public CharacterProfile {
        if (level < 1 || level > MAX_STORED_LEVEL) throw new IllegalArgumentException("Invalid level " + level);
        if (granted < 0 || granted > MAX_STORED_POINTS) throw new IllegalArgumentException("Invalid granted " + granted);
        if (unspent < 0 || unspent > MAX_STORED_POINTS) throw new IllegalArgumentException("Invalid unspent " + unspent);
        if (invested == null || invested.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("A profile needs exactly " + CharacterStat.COUNT + " invested values");
        int[] copy = invested.clone();
        long total = 0;
        for (int value : copy) {
            if (value < 0) throw new IllegalArgumentException("Negative invested point");
            total += value;
        }
        if (total > MAX_STORED_POINTS) throw new IllegalArgumentException("Invested points exceed the storage bound");
        if ((long) granted != total + unspent)
            throw new IllegalArgumentException("Point ledger is not conserved: granted=" + granted
                    + " invested=" + total + " unspent=" + unspent);
        if (revision < 0) throw new IllegalArgumentException("Invalid revision " + revision);
        invested = copy;
    }

    /** A brand-new character: level 1, nothing granted, nothing invested. */
    public static CharacterProfile fresh() { return new CharacterProfile(1, 0, 0, new int[CharacterStat.COUNT], 0L); }

    @Override public int[] invested() { return invested.clone(); }

    public int invested(CharacterStat stat) { return invested[stat.index()]; }

    public int totalInvested() {
        int total = 0;
        for (int value : invested) total += value;
        return total;
    }

    /** Displayed value of one stat under these rules; the baseline is display only. */
    public int display(CharacterStat stat, ProgressionRules rules) { return rules.displayValue(invested(stat)); }

    public CharacterProfile withRevision(long revision) {
        return new CharacterProfile(level, granted, unspent, invested, revision);
    }

    /** Why a level-up would be refused, or empty when it is allowed. */
    public Optional<ProgressionReason> levelUpFailure(ProgressionRules rules) {
        if (level >= rules.maxLevel()) return Optional.of(ProgressionReason.LEVEL_CAP);
        if (pointsPerLevelGrant(rules) <= 0) return Optional.of(ProgressionReason.SERVER_ERROR);
        return Optional.empty();
    }

    /**
     * One level-up: the configured points are granted, added to the unspent balance and recorded in the
     * historical ledger. Nothing else changes, so derived values stay exactly as they were.
     */
    public CharacterProfile levelUp(ProgressionRules rules) {
        levelUpFailure(rules).ifPresent(reason -> { throw new ProgressionRefusedException(reason); });
        int points = pointsPerLevelGrant(rules);
        long nextGranted = (long) granted + points;
        long nextUnspent = (long) unspent + points;
        if (nextGranted > MAX_STORED_POINTS || nextUnspent > MAX_STORED_POINTS)
            throw new ProgressionRefusedException(ProgressionReason.SERVER_ERROR, "point ledger would overflow");
        return new CharacterProfile(level + 1, (int) nextGranted, (int) nextUnspent, invested, revision + 1);
    }

    private static int pointsPerLevelGrant(ProgressionRules rules) { return rules.pointsPerLevel(); }

    /**
     * Why an allocation would be refused, or empty when it is allowed.
     *
     * <p>The order of these answers is part of the contract: a malformed array is reported as such, an
     * empty draft is reported as nothing to confirm, then the unspent balance is checked, and only then the
     * per-stat cap. Increments are non-negative by definition, so an allocation can never take back an
     * already committed point.</p>
     */
    public Optional<ProgressionReason> allocationFailure(int[] increments, ProgressionRules rules) {
        if (increments == null || increments.length != CharacterStat.COUNT)
            return Optional.of(ProgressionReason.INVALID_INCREMENT);
        long total = 0;
        for (int increment : increments) {
            if (increment < 0) return Optional.of(ProgressionReason.INVALID_INCREMENT);
            total += increment;
        }
        if (total == 0) return Optional.of(ProgressionReason.NOTHING_TO_ALLOCATE);
        if (total > unspent) return Optional.of(ProgressionReason.INSUFFICIENT_POINTS);
        int maximum = rules.maximumInvested();
        for (int index = 0; index < CharacterStat.COUNT; index++) {
            // Only a stat that is actually being increased is measured against the cap. A stat whose points
            // already exceed a lowered cap keeps them and stays visible; it simply cannot grow further, and it
            // never blocks a legal increment of another stat.
            if (increments[index] > 0 && (long) invested[index] + increments[index] > maximum)
                return Optional.of(ProgressionReason.STAT_CAP);
        }
        return Optional.empty();
    }

    /**
     * One committed allocation: the increments are added and the same number of points leaves the unspent
     * balance, so the ledger stays conserved and the revision advances exactly once.
     */
    public CharacterProfile allocate(int[] increments, ProgressionRules rules) {
        allocationFailure(increments, rules).ifPresent(reason -> { throw new ProgressionRefusedException(reason); });
        int[] next = invested.clone();
        long total = 0;
        for (int index = 0; index < next.length; index++) {
            next[index] += increments[index];
            total += increments[index];
        }
        return new CharacterProfile(level, granted, (int) (unspent - total), next, revision + 1);
    }

    /** Types of the six displayed values, for diagnostics and for the delivery document. */
    public int[] displays(ProgressionRules rules) {
        int[] values = new int[CharacterStat.COUNT];
        for (CharacterStat stat : CharacterStat.values()) values[stat.index()] = display(stat, rules);
        return values;
    }

    /** Structural equality including the invested array. */
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        return other instanceof CharacterProfile profile && level == profile.level && granted == profile.granted
                && unspent == profile.unspent && revision == profile.revision
                && Arrays.equals(invested, profile.invested);
    }

    @Override public int hashCode() {
        return ((level * 31 + granted) * 31 + unspent) * 31 + Arrays.hashCode(invested) ^ Long.hashCode(revision);
    }

    @Override public String toString() {
        return "CharacterProfile[level=" + level + ", granted=" + granted + ", unspent=" + unspent
                + ", invested=" + Arrays.toString(invested) + ", revision=" + revision + "]";
    }
}
