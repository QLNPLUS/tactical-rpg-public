package com.tactical.neoforge.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CursorPickPolicyTest {
    @Test void targetingIgnoresEmptyCollisionShapes() {
        for (BattleMode mode : new BattleMode[]{BattleMode.ATTACK, BattleMode.SKILL, BattleMode.MOVE})
            assertTrue(CursorPickPolicy.usesCollider(mode), mode.name());
    }

    @Test void placementAndOrdinaryItemUseRetainOutlineSelection() {
        for (BattleMode mode : new BattleMode[]{BattleMode.PLACE, BattleMode.USE_ITEM, BattleMode.NONE})
            assertFalse(CursorPickPolicy.usesCollider(mode), mode.name());
    }
}
