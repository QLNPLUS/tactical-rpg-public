package com.tactical.core.progression;

import java.util.Locale;

/**
 * The six character stats, in their frozen canonical order.
 *
 * <p>{@link #ordinal()} is the wire position and the storage position: every six-element array in this
 * feature (invested points, displayed values, derived values, draft increments) uses this order, so no
 * name ever travels on the wire or into a save file. {@link #BASE} is the value every character displays
 * before any point is invested; it is a display baseline, not a free bonus, so the derived modifiers are
 * computed from invested points only.</p>
 */
public enum CharacterStat {
    VIGOR,
    MIND,
    ENDURANCE,
    STRENGTH,
    DEXTERITY,
    INTELLIGENCE;

    /** Number of stats; the length of every stat array in the protocol and in storage. */
    public static final int COUNT = 6;

    /** Displayed value at level 1 with nothing invested. Never applied as an attribute bonus. */
    public static final int BASE = 10;

    public int index() { return ordinal(); }

    /** Translation key of the stat name; the server only ever ships this key, never a language. */
    public String translationKey() { return "tactical.stat." + name().toLowerCase(Locale.ROOT); }

    /** Stable identifier used in the stat's own modifier id. */
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static CharacterStat of(int index) {
        CharacterStat[] all = values();
        if (index < 0 || index >= all.length) throw new IllegalArgumentException("Unknown stat index " + index);
        return all[index];
    }

    /** Defensive copy of the canonical order. */
    public static CharacterStat[] canonical() { return values().clone(); }
}
