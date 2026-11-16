package com.tactical.neoforge.compat;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.OptionalInt;

/**
 * The one optional read of a creature's Advanced-Leveling display level.
 *
 * <p>Advanced-Leveling is a plain optional mod here: nothing in this mod compiles against it, and nothing
 * on the normal classpath names one of its classes. The approved public API is resolved by name, once,
 * through the same isolated pattern the client camera bridge uses, so a server without Advanced-Leveling
 * simply has no creature levels instead of a class that cannot be loaded. An absent mod is not a warning:
 * only a mod that is present and whose API cannot be used is reported, and only once.</p>
 *
 * <p>The call is read-only by contract of the API itself: it answers the level the mod already stores and
 * shows, and it neither writes the entity, refreshes attributes, drops loot nor starts anything. A player
 * is never answered by this mod's own system - a player's number comes from Tactical's character profile -
 * so this bridge is only asked about creatures.</p>
 */
public final class AdvancedLevelingBridge {

    /** The mod id this bridge speaks to. */
    public static final String MOD_ID = "autoleveling";

    /** The approved public API: {@code public static OptionalInt displayLevel(LivingEntity)}. */
    static final String API_CLASS = "daripher.autoleveling.api.LevelingApi";
    static final String API_METHOD = "displayLevel";

    private static boolean resolved;
    private static boolean warned;
    private static Method displayLevel;

    private AdvancedLevelingBridge() {
    }

    /** Whether the optional mod is installed in this instance; a security-manager-free cheap read. */
    public static boolean present() {
        try {
            ModList mods = ModList.get();
            return mods != null && mods.isLoaded(MOD_ID);
        } catch (RuntimeException | LinkageError unavailable) {
            // Outside a booted game - a plain unit test - there is no mod list at all.
            return false;
        }
    }

    /**
     * The creature's display level, or empty when there is nothing this bridge may show.
     *
     * <p>Empty means exactly one thing: no suffix. A missing mod, an unusable API, a hidden policy, a
     * missing or malformed stored level and a refused call all collapse into it, and none of them is
     * turned into a fabricated level, a raw stored value or a guess from vanilla experience.</p>
     */
    public static OptionalInt displayLevel(LivingEntity entity) {
        if (entity == null) return OptionalInt.empty();
        // A player is never answered here, whatever the other mod stores for it: a player's number is this
        // mod's own character level, and the rule is enforced at the source split as well as at this door.
        if (entity instanceof net.minecraft.world.entity.player.Player) return OptionalInt.empty();
        if (!resolve()) return OptionalInt.empty();
        try {
            Object answer = displayLevel.invoke(null, entity);
            if (!(answer instanceof OptionalInt level) || level.isEmpty()) return OptionalInt.empty();
            // The API answers a displayed level, which is one-based: anything below one is not a level.
            int value = level.getAsInt();
            return value > 0 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (InvocationTargetException refused) {
            // The API answered by throwing: that is not a level, and it must never reach the caller as one.
            warn(refused.getCause() == null ? refused : refused.getCause());
            return OptionalInt.empty();
        } catch (IllegalAccessException | RuntimeException | LinkageError broken) {
            warn(broken);
            return OptionalInt.empty();
        }
    }

    /** The one warning this bridge may emit: an installed mod whose approved API could not be used. */
    private static void warn(Throwable exception) {
        if (warned) return;
        warned = true;
        Tactical.LOGGER.warn("[Tactical] {} is installed but its level API is unusable; creature levels stay "
                + "hidden for this session", MOD_ID, exception);
    }

    /**
     * Resolves and caches the approved API, once.
     *
     * <p>Resolution is attempted only when the mod is really installed, so a normal instance pays one
     * mod-list lookup per snapshot and never a class lookup, and no {@link NoClassDefFoundError} can be
     * produced by asking for a class that was never shipped.</p>
     */
    private static boolean resolve() {
        if (resolved) return displayLevel != null;
        resolved = true;
        if (!present()) return false;
        try {
            Class<?> api = Class.forName(API_CLASS);
            Method method = api.getMethod(API_METHOD, LivingEntity.class);
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != OptionalInt.class) {
                // A different signature is not this API: hide the level once, never call it blindly.
                throw new NoSuchMethodException(API_CLASS + "." + API_METHOD + " has an unexpected shape");
            }
            method.setAccessible(true);
            displayLevel = method;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unusable) {
            warn(unusable);
            return false;
        }
    }

    /** Drops the cached resolution and the warning latch; used by fixtures only. */
    public static void reset() {
        resolved = false;
        warned = false;
        displayLevel = null;
    }
}
