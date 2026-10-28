package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.network.BattleStatePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Opt-in real dedicated-server fixture: -Dtactical.selftest=reinforcements. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ReinforcementSelfTest {
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static boolean enabled, configured;
    private static int ticks, stage, since, oldCap;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner, other;
    private static Zombie first, second, overflow, late, far, otherTarget, locked;
    private static ZombifiedPiglin neutral;
    private static Phantom phantom;
    private static ServerBattle encounter;
    private static double phantomY;
    private static String phase;
    private static int ap, inactive;

    private ReinforcementSelfTest() {}

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        enabled = "reinforcements".equals(System.getProperty("tactical.selftest"));
        ticks = stage = since = 0;
    }

    @SubscribeEvent
    public static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        // Capture native flight immediately before the server battle pulse; test admission,
        // not whether vanilla free flight remained stationary during the ten-tick wait.
        if (enabled && stage == 3 && event.getEntity() == phantom
                && encounter != null && !encounter.isParticipant(phantom)) phantomY = phantom.getY();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            check(ticks < 300, "fixture completes before real-tick deadline");
            if (ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            // Forced chunks and freshly added entities become query-visible asynchronously.
            AABB loadedArea = new AABB(origin).inflate(80);
            if (stage == 0 && !level.getEntitiesOfClass(Mob.class, loadedArea).containsAll(
                    List.of(first, second, overflow, late, far, otherTarget, locked, neutral, phantom))) return;
            run(event.getServer());
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] REINFORCEMENT SELFTEST FAILED at tick {} stage {}", ticks, stage, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        oldCap = TacticalConfig.INSTANCE.participantCap.get();
        configured = true;
        TacticalConfig.INSTANCE.participantCap.set(2);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        // Deliberately span several chunk borders and keep native melee out of reach.
        origin = new BlockPos((spawn.getX() >> 4) * 16 + 15,
                Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 20), spawn.getZ());
        for (int cx = (origin.getX() - 72) >> 4; cx <= (origin.getX() + 72) >> 4; cx++) {
            for (int cz = (origin.getZ() - 12) >> 4; cz <= (origin.getZ() + 12) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) chunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -72; x <= 72; x++) for (int z = -10; z <= 10; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 8; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "ReinforceOwner", 0);
        other = player(server, "ReinforceOther", -18);
        first = spawn(EntityType.ZOMBIE, 5, 0);
        second = spawn(EntityType.ZOMBIE, 8, 0);
        overflow = spawn(EntityType.ZOMBIE, 11, 0);
        late = spawn(EntityType.ZOMBIE, 14, 0);
        far = spawn(EntityType.ZOMBIE, BattleManager.zoneRadius() + 20, 0);
        otherTarget = spawn(EntityType.ZOMBIE, -10, 0);
        locked = spawn(EntityType.ZOMBIE, -13, 0);
        locked.setNoAi(true);
        neutral = spawn(EntityType.ZOMBIFIED_PIGLIN, -7, 0);
        phantom = spawn(EntityType.PHANTOM, 7, 4);
        phantom.setNoGravity(true);
        // Hold inactive candidates still until their native target is deliberately assigned.
        late.setNoAi(true);
        far.setNoAi(true);
        phantom.setNoAi(true);
    }

    private static void run(MinecraftServer server) {
        if (stage == 0) {
            check(level.players().containsAll(List.of(owner, other)), "two real server players loaded");
            first.setTarget(owner); second.setTarget(owner); overflow.setTarget(owner);
            otherTarget.setTarget(other);
            check(BattleManager.start(owner), "normal aggro encounter starts");
            encounter = BattleManager.current();
            check(encounter.enemyCount() == 2 && encounter.isParticipant(owner), "cap2 counts enemies, excludes owner");
            check(encounter.isParticipant(first) && encounter.isParticipant(second), "nearest initial enemies selected");
            check(!overflow.isNoAi() && overflow.getTarget() == owner && !encounter.isParticipant(overflow),
                    "initial overflow retains native AI and aggro");
            check(!otherTarget.isNoAi() && otherTarget.getTarget() == other, "other-player target untouched");
            check(locked.isNoAi() && locked.getTarget() == null, "preexisting bystander AI lock untouched");
            check(!encounter.tryAbsorb(far), "outside-attacker path enforces full cap");
            check(!encounter.tryAbsorb(first), "duplicate participant rejected");
            snapshot();
            phase = encounter.phaseName(); ap = encounter.currentAp();
            inactive = wire(owner).inactiveCount();
            first.setHealth(0); first.discard();
            next();
        } else if (stage == 1) {
            sameEncounter();
            if (!encounter.isParticipant(overflow)) { check(ticks - since <= 10, "dead slot refilled within ten ticks"); return; }
            check(encounter.enemyCount() == 2, "overflow refills dead slot");
            check(encounter.phaseName().equals(phase) && encounter.currentAp() == ap, "reinforcement does not reset owner turn/AP");
            snapshot();
            // All present enemies disappear on one real tick, with a pending native aggro target.
            late.setNoAi(false); late.setTarget(owner);
            second.setHealth(0); second.discard();
            overflow.setHealth(0); overflow.discard();
            next();
        } else if (stage == 2) {
            sameEncounter();
            if (!encounter.isParticipant(late)) { check(ticks - since <= 10, "pending aggro joins instead of premature victory"); return; }
            check(encounter.enemyCount() == 1 && late.isNoAi(), "last-dead replacement locked only after admission");
            phantom.setNoAi(false); phantom.setTarget(owner); phantomY = phantom.getY();
            next();
        } else if (stage == 3) {
            sameEncounter();
            if (!encounter.isParticipant(phantom)) { check(ticks - since <= 10, "native late hostile autojoins within ten ticks"); return; }
            check(Math.abs(phantom.getY() - phantomY) < .01, "safe aerial reinforcement preserves Y");
            check(!encounter.tryAbsorb(phantom), "duplicate late participant rejected");
            snapshot();
            late.setHealth(0); late.discard();
            far.setNoAi(false); far.setTarget(owner);
            next();
        } else if (stage == 4) {
            sameEncounter();
            check(!encounter.isParticipant(far), "outside zone bounds inflated eight is not autoabsorbed");
            check(!encounter.isParticipant(neutral) && neutral.getTarget() == null, "neutral without aggro stays outside");
            check(!encounter.isParticipant(otherTarget) && otherTarget.getTarget() == other, "other player's target ignored");
            if (ticks - since < 12) return;
            check(encounter.enemyCount() == 1, "vacant slot remains available for true outside attacker");
            int radius = encounter.zone().radius();
            check(encounter.tryAbsorb(far), "native outside-attacker entry path may expand zone");
            check(encounter.zone().radius() > radius && encounter.enemyCount() == 2, "outside attacker expands and fills slot");
            check(!encounter.tryAbsorb(otherTarget), "cap remains enforced after outside expansion");
            snapshot();
            BattleManager.grantFleeImmunity(owner);
            far.setHealth(0); far.discard();
            otherTarget.setTarget(owner); // ordinary vanilla target hook must reject protected owner
            check(otherTarget.getTarget() == other, "native target hook rejects fled owner while preserving unrelated target");
            next();
        } else if (stage == 5) {
            sameEncounter();
            check(!encounter.isParticipant(otherTarget), "fled-owner grace prevents joins");
            if (ticks - since < 12) return;
            check(!encounter.tryAbsorb(otherTarget), "outside-attacker path respects fled-owner grace");
            BattleManager.stop(owner);
            check(!phantom.isNoAi(), "admitted original-false AI restored on release");
            check(locked.isNoAi(), "release does not mutate nonparticipant original-true AI");
            check(!otherTarget.isNoAi() && !neutral.isNoAi(), "release does not freeze nonparticipants");
            check(wire(owner).inactiveCount() == inactive + 1 && wire(other).states.isEmpty(), "only owner receives one exit state");
            Tactical.LOGGER.info("[Tactical] REINFORCEMENT SELFTEST PASSED: real loaded cross-border entities, cap excludes owner, native target autojoin <=10 ticks, untouched overflow/bystanders, duplicate queue protection, dead-slot and last-dead refill without reset, aerial Y, bounded scanning/outside expansion, flee grace, participant-only AI release, owner-only packets");
            finish(server);
        }
    }

    private static void sameEncounter() {
        check(BattleManager.current() == encounter && !encounter.finished(), "same encounter survives pending reinforcements");
        check(wire(owner).inactiveCount() == inactive, "no premature inactive packet");
    }

    private static void snapshot() {
        BattleStatePacket state = wire(owner).states.getLast();
        check(state.active(), "active state crosses real packet listener");
        int[] ids = state.participants();
        HashSet<Integer> participantIds = new HashSet<>();
        for (int i = 0; i < ids.length; i += 3) check(participantIds.add(ids[i]), "participants contain no duplicate IDs");
        check(state.order().size() == encounter.enemyCount() + 1, "turn order contains exactly living enemies and owner");
        check(Arrays.stream(state.turnEntityIds()).distinct().count() == state.turnEntityIds().length, "queue entity IDs unique");
        check(wire(other).states.isEmpty(), "outsider receives no owner state");
    }

    private static void next() { stage++; since = ticks; }

    private static <T extends Mob> T spawn(EntityType<T> type, int dx, int dy) {
        T mob = type.create(level);
        check(mob != null, "actual mob created");
        mob.setPos(origin.getX() + dx + .5, origin.getY() + dy, origin.getZ() + .5);
        mob.setPersistenceRequired();
        mob.setNoAi(false);
        if (mob.getAttribute(Attributes.MOVEMENT_SPEED) != null) mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        mob.setInvulnerable(true); // sunlight cannot kill a pending fixture
        check(level.addFreshEntity(mob), "actual mob registered");
        entities.add(mob);
        return mob;
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        wire.player = player;
        wires.add(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.setInvulnerable(true);
        level.addNewPlayer(player);
        entities.add(player);
        return player;
    }

    private static RecordingConnection wire(ServerPlayer player) {
        return wires.stream().filter(w -> w.player == player).findFirst().orElseThrow();
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        BattleManager.setAutoTrigger(true);
        if (configured) TacticalConfig.INSTANCE.participantCap.set(oldCap);
        configured = false;
        if (owner != null) FleeProtection.remove(owner);
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer player && !player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            else entity.discard();
        }
        for (RecordingConnection wire : wires) if (wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : chunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        entities.clear(); wires.clear(); chunks.clear();
        server.halt(false);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private ServerPlayer player;
        private final List<BattleStatePacket> states = new ArrayList<>();
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state) states.add(state);
        }
        private int inactiveCount() { return (int) states.stream().filter(s -> !s.active()).count(); }
    }
}
