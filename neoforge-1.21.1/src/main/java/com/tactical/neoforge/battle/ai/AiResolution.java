package com.tactical.neoforge.battle.ai;

/** Never advance a turn after ROSTER_CHANGED: removeDeadEnemies owns that transition. */
public enum AiResolution {
    END_TURN,
    ROSTER_CHANGED,
    /**
     * The actor really moved as one controlled action (a native teleport), and the same own turn continues.
     *
     * <p>The owner re-reads its context and asks the adapter exactly once for a follow-up. Only a melee or an
     * end of turn may come back: the follow-up is priced and validated like any other action, so a relocation
     * can never be turned into a free second turn.</p>
     */
    RELOCATED
}
