package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.DrownedTacticalAi;
import com.tactical.neoforge.battle.ai.GenericMeleeAi;
import com.tactical.neoforge.battle.ai.GuardianTacticalAi;
import com.tactical.neoforge.battle.ai.TacticalAiRegistry;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Real loaded-server fixture for the guardian family: {@code -Dtactical.selftest=guardian}.
 *
 * <p>Every reading comes from a booted dedicated server: a real {@link ServerLevel}, real vanilla guardians with
 * their own bodies, and the encounter's own admission, water-route, frame and refund paths. The stages follow the
 * frozen groups - the two exact types with their native bodies and borrowed flags (S1), the real water geometry,
 * the narrow tunnel, the 3D edges, the negatives and the bounded search (S2), and the interruption with its frozen
 * per-edge price (S3).</p>
 *
 * <p>Two kinds of waiting are used on purpose. A synchronous read proves a geometry or a transaction answer; a
 * stage that ends in {@link #await} lets <em>real server ticks</em> pass, so an animated swim, a real interruption
 * and a native descent are only believed when the world really did them.</p>
 *
 * <p>No client is launched and no user world is touched: the run directory is its own (see
 * {@code serverGuardianTest} in {@code build.gradle}). A failure halts the server with a non-zero code, so a green
 * Gradle task can never look like a passing fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class GuardianSelfTest {

    /** How long the fixture may wait for its own world to become real before it reports what is still missing. */
    private static final int WORLD_READY_LIMIT = 200;
    /** A stage's own bounded wait, in real server ticks. */
    private static final int SETTLE_LIMIT = 800;
    /**
     * The bounded wait for a stage that has to see a dozen own turns of one unit.
     *
     * <p>The shared queue is an ATB charge order and each of those own turns may really fire a beam, so such a stage
     * legitimately costs an order of magnitude more ticks than a single action. The bound is still finite and still
     * a real deadline, not an open wait.</p>
     */
    private static final int CADENCE_LIMIT = 4000;

    private static boolean enabled;
    private static int ticks;
    private static int checks;
    private static int stage;
    private static boolean worldReady;
    private static int worldReadyWaits;
    private static final List<Runnable> STAGES = List.of(
            GuardianSelfTest::exactTypesAndRegistry,
            GuardianSelfTest::nativeBodiesAndAttributes,
            GuardianSelfTest::borrowedFlagsReturnOnExit,
            GuardianSelfTest::failedAdmissionRollsBack,
            GuardianSelfTest::otherCreaturesUnchanged,
            GuardianSelfTest::outsideBattleKeepsNativeTravel,
            GuardianSelfTest::narrowWaterTunnel,
            GuardianSelfTest::fractionalAdmissionKeepsRealFeet,
            GuardianSelfTest::threeDimensionalWaterEdges,
            GuardianSelfTest::oneLegStaysUnderWaterThroughout,
            GuardianSelfTest::boundedSearchHonoursItsCeiling,
            GuardianSelfTest::shrunkenBudgetNeverSwimsCloser,
            GuardianSelfTest::fractionalFeetDescentKeepsItsPosition,
            GuardianSelfTest::bodyZoneOccupancyHazardAndMedium,
            GuardianSelfTest::unloadedChunkRefusedWithoutLoading,
            GuardianSelfTest::swimsToARealFiringPosition,
            GuardianSelfTest::closeRangeMustBackAway,
            GuardianSelfTest::elderHasNoNearGate,
            GuardianSelfTest::deploymentAndReinforcementGates,
            GuardianSelfTest::firstFrameRefusalIsFree,
            GuardianSelfTest::startedEdgeKeepsFrozenPrice,
            GuardianSelfTest::drainedMidRouteSettlesSafely,
            GuardianSelfTest::idleDrainIsHandled,
            GuardianSelfTest::nativeBeamChargesAfterArriving,
            GuardianSelfTest::elderFiresInCloseRange,
            GuardianSelfTest::nativeBeamReceiptIsOneCharge,
            GuardianSelfTest::nativeBeamWithBothPartsCancelledChargesNothing,
            GuardianSelfTest::nativeBeamChargesOnceWhenTheSecondPartIsRefused,
            GuardianSelfTest::anUnpayableBeamStopsBeforeItsHit,
            GuardianSelfTest::nativeThornsStayInThisEncounter,
            GuardianSelfTest::elderFatiguePulsesOnItsOwnTurns,
            GuardianSelfTest::elderFatigueRespectsStrongerEffectsAndExit,
            GuardianSelfTest::beamLifecycleCleansUp,
            GuardianSelfTest::beamStopsWhenTheWaterIsGone,
            GuardianSelfTest::beamStopsOnEncounterEnd,
            GuardianSelfTest::failedBeamLaunchRollsBack,
            GuardianSelfTest::aNestedOtherGuardianCannotFillTheReceipt,
            GuardianSelfTest::aSameActorBypassCannotFillTheReceipt,
            GuardianSelfTest::aRemovedGoalStopsTheBeam,
            GuardianSelfTest::aThrowingNativeStopStillChargesOnce,
            GuardianSelfTest::aDeadActorStillGivesItsStateBack,
            GuardianSelfTest::admissionClearsTheOldUnpaidAttack,
            GuardianSelfTest::aFailedAdmissionRestoresTheNativePose,
            GuardianSelfTest::theSessionQuotesItsOwnConfiguredPrice,
            GuardianSelfTest::aHardArmouredBeamKeepsTheNativeNumbers);

    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerBattle battle;
    private static ServerBattle secondBattle;
    /** The second encounter's own player, so another encounter's member can be checked for effects it never got. */
    private static ServerPlayer secondOwner;
    private static MinecraftServer serverRef;
    private static Wire wire;
    private static final List<Entity> spawned = new ArrayList<>();

    private static int waitTicks;
    private static Runnable afterWait;

    private GuardianSelfTest() { }

    // ------------------------------------------------------------------ fixture plumbing

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        enabled = "guardian".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        checks = 0;
        stage = 0;
        worldReady = false;
        worldReadyWaits = 0;
        waitTicks = 0;
        afterWait = null;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            if (afterWait != null) {
                Runnable next = afterWait;
                afterWait = null;
                next.run();
                return;
            }
            if (++ticks == 20) {
                setup(event.getServer());
                return;
            }
            if (ticks < 40) return;
            if (!worldReady) {
                String missing = worldNotReady();
                if (missing == null) {
                    worldReady = true;
                } else {
                    worldReadyWaits++;
                    if (worldReadyWaits > WORLD_READY_LIMIT) {
                        check(false, "the fixture world never became real within " + WORLD_READY_LIMIT
                                + " ticks: " + missing);
                    } else if (worldReadyWaits % 20 == 0) {
                        Tactical.LOGGER.info("[Tactical] GUARDIAN waiting for its own world: {} ({} of {} ticks)",
                                missing, worldReadyWaits, WORLD_READY_LIMIT);
                    }
                    return;
                }
            }
            if (stage < STAGES.size()) {
                check(ticks < 30000, "fixture deadline");
                STAGES.get(stage++).run();
                return;
            }
            Tactical.LOGGER.info("[Tactical] GUARDIAN SELFTEST PASSED: {} checks on the two exact types and their "
                    + "native bodies, the borrowed flags, the water geometry and its negatives, the 3D edges, the "
                    + "bounded search, the real firing position, the retreat, the elder near gate, the deployment "
                    + "gates and the frozen per-edge price with its interruption, the real native beam with its two "
                    + "native parts, the encounter's own spikes, the elder's own-turn fatigue and every beam exit",
                    checks);
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] GUARDIAN SELFTEST FAILED after {} checks", checks, failure);
            finish(event.getServer());
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    /** Let {@code count} real server ticks pass, then run {@code then} on a later tick. */
    private static void await(int count, Runnable then) {
        waitTicks = Math.max(0, count);
        afterWait = then;
    }

    private static void setup(MinecraftServer server) {
        serverRef = server;
        BattleManager.setAutoTrigger(false);
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX() + 320, 80, spawn.getZ() + 320);
        for (int x = (origin.getX() - 64) >> 4; x <= (origin.getX() + 64) >> 4; x++) {
            for (int z = (origin.getZ() - 64) >> 4; z <= (origin.getZ() + 64) >> 4; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-guardian-fixture".getBytes(StandardCharsets.UTF_8)), "GuardianOwner");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        wire = new Wire();
        wire.channel = new io.netty.channel.embedded.EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer")) {
            net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(wire)
                    .add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, owner,
                CommonListenerCookie.createInitial(profile, false));
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        owner.setHealth(1000);
        owner.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(owner);
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        resetArena();
    }

    /**
     * The first precondition of this fixture that its own world does not satisfy yet, or {@code null}.
     *
     * <p>Read from the live server rather than assumed: the fixture's own chunk has to be entity ticking, and its
     * floor has to read back as the stone it was given. A reused world is already ticking; a brand-new one has to
     * generate and promote the fixture's chunks first.</p>
     */
    private static String worldNotReady() {
        if (level == null || origin == null) return "the fixture has no level or origin yet";
        BlockPos probe = origin.below();
        long chunk = net.minecraft.world.level.ChunkPos.asLong(probe);
        if (!level.areEntitiesLoaded(chunk)) return "the fixture chunk is not entity loaded";
        if (!level.isPositionEntityTicking(probe)) return "the fixture chunk is not entity ticking";
        if (!level.getBlockState(probe).is(Blocks.STONE)) {
            return "the fixture floor at " + probe.toShortString() + " does not read back as stone";
        }
        if (!level.getBlockState(origin).isAir()) {
            return "the cell above the fixture floor at " + origin.toShortString() + " is not air";
        }
        return null;
    }

    // ------------------------------------------------------------------ arena

    private static void resetArena() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = -6; y <= 3; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    /**
     * The measuring water: a two-deep open pit, an island the target can stand on, and one stone wall across the
     * western half.
     *
     * <p>Neither the island nor the wall is decoration. The wall is what makes a route necessary at all: a body
     * west of it has no native line of sight to the platform, so it has to swim through the gap at the south end
     * before it could fire - and the gap makes that a real three-dimensional water route. The island is what makes
     * "too close for vanilla's near gate" reachable without inventing a floating target.</p>
     */
    private static void buildPit() {
        resetArena();
        for (int x = -8; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlock(origin.offset(x, -4, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, -2, z), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(origin.offset(x, -3, z), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        // The arena floor is laid over the whole area first, so the pit's own mouth has to be opened again: without
        // this the water would sit under a stone lid and nothing in it could ever see the platform.
        for (int x = -8; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int x = -9; x <= 9; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean rim = x == -9 || x == 9 || z == -4 || z == 4;
                if (!rim) continue;
                for (int y = -3; y <= -1; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        // The island the target can stand on inside the pit, at the open half.
        for (int y = -3; y <= -1; y++) {
            level.setBlock(origin.offset(6, y, 0), Blocks.STONE.defaultBlockState(), 2);
        }
        // The wall: one block thick, from the floor to well above the surface, with a two-cell gap at the south end.
        for (int z = -3; z <= 1; z++) {
            for (int y = -3; y <= 1; y++) {
                level.setBlock(origin.offset(-1, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    /** One 1x1 water tunnel: a 0.85 block body fits it, a 1.9975 block body does not. */
    private static void buildTunnel(int z0, int from, int to) {
        for (int x = from; x <= to; x++) {
            level.setBlock(origin.offset(x, 0, z0), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(origin.offset(x, -1, z0), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(origin.offset(x, 1, z0), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(origin.offset(x, 0, z0 - 1), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(origin.offset(x, 0, z0 + 1), Blocks.STONE.defaultBlockState(), 2);
        }
    }

    /** Take that chamber's whole volume back out, so a reused world never carries one into the next run. */
    private static void clearChamber(int dx, int dy, int dz, int sx, int sy, int sz) {
        for (int x = -1; x <= sx; x++) {
            for (int y = -1; y <= sy; y++) {
                for (int z = -1; z <= sz; z++) {
                    level.setBlock(origin.offset(dx + x, dy + y, dz + z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * A sealed rectangular water chamber: every cell of it a legal position and nothing outside it reachable.
     *
     * <p>It is deliberately small - a single stone shell around a bounded volume - because the ceiling is a property
     * of the search, not of a long tunnel: a chamber of more cells than the ceiling lets the search hit its bound
     * while the far corner stays genuinely reachable, which is exactly the discrimination the check needs. The
     * shell is airtight, so natural terrain outside can never be mistaken for part of the pocket.</p>
     */
    private static void buildChamber(int dx, int dy, int dz, int sx, int sy, int sz) {
        for (int x = -1; x <= sx; x++) {
            for (int y = -1; y <= sy; y++) {
                for (int z = -1; z <= sz; z++) {
                    boolean inside = x >= 0 && x < sx && y >= 0 && y < sy && z >= 0 && z < sz;
                    BlockPos pos = origin.offset(dx + x, dy + y, dz + z);
                    level.setBlock(pos, (inside ? Blocks.WATER : Blocks.STONE).defaultBlockState(), 2);
                }
            }
        }
    }

    // ------------------------------------------------------------------ bodies

    private static <T extends Entity> T spawn(EntityType<T> type, double dx, double dy, double dz,
                                              boolean noAi, boolean persistent) {
        T entity = type.create(level);
        check(entity != null, "the native " + type.toShortString() + " was created");
        entity.setPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
        if (entity instanceof Mob mob) {
            mob.setNoAi(noAi);
            if (persistent) mob.setPersistenceRequired();
            else ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) mob).tactical$persistenceRequired(false);
        }
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    private static Guardian spawnGuardian(double dx, double dy, double dz) {
        return spawn(EntityType.GUARDIAN, dx, dy, dz, false, false);
    }

    /** The grid cell at that offset from the fixture's own origin. */
    private static GridPos cell(int dx, int dy, int dz) {
        return new GridPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
    }

    private static ElderGuardian spawnElder(double dx, double dy, double dz) {
        return spawn(EntityType.ELDER_GUARDIAN, dx, dy, dz, false, false);
    }

    private static void purgeAll() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        battle = null;
        endSecondEncounter();
        for (Entity entity : List.copyOf(spawned)) {
            if (entity instanceof Guardian guardian) {
                ((TacticalGuardianAccess) guardian).tactical$setMotionHandler(null);
            }
            entity.discard();
        }
        spawned.clear();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        // A chamber an earlier run left in this saved world would silently change what the ceiling measures.
        clearChamber(0, -12, 40, 8, 9, 8);
        resetArena();
    }

    private static void purgeForeignMonsters() {
        for (Entity entity : level.getEntitiesOfClass(Mob.class,
                new net.minecraft.world.phys.AABB(origin).inflate(64),
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy && !spawned.contains(mob))) {
            entity.discard();
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            String detail = wire == null || wire.log.isEmpty() ? "" : " (journal: " + wire.log + ")";
            Tactical.LOGGER.error("[Tactical] GUARDIAN [{}] FAIL {}{}", checks + 1, label, detail);
            throw new AssertionError(label + detail);
        }
        checks++;
        Tactical.LOGGER.info("[Tactical] GUARDIAN [{}] PASS {}", checks, label);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        for (Entity entity : spawned) {
            if (entity instanceof Guardian guardian) {
                ((TacticalGuardianAccess) guardian).tactical$setMotionHandler(null);
            }
            entity.discard();
        }
        spawned.clear();
        if (owner != null && !owner.isRemoved()) {
            level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        BattleEffects.clear();
        server.halt(false);
    }

    private static double attribute(LivingEntity entity, net.minecraft.core.Holder<Attribute> attribute) {
        var instance = entity.getAttribute(attribute);
        return instance == null ? 0.0D : instance.getValue();
    }

    /** Every non-empty collision shape inside that box, for a failure message. */
    private static String solidInside(net.minecraft.world.phys.AABB box) {
        StringBuilder found = new StringBuilder();
        for (BlockPos pos : BlockPos.betweenClosed(BodyGeometry.floor(box.minX), BodyGeometry.floor(box.minY),
                BodyGeometry.floor(box.minZ), BodyGeometry.floor(box.maxX), BodyGeometry.floor(box.maxY),
                BodyGeometry.floor(box.maxZ))) {
            var state = level.getBlockState(pos);
            if (!state.getCollisionShape(level, pos).isEmpty()) {
                found.append(' ').append(pos.toShortString()).append('=').append(state.getBlock().toString());
            }
        }
        return found.length() == 0 ? "none" : found.toString();
    }

    /**
     * Which of the water body's own gates refuses that position, for a failure message.
     *
     * <p>A fixture failure that only says "the position was refused" cannot tell a real rule from a fixture
     * accident, so the message names the gate that answered no.</p>
     */
    private static String whyNotLegal(LivingEntity body, GridPos cell, Vec3 offset) {
        GuardianMovement.Node node = new GuardianMovement.Node(cell,
                com.tactical.neoforge.battle.AerialMovement.feet(cell, offset));
        var box = BodyGeometry.bodyAt(body, node.feet());
        StringBuilder why = new StringBuilder(" [cell=").append(cell).append(" feet=").append(node.feet())
                .append(" box=").append(box).append(" sameCell=").append(GuardianMovement.cellOf(node.feet()).equals(cell))
                .append(" loaded=").append(BodyGeometry.loaded(level, box))
                .append(" noCollision=").append(box != null && level.noCollision(body, box))
                .append(" solid=").append(box == null ? "-" : solidInside(box))
                .append(" bodies=").append(box == null ? "-" : AmphibiousMovement.overlapsBody(level, body, box))
                .append(" hazard=").append(box != null && BodyGeometry.hazardous(level, box))
                .append(" waterOnly=").append(GuardianMovement.waterOnly(level, box))
                .append(" feetWater=").append(GuardianMovement.isWater(level, BlockPos.containing(node.feet())))
                .append(" eyeWater=").append(GuardianMovement.isWater(level,
                        BlockPos.containing(GuardianMovement.eye(body, node.feet()))))
                .append("]");
        return why.toString();
    }

    /**
     * A second, independent encounter far from the measuring arena, with its own real player and hostile.
     *
     * <p>It exists to prove that this family's admission never reaches into somebody else's battlefield: a body
     * already owned by that encounter cannot be taken by this one, and neither roster, pool nor battlefield moves.
     * </p>
     */
    private static ServerBattle secondEncounter() {
        return secondEncounter(0, 64);
    }

    /** The same, at an explicit offset, so a case can place its battlefield where the geometry needs it. */
    private static ServerBattle secondEncounter(int dx, int dz) {
        if (serverRef == null) return null;
        BlockPos base = origin.offset(dx, 0, dz);
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 4; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-guardian-second".getBytes(StandardCharsets.UTF_8)), "GuardianSecond");
        ServerPlayer other = new ServerPlayer(serverRef, level, profile, ClientInformation.createDefault());
        Wire otherWire = new Wire();
        otherWire.channel = new io.netty.channel.embedded.EmbeddedChannel(otherWire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer")) {
            net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(otherWire)
                    .add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(serverRef, otherWire, other,
                CommonListenerCookie.createInitial(profile, false));
        other.setPos(base.getX() + 2.5D, base.getY(), base.getZ() + 0.5D);
        other.setInvulnerable(true);
        level.addNewPlayer(other);
        spawned.add(other);
        secondOwner = other;
        Zombie bait = EntityType.ZOMBIE.create(level);
        check(bait != null, "the second encounter's bait really exists");
        bait.setPos(base.getX() + 0.5D, base.getY(), base.getZ() + 2.5D);
        level.addFreshEntity(bait);
        spawned.add(bait);
        bait.setTarget(other);
        if (!BattleManager.start(other, true)) return null;
        return BattleManager.findFor(other.getUUID());
    }

    private static void endSecondEncounter() {
        if (secondBattle != null && !secondBattle.finished()) {
            secondBattle.finish("fixture second encounter done");
        }
        secondBattle = null;
    }

    // ------------------------------------------------------------------ S1: types and bodies

    private static void exactTypesAndRegistry() {
        purgeForeignMonsters();
        Guardian guardian = spawnGuardian(-4, -2, 0);
        ElderGuardian elder = spawnElder(-6, -3, 0);
        check(GuardianMovement.supports(guardian), "the exact guardian type is supported");
        check(GuardianMovement.supports(elder), "the exact elder guardian type is supported");
        check(!GuardianMovement.elder(guardian) && GuardianMovement.elder(elder),
                "only the elder guardian is the elder body");
        Drowned drowned = spawn(EntityType.DROWNED, 4, 0, 4, false, false);
        Zombie zombie = spawn(EntityType.ZOMBIE, 6, 0, 4, false, false);
        check(!GuardianMovement.supports(drowned) && !GuardianMovement.supports(zombie),
                "no other water creature or monster is supported by this family");
        check(AmphibiousMovement.supports(drowned),
                "and the drowned keeps its own water policy instead of being widened into this one");
        check(!AerialMovement.supports(guardian) && !AerialMovement.supports(elder),
                "nor is either guardian turned into a flying body");
        check(TacticalAiRegistry.create(guardian) instanceof GuardianTacticalAi,
                "the registry gives the guardian its own adapter");
        check(TacticalAiRegistry.create(elder) instanceof GuardianTacticalAi,
                "and the elder guardian the same real adapter, one body at a time");
        check(TacticalAiRegistry.create(drowned) instanceof DrownedTacticalAi,
                "the drowned keeps its trident adapter");
        check(TacticalAiRegistry.create(zombie) instanceof GenericMeleeAi,
                "and an unregistered monster keeps the generic melee");
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() == null,
                "a wild guardian carries no motion seam");
        check(!GuardianMotion.controls(guardian), "so its own travel is never taken from it");
        check(!GuardianMotion.controls(elder), "nor from a wild elder guardian");
    }

    private static void nativeBodiesAndAttributes() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        ElderGuardian elder = spawnElder(-2.5D, -3, 0.5D);
        check(Math.abs(guardian.getBbWidth() - 0.85D) < 1.0E-6D
                && Math.abs(guardian.getBbHeight() - 0.85D) < 1.0E-6D,
                "the ordinary guardian keeps its native 0.85 block body: " + guardian.getBbWidth());
        check(Math.abs(elder.getBbWidth() - 1.9975D) < 1.0E-6D
                && Math.abs(elder.getBbHeight() - 1.9975D) < 1.0E-6D,
                "the elder guardian keeps its native 1.9975 block body: " + elder.getBbWidth());
        check(Math.abs(guardian.getEyeHeight() - 0.425D) < 1.0E-6D,
                "the ordinary guardian keeps its native eye height: " + guardian.getEyeHeight());
        check(Math.abs(elder.getEyeHeight() - 0.99875D) < 1.0E-6D,
                "the elder guardian keeps its native eye height: " + elder.getEyeHeight());
        check(attribute(guardian, Attributes.MAX_HEALTH) == 30.0D, "the ordinary guardian keeps 30 health");
        check(attribute(elder, Attributes.MAX_HEALTH) == 80.0D, "the elder guardian keeps 80 health");
        check(Math.abs(attribute(guardian, Attributes.MOVEMENT_SPEED) - 0.5D) < 1.0E-6D,
                "the ordinary guardian keeps its native swim speed");
        check(Math.abs(attribute(elder, Attributes.MOVEMENT_SPEED) - 0.3D) < 1.0E-6D,
                "the elder guardian keeps its own slower swim speed");
        check(attribute(guardian, Attributes.ATTACK_DAMAGE) == 6.0D
                && attribute(elder, Attributes.ATTACK_DAMAGE) == 8.0D,
                "and neither native attack value is rewritten");
        check(Math.abs(attribute(guardian, Attributes.FOLLOW_RANGE) - 16.0D) < 1.0E-6D,
                "the ordinary guardian keeps its native follow range");
        check(guardian.getAttackDuration() == 80, "the ordinary guardian keeps its 80 tick native charge");
        check(elder.getAttackDuration() == 60, "the elder guardian keeps its 60 tick native charge");
        double width = guardian.getBbWidth();
        double eye = guardian.getEyeHeight();
        float health = guardian.getHealth();
        float maxHealth = guardian.getMaxHealth();
        Vec3 position = guardian.position();
        check(BattleManager.start(owner, true), "a real encounter starts around both water bodies");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the ordinary guardian is admitted on a real water node");
        check(battle.fixtureEnemy(elder.getUUID()) == null || battle.fixtureEnemy(elder.getUUID()).guardian() != null,
                "the elder is either admitted on a real water node or refused, never half-placed");
        battle.finish("fixture body measurement done");
        battle = null;
        check(Math.abs(guardian.getBbWidth() - width) < 1.0E-6D, "admission and a clean exit never resize the body");
        check(Math.abs(guardian.getEyeHeight() - eye) < 1.0E-6D, "nor its eye height");
        check(guardian.getHealth() == health && guardian.getMaxHealth() == maxHealth,
                "nor heal it or rewrite its health cap");
        check(guardian.position().distanceToSqr(position) < 1.0E-6D,
                "nor leave it somewhere it never swam to: " + guardian.position());
    }

    private static void borrowedFlagsReturnOnExit() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        check(!guardian.isNoAi() && !guardian.isPersistenceRequired(),
                "the body starts with its own AI and its own persistence flag");
        Vec3 start = guardian.position();
        check(BattleManager.start(owner, true), "the encounter starts with the body");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(guardian.getUUID()) != null, "the body really joined the roster");
        check(guardian.isNoAi(), "its AI is borrowed for the encounter");
        check(guardian.isPersistenceRequired(), "and its persistence too");
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() != null,
                "and the one motion seam this encounter owns is installed");
        check(GuardianMotion.controls(guardian), "which answers that this encounter holds the body");
        battle.finish("fixture flags done");
        battle = null;
        check(!guardian.isNoAi(), "a normal exit gives the original no-AI flag back");
        check(!guardian.isPersistenceRequired(), "and the original persistence flag");
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() == null,
                "and drops the motion seam, so a released body is a plain vanilla guardian");
        check(!guardian.isMoving(), "with its native swimming bit cleared");
        check(guardian.position().distanceToSqr(start) < 1.0E-6D,
                "and its real position kept: " + guardian.position());
        check(guardian.getHealth() == guardian.getMaxHealth(), "and its real health kept");
    }

    private static void failedAdmissionRollsBack() {
        purgeAll();
        buildPit();
        Zombie keeper = spawn(EntityType.ZOMBIE, 3.5D, 0, 5.5D, false, false);
        keeper.setTarget(owner);
        check(BattleManager.start(owner, true), "an encounter starts with the keeper");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(keeper.getUUID()) != null, "the keeper is on the roster");
        // The body under test joins the world only after the encounter exists, so the admission it is about to be
        // refused really is the only transaction that touches it.
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) guardian).tactical$persistenceRequired(false);
        check(!guardian.isPersistenceRequired(),
                "the body under test really carries its own false persistence flag");
        guardian.setNoAi(false);
        Vec3 position = guardian.position();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        check(!battle.tryAbsorb(guardian), "an admission that throws is refused, not half-committed");
        check(battle.fixtureEnemy(guardian.getUUID()) == null, "and the body really is not on the roster");
        check(!guardian.isNoAi(), "the borrowed AI flag was given back");
        check(!guardian.isPersistenceRequired(), "and the borrowed persistence flag with it");
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() == null,
                "no motion seam is left on a body the encounter refused");
        check(guardian.position().distanceToSqr(position) < 1.0E-6D, "and the body is where it was");
        check(ServerBattle.pendingFaults() == 0, "the injected fault really fired");
        check(BattleManager.ownerOf(guardian.getUUID()) == null, "and the ownership claim was released again");
    }

    private static void otherCreaturesUnchanged() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        Drowned drowned = spawn(EntityType.DROWNED, -6.5D, -3, 0.5D, true, true);
        Zombie zombie = spawn(EntityType.ZOMBIE, 4.5D, 0, 6.5D, true, true);
        check(!GuardianMovement.controllable(drowned), "a drowned is never controlled by this family");
        check(!GuardianMotion.controls(zombie), "nor is a zombie");
        check(BattleManager.start(owner, true), "an encounter with a guardian starts beside them");
        battle = BattleManager.current();
        check(GuardianMotion.controls(guardian), "the guardian really is held by this encounter");
        check(!GuardianMotion.controls(drowned), "and the drowned beside it is not");
        check(!GuardianMotion.controls(zombie), "nor the zombie on the platform");
        check(drowned.isNoAi() && zombie.isNoAi(), "and both keep the flags they were given, untouched");
        battle.finish("fixture other creatures done");
        battle = null;
    }

    private static UUID heldGuardianId;
    private static double idleHeldY;

    private static void outsideBattleKeepsNativeTravel() {
        purgeAll();
        buildPit();
        // Both bodies are put in the upper water layer, unsupported: a body whose own travel runs really sinks.
        Guardian wild = spawnGuardian(-4.5D, -2, 0.5D);
        check(!GuardianMotion.controls(wild), "a wild guardian in real water is not held");
        double wildStart = wild.getY();
        await(12, () -> {
            check(Math.abs(wild.getY() - wildStart) > 1.0E-4D,
                    "a wild guardian really moves under its own travel: " + wildStart + " -> " + wild.getY());
            wild.discard();
            spawned.remove(wild);
            Guardian held = spawnGuardian(0.5D, -2, 0.5D);
            heldGuardianId = held.getUUID();
            check(BattleManager.start(owner, true), "an encounter takes the second body");
            battle = BattleManager.current();
            ServerBattle.EnemyView view = battle.fixtureEnemy(held.getUUID());
            check(view != null && view.guardian() != null, "which is admitted on a real water node");
            check(GuardianMotion.controls(held), "and is really held by the encounter");
            idleHeldY = held.getY();
            await(12, GuardianSelfTest::verifyHeldBodyIsNotIntegrated);
        });
    }

    private static void verifyHeldBodyIsNotIntegrated() {
        Guardian held = battle == null ? null : (Guardian) level.getEntity(heldGuardianId);
        check(held != null, "the held body is still in the world");
        check(Math.abs(held.getY() - idleHeldY) < 1.0E-4D,
                "a held guardian is never integrated a second time between its own turns: " + idleHeldY + " -> "
                        + held.getY());
        check(!held.isMoving(), "and its native swimming bit says it is standing still");
        check(held.isNoAi(), "while the encounter still owns its AI lock");
        check(battle.fixtureEnemy(heldGuardianId) != null, "and it is still on the roster");
        battle.finish("fixture idle body done");
        battle = null;
        purgeAll();
    }

    // ------------------------------------------------------------------ S2: water geometry

    private static void narrowWaterTunnel() {
        purgeAll();
        buildTunnel(8, -6, 6);
        Guardian guardian = spawnGuardian(0.5D, 0, 8.5D);
        ElderGuardian elder = spawnElder(-4.5D, 0, 8.5D);
        GridPos tunnelCell = cell(0, 0, 8);
        check(GuardianMovement.waterNode(level, guardian, tunnelCell, Vec3.ZERO).isPresent(),
                "the 0.85 block guardian really fits the one-block water tunnel at " + tunnelCell);
        check(GuardianMovement.waterNode(level, elder, tunnelCell, Vec3.ZERO).isEmpty(),
                "the 1.9975 block elder guardian does not fit the very same tunnel");
        check(elder.getBbWidth() > 1.0D, "the elder's real body is wider than that tunnel");
        buildPit();
        check(GuardianMovement.waterNode(level, elder, cell(3, -3, 0), Vec3.ZERO).isPresent(),
                "while the elder really does hold wide water, so the refusal above was about the body");
    }

    private static void fractionalAdmissionKeepsRealFeet() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.35D, -2.4D, 0.35D);
        Vec3 start = guardian.position();
        check(Math.abs(start.x - Math.floor(start.x) - 0.5D) > 1.0E-3D
                        || Math.abs(start.z - Math.floor(start.z) - 0.5D) > 1.0E-3D,
                "the body under test really holds a position off the cell centre: " + start);
        check(BattleManager.start(owner, true), "the encounter takes a fractional body");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "which holds a real water node");
        check(view.guardian().feet().distanceToSqr(start) < 1.0E-6D,
                "and the node is the position the body really has, not a cell centre: " + view.guardian().feet());
        check(view.guardian().cell().equals(GuardianMovement.cellOf(start)),
                "with the cell derived from those real feet: " + view.guardian().cell());
        check(view.cell().equals(view.guardian().cell()), "and the roster cell agrees with it");
        await(8, () -> {
            ServerBattle.EnemyView now = battle.fixtureEnemy(heldGuardianIdOrGuardian(guardian));
            check(now != null && now.guardian() != null && now.guardian().feet().distanceToSqr(start) < 1.0E-6D,
                    "and the idle body keeps exactly those feet instead of drifting: "
                            + (now == null ? "gone" : now.feet()));
            battle.finish("fixture fractional admission done");
            battle = null;
            purgeAll();
        });
    }

    private static UUID heldGuardianIdOrGuardian(Guardian guardian) {
        heldGuardianId = guardian.getUUID();
        return heldGuardianId;
    }

    private static void threeDimensionalWaterEdges() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(0.5D, -3, 0.5D);
        // The pit is two water layers deep: a purely horizontal rule could never describe that.
        GuardianMovement.Node low = GuardianMovement.waterNode(level, guardian, cell(0, -3, 0), Vec3.ZERO)
                .orElseThrow();
        GuardianMovement.Node high = GuardianMovement.waterNode(level, guardian, cell(0, -2, 0), Vec3.ZERO)
                .orElseThrow();
        GuardianMovement.Node highSide = GuardianMovement.waterNode(level, guardian, cell(1, -2, 1), Vec3.ZERO)
                .orElseThrow();
        check(GuardianMovement.adjacent(low.cell(), high.cell()), "a vertical water edge is a real neighbour");
        check(GuardianMovement.canStep(level, guardian, low, high, c -> true),
                "and the body really can swim straight up through the water");
        check(GuardianMovement.canStep(level, guardian, high, highSide, c -> true),
                "as well as up-and-across, because the sweep is asked of the real body");
        check(!GuardianMovement.adjacent(low.cell(), cell(6, -3, 6)), "a far cell is never one edge");
        GuardianMovement.Node far = GuardianMovement.waterNode(level, guardian, cell(3, -3, 3), Vec3.ZERO)
                .orElseThrow();
        check(!GuardianMovement.canStep(level, guardian, low, far, c -> true),
                "and a body cannot cross several cells in one edge, even through open water");
        // A solid block in the way refuses the vertical edge instead of tunnelling through it.
        level.setBlock(origin.offset(0, -2, 0), Blocks.STONE.defaultBlockState(), 2);
        check(GuardianMovement.canStep(level, guardian, low, high, c -> true) == false,
                "a block placed into the water column refuses the vertical edge");
    }

    /**
     * S2/A-R2: one edge is only legal while the whole leg stays in water - feet and real eye alike.
     *
     * <p>The negative case is the one an endpoint-only rule cannot see: both ends are legal water bodies, the swept
     * body has no block, no hazard and only water for liquid, and yet the corner between them is air, so the body
     * would swim through air for part of the animation. The positive case is an ordinary continuous pool, which a
     * conservative rule must still allow.</p>
     */
    private static void oneLegStaysUnderWaterThroughout() {
        purgeAll();
        // Two water cells on a diagonal, and the corner between them is air.
        level.setBlock(origin.offset(0, -3, 0), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(origin.offset(1, -2, 0), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(origin.offset(1, -3, 0), Blocks.AIR.defaultBlockState(), 2);
        Guardian guardian = spawnGuardian(0.9D, -3, 0.5D);
        GuardianMovement.Node low = GuardianMovement.waterNode(level, guardian, cell(0, -3, 0),
                new Vec3(0.4D, 0.1D, 0)).orElseThrow();
        GuardianMovement.Node high = GuardianMovement.waterNode(level, guardian, cell(1, -2, 0),
                new Vec3(0.4D, 0.1D, 0)).orElseThrow();
        check(GuardianMovement.legal(level, guardian, low), "the first end is a legal water body");
        check(GuardianMovement.legal(level, guardian, high), "and so is the second end, diagonally above it");
        check(GuardianMovement.sweepClear(level, guardian, low.feet(), high.feet(), c -> true),
                "the swept body itself has no block, no hazard and no other or non-water liquid");
        check(!GuardianMovement.waterThroughout(level, guardian, low.feet(), high.feet()),
                "but the corner between the two ends is air, so the medium does not hold for the whole leg");
        check(!GuardianMovement.canStep(level, guardian, low, high, c -> true),
                "and the edge is therefore refused, instead of being swum through air");
        // The same shape inside a real continuous pool must be allowed: every block the diagonal leg can enter - the
        // feet's and the real eye's - is water now, so the conservative rule has nothing to refuse.
        level.setBlock(origin.offset(1, -3, 0), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(origin.offset(0, -2, 0), Blocks.WATER.defaultBlockState(), 2);
        check(GuardianMovement.waterThroughout(level, guardian, low.feet(), high.feet()),
                "filling the corner makes the whole leg water");
        check(GuardianMovement.canStep(level, guardian, low, high, c -> true),
                "and the very same edge is now a legal swim, so the rule is about the medium and not about shape");
        purgeAll();
    }

    private static void boundedSearchHonoursItsCeiling() {
        purgeAll();
        // 8 x 9 x 8 = 576 legal positions: more than the 512 discovered-state ceiling, so the bound really bites,
        // while the far corner is genuinely reachable - the hand-written walk below proves that.
        buildChamber(0, -12, 40, 8, 9, 8);
        Guardian guardian = spawnGuardian(0.5D, -12, 40.5D);
        GuardianMovement.Node start = GuardianMovement.waterNode(level, guardian, cell(0, -12, 40), Vec3.ZERO)
                .orElseThrow();
        Predicate<GridPos> none = c -> false;
        Predicate<GridPos> anywhere = c -> true;
        GridPos nearGoal = cell(5, -7, 45);
        GridPos farGoal = cell(7, -4, 47);
        check(GuardianMovement.MAX_SEARCH_NODES == 512, "the ceiling is the frozen 512 discovered states");
        // A breadth-first walk written here, with no ceiling at all, over the production neighbour rule: it proves
        // the chamber really holds both goals and that nothing outside leaked in.
        java.util.Set<GridPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<GuardianMovement.Node> open = new java.util.ArrayDeque<>();
        seen.add(start.cell());
        open.add(start);
        int discovered = 1;
        while (!open.isEmpty() && discovered < 4096) {
            GuardianMovement.Node current = open.poll();
            for (GuardianMovement.Node next : GuardianMovement.neighbours(level, guardian, current, farGoal,
                    Vec3.ZERO, none, anywhere)) {
                if (!seen.add(next.cell())) continue;
                discovered++;
                open.add(next);
            }
        }
        check(discovered == 576 && seen.contains(nearGoal) && seen.contains(farGoal),
                "the chamber is a sealed pocket of its own 576 positions and both goals really exist in it: ["
                        + discovered + " " + seen.contains(nearGoal) + " " + seen.contains(farGoal) + "]");
        // Inside the ceiling the goal is really reached.
        java.util.function.ToIntFunction<GuardianMovement.Node> toNear =
                node -> Math.abs(node.cell().x() - nearGoal.x()) + Math.abs(node.cell().y() - nearGoal.y())
                        + Math.abs(node.cell().z() - nearGoal.z());
        GuardianMovement.Route nearRoute = GuardianMovement.swimTo(level, guardian, start, nearGoal, Vec3.ZERO,
                600, none, anywhere, node -> node.cell().equals(nearGoal), toNear).orElse(null);
        check(nearRoute != null, "a goal inside the search ceiling is really reached");
        check(nearRoute.end().cell().equals(nearGoal),
                "and the route ends on it: " + nearRoute.end().cell());
        check(nearRoute.nodes().size() == 5, "with exactly the five diagonal edges it took: "
                + nearRoute.nodes().size());
        // Beyond the ceiling the same search must answer with a legal prefix, never with the goal.
        java.util.function.ToIntFunction<GuardianMovement.Node> toFar =
                node -> Math.abs(node.cell().x() - farGoal.x()) + Math.abs(node.cell().y() - farGoal.y())
                        + Math.abs(node.cell().z() - farGoal.z());
        GuardianMovement.Route farRoute = GuardianMovement.swimTo(level, guardian, start, farGoal, Vec3.ZERO,
                600, none, anywhere, node -> node.cell().equals(farGoal), toFar).orElse(null);
        check(farRoute != null, "a goal beyond the ceiling still answers with a legal prefix");
        check(!farRoute.end().cell().equals(farGoal), "but never pretends to reach it: " + farRoute.end().cell());
        check(toFar.applyAsInt(farRoute.end()) < toFar.applyAsInt(start),
                "and the prefix really improves on standing still: " + toFar.applyAsInt(start) + " -> "
                        + toFar.applyAsInt(farRoute.end()));
        check(farRoute.nodes().size() <= GuardianMovement.MAX_SEARCH_NODES,
                "inside the same ceiling, so the scan never becomes unbounded: " + farRoute.nodes().size());
        // A score that cannot be improved is not progress: the same search refuses to answer at all.
        GuardianMovement.Route nowhere = GuardianMovement.swimTo(level, guardian, start, farGoal, Vec3.ZERO,
                600, none, anywhere, node -> false, node -> 7).orElse(null);
        check(nowhere == null, "and a bounded search that cannot improve its score answers with no route at all");
        purgeAll();
    }

    private static void bodyZoneOccupancyHazardAndMedium() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(0.5D, -3, 0.5D);
        GuardianMovement.Node open = GuardianMovement.waterNode(level, guardian, cell(0, -3, 0), Vec3.ZERO)
                .orElseThrow();
        check(GuardianMovement.legal(level, guardian, open), "an open water cell of the pit really holds");
        // The whole body, not the feet cell, has to be inside the battlefield.
        Predicate<GridPos> ownColumnOnly = g -> g.x() == open.cell().x() && g.z() == open.cell().z();
        check(GuardianMovement.bodyInsideZone(guardian, open, ownColumnOnly),
                "a 0.85 block body centred on its cell really fits one column");
        GuardianMovement.Node shifted = GuardianMovement
                .waterNode(level, guardian, open.cell(), new Vec3(0.49D, 0, 0)).orElseThrow();
        check(!GuardianMovement.bodyInsideZone(guardian, shifted, ownColumnOnly),
                "but the same body, off the cell centre, really reaches into the next column");
        check(GuardianMovement.bodyInsideZone(guardian, shifted, g -> true),
                "while a battlefield that covers every column it touches really holds it");
        check(GuardianMovement.bodyInsideZone(guardian, open, g -> g.z() != open.cell().z()) == false,
                "and a battlefield that omits a column the body touches refuses it");
        // Real occupant.
        owner.setPos(origin.getX() + 0.5D, origin.getY() - 3, origin.getZ() + 0.5D);
        check(GuardianMovement.waterNode(level, guardian, open.cell(), Vec3.ZERO).isEmpty(),
                "a position another unit really occupies is refused");
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        // Hazard inside the body. A 0.85 block body at integer feet occupies exactly its feet block, so the hazard
        // has to be inside that block - which is also the proof that the rule is asked of the body, not of a guess.
        level.setBlock(origin.offset(0, -3, 0), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        check(GuardianMovement.waterNode(level, guardian, open.cell(), Vec3.ZERO).isEmpty(),
                "a hazardous block inside the body refuses the position");
        level.setBlock(origin.offset(0, -3, 0), Blocks.WATER.defaultBlockState(), 2);
        // A liquid that is not water.
        level.setBlock(origin.offset(0, -3, 0), Blocks.LAVA.defaultBlockState(), 2);
        check(GuardianMovement.waterNode(level, guardian, open.cell(), Vec3.ZERO).isEmpty(),
                "lava is not the medium this body may hold");
        level.setBlock(origin.offset(0, -3, 0), Blocks.WATER.defaultBlockState(), 2);
        // Dry: taking the water away from the cell the body holds refuses it outright.
        level.setBlock(origin.offset(0, -3, 0), Blocks.AIR.defaultBlockState(), 2);
        check(GuardianMovement.waterNode(level, guardian, open.cell(), Vec3.ZERO).isEmpty(),
                "a drained feet cell is refused instead of being treated as swimmable");
        level.setBlock(origin.offset(0, -3, 0), Blocks.WATER.defaultBlockState(), 2);
        // Dry only where the real eye is. An elder guardian's eye is 0.99875 blocks up, so it needs a real
        // fractional foot height for its eye to leave the feet block at all - which is exactly the case a
        // cell-centre rule would silently answer wrong.
        ElderGuardian tall = spawnElder(3.5D, -1.5D, 0.5D);
        GridPos tallCell = cell(3, -2, 0);
        Vec3 tallOffset = new Vec3(0, 0.5D, 0);
        level.setBlock(origin.offset(3, -1, 0), Blocks.WATER.defaultBlockState(), 2);
        check(GuardianMovement.waterNode(level, tall, tallCell, tallOffset).isPresent(),
                "the elder really holds that column, with its real eye submerged in the block above its feet"
                        + whyNotLegal(tall, tallCell, tallOffset));
        level.setBlock(origin.offset(3, -1, 0), Blocks.AIR.defaultBlockState(), 2);
        check(GuardianMovement.waterNode(level, tall, tallCell, tallOffset).isEmpty(),
                "and taking the water away only where its real eye is refuses that position too");
        level.setBlock(origin.offset(3, -1, 0), Blocks.WATER.defaultBlockState(), 2);
    }

    private static void unloadedChunkRefusedWithoutLoading() {
        purgeAll();
        Guardian guardian = spawnGuardian(0.5D, -3, 0.5D);
        GridPos far = new GridPos(origin.getX() + 6000, 60, origin.getZ() + 6000);
        int chunkX = far.x() >> 4;
        int chunkZ = far.z() >> 4;
        check(!level.hasChunk(chunkX, chunkZ), "the far cell really starts unloaded");
        check(GuardianMovement.waterNode(level, guardian, far, Vec3.ZERO).isEmpty(),
                "a cell whose chunk is not loaded is refused");
        check(!level.hasChunk(chunkX, chunkZ), "and nothing was force-loaded to answer the question");
        check(!GuardianMovement.bodyLoaded(level, guardian, new Vec3(far.x() + 0.5D, far.y(), far.z() + 0.5D)),
                "the load gate answers before any block is read");
    }

    // ------------------------------------------------------------------ S2/S3: real routes through the encounter

    /** How many times this fixture has handed the player's own turn on while waiting for a specific unit. */
    private static int handOvers;

    /**
     * Hand the player's own turn on, and say whether the unit under test has already had one.
     *
     * <p>The shared queue is an ATB charge order, so "the player ended its turn" does not mean the unit under test
     * acts next; it may need several player turns to come round. The fixture therefore keeps handing the turn over
     * until that unit's own turn really happened, and never assumes an order.</p>
     */
    private static boolean awaitOwnTurn(UUID id) {
        if (battle == null || battle.finished()) return false;
        if (battle.fixtureOwnTurns(id) >= 1) return true;
        if (handOvers > 400) return false;
        if (battle.isPlayerTurnNow()) {
            handOvers++;
            battle.endPlayerTurn();
        }
        return false;
    }

    private static boolean routeSeen;
    /** Whether every sampled frame of that route really had no paid action. */
    private static boolean routePaidZero = true;
    private static int routeEdges;
    private static int routePrice;
    private static int routePerEdge;
    private static GridPos routeStartCell;
    private static Vec3 routeStartFeet;
    private static GridPos routeEndCell;
    private static Vec3 routeEndFeet;
    private static int ownerApMark;
    /**
     * The lowest pool the other side ever held while this case ran.
     *
     * <p>The player's own pool is refilled at its own turn start, so a final reading cannot tell a legitimate
     * refill from a charge. The minimum can: a pool that never dipped below what it had before somebody else's
     * turn was never charged for that movement.</p>
     */
    private static int ownerApMin;

    private static void swimsToARealFiringPosition() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the encounter starts with a body under the roof");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the body holds a real water node under the roof");
        check(!battle.fixtureGuardianFires(guardian.getUUID()),
                "and cannot fire from there: the roof really blocks the native line of sight");
        int moveCost = Math.max(1, view.moveCost());
        check(battle.fixtureEnemyPool(guardian.getUUID(), 12 * moveCost + 40),
                "the fixture gives the body room for a route and one beam");
        routeStartCell = view.guardian().cell();
        routeStartFeet = view.guardian().feet();
        routeSeen = false;
        routePaidZero = true;
        handOvers = 0;
        ownerApMark = battle.currentAp(owner);
        ownerApMin = ownerApMark;
        await(0, () -> watchFiringRoute(0));
    }

    /**
     * The production path: the player hands its turn over, the guardian's own turn plans and commits a real route,
     * and the encounter animates it. Nothing here writes a route or a cell.
     */
    private static void watchFiringRoute(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the firing-position route never settled: busy=" + battle.isBusy() + " playerTurn="
                    + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        ownerApMin = Math.min(ownerApMin, battle.currentAp(owner));
        if (view.swimming()) {
            routeSeen = true;
            // Sampled on every real frame of the route: the movement itself never advances the paid counter.
            routePaidZero &= view.paidActions() == 0;
            routeEdges = view.swimEdges();
            routePrice = view.swimPrice();
            routePerEdge = view.moveCost();
            routeEndCell = view.guardian() == null ? null : view.guardian().cell();
            await(0, () -> watchFiringRoute(guard + 1));
            return;
        }
        if (!routeSeen && !awaitOwnTurn(heldGuardianId)) {
            await(0, () -> watchFiringRoute(guard + 1));
            return;
        }
        if (battle.isPlayerTurnNow() && !battle.isBusy()) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(routeSeen, "the guardian really committed and swam a route");
            check(routeEdges >= 2, "the committed route has more than one edge: " + routeEdges);
            check(routePrice == routeEdges * routePerEdge,
                    "the route is quoted per edge and frozen: " + routePrice + " for " + routeEdges + " edges");
            check(settled != null && settled.guardian() != null,
                    "the body holds a real water node after the route");
            check(!settled.guardian().cell().equals(routeStartCell),
                    "and it really moved: " + routeStartCell + " -> " + settled.guardian().cell());
            check(settled.guardian().feet().distanceToSqr(routeStartFeet) > 1.0E-4D,
                    "with real feet, not the take-off point");
            check(battle.fixtureGuardianFires(heldGuardianId),
                    "and the cell it reached can really fire, which the start could not");
            check(!settled.swimming() && !battle.isBusy(), "the encounter is not left busy");
            check(ownerApMin == ownerApMark,
                    "the other side's pool was never charged for this route: " + ownerApMark + " -> " + ownerApMin);
            // From the B batch on the body fires its own native beam from the water it reached, in the same own turn:
            // that beam is the one paid action. The route itself still never counts one, which is why the answer is at
            // most one and never one per edge.
            check(routePaidZero, "a flying route never counts a paid action");
            check(settled.paidActions() == 1,
                    "and only the real beam that followed it counts exactly one: " + settled.paidActions());
            battle.finish("fixture firing position done");
            battle = null;
            purgeAll();
            return;
        }
        if (!routeSeen && awaitOwnTurn(heldGuardianId)) {
            await(0, () -> watchFiringRoute(guard + 1));
            return;
        }
        await(0, () -> watchFiringRoute(guard + 1));
    }

    private static void closeRangeMustBackAway() {
        purgeAll();
        buildPit();
        // The target stands on the island inside the pit; a guardian right beside it is inside vanilla's near gate.
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(7.5D, -2, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the encounter starts with a body too close to fire");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the close body holds a real water node");
        check(!battle.fixtureGuardianFires(guardian.getUUID()),
                "and vanilla's own near gate refuses it from less than three blocks");
        int moveCost = Math.max(1, view.moveCost());
        check(battle.fixtureEnemyPool(guardian.getUUID(), 12 * moveCost + 40),
                "the fixture gives the close body room to back away");
        routeStartCell = view.guardian().cell();
        routeStartFeet = view.guardian().feet();
        routeSeen = false;
        handOvers = 0;
        ownerApMark = battle.currentAp(owner);
        ownerApMin = ownerApMark;
        await(0, () -> watchRetreat(0));
    }

    private static void watchRetreat(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the retreat never settled: busy=" + battle.isBusy() + " playerTurn="
                    + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        ownerApMin = Math.min(ownerApMin, battle.currentAp(owner));
        if (view.swimming()) {
            routeSeen = true;
            routeEdges = view.swimEdges();
            await(0, () -> watchRetreat(guard + 1));
            return;
        }
        if (!routeSeen && !awaitOwnTurn(heldGuardianId)) {
            await(0, () -> watchRetreat(guard + 1));
            return;
        }
        if (battle.isPlayerTurnNow() && !battle.isBusy()) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(routeSeen, "the close body really swam instead of standing still");
            check(settled != null && settled.guardian() != null, "and holds a real water node afterwards");
            double before = Math.sqrt(routeStartFeet.distanceToSqr(owner.position()));
            double after = Math.sqrt(settled.guardian().feet().distanceToSqr(owner.position()));
            check(after > before + 1.0E-4D,
                    "the route really moved the body away from its target: " + before + " -> " + after);
            check(after > 3.0D, "far enough out that vanilla's near gate no longer refuses it: " + after);
            check(battle.fixtureGuardianFires(heldGuardianId),
                    "and from there the native beam really could be fired");
            check(ownerApMin == ownerApMark,
                    "the other side's pool was never charged for this retreat: " + ownerApMark + " -> " + ownerApMin);
            battle.finish("fixture retreat done");
            battle = null;
            purgeAll();
            return;
        }
        await(0, () -> watchRetreat(guard + 1));
    }

    private static void elderHasNoNearGate() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        ElderGuardian elder = spawnElder(7.5D, -2, 0.5D);
        heldGuardianId = elder.getUUID();
        check(BattleManager.start(owner, true), "the encounter starts with the elder too close for its cousin");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(elder.getUUID());
        check(view != null && view.guardian() != null, "the elder holds a real water node");
        check(battle.fixtureGuardianFires(elder.getUUID()),
                "but an elder guardian has no near gate: it can fire from the same distance");
        GridPos before = view.guardian().cell();
        int moveCost = Math.max(1, view.moveCost());
        check(battle.fixtureEnemyPool(elder.getUUID(), 12 * moveCost + 40), "the elder has room for a route");
        handOvers = 0;
        await(0, () -> watchElderStandsItsGround(0, before));
    }

    private static void watchElderStandsItsGround(int guard, GridPos before) {
        if (guard > 200) {
            check(false, "the elder case never settled: busy=" + battle.isBusy() + " playerTurn="
                    + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the elder stayed on the roster");
            return;
        }
        check(!view.swimming(), "the elder never swims away from a firing position it already holds");
        if (battle.fixtureOwnTurns(heldGuardianId) < 1) {
            awaitOwnTurn(heldGuardianId);
            await(0, () -> watchElderStandsItsGround(guard + 1, before));
            return;
        }
        if (battle.isPlayerTurnNow() && !battle.isBusy()) {
            check(view.guardian() != null && view.guardian().cell().equals(before),
                    "and it really stayed where it was: " + before);
            // From the B batch on, an elder that already stands in a legal firing position spends its own turn on its
            // native beam instead of a route: the position itself is the whole point of the elder's missing near gate.
            // Its own beam is exactly one paid action, and the charge window before it is asserted to be zero in S4
            // ("a launched beam charges nothing before its own native hit"), so the two facts are separated in time
            // instead of being flattened into an upper bound.
            check(view.paidActions() == 1,
                    "its own turn spent exactly the one paid action of its beam: " + view.paidActions());
            check(view.ap() == view.maxAp() || view.ap() > 0, "and without spending its pool on a route");
            battle.finish("fixture elder done");
            battle = null;
            purgeAll();
            return;
        }
        await(0, () -> watchElderStandsItsGround(guard + 1, before));
    }

    private static void deploymentAndReinforcementGates() {
        purgeAll();
        // A dry guardian inside the battlefield is never taken: this family has no land position at all.
        Zombie keeper = spawn(EntityType.ZOMBIE, 2.5D, 0, 2.5D, false, false);
        keeper.setTarget(owner);
        Guardian dry = spawnGuardian(4.5D, 0, 4.5D);
        check(BattleManager.start(owner, true), "an encounter starts even though a guardian cannot be placed");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(keeper.getUUID()) != null, "the dry monster behind it is on the roster");
        check(battle.fixtureEnemy(dry.getUUID()) == null, "and the guardian with no water is refused outright");
        check(!dry.isNoAi(), "without even borrowing its AI flag");
        check(((TacticalGuardianAccess) dry).tactical$motionHandler() == null,
                "and without leaving a motion seam on it");
        check(dry.position().distanceToSqr(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 4.5D) < 1.0E-6D,
                "nor is it moved anywhere to make it fit");
        battle.finish("fixture dry deployment done");
        battle = null;
        // A body another encounter already owns is never taken, and neither roster, pool nor battlefield moves.
        purgeAll();
        buildPit();
        secondBattle = secondEncounter();
        check(secondBattle != null && !secondBattle.finished(), "a second encounter really runs");
        Guardian taken = spawnGuardian(-4.5D, -3, 0.5D);
        check(BattleManager.start(owner, true), "this encounter starts too");
        // Two encounters are live now, so this one is resolved by its founder rather than as "the current" one.
        battle = BattleManager.findFor(owner.getUUID());
        check(battle.fixtureEnemy(taken.getUUID()) != null, "the guardian joins this encounter");
        int otherRoster = secondBattle.hostileCount();
        int otherRadius = secondBattle.zone().radius();
        check(!secondBattle.tryAbsorb(taken),
                "and the other encounter refuses to take a body this one already owns");
        check(secondBattle.hostileCount() == otherRoster, "so its roster is unchanged");
        check(secondBattle.zone().radius() == otherRadius, "and its battlefield is unchanged");
        check(!secondBattle.isParticipant(taken), "and the body is still nobody else's participant");
        check(battle.fixtureEnemy(taken.getUUID()) != null, "while this encounter still holds it");
        check(BattleManager.ownerOf(taken.getUUID()) != null, "and the ownership claim is still this one's");
        endSecondEncounter();
        purgeAll();
        // The real full-body boundary: a candidate nobody owns, whose feet cell alone would not reach the other
        // encounter but whose 1.9975 block body really would.
        fullBodyExpansionConflict();
        // The neighbourhood boundary: dry ground beside real water the body could occupy is not a reason to move it.
        dryBodyBesideWaterIsRefused();
    }

    /**
     * C final: a dry body standing on a real dry shore with real legal water beside it is still refused.
     *
     * <p>The neighbourhood really does hold a water position this same body could occupy, and this encounter
     * really does take a wet body placed there - so the dry body's refusal cannot be explained by "there was no
     * water anywhere", which the older no-water case was the only evidence for. The body is never teleported into
     * the pool to make it fit, and every other state it owns stays as it was.</p>
     */
    private static void dryBodyBesideWaterIsRefused() {
        purgeAll();
        buildPit();
        // The shore: the arena floor is stone at y=-1, so this body stands on solid ground two cells from the pit.
        Guardian dry = spawnGuardian(0, 0, 5);
        Vec3 dryAt = dry.position();
        check(!GuardianMovement.bothUnderWater(level, dry, dryAt),
                "the body under test really stands dry: " + dryAt);
        // The water in that neighbourhood, proved usable by the same body at its own point. The depth is the one
        // the existing positive admission case uses, so a boundary artefact of a shallower spawn cannot be
        // mistaken for the refusal under test.
        Guardian wet = spawnGuardian(0, -3, 3);
        Vec3 wetAt = wet.position();
        GridPos wetCell = GuardianMovement.cellOf(wetAt);
        check(GuardianMovement.legal(level, wet, new GuardianMovement.Node(wetCell, wetAt)),
                "and a legal water hold for this body really exists beside it: " + wetCell);
        check(dryAt.distanceToSqr(wetAt) < 16.0D,
                "inside the four blocks the neighbourhood search scans: " + dryAt.distanceToSqr(wetAt));
        check(BattleManager.start(owner, true), "an encounter starts with both bodies present");
        battle = BattleManager.current();
        int roster = battle.hostileCount();
        int queue = battle.turnQueueSize();
        int radius = battle.zone().radius();
        ServerBattle.EnemyView taken = battle.fixtureEnemy(wet.getUUID());
        check(taken != null && taken.guardian() != null
                        && taken.guardian().feet().distanceToSqr(wetAt) < 1.0E-6D,
                "the wet body really joins at that very water point");
        check(roster == 1, "and the roster really holds exactly it: " + roster);
        check(battle.fixtureEnemy(dry.getUUID()) == null,
                "while the dry body two cells away is refused even though water is beside it");
        check(!battle.tryAbsorb(dry), "and the admission entry refuses it as well");
        check(dry.position().distanceToSqr(dryAt) < 1.0E-6D, "so it is never teleported into the pool");
        check(!dry.isNoAi(), "its own AI flag is untouched");
        check(!dry.isPersistenceRequired(), "and so is its own persistence flag, in its original direction");
        check(dry.getTarget() == null, "its own target is untouched");
        check(((TacticalGuardianAccess) dry).tactical$motionHandler() == null, "no motion seam was left on it");
        check(BattleManager.ownerOf(dry.getUUID()) == null, "nobody claims it");
        check(!battle.isParticipant(dry), "and it is on no roster");
        check(battle.fixtureEnemy(dry.getUUID()) == null, "with no roster entry behind it");
        check(battle.hostileCount() == roster && battle.turnQueueSize() == queue
                        && battle.zone().radius() == radius,
                "roster, queue and battlefield are unchanged: " + battle.hostileCount() + "/"
                        + battle.turnQueueSize() + "/" + battle.zone().radius());
        battle.finish("fixture dry shore done");
        battle = null;
    }

    /**
     * S2: the encounter's own grown battlefield is what the neighbouring battle must refuse, and it is the whole
     * real body that grows it.
     *
     * <p>The candidate stands one cell outside the other encounter's volume, and its grown battlefield would reach
     * only its own feet cell there - that alone does not conflict. A 1.9975 block elder guardian however reaches a
     * whole cell further, so the battlefield this encounter would really have to grant it does conflict, and the
     * admission has to be refused. Everything is read back: the candidate's own flags, position and claim, both
     * rosters and queues, both pools and both battlefields.</p>
     */
    private static void fullBodyExpansionConflict() {
        purgeAll();
        // The measuring water sits on this encounter's own sphere: its feet cell is inside, and the body reaches out.
        for (int x = 23; x <= 26; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlock(origin.offset(x, -4, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = -3; y <= -1; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.WATER.defaultBlockState(), 2);
                }
                for (int y = 0; y <= 2; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        // The other encounter is built so that its own volume starts exactly beyond that extra cell.
        secondBattle = secondEncounter(47, 49);
        check(secondBattle != null && !secondBattle.finished(), "the neighbouring encounter really runs");
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        Zombie bait = spawn(EntityType.ZOMBIE, 2.5D, 0, 2.5D, false, false);
        bait.setTarget(owner);
        check(BattleManager.start(owner, true), "this encounter starts with its bait");
        battle = BattleManager.findFor(owner.getUUID());
        check(battle != null, "and this encounter is resolved by its founder, with two live");
        int mineBefore = battle.hostileCount();
        int mineRadiusBefore = battle.zone().radius();
        int theirHostileBefore = secondBattle.hostileCount();
        int theirQueueBefore = secondBattle.turnQueueSize();
        int theirRadiusBefore = secondBattle.zone().radius();
        GridPos theirCenter = secondBattle.zone().center();
        // A candidate nobody claims, on real water, with its whole body loaded.
        ElderGuardian elder = spawnElder(24.5D, -1, 0.5D);
        Vec3 before = elder.position();
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) elder).tactical$persistenceRequired(true);
        check(BattleManager.ownerOf(elder.getUUID()) == null, "the candidate is owned by nobody");
        check(!battle.isParticipant(elder) && !secondBattle.isParticipant(elder),
                "and is on neither roster");
        GridPos feet = GuardianMovement.cellOf(before);
        // A battlefield is compared by the box it really spans, so both statements below are made of zone boxes:
        // the one the feet cell alone would grant, and the one the real 1.9975 block body really needs.
        com.tactical.core.zone.BattleZone.Bounds theirs = secondBattle.zone().bounds();
        com.tactical.core.zone.BattleZone.Bounds feetOnly = battle.zone().including(feet).bounds();
        com.tactical.core.zone.BattleZone grown = battle.zone();
        var body = BodyGeometry.bodyAt(elder, before);
        check(body != null, "the candidate really has a body box");
        for (int x = BodyGeometry.floor(body.minX); x <= BodyGeometry.floor(body.maxX); x++) {
            for (int y = BodyGeometry.floor(body.minY); y <= BodyGeometry.floor(body.maxY); y++) {
                for (int z = BodyGeometry.floor(body.minZ); z <= BodyGeometry.floor(body.maxZ); z++) {
                    grown = grown.including(new GridPos(x, y, z));
                }
            }
        }
        com.tactical.core.zone.BattleZone.Bounds bodyGrown = grown.bounds();
        check(!com.tactical.core.zone.ZoneSeparation.overlap(feetOnly, theirs),
                "the battlefield its feet cell alone would grant does not reach the neighbour: " + feetOnly
                        + " vs " + theirs);
        check(com.tactical.core.zone.ZoneSeparation.overlap(bodyGrown, theirs),
                "while the one its whole real body needs really does: " + bodyGrown + " vs " + theirs);
        check(GuardianMovement.legal(level, elder, new GuardianMovement.Node(feet, before)),
                "and it stands on a legal water position of its own");
        check(!battle.tryAbsorb(elder),
                "so this encounter refuses the reinforcement: its grown battlefield would conflict");
        check(BattleManager.ownerOf(elder.getUUID()) == null, "the refused candidate is still unclaimed");
        check(!battle.isParticipant(elder) && !secondBattle.isParticipant(elder),
                "and still on neither roster");
        check(elder.position().distanceToSqr(before) < 1.0E-6D, "and it was not moved to make it fit");
        check(((TacticalGuardianAccess) elder).tactical$motionHandler() == null,
                "no motion seam was left on it");
        check(!elder.isNoAi(), "its own AI flag is untouched");
        check(elder.isPersistenceRequired(), "and so is its own persistence flag, in its original direction");
        check(battle.hostileCount() == mineBefore, "this roster is unchanged: " + battle.hostileCount());
        check(battle.zone().radius() == mineRadiusBefore, "and so is this battlefield");
        check(secondBattle.hostileCount() == theirHostileBefore, "the neighbouring roster is unchanged");
        check(secondBattle.turnQueueSize() == theirQueueBefore, "and its queue with it");
        check(secondBattle.zone().radius() == theirRadiusBefore
                        && secondBattle.zone().center().equals(theirCenter),
                "and its battlefield, centre and radius alike");
        check(elder.getHealth() == elder.getMaxHealth(), "the candidate is not hurt or healed by the refusal");
        endSecondEncounter();
        purgeAll();
    }

    /**
     * S3/A-R3: with one edge of budget, an ordinary guardian inside the near gate must not swim closer.
     *
     * <p>The scenario is the one a single "closer to the enemy" score gets wrong: a two-cell water pocket, the
     * target two cells from the body, and only enough pool for one edge. Retreating by one cell is still inside the
     * three-block gate, so it is not a firing position either - but it is further out, and moving in would make the
     * guardian's own band worse. The answer is either that retreat or ending the turn where it stands; a route that
     * ends nearer the target is the failure this case exists for.</p>
     */
    private static void shrunkenBudgetNeverSwimsCloser() {
        purgeAll();
        // A two-cell water pocket whose roof is the arena floor itself, so only those two cells are legal positions
        // at all - and nothing in the pocket can see the platform, so no cell here can fire.
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 1; z++) {
                level.setBlock(origin.offset(x, -3, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        for (int x = 0; x <= 1; x++) {
            level.setBlock(origin.offset(x, -2, 0), Blocks.WATER.defaultBlockState(), 2);
            for (int z = -1; z <= 1; z += 2) {
                level.setBlock(origin.offset(x, -2, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        level.setBlock(origin.offset(-1, -2, 0), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(origin.offset(2, -2, 0), Blocks.STONE.defaultBlockState(), 2);
        owner.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(1.5D, -2, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the shrunken-budget case starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the body holds the far cell of a two-cell pocket");
        check(!battle.fixtureGuardianFires(guardian.getUUID()),
                "and cannot fire from two blocks away: vanilla's near gate refuses it");
        int moveCost = Math.max(1, view.moveCost());
        // Exactly one edge of budget: the beam price plus one move, nothing more.
        check(battle.fixtureEnemyPool(guardian.getUUID(), 20 + moveCost),
                "the fixture leaves exactly one edge of budget after the beam reserve");
        check(battle.fixtureGuardianHere(guardian.getUUID()) != null, "with the body still holding water");
        ownerApMark = battle.currentAp(owner);
        ownerApMin = ownerApMark;
        routeStartFeet = view.guardian().feet();
        routeSeen = false;
        handOvers = 0;
        await(0, () -> watchShrunkenBudget(0));
    }

    private static void watchShrunkenBudget(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the shrunken-budget case never settled: busy=" + battle.isBusy() + " playerTurn="
                    + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        ownerApMin = Math.min(ownerApMin, battle.currentAp(owner));
        if (view.swimming()) {
            routeSeen = true;
            await(0, () -> watchShrunkenBudget(guard + 1));
            return;
        }
        if (!routeSeen && !awaitOwnTurn(heldGuardianId)) {
            await(0, () -> watchShrunkenBudget(guard + 1));
            return;
        }
        if (battle.isPlayerTurnNow() && !battle.isBusy()) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(settled != null && settled.guardian() != null, "the body still holds a real water node");
            double before = Math.sqrt(routeStartFeet.distanceToSqr(owner.position()));
            double after = Math.sqrt(settled.guardian().feet().distanceToSqr(owner.position()));
            check(after >= before - 1.0E-4D,
                    "with one edge of budget the body never swims closer to a target it is too close to: "
                            + before + " -> " + after);
            check(!settled.swimming() && !battle.isBusy(), "and nothing is left in flight or busy");
            check(ownerApMin == ownerApMark, "and the other side's pool was never charged for it");
            battle.finish("fixture shrunken budget done");
            battle = null;
            purgeAll();
            return;
        }
        await(0, () -> watchShrunkenBudget(guard + 1));
    }

    // ------------------------------------------------------------------ S3: interruption and price

    private static String interruptLabel = "";
    private static boolean interruptFirstFrame;
    private static boolean interruptBroken;
    private static boolean interruptSeenSwim;
    private static int interruptPerEdge;
    private static int interruptPoolBefore;
    private static int interruptPaidBefore;
    private static int interruptPrice;
    private static int interruptEdges;
    private static int interruptOwnerAp;
    private static int interruptOwnerMin;
    private static Vec3 interruptStartFeet;
    private static List<GridPos> interruptRoute = List.of();
    /** Whether the block the body really held was turned to air mid-route. */
    private static boolean interruptDrained;

    private static void firstFrameRefusalIsFree() {
        startInterruptionCase("first frame", true);
    }

    private static void startedEdgeKeepsFrozenPrice() {
        startInterruptionCase("started edge", false);
    }

    private static void startInterruptionCase(String label, boolean firstFrame) {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), label + ": the encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, label + ": the body holds a real water node");
        int moveCost = Math.max(1, view.moveCost());
        int pool = 12 * moveCost + 40;
        check(battle.fixtureEnemyPool(guardian.getUUID(), pool), label + ": the fixture sets the pool");
        // The view is a snapshot: the pool this case starts from is read again, after it was really set.
        ServerBattle.EnemyView funded = battle.fixtureEnemy(guardian.getUUID());
        check(funded != null && funded.ap() == pool, label + ": the pool really holds that quote");
        interruptLabel = label;
        interruptFirstFrame = firstFrame;
        interruptPerEdge = moveCost;
        interruptPoolBefore = funded.ap();
        interruptPaidBefore = funded.paidActions();
        interruptStartFeet = view.guardian().feet();
        interruptPrice = 0;
        interruptEdges = 0;
        interruptBroken = false;
        interruptSeenSwim = false;
        interruptRoute = List.of();
        interruptOwnerAp = battle.currentAp(owner);
        interruptOwnerMin = interruptOwnerAp;
        handOvers = 0;
        await(0, () -> watchInterruption(0));
    }

    private static void watchInterruption(int guard) {
        if (guard > SETTLE_LIMIT) {
            ServerBattle.EnemyView stuck = battle.fixtureEnemy(heldGuardianId);
            check(false, interruptLabel + ": the interrupted route never settled: "
                    + (stuck == null ? "gone" : "swimming=" + stuck.swimming() + " ap=" + stuck.ap()) + " busy="
                    + battle.isBusy() + " playerTurn=" + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, interruptLabel + ": the body stayed on the roster");
            return;
        }
        interruptOwnerMin = Math.min(interruptOwnerMin, battle.currentAp(owner));
        if (view.swimming()) {
            interruptSeenSwim = true;
            if (interruptPrice == 0) {
                interruptEdges = view.swimEdges();
                interruptPrice = view.swimPrice();
                interruptRoute = battle.fixtureSwimRoute(heldGuardianId);
                check(interruptEdges >= 2, interruptLabel + ": the committed route has more than one edge: "
                        + interruptEdges);
                check(interruptPrice == interruptEdges * interruptPerEdge,
                        interruptLabel + ": the route is quoted per edge and frozen: " + interruptPrice + " for "
                                + interruptEdges + " edges");
                check(view.ap() == interruptPoolBefore - interruptPrice,
                        interruptLabel + ": the whole route is taken out of its own pool once: "
                                + interruptPoolBefore + " -> " + view.ap());
                check(view.paidActions() == interruptPaidBefore,
                        interruptLabel + ": a swim never counts a paid action");
            }
            if (interruptFirstFrame && !interruptBroken) {
                check(view.swimIndex() == 0 && !view.swimEdgeStarted(),
                        interruptLabel + ": the route is at its commit, with no edge begun: index="
                                + view.swimIndex() + " started=" + view.swimEdgeStarted());
                check(view.feet().distanceToSqr(interruptStartFeet) < 1.0E-6D,
                        interruptLabel + ": and the commit itself moved nothing: " + view.feet());
                // Seal the cell the very first edge would enter, before a single frame of it exists. Nothing is
                // done to the body itself: it keeps its water, its position and its roster entry, so what this case
                // measures is only the price of a route the world changed under.
                check(!interruptRoute.isEmpty(), interruptLabel + ": the first edge is known");
                GridPos first = interruptRoute.get(0);
                for (int dy = 0; dy <= 1; dy++) {
                    level.setBlock(new BlockPos(first.x(), first.y() + dy, first.z()),
                            Blocks.STONE.defaultBlockState(), 2);
                }
                interruptBroken = true;
            }
            if (!interruptFirstFrame && !interruptBroken && view.swimEdgeStarted()) {
                check(view.swimIndex() == 0 && view.feet().distanceToSqr(interruptStartFeet) > 1.0E-6D,
                        interruptLabel + ": the first edge is really in flight: index=" + view.swimIndex()
                                + " feet=" + view.feet());
                check(interruptRoute.size() >= 2, interruptLabel + ": the committed route is known: "
                        + interruptRoute);
                // Seal the cell after the one in flight, and change the per-edge price after the commit.
                GridPos next = interruptRoute.get(1);
                for (int dy = 0; dy <= 1; dy++) {
                    level.setBlock(new BlockPos(next.x(), next.y() + dy, next.z()),
                            Blocks.STONE.defaultBlockState(), 2);
                }
                check(battle.fixtureEnemyMoveCost(heldGuardianId, 2 * interruptPerEdge),
                        interruptLabel + ": the fixture can change the per-edge price after the commit");
                interruptBroken = true;
            }
            await(0, () -> watchInterruption(guard + 1));
            return;
        }
        if (!interruptSeenSwim && !awaitOwnTurn(heldGuardianId)) {
            await(0, () -> watchInterruption(guard + 1));
            return;
        }
        if (!battle.isPlayerTurnNow()) {
            await(0, () -> watchInterruption(guard + 1));
            return;
        }
        // Settled: the turn came back and nothing is in flight.
        ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
        check(interruptSeenSwim, interruptLabel + ": the body really committed a route");
        check(settled != null, interruptLabel + ": and stayed on the roster");
        check(!settled.swimming(), interruptLabel + ": no route is left in flight");
        int expected = interruptFirstFrame ? interruptPoolBefore : interruptPoolBefore - interruptPerEdge;
        check(settled.ap() == expected,
                interruptLabel + ": exactly the started edges stay paid at the frozen price: pool=" + settled.ap()
                        + " expected=" + expected + " (edges=" + interruptEdges + ", per edge=" + interruptPerEdge
                        + ", before=" + interruptPoolBefore + ")");
        check(settled.paidActions() == interruptPaidBefore,
                interruptLabel + ": movement still never counts a paid action");
        check(interruptOwnerMin == interruptOwnerAp,
                interruptLabel + ": the other side's pool never moved during somebody else's turn: "
                        + interruptOwnerAp + " -> " + interruptOwnerMin);
        check(!battle.isBusy() || battle.terrainFallActive(),
                interruptLabel + ": no busy window is left behind, or the fall barrier really is running");
        check(settled.feet().y >= interruptStartFeet.y - 24.0D,
                interruptLabel + ": the body did not fall through the world: " + settled.feet());
        GuardianMovement.Node held = battle.fixtureGuardianHere(heldGuardianId);
        check(held == null || held.feet().distanceToSqr(settled.feet()) < 1.0E-6D,
                interruptLabel + ": a held node names exactly where the body is: " + settled.feet());
        if (!interruptFirstFrame) {
            check(held != null, interruptLabel + ": the body really still holds water where it stopped");
            check(settled.feet().distanceToSqr(interruptStartFeet) > 1.0E-6D,
                    interruptLabel + ": and it is not pulled back to its take-off point");
        }
        battle.finish("fixture interruption done");
        battle = null;
        purgeAll();
    }

    private static void drainedMidRouteSettlesSafely() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the drain case starts with a body under the roof");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the body holds a real water node");
        int moveCost = Math.max(1, view.moveCost());
        check(battle.fixtureEnemyPool(guardian.getUUID(), 12 * moveCost + 40), "with room for a route");
        // The view is a snapshot: the pool this case starts from is read again, after it was really set.
        ServerBattle.EnemyView funded = battle.fixtureEnemy(guardian.getUUID());
        check(funded != null && funded.ap() == 12 * moveCost + 40, "the drain case really holds that pool");
        interruptLabel = "drain";
        interruptFirstFrame = false;
        interruptBroken = false;
        interruptSeenSwim = false;
        interruptPrice = 0;
        interruptEdges = 0;
        interruptPerEdge = moveCost;
        interruptPoolBefore = funded.ap();
        interruptPaidBefore = funded.paidActions();
        interruptStartFeet = view.guardian().feet();
        interruptOwnerAp = battle.currentAp(owner);
        interruptOwnerMin = interruptOwnerAp;
        handOvers = 0;
        await(0, () -> watchDrain(0));
    }

    private static void watchDrain(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "drain: the interrupted route never settled: busy=" + battle.isBusy()
                    + " fall=" + battle.terrainFallActive() + " playerTurn=" + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "drain: the body stayed on the roster");
            return;
        }
        interruptOwnerMin = Math.min(interruptOwnerMin, battle.currentAp(owner));
        if (view.swimming()) {
            interruptSeenSwim = true;
            if (!interruptBroken && view.swimEdgeStarted()) {
                // Drain only what the body really holds this instant - its own feet and eye blocks - and leave the
                // destination's water in place. An endpoint-only rule would let this frame through, because the
                // node it is heading for is still a legal water body.
                Vec3 at = view.feet();
                Guardian body = (Guardian) level.getEntity(heldGuardianId);
                BlockPos feetCell = BlockPos.containing(at);
                level.setBlock(feetCell, Blocks.AIR.defaultBlockState(), 2);
                if (body != null) {
                    BlockPos eyeCell = BlockPos.containing(GuardianMovement.eye(body, at));
                    if (!eyeCell.equals(feetCell)) level.setBlock(eyeCell, Blocks.AIR.defaultBlockState(), 2);
                }
                interruptDrained = !GuardianMovement.isWater(level, feetCell);
                check(interruptDrained, "drain: the block the body really holds is now air");
                interruptBroken = true;
            }
            await(0, () -> watchDrain(guard + 1));
            return;
        }
        if (!interruptSeenSwim) {
            // Its own turn may still be about to plan: keep waiting until a route appears, or until its own turn
            // is really over without one - which would be a real failure, not something to wait out.
            if (battle.isPlayerTurnNow() && battle.fixtureOwnTurns(heldGuardianId) >= 1) {
                check(false, "drain: the body's own turn committed no route, so the drain could not be observed");
                return;
            }
            awaitOwnTurn(heldGuardianId);
            await(0, () -> watchDrain(guard + 1));
            return;
        }
        check(interruptDrained, "drain: the frame really lost the water it was standing in");
        boolean fall = battle.terrainFallActive();
        GuardianMovement.Node held = battle.fixtureGuardianHere(heldGuardianId);
        check(fall || held != null || !battle.isBusy(),
                "drain: the interrupted body is either really held or really descending: fall=" + fall
                        + " held=" + held + " feet=" + view.feet());
        if (held != null) {
            check(held.feet().distanceToSqr(view.feet()) < 1.0E-6D,
                    "drain: a held node names exactly where the body really is");
        }
        check(interruptOwnerMin == interruptOwnerAp,
                "drain: the other side's pool never moved: " + interruptOwnerAp + " -> " + interruptOwnerMin);
        check(view.ap() >= interruptPoolBefore - interruptPerEdge,
                "drain: at most the started edge stays paid: " + interruptPoolBefore + " -> " + view.ap());
        check(view.paidActions() == interruptPaidBefore, "drain: and no paid action was invented");
        if (battle.isPlayerTurnNow() || fall) {
            battle.finish("fixture drain done");
            battle = null;
            purgeAll();
            return;
        }
        await(0, () -> watchDrain(guard + 1));
    }

    /**
     * S3/A-R1: a body interrupted at a fractional height really descends from where it is, into real water.
     *
     * <p>The ordinary enemy descent snaps a body onto the centre of its cell, which is exactly the "pulled back"
     * move this batch forbids. Here the feet start at a fractional X/Z and a fractional height, the water directly
     * under them is taken away, and the body has to come down one cell into the water below and be adopted
     * <em>where it is</em>. Every world tick of the way down is recorded: the real Y, the busy flag, the fall, the
     * acting slot, the turn epoch and the other side's pool.</p>
     */
    private static void fractionalFeetDescentKeepsItsPosition() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.35D, -1.5D, 0.35D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the fractional descent case starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the body holds a fractional water position");
        descentStartFeet = view.guardian().feet();
        check(Math.abs(descentStartFeet.y - Math.rint(descentStartFeet.y)) > 1.0E-3D,
                "whose real height is fractional: " + descentStartFeet);
        check(Math.abs(descentStartFeet.x - Math.floor(descentStartFeet.x) - 0.5D) > 1.0E-3D
                        || Math.abs(descentStartFeet.z - Math.floor(descentStartFeet.z) - 0.5D) > 1.0E-3D,
                "and so is its horizontal position: " + descentStartFeet);
        descentReset();
        // Only the block the body really holds is taken; the water below it stays, so there is a real landing.
        int cellY = (int) Math.floor(descentStartFeet.y);
        level.setBlock(new BlockPos((int) Math.floor(descentStartFeet.x), cellY,
                (int) Math.floor(descentStartFeet.z)), Blocks.AIR.defaultBlockState(), 2);
        check(!GuardianMovement.isWater(level, BlockPos.containing(descentStartFeet)),
                "the block under the body really stops being water");
        check(GuardianMovement.isWater(level, new BlockPos((int) Math.floor(descentStartFeet.x), cellY - 1,
                        (int) Math.floor(descentStartFeet.z))),
                "while the cell it will really land in stays water");
        descentOwnerAp = battle.currentAp(owner);
        descentEpoch = battle.turnEpoch();
        // Sampled once per real server tick from here on, so every recorded drop spans exactly one tick.
        await(0, () -> watchFractionalDescent(0, false));
    }

    private static Vec3 descentStartFeet;
    private static double descentLastY;
    private static double descentLowestY;
    private static double descentDrift;
    private static int descentSamples;
    private static int descentRises;
    private static double descentBiggestSlice;
    private static int descentOwnerAp;
    private static long descentEpoch;
    private static boolean descentActingSeen;

    private static void descentReset() {
        descentLastY = descentStartFeet.y;
        descentLowestY = descentStartFeet.y;
        descentDrift = 0.0D;
        descentSamples = 0;
        descentRises = 0;
        descentBiggestSlice = 0.0D;
        descentActingSeen = false;
    }

    /**
     * One world tick of a real descent.
     *
     * <p>Everything asserted here is read from the live server on that tick: the real position, the shared fall
     * flag, the busy flag, the epoch and the other side's pool. The per-tick drop is compared against what the
     * production really allows - one fractional slice plus one cell slice - instead of a bound this fixture
     * invented.</p>
     */
    private static void watchFractionalDescent(int guard, boolean dryExit) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the fractional descent never settled: fall=" + battle.terrainFallActive()
                    + " busy=" + battle.isBusy());
            return;
        }
        Guardian body = (Guardian) level.getEntity(heldGuardianId);
        if (body == null) {
            check(false, "the descending body stayed in the world");
            return;
        }
        double drift = Math.sqrt(Math.pow(body.getX() - descentStartFeet.x, 2)
                + Math.pow(body.getZ() - descentStartFeet.z, 2));
        descentDrift = Math.max(descentDrift, drift);
        if (body.getY() > descentLastY + 1.0E-6D) descentRises++;
        descentBiggestSlice = Math.max(descentBiggestSlice, descentLastY - body.getY());
        descentLastY = body.getY();
        descentLowestY = Math.min(descentLowestY, body.getY());
        descentSamples++;
        check(battle.turnEpoch() == descentEpoch,
                "the descent runs inside its own turn epoch: " + descentEpoch + " vs " + battle.turnEpoch());
        check(battle.currentAp(owner) == descentOwnerAp,
                "the other side's pool is never charged for this descent: " + descentOwnerAp + " -> "
                        + battle.currentAp(owner));
        if (battle.fixtureOwnTurns(heldGuardianId) >= 1) descentActingSeen = true;
        boolean fall = battle.terrainFallActive();
        if (fall) {
            check(drift < 1.0E-6D, "the descending body keeps its real horizontal position: drift=" + drift);
            // The production may take one fractional slice and one cell slice in the same tick, so the bound is
            // the sum of the two, never a slower animation invented here.
            double allowed = 2.0D * battle.fixtureSubCellDescent() + 1.0E-6D;
            check(descentBiggestSlice <= allowed,
                    "no tick descends further than one fractional slice plus one cell slice: "
                            + descentBiggestSlice + " <= " + allowed);
            await(0, () -> watchFractionalDescent(guard + 1, dryExit));
            return;
        }
        check(descentSamples > 1, "the descent was really observed over more than one tick: " + descentSamples);
        check(descentDrift < 1.0E-6D,
                "the whole way down, the body never slid sideways onto a cell centre: max drift=" + descentDrift);
        check(descentRises == 0, "and the real height never bounced back up: rises=" + descentRises);
        check(descentLowestY < descentStartFeet.y - 1.0E-4D,
                "and it really descended instead of being declared landed where it stood: " + descentStartFeet.y
                        + " -> " + descentLowestY);
        if (!dryExit) {
            check(battle.fixtureEnemy(heldGuardianId) != null,
                    "a descent that really lands in water keeps the unit on the roster");
            GuardianMovement.Node held = battle.fixtureGuardianHere(heldGuardianId);
            check(held != null, "and adopts the real water point it reached");
            check(held.feet().distanceToSqr(body.position()) < 1.0E-6D,
                    "at exactly the position the body really has: " + held.feet() + " vs " + body.position());
            check(body.position().distanceToSqr(descentStartFeet) > 1.0E-6D,
                    "so it is not back where it started: " + body.position());
            check(!battle.isBusy(), "and the encounter is not left busy once the fall is over");
        }
        finishDescent(dryExit, body);
    }

    private static void finishDescent(boolean dryExit, Guardian body) {
        battle.finish("fixture fractional descent done");
        battle = null;
        purgeAll();
    }

    /**
     * S3/A-R1: a held swimmer whose water is really gone is released, not frozen for the rest of the fight.
     *
     * <p>This is the case Sol's first difference named: a body that comes down where there is no legal water used
     * to keep its roster slot, its borrowed flags and its switched-off native travel, with only an end-turn every
     * round. It is now given back through this family's named exit - roster, queue, claim, motion seam and the
     * original flags - while the entity itself stays in the world, at its real position, untouched. The release
     * has to happen on its own, with no manual finish, and it must not be reported as the timeout.</p>
     */
    private static void idleDrainIsHandled() {
        purgeAll();
        buildPit();
        Guardian guardian = spawnGuardian(-4.35D, -2.4D, 0.35D);
        heldGuardianId = guardian.getUUID();
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) guardian).tactical$persistenceRequired(false);
        check(BattleManager.start(owner, true), "the dry-exit case starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null && view.guardian() != null, "the held body has a real water position");
        check(battle.isPlayerTurnNow(), "and the turn is still the player's, so the body is idle");
        descentStartFeet = view.guardian().feet();
        descentReset();
        descentOwnerAp = battle.currentAp(owner);
        descentEpoch = battle.turnEpoch();
        check(guardian.isNoAi(), "the AI flag is borrowed by the encounter, as usual");
        check(guardian.isPersistenceRequired(), "and so is the persistence flag");
        // Take the water away above the floor and nothing else: the body has nothing legal left to hold, and it
        // still comes down on solid rock. A drain that took the floor too would leave the planner with no landing
        // at all, and the shared timeout would answer instead of this family's own exit - the very confusion this
        // case exists to rule out, so both halves of that are asserted before the body is left to fall.
        int cellY = (int) Math.floor(descentStartFeet.y);
        BlockPos column = new BlockPos((int) Math.floor(descentStartFeet.x), cellY,
                (int) Math.floor(descentStartFeet.z));
        for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(column.above(dy), Blocks.AIR.defaultBlockState(), 2);
        }
        check(!level.getFluidState(column).is(net.minecraft.tags.FluidTags.WATER),
                "the water it held is really gone");
        check(!level.getBlockState(column.below())
                .getCollisionShape(level, column.below()).isEmpty(),
                "while the rock it comes down on is still there");
        await(0, () -> watchDryExit(0));
    }

    private static int dryExitGuard;
    private static boolean dryExitReleased;

    private static void watchDryExit(int guard) {
        dryExitGuard = guard;
        if (guard > SETTLE_LIMIT) {
            check(false, "the dry exit never settled: fall=" + battle.terrainFallActive()
                    + " busy=" + battle.isBusy() + " roster=" + (battle.fixtureEnemy(heldGuardianId) != null));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        Guardian body = (Guardian) level.getEntity(heldGuardianId);
        if (body == null) {
            check(false, "the released body is still a real entity in the world");
            return;
        }
        double drift = Math.sqrt(Math.pow(body.getX() - descentStartFeet.x, 2)
                + Math.pow(body.getZ() - descentStartFeet.z, 2));
        descentDrift = Math.max(descentDrift, drift);
        if (body.getY() > descentLastY + 1.0E-6D) descentRises++;
        descentBiggestSlice = Math.max(descentBiggestSlice, descentLastY - body.getY());
        descentLastY = body.getY();
        descentLowestY = Math.min(descentLowestY, body.getY());
        descentSamples++;
        if (view != null) {
            check(descentDrift < 1.0E-6D,
                    "while it is still held, the descending body keeps its real horizontal position: drift="
                            + descentDrift);
            check(battle.turnEpoch() == descentEpoch, "the descent runs inside its own turn epoch");
            check(battle.currentAp(owner) == descentOwnerAp,
                    "and the other side's pool is never charged for it: " + descentOwnerAp + " -> "
                            + battle.currentAp(owner));
            await(0, () -> watchDryExit(guard + 1));
            return;
        }
        // It really left the roster on its own.
        check(!battle.finished(), "the release happened while the encounter is still running, not at a manual end");
        check(descentSamples > 1, "the descent was really observed before the release: " + descentSamples);
        check(descentDrift < 1.0E-6D,
                "the body never slid sideways onto a cell centre: max drift=" + descentDrift);
        check(descentRises == 0, "nor did its height bounce back up: rises=" + descentRises);
        check(descentLowestY < descentStartFeet.y - 1.0E-4D,
                "and it really descended: " + descentStartFeet.y + " -> " + descentLowestY);
        check(battle.fixtureGuardianHere(heldGuardianId) == null, "the dry landing holds no water node");
        check(!battle.terrainFallActive(), "and the shared fall really ended");
        check(!battle.isBusy(), "the encounter is not left busy by the release");
        check(battle.isPlayerTurnNow(), "and the player keeps the turn it had");
        check(BattleManager.ownerOf(heldGuardianId) == null, "the ownership claim was released");
        check(((TacticalGuardianAccess) body).tactical$motionHandler() == null,
                "the motion seam was cleared, so nothing of this encounter still owns the body's travel");
        check(!GuardianMotion.controls(body), "and the body answers that nobody holds it");
        check(!body.isNoAi(), "the original no-AI flag came back");
        check(!body.isPersistenceRequired(), "and the original persistence flag with it");
        check(body.isAlive() && !body.isRemoved(), "while the entity itself is still alive in the world");
        check(body.level() == level && level.getEntity(heldGuardianId) == body,
                "and still really present in this level");
        check(battle.hostileCount() == 0, "the roster really lost the unit: hostiles=" + battle.hostileCount());
        // Real journal rows are compared as the players read them, so the named exit is asked for by its own
        // sentence and the waited-out timeout by its own: a release that the wrong branch reported cannot pass here.
        String named = BattleText.translatable(BattleText.KEY_ENEMY_RELEASED_NO_WATER,
                BattleText.name(body)).getString();
        String timedOut = BattleText.translatable(BattleText.KEY_FALL_ABANDONED,
                BattleText.name(body)).getString();
        boolean sawNamed = wire != null && wire.log.stream().anyMatch(row -> row.contains(named));
        boolean sawTimeout = wire != null && wire.log.stream().anyMatch(row -> row.contains(timedOut));
        check(sawNamed, "and the journal names this its own exit: " + named + " in "
                + (wire == null ? "no wire" : wire.log));
        check(!sawTimeout, "rather than reporting a normal loss of water as a waited-out timeout");
        check(battle.fixtureEnemy(heldGuardianId) == null, "and its roster entry is gone");
        battle.finish("fixture dry exit done");
        battle = null;
        purgeAll();
    }

    // ------------------------------------------------------------------ S4/S5: the native beam

    /** Ticks (counted by the watcher) at which a watched beam was first seen, pointed and settled. */
    private static int beamFirstTick;
    private static int beamSyncTick;
    private static int beamSettleTick;
    private static float beamHealthBefore;
    private static int beamApBefore;
    private static int beamOwnerApBefore;
    private static int beamPriceSeen;
    private static int beamChargeSeen;
    private static int beamSettlementsBefore;
    private static boolean beamBusySeen;
    /** Whether the encounter really stayed busy through every tick of the charge window. */
    private static boolean beamBusyHeld;
    private static boolean beamOwnerCharged;
    private static boolean beamOtherSideTouched;
    private static String beamCaseLabel;
    private static Runnable beamCaseAssert;

    /**
     * The one incoming-damage filter a case installs, so a native hit can be cancelled or made to throw for real.
     *
     * <p>It is a real listener on the game bus, not a mock: a cancelled hit returns {@code false} from vanilla's own
     * {@code hurt}, which is exactly the boolean the beam's receipt has to reflect.</p>
     */
    private static Predicate<net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent> damageFilter =
            event -> false;
    private static int damageFilterHits;

    // receiveCanceled: the encounter's own isolation cancels outside damage at HIGHEST priority, and this fixture
    // has to see the real event either way - a cancelled hit is exactly one of the cases it must be able to name.
    @SubscribeEvent(receiveCanceled = true)
    public static void incomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!enabled) return;
        if (!damageFilter.test(event)) return;
        damageFilterHits++;
        event.setCanceled(true);
    }

    private static void resetBeamWatch(String label, Runnable assertions) {
        beamFirstTick = -1;
        beamSyncTick = -1;
        beamSettleTick = -1;
        beamHealthBefore = 0.0F;
        beamApBefore = 0;
        beamOwnerApBefore = 0;
        beamPriceSeen = 0;
        beamChargeSeen = 0;
        beamSettlementsBefore = battle.fixtureGuardianBeamSettlements();
        beamBusySeen = false;
        beamBusyHeld = true;
        beamOwnerCharged = false;
        beamOtherSideTouched = false;
        beamCaseLabel = label;
        beamCaseAssert = assertions;
        handOvers = 0;
    }

    /**
     * The shared watcher of one real beam: it records the busy window, the synced attack target and the settlement,
     * then lets the case assert on what really happened.
     *
     * <p>Every reading is a production readback of the encounter's own state, and every tick of the window is a real
     * server tick: the charge is not simulated and the settlement is not called by hand.</p>
     */
    private static void watchBeam(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, beamCaseLabel + ": the beam never settled (busy=" + battle.isBusy() + ", beaming="
                    + battle.fixtureGuardianBeamBusy(heldGuardianId) + ")");
            return;
        }
        if (battle.finished()) {
            check(false, beamCaseLabel + ": the encounter ended before the beam settled");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, beamCaseLabel + ": the body stayed on the roster");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamHealthBefore = owner.getHealth();
                beamApBefore = view.ap();
                beamOwnerApBefore = battle.currentAp(owner);
                beamPriceSeen = battle.fixtureGuardianBeamPrice(heldGuardianId);
                beamChargeSeen = battle.fixtureGuardianCharge(heldGuardianId);
                check(view.paidActions() == 0,
                        beamCaseLabel + ": a launched beam charges nothing before its own native hit");
                check(!battle.fixtureGuardianMoving(heldGuardianId),
                        beamCaseLabel + ": a charging body is not swimming, so the native bit says so");
            }
            // The busy window is tracked tick by tick and asserted once at the settlement, so one repeated label
            // cannot inflate the check count.
            beamBusyHeld &= battle.isBusy();
            if (battle.fixtureGuardianSyncedTarget(heldGuardianId) && beamSyncTick < 0) {
                beamSyncTick = guard;
                check(battle.fixtureGuardianSyncedTargetId(heldGuardianId) == owner.getId(),
                        beamCaseLabel + ": the native synced attack target is this turn's own opponent");
            }
            beamOwnerCharged |= battle.currentAp(owner) < beamOwnerApBefore;
            await(0, () -> watchBeam(guard + 1));
            return;
        }
        if (beamFirstTick >= 0) {
            beamSettleTick = guard;
            beamOwnerCharged |= battle.currentAp(owner) < beamOwnerApBefore;
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(beamBusyHeld, beamCaseLabel + ": the whole charge window held the encounter busy");
            check(beamSyncTick >= 0, beamCaseLabel + ": the native charge really pointed at its target");
            check(beamSyncTick - beamFirstTick == 10,
                    beamCaseLabel + ": vanilla's own -10 pre-heat is kept (" + beamSyncTick + " - " + beamFirstTick + ")");
            check(settled != null && settled.paidActions() <= 1,
                    beamCaseLabel + ": at most one paid action for the whole beam");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore + 1,
                    beamCaseLabel + ": exactly one settlement, never a second hand-back");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId),
                    beamCaseLabel + ": the native goal cleared the synced attack target when it stopped");
            check(!battle.isBusy() && !battle.fixtureGuardianBeamBusy(heldGuardianId),
                    beamCaseLabel + ": no busy window is left behind");
            check(!beamOwnerCharged,
                    beamCaseLabel + ": the other side's own pool was never charged for this beam");
            check(battle.fixtureGuardianGoalInstalled(heldGuardianId),
                    beamCaseLabel + ": the captured goal is still the one vanilla installed");
            beamCaseAssert.run();
            battle.finish("fixture " + beamCaseLabel + " done");
            battle = null;
            damageFilter = event -> false;
            purgeAll();
            return;
        }
        if (battle.fixtureOwnTurns(heldGuardianId) >= 1 && battle.isPlayerTurnNow() && !battle.isBusy()) {
            check(false, beamCaseLabel + ": the body's own turn came and went without launching its beam");
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchBeam(guard + 1));
    }

    /** Put one body in a legal firing position over the measuring water and start the encounter. */
    private static Guardian beamCaseBody(boolean elder) {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian body = elder ? spawnElder(7.5D, -2, 0.5D) : spawnGuardian(2.5D, -3, 0.5D);
        heldGuardianId = body.getUUID();
        check(BattleManager.start(owner, true), beamCaseLabel + ": the encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(body.getUUID());
        check(view != null && view.guardian() != null, beamCaseLabel + ": the body holds a real water node");
        check(battle.fixtureGuardianFires(body.getUUID()),
                beamCaseLabel + ": it really stands in a legal firing position");
        check(battle.fixtureGuardianGoalInstalled(body.getUUID()),
                beamCaseLabel + ": the native attack goal it owns is the one vanilla installed");
        int configuredBase = com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.get();
        check(battle.fixtureGuardianBeamCost(body.getUUID()) == configuredBase,
                beamCaseLabel + ": the quote is the configured base price at no paid action: "
                        + battle.fixtureGuardianBeamCost(body.getUUID()) + " vs " + configuredBase);
        return body;
    }

    /**
     * S4: a body that had to swim first fires its own native beam from the water it really reached.
     *
     * <p>The whole window is measured on real server ticks: the pre-heat, the charge, the synced attack target, the
     * frozen price charged once, and the real health the two native hits took from a real player.</p>
     */
    private static void nativeBeamChargesAfterArriving() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(-4.5D, -3, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the beam encounter starts with a body under the roof");
        battle = BattleManager.current();
        check(battle.fixtureGuardianGoalInstalled(guardian.getUUID()),
                "the captured goal is really installed in that body's own goal selector");
        check(battle.fixtureGuardianCharge(guardian.getUUID()) == 80,
                "an ordinary guardian's native charge length is vanilla's eighty");
        check(battle.fixtureGuardianBeamCost(guardian.getUUID()) == 20, "and its beam is quoted at the base price");
        check(!battle.fixtureGuardianFires(guardian.getUUID()), "it cannot fire from behind the wall");
        ServerBattle.EnemyView view = battle.fixtureEnemy(guardian.getUUID());
        check(view != null, "the body is on the roster");
        battle.fixtureEnemyPool(guardian.getUUID(), 12 * Math.max(1, view.moveCost()) + 40);
        resetBeamWatch("S4 arriving beam", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(beamPriceSeen == 20, "the frozen price is the quoted base price: " + beamPriceSeen);
            check(settled != null && settled.ap() == beamApBefore - beamPriceSeen,
                    "the frozen price was charged exactly once: " + beamApBefore + " -> "
                            + (settled == null ? "gone" : settled.ap()));
            check(settled != null && settled.paidActions() == 1, "and it counts as one paid action");
            check(owner.getHealth() < beamHealthBefore,
                    "a real player really took the native hits: " + beamHealthBefore + " -> " + owner.getHealth());
            int window = beamSettleTick - beamFirstTick;
            check(Math.abs(window - (10 + beamChargeSeen)) <= 1,
                    "the whole window is the pre-heat plus the native charge: " + window + " ticks for "
                            + beamChargeSeen);
            check(battle.fixtureGuardianFires(heldGuardianId), "the body still holds the water it fired from");
        });
        await(0, () -> watchBeam(0));
    }

    /**
     * S4 (elder): the elder's own sixty and its missing near gate, measured the same way.
     *
     * <p>The elder stands about two blocks from its opponent - a distance an ordinary guardian's own near gate
     * refuses - and still fires, with a charge twenty ticks shorter than the ordinary body's.</p>
     */
    private static void elderFiresInCloseRange() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        ElderGuardian elder = spawnElder(7.5D, -2, 0.5D);
        heldGuardianId = elder.getUUID();
        check(BattleManager.start(owner, true), "the elder encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(elder.getUUID());
        check(view != null && view.guardian() != null, "the elder holds a real water node");
        check(battle.fixtureGuardianCharge(elder.getUUID()) == 60, "the elder's native charge is vanilla's sixty");
        check(battle.fixtureGuardianFires(elder.getUUID()),
                "vanilla's own near gate does not exist for the elder: it can fire from here");
        resetBeamWatch("S4 elder beam", () -> {
            int window = beamSettleTick - beamFirstTick;
            check(Math.abs(window - (10 + beamChargeSeen)) <= 1,
                    "the elder's window is its own pre-heat plus sixty: " + window + " ticks");
            check(owner.getHealth() < beamHealthBefore, "a real player took the elder's native hits");
        });
        await(0, () -> watchBeam(0));
    }

    /** Where the two native parts of the settlement are, for a filter that has to name one of them. */
    private static boolean fromGuardian(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        return event.getSource().getEntity() instanceof Guardian guardian
                && guardian.getUUID().equals(heldGuardianId);
    }

    /**
     * S5: one real settlement, with the two native parts cancelled, kept or made to throw.
     *
     * <p>Every case drives the same production path and reads the same authoritative state; only the damage event
     * filter differs. Nothing infers a hit from a health delta: the cases turn on what vanilla's own two calls really
     * returned.</p>
     */
    private static void nativeBeamReceiptIsOneCharge() {
        // (a) the magic part is cancelled, the physical part lands: one hit, one charge.
        beamCaseLabel = "S5 magic cancelled";
        beamCaseBody(false);
        damageFilterHits = 0;
        damageFilter = event -> event.getEntity() == owner && fromGuardian(event)
                && event.getSource().is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC);
        resetBeamWatch("S5 magic cancelled", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(damageFilterHits == 1, "the magic part really reached the damage event and was cancelled");
            check(settled != null && settled.paidActions() == 1,
                    "one native part landed, so the beam is one paid action");
            check(settled != null && settled.ap() == beamApBefore - beamPriceSeen,
                    "and the frozen price was charged once: " + beamApBefore + " -> "
                            + (settled == null ? "gone" : settled.ap()));
            check(owner.getHealth() < beamHealthBefore, "the physical part still hurt a real player");
        });
        await(0, () -> watchBeam(0));
    }

    private static void nativeBeamWithBothPartsCancelledChargesNothing() {
        beamCaseLabel = "S5 both cancelled";
        beamCaseBody(false);
        damageFilterHits = 0;
        damageFilter = event -> event.getEntity() == owner && fromGuardian(event);
        resetBeamWatch("S5 both cancelled", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(damageFilterHits == 2, "both native parts really reached the damage event: " + damageFilterHits);
            check(settled != null && settled.paidActions() == 0, "two refusals charge no paid action");
            check(settled != null && settled.ap() == beamApBefore, "and no AP: " + beamApBefore + " -> "
                    + (settled == null ? "gone" : settled.ap()));
            check(owner.getHealth() == beamHealthBefore, "a real player took no damage at all");
        });
        await(0, () -> watchBeam(0));
    }

    /**
     * S5 (the other half-success): the magic part lands and the physical part is refused.
     *
     * <p>The mirror image of the case above, and the one that proves the receipt is not a single "did anything
     * happen" flag: the very same beam that lands one native part and loses the other is still exactly one paid
     * action at exactly one frozen price.</p>
     *
     * <p>Deliberately not a throwing case: NeoForge's event bus catches and logs listener exceptions instead of
     * propagating them, so a listener cannot be used to abort the second native call on a real server. The fix for a
     * throwing post-hit step is the inner-hurt receipt in {@link NativeBeamBridge}, and the exception-safe cleanup is
     * proved by the native-stop case in the R2 stage.</p>
     */
    private static void nativeBeamChargesOnceWhenTheSecondPartIsRefused() {
        beamCaseLabel = "S5 physical refused";
        beamCaseBody(false);
        damageFilterHits = 0;
        damageFilter = event -> event.getEntity() == owner && fromGuardian(event)
                && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC);
        resetBeamWatch("S5 physical refused", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(damageFilterHits == 1, "the physical part really reached the damage event and was refused");
            check(settled != null && settled.paidActions() == 1,
                    "a first part that landed still owes exactly one paid action");
            check(settled != null && settled.ap() == beamApBefore - beamPriceSeen,
                    "and exactly one frozen price, never a refund: " + beamApBefore + " -> "
                            + (settled == null ? "gone" : settled.ap()));
            check(owner.getHealth() < beamHealthBefore, "the magic part really hurt a real player");
            check(!NativeBeamBridge.inScope(), "the receipt scope never survives the settlement");
        });
        await(0, () -> watchBeam(0));
    }

    /**
     * S5 (unaffordable): the frozen quote is re-checked before the settlement tick, so a body that lost its pool
     * mid-charge never lands the hit and never pays.
     */
    private static void anUnpayableBeamStopsBeforeItsHit() {
        beamCaseLabel = "S5 unpayable";
        beamCaseBody(false);
        resetBeamWatch("S5 unpayable", () -> { });
        await(0, () -> watchUnpayableBeam(0));
    }

    private static void watchUnpayableBeam(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the unpayable case never finished (busy=" + battle.isBusy() + ")");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        // The other side's pool only ever rises on its own turn start, so a mark below the start means a charge.
        if (beamFirstTick >= 0) beamOwnerCharged |= battle.currentAp(owner) < beamOwnerApBefore;
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamHealthBefore = owner.getHealth();
                beamApBefore = view.ap();
                beamOwnerApBefore = battle.currentAp(owner);
                // Take the pool below the frozen quote while the charge is running: the settlement check must refuse.
                battle.fixtureEnemyAp(heldGuardianId, 1);
            }
            await(0, () -> watchUnpayableBeam(guard + 1));
            return;
        }
        if (beamFirstTick >= 0) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(settled != null && settled.paidActions() == 0, "an unpayable beam spends no paid action");
            check(settled != null && settled.ap() == 1, "and its pool is never pushed below zero: "
                    + (settled == null ? "gone" : settled.ap()));
            check(owner.getHealth() == beamHealthBefore, "and no native hit was landed at all");
            check(!battle.isBusy() && !battle.fixtureGuardianBeamBusy(heldGuardianId), "no busy window is left behind");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId), "and the native attack target is cleared");
            check(!beamOwnerCharged, "the other side's own pool was never charged: " + beamOwnerApBefore + " -> "
                    + battle.currentAp(owner));
            battle.finish("fixture unpayable beam done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchUnpayableBeam(guard + 1));
    }

    // ------------------------------------------------------------------ S6: the native spikes

    /** Every spike reaction this fixture really saw, and who took it. */
    private static int thornsEvents;
    private static float thornsAmount;
    private static Entity thornsVictim;

    /** How many incoming-damage events really reached the body under test, whatever their outcome. */
    private static int guardianHitEvents;

    @SubscribeEvent(receiveCanceled = true)
    public static void observeThorns(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!enabled) return;
        if (event.getEntity() instanceof Guardian held && held.getUUID().equals(heldGuardianId)) guardianHitEvents++;
        if (!event.getSource().is(net.minecraft.world.damagesource.DamageTypes.THORNS)) return;
        thornsEvents++;
        thornsAmount = event.getAmount();
        thornsVictim = event.getEntity();
    }

    /**
     * S6: vanilla's own spikes, on a controlled body, only for a real opposed attacker of this same encounter.
     *
     * <p>The reaction itself is never written by hand: the assertions count the {@code thorns} damage source vanilla
     * really created and read the guard's own health, so "the spikes did not run" and "the spikes were cancelled
     * later" are different facts. Nothing here charges AP or a paid action, and a hit whose main damage is cancelled
     * still leaves the spikes standing.</p>
     */
    private static void nativeThornsStayInThisEncounter() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(7.5D, -2, 0.5D);
        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the spike encounter starts");
        battle = BattleManager.current();
        // A bystander spawned after the admission scan, so it never joined: the same native melee, from a body this
        // encounter does not own.
        Zombie bystander = spawn(EntityType.ZOMBIE, 40.5D, 0, 0.5D, false, false);
        check(!battle.isParticipant(bystander), "a body outside the encounter is not a participant");
        check(battle.isParticipant(owner), "the firing side's player is a participant");
        check(theGuardianIsStandingStill(guardian), "the body is standing still, so vanilla's own gate is open");

        // 1. A real opposed melee: vanilla's own spike really fires, twice.
        ServerBattle.EnemyView spikeView = battle.fixtureEnemy(heldGuardianId);
        check(spikeView != null, "the body is on the roster");
        int guardianApBeforeSpikes = spikeView.ap();
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        thornsAmount = 0.0F;
        thornsVictim = null;
        float guardianBefore = guardian.getHealth();
        owner.attack(guardian);
        check(guardian.getHealth() < guardianBefore, "the real melee really hurt the body");
        check(thornsEvents == 1, "vanilla's own spike ran exactly once for that one hit: " + thornsEvents);
        check(thornsVictim == owner, "and it really landed on the attacker");
        check(thornsAmount == 2.0F, "with vanilla's own two damage, not a written number: " + thornsAmount);
        ServerBattle.EnemyView afterMelee = battle.fixtureEnemy(heldGuardianId);
        check(afterMelee != null && afterMelee.paidActions() == 0, "the spikes are never a paid action");
        check(afterMelee != null && afterMelee.ap() == guardianApBeforeSpikes,
                "and they never cost AP: " + guardianApBeforeSpikes + " -> "
                        + (afterMelee == null ? "gone" : afterMelee.ap()));

        // 2. A main hit that is cancelled after the spike: the spike keeps vanilla's own order.
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        float playerBefore = owner.getHealth();
        float guardianBeforeCancel = guardian.getHealth();
        damageFilter = event -> event.getEntity() == guardian
                && event.getSource().getDirectEntity() == owner
                && !event.getSource().is(net.minecraft.world.damagesource.DamageTypes.THORNS);
        owner.attack(guardian);
        damageFilter = event -> false;
        check(thornsEvents == 1, "the spike still ran although the main hit was cancelled: " + thornsEvents);
        check(owner.getHealth() < playerBefore, "and it really hurt the attacker");
        check(guardian.getHealth() == guardianBeforeCancel, "while the cancelled main hit changed nothing");

        // 3. Vanilla's own moving gate: a swimming body has no spikes.
        GuardianMotion.moving(guardian, true);
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        guardianHitEvents = 0;
        float movingGuardianBefore = guardian.getHealth();
        owner.attack(guardian);
        check(guardianHitEvents == 1, "the melee really reached the moving body: " + guardianHitEvents);
        check(guardian.getHealth() < movingGuardianBefore, "the melee still landed on the moving body");
        check(thornsEvents == 0, "but a body vanilla calls moving has no spikes at all: " + thornsEvents);
        GuardianMotion.moving(guardian, false);

        // 4. A real arrow: the direct entity is the arrow, not a living attacker, so no spike.
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        float arrowGuardianBefore = guardian.getHealth();
        net.minecraft.world.entity.projectile.Arrow arrow =
                new net.minecraft.world.entity.projectile.Arrow(EntityType.ARROW, level);
        arrow.setOwner(owner);
        arrow.setPos(origin.getX() + 5.5D, origin.getY() - 2.0D, origin.getZ() + 0.5D);
        level.addFreshEntity(arrow);
        guardianHitEvents = 0;
        guardian.hurt(level.damageSources().arrow(arrow, owner), 3.0F);
        check(guardianHitEvents == 1, "the real arrow's damage really reached this body: " + guardianHitEvents);
        check(guardian.getHealth() < arrowGuardianBefore, "a real arrow hurt the body");
        check(thornsEvents == 0, "an arrow is not a living direct attacker, so no spike: " + thornsEvents);

        // 5. A bystander's real melee: same native call, but it never joined this encounter.
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        guardianHitEvents = 0;
        guardianHitEvents = 0;
        guardian.hurt(level.damageSources().mobAttack(bystander), 3.0F);
        check(guardianHitEvents == 1, "the bystander's real mob-attack damage really reached this body: events="
                + guardianHitEvents + " asked=" + battle.fixtureGuardianThornsAsked()
                + " hp=" + guardian.getHealth());
        check(thornsEvents == 0, "a bystander may not use this body's spikes: " + thornsEvents);

        // 6. A same-side unit of this same encounter: allied, therefore never opposed.
        Zombie ally = spawn(EntityType.ZOMBIE, 7.5D, 0, 2.5D, false, false);
        if (!battle.isParticipant(ally)) battle.tryAbsorb(ally);
        check(battle.isParticipant(ally), "a second hostile really joined this encounter");
        check(battle.fixtureSide(ally.getUUID()) != null, "and it is a real roster entry of this encounter");
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        guardianHitEvents = 0;
        guardianHitEvents = 0;
        guardian.hurt(level.damageSources().mobAttack(ally), 3.0F);
        check(guardianHitEvents == 1, "the same-side real mob-attack damage really reached this body: events="
                + guardianHitEvents + " hp=" + guardian.getHealth());
        check(thornsEvents == 0, "a same-side attacker gets no spike reaction: " + thornsEvents);

        // 7. Another encounter's source: the same native melee, from a body of somebody else's battlefield.
        check(secondEncounter() != null, "the second encounter really started");
        secondBattle = BattleManager.current();
        List<Zombie> foreignBodies = level.getEntitiesOfClass(Zombie.class,
                new net.minecraft.world.phys.AABB(origin.offset(0, 0, 64)).inflate(8));
        check(!foreignBodies.isEmpty(), "the other encounter really has its own hostile");
        Zombie foreign = foreignBodies.get(0);
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        guardianHitEvents = 0;
        guardianHitEvents = 0;
        guardian.hurt(level.damageSources().mobAttack(foreign), 3.0F);
        check(guardianHitEvents == 1, "the foreign real mob-attack damage really reached this body: events="
                + guardianHitEvents + " hp=" + guardian.getHealth());
        check(thornsEvents == 0, "another encounter's unit may not use this body's spikes: " + thornsEvents);
        endSecondEncounter();

        // 8. Released: a body this encounter gave back keeps vanilla's spikes for whoever really hits it.
        battle.finish("fixture spikes done");
        battle = null;
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() == null,
                "the released body no longer answers this encounter's seam");
        clearHurtCooldowns(guardian);
        thornsEvents = 0;
        owner.attack(guardian);
        check(thornsEvents == 1, "a released body keeps vanilla's own spikes: " + thornsEvents);
        purgeAll();
    }

    private static boolean theGuardianIsStandingStill(Guardian guardian) {
        return !guardian.isMoving();
    }

    /**
     * Clear vanilla's own ten-tick hurt cooldown before a fixture melee.
     *
     * <p>Two separate native hits inside the same fixture tick would otherwise have the second one absorbed by the
     * cooldown the first one started, which says nothing about the spike rule under test. This removes only that
     * fixture-side precondition; the reaction's own gates are untouched.</p>
     */
    private static void clearHurtCooldowns(Guardian guardian) {
        guardian.invulnerableTime = 0;
        owner.invulnerableTime = 0;
    }

    // ------------------------------------------------------------------ S7/S8: fatigue and lifecycle

    /** The own-turn ordinal at which each observed fatigue pulse happened, in order. */
    private static final List<Integer> pulseTurns = new ArrayList<>();
    private static int pulseStep;
    private static int pulseGuard;
    private static int pulseOwnTurnsMark;
    private static int pulseDurationMark;

    private static net.minecraft.world.effect.MobEffectInstance fatigueOnOwner() {
        return owner.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
    }

    /**
     * S7: the elder's fatigue is that unit's own-turn pulse - the 1st, 7th and 13th own turn and nothing else.
     *
     * <p>The cadence is driven by handing the player's own turns over and letting the shared queue come round, so
     * every pulse is a real own turn of that elder. A player's own turns, another unit's turns and real waiting all
     * happen in between and must change nothing; the effect's own duration only moves when a complete own cycle of
     * the affected player is really charged.</p>
     */
    private static void elderFatiguePulsesOnItsOwnTurns() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        ElderGuardian elder = spawnElder(3.5D, -3, 0.5D);
        heldGuardianId = elder.getUUID();
        check(BattleManager.start(owner, true), "the fatigue encounter starts");
        battle = BattleManager.current();
        check(battle.isParticipant(owner), "the player is a real opposed member of this encounter");
        owner.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
        if (wire != null) wire.elderEffectPackets = 0;
        pulseTurns.clear();
        pulseStep = 0;
        pulseGuard = 0;
        // The first real pulse is put through vanilla's own rejection entry: the encounter keeps its native predicate,
        // and only a real addEffect success may be reported as one.
        rejectFatigueEffect = true;
        await(0, () -> watchFatiguePulse(0));
    }

    private static void watchFatiguePulse(int guard) {
        if (guard > CADENCE_LIMIT) {
            check(false, "the fatigue cadence never finished: ownTurns=" + battle.fixtureOwnTurns(heldGuardianId)
                    + " playerTurn=" + battle.isPlayerTurnNow() + " step=" + pulseStep);
            return;
        }
        int ownTurns = battle.fixtureOwnTurns(heldGuardianId);
        net.minecraft.world.effect.MobEffectInstance fatigue = fatigueOnOwner();
        switch (pulseStep) {
            case 0 -> {
                // The first own turn really pulsed - and that pulse is refused by vanilla's own effect entry.
                if (ownTurns < 1) { handOverForCadence(); await(0, () -> watchFatiguePulse(guard + 1)); return; }
                check(fatigueOnOwner() == null, "a native Applicable rejection really refused that pulse");
                check(wire == null || wire.elderEffectPackets == 0,
                        "and no native success game event was sent for a refused pulse: "
                                + (wire == null ? "no wire" : wire.elderEffectPackets));
                rejectFatigueEffect = false;
                pulseOwnTurnsMark = ownTurns;
                ownRemovalTurn = ownTurns;
                pulseStep = 1;
                await(0, () -> watchFatiguePulse(guard + 1));
            }
            case 1 -> {
                // The 7th own turn is the next pulse; turns two to six must not re-apply anything.
                if (ownTurns >= 7) {
                    check(fatigue != null, "the 7th own turn really pulsed the fatigue");
                    check(fatigue.getDuration() == 6000, "as a fresh vanilla instance: " + fatigue.getDuration());
                    check(fatigue.getAmplifier() == 2, "with vanilla's amplifier two: " + fatigue.getAmplifier());
                    check(wire == null || wire.elderEffectPackets == 1,
                            "exactly one native success game event for that one applied player: "
                                    + (wire == null ? "no wire" : wire.elderEffectPackets));
                    pulseTurns.add(7);
                    // Another encounter's member is not a candidate of this pulse at all.
                    check(secondEncounter() != null, "a second, independent encounter really starts");
                    secondBattle = BattleManager.current();
                    ServerPlayer other = secondOwner;
                    check(other != null && !battle.isParticipant(other),
                            "its player is not a member of this encounter");
                    check(other != null && other.getEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN) == null,
                            "so this elder's fatigue was never applied to another encounter's player");
                    endSecondEncounter();
                    pulseDurationMark = fatigue.getDuration();
                    owner.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                    check(fatigueOnOwner() == null, "a second legal removal really removed it");
                    ownRemovalTurn = ownTurns;
                    pulseOwnTurnsMark = ownTurns;
                    pulseStep = 2;
                    await(0, () -> watchFatiguePulse(guard + 1));
                    return;
                }
                if (ownTurns != pulseOwnTurnsMark) {
                    check(fatigueOnOwner() == null, "own turn " + ownTurns
                            + " did not re-apply the fatigue (the cadence is six own cycles)");
                    pulseOwnTurnsMark = ownTurns;
                }
                handOverForCadence();
                await(0, () -> watchFatiguePulse(guard + 1));
            }
            case 2 -> {
                // The affected player's own clock: one baseline own start, then one complete cycle of 200 ticks.
                if (ownTurns >= 13) {
                    check(fatigue != null, "the 13th own turn pulsed again after the removal");
                    pulseTurns.add(13);
                    check(pulseTurns.equals(List.of(7, 13)),
                            "the accepted cadence was exactly the 7th and 13th own turn (the 1st was refused): "
                                    + pulseTurns);
                    battle.finish("fixture fatigue cadence done");
                    battle = null;
                    purgeAll();
                    return;
                }
                if (ownTurns != pulseOwnTurnsMark) {
                    check(fatigueOnOwner() == null,
                            "own turn " + ownTurns + " is not a pulse turn and re-applied nothing");
                    pulseOwnTurnsMark = ownTurns;
                }
                handOverForCadence();
                await(0, () -> watchFatiguePulse(guard + 1));
            }
            default -> check(false, "unknown fatigue step " + pulseStep);
        }
    }

    /** The own-turn ordinal the fixture is currently skipping over between two pulses. */
    private static int ownRemovalTurn;

    private static void handOverForCadence() {
        if (battle == null || battle.finished()) return;
        if (battle.isPlayerTurnNow() && !battle.isBusy()) battle.endPlayerTurn();
    }

    /**
     * S7 (bounds): a stronger effect, another elder's own counter and the real exit clock.
     *
     * <p>Vanilla's own refresh predicate refuses to overwrite a stronger instance, so a pulse that is refused must
     * stay refused; a second elder counts its own own-turns rather than sharing the first one's; and a member that
     * leaves keeps the real remaining duration, which then runs on the ordinary outside clock again.</p>
     */
    private static void elderFatigueRespectsStrongerEffectsAndExit() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
        ElderGuardian first = spawnElder(3.5D, -3, 0.5D);
        heldGuardianId = first.getUUID();
        check(BattleManager.start(owner, true), "the fatigue bounds encounter starts");
        battle = BattleManager.current();
        pulseStep = 0;
        pulseOwnTurnsMark = 0;
        await(0, () -> watchFatigueBounds(0));
    }

    private static void watchFatigueBounds(int guard) {
        if (guard > CADENCE_LIMIT) {
            check(false, "the fatigue bounds case never finished: ownTurns=" + battle.fixtureOwnTurns(heldGuardianId)
                    + " step=" + pulseStep);
            return;
        }
        int ownTurns = battle.fixtureOwnTurns(heldGuardianId);
        switch (pulseStep) {
            case 0 -> {
                if (ownTurns < 1) { handOverForCadence(); await(0, () -> watchFatigueBounds(guard + 1)); return; }
                check(fatigueOnOwner() != null, "the first elder really pulsed");
                // A stronger instance of the same effect: vanilla's own predicate must refuse to overwrite it.
                owner.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                owner.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN, 5000, 3));
                pulseOwnTurnsMark = ownTurns;
                pulseDurationMark = 5000;
                pulseStep = 1;
                await(0, () -> watchFatigueBounds(guard + 1));
            }
            case 1 -> {
                // One non-pulse own turn and then the pulse turn itself: a stronger instance must survive both.
                if (ownTurns != pulseOwnTurnsMark && ownTurns == 2) {
                    net.minecraft.world.effect.MobEffectInstance strong = fatigueOnOwner();
                    check(strong != null && strong.getAmplifier() == 3,
                            "a non-pulse own turn leaves a stronger instance alone: "
                                    + (strong == null ? "gone" : strong.getAmplifier()));
                    check(strong != null && strong.getDuration() <= pulseDurationMark,
                            "and never pushes its own duration back up: "
                                    + (strong == null ? "gone" : strong.getDuration()));
                    pulseOwnTurnsMark = ownTurns;
                }
                if (ownTurns < 7) { handOverForCadence(); await(0, () -> watchFatigueBounds(guard + 1)); return; }
                net.minecraft.world.effect.MobEffectInstance strong = fatigueOnOwner();
                check(strong != null && strong.getAmplifier() == 3,
                        "the 7th own turn's pulse refused to overwrite a stronger instance: "
                                + (strong == null ? "gone" : strong.getAmplifier()));
                check(strong != null && strong.getDuration() <= pulseDurationMark,
                        "and left its own remaining time untouched: "
                                + (strong == null ? "gone" : strong.getDuration()));
                // The stronger effect is gone: now a second elder's own first turn has to pulse on its own counter.
                owner.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                check(fatigueOnOwner() == null, "the fixture removed the stronger effect legally");
                ElderGuardian second = spawnElder(-2.5D, -3, 0.5D);
                check(battle.tryAbsorb(second), "a second elder really joined this same encounter");
                secondElderId = second.getUUID();
                pulseStep = 2;
                await(0, () -> watchFatigueBounds(guard + 1));
            }
            case 2 -> {
                int secondTurns = battle.fixtureOwnTurns(secondElderId);
                if (secondTurns < 1) { handOverForCadence(); await(0, () -> watchFatigueBounds(guard + 1)); return; }
                check(fatigueOnOwner() != null,
                        "the second elder's own 1st turn pulsed, so the counter is that unit's own: "
                                + battle.fixtureOwnTurns(heldGuardianId) + " vs " + secondTurns);
                check(battle.fixtureOwnTurns(heldGuardianId) < 13,
                        "while the first elder was nowhere near its own 13th turn");
                pulseDurationMark = fatigueOnOwner().getDuration();
                pulseStep = 3;
                await(0, () -> watchFatigueBounds(guard + 1));
            }
            case 3 -> {
                // Leaving the encounter keeps the real remaining effect and hands it back to the ordinary clock.
                check(fatigueOnOwner() != null, "the effect is still there before the exit");
                battle.finish("fixture fatigue bounds done");
                battle = null;
                int before = fatigueOnOwner() == null ? -1 : fatigueOnOwner().getDuration();
                check(before > 0, "a member that left keeps its real remaining fatigue: " + before);
                pulseDurationMark = before;
                pulseStep = 4;
                await(40, () -> {
                    net.minecraft.world.effect.MobEffectInstance kept = fatigueOnOwner();
                    int after = kept == null ? -1 : kept.getDuration();
                    check(after > 0, "and the effect was not deleted by the exit: " + after);
                    check(after <= pulseDurationMark, "nor was its real remaining time pushed back up: "
                            + pulseDurationMark + " -> " + after);
                    // The battle no longer freezes that member's effect clock, which is the production question; the
                    // member's own tick then really costs one vanilla tick per tick again.
                    check(!BattleEffects.freezeVanillaTick(owner, kept),
                            "the battle no longer freezes that member's effect clock");
                    int beforeTick = kept.getDuration();
                    kept.tick(owner, () -> { });
                    check(kept.getDuration() == beforeTick - 1,
                            "one real vanilla effect tick really costs one tick again: "
                                    + beforeTick + " -> " + kept.getDuration());
                    owner.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN);
                    purgeAll();
                });
            }
            default -> check(false, "unknown fatigue bounds step " + pulseStep);
        }
    }

    private static UUID secondElderId;

    /**
     * S8: every exit of a running beam cleans up what it borrowed, and a failed launch gives back exactly what it
     * touched.
     */
    private static void beamLifecycleCleansUp() {
        // (a) the opponent leaves the body's reach while it is charging.
        beamCaseBody(false);
        resetBeamWatch("S8 target leaves", () -> { });
        await(0, () -> watchBeamAbort(0, "target leaves", () -> owner.setPos(
                origin.getX() + 400.5D, origin.getY(), origin.getZ() + 0.5D)));
    }

    /** S8 (b): the water under the charging body is drained, so the encounter has to let it go. */
    private static void beamStopsWhenTheWaterIsGone() {
        beamCaseBody(false);
        resetBeamWatch("S8 water gone", () -> { });
        await(0, () -> watchBeamAbort(0, "water gone", () -> {
            GuardianMovement.Node here = battle.fixtureGuardianHere(heldGuardianId);
            check(here != null, "the charging body really holds a water node to drain");
            GridPos cell = here.cell();
            for (int dy = -1; dy <= 1; dy++) {
                level.setBlock(new BlockPos(cell.x(), cell.y() + dy, cell.z()), Blocks.AIR.defaultBlockState(), 2);
            }
        }));
    }

    /** S8 (c): the encounter ends in the middle of a charge, so the native goal may not outlive it. */
    private static void beamStopsOnEncounterEnd() {
        beamCaseLabel = "S8 encounter ends";
        beamCaseBody(false);
        resetBeamWatch("S8 encounter ends", () -> { });
        await(0, () -> watchBeamEndsWithTheEncounter(0));
    }

    private static void watchBeamEndsWithTheEncounter(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the encounter-end case never reached a running beam");
            return;
        }
        if (battle.finished()) {
            check(false, "the encounter ended before a beam was running");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            beamFirstTick = guard;
            beamApBefore = view.ap();
            beamHealthBefore = owner.getHealth();
            beamSettlementsBefore = battle.fixtureGuardianBeamSettlements();
            Guardian body = viewBody(heldGuardianId);
            // A real end of the encounter while the charge is running: everything below is read afterwards, from the
            // same server tick, because the finish itself is the one commit point.
            battle.finish("fixture encounter ended mid beam");
            check(battle.finished(), "the encounter really ended");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore,
                    "an ended encounter settles nothing it did not finish");
            check(!battle.fixtureGuardianBeamBusy(heldGuardianId), "no beam state survives the end");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId),
                    "the native synced attack target was cleared by the native goal's own stop");
            check(body != null && ((TacticalGuardianAccess) body).tactical$attackGoal() != null,
                    "the body still owns vanilla's own goal object; this encounter just stopped driving it");
            check(body != null && !body.isMoving(), "and it is not left claiming to swim");
            check(owner.getHealth() == beamHealthBefore,
                    "an interrupted charge landed no native hit: " + beamHealthBefore + " -> " + owner.getHealth());
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchBeamEndsWithTheEncounter(guard + 1));
    }

    private static Guardian viewBody(UUID id) {
        for (Entity entity : spawned) {
            if (entity instanceof Guardian guardian && guardian.getUUID().equals(id)) return guardian;
        }
        return null;
    }

    /** A target-changing listener the failed-launch case installs, counting how often it really interfered. */
    private static int targetRedirects;

    @SubscribeEvent
    public static void redirectTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (!enabled || redirectTargetsFor == null) return;
        if (event.getNewAboutToBeSetTarget() == null) return;
        if (event.getEntity().getUUID().equals(redirectTargetsFor)) {
            targetRedirects++;
            event.setNewAboutToBeSetTarget(null);
        }
    }

    private static UUID redirectTargetsFor;

    /**
     * S8 (d): a launch whose target change is taken away by an event gives back exactly what it borrowed.
     *
     * <p>The native setter really ran, a real listener really replaced the target, and the launch must therefore
     * refuse: no goal is started, no price is charged, nothing stays busy, and the body keeps the attack state it had.
     * A fresh encounter then proves that no counter and no claim is inherited.</p>
     */
    private static void failedBeamLaunchRollsBack() {
        beamCaseLabel = "S8 failed launch";
        Guardian body = beamCaseBody(false);
        targetRedirects = 0;
        redirectTargetsFor = body.getUUID();
        ServerBattle.EnemyView view = battle.fixtureEnemy(body.getUUID());
        check(view != null, "the body is on the roster");
        beamApBefore = view.ap();
        beamSettlementsBefore = battle.fixtureGuardianBeamSettlements();
        await(0, () -> watchFailedLaunch(0));
    }

    private static void watchFailedLaunch(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the failed-launch case never finished (targetRedirects=" + targetRedirects + ")");
            return;
        }
        if (battle.finished()) {
            check(false, "the encounter ended before the launch could be refused");
            return;
        }
        if (targetRedirects > 0 && !battle.isBusy()) {
            // The native setter was interfered with, so the launch refused before starting any goal.
            ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
            check(view != null, "the body stayed on the roster");
            check(targetRedirects == 1, "the target change was really interfered with once: " + targetRedirects);
            check(!battle.fixtureGuardianBeamBusy(heldGuardianId), "no beam was launched");
            check(view.ap() == beamApBefore,
                    "the pool is never charged for a refused launch: " + beamApBefore + " -> " + view.ap());
            check(view.paidActions() == 0, "and no paid action was spent");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore,
                    "nothing was settled, so nothing was counted");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId),
                    "the synced attack target was not left pointing at anybody");
            check(((Guardian) viewBody(heldGuardianId)).getTarget() == null,
                    "and the body's original target really came back: it had none");
            check(battle.isPlayerTurnNow(), "the turn still moved on after the refusal");
            battle.finish("fixture failed launch done");
            battle = null;
            redirectTargetsFor = null;
            purgeAll();

            // Nothing of the ended encounter may be inherited by a fresh one.
            buildPit();
            owner.setPos(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 0.5D);
            Guardian fresh = spawnGuardian(3.5D, -3, 0.5D);
            check(BattleManager.start(owner, true), "a fresh encounter starts after the failed launch");
            battle = BattleManager.current();
            check(battle.fixtureEnemy(fresh.getUUID()) != null, "the fresh body is on the new roster");
            check(battle.fixtureGuardianBeamSettlements() == 0, "the new encounter inherits no settled beam");
            check(battle.fixtureOwnTurns(fresh.getUUID()) == 0, "and no own-turn count");
            check(battle.fixtureGuardianBeamCost(fresh.getUUID()) == 20, "and its quote starts at the base price");
            battle.finish("fixture fresh encounter done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchFailedLaunch(guard + 1));
    }

    /** Watchers that abort a running beam from the outside and then assert the whole cleanup. */
    private static void watchBeamAbort(int guard, String label, Runnable abort) {
        if (guard > SETTLE_LIMIT) {
            check(false, label + ": the case never finished (busy=" + battle.isBusy() + ")");
            return;
        }
        if (battle.finished()) {
            check(false, label + ": the encounter ended before the beam could be aborted");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, label + ": the body stayed on the roster");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamHealthBefore = owner.getHealth();
                beamApBefore = view.ap();
                beamPriceSeen = battle.fixtureGuardianBeamPrice(heldGuardianId);
                abort.run();
            }
            await(0, () -> watchBeamAbort(guard + 1, label, abort));
            return;
        }
        if (beamFirstTick >= 0) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(settled != null && settled.paidActions() == 0, label + ": an aborted beam charges no paid action");
            check(settled != null && settled.ap() == beamApBefore, label + ": and no AP: " + beamApBefore + " -> "
                    + (settled == null ? "gone" : settled.ap()));
            check(owner.getHealth() == beamHealthBefore, label + ": no native hit was landed on the opponent");
            check(!battle.isBusy() || battle.fixtureFalling(heldGuardianId),
                    label + ": no beam busy window is left behind; only that unit's own real descent may own the "
                            + "encounter (falling=" + battle.fixtureFalling(heldGuardianId) + ")");
            check(!battle.fixtureGuardianBeamBusy(heldGuardianId), label + ": no beam state survives");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId), label + ": the synced attack target is cleared");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore,
                    label + ": nothing was settled, so nothing was counted");
            if (!battle.finished()) {
                check(battle.isPlayerTurnNow() || battle.fixtureOwnTurns(heldGuardianId) > 0,
                        label + ": the turn really moved on");
            }
            battle.finish("fixture " + label + " done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchBeamAbort(guard + 1, label, abort));
    }

    // ------------------------------------------------------------------ B集中审查 R1-R4

    /** A second guardian's own goal, driven from inside another body's receipt scope on purpose. */
    private static Guardian nestedGuardian;
    private static net.minecraft.world.entity.ai.goal.Goal nestedGoal;
    private static Runnable nestedDrive;
    /** The two applied damage amounts one armoured beam really produced, recorded separately. */
    private static float magicEventAmount;
    private static float meleeEventAmount;
    /** Fixture switch for the native-effect rejection entry. */
    private static boolean rejectFatigueEffect;
    /** The configuration value this fixture found, so it is always put back. */
    private static int originalBeamConfig;

    @SubscribeEvent(receiveCanceled = true)
    public static void observeDamageParts(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!enabled || event.getEntity() != owner) return;
        if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC)) {
            magicEventAmount = event.getAmount();
        } else if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.MOB_ATTACK)) {
            meleeEventAmount = event.getAmount();
        }
        Runnable nested = nestedDrive;
        if (nested != null && fromGuardian(event)) {
            nestedDrive = null;
            nested.run();
        }
    }

    /** The one real native rejection entry for an effect: vanilla's own {@code addEffect} then answers false. */
    @SubscribeEvent
    public static void rejectFatigue(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable event) {
        if (!enabled || !rejectFatigueEffect) return;
        if (event.getEntity() != owner) return;
        if (!event.getEffectInstance().getEffect().is(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)) return;
        event.setResult(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
    }

    /**
     * B-R1(a): another guardian's real settlement on the same opponent, inside this drive's scope, fills nothing.
     *
     * <p>Both parts of the body under test are refused, so the only real hit on that opponent comes from the other
     * guardian. Without an identity binding that hit would complete the outer receipt and buy a beam that never hurt
     * anybody; with it, the outer settlement stays a refusal and costs nothing.</p>
     */
    private static void aNestedOtherGuardianCannotFillTheReceipt() {
        beamCaseLabel = "R1 nested other guardian";
        Guardian body = beamCaseBody(false);
        nestedGuardian = spawnGuardian(0.5D, -3, 0.5D);
        // Vanilla's own selector would drive that body's goal beside this fixture's drive; NoAI keeps this case about
        // one goal tick at a time, which is exactly the nesting under test.
        nestedGuardian.setNoAi(true);
        check(!battle.isParticipant(nestedGuardian),
                "the other body never joined this encounter, so it is somebody else's guardian");
        nestedGoal = NativeBeamBridge.installedGoal(nestedGuardian);
        check(nestedGoal != null, "the other guardian really owns its own native goal");
        check(NativeBeamBridge.start(nestedGuardian, nestedGoal, owner), "and it can really be pointed at the opponent");
        for (int i = 0; i < 89; i++) NativeBeamBridge.tick(nestedGuardian, nestedGoal, owner);
        check(NativeBeamBridge.chargeProgress(nestedGoal) == 79,
                "its own charge is one tick short of its settlement: " + NativeBeamBridge.chargeProgress(nestedGoal));
        damageFilter = event -> event.getEntity() == owner && fromGuardian(event);
        nestedDrive = () -> {
            Guardian other = nestedGuardian;
            net.minecraft.world.entity.ai.goal.Goal goal = nestedGoal;
            if (other == null || goal == null) return;
            // The encounter may have absorbed that body on the way here, and its own takeover clears the old target:
            // pointing it back at the opponent is the fixture's job, not the product's.
            other.setTarget(owner);
            check(other.getTarget() == owner, "the other guardian is really pointed at the same opponent");
            goal.tick();
        };
        float ownerBefore = owner.getHealth();
        resetBeamWatch("R1 nested other guardian", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(owner.getHealth() < ownerBefore,
                    "the other guardian's hit really landed on the same opponent: " + ownerBefore + " -> "
                            + owner.getHealth());
            check(settled != null && settled.paidActions() == 0,
                    "but it never completed this body's receipt: paid=" + (settled == null ? "?" : settled.paidActions()));
            check(settled != null && settled.ap() == beamApBefore,
                    "and this body paid nothing for a hit that was not its own: " + beamApBefore + " -> "
                            + (settled == null ? "?" : settled.ap()));
            check(!NativeBeamBridge.inScope(), "the receipt scope never survives the nested drive");
            nestedGuardian = null;
            nestedGoal = null;
            nestedDrive = null;
        });
        check(body != null, "the body under test exists");
        await(0, () -> watchBeam(0));
    }

    /**
     * The one side call this fixture arms: a physical attack by the <em>same</em> guardian on the <em>same</em>
     * opponent, made from inside the magic part's own damage listener.
     *
     * <p>That is the case a receipt built on "this actor, this target" cannot separate: the attacker and the receiver
     * are identical to the goal's own settlement, and the call happens while this drive's scope is open. Only the fact
     * that the goal's own physical call is the one physical call may decide it, so this bypass must leave the receipt
     * empty while its own damage really lands.</p>
     */
    private static boolean bypassPending;
    /** Whether the fixture's own side call is on the stack, so its hit is neither counted nor refused. */
    private static boolean inBypass;
    private static boolean bypassInnerSeen;
    private static boolean bypassHurt;
    private static boolean bypassThrew;
    private static UUID bypassActorId;
    private static Entity bypassReceiver;
    private static int magicPartsSeen;
    private static int physicalPartsSeen;

    private static boolean sameActorBypassFilter(
            net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (inBypass) {
            // The fixture's own side call: it has to land for real, and it is not one of the beam's native parts.
            bypassInnerSeen = true;
            return false;
        }
        if (event.getEntity() != owner || !fromGuardian(event)) return false;
        if (!event.getSource().is(net.minecraft.world.damagesource.DamageTypes.INDIRECT_MAGIC)) {
            physicalPartsSeen++;
            return true;
        }
        magicPartsSeen++;
        if (!bypassPending) return true;
        bypassPending = false;
        Mob actor = viewBody(heldGuardianId);
        if (actor == null) return true;
        bypassActorId = actor.getUUID();
        bypassReceiver = event.getEntity();
        inBypass = true;
        try {
            bypassHurt = actor.doHurtTarget(owner);
        } catch (RuntimeException failure) {
            bypassThrew = true;
        } finally {
            inBypass = false;
        }
        // Give vanilla's own physical part the same ground the bypass had, so its refusal is this case's decision and
        // not this case's own damage window.
        owner.invulnerableTime = 0;
        return true;
    }

    /**
     * B-R1(a) again, with the harder half: the bypassing body is the beam's own actor and the target is the same
     * opponent.
     *
     * <p>Both native parts are refused, so vanilla's two calls both answer false. In the same settlement tick, from
     * inside the magic part's listener, this fixture makes the same guardian attack the same opponent once more -
     * a real {@code Mob.doHurtTarget} that lands real damage. A receipt that only compares actor and target would take
     * that side hit as the beam's physical part and buy a beam that never hurt anybody; the inner-hurt tap therefore
     * only answers for the goal's own physical call. Nothing about the damage itself is touched: the side hit lands,
     * the beam still pays nothing, and the scope is closed when the tick ends.</p>
     */
    private static void aSameActorBypassCannotFillTheReceipt() {
        beamCaseLabel = "R1 same actor bypass";
        beamCaseBody(false);
        bypassPending = true;
        inBypass = false;
        bypassInnerSeen = false;
        bypassHurt = false;
        bypassThrew = false;
        bypassActorId = null;
        bypassReceiver = null;
        magicPartsSeen = 0;
        physicalPartsSeen = 0;
        damageFilter = GuardianSelfTest::sameActorBypassFilter;
        float ownerBefore = owner.getHealth();
        resetBeamWatch("R1 same actor bypass", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(!bypassThrew, "the fixture's own side call really returned instead of throwing");
            check(bypassHurt, "the same guardian's side attack on the same opponent really landed");
            check(heldGuardianId.equals(bypassActorId),
                    "and it was that very body's attack, not another guardian's");
            check(bypassReceiver == owner, "against this turn's own opponent");
            check(bypassInnerSeen, "its inner hurt really reached the damage event");
            check(owner.getHealth() < ownerBefore,
                    "so the opponent really lost health to it: " + ownerBefore + " -> " + owner.getHealth());
            check(magicPartsSeen == 1, "the native magic part really reached the damage event and was refused");
            check(physicalPartsSeen == 1,
                    "and so did the goal's own physical part, refused by this case: " + physicalPartsSeen);
            check(settled != null && settled.paidActions() == 0,
                    "but the beam still buys nothing: paid=" + (settled == null ? "?" : settled.paidActions()));
            check(settled != null && settled.ap() == beamApBefore,
                    "and its own pool was never charged: " + beamApBefore + " -> "
                            + (settled == null ? "?" : settled.ap()));
            check(!NativeBeamBridge.inScope(), "the receipt scope never survives the side attack");
            bypassPending = false;
            inBypass = false;
        });
        await(0, () -> watchBeam(0));
    }

    /**
     * B-R1(b): the goal this action launched is removed from the body's selector mid-charge.
     *
     * <p>A running action may never keep driving an object the body no longer owns, and it may never pick a
     * replacement: the beam ends, nothing is damaged, nothing is charged, and the native presentation is cleared by
     * this encounter rather than assumed to have been cleared by a selector that never owned the run.</p>
     */
    private static void aRemovedGoalStopsTheBeam() {
        beamCaseLabel = "R1 goal removed";
        beamCaseBody(false);
        resetBeamWatch("R1 goal removed", () -> { });
        await(0, () -> watchRemovedGoal(0));
    }

    private static void watchRemovedGoal(int guard) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the removed-goal case never reached a running beam");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            check(false, "the body stayed on the roster");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamHealthBefore = owner.getHealth();
                beamApBefore = view.ap();
                Guardian body = viewBody(heldGuardianId);
                net.minecraft.world.entity.ai.goal.Goal goal = NativeBeamBridge.installedGoal(body);
                check(goal != null, "the charging body really owns the goal this action launched");
                ((com.tactical.neoforge.mixin.MobGoalSelectorAccess) body).tactical$goalSelector().removeGoal(goal);
                check(NativeBeamBridge.installedGoal(body) == null, "and it is really gone from that body's selector");
            }
            await(0, () -> watchRemovedGoal(guard + 1));
            return;
        }
        if (beamFirstTick >= 0) {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(settled != null && settled.paidActions() == 0, "a beam on a removed goal spends no paid action");
            check(settled != null && settled.ap() == beamApBefore, "and no AP: " + beamApBefore + " -> "
                    + (settled == null ? "?" : settled.ap()));
            check(owner.getHealth() == beamHealthBefore, "no native hit was landed by a goal the body does not own");
            check(!battle.fixtureGuardianBeamBusy(heldGuardianId), "no beam state survives");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId),
                    "and the synched attack target really is cleared, not just forgotten by the field");
            check(!battle.isBusy(), "the encounter is not left busy by the removed goal");
            battle.finish("fixture goal removed done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchRemovedGoal(guard + 1));
    }

    /**
     * B-R2(a): the native {@code stop()} itself really throws, after a real hit has been committed.
     *
     * <p>The throw is produced inside vanilla's own {@code GuardianAttackGoal.stop()}: its last statement triggers a
     * stroll goal the fixture has cleared, so the exception happens after the synched attack target was cleared and
     * after the target was dropped. The committed hit must still stand exactly once, the borrowed swimming bit must
     * still be returned, nothing may stay busy, and the turn must still move on. NeoForge's bus swallows listener
     * exceptions, so a listener-based throw could never prove this: the failure has to come from the native code
     * itself.</p>
     */
    private static void aThrowingNativeStopStillChargesOnce() {
        beamCaseLabel = "R2 throwing native stop";
        Guardian body = beamCaseBody(false);
        net.minecraft.world.entity.ai.goal.RandomStrollGoal stroll =
                ((com.tactical.neoforge.mixin.GuardianNativeInvoker) body).tactical$randomStrollGoal();
        check(stroll != null, "the body really has the stroll goal vanilla's attack goal ends with");
        resetBeamWatch("R2 throwing native stop", () -> { });
        await(0, () -> watchThrowingStop(0, body, stroll));
    }

    /** How many native stop failures that encounter had already recorded. */
    private static int beamStopFailuresBefore;

    private static void watchThrowingStop(int guard, Guardian body,
                                          net.minecraft.world.entity.ai.goal.RandomStrollGoal stroll) {
        if (guard > SETTLE_LIMIT) {
            ((com.tactical.neoforge.mixin.GuardianNativeInvoker) body).tactical$randomStrollGoal(stroll);
            check(false, "the throwing-stop case never finished (busy=" + battle.isBusy() + ")");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            ((com.tactical.neoforge.mixin.GuardianNativeInvoker) body).tactical$randomStrollGoal(stroll);
            check(false, "the body stayed on the roster");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamApBefore = view.ap();
                beamHealthBefore = owner.getHealth();
                beamStopFailuresBefore = battle.fixtureGuardianBeamStopFailures();
                // The native stop ends with this goal's trigger; clearing it makes that very statement throw.
                ((com.tactical.neoforge.mixin.GuardianNativeInvoker) body).tactical$randomStrollGoal(null);
            }
            await(0, () -> watchThrowingStop(guard + 1, body, stroll));
            return;
        }
        if (beamFirstTick >= 0) {
            ((com.tactical.neoforge.mixin.GuardianNativeInvoker) body).tactical$randomStrollGoal(stroll);
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(battle.fixtureGuardianBeamStopFailures() == beamStopFailuresBefore + 1,
                    "the native stop really threw once: failures=" + battle.fixtureGuardianBeamStopFailures());
            check(settled != null && settled.paidActions() == 1,
                    "a real hit is charged once even though the native stop threw: paid="
                            + (settled == null ? "?" : settled.paidActions()));
            check(settled != null && settled.ap() == beamApBefore - 20,
                    "and exactly one frozen price: " + beamApBefore + " -> " + (settled == null ? "?" : settled.ap()));
            check(owner.getHealth() < beamHealthBefore, "the hit really landed before the throw");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore + 1,
                    "one settlement, no retry after the exception");
            check(!NativeBeamBridge.inScope(), "and the receipt scope is closed");
            check(!body.isMoving(), "the borrowed swimming bit was returned in the finally");
            check(!battle.isBusy() && !battle.fixtureGuardianBeamBusy(heldGuardianId),
                    "no busy window survives the throwing stop");
            check(!battle.fixtureGuardianSyncedTarget(heldGuardianId), "and the synched attack target is cleared");
            battle.finish("fixture throwing native stop done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchThrowingStop(guard + 1, body, stroll));
    }

    /** B-R2(b): the acting body really dies mid-charge, after its roster entry is already gone. */
    private static void aDeadActorStillGivesItsStateBack() {
        beamCaseLabel = "R2 dead actor";
        Guardian body = beamCaseBody(false);
        resetBeamWatch("R2 dead actor", () -> { });
        await(0, () -> watchDeadActor(0, body));
    }

    private static void watchDeadActor(int guard, Guardian body) {
        if (guard > SETTLE_LIMIT) {
            check(false, "the dead-actor case never finished (busy=" + battle.isBusy() + ")");
            return;
        }
        if (battle.fixtureGuardianBeamBusy(body.getUUID())) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamHealthBefore = owner.getHealth();
                body.kill();
            }
            await(0, () -> watchDeadActor(guard + 1, body));
            return;
        }
        if (beamFirstTick >= 0) {
            check(battle.fixtureEnemy(body.getUUID()) == null, "the dead body really left the roster");
            check(!battle.fixtureGuardianBeamBusy(body.getUUID()), "and no beam state survives on it");
            check(!body.hasActiveAttackTarget(),
                    "the native synched attack target was cleared even though the roster entry was already gone");
            check(!body.isMoving(), "and the borrowed swimming bit was returned");
            check(((TacticalGuardianAccess) body).tactical$motionHandler() == null,
                    "and no motion seam is left on the released body");
            check(battle.fixtureGuardianBeamSettlements() == beamSettlementsBefore,
                    "a beam that never reached its settlement charged nothing");
            check(battle.finished() || battle.isPlayerTurnNow() || !battle.isBusy(),
                    "and the encounter did not stay busy on a dead body");
            battle.finish("fixture dead actor done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(body.getUUID());
        await(0, () -> watchDeadActor(guard + 1, body));
    }

    /** B-R3: a body vanilla was already charging with is taken out of that old attack on admission. */
    private static void admissionClearsTheOldUnpaidAttack() {
        purgeAll();
        buildPit();
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        Guardian guardian = spawnGuardian(2.5D, -3, 0.5D);
        net.minecraft.world.entity.ai.goal.Goal goal = NativeBeamBridge.installedGoal(guardian);
        check(goal != null, "the body owns its native attack goal before admission");
        GuardianMotion.moving(guardian, true);
        check(NativeBeamBridge.start(guardian, goal, owner), "and vanilla really starts charging at a real opponent");
        for (int i = 0; i < 20; i++) NativeBeamBridge.tick(guardian, goal, owner);
        check(guardian.hasActiveAttackTarget(), "the body is visibly mid-beam before the encounter takes it");
        check(GuardianNativePose.rawAttackTargetId(guardian) == owner.getId(),
                "with the raw synched id a client would draw from");
        check(guardian.isMoving(), "and its own swimming bit says it is moving");
        check(NativeBeamBridge.chargeProgress(goal) > 0,
                "and its own charge counter has advanced: " + NativeBeamBridge.chargeProgress(goal));

        heldGuardianId = guardian.getUUID();
        check(BattleManager.start(owner, true), "the encounter really admits that mid-charge body");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(guardian.getUUID()) != null, "the body is on the roster");
        check(!guardian.hasActiveAttackTarget(),
                "a successful takeover really cleared the old synched attack target");
        check(GuardianNativePose.rawAttackTargetId(guardian) == 0, "the raw id is really zero, not just unread");
        check(!guardian.isMoving(), "and the body is not left claiming to swim");
        float ownerBefore = owner.getHealth();
        int settlementsBefore = battle.fixtureGuardianBeamSettlements();
        await(30, () -> {
            check(owner.getHealth() == ownerBefore,
                    "no unpaid old beam settled during the controlled wait: " + ownerBefore + " -> " + owner.getHealth());
            check(battle.fixtureGuardianBeamSettlements() == settlementsBefore,
                    "and nothing was settled at all in that wait");
            battle.finish("fixture admission takeover done");
            battle = null;
            purgeAll();
        });
    }

    /** B-R3 (rollback): a failed admission gives back the moving bit, the raw attack id and the charge counter. */
    private static void aFailedAdmissionRestoresTheNativePose() {
        purgeAll();
        buildPit();
        Zombie keeper = spawn(EntityType.ZOMBIE, 3.5D, 0, 5.5D, false, false);
        keeper.setTarget(owner);
        // The opponent stands where the water body really has a native line of sight, so the old charge can advance.
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        check(BattleManager.start(owner, true), "an encounter starts with the keeper");
        battle = BattleManager.current();
        Guardian guardian = spawnGuardian(2.5D, -3, 0.5D);
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) guardian).tactical$persistenceRequired(false);
        guardian.setNoAi(false);
        net.minecraft.world.entity.ai.goal.Goal goal = NativeBeamBridge.installedGoal(guardian);
        check(goal != null, "the body under test owns its native goal");
        GuardianMotion.moving(guardian, true);
        check(NativeBeamBridge.start(guardian, goal, owner), "and it is really mid-charge at a real opponent");
        for (int i = 0; i < 25; i++) NativeBeamBridge.tick(guardian, goal, owner);
        int rawBefore = GuardianNativePose.rawAttackTargetId(guardian);
        int progressBefore = NativeBeamBridge.chargeProgress(goal);
        check(rawBefore == owner.getId() && progressBefore > 0,
                "the pose it will lose is real: id=" + rawBefore + " progress=" + progressBefore);
        Vec3 position = guardian.position();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        check(!battle.tryAbsorb(guardian), "the faulty admission is refused");
        check(battle.fixtureEnemy(guardian.getUUID()) == null, "and the body is not on the roster");
        check(guardian.isMoving(), "the moving bit it really had came back: moving=" + guardian.isMoving());
        check(GuardianNativePose.rawAttackTargetId(guardian) == rawBefore,
                "and the raw attack id it really had: " + rawBefore + " -> "
                        + GuardianNativePose.rawAttackTargetId(guardian));
        check(NativeBeamBridge.chargeProgress(goal) == progressBefore,
                "and its own charge counter: " + progressBefore + " -> " + NativeBeamBridge.chargeProgress(goal));
        check(guardian.getTarget() == owner, "and the target it really held");
        check(!guardian.isNoAi(), "the borrowed AI flag was given back");
        check(!guardian.isPersistenceRequired(), "with the borrowed persistence flag");
        check(((TacticalGuardianAccess) guardian).tactical$motionHandler() == null,
                "no motion seam is left on a body the encounter refused");
        check(guardian.position().distanceToSqr(position) < 1.0E-6D, "and it is where it was");
        check(ServerBattle.pendingFaults() == 0, "the injected fault really fired");
        check(BattleManager.ownerOf(guardian.getUUID()) == null, "and the claim was released");
        battle.finish("fixture failed admission pose done");
        battle = null;
        purgeAll();
    }

    /** B-R4(1a): the session really quotes and charges the configured price plus this actor's own surcharge. */
    private static void theSessionQuotesItsOwnConfiguredPrice() {
        beamCaseLabel = "R4 configured price";
        configuredPaidInjected = false;
        originalBeamConfig = com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.get();
        check(originalBeamConfig == 20, "the default base price is the ruleset's twenty: " + originalBeamConfig);
        com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.set(24);
        Guardian body = beamCaseBody(false);
        check(battle.fixtureGuardianBeamCost(body.getUUID()) == 24,
                "one beam at no paid action is quoted at the configured base: "
                        + battle.fixtureGuardianBeamCost(body.getUUID()));
        resetBeamWatch("R4 configured price", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(beamPriceSeen == 30, "the frozen price is the quoted thirty: " + beamPriceSeen);
            check(settled != null && settled.ap() == beamApBefore - 30,
                    "and exactly that much was charged: " + beamApBefore + " -> "
                            + (settled == null ? "?" : settled.ap()));
            check(settled != null && settled.paidActions() == 2,
                    "the paid counter advanced exactly once from one: "
                            + (settled == null ? "?" : settled.paidActions()));
            check(!beamOwnerCharged, "the other side's resources were never charged");
        });
        await(0, () -> watchConfiguredPriceBeam(0));
    }

    /** Whether this case already put the actor on its first paid action inside its own turn. */
    private static boolean configuredPaidInjected;

    private static void watchConfiguredPriceBeam(int guard) {
        if (guard > SETTLE_LIMIT) {
            com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.set(originalBeamConfig);
            check(false, "the configured-price case never settled");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(heldGuardianId);
        if (view == null) {
            com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.set(originalBeamConfig);
            check(false, "the body stayed on the roster");
            return;
        }
        if (!configuredPaidInjected && battle.fixtureOwnTurns(heldGuardianId) >= 1
                && !battle.isPlayerTurnNow() && !battle.isBusy()) {
            // The own turn has really begun (which resets the counter), so this is the only window in which a prior
            // successful action can really be observed by the quote.
            check(battle.fixtureEnemyPaid(heldGuardianId, 1), "the fixture puts this actor on its first paid action");
            check(battle.fixtureGuardianBeamCost(heldGuardianId) == 30,
                    "so the next quote carries the shared surcharge: "
                            + battle.fixtureGuardianBeamCost(heldGuardianId));
            configuredPaidInjected = true;
            await(0, () -> watchConfiguredPriceBeam(guard + 1));
            return;
        }
        if (battle.fixtureGuardianBeamBusy(heldGuardianId)) {
            if (beamFirstTick < 0) {
                beamFirstTick = guard;
                beamApBefore = view.ap();
                beamOwnerApBefore = battle.currentAp(owner);
                beamHealthBefore = owner.getHealth();
                beamPriceSeen = battle.fixtureGuardianBeamPrice(heldGuardianId);
                // Changing the configuration mid-charge must not touch this beam's frozen price.
                com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.set(99);
                check(battle.fixtureGuardianBeamPrice(heldGuardianId) == beamPriceSeen,
                        "a configuration change after the launch does not re-price this beam: "
                                + beamPriceSeen + " -> " + battle.fixtureGuardianBeamPrice(heldGuardianId));
            }
            beamOwnerCharged |= battle.currentAp(owner) < beamOwnerApBefore;
            await(0, () -> watchConfiguredPriceBeam(guard + 1));
            return;
        }
        if (beamFirstTick >= 0) {
            beamCaseAssert.run();
            com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.set(originalBeamConfig);
            check(com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.get() == originalBeamConfig,
                    "the configuration is restored exactly as it was: "
                            + com.tactical.neoforge.TacticalConfig.INSTANCE.guardianBeamApCost.get());
            battle.finish("fixture configured price done");
            battle = null;
            purgeAll();
            return;
        }
        awaitOwnTurn(heldGuardianId);
        await(0, () -> watchConfiguredPriceBeam(guard + 1));
    }

    /**
     * B-R4(1b): one real beam on a harder difficulty and an armoured opponent, with the two native returns, the two
     * applied event amounts, health and the resources recorded separately.
     */
    private static void aHardArmouredBeamKeepsTheNativeNumbers() {
        beamCaseLabel = "R4 hard armoured";
        Guardian body = beamCaseBody(false);
        check(serverRef != null, "the fixture has its own server");
        net.minecraft.world.Difficulty previous = level.getDifficulty();
        serverRef.setDifficulty(net.minecraft.world.Difficulty.HARD, true);
        // Armour-class defence is the ARMOR attribute the whole damage chain really reads; the fixture sets that
        // directly instead of relying on a fake player's equipment pass.
        net.minecraft.world.entity.ai.attributes.AttributeInstance armour =
                owner.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
        check(armour != null, "the opponent really has an armour attribute");
        double previousArmour = armour.getBaseValue();
        armour.setBaseValue(8.0D);
        magicEventAmount = 0.0F;
        meleeEventAmount = 0.0F;
        resetBeamWatch("R4 hard armoured", () -> {
            ServerBattle.EnemyView settled = battle.fixtureEnemy(heldGuardianId);
            check(settled != null && settled.paidActions() == 1, "the armoured beam is one paid action");
            check(magicEventAmount > 0.0F, "the magical part really reached the damage chain: " + magicEventAmount);
            check(meleeEventAmount > 0.0F, "and so did the physical part: " + meleeEventAmount);
            check(owner.getHealth() < beamHealthBefore,
                    "the armoured opponent's health really moved: " + beamHealthBefore + " -> " + owner.getHealth());
            check(owner.getArmorValue() > 0, "the opponent really wore armour: " + owner.getArmorValue());
            check(settled != null && settled.ap() == beamApBefore - beamPriceSeen,
                    "and exactly the frozen price was charged: " + beamApBefore + " -> "
                            + (settled == null ? "?" : settled.ap()));
            Tactical.LOGGER.info("[Tactical] GUARDIAN R4 hard armoured: magic={} melee={} hp={} -> {} "
                            + "armour={} ap={} -> {} paid={}", magicEventAmount, meleeEventAmount, beamHealthBefore,
                    owner.getHealth(), owner.getArmorValue(), beamApBefore, settled == null ? "?" : settled.ap(),
                    settled == null ? "?" : settled.paidActions());
            serverRef.setDifficulty(previous, true);
            armour.setBaseValue(previousArmour);
            check(level.getDifficulty() == previous, "the fixture put the difficulty back: " + level.getDifficulty());
        });
        check(body != null, "the body under test exists");
        await(0, () -> watchBeam(0));
    }

    /** The journal tail, so a refused action names its own reason in a failure message. */
    private static final class Wire extends Connection {
        io.netty.channel.embedded.EmbeddedChannel channel;
        final List<String> log = new ArrayList<>();

        Wire() {
            super(PacketFlow.SERVERBOUND);
        }

        /** How many native elder-effect game events this member's connection really received. */
        int elderEffectPackets;

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundGameEventPacket event
                    && event.getEvent() == net.minecraft.network.protocol.game.ClientboundGameEventPacket
                            .GUARDIAN_ELDER_EFFECT) {
                elderEffectPackets++;
            }
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof BattleLogPacket next) {
                log.clear();
                for (BattleLogPacket.Entry entry : next.history()) {
                    log.add(entry.category() + "=" + entry.text().getString());
                }
            }
        }
    }
}
