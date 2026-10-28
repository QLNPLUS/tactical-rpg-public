package com.tactical.neoforge.client;

/** Standalone mode-policy checks; no Minecraft, loader, Gradle or JUnit required. */
public final class CursorPickPolicyPureTest {
    public static void main(String[] args) {
        check(CursorPickPolicy.usesCollider(BattleMode.ATTACK), "attack ignores non-colliding vegetation");
        check(CursorPickPolicy.usesCollider(BattleMode.MOVE), "move uses collision ground");
        check(CursorPickPolicy.usesCollider(BattleMode.SKILL), "skill uses collision ground");
        check(!CursorPickPolicy.usesCollider(BattleMode.PLACE), "place preserves outline surfaces");
        check(!CursorPickPolicy.usesCollider(BattleMode.USE_ITEM), "item use preserves outline");
        check(!CursorPickPolicy.usesCollider(BattleMode.NONE), "idle hover preserves outline");
        System.out.println("CursorPickPolicyPureTest: 6 checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
