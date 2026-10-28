package com.tactical.neoforge.client;

/** Pure mode policy; shape selection must not change wall or entity occlusion. */
final class CursorPickPolicy {
    private CursorPickPolicy() { }

    static boolean usesCollider(BattleMode mode) {
        return mode == BattleMode.ATTACK || mode == BattleMode.MOVE || mode == BattleMode.SKILL;
    }
}
