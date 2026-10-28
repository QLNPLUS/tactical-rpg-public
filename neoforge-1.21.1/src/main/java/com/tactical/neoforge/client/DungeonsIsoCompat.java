package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Verified against the installed Dungeons Perspective 3.6.3+1.21.1 jar.
 * Config.GSON.instance() is an INSTANCE method on its holder. Dungeons' dynamic
 * branch follows Minecraft.player, not cameraEntity, and uses a per-frame lerp.
 * Suppress it only while Camera.setup runs; keep Dungeons' rotation, zoom and
 * culling. Never invoke ConfigHolder.save() or change the on-disk config.
 */
public final class DungeonsIsoCompat {
    private static boolean resolved;
    private static boolean warned;
    private static Field holderField;
    private static Method instanceMethod;
    private static Field dynamicField;
    private static Field enabledField;
    private static Field contextField;
    private static Field panXField;
    private static Field panZField;
    private static Object overriddenConfig;
    private static boolean previousDynamic;
    private static boolean previousContext;
    private static double previousPanX;
    private static double previousPanZ;

    private DungeonsIsoCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("dungeons_iso");
    }

    private static boolean resolve() {
        if (resolved) {
            return holderField != null;
        }
        resolved = true;
        if (!isLoaded()) {
            return false;
        }
        try {
            Class<?> configType = Class.forName("com.cleannrooster.dungeons_iso.config.Config");
            Class<?> modType = Class.forName("com.cleannrooster.dungeons_iso.mod.Mod");
            Field holder = configType.getField("GSON");
            Method instance = holder.getType().getMethod("instance");
            dynamicField = configType.getField("dynamicCamera");
            enabledField = modType.getField("enabled");
            contextField = modType.getField("contextToggle");
            panXField = modType.getField("x");
            panZField = modType.getField("z");
            instanceMethod = instance;
            holderField = holder;
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            warn(exception);
            return false;
        }
    }

    /** Render-thread scope: configuration UI and ticks never observe the temporary override. */
    public static void beginCameraSetup() {
        restore();
        if (!resolve()) {
            return;
        }
        try {
            if (!enabledField.getBoolean(null)) {
                return;
            }
            Object config = instanceMethod.invoke(holderField.get(null));
            previousDynamic = dynamicField.getBoolean(config);
            previousContext = contextField.getBoolean(null);
            previousPanX = panXField.getDouble(null);
            previousPanZ = panZField.getDouble(null);
            // Set scope first so a partially applied override can still be restored.
            overriddenConfig = config;
            dynamicField.setBoolean(config, false);
            contextField.setBoolean(null, false);
        } catch (ReflectiveOperationException | LinkageError exception) {
            restore();
            warn(exception);
        }
    }

    public static void restore() {
        if (overriddenConfig == null) {
            return;
        }
        Object config = overriddenConfig;
        overriddenConfig = null;
        try {
            dynamicField.setBoolean(config, previousDynamic);
            contextField.setBoolean(null, previousContext);
            // Dungeons resets its pan offsets in the non-dynamic branch. Preserve them too.
            panXField.setDouble(null, previousPanX);
            panZField.setDouble(null, previousPanZ);
        } catch (ReflectiveOperationException | LinkageError exception) {
            warn(exception);
        }
    }

    private static void warn(Throwable exception) {
        if (!warned) {
            warned = true;
            Tactical.LOGGER.warn("[Tactical] Dungeons camera compatibility unavailable; using vanilla anchor", exception);
        }
    }
}
