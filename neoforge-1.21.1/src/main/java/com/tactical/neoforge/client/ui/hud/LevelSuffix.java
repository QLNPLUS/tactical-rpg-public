package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.battle.BattleLevelTable;

/**
 * The one compact level suffix the HUD appends to a name it already has.
 *
 * <p>The approved form is {@code name · Lv N}: the raw name is kept exactly as the server or the client
 * resolved it, the level is a separate number, and the two are joined only where something is drawn. The
 * suffix is deliberately tiny and sticks to the existing font, size and text colour - it is a value on an
 * existing line, not a new badge, and it introduces no colour, icon, frame or animation of its own.</p>
 *
 * <p>Three answers, and they are not interchangeable:</p>
 * <ul>
 *   <li>a real level is {@code · Lv 3};</li>
 *   <li>a player whose own profile produced no usable level is {@code · Lv —}, the explicit unknown - it says
 *       "this number could not be read" instead of drawing a fabricated one;</li>
 *   <li>{@link BattleLevelTable#NONE} is no suffix at all, with no placeholder and no gap: this encounter has
 *       nothing to show for that unit, which is what a creature without an applicable level really is.</li>
 * </ul>
 *
 * <p>The {@code Lv} itself is the client's own wording and arrives resolved from the shipped language files,
 * so this class stays pure and the layout rules can be tested without a booted game.</p>
 */
public final class LevelSuffix {

    /** The separator between a name and its level; it is punctuation, not wording. */
    public static final String SEPARATOR = " · ";

    /** What stands in for a level that could not be read; also punctuation, and never a number. */
    public static final String UNKNOWN_MARK = "—";

    private LevelSuffix() {
    }

    /**
     * The suffix for one level, or an empty string when this unit has nothing to show.
     *
     * @param label the level's own wording in the client's language, resolved at the display point
     * @param level a positive level, {@link BattleLevelTable#UNKNOWN} or {@link BattleLevelTable#NONE}
     */
    public static String of(String label, int level) {
        if (level == BattleLevelTable.NONE) return "";
        String wording = label == null ? "" : label.strip();
        String value = level > 0 ? Integer.toString(level) : UNKNOWN_MARK;
        return wording.isEmpty() ? SEPARATOR + value : SEPARATOR + wording + " " + value;
    }

    /** A name with its suffix, or the name alone when this unit has no level to show. */
    public static String join(String name, String label, int level) {
        String text = name == null ? "" : name;
        return text + of(label, level);
    }
}
