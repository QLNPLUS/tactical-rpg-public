package com.tactical.neoforge.registry.companion;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.api.companion.CompanionAdapter;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Factory table of companion kinds.
 *
 * <p>Deliberately a static table with no entity, battle, turn or cost state: one mob's control record lives
 * in the battle that recruited it, so a leftover registry entry can never act on behalf of a finished
 * encounter. Registration is open to other mods through {@link #doRegister(CompanionAdapter)}; nothing is
 * promised about mobs nobody registered.</p>
 *
 * <p>First matching adapter wins and registration order is preserved, so an add-on may claim a more specific
 * mob before the built-in default claims its superclass - register the specific one first, exactly like the
 * tactical AI adapter table.</p>
 */
public final class CompanionRegistry {

    private static final List<CompanionAdapter> ADAPTERS = new ArrayList<>();
    private static boolean defaultsRegistered;

    private CompanionRegistry() {
    }

    /**
     * Register one companion kind. Idempotent for the same instance; a different adapter under an id that is
     * already taken is refused instead of silently replacing somebody's companion support.
     */
    public static synchronized void doRegister(CompanionAdapter adapter) {
        Objects.requireNonNull(adapter, "companion adapter");
        Objects.requireNonNull(adapter.id(), "companion adapter id");
        for (CompanionAdapter existing : ADAPTERS) {
            if (existing == adapter) return;
            if (existing.id().equals(adapter.id())) {
                throw new IllegalStateException("Duplicate companion adapter: " + adapter.id());
            }
        }
        ADAPTERS.add(adapter);
        Tactical.LOGGER.debug("[Tactical] companion adapter registered: {}", adapter.id());
    }

    /** The built-in adapters, installed exactly once by the mod constructor. */
    public static synchronized void registerDefaults() {
        if (defaultsRegistered) return;
        defaultsRegistered = true;
        doRegister(new com.tactical.neoforge.companion.WolfCompanionAdapter());
    }

    /** The adapter claiming this mob, or {@code null} when nobody claims it. */
    public static synchronized CompanionAdapter find(Mob mob) {
        if (mob == null) return null;
        for (CompanionAdapter adapter : ADAPTERS) {
            if (adapter.supports(mob)) return adapter;
        }
        return null;
    }

    /** Immutable snapshot of the registered adapters, in claim order. */
    public static synchronized List<CompanionAdapter> adapters() {
        return List.copyOf(ADAPTERS);
    }

    /** Test/administrative reset of the table, so a fixture never leaks an adapter into the next fixture. */
    public static synchronized void clearForTests() {
        ADAPTERS.clear();
        defaultsRegistered = false;
    }
}
