package com.tactical.neoforge.client;

/** Standalone regression checks; no Minecraft, loader, Gradle or JUnit required. */
public final class BattleInputPolicyPureTest {
    public static void main(String[] args) {
        int checks = 0;
        for (boolean battle : new boolean[]{false, true}) {
            for (BattleInputPolicy.Surface surface : BattleInputPolicy.Surface.values()) {
                boolean expected = battle && (surface == BattleInputPolicy.Surface.WORLD || surface == BattleInputPolicy.Surface.TACTICAL);
                check(BattleInputPolicy.owns(battle, surface) == expected, "surface ownership " + surface); checks++;
                check(BattleInputPolicy.wheel(battle, surface, false) == (expected ? BattleInputPolicy.Wheel.HOTBAR : BattleInputPolicy.Wheel.IGNORE), "plain wheel " + surface); checks++;
                check(BattleInputPolicy.wheel(battle, surface, true) == (expected ? BattleInputPolicy.Wheel.CAMERA : BattleInputPolicy.Wheel.IGNORE), "middle wheel " + surface); checks++;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            check(BattleInputPolicy.scrollSlot(slot, 1) == (slot + 8) % 9, "wheel up");
            check(BattleInputPolicy.scrollSlot(slot, -1) == (slot + 1) % 9, "wheel down");
            check(BattleInputPolicy.scrollSlot(slot, 0) == slot, "zero wheel");
            check(BattleInputPolicy.scrollSlot(slot, Double.NaN) == slot, "invalid wheel");
            check(BattleInputPolicy.scrollSlot(slot, 0.01) == (slot + 8) % 9, "high-resolution wheel");
            checks += 5;
        }
        BattleInputPolicy.MiddleGesture gesture = new BattleInputPolicy.MiddleGesture();
        gesture.press(true);
        check(!gesture.camera(true, true), "UI press stays UI");
        check(!gesture.camera(true, false), "UI-origin drag outside never becomes camera");
        gesture.release();
        gesture.press(false);
        check(gesture.camera(true, false), "world press camera");
        check(!gesture.camera(true, true), "world drag pauses over UI");
        check(gesture.camera(true, false), "world drag resumes outside UI");
        gesture.release();
        check(!gesture.camera(false, false), "release ends camera gesture");
        checks += 6;
        for (BattleInputPolicy.Surface surface : BattleInputPolicy.Surface.values()) {
            check(BattleInputPolicy.wheel(true, surface, false, true, false) == BattleInputPolicy.Wheel.IGNORE, "UI plain wheel native");
            check(BattleInputPolicy.wheel(true, surface, true, true, true) == BattleInputPolicy.Wheel.IGNORE, "UI middle wheel native");
            check(BattleInputPolicy.wheel(true, surface, true, false, false) == BattleInputPolicy.Wheel.IGNORE, "UI-origin wheel outside blocked");
            checks += 3;
        }
        System.out.println("BattleInputPolicyPureTest: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
