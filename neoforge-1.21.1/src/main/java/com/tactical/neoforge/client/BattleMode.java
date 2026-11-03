package com.tactical.neoforge.client;

/** What a left click currently means. */
public enum BattleMode {
    /** No command chosen yet: world clicks do nothing. */
    NONE,
    MOVE,
    ATTACK,
    SKILL,
    /** Place the main-hand block. Entered only from the block command, never from the item command. */
    PLACE,
    /** Break the block under the cursor with whatever the main hand holds. */
    BREAK,
    USE_ITEM
}
