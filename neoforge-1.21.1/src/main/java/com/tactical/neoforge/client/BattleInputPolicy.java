package com.tactical.neoforge.client;

/** Pure routing rules: phase/actor/mode are deliberately not inputs. */
public final class BattleInputPolicy {
    public enum Surface { WORLD, TACTICAL, CONTAINER, CHAT, OTHER }
    public enum Wheel { IGNORE, CAMERA, HOTBAR }
    /** Who a left press belongs to when an interface control is under the pointer. */
    public enum PressOwner { LOWER, HUD, PAGE }
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
    /**
     * The layer order of a left press: a visible interface control is the upper layer, so it keeps the
     * press even where the hotbar band of the vanilla HUD also covers that point - a size handle
     * dragged down over the quick bar must start its own drag, not select the slot under it. Only a
     * point that no interface control claims keeps the old order, where the hotbar is asked before
     * the rest of the interface and before the world.
     *
     * <p>{@link PressOwner#PAGE} means this listener must not consume the event: an open tactical page
     * already offers every click to the interface first, so consuming it here would either drop the
     * press or deliver it twice. {@link PressOwner#HUD} means nothing else will deliver it and the
     * listener has to.</p>
     */
    public static PressOwner pressOwner(boolean interfaceControl, Surface surface) {
        if (!interfaceControl) return PressOwner.LOWER;
        return surface == Surface.TACTICAL ? PressOwner.PAGE : PressOwner.HUD;
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
