package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.Tutorial;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;

/** Calls the installed 3.6.3 mouse callbacks, never reimplements its camera transforms. */
public final class DungeonsInputBridge {
    private static Method movement;
    private static Method wheel;
    private static boolean resolved;
    private static boolean warned;
    private static boolean dragging;
    private static long moveCallbacks;
    private static long buttonCallbacks;
    private static long movementInvocations;
    private static double lastDx, lastDy;
    private static boolean sampledNativeMiddle;
    private static boolean callbackMiddle;
    private static final BattleInputPolicy.MiddleGesture GESTURE = new BattleInputPolicy.MiddleGesture();

    /** Returns whether this gesture belongs to the world, including its release. */
    public static boolean middleButton(boolean pressed) {
        buttonCallbacks++;
        callbackMiddle=pressed;
        boolean owned = GESTURE.worldOrigin();
        if (pressed) {
            GESTURE.press(BattleUiRegions.overUi());
            owned = GESTURE.worldOrigin();
        } else {
            GESTURE.release();
        }
        Minecraft.getInstance().options.keyPickItem.setDown(pressed && owned && !BattleUiRegions.overUi());
        if (!pressed) move(0, 0);
        return owned;
    }

    public static boolean physicalMiddleHeld() {
        Minecraft mc = Minecraft.getInstance();
        return callbackMiddle || GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
    }
    public static void cancelGesture() {
        if(!callbackMiddle&&!dragging&&!GESTURE.worldOrigin()) return;
        callbackMiddle=false;dragging=false;GESTURE.release();
        Minecraft.getInstance().options.keyPickItem.setDown(false);
    }
    private DungeonsInputBridge() {}

    public static boolean enabled() {
        if (!DungeonsIsoCompat.isLoaded()) return false;
        try {
            return Class.forName("com.cleannrooster.dungeons_iso.mod.Mod").getField("enabled").getBoolean(null);
        } catch (ReflectiveOperationException | LinkageError e) { warn(e); return false; }
    }

    public static boolean middleHeld() {
        Minecraft mc = Minecraft.getInstance();
        return enabled() && mc.isWindowActive() && BattleScreenInput.owns(mc.screen)
                && GESTURE.camera(physicalMiddleHeld(), BattleUiRegions.overUi());
    }

    private static boolean resolve() {
        if (resolved) return movement != null && wheel != null;
        resolved = true;
        // Mixin prefixes merged handler names. Match exact verified suffix + signature.
        for (Method method : Minecraft.getInstance().mouseHandler.getClass().getDeclaredMethods()) {
            if (method.getName().endsWith("updateMouseAXIV")
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{Tutorial.class, double.class, double.class})) {
                movement = method; method.setAccessible(true);
            }
            if (method.getName().endsWith("scrollInHotbarXIV")
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{Inventory.class, double.class})) {
                wheel = method; method.setAccessible(true);
            }
        }
        if (movement == null || wheel == null) warn(new IllegalStateException("Dungeons 3.6.3 mouse callbacks not found"));
        return movement != null && wheel != null;
    }

    public static void move(double dx, double dy) {
        moveCallbacks++;
        lastDx = dx; lastDy = dy;
        Minecraft mc = Minecraft.getInstance();
        boolean held = middleHeld();
        if (!sampledNativeMiddle && physicalMiddleHeld() && BattleScreenInput.owns(mc.screen)) {
            sampledNativeMiddle = true;
            Tactical.LOGGER.info("[Tactical] Dungeons native middle diagnostic {}", debugSnapshot());
        }
        if ((!held && !dragging) || mc.player == null || !enabled() || !resolve()) { dragging = false; return; }
        mc.options.keyPickItem.setDown(held);
        try {
            // These are the same sensitivity-scaled deltas passed to Tutorial.onMouse by vanilla.
            double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
            double scale = sensitivity * sensitivity * sensitivity * 8.0;
            movement.invoke(mc.mouseHandler, mc.getTutorial(), held ? dx * scale : 0.0, held ? dy * scale : 0.0);
            movementInvocations++;
        } catch (ReflectiveOperationException | LinkageError e) { warn(e); }
        dragging = held;
    }

    public static void scroll(double delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!middleHeld() || mc.player == null || !resolve()) return;
        int selected = mc.player.getInventory().selected;
        try { wheel.invoke(mc.mouseHandler, mc.player.getInventory(), delta); }
        catch (ReflectiveOperationException | LinkageError e) { warn(e); }
        finally {
            // If Dungeons' scrollWheelZoom setting is off, its own callback falls back
            // to swapPaint. A middle-camera gesture must never select a different tool.
            mc.player.getInventory().selected = selected;
        }
    }

    /** Read-only telemetry for the native-input helper; does not resolve or mutate camera state. */
    public static java.util.Map<String, Object> debugSnapshot() {
        Minecraft mc = Minecraft.getInstance();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("moveCallbacks", moveCallbacks);
        out.put("buttonCallbacks", buttonCallbacks);
        out.put("movementInvocations", movementInvocations);
        out.put("lastDx", lastDx); out.put("lastDy", lastDy);
        out.put("battle", BattleClientState.active);
        out.put("screen", mc.screen == null ? "null" : mc.screen.getClass().getName());
        out.put("ownsScreen", BattleScreenInput.owns(mc.screen));
        out.put("windowActive", mc.isWindowActive());
        out.put("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());
        out.put("physicalMiddle", physicalMiddleHeld());
        out.put("worldOrigin", GESTURE.worldOrigin());
        out.put("overUi", BattleUiRegions.overUi());
        out.put("keyPickItem", mc.options.keyPickItem.isDown());
        out.put("cameraEntity", mc.getCameraEntity() == null ? "null" : mc.getCameraEntity().getClass().getName());
        out.put("resolved", resolved);
        out.put("movementMethod", movement == null ? "null" : movement.getName());
        out.put("wheelMethod", wheel == null ? "null" : wheel.getName());
        java.util.List<String> merged = new java.util.ArrayList<>();
        for (Method candidate : mc.mouseHandler.getClass().getDeclaredMethods()) {
            if (candidate.getName().contains("tactical$") || candidate.getName().contains("MouseAXIV")
                    || candidate.getName().contains("HotbarXIV")) merged.add(candidate.getName());
        }
        out.put("mergedMouseMethods", merged);
        try {
            Class<?> mod = Class.forName("com.cleannrooster.dungeons_iso.mod.Mod");
            for (String field : new String[]{"enabled", "yaw", "pitch", "zoom", "rotateToggle"})
                out.put("dungeons." + field, mod.getField(field).get(null));
            Class<?> config = Class.forName("com.cleannrooster.dungeons_iso.config.Config");
            Object holder = config.getField("GSON").get(null);
            Object instance = holder.getClass().getMethod("instance").invoke(holder);
            for (String field : new String[]{"XIV", "scrollWheelZoom", "dynamicCamera"})
                out.put("config." + field, config.getField(field).get(instance));
        } catch (ReflectiveOperationException | LinkageError e) { out.put("reflectionError", e.toString()); }
        return out;
    }

    private static void warn(Throwable e) {
        if (!warned) { warned = true; Tactical.LOGGER.warn("[Tactical] Dungeons mouse bridge unavailable", e); }
    }
}
