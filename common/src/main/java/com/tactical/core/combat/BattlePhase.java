package com.tactical.core.combat;

/** Battle lifecycle. Mirrors the design document's state machine. */
public enum BattlePhase {
    INACTIVE,
    DEPLOYING,
    PLAYER_TURN,
    ENEMY_TURN,
    ENDED;

    public boolean isActive() {
        return this == DEPLOYING || this == PLAYER_TURN || this == ENEMY_TURN;
    }
}
