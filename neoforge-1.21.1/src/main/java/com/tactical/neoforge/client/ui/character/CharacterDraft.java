package com.tactical.neoforge.client.ui.character;

import com.tactical.core.progression.CharacterStat;

import java.util.Arrays;

/**
 * The uncommitted allocation: exactly six non-negative increments on top of the committed points.
 *
 * <p>A draft can only ever take back what this same draft added - it is built on top of the server's
 * invested array and never subtracts from it - so pressing {@code −} after a commit cannot spend a refund
 * that does not exist. Immutable, and every boundary is decided here so the screen, the model and the tests
 * agree on one arithmetic.</p>
 */
public record CharacterDraft(int[] increments) {

    public CharacterDraft {
        if (increments == null || increments.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("A draft needs exactly " + CharacterStat.COUNT + " increments");
        int[] copy = increments.clone();
        for (int value : copy) {
            if (value < 0) throw new IllegalArgumentException("A draft increment is never negative");
        }
        increments = copy;
    }

    public static CharacterDraft empty() { return new CharacterDraft(new int[CharacterStat.COUNT]); }

    @Override public int[] increments() { return increments.clone(); }

    public int increment(CharacterStat stat) { return increments[stat.index()]; }

    public int total() {
        int total = 0;
        for (int value : increments) total += value;
        return total;
    }

    public boolean isEmpty() { return total() == 0; }

    /**
     * Whether one more point may be drafted onto {@code stat}.
     *
     * <p>Two limits, both the server's: the unspent balance this draft has not used yet, and the displayed
     * cap for that stat. A stat that is already above a lowered cap keeps its points but cannot grow, which
     * is why the check is on the committed value plus the draft, not on the cap alone.</p>
     */
    public boolean canAdd(CharacterStat stat, int unspent, int invested, int statCap) {
        if (stat == null) return false;
        int remaining = unspent - total();
        if (remaining <= 0) return false;
        int maximumInvested = Math.max(0, statCap - CharacterStat.BASE);
        return (long) invested + increments[stat.index()] < maximumInvested;
    }

    /** Whether {@code −} may be pressed: only while this draft still holds a point of that stat. */
    public boolean canRemove(CharacterStat stat) {
        return stat != null && increments[stat.index()] > 0;
    }

    public CharacterDraft add(CharacterStat stat, int unspent, int invested, int statCap) {
        if (!canAdd(stat, unspent, invested, statCap)) return this;
        int[] next = increments.clone();
        next[stat.index()]++;
        return new CharacterDraft(next);
    }

    public CharacterDraft remove(CharacterStat stat) {
        if (!canRemove(stat)) return this;
        int[] next = increments.clone();
        next[stat.index()]--;
        return new CharacterDraft(next);
    }

    /** The displayed value of one stat with this draft applied; the server's display plus the draft. */
    public int display(CharacterStat stat, int committedDisplay) {
        return committedDisplay + increments[stat.index()];
    }

    public boolean sameAs(int[] other) { return other != null && Arrays.equals(increments, other); }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof CharacterDraft draft && Arrays.equals(increments, draft.increments);
    }

    @Override public int hashCode() { return Arrays.hashCode(increments); }

    @Override public String toString() { return "CharacterDraft" + Arrays.toString(increments); }
}
