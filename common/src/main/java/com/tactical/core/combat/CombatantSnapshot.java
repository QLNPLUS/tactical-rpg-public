package com.tactical.core.combat;

import com.tactical.core.grid.GridPos;

/** Read-only view of one battle participant. */
public record CombatantSnapshot(
        String id,
        String name,
        Team team,
        GridPos cell,
        int hp,
        int maxHp,
        int initiative,
        int ap,
        int maxAp
) {
}
