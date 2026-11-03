package com.tactical.neoforge.client.ui.hud;

import com.tactical.neoforge.client.ui.platform.ClientText;

/**
 * The journal's and the hint's wording keys, in one place and free of Minecraft types.
 *
 * <p>These are keys, not translations. The two shipped language files are the only place a wording
 * lives, and the painter resolves each key in the player's own language through
 * {@link ClientText} at the display point, once per frame. Keeping the constants here means the pure
 * layout and model code can name a wording without holding a译文 or a {@code Component}.</p>
 */
public final class JournalLabels {

    /** The key the hint asks the player to press; a key binding's letter is not translated. */
    public static final String HINT_KEY = "B";

    /** The chip on the acting participant's roster row. */
    public static final String ACTING = ClientText.Keys.HUD_ACTING;

    public static final String STATUS_LATEST = ClientText.Keys.HUD_STATUS_LATEST;
    public static final String STATUS_HISTORY = ClientText.Keys.HUD_STATUS_HISTORY;

    /** The footer's count template, taking {@code first–last} and the total. */
    public static final String COUNT = ClientText.Keys.HUD_COUNT;

    private JournalLabels() {
    }

    /** Unknown categories collapse to the generic action chip, exactly like the old journal. */
    public static String category(String value) {
        if (value == null) return "action";
        return switch (value) {
            case "battle", "turn", "move", "attack", "damage", "heal", "effect", "death",
                    "block", "reject", "enemy", "inventory", "action" -> value;
            default -> "action";
        };
    }

    /** The chip's wording key for a category: every category a row can carry has its own key. */
    public static String label(String category) {
        return switch (category == null ? "action" : category) {
            case "battle" -> ClientText.Keys.JOURNAL_BATTLE;
            case "turn" -> ClientText.Keys.JOURNAL_TURN;
            case "move" -> ClientText.Keys.JOURNAL_MOVE;
            case "attack" -> ClientText.Keys.JOURNAL_ATTACK;
            case "damage" -> ClientText.Keys.JOURNAL_DAMAGE;
            case "heal" -> ClientText.Keys.JOURNAL_HEAL;
            case "effect" -> ClientText.Keys.JOURNAL_EFFECT;
            case "death" -> ClientText.Keys.JOURNAL_DEATH;
            case "block" -> ClientText.Keys.JOURNAL_BLOCK;
            case "reject" -> ClientText.Keys.JOURNAL_REJECT;
            case "enemy" -> ClientText.Keys.JOURNAL_ENEMY;
            case "inventory" -> ClientText.Keys.JOURNAL_INVENTORY;
            default -> ClientText.Keys.JOURNAL_ACTION;
        };
    }

    /** The footer's count arguments: {@code a–b} while entries are drawn, {@code 0} when empty. */
    public static String countRange(long firstSequence, long lastSequence, boolean empty) {
        return empty ? "0" : firstSequence + "–" + lastSequence;
    }

    public static String status(boolean browsingHistory) {
        return browsingHistory ? STATUS_HISTORY : STATUS_LATEST;
    }

    /** The toggle of the journal title row reflects the state that is really on screen. */
    public static String toggle(boolean bodyVisible) {
        return bodyVisible ? ClientText.Keys.HUD_TOGGLE_COLLAPSE : ClientText.Keys.HUD_TOGGLE_EXPAND;
    }
}
