package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.AiAction;
import com.tactical.neoforge.battle.ai.AiContext;
import com.tactical.neoforge.battle.ai.BattleAiSession;
import com.tactical.neoforge.battle.ai.GenericMeleeAi;
import com.tactical.neoforge.battle.ai.PhantomTacticalAi;
import com.tactical.neoforge.battle.ai.TacticalAiRegistry;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.network.BattleLogPacket;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disposable dedicated-server fixture: -Dtactical.selftest=flight. No client or test-only damage. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class FlightSelfTest {
    private static boolean enabled;
    private static int ticks, stage, stageAt, hits, beforeAp, expectedCost;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Phantom phantom;
    private static ServerBattle battle;
    private static RecordingConnection wire;
    private static Vec3 home;
    private static float ownerHealth;
    private static final List<BlockPos> forced = new ArrayList<>();

    private FlightSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "flight".equals(System.getProperty("tactical.selftest"));
        ticks = stage = stageAt = hits = 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || event.getEntity() != owner || event.getSource().getEntity() != phantom || event.getNewDamage() <= 0) return;
        require(battle != null && !battle.isPlayerTurnNow() && wire.state != null
                && wire.state.actorEntityId() == phantom.getId(), "actual vanilla phantom damage occurs only in its own turn");
        hits++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 1000, "flight fixture completion deadline");
            if (stage == 0) {
                setup(event.getServer());
                stage = 1;
            } else if (stage == 1) {
                if (ticks < 100 || !level.getEntitiesOfClass(Phantom.class, owner.getBoundingBox().inflate(14)).contains(phantom)) return;
                for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(24))) {
                    if (other != phantom && other instanceof net.minecraft.world.entity.monster.Enemy) other.discard();
                }
                phantom.setPos(home);
                checkPureHelpers();
                checkPhysicalGeometry();
                require(BattleManager.start(owner, true), "airborne encounter starts");
                battle = BattleManager.current();
                require(battle != null && battle.isPlayerTurnNow(), "high initiative real owner starts");
                require(phantom.position().distanceToSqr(home) < 1.0E-10, "deployment preserves exact fractional airborne altitude");
                AiContext context = actualContext(enemyInt("ap"));
                AiAction planned = new PhantomTacticalAi().planTurn(context);
                require(planned.kind() == AiAction.Kind.DIVE && BattleAiSession.validDive(context, planned), "actual loaded phantom has complete collision-safe dive plan");
                beforeAp = enemyInt("ap");
                expectedCost = planned.path().size() * context.moveCost() + context.meleeCost();
                ownerHealth = owner.getHealth();
                stageAt = ticks;
                stage = 2;
            } else if (stage == 2) {
                require(battle.isPlayerTurnNow() && hits == 0 && owner.getHealth() == ownerHealth
                        && phantom.position().distanceToSqr(home) < 1.0E-10, "idle player turn holds phantom in air without movement or damage");
                if (ticks - stageAt < 80) return;
                owner.invulnerableTime = 0;
                battle.endPlayerTurn();
                stage = 3;
            } else if (stage == 3) {
                if (!battle.isPlayerTurnNow()) return;
                if (hits == 0) { battle.endPlayerTurn(); return; }
                require(hits == 1 && owner.getHealth() < ownerHealth, "own-turn vanilla phantom melee deals actual damage exactly once");
                require(phantom.position().distanceToSqr(home) < 1.0E-8, "complete dive returns to exact airborne home");
                require(enemyInt("ap") == beforeAp - expectedCost, "full outbound and return movement plus melee charged once");
                stageAt = ticks;
                stage = 4;
            } else if (stage == 4) {
                require(battle.isPlayerTurnNow() && hits == 1 && phantom.position().distanceToSqr(home) < 1.0E-8,
                        "no out-of-turn repeated dive or gravity fall after return");
                if (ticks - stageAt < 100) return;
                Tactical.LOGGER.info("[Tactical] FLIGHT SELFTEST PASSED: real phantom exact fractional altitude, 3D swept-body/roof/actor obstruction, full own-turn native damage+return, exact movement+melee AP, idle no attack, insufficient-AP approach, bounded pure path tests");
                finish(event.getServer());
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] FLIGHT SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            Tactical.LOGGER.error("[Tactical] FLIGHT DIAGNOSTIC actual={} home={} player={} bbox={} delta={} hits={} ap={}/{} expectedCost={} wire={} journal={}",
                    phantom == null ? null : phantom.position(), home, owner == null ? null : owner.position(),
                    phantom == null ? null : phantom.getBoundingBox(), phantom == null ? null : phantom.getDeltaMovement(),
                    hits, battle == null ? -1 : enemyInt("ap"), beforeAp, expectedCost,
                    wire == null ? null : wire.state, wire == null ? null : wire.log);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static AiContext actualContext(int ap) {
        GridPos start = new GridPos(phantom.getBlockX(), phantom.getBlockY(), phantom.getBlockZ());
        GridPos player = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
        Vec3 offset = phantom.position().subtract(AerialMovement.feet(start));
        int moveCost = enemyInt("moveCost");
        return new AiContext() {
            public Mob actor() { return phantom; }
            public GridPos actorCell() { return start; }
            public GridPos playerCell() { return player; }
            public int ap() { return ap; }
            public int moveCost() { return moveCost; }
            public int meleeCost() { return battle.currentMeleeCost(); }
            public List<GridPos> pathToPlayer() { return List.of(); }
            public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            public List<GridPos> airNeighbors(GridPos from) {
                return AerialMovement.neighbors(level, phantom, from, offset, this::occupied,
                        cell -> Math.abs(cell.x() - player.x()) <= 12 && Math.abs(cell.z() - player.z()) <= 12
                                && cell.y() >= player.y() && cell.y() <= player.y() + 10);
            }
            public boolean occupied(GridPos cell) { return cell.equals(player); }
            public boolean inMeleeRange(GridPos from, GridPos to) {
                return Math.abs(from.x() - to.x()) <= 1 && Math.abs(from.y() - to.y()) <= 1 && Math.abs(from.z() - to.z()) <= 1;
            }
            public boolean hasLineOfSight(GridPos from, GridPos to) { return true; }
            public void attackPlayer() { throw new AssertionError("planner cannot directly attack"); }
            public void log(String message) { }
        };
    }

    private static void checkPhysicalGeometry() {
        require(AerialMovement.supports(phantom) && TacticalAiRegistry.create(phantom) instanceof PhantomTacticalAi,
                "actual vanilla phantom uses flying policy and exact adapter");
        var vex = EntityType.VEX.create(level);
        require(vex != null && AerialMovement.supports(vex) && TacticalAiRegistry.create(vex) instanceof GenericMeleeAi,
                "other flying types keep generic fallback, not phantom dive adapter");
        require(AerialMovement.findNearestClearAir(level, phantom, ignored -> false, ignored -> true).orElseThrow().equals(home),
                "air deployment needs no floor and preserves fractional altitude");
        Vec3 from = home, to = home.add(2, 0, 0);
        BlockPos wall = BlockPos.containing(home.add(1, 0, 0));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), 3);
        require(AerialMovement.isClearAir(level, phantom, from) && AerialMovement.isClearAir(level, phantom, to)
                && !AerialMovement.isSegmentClear(level, phantom, from, to), "clear endpoints cannot tunnel through intermediate wall");
        level.setBlock(wall, Blocks.AIR.defaultBlockState(), 3);
        require(!AerialMovement.isClearAir(level, phantom, owner.position()), "full body cannot occupy actual player");
        Vec3 roofPoint = home.add(0, 1, 0);
        BlockPos roof = BlockPos.containing(roofPoint);
        level.setBlock(roof, Blocks.STONE.defaultBlockState(), 3);
        require(!AerialMovement.isSegmentClear(level, phantom, home, roofPoint), "vertical segment rejects roof");
        level.setBlock(roof, Blocks.AIR.defaultBlockState(), 3);
    }

    /** Deterministic helpers also exercise a real Phantom as the adapter's actor. */
    private static void checkPureHelpers() {
        GridPos start = new GridPos(0, 4, 0), attack = new GridPos(2, 2, 0), player = new GridPos(3, 1, 0);
        List<GridPos> outbound = List.of(new GridPos(1, 3, 0), attack);
        List<GridPos> trip = PhantomTacticalAi.roundTrip(start, outbound);
        require(trip.equals(List.of(outbound.get(0), attack, outbound.get(0), start)), "pure roundtrip excludes start until final return");
        int meleeCost = com.tactical.core.combat.ApCalculator.defaultMeleeCost();
        int completeCost = 4 * 5 + meleeCost;
        require(PhantomTacticalAi.affordable(4, 5, meleeCost, completeCost)
                && !PhantomTacticalAi.affordable(4, 5, meleeCost, completeCost - 1)
                && !PhantomTacticalAi.affordable(Integer.MAX_VALUE, Integer.MAX_VALUE, meleeCost, completeCost), "exact AP boundary and overflow safety");
        AiContext enough = corridorContext(start, player, completeCost);
        AiAction dive = new PhantomTacticalAi().planTurn(enough);
        require(dive.kind() == AiAction.Kind.DIVE && dive.attackIndex() == 1 && BattleAiSession.validDive(enough, dive), "affordable pure dive validates turnaround and return");
        AiAction poor = new PhantomTacticalAi().planTurn(corridorContext(start, player, completeCost - 1));
        require(poor.kind() == AiAction.Kind.MOVE && poor.path().equals(outbound), "insufficient complete-dive AP chooses paid approach, not free return");
        require(new PhantomTacticalAi().planTurn(corridorContext(start, player, 4)).kind() == AiAction.Kind.END_TURN,
                "less than one step AP cannot move");
        require(!BattleAiSession.validDive(corridorContext(start, player, completeCost - 1), dive), "validator independently rejects unaffordable dive");
        require(AerialMovement.findPath(start, attack, 1, enough::airNeighbors).isEmpty()
                && AerialMovement.findPath(start, attack, 2, enough::airNeighbors).equals(outbound), "pure 3D BFS obeys exact step budget");
        boolean rejected = false;
        try { new AiAction(AiAction.Kind.DIVE, trip, trip.size() - 1); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "attack marker must leave a nonempty return");
    }

    private static AiContext corridorContext(GridPos start, GridPos player, int ap) {
        GridPos middle = new GridPos(1, 3, 0), attack = new GridPos(2, 2, 0);
        Map<GridPos, List<GridPos>> edges = Map.of(start, List.of(middle), middle, List.of(start, attack), attack, List.of(middle));
        return new AiContext() {
            public Mob actor() { return phantom; }
            public GridPos actorCell() { return start; }
            public GridPos playerCell() { return player; }
            public int ap() { return ap; }
            public int moveCost() { return 5; }
            public List<GridPos> pathToPlayer() { return List.of(); }
            public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
            public List<GridPos> airNeighbors(GridPos from) { return edges.getOrDefault(from, List.of()); }
            public boolean occupied(GridPos cell) { return cell.equals(player); }
            public boolean inMeleeRange(GridPos from, GridPos target) { return from.equals(attack); }
            public boolean hasLineOfSight(GridPos from, GridPos target) { return true; }
            public void attackPlayer() { throw new AssertionError("planning caused attack"); }
            public void log(String message) { }
        };
    }

    private static int enemyInt(String name) {
        try {
            var enemiesField = ServerBattle.class.getDeclaredField("enemies");
            enemiesField.setAccessible(true);
            Object state = ((Map<?, ?>) enemiesField.get(battle)).get(phantom.getUUID());
            var field = state.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(state);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("flight fixture readback", failure); }
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 16, spawn.getY() + 24), spawn.getZ());
        for (int cx = (origin.getX() - 20) >> 4; cx <= (origin.getX() + 20) >> 4; cx++) {
            for (int cz = (origin.getZ() - 20) >> 4; cz <= (origin.getZ() + 20) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y <= 12; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes("tactical-flight-selftest".getBytes(StandardCharsets.UTF_8)), "FlightOwner");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, owner, CommonListenerCookie.createInitial(profile, false));
        owner.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        owner.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        owner.setHealth(100);
        level.addNewPlayer(owner);
        phantom = EntityType.PHANTOM.create(level);
        require(phantom != null, "actual vanilla phantom exists");
        home = new Vec3(origin.getX() + 4.5, origin.getY() + 4.25, origin.getZ() + .5);
        phantom.setPos(home);
        phantom.setNoAi(true);
        phantom.setPersistenceRequired();
        require(level.addFreshEntity(phantom), "phantom registered in real level");
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (phantom != null) phantom.discard();
        if (owner != null && !owner.isRemoved()) level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private BattleStatePacket state;
        private BattleLogPacket log;
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) {
                if (custom.payload() instanceof BattleStatePacket next) state = next;
                if (custom.payload() instanceof BattleLogPacket next) log = next;
            }
        }
    }
}
