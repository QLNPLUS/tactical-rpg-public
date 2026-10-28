package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated-server integration fixture, opt-in with {@code -Dtactical.selftest=death}.
 * Uses a disposable world and stops that server after passing or failing. Both players
 * are real ServerPlayers registered in the ServerLevel, not death-immune FakePlayers.
 * The real packet listener performs its ordinary packet validation; only the final
 * Connection transport is replaced with a recorder (there is no remote client).
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class DeathSelfTest {
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static final List<RecordingConnection> connections = new ArrayList<>();
    private static final List<BlockPos> forcedChunks = new ArrayList<>();
    private static boolean enabled;
    private static int ticks;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer outsider;
    private static ServerPlayer cancelDeathOf;
    private static int canceledDeaths;
    private static int actualDeaths;
    private static Creeper enemy;

    private DeathSelfTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "death".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        canceledDeaths = 0;
        actualDeaths = 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!enabled) return;
        if (event.getEntity() == cancelDeathOf) {
            // A cancellation that revives is the meaningful case: a canceled event
            // left at health zero is still dead to vanilla and to the tick fallback.
            event.setCanceled(true);
            event.getEntity().setHealth(event.getEntity().getMaxHealth());
            canceledDeaths++;
        } else if (event.getEntity() == owner || event.getEntity() == outsider) {
            actualDeaths++;
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            if (ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            check(ticks < 200, "fixture chunks become entity-ticking before deadline");
            // Chunk visibility is asynchronous even when its blocks were just written.
            if (!level.getEntitiesOfClass(Creeper.class, owner.getBoundingBox().inflate(10)).contains(enemy)) return;
            run(event);
            Tactical.LOGGER.info("[Tactical] DEATH SELFTEST PASSED: real owner+outsider deaths, owner-only stop, canceled death, dead-owner S2C through actual listener, mob/effect/reach release, same-UUID respawn before/after tick, stale intents, isolated login/logout");
            finish(event.getServer());
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] DEATH SELFTEST FAILED at tick {}", ticks, failure);
            try {
                finish(event.getServer());
            } catch (Throwable ignored) {
                // A broken teardown must not hide the original failure.
            }
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int floor = Math.min(level.getMaxBuildHeight() - 8, spawn.getY() + 16);
        origin = new BlockPos(spawn.getX(), floor + 1, spawn.getZ());
        for (int cx = (origin.getX() - 12) >> 4; cx <= (origin.getX() + 12) >> 4; cx++) {
            for (int cz = (origin.getZ() - 12) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forcedChunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 4; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        owner = player(server, uuid("owner"), "DeathOwner", 0);
        outsider = player(server, uuid("outsider"), "DeathOutsider", -3);
        check(!owner.getUUID().equals(outsider.getUUID()), "players have distinct UUIDs");
        enemy = EntityType.CREEPER.create(level);
        check(enemy != null, "actual enemy created");
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        enemy.setPersistenceRequired();
        // Prevent normal AI from changing the fixture while its chunk settles.
        enemy.setNoAi(true);
        check(level.addFreshEntity(enemy), "enemy registered in server level");
    }

    private static ServerPlayer player(MinecraftServer server, UUID uuid, String name, int dx) {
        GameProfile profile = new GameProfile(uuid, name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection transport = new RecordingConnection();
        connections.add(transport);
        // Installs Connection.channel via its real channelActive path, allowing real
        // NetworkRegistry.checkPacket/ChannelAttributes to run without mocking them.
        transport.channel = new EmbeddedChannel(transport);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(transport).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, transport, player, CommonListenerCookie.createInitial(profile, false));
        transport.player = player;
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.2);
        level.addNewPlayer(player);
        players.add(player);
        return player;
    }

    private static void run(ServerTickEvent.Post event) {
        check(level.players().contains(owner) && level.players().contains(outsider), "both server player entities coexist");
        enemy.setNoAi(false);
        double blockReach = owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        double entityReach = owner.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        ServerBattle first = start(owner);
        RecordingConnection ownerWire = wire(owner);
        RecordingConnection outsiderWire = wire(outsider);
        check(ownerWire.count(true) > 0, "positive control: active state crosses actual packet listener");
        int ownerInactive = ownerWire.count(false);
        int outsiderStates = outsiderWire.states.size();
        check(owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) > blockReach, "battle extends owner reach");
        enemy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        BattleEffects.onTurnStart(enemy);
        MobEffectInstance speed = enemy.getEffect(MobEffects.MOVEMENT_SPEED);
        check(speed != null, "effect attached to participant");
        speed.tick(enemy, () -> {});
        check(speed.getDuration() == 600, "participant vanilla effect clock is frozen");

        BattleManager.stop(outsider);
        assertRunning(first, "non-owner stop cannot end encounter or release enemy");
        kill(outsider);
        check(actualDeaths == 1 && !outsider.isAlive(), "outsider follows real cancellable vanilla death path");
        BattleManager.onServerTick(event);
        assertRunning(first, "outsider death cannot end owner's encounter or release enemy");
        check(ownerWire.count(false) == ownerInactive && outsiderWire.states.size() == outsiderStates,
                "outsider death sends no tactical state to either client");
        speed.tick(enemy, () -> {});
        check(speed.getDuration() == 600, "outsider death does not unfreeze participant effects");

        cancelDeathOf = owner;
        kill(owner);
        cancelDeathOf = null;
        check(canceledDeaths == 1 && owner.isAlive(), "real LivingDeathEvent can be canceled and revive owner");
        BattleManager.onServerTick(event);
        assertRunning(first, "canceled owner death must retain encounter");
        check(ownerWire.count(false) == ownerInactive, "canceled owner death does not emit exit");

        kill(owner);
        check(!owner.isAlive() && actualDeaths == 2, "owner actually dies, without FakePlayer immunity");
        check(ownerWire.killPackets > 0, "vanilla death packet crosses actual listener");
        BattleManager.onServerTick(event);
        check(BattleManager.current() == null && first.finished(), "owner death ends and removes encounter within one server tick");
        check(!enemy.isNoAi() && enemy.getTarget() == null && !BattleManager.isParticipant(enemy), "owner death releases mob AI, aggro and participation");
        check(owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == blockReach
                && owner.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE) == entityReach, "dead owner's transient reach restored");
        check(ownerWire.count(false) == ownerInactive + 1, "exactly one inactive S2C sent to dead owner connection");
        check(ownerWire.states.get(ownerWire.states.size() - 1).deadAtSend, "inactive S2C was sent while owner was dead");
        assertInactive(ownerWire.last());
        check(outsiderWire.states.size() == outsiderStates, "owner death does not target outsider connection");
        speed.tick(enemy, () -> {});
        check(speed.getDuration() == 599, "vanilla effect clock resumes after owner death");
        BattleEffects.onTurnStart(enemy);
        check(speed.getDuration() == 599, "owner death forgets old turn-clock baseline");
        BattleManager.onServerTick(event);
        first.tick();
        check(ownerWire.count(false) == ownerInactive + 1, "finished encounter cannot resend late exit");
        check(!BattleManager.start(owner, true), "dead owner cannot immediately restart encounter");

        ServerPlayer deadOwner = owner;
        Vec3 corpsePosition = deadOwner.position();
        remove(deadOwner);
        owner = player(event.getServer(), deadOwner.getUUID(), "DeathOwner", 0);
        check(owner != deadOwner && owner.getUUID().equals(deadOwner.getUUID()), "respawn is distinct entity with same UUID");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(owner, false));
        check(BattleManager.current() == null && !BattleManager.isParticipant(owner), "replacement cannot inherit finished encounter");
        assertInactive(wire(owner).last());
        check(owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == blockReach, "replacement has no stale reach modifier");
        enemy.removeAllEffects();
        ServerBattle second = start(owner);
        int ap = second.currentAp();
        int revision = second.stateRevision();
        staleIntents(deadOwner);
        first.tick();
        check(BattleManager.current() == second && second.currentAp() == ap && second.stateRevision() == revision,
                "late same-UUID old-owner commands cannot mutate replacement encounter");
        check(deadOwner.position().equals(corpsePosition) && !deadOwner.isAlive(), "finished encounter cannot move or revive old owner");

        remove(outsider);
        outsider = player(event.getServer(), outsider.getUUID(), "DeathOutsider", -3);
        int secondInactive = wire(owner).count(false);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(outsider, false));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(outsider));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(outsider));
        assertRunning(second, "outsider respawn/login/logout cannot terminate owner's encounter");
        check(wire(owner).count(false) == secondInactive, "outsider lifecycle cannot reset owner client");
        assertInactive(wire(outsider).last());

        // Race regression: replacement arrives before the next battle tick sees death.
        ServerPlayer oldReplacement = owner;
        kill(oldReplacement);
        check(!oldReplacement.isAlive() && BattleManager.current() == second, "owner death awaits tick fallback before replacement event");
        remove(oldReplacement);
        owner = player(event.getServer(), oldReplacement.getUUID(), "DeathOwner", 0);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerRespawnEvent(owner, false));
        check(BattleManager.current() == null && second.finished() && !enemy.isNoAi(), "same-UUID respawn before tick releases stale encounter");
        check(!BattleManager.isParticipant(owner), "early replacement not retained as participant");
        assertInactive(wire(owner).last());
        ServerBattle third = start(owner);
        staleIntents(oldReplacement);
        check(BattleManager.current() == third && !third.finished(), "older same-UUID entity cannot stop newly started encounter");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(owner));
        check(BattleManager.current() == null && third.finished() && !enemy.isNoAi(), "owner logout releases only owner's encounter");
        assertInactive(wire(owner).last());
    }

    private static ServerBattle start(ServerPlayer actor) {
        enemy.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() + .5);
        enemy.setNoAi(false);
        check(BattleManager.start(actor, true), "owner battle starts");
        ServerBattle battle = BattleManager.current();
        check(battle != null && battle.isOwner(actor) && battle.isPlayerTurnNow(), "real owner wins initiative deterministically");
        check(BattleManager.isParticipant(enemy) && enemy.isNoAi(), "actual enemy captured and frozen");
        return battle;
    }

    private static void assertRunning(ServerBattle expected, String message) {
        check(BattleManager.current() == expected && !expected.finished() && enemy.isNoAi()
                && BattleManager.isParticipant(enemy), message);
    }

    private static void staleIntents(ServerPlayer oldOwner) {
        BattleManager.stop(oldOwner);
        BattleManager.move(oldOwner, origin.getX() + 1, origin.getY(), origin.getZ());
        BattleManager.attack(oldOwner, enemy.getUUID());
        BattleManager.endTurn(oldOwner);
        BattleManager.endTurnFacing(oldOwner, 90);
        BattleManager.defend(oldOwner);
        BattleManager.flee(oldOwner);
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(oldOwner));
    }

    private static void kill(ServerPlayer player) {
        player.invulnerableTime = 0;
        // genericKill bypasses a new ServerPlayer's 60-tick spawn immunity. The
        // actual hurt -> die -> LivingDeathEvent path is never stubbed or replaced.
        check(player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE), "real lethal damage accepted");
    }

    private static void assertInactive(BattleStatePacket packet) {
        check(!packet.active() && !packet.moving() && !packet.defending() && packet.ap() == 0
                && packet.reachable().length == 0 && packet.participants().length == 0
                && packet.gauges().length == 0 && packet.order().isEmpty() && packet.actorEntityId() == -1,
                "inactive snapshot clears all battle entity and movement references");
    }

    private static RecordingConnection wire(ServerPlayer player) {
        return connections.stream().filter(connection -> connection.player == player).findFirst().orElseThrow();
    }

    private static void remove(ServerPlayer player) {
        if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        cancelDeathOf = null;
        BattleManager.reset();
        if (enemy != null) enemy.discard();
        for (ServerPlayer player : players) remove(player);
        for (RecordingConnection connection : connections) {
            if (connection.channel != null) connection.channel.finishAndReleaseAll();
        }
        if (level != null) for (BlockPos chunk : forcedChunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        players.clear();
        connections.clear();
        forcedChunks.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("tactical-death-selftest-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record SentState(BattleStatePacket packet, boolean deadAtSend) {}

    private static final class RecordingConnection extends Connection {
        private final List<SentState> states = new ArrayList<>();
        private EmbeddedChannel channel;
        private ServerPlayer player;
        private int killPackets;

        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state) {
                states.add(new SentState(state, player != null && !player.isAlive()));
            }
            if (packet instanceof ClientboundPlayerCombatKillPacket) killPackets++;
        }

        private int count(boolean active) {
            return (int) states.stream().filter(state -> state.packet.active() == active).count();
        }

        private BattleStatePacket last() {
            check(!states.isEmpty(), "connection recorded a tactical state");
            return states.get(states.size() - 1).packet;
        }
    }
}
