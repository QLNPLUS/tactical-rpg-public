package com.tactical.neoforge.client;

/** Pure mode policy; shape selection must not change wall or entity occlusion. */
final class CursorPickPolicy {
    private CursorPickPolicy() { }

    /**
     * Collision shapes are used only where an empty shape must not be selected. Both block commands read
     * the outline surface, so a block is highlighted exactly as the vanilla cursor highlights it.
     */
    static boolean usesCollider(BattleMode mode) {
        return mode == BattleMode.ATTACK || mode == BattleMode.MOVE || mode == BattleMode.SKILL;
    }
}
