package com.tactical.neoforge.client;

/** What a left click currently means. */
public enum BattleMode {
    /** No command chosen yet: world clicks do nothing. */
    NONE,
    MOVE,
    ATTACK,
    SKILL,
    PLACE,
    USE_ITEM
}
