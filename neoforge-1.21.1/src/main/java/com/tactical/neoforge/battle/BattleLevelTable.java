package com.tactical.neoforge.battle;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * The bounded {@code (entityId, displayLevel)} table of one battle snapshot, built once per snapshot.
 *
 * <p>It is the readable form of the three-level contract the wire and the roster share:</p>
 * <ul>
 *   <li>a <strong>player</strong> is always entered, because Tactical's own character level always applies to
 *       a player. A profile that is missing or unusable is the explicit {@link #UNKNOWN} value, which the HUD
 *       draws as an unknown level - never a fabricated level 1;</li>
 *   <li>a <strong>creature</strong> is entered only when a real display level was read from the optional
 *       Advanced-Leveling API. No entry is the answer for "this encounter has nothing to show": the mod is
 *       absent, the entry is hidden, or the stored level is missing or unusable. It is not unknown;</li>
 *   <li>an entity that is not a participant is never entered.</li>
 * </ul>
 *
 * <p>Nothing here reads a level, spends anything or changes an entity: the caller supplies the numbers it
 * already read, and this only packs them in the order they were given. Pure, so the roster's rules can be
 * pinned without a booted game.</p>
 */
public final class BattleLevelTable {

    /** A known player whose own profile could not be read as a level; drawn as an unknown level. */
    public static final int UNKNOWN = 0;

    /**
     * "This entity has no level to show" - the client-side absence marker.
     *
     * <p>It never travels: the wire simply omits the entity, and the receiving side turns that omission into
     * this value so a row, a title and the range legend can tell "no suffix" from "unknown".</p>
     */
    public static final int NONE = -1;

    private int[] pairs = new int[16];
    private int size;

    /** One player, always entered: a level that is not a real one is the explicit unknown. */
    public BattleLevelTable player(int entityId, int level) {
        return put(entityId, level > 0 ? level : UNKNOWN);
    }

    /** One creature, entered only when a real display level is known. */
    public BattleLevelTable creature(int entityId, int level) {
        return level > 0 ? put(entityId, level) : this;
    }

    /** One creature, entered only when the optional bridge really answered a level. */
    public BattleLevelTable creature(int entityId, OptionalInt level) {
        return level != null && level.isPresent() ? creature(entityId, level.getAsInt()) : this;
    }

    /** How many entities this table names. */
    public int count() {
        return size / 2;
    }

    /** The pairs, in the order they were added, as the wire and the record both take them. */
    public int[] build() {
        return Arrays.copyOf(pairs, size);
    }

    private BattleLevelTable put(int entityId, int level) {
        // An entity id below zero is not an entity, so it is ignored rather than packed. A repeated id is not
        // a second row: one identity can only carry one number, so the later answer replaces the earlier one
        // in place. The wire refuses a duplicate outright, and this builder never produces one.
        if (entityId < 0) return this;
        for (int index = 0; index < size; index += 2) {
            if (pairs[index] == entityId) {
                pairs[index + 1] = level;
                return this;
            }
        }
        if (size + 2 > pairs.length) pairs = Arrays.copyOf(pairs, pairs.length * 2);
        pairs[size++] = entityId;
        pairs[size++] = level;
        return this;
    }
}
