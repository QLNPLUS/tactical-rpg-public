package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterProfile;
import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.CharacterStatePacket;
import com.tactical.neoforge.network.ServerPackets;
import com.tactical.neoforge.skill.SkillBindings;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The single registration entry point of character growth, plus its player lifecycle.
 *
 * <p>Everything this feature registers - the skill-power attribute, the lifecycle listeners and the skill
 * formula variables - goes through {@link #register(IEventBus)} exactly once. No class in this package
 * carries its own event-bus annotation, so there is no second registration path to drift out of sync with
 * this one, and nothing here loads a client class: the whole package is safe on both physical sides.</p>
 *
 * <p>The lifecycle is deliberately stateless. The profile lives in the player's persistent data, so a
 * logout, a restart, a death clone and a dimension change all keep it; the only thing each of those events
 * has to do is copy the record where vanilla creates a new player instance, re-apply this mod's own
 * attribute modifiers (they live on the entity, not in the record) and tell that player's client the
 * authoritative state.</p>
 */
public final class ProgressionHandler {

    private static final String VERSION_KEY = "character";
    private static boolean registered;

    private ProgressionHandler() { }

    /** Must be called from the mod constructor. A second call is a no-op, never a double registration. */
    public static void register(IEventBus modEventBus) {
        if (registered) return;
        registered = true;
        ProgressionEffects.register(modEventBus);
        // A reloaded configuration changes prices and coefficients; it must reach the live attributes of
        // everyone online, not wait for their next allocation.
        modEventBus.addListener(ProgressionHandler::onConfigChanged);
        NeoForge.EVENT_BUS.addListener(ProgressionHandler::onLoggedIn);
        NeoForge.EVENT_BUS.addListener(ProgressionHandler::onClone);
        NeoForge.EVENT_BUS.addListener(ProgressionHandler::onChangedDimension);
        registerSkillVariables();
    }

    /** True once {@link #register(IEventBus)} ran; fixtures assert there is exactly one registration. */
    public static boolean registered() { return registered; }

    /**
     * Registers the {@code character_*} formula variables.
     *
     * <p>{@code caster_level} keeps its original meaning (the vanilla experience level); these are
     * separately named so no existing formula changes meaning. Every value is read from the stored profile,
     * never from a cache, and an actor without a profile - any mob, or a player who has not been stored yet -
     * reports a neutral value instead of an exception.</p>
     */
    static void registerSkillVariables() {
        registerVariable("character_level", (actor, rules) -> actor == null ? 0.0D : level(actor, rules));
        registerVariable("character_unspent", (actor, rules) -> actor == null ? 0.0D : unspent(actor, rules));
        registerVariable("character_skill_power", (actor, rules) -> actor == null ? 1.0D : ProgressionEffects.skillPower(actor));
        for (CharacterStat stat : CharacterStat.values()) {
            registerVariable("character_" + stat.id(), (actor, rules) -> actor == null ? CharacterStat.BASE
                    : display(actor, rules, stat));
        }
    }

    private static void registerVariable(String name, Reader reader) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, name);
        SkillBindings.register(id, name, (battle, caster, target, level) -> {
            ProgressionRules rules = ProgressionSettings.rules();
            double value = reader.read(caster, rules);
            return Double.isFinite(value) ? value : 0.0D;
        });
    }

    @FunctionalInterface private interface Reader {
        double read(LivingEntity actor, ProgressionRules rules);
    }

    private static CharacterProfile profileOf(LivingEntity actor, ProgressionRules rules) {
        if (!(actor instanceof ServerPlayer player) || player.level().isClientSide) return null;
        ProgressionStore.Loaded loaded = ProgressionStore.peek(player, rules);
        return loaded.usable() ? loaded.profile() : null;
    }

    private static double level(LivingEntity actor, ProgressionRules rules) {
        CharacterProfile profile = profileOf(actor, rules);
        return profile == null ? 0.0D : profile.level();
    }

    private static double unspent(LivingEntity actor, ProgressionRules rules) {
        CharacterProfile profile = profileOf(actor, rules);
        return profile == null ? 0.0D : profile.unspent();
    }

    private static double display(LivingEntity actor, ProgressionRules rules, CharacterStat stat) {
        CharacterProfile profile = profileOf(actor, rules);
        return profile == null ? CharacterStat.BASE : profile.display(stat, rules);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    private static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        refresh(player, "login");
    }

    /**
     * Death and the End-return create a new player instance; the record has to follow it, and the modifiers
     * have to be re-applied because the new instance starts from the default attributes.
     */
    private static void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Player original = event.getOriginal();
        ProgressionStore.copy(original, player);
        ProgressionEffects.clear(player);
        refresh(player, event.isWasDeath() ? "respawn" : "clone");
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        refresh(player, "dimension");
    }

    /**
     * A loaded or reloaded configuration changed the rules; every online character is re-derived.
     *
     * <p>The event is fired off the game thread, so nothing here touches a player directly: the work is
     * handed to {@code server.execute}. Each refresh re-reads the record (which advances the revision once
     * because the rule fingerprint changed, so an old quote is refused) and re-applies this mod's own
     * modifiers with the new coefficients - the attributes of an already-invested character are correct
     * immediately, without needing one more allocation.</p>
     */
    private static void onConfigChanged(ModConfigEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> refreshOnline(server));
    }

    private static void refreshOnline(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : new java.util.ArrayList<>(server.getPlayerList().getPlayers())) {
            try {
                refresh(player, "config");
            } catch (RuntimeException failure) {
                Tactical.LOGGER.error("[Tactical] character refresh after a config change failed for {}",
                        player.getUUID(), failure);
            }
        }
    }

    /**
     * Re-reads the record, makes the actor's attributes agree with it and pushes the state to that player.
     *
     * <p>It fails closed: a broken record is logged and reported to the client as invalid instead of being
     * replaced, and an unreachable client simply misses one display update.</p>
     */
    public static void refresh(ServerPlayer player, String reason) {
        if (player == null || player.level().isClientSide) return;
        ProgressionRules rules = ProgressionSettings.rules();
        try {
            ProgressionStore.Loaded loaded = ProgressionStore.load(player, rules);
            if (!loaded.usable()) {
                Tactical.LOGGER.warn("[Tactical] character profile of {} is unusable ({}: {}); modifiers left alone",
                        player.getUUID(), loaded.status(), loaded.detail());
                push(player, ProgressionService.snapshot(player, 0, false));
                return;
            }
            ProgressionEffects.apply(player, rules, loaded.profile());
            push(player, ProgressionService.snapshot(player, 0, false));
            if (loaded.rulesChanged())
                Tactical.LOGGER.info("[Tactical] character state refreshed for {} after a rule change ({})",
                        player.getUUID(), reason);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] character refresh for {} failed during {}", player.getUUID(), reason, failure);
        }
    }

    private static void push(ServerPlayer player, CharacterStatePacket packet) {
        if (packet == null || !ServerPackets.canReceive(player)) return;
        ServerPackets.sendUnchecked(player, packet);
    }

    /** Version tag of this feature's stored record, for logs and the delivery document. */
    public static String version() { return VERSION_KEY + "/" + ProgressionStore.SCHEMA; }
}
