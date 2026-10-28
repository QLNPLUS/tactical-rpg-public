package com.tactical.neoforge.client;

/** Pure routing rules: phase/actor/mode are deliberately not inputs. */
public final class BattleInputPolicy {
    public enum Surface { WORLD, TACTICAL, CONTAINER, CHAT, OTHER }
    public enum Wheel { IGNORE, CAMERA, HOTBAR }
    private BattleInputPolicy() {}
    public static boolean owns(boolean battle, Surface surface) {
        return battle && (surface == Surface.WORLD || surface == Surface.TACTICAL);
    }
    public static Wheel wheel(boolean battle, Surface surface, boolean middle) {
        if (!owns(battle, surface)) return Wheel.IGNORE;
        return middle ? Wheel.CAMERA : Wheel.HOTBAR;
    }
    public static Wheel wheel(boolean battle, Surface surface, boolean middle, boolean overUi, boolean worldOrigin) {
        if (overUi) return Wheel.IGNORE;
        if (middle && !worldOrigin) return Wheel.IGNORE;
        return wheel(battle, surface, middle);
    }
    /** Latched origin: a middle press in UI never turns into camera input mid-drag. */
    public static final class MiddleGesture {
        private boolean worldOrigin;
        public void press(boolean overUi) { worldOrigin = !overUi; }
        public void release() { worldOrigin = false; }
        public boolean worldOrigin() { return worldOrigin; }
        public boolean camera(boolean physicallyHeld, boolean overUi) {
            return physicallyHeld && worldOrigin && !overUi;
        }
    }
    public static int scrollSlot(int selected, double delta) {
        if (!Double.isFinite(delta) || delta == 0) return selected;
        return Math.floorMod(selected - (delta > 0 ? 1 : -1), 9);
    }
}
