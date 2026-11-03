package com.tactical.core.combat;

/**
 * Battle lifecycle. Mirrors the design document's state machine.
 *
 * <p>{@link #COMPANION_TURN} is a controlled-ally turn: a recruited companion owns the queue slot and acts
 * only on its controller's explicit order. It is deliberately its own phase and not {@link #ENEMY_TURN},
 * because the enemy phase is what runs the automatic mob AI - a companion there would play itself - and not
 * {@link #PLAYER_TURN} either, because that phase is what authorizes the owner's own body, inventory and
 * growth. It is appended last so every previously published ordinal is unchanged.</p>
 */
public enum BattlePhase {
    INACTIVE,
    DEPLOYING,
    PLAYER_TURN,
    ENEMY_TURN,
    ENDED,
    COMPANION_TURN;

    public boolean isActive() {
        return this == DEPLOYING || this == PLAYER_TURN || this == ENEMY_TURN || this == COMPANION_TURN;
    }

    /** True for a phase that waits for input from a connected player rather than from the mob AI. */
    public boolean awaitsPlayerOrder() {
        return this == PLAYER_TURN || this == COMPANION_TURN;
    }
}
