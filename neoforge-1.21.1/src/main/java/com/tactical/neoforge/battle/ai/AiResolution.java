package com.tactical.neoforge.battle.ai;

/** Never advance a turn after ROSTER_CHANGED: removeDeadEnemies owns that transition. */
public enum AiResolution {
    END_TURN,
    ROSTER_CHANGED
}
