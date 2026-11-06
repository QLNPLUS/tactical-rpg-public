package com.tactical.neoforge.battle;

import com.tactical.core.combat.BattleRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Real loaded-server fixture for the drowned trident: {@code -Dtactical.selftest=drowned}.
 *
 * <p>Every reading comes from a booted dedicated server: a real world with a real pool, a real player body, a
 * real drowned and its own native {@code performRangedAttack}. Nothing here simulates the ruleset - the point
 * of the fixture is what the product really did. The stages follow the frozen D1-D7 acceptance groups.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class DrownedSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer stranger;
    /** A founder that has never fought before, so a stale claim cannot decide the dual-encounter case. */
    private static ServerPlayer stranger2;
    private static Drowned drowned;
    private static Cow bystander;
    private static ArmorStand nonLiving;
    private static ServerBattle battle;

    private static final List<BlockPos> forced = new ArrayList<>();

    /** D1 hand rules. */
    private static boolean offHandRefused;
    private static boolean rodRefused;
    private static int meleeHitsByDrowned;

    /** D2 the native arc. */
    private static int tridentsJoined;
    private static int joinsAtScenarioStart;
    private static Vec3 spawnPoint;
    private static boolean spawnInWater;
    private static final List<Double> airSpeed = new ArrayList<>();
    private static final List<Double> waterSpeed = new ArrayList<>();
    private static final List<Double> launchSpeed = new ArrayList<>();
    private static double scenarioLaunch = -1.0D;
    private static double scenarioLaunchHorizontal = -1.0D;
    private static float rawDamage = -1.0F;
    private static float appliedDamage = -1.0F;
    private static boolean damageFromDrowned;
    private static boolean tridentWasOwnerThrown;

    /** D3 price and conservation. */
    private static int apAtStart = -1;
    private static int apLowest = Integer.MAX_VALUE;
    private static int ownerApBefore = -1;
    private static boolean tridentPickable;
    private static String drownedHandBefore = "";
    private static String ownerHandBefore = "";
    private static final List<String> ownerInventory = new ArrayList<>();
    private static boolean cancelAllJoins;
    private static int cancelledJoins;

    /** D4 real water. */
    private static final List<GridPos> visitedCells = new ArrayList<>();
    private static double drift = -1.0D;
    private static boolean poseWhileInWater;
    private static boolean poseAfterRelease;
    private static boolean dryShoreEdge;
    private static boolean drainedCellHoldsOnFloor;

    private static float bystanderHealthAtStart = -1.0F;

    /** D5/D6 lifecycle and the collision rule. */
    private static int markedWhileFlying = -1;
    private static boolean markReleasedAfterSettle;
    private static int refusedImpacts;
    private static boolean markSurvivedRefusal;
    private static int legalCancellations;
    private static int nonLivingRefusals;
    private static int otherEncounterRefusals;


    /** D7 cover. */
    private static int noShotRows;

    /** Extra evidence added for the review's fix table (R1-R5). */
    private static double r1WaterY = -1.0D;
    private static boolean r1ReachedLand;
    private static boolean r1LandFeetInteger;
    private static boolean r1LandStandable;
    private static boolean r1BlockedRouteStopped;
    private static boolean r1NoTunnelling;
    private static boolean r2UnloadedRefused;
    private static boolean r2StillUnloaded;
    private static boolean r3LeftoverCleared;
    private static boolean r3PoseDuringFlight;
    /** Durable reading for the summary: the per-scenario flag is reset between scenarios. */
    private static boolean poseDuringFlightSeen;
    private static boolean r3PoseClearedAfter;
    private static boolean r3RestoredAfterFailedAdmission;
    private static boolean r4LateEnchantRefused;
    private static boolean r4ZeroShotExceptionFree;
    private static boolean r4PostJoinExceptionCharged;
    private static boolean r5TimeoutReclaimed;
    private static boolean r5OwnerExitCleaned;
    private static boolean r5FinishCleaned;
    private static boolean r5LegalCancelThenIllegal;
    private static boolean r5OtherEncounterCollision;
    private static int r5OtherHealthBefore = -1;
    private static double zoneGapBlocks = -1.0D;
    private static double nearestForeign = -1.0D;
    private static boolean reachesForeign;
    private static GridPos startCell;
    private static boolean throwSeen;
    private static boolean wallPlaced;
    private static int d7ScenarioTridents;
    private static int d6ForeignRefusals;
    private static int apAfterThrowAt = -1;
    private static int throwTick;
    private static boolean r5OtherOwnershipKept;

    /** Fixture switches for the join-path cases. */
    private static boolean enchantOnJoin;
    private static boolean throwOnJoin;
    private static boolean markedDuringJoin;

    /** Readings captured at the moment each group was checked, so the final line cannot print reset state. */
    private static double r2Launch = -1.0D;
    private static float r2Raw = -1.0F;
    private static float r2Applied = -1.0F;
    private static double r2AirRatio = -1.0D;
    private static int r3ApStart = -1;
    private static int r3ApLow = -1;
    private static int r3Cancelled = -1;
    private static boolean r4Pose;
    private static double r4Drift = -1.0D;
    private static boolean r4SpawnInWater;
    private static double r4WaterRatio = -1.0D;
    private static boolean r4Shore;
    private static boolean r4Drained;
    private static int r5Marked = -1;
    private static boolean r5Released;
    private static boolean r5Leftover;
    private static int r6Refusals = -1;
    private static int r6NonLiving = -1;
    private static int r6Other = -1;
    private static boolean r6MarkSurvived;
    private static boolean r7RefusedRow;

    /** Scenario timing: the queue may keep the player's slot for a while before the enemy's turn is due. */
    private static int scenarioStart;
    private static boolean sawEnemyPhase;
    private static boolean turnHanded;
    private static final int ENEMY_TURN_BUDGET_TICKS = 200;

    private DrownedSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "drowned".equals(System.getProperty("tactical.selftest"));
    }

    /** Every real trident join, and the fixture-only cancellation used by D3. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!enabled || level == null || event.getLevel() != level) return;
        if (!(event.getEntity() instanceof ThrownTrident trident)) return;
        if (drowned == null || trident.getOwner() != drowned) return;
        if (cancelAllJoins) {
            cancelledJoins++;
            event.setCanceled(true);
            return;
        }
        tridentsJoined++;
        if (spawnPoint == null) {
            spawnPoint = trident.position();
            // The entity's own cached swim flag is not updated until its first fluid pass, so the world block at
            // the real spawn point is what decides whether the throw began under water.
            spawnInWater = level.getFluidState(BlockPos.containing(spawnPoint))
                    .is(net.minecraft.tags.FluidTags.WATER);
            scenarioLaunch = trident.getDeltaMovement().length();
            scenarioLaunchHorizontal = trident.getDeltaMovement().horizontalDistance();
            launchSpeed.add(scenarioLaunch);
            tridentPickable = trident.isPickable();
        }
        lastTrident = trident;
    }

    private static ThrownTrident lastTrident;

    /**
     * A later listener on the same join: it runs after the product's own HIGH listener, which is exactly the danger
     * the review named - an enchantment added during the transaction would otherwise be accepted and could then
     * trigger channelling or loyalty effects this ruleset excludes.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoinLate(EntityJoinLevelEvent event) {
        if (!enabled || !(event.getEntity() instanceof ThrownTrident trident)) return;
        // A later listener already sees this encounter's rule on the entity, before the native call returns.
        if (!markedDuringJoin) markedDuringJoin = DrownedTridents.marked(trident);
        if (enchantOnJoin) {
            trident.getPickupItemStackOrigin().enchant(
                    level.registryAccess().holderOrThrow(net.minecraft.world.item.enchantment.Enchantments.LOYALTY), 1);
        }
        if (throwOnJoin) {
            throw new IllegalStateException("fixture: a later join listener failed after the entity was added");
        }
    }

    /** The real collision entry: refusals, and the fixture-only cancellation of a legal impact. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!enabled || level == null || drowned == null) return;
        if (!(event.getProjectile() instanceof ThrownTrident trident)) return;
        if (trident.getOwner() != drowned) return;
        HitResult hit = event.getRayTraceResult();
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
        if (!(hit instanceof EntityHitResult entityHit)) return;
        Entity body = entityHit.getEntity();
        boolean legal = battle != null && battle.acceptsTrident(drowned.getUUID(), body);
        if (!legal) {
            refusedImpacts++;
            if (body == nonLiving) nonLivingRefusals++;
            if (BattleManager.containing(body) != null && BattleManager.containing(body) != battle) {
                otherEncounterRefusals++;
            }
            if (DrownedTridents.marked(trident)) markSurvivedRefusal = true;
            require(event.isCanceled(), "a refused body is refused at the collision entry");
            return;
        }
        if (legalImpactCancelled) {
            legalCancellations++;
            event.setCanceled(true);
        }
    }

    private static boolean legalImpactCancelled;

    /**
     * The native amount before any reduction: this is the number the trident's own {@code onHitEntity} passed to
     * {@code hurt}, kept apart from what the member finally lost.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!enabled || owner == null || event.getEntity() != owner) return;
        if (!(event.getSource().getDirectEntity() instanceof ThrownTrident)) return;
        nativeAmount = event.getAmount();
    }

    private static float nativeAmount = -1.0F;

    /** Authoritative damage the member really took, and from which native source. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || owner == null || drowned == null || event.getEntity() != owner) return;
        var source = event.getSource();
        if (source.getDirectEntity() instanceof ThrownTrident) {
            rawDamage = event.getOriginalDamage();
            appliedDamage = event.getNewDamage();
            damageFromDrowned = source.getEntity() == drowned;
            tridentWasOwnerThrown = source.getDirectEntity() != null;
            return;
        }
        if (source.getEntity() == drowned && source.getDirectEntity() == drowned) meleeHitsByDrowned++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            require(ticks < 16_000, "drowned fixture completion deadline" + diagnostics());
            sampleFlight();
            switch (stage) {
                case 0 -> { setup(event.getServer()); stage = 1; }
                case 1 -> { if (ticks < 100) return; beginLandThrow(); stage = 2; }
                case 2 -> { if (driveTurn()) return; checkLandThrow(); nextScenario(); beginOffHandOnly(); stage = 3; }
                case 3 -> { if (driveTurn()) return; checkOffHandOnly(); nextScenario(); beginFishingRod(); stage = 4; }
                case 4 -> { if (driveTurn()) return; checkFishingRod(); nextScenario(); beginDisarmed(); stage = 5; }
                case 5 -> { if (driveTurn()) return; checkDisarmed(); nextScenario(); beginCancelledJoin(); stage = 6; }
                case 6 -> { if (driveTurn()) return; checkCancelledJoin(); nextScenario(); beginWater(); stage = 7; }
                case 7 -> { if (driveWaterTurn()) return; checkWater(); nextScenario(); beginBystander(); stage = 8; }
                case 8 -> { if (driveTurn()) return; checkBystander(); nextScenario(); beginCover(); stage = 9; }
                case 9 -> { if (driveTurn()) return; checkCover(); checkOtherEncounterCase(); stage = 10; }
                case 10 -> { if (ticks - scenarioStart < 60) return; checkOtherEncounterResult(); nextScenario(); beginWaterLandStep(); stage = 11; }
                case 11 -> { if (driveWaterTurn()) return; checkWaterLandStep(); nextScenario(); beginBlockedRoute(); stage = 12; }
                case 12 -> {
                    if (!wallPlaced) {
                        if (driveWaterTurn()) {
                            if (visitedCells.size() >= 2) placeWallInFront();
                            return;
                        }
                        require(visitedCells.size() >= 2,
                                "the drowned walked before the route could be cut: " + visitedCells + diagnostics());
                        placeWallInFront();
                        return;
                    }
                    if (driveWaterTurn()) return;
                    checkBlockedRoute();
                    nextScenario();
                    beginChunkEdge();
                    stage = 13;
                }
                case 13 -> { if (ticks - scenarioStart < 40) return; checkChunkEdge(); nextScenario(); beginPoseCase(); stage = 14; }
                case 14 -> { if (driveTurn()) return; checkPoseCase(); nextScenario(); beginTimeoutCase(); stage = 15; }
                case 15 -> {
                    if (!throwSeen) {
                        if (driveUntilThrow()) return;
                        if (DrownedTridents.markedFor(battle) == 0) return;
                        throwSeen = true;
                        throwTick = ticks;
                        apAfterThrowAt = battle.actorAp(drowned);
                        require(apAfterThrowAt == apAtStart - BattleRules.DROWNED_TRIDENT_COST,
                                "the throw really cost its own price (" + apAtStart + " -> " + apAfterThrowAt + ")");
                    }
                    if (ticks - throwTick < 120) return;
                    checkTimeoutCase();
                    nextScenario();
                    beginExitCase();
                    stage = 16;
                }
                case 16 -> {
                    if (!throwSeen) {
                        if (driveUntilThrow()) return;
                        if (DrownedTridents.markedFor(battle) == 0) return;
                        throwSeen = true;
                        require(DrownedTridents.markedFor(battle) >= 1,
                                "the trident is in the air when the owner leaves");
                        battle.leave(owner, BattleText.literal("fixture: the owner left mid flight"));
                        checkExitCase();
                        nextScenario();
                        beginFinishCase();
                        stage = 17;
                        return;
                    }
                }
                case 17 -> {
                    if (!throwSeen) {
                        if (driveUntilThrow()) return;
                        if (DrownedTridents.markedFor(battle) == 0) return;
                        throwSeen = true;
                        require(DrownedTridents.markedFor(battle) >= 1,
                                "the trident is in the air when the encounter ends");
                        battle.finish(BattleText.literal("fixture: the encounter ended mid flight"));
                        checkFinishCase();
                        nextScenario();
                        beginLateEnchant();
                        stage = 18;
                        return;
                    }
                }
                case 18 -> { if (driveTurn()) return; checkLateEnchant(); nextScenario(); beginJoinExceptions(); stage = 19; }
                case 19 -> { if (driveTurn()) return; checkJoinExceptions(); nextScenario(); beginLegalCancelThenIllegal(); stage = 20; }
                case 20 -> { if (driveTurn()) return; checkLegalCancelThenIllegal(); nextScenario(); beginZoneEdge(); stage = 21; }
                case 21 -> { if (ticks - scenarioStart < 40) return; checkZoneEdge(); complete(event.getServer()); }
                default -> { }
            }
        } catch (AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] DROWNED SELFTEST FAILED at stage {}: {}", stage, failure.getMessage());
            throw failure;
        }
    }

    // ------------------------------------------------------------------ arena

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        // Vanilla scales a mob's damage by difficulty. The native trident number can only be read as its own
        // constant on the neutral difficulty, so the fixture runs there and records what it really measured.
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 20, spawn.getY() + 24);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 32) >> 4; cx <= (origin.getX() + 32) >> 4; cx++) {
            for (int cz = (origin.getZ() - 32) >> 4; cz <= (origin.getZ() + 32) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -16; x <= 16; x++) for (int z = -10; z <= 10; z++) {
            set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int dy = 0; dy < 12; dy++) set(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState());
        }
        buildPool();
        clearWorkingBox();
        owner = player(server, "tactical-drowned-owner", "DrownedOwner");
        stranger = player(server, "tactical-drowned-stranger", "DrownedStranger");
        stranger.setPos(origin.getX() + 70.5D, origin.getY(), origin.getZ() + 0.5D);
        stranger2 = player(server, "tactical-drowned-stranger2", "DrownedStranger2");
        stranger2.setPos(origin.getX() + 96.5D, origin.getY(), origin.getZ() + 0.5D);
    }

    /**
     * A walled pool with a one-block stone shelf on its west side: real water, real walls, a real floor, and a
     * shore a swimmer can genuinely reach by one step out of the water.
     *
     * <p>The shelf column is solid at the three water levels, so its top face is a dry standable cell exactly one
     * block above the deepest water. That is the shore transition this fixture tests; a four-block wall would be
     * a wall, not a shore.</p>
     */
    private static void buildPool() {
        // A real walled pool: three water levels inside, a solid ring that holds the water, and a one-block stone
        // shelf on the west side whose top face is a dry standable cell exactly one step above the water. That
        // shelf is the shore transition; a four-block wall would be a wall, not a shore.
        for (int x = 5; x <= 13; x++) {
            for (int z = -4; z <= 4; z++) {
                boolean ring = x == 5 || x == 13 || z == -4 || z == 4;
                for (int dy = 0; dy <= 3; dy++) {
                    set(origin.offset(x, dy, z),
                            ring ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        for (int z = -3; z <= 3; z++) {
            // The shelf column is solid up to the water surface, so its top is one block above the deepest water.
            for (int dy = 0; dy <= 2; dy++) set(origin.offset(6, dy, z), Blocks.STONE.defaultBlockState());
        }
        for (int x = 7; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int dy = 0; dy <= 2; dy++) set(origin.offset(x, dy, z), Blocks.WATER.defaultBlockState());
                set(origin.offset(x, 3, z), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void set(BlockPos pos, BlockState state) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
        level.setBlock(pos, state, 3);
    }

    private static ServerPlayer player(MinecraftServer server, String key, String name) {
        var profile = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),
                player, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 6.5D);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.1D);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        player.setHealth(200);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        level.addNewPlayer(player);
        return player;
    }

    private static Drowned spawnDrowned(double dx, double dy, double dz) {
        Drowned mob = (Drowned) EntityType.DROWNED.create(level);
        require(mob != null, "a drowned exists in this version");
        mob.setPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        require(level.addFreshEntity(mob), "the drowned really joined the world");
        return mob;
    }

    /**
     * Remove every leftover body from the working box before a scenario is built.
     *
     * <p>A reused run directory carries the bodies of an aborted run, and a non-living leftover - an armour
     * stand above all - really blocks a trident's arc while not being an animal or an enemy. Starting from an
     * empty box is what keeps "the throw was refused" from meaning "last night's fixture was still standing
     * there".</p>
     */
    private static void clearWorkingBox() {
        if (level == null || origin == null) return;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin).inflate(40);
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, box)) {
            if (leftover instanceof ServerPlayer) continue;
            leftover.discard();
        }
    }

    // ------------------------------------------------------------------ driving

    private static void startBattle() {
        ServerPackets.startCapture();
        BattleManager.reset();
        require(BattleManager.start(owner, true), "the drowned encounter starts");
        battle = BattleManager.current();
        require(battle != null, "the encounter exists");
        require(battle.participants().contains(drowned), "the drowned is a real participant");
        require(drowned.isNoAi(), "the encounter really holds the unit's own AI");
        require(battle.isPlayerTurnNow(), "the high-initiative owner acts first" + diagnostics());
        scenarioStart = ticks;
        joinsAtScenarioStart = tridentsJoined;
        spawnPoint = null;
        spawnInWater = false;
        scenarioLaunch = -1.0D;
        scenarioLaunchHorizontal = -1.0D;
        airSpeed.clear();
        waterSpeed.clear();
        sawEnemyPhase = false;
        turnHanded = false;
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(56))) {
            if (other != drowned && other != bystander && other instanceof net.minecraft.world.entity.monster.Enemy) {
                other.discard();
            }
        }
    }

    /**
     * Wait through the drowned's own turn; false once control is back with the player.
     *
     * <p>A turn that resolves instantly (a refused throw, a plain end of turn) begins and ends inside one or two
     * ticks, so the own turn is read from the AP low-water mark instead of from a per-tick "is it acting"
     * sample that an instantaneous turn can slip past.</p>
     */
    private static boolean driveTurn() {
        if (battle == null || battle.finished()) return false;
        sampleMark();
        sampleAp();
        if (!battle.isPlayerTurnNow()) {
            sawEnemyPhase = true;
            recordVisitedCell();
            return true;
        }
        // The turn scheduler is active-time based: a very fast member keeps reaching a full gauge and takes the
        // slot again and again, so the waiting player has to keep passing it on until the drowned's own gauge
        // fills. An instant enemy action can also hand the slot straight back, which is why this waits for a
        // visible enemy phase instead of assuming one hand-over is enough.
        turnHanded = true;
        battle.endPlayerTurn();
        return !sawEnemyPhase && ticks - scenarioStart < ENEMY_TURN_BUDGET_TICKS;
    }

    /** Tridents this scenario really created, so one scenario's evidence never satisfies another's. */
    private static int joinsDelta() { return tridentsJoined - joinsAtScenarioStart; }

    private static void sampleAp() {
        if (battle == null || drowned == null || !battle.isParticipant(drowned)) return;
        int ap = battle.actorAp(drowned);
        if (apAtStart < 0) apAtStart = ap;
        apLowest = Math.min(apLowest, ap);
    }

    private static void sampleMark() {
        if (battle == null) return;
        int marked = DrownedTridents.markedFor(battle);
        if (markedWhileFlying < 0 && marked > 0) markedWhileFlying = marked;
        if (markedWhileFlying > 0 && marked == 0 && !markReleasedAfterSettle) markReleasedAfterSettle = true;
        if (marked > 0 && drowned != null && drowned.isAggressive() && !r3PoseDuringFlight) {
            // The renderer's own condition for THROW_SPEAR is "trident in the main hand && isAggressive", so the
            // aggressive bit is the pose being observed; the vanilla use-items flag is recorded beside it.
            r3PoseDuringFlight = true;
            Tactical.LOGGER.info("[Tactical] DROWNED fixture pose during flight: aggressive={} usingItem={} marked={}",
                    drowned.isAggressive(), drowned.isUsingItem(), marked);
        }
    }

    /**
     * Per-tick horizontal speed of the trident really in flight.
     *
     * <p>Whether the flight is in water is read from the world block the entity occupies, not from the entity's
     * own cached swim flag, which lags its first fluid pass. Horizontal speed is the honest measurement: air and
     * liquid drag multiply it directly while gravity only touches the vertical part.</p>
     */
    private static void sampleFlight() {
        ThrownTrident trident = lastTrident;
        if (trident == null || trident.isRemoved()) return;
        double horizontal = trident.getDeltaMovement().horizontalDistance();
        if (horizontal < 0.4D) return;
        boolean wet = level.getFluidState(trident.blockPosition())
                .is(net.minecraft.tags.FluidTags.WATER);
        if (wet) {
            if (waterSpeed.size() < 12) waterSpeed.add(horizontal);
        } else if (airSpeed.size() < 12) {
            airSpeed.add(horizontal);
        }
    }

    private static void recordVisitedCell() {
        GridPos cell = battle.currentActorCell(drowned);
        if (cell == null) return;
        if (visitedCells.isEmpty() || !visitedCells.get(visitedCells.size() - 1).equals(cell)) visitedCells.add(cell);
    }

    private static void nextScenario() {
        if (battle != null) battle.finish(BattleText.literal("fixture: next scenario"));
        BattleManager.reset();
        battle = null;
        if (drowned != null) drowned.discard();
        drowned = null;
        if (bystander != null) bystander.discard();
        bystander = null;
        if (nonLiving != null) nonLiving.discard();
        nonLiving = null;
        if (lastTrident != null && !lastTrident.isRemoved()) lastTrident.discard();
        lastTrident = null;
        spawnPoint = null;
        apAtStart = -1;
        apLowest = Integer.MAX_VALUE;
        cancelAllJoins = false;
        legalImpactCancelled = false;
        enchantOnJoin = false;
        throwOnJoin = false;
        markedDuringJoin = false;
        r3PoseDuringFlight = false;
        zoneGapBlocks = -1.0D;
        nearestForeign = -1.0D;
        reachesForeign = false;
        throwSeen = false;
        apAfterThrowAt = -1;
        visitedCells.clear();
        if (otherEncounterUnit != null) {
            otherEncounterUnit.discard();
            otherEncounterUnit = null;
        }
        owner.invulnerableTime = 0;
        owner.setHealth(owner.getMaxHealth());
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 6.5D);
    }

    // ------------------------------------------------------------------ D1/D2/D3/D5: one real land throw

    private static void beginLandThrow() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        drownedHandBefore = drowned.getMainHandItem().copy().toString();
        startBattle();
        ownerApBefore = battle.actorAp(owner);
        ownerHandBefore = owner.getMainHandItem().copy().toString();
        ownerInventory.clear();
        for (ItemStack stack : owner.getInventory().items) ownerInventory.add(stack.copy().toString());
        require(battle.quoteActorActionCost(drowned, BattleRules.DROWNED_TRIDENT_COST, true) > 0,
                "the drowned really quotes a trident price with a trident in its main hand");
        require(battle.tridentThrowLegal(drowned),
                "fixture precondition: this position really allows the native throw"
                        + " drowned=" + drowned.position() + " owner=" + owner.position()
                        + " dist=" + drowned.position().distanceTo(owner.position())
                        + diagnostics());
    }

    private static void checkLandThrow() {
        int price = TacticalConfig.INSTANCE.drownedTridentApCost.get();
        require(joinsDelta() == 1, "exactly one real trident was created, got " + joinsDelta() + diagnostics());
        require(spawnPoint != null, "the trident really joined the world");
        require(!spawnInWater, "this scenario's spawn point is on dry land");
        require(tridentWasOwnerThrown, "the damage really came from a thrown trident entity");
        require(damageFromDrowned, "that trident belonged to the drowned");
        require(Math.abs(nativeAmount - 8.0F) < 0.001F,
                "the native trident hurt the member for exactly 8.0 before any reduction, got " + nativeAmount);
        require(appliedDamage > 0.0F && appliedDamage <= rawDamage + 0.001F,
                "the settled damage stays within the raw hit: " + appliedDamage);
        require(!tridentPickable, "a drowned's trident is never pickable");
        require(apAtStart >= 0 && apLowest != Integer.MAX_VALUE, "the drowned's AP was observed" + diagnostics());
        require(apAtStart - apLowest == price,
                "one throw costs exactly the quoted " + price + " AP (" + apAtStart + " -> " + apLowest + ")");
        // The waiting member keeps passing its own slot on, and every own turn start restores AP, so the only
        // honest statement is that this throw never charged the member anything.
        require(battle.actorAp(owner) >= ownerApBefore,
                "the throw never charges the member's AP (" + ownerApBefore + " -> " + battle.actorAp(owner) + ")");
        require(owner.getMainHandItem().copy().toString().equals(ownerHandBefore),
                "the throw never touches the member's inventory");
        List<String> after = new ArrayList<>();
        for (ItemStack stack : owner.getInventory().items) after.add(stack.copy().toString());
        require(after.equals(ownerInventory), "the member's whole inventory is unchanged");
        require(drowned.getMainHandItem().copy().toString().equals(drownedHandBefore),
                "the throw never consumes, copies or re-enchants the drowned's own trident");
        require(markedWhileFlying > 0, "the real trident carried this encounter's mark while it flew");
        require(markReleasedAfterSettle, "the mark is released once the flight is over" + diagnostics());
        require(!leftoverTridentInWorld(), "no marked trident is left lying in the world");
        r5Marked = markedWhileFlying;
        r5Released = markReleasedAfterSettle;
        r5Leftover = leftoverTridentInWorld();
        require(!launchSpeed.isEmpty(), "a launch speed was recorded");
        double launched = launchSpeed.get(0);
        require(launched > 1.45D && launched < 1.75D,
                "the native launch speed is about 1.6 blocks per tick, got " + launched);
        require(airSpeed.size() >= 3, "the flight was sampled in air" + diagnostics());
        double ratio = airDragRatio(airSpeed);
        require(Math.abs(ratio - 0.99D) < 0.02D, "air drag is the trident's 0.99 per tick, saw " + ratio);
        r2Launch = launched;
        r2Raw = nativeAmount;
        r2Applied = appliedDamage;
        r2AirRatio = ratio;
        require(rawDamage > 0.0F && rawDamage <= nativeAmount + 0.001F,
                "the settled original damage stays within the native amount: " + rawDamage + " vs " + nativeAmount);
    }

    /** Horizontal speed ratio between two consecutive sampled ticks, ignoring gravity and rounding. */
    private static double airDragRatio(List<Double> samples) {
        for (int index = 1; index < samples.size(); index++) {
            if (samples.get(index - 1) < 0.05D) continue;
            return samples.get(index) / samples.get(index - 1);
        }
        return -1.0D;
    }

    // ------------------------------------------------------------------ D1: hand rules

    private static void beginOffHandOnly() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.AIR));
        drowned.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TRIDENT));
        startBattle();
    }

    private static void checkOffHandOnly() {
        offHandRefused = joinsDelta() == 0;
        require(offHandRefused, "a trident in the off hand never becomes a throw" + diagnostics());
        require(drowned.getOffhandItem().is(Items.TRIDENT), "the off-hand trident is untouched");
    }

    private static void beginFishingRod() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
        startBattle();
    }

    private static void checkFishingRod() {
        rodRefused = joinsDelta() == 0;
        require(rodRefused, "a fishing rod is not a trident and never throws" + diagnostics());
    }

    private static void beginDisarmed() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        // The weapon leaves the hand before the own turn is planned: nothing may be spent on a throw it can no
        // longer perform, and the execution path re-reads the hand anyway.
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.AIR));
    }

    private static void checkDisarmed() {
        require(joinsDelta() == 0, "an empty main hand creates no trident" + diagnostics());
        require(DrownedTridents.markedFor(battle) == 0, "an empty main hand leaves no mark");
        require(drowned.getMainHandItem().isEmpty(), "the empty hand stays empty");
    }

    // ------------------------------------------------------------------ D3: a refused projectile is free

    private static void beginCancelledJoin() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        cancelAllJoins = true;
    }

    private static void checkCancelledJoin() {
        require(cancelledJoins >= 1, "the native throw really tried to create a trident" + diagnostics());
        require(joinsDelta() == 0, "a cancelled join leaves no trident in the world");
        require(DrownedTridents.markedFor(battle) == 0, "nothing is marked for an entity that never existed");
        require(!leftoverTridentInWorld(), "a cancelled join leaves no projectile behind");
        require(journalHas(BattleText.KEY_ENEMY_NO_SHOT),
                "the refusal is recorded as structure instead of silently costing AP");
        require(apAtStart >= 0 && apLowest != Integer.MAX_VALUE, "the drowned's AP was observed" + diagnostics());
        require(apAtStart == apLowest,
                "a zero-projectile throw is free (" + apAtStart + " -> " + apLowest + ")");
        r3ApStart = apAtStart;
        r3ApLow = apLowest;
        r3Cancelled = cancelledJoins;
    }

    // ------------------------------------------------------------------ D4: real water

    /**
     * A drowned submerged in the pool throws at a member standing on a small stone island inside it.
     *
     * <p>The island is what makes this a real in-water shot: the arc from the thrower to the target never
     * leaves the water and never crosses the pool wall, so the water origin and the water inertia are measured
     * on a throw that can actually land. The walled pool is used by the cover scenario instead.</p>
     */
    private static void beginWater() {
        // Far end of the pool, so the flight stays over water for several ticks and the inertia is measurable.
        drowned = spawnDrowned(11.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        // The member stands on the pool's own shelf: the arc leaves the water at almost the height it arrives at,
        // so it is a real throw rather than a vertical lob.
        owner.setPos(origin.getX() + 6.5D, origin.getY() + 3.0D, origin.getZ() + 0.5D);
        startBattle();
        GridPos cell = battle.currentActorCell(drowned);
        require(cell != null, "the drowned has an authoritative cell");
        require(isWater(cell), "a drowned is really admitted into a water cell instead of a beach");
        require(battle.tridentThrowLegal(drowned),
                "fixture precondition: the in-water position really allows the throw" + diagnostics());
        drift = 0.0D;
    }

    private static boolean driveWaterTurn() {
        if (battle == null || battle.finished()) return false;
        sampleMark();
        sampleAp();
        if (!battle.isPlayerTurnNow()) {
            sawEnemyPhase = true;
            boolean acting = battle.isActorTurn(drowned);
            if (!acting && drowned.isInWater()) {
                // Between turns the pinned body must not drift away from the cell its turn is priced on.
                GridPos cell = battle.currentActorCell(drowned);
                if (cell != null) {
                    Vec3 expected = new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
                    drift = Math.max(drift, drowned.position().distanceTo(expected));
                }
            }
            if (drowned.isInWater()) {
                poseWhileInWater |= drowned.isSwimming();
                poseAfterRelease = false;
            }
            if (acting) recordVisitedCell();
            return true;
        }
        turnHanded = true;
        battle.endPlayerTurn();
        return !sawEnemyPhase && ticks - scenarioStart < ENEMY_TURN_BUDGET_TICKS;
    }

    private static void checkWater() {
        require(poseWhileInWater, "a controlled drowned really shows its own swimming pose in water");
        require(joinsDelta() >= 1, "a submerged drowned really threw its trident" + diagnostics());
        require(drift >= 0.0D, "a waiting sample was taken while it was in water" + diagnostics());
        require(drift < 0.05D, "a waiting drowned does not drift away from its cell, drift " + drift);
        require(spawnInWater, "the trident was really created under water" + diagnostics());
        require(!waterSpeed.isEmpty(), "the flight was sampled in water" + diagnostics());
        double ratio = airDragRatio(waterSpeed);
        if (ratio > 0.0D) {
            require(Math.abs(ratio - 0.99D) < 0.02D,
                    "a thrown trident keeps 0.99 of its horizontal speed in water, saw " + ratio);
        } else {
            // A very short flight may only yield the first in-water sample. It is still a real measurement: the
            // medium kept nearly all of the launch speed, where an arrow's 0.6 would have thrown it away.
            require(scenarioLaunchHorizontal > 0.4D
                            && waterSpeed.get(0) / scenarioLaunchHorizontal > 0.95D,
                    "the water kept the launch speed (launch " + scenarioLaunchHorizontal + ", first in water "
                            + waterSpeed.get(0) + ")");
        }
        GridPos cell = battle.currentActorCell(drowned);
        require(cell != null, "the drowned still has a cell");
        // The shore must really be reachable through the amphibious edges themselves, several water steps away
        // included: that is the water-to-land link this group is about, not a straight-line guess.
        dryShoreEdge = reachableDryWithin(cell, 8);
        require(dryShoreEdge, "a dry shore is really reachable from the water cell through the swim edges");
        var edges = AmphibiousMovement.neighbors(level, drowned, cell, Vec3.ZERO, c -> false, c -> true);
        require(edges.stream().anyMatch(DrownedSelfTest::isWater),
                "the water cell really has neighbouring water cells to swim through");
        // Draining the pool must not leave it floating: the real floor underneath is a legal dry position.
        for (int x = 6; x <= 12; x++) for (int z = -3; z <= 3; z++) for (int dy = 0; dy <= 3; dy++) {
            set(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState());
        }
        drainedCellHoldsOnFloor = AmphibiousMovement.isHoldable(level, drowned,
                AmphibiousMovement.holdFeet(level, cell, Vec3.ZERO));
        require(!isWater(cell), "the drained cell really has no water left");
        require(drainedCellHoldsOnFloor, "a drained cell with a real floor is still a legal dry position");
        r4Pose = poseWhileInWater;
        r4Drift = drift;
        r4SpawnInWater = spawnInWater;
        r4WaterRatio = airDragRatio(waterSpeed);
        r4Shore = dryShoreEdge;
        r4Drained = drainedCellHoldsOnFloor;
        buildPool();
    }

    /** Whether a really holdable, really dry cell is reachable from here through the swim edges. */
    private static boolean reachableDryWithin(GridPos start, int maxSteps) {
        java.util.Set<GridPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<GridPos> queue = new java.util.ArrayDeque<>();
        java.util.Map<GridPos, Integer> depth = new java.util.HashMap<>();
        seen.add(start);
        depth.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            GridPos cell = queue.removeFirst();
            int step = depth.get(cell);
            if (step > 0 && !isWater(cell)
                    && AmphibiousMovement.isHoldable(level, drowned,
                            AmphibiousMovement.holdFeet(level, cell, Vec3.ZERO))) {
                return true;
            }
            if (step >= maxSteps) continue;
            for (GridPos next : AmphibiousMovement.neighbors(level, drowned, cell, Vec3.ZERO,
                    c -> false, c -> true)) {
                if (!seen.add(next)) continue;
                depth.put(next, step + 1);
                queue.addLast(next);
            }
        }
        return false;
    }

    private static boolean isWater(GridPos cell) {
        return level.getFluidState(new BlockPos(cell.x(), cell.y(), cell.z()))
                .is(net.minecraft.tags.FluidTags.WATER);
    }

    // ------------------------------------------------------------------ D6: the collision rule

    /**
     * The collision rule at a real collision.
     *
     * <p>The AI refuses such a shot in advance - that is D7 - so the encounter's own rule can only be exercised
     * by a real projectile reaching a body the plan never aimed at. This scenario therefore fires the product's
     * own native throw (the same entity and the same marking path) at a bystander, at a non-living body, and at
     * the member, with both obstacles really standing on the line.</p>
     */
    private static void beginBystander() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        nonLiving = new ArmorStand(level, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 2.5D);
        require(level.addFreshEntity(nonLiving), "the non-living body joined the world");
        bystander = (Cow) EntityType.COW.create(level);
        require(bystander != null, "a cow exists");
        bystander.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 4.5D);
        bystander.setNoAi(true);
        bystander.setPersistenceRequired();
        require(level.addFreshEntity(bystander), "the bystander joined the world");
        bystanderHealthAtStart = bystander.getHealth();
        startBattle();
        require(!battle.acceptsTrident(drowned.getUUID(), bystander),
                "a bystander is not a legal target of this encounter");
        require(!battle.acceptsTrident(drowned.getUUID(), nonLiving),
                "a non-living body is not a legal target of this encounter");
        require(battle.acceptsTrident(drowned.getUUID(), owner),
                "the member itself is a legal target");
        // Real throws through the product's own path, all of them crossing both obstacles first.
        for (int attempt = 0; attempt < 4; attempt++) {
            require(battle.fixtureNativeTrident(drowned, owner), "a native trident really joined the world");
        }
    }

    private static void checkBystander() {
        require(!bystander.isRemoved(), "the bystander still exists");
        require(bystander.getHealth() == bystanderHealthAtStart,
                "a refused collision never damages the bystander: " + bystander.getHealth());
        require(!nonLiving.isRemoved(), "the non-living body still exists");
        require(refusedImpacts >= 1, "a real trident really reached a refused body" + diagnostics());
        require(nonLivingRefusals >= 1,
                "the non-living body was refused as a non-participant" + diagnostics());
        require(markSurvivedRefusal, "a refused body never takes the encounter's rule off the trident");
        // The obstacle stops nothing: the same trident keeps flying under the rule and can still reach the legal
        // target behind it, which is what "refused at the entry, not repaired afterwards" has to mean.
        require(owner.getHealth() < owner.getMaxHealth(),
                "a trident that was refused at two bodies still reached the legal target behind them");
        require(DrownedTridents.markedFor(battle) == 0 || refusedImpacts > 0,
                "every marked trident is either still under the rule or already reclaimed");
        r6Refusals = refusedImpacts;
        r6NonLiving = nonLivingRefusals;
        r6Other = otherEncounterRefusals;
        r6MarkSurvived = markSurvivedRefusal;
    }

    // ------------------------------------------------------------------ D7: cover across the water

    private static void beginCover() {
        drowned = spawnDrowned(9.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        for (int z = -2; z <= 2; z++) {
            for (int dy = 0; dy <= 3; dy++) set(origin.offset(4, dy, z), Blocks.STONE.defaultBlockState());
        }
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        startBattle();
        require(!battle.tridentThrowLegal(drowned),
                "fixture precondition: the stone wall really blocks the native arc" + diagnostics());
    }

    private static void checkCover() {
        require(joinsDelta() == 0, "a blocked arc is refused instead of paid for" + diagnostics());
        require(drowned.getMainHandItem().is(Items.TRIDENT), "the refused throw never consumed the trident");
        // Either the position itself was refused as a structured row, or the actor really spent AP looking for a
        // legal one. What is not allowed is a paid shot into its own wall.
        boolean refusedRow = journalHas(BattleText.KEY_ENEMY_NO_SHOT);
        boolean lookedForPosition = battle.actorAp(drowned) < apAtStart;
        require(refusedRow || lookedForPosition,
                "the blocked arc either recorded a structured refusal or really spent the turn repositioning"
                        + diagnostics());
        GridPos cell = battle.currentActorCell(drowned);
        require(cell != null && isWater(cell),
                "the refused thrower is still standing in its own water cell, not inside the wall");
        noShotRows = refusedRow ? 1 : 0;
        r7RefusedRow = refusedRow;
        // Durable: the later scenarios fire their own tridents, so the summary must not read a live counter that
        // belongs to the last scenario instead of this group.
        d7ScenarioTridents = joinsDelta();
        d6ForeignRefusals = otherEncounterRefusals;
    }

    // ------------------------------------------------------------------ D6: a unit of a second, non-overlapping encounter

    /**
     * A real participant of another encounter standing on this trident's path.
     *
     * <p>Two battlefields cannot overlap, but a projectile can still cross the gap, so the second founder stands
     * far outside while its own unit stands inside this encounter's area. The shots are fired through the
     * product's own native throw, exactly like the bystander case: the AI would refuse the position, and what is
     * under test is the collision rule of a projectile that really exists.</p>
     */
    private static void checkOtherEncounterCase() {
        nextScenario();
        // Real ground out to the second founder, exactly as a second encounter needs.
        for (int dx = 16; dx <= 60; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                set(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) set(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        }
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 6.5D);
        startBattle();
        // The second founder stands far enough that the two battlefields cannot overlap, and its own zone really
        // contains the unit it recruits. A trident only reaches 10 blocks, and the non-overlap rule forces that
        // unit at least 24 blocks out, so no real trident can reach it from inside this encounter - the roster
        // predicate is therefore asserted against the real, live dual-encounter state.
        Mob unit = (Mob) EntityType.RAVAGER.create(level);
        require(unit != null, "a ravager exists in this version");
        unit.setPos(origin.getX() + 28.5D, origin.getY(), origin.getZ() + 0.5D);
        unit.setPersistenceRequired();
        require(level.addFreshEntity(unit), "the other encounter's unit joined the world");
        otherEncounterUnit = unit;
        stranger.setPos(origin.getX() + 52.5D, origin.getY(), origin.getZ() + 0.5D);
        require(BattleManager.start(stranger, true), "the second encounter really starts");
        require(BattleManager.containing(unit) != null, "the second encounter owns its own unit");
        require(BattleManager.containing(unit) != battle, "that unit belongs to the other encounter");
        require(!battle.acceptsTrident(drowned.getUUID(), unit),
                "this encounter refuses a body from another encounter, read from the live dual state");
        scenarioStart = ticks;
    }

    private static Mob otherEncounterUnit;

    private static void checkOtherEncounterResult() {
        require(otherEncounterUnit != null, "the other encounter's unit still exists");
        require(BattleManager.containing(otherEncounterUnit) != battle
                        && BattleManager.containing(otherEncounterUnit) != null,
                "the unit still belongs to the other encounter");
        require(otherEncounterUnit.getHealth() == otherEncounterUnit.getMaxHealth(),
                "the other encounter's unit keeps its health: " + otherEncounterUnit.getHealth());
        r6Other = otherEncounterRefusals;
        Tactical.LOGGER.info("[Tactical] DROWNED fixture other encounter: predicate refused={} real crossings={} "
                        + "(a 10 block trident cannot cross the 24 block non-overlap gap)",
                !battle.acceptsTrident(drowned.getUUID(), otherEncounterUnit), otherEncounterRefusals);
    }

    // ------------------------------------------------------------------ R1: a real fractional water altitude that really walks ashore

    /**
     * A drowned admitted at {@code y + 0.4} in water, then really walking ashore.
     *
     * <p>The point is the per-medium feet rule: a fractional water altitude must survive in water and must not be
     * carried onto a dry cell, where the ordinary rule only accepts an integer support height. The evidence is the
     * real cell sequence and the real position on the first dry cell, not a search result.</p>
     */
    private static void beginWaterLandStep() {
        owner.setPos(origin.getX() - 8.5D, origin.getY(), origin.getZ() + 0.5D);
        // A shallow one block channel out of the pool, with a solid shore that stops the water flowing after it:
        // the walk ashore is then a real 0.4 -> integer-feet step instead of a three block climb or a puddle.
        for (int x = 5; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                if (x >= 7) {
                    set(origin.offset(x, 0, z), Blocks.WATER.defaultBlockState());
                } else {
                    set(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
                }
                set(origin.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                set(origin.offset(x, 2, z), Blocks.AIR.defaultBlockState());
            }
        }
        drowned = spawnDrowned(11.5D, 0.4D, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        GridPos cell = battle.currentActorCell(drowned);
        require(cell != null && isWater(cell), "the drowned is really admitted into water" + diagnostics());
        r1WaterY = drowned.getY() - origin.getY();
        require(Math.abs(r1WaterY - 0.4D) < 0.02D,
                "the real water altitude survived admission, got " + r1WaterY);
        visitedCells.clear();
    }

    private static void checkWaterLandStep() {
        require(battle.currentActorCell(drowned) != null, "the drowned still has a cell");
        r1ReachedLand = visitedCells.stream().anyMatch(cell -> !isWater(cell));
        if (r1ReachedLand) {
            double feetY = drowned.getY() - origin.getY();
            r1LandFeetInteger = Math.abs(feetY - Math.rint(feetY)) < 1.0E-6D;
            r1LandStandable = BattleDeployment.isStandable(level, drowned, drowned.position());
            require(r1LandFeetInteger, "on dry land the feet sit on the support height, got " + feetY);
            require(r1LandStandable, "the dry position is a real standable position, not a floating one");
        }
        require(!visitedCells.isEmpty(), "the drowned really moved through cells" + diagnostics());
        Tactical.LOGGER.info("[Tactical] DROWNED fixture water start: waterY={} cells={} reachedLand={} "
                        + "landFeetInteger={} landStandable={}",
                r1WaterY, visitedCells, r1ReachedLand, r1LandFeetInteger, r1LandStandable);
    }

    // ------------------------------------------------------------------ R1b: a route that stops being legal mid-way

    /**
     * The drowned really walks out of a shallow pool, and a wall appears in front of it after two cells.
     *
     * <p>The point is the per-segment revalidation: the step it already walked is paid, the steps it can no longer
     * walk are not, the refusal is recorded as structure, and the body is never left inside solid ground.</p>
     */
    private static void beginBlockedRoute() {
        owner.setPos(origin.getX() - 8.5D, origin.getY(), origin.getZ() + 0.5D);
        for (int x = 5; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) {
                set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                set(origin.offset(x, 0, z), x >= 7 ? Blocks.WATER.defaultBlockState() : Blocks.STONE.defaultBlockState());
                set(origin.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                set(origin.offset(x, 2, z), Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = -8; x <= 4; x++) {
            for (int z = -3; z <= 3; z++) {
                set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 2; dy++) set(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState());
            }
        }
        drowned = spawnDrowned(11.5D, 0.4D, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        apAtStart = battle.actorAp(drowned);
        visitedCells.clear();
        wallPlaced = false;
        startCell = battle.currentActorCell(drowned);
        require(startCell != null && isWater(startCell),
                "the drowned really starts in water for the interrupted route" + diagnostics());
    }

    /** Close the route two cells in front of the body, while the encounter is still walking it. */
    private static void placeWallInFront() {
        GridPos last = battle.currentActorCell(drowned);
        require(last != null, "the walking drowned has an authoritative cell");
        // Never wall the body's own cell or its immediate ring: the wall closes the route ahead of the step the
        // encounter is about to take, which is what makes this an interruption and not a teleport into stone.
        for (int x = last.x() - 4; x <= last.x() + 4; x++) {
            for (int z = last.z() - 4; z <= last.z() + 4; z++) {
                if (Math.abs(x - last.x()) < 2 && Math.abs(z - last.z()) < 2) continue;
                set(new BlockPos(x, last.y(), z), Blocks.STONE.defaultBlockState());
                set(new BlockPos(x, last.y() + 1, z), Blocks.STONE.defaultBlockState());
            }
        }
        wallPlaced = true;
    }

    private static void checkBlockedRoute() {
        GridPos cell = battle.currentActorCell(drowned);
        require(cell != null, "the drowned still has a cell after the interrupted route");
        int ap = battle.actorAp(drowned);
        r1BlockedRouteStopped = journalHas(BattleText.KEY_ENEMY_SWIM_BLOCKED);
        r1NoTunnelling = !level.getBlockState(drowned.blockPosition()).isSolid()
                && !level.getBlockState(drowned.blockPosition().above()).isSolid();
        require(visitedCells.size() >= 2,
                "the drowned really walked before the route was cut: " + visitedCells + diagnostics());
        require(r1BlockedRouteStopped,
                "the interrupted route is recorded as structure instead of silently stopping" + diagnostics());
        require(ap > 0, "the unwalked steps are given back instead of being kept (ap=" + ap + ")");
        require(r1NoTunnelling, "the body was never left inside the wall it ran into" + diagnostics());
        Tactical.LOGGER.info("[Tactical] DROWNED fixture interrupted route: ap={}->{} cells={} row={} stopped={}",
                apAtStart, ap, visitedCells, r1BlockedRouteStopped, r1BlockedRouteStopped);
    }

    // ------------------------------------------------------------------ R2: an unloaded neighbour is refused, not read

    private static void beginChunkEdge() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        // A position in a chunk the world really has not loaded: the arena's own chunks are all ticket-loaded, so
        // the honest probe is further out, and the precondition is recorded rather than assumed.
        BlockPos far = null;
        for (int d = 64; d <= 4096 && far == null; d += 16) {
            BlockPos candidate = origin.offset(d, 0, 0);
            if (!level.hasChunkAt(candidate)) far = candidate;
        }
        require(far != null, "the world really has an unloaded chunk to probe");
        r2StillUnloaded = !level.hasChunkAt(far);
        require(r2StillUnloaded, "fixture precondition: that chunk is really not loaded");
        Vec3 farFeet = new Vec3(far.getX() + 0.5D, far.getY(), far.getZ() + 0.5D);
        r2UnloadedRefused = !AmphibiousMovement.loaded(level, far)
                && !AmphibiousMovement.isHoldable(level, drowned, farFeet);
        require(r2UnloadedRefused, "a position in an unloaded chunk is refused before anything is read");
        require(!level.hasChunkAt(far), "the refusal never loaded that chunk");
        r2StillUnloaded = !level.hasChunkAt(far);
        // The control: the same rule on the drowned's own loaded cell is holdable, so the refusal above is about
        // the chunk and not about a rule that refuses everything.
        require(AmphibiousMovement.isHoldable(level, drowned, drowned.position()),
                "the same rule accepts the drowned's own loaded cell" + diagnostics());
        Tactical.LOGGER.info("[Tactical] DROWNED fixture chunk edge: probe={} loaded={} refused={} stillUnloaded={}",
                far, AmphibiousMovement.loaded(level, far), r2UnloadedRefused, r2StillUnloaded);
    }

    private static void checkChunkEdge() {
        require(r2UnloadedRefused && r2StillUnloaded, "the chunk-edge refusal holds after the encounter ticked");
        require(level.noCollision(drowned, drowned.getBoundingBox()),
                "the actor was never left inside solid ground");
    }

    // ------------------------------------------------------------------ R3: native pose transients

    private static void beginPoseCase() {
        // Out of melee reach but inside the trident's own reach, so this scenario really throws.
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 6.5D);
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        drowned.setAggressive(true);
        drowned.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        startBattle();
        r3LeftoverCleared = !drowned.isAggressive() && !drowned.isUsingItem();
        require(r3LeftoverCleared,
                "admission takes a pre-battle throw pose out of the unit instead of presenting it as ours");
    }

    private static void checkPoseCase() {
        Tactical.LOGGER.info("[Tactical] DROWNED fixture pose case: seen={} aggressive={} usingItem={} joins={}",
                r3PoseDuringFlight, drowned.isAggressive(), drowned.isUsingItem(), joinsDelta());
        require(r3PoseDuringFlight, "the encounter's own throw drove the native pose while the trident flew");
        poseDuringFlightSeen = r3PoseDuringFlight && !drowned.isAggressive();
        r3PoseClearedAfter = !drowned.isAggressive() && !drowned.isUsingItem();
        require(r3PoseClearedAfter, "the throw pose is cleared once the projectile settles");
        // A failed admission must hand the unit back with the pose it arrived with, not with ours.
        nextScenario();
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        drowned.setAggressive(true);
        drowned.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        boolean refused = !BattleManager.start(owner, true);
        boolean stillAggressive = drowned.isAggressive() && drowned.isUsingItem();
        require(ServerBattle.pendingFaults() == 0, "the injected admission fault was really consumed");
        r3RestoredAfterFailedAdmission = refused && stillAggressive;
        require(refused, "the injected admission fault really refused the encounter");
        require(stillAggressive,
                "a refused admission hands the unit back with the pose it arrived with" + diagnostics());
    }

    // ------------------------------------------------------------------ R5: the lifecycle branches a hit does not prove

    /** Drive turns until the drowned's own native throw is really in the air. */
    private static boolean driveUntilThrow() {
        if (battle == null || battle.finished()) return false;
        sampleMark();
        sampleAp();
        if (DrownedTridents.markedFor(battle) > 0) return false;
        if (!battle.isPlayerTurnNow()) {
            sawEnemyPhase = true;
            recordVisitedCell();
            return true;
        }
        turnHanded = true;
        battle.endPlayerTurn();
        return !sawEnemyPhase && ticks - scenarioStart < ENEMY_TURN_BUDGET_TICKS;
    }

    /**
     * A trident whose every legal impact is cancelled: the flight never lands, so it is the encounter's own 80 tick
     * retirement - and the settlement behind it - that has to reclaim the entity and let go of the pose.
     */
    private static void beginTimeoutCase() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        legalImpactCancelled = true;
        apAtStart = -1;
        startBattle();
        apAtStart = battle.actorAp(drowned);
        throwSeen = false;
    }

    private static void checkTimeoutCase() {
        r5TimeoutReclaimed = DrownedTridents.markedFor(battle) == 0
                && (lastTrident == null || lastTrident.isRemoved())
                && !drowned.isAggressive() && !drowned.isUsingItem();
        require(r5TimeoutReclaimed,
                "a flight that never lands is reclaimed by this encounter and its pose released" + diagnostics());
        require(battle.actorAp(drowned) == apAfterThrowAt,
                "a shot that never landed is never refunded (" + apAfterThrowAt + " -> "
                        + battle.actorAp(drowned) + ")");
        Tactical.LOGGER.info("[Tactical] DROWNED fixture timeout: apAfterThrow={} apNow={} marked={} tridentGone={}",
                apAfterThrowAt, battle.actorAp(drowned), DrownedTridents.markedFor(battle),
                lastTrident == null || lastTrident.isRemoved());
    }

    /**
     * The owner leaves while the trident is still in the air: the release has to clear this encounter's projectile.
     */
    private static void beginExitCase() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        throwSeen = false;
    }

    private static void checkExitCase() {
        r5OwnerExitCleaned = DrownedTridents.markedFor(battle) == 0
                && (lastTrident == null || lastTrident.isRemoved());
        require(r5OwnerExitCleaned, "an owner exit clears this encounter's marked trident" + diagnostics());
    }

    /** The encounter ends while the trident is still in the air. */
    private static void beginFinishCase() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        throwSeen = false;
    }

    private static void checkFinishCase() {
        r5FinishCleaned = DrownedTridents.markedFor(battle) == 0
                && (lastTrident == null || lastTrident.isRemoved());
        require(r5FinishCleaned, "ending the encounter clears this encounter's marked trident" + diagnostics());
    }

    // ------------------------------------------------------------------ R4: the creation transaction

    private static void beginLateEnchant() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        apAtStart = battle.actorAp(drowned);
        enchantOnJoin = true;
        boolean joined = battle.fixtureNativeTrident(drowned, owner);
        enchantOnJoin = false;
        // The entity really joins and is then discarded by the settlement, so the join counter moving is expected;
        // what must not happen is a paid throw or a live marked trident.
        r4LateEnchantRefused = !joined && DrownedTridents.markedFor(battle) == 0
                && battle.actorAp(drowned) == apAtStart && !leftoverTridentInWorld();
        require(r4LateEnchantRefused,
                "a trident enchanted by a later join listener is refused and leaves nothing behind" + diagnostics());
    }

    private static void checkLateEnchant() {
        // The refusal was decided and recorded at creation time; a later turn of the same encounter is allowed to
        // change AP, so only the recorded outcome is re-asserted here.
        require(r4LateEnchantRefused,
                "the late-enchant refusal still holds after the encounter ticked" + diagnostics());
    }

    private static void beginJoinExceptions() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        apAtStart = battle.actorAp(drowned);
        // (a) nothing joined at all and the native call still threw: no projectile, no charge.
        int before = tridentsJoined;
        cancelAllJoins = true;
        throwOnJoin = true;
        boolean joined = battle.fixtureNativeTrident(drowned, owner);
        throwOnJoin = false;
        cancelAllJoins = false;
        r4ZeroShotExceptionFree = !joined && tridentsJoined == before && battle.actorAp(drowned) == apAtStart;
        require(r4ZeroShotExceptionFree, "a throw that produced nothing is free even when the call throws");
        // (b) a later listener on the same join already finds the entity ruled, and a throw that really produced a
        // projectile is paid exactly once. This is the reachable form of "the mark is established inside the
        // transaction": a listener that runs after the product's own and still sees the mark.
        markedDuringJoin = false;
        before = tridentsJoined;
        boolean second = battle.fixtureNativeTrident(drowned, owner);
        // This fixture entry creates the projectile without pricing it (pricing is the AI action's own step, already
        // measured as 100 -> 80 in the timeout case above), so the assertion here is about the mark only.
        int spent = apAtStart - battle.actorAp(drowned);
        r4PostJoinExceptionCharged = second && markedDuringJoin && tridentsJoined == before + 1
                && DrownedTridents.markedFor(battle) >= 1 && spent == 0;
        require(r4PostJoinExceptionCharged,
                "the mark already exists inside the join chain (" + "markedDuringJoin=" + markedDuringJoin
                        + " spent=" + spent + ")" + diagnostics());
    }

    private static void checkJoinExceptions() {
        require(r4PostJoinExceptionCharged, "the in-transaction mark case still holds");
        require(DrownedTridents.markedFor(battle) >= 1 || lastTrident == null || lastTrident.isRemoved(),
                "the trident of the post-join case is either still ruled or already reclaimed");
    }

    // ------------------------------------------------------------------ D6: a legal impact cancelled, then an illegal body

    private static void beginLegalCancelThenIllegal() {
        drowned = spawnDrowned(0.5D, 0, 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        bystander = (Cow) EntityType.COW.create(level);
        require(bystander != null, "a cow exists in this version");
        bystander.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 3.5D);
        bystander.setNoAi(true);
        bystander.setPersistenceRequired();
        require(level.addFreshEntity(bystander), "the bystander joined the world");
        bystanderHealthAtStart = bystander.getHealth();
        startBattle();
        legalImpactCancelled = true;
        require(battle.fixtureNativeTrident(drowned, owner), "a native trident really joined the world");
    }

    private static void checkLegalCancelThenIllegal() {
        require(legalCancellations >= 1, "a legal impact really was cancelled by a later listener" + diagnostics());
        require(markSurvivedRefusal, "the same trident was still ruled when it reached the illegal body");
        require(bystander.getHealth() == bystanderHealthAtStart,
                "the illegal body behind the cancelled legal impact was never damaged");
        r5LegalCancelThenIllegal = legalCancellations >= 1 && markSurvivedRefusal
                && bystander.getHealth() == bystanderHealthAtStart;
    }

    // ------------------------------------------------------------------ D6: two real encounters at their own edges

    /**
     * Two real encounters, each unit standing at its own boundary, so the gap between the thrower and the other
     * encounter's unit is only the true non-overlap separation - not a distance this fixture chose.
     *
     * <p>Every position is derived from the configured zone radius and the founder's own block centre: the thrower
     * two blocks inside this encounter's edge, the other unit two blocks inside the other encounter's left edge, and
     * the other founder at {@code 2r + 4} so the two closed boxes cannot overlap. The thrower is placed before this
     * encounter admits it, so its authoritative cell is the edge cell and not a central one moved afterwards.</p>
     *
     * <p>The real trident is then fired at that body through the product's own native path: the collision has to be
     * refused, and the other encounter's unit has to keep its health, its ownership and this encounter's legality.</p>
     */
    private static void beginZoneEdge() {
        int radius = BattleManager.zoneRadius();
        int centerX = origin.getX();
        int centerZ = origin.getZ() + 6;
        // Continuous real floor from before this encounter's centre out past the other founder, with clear air for
        // the arc, laid before anything is admitted.
        for (int x = centerX - 2; x <= centerX + 2 * radius + 10; x++) {
            for (int z = centerZ - 6; z <= centerZ + 6; z++) {
                set(new BlockPos(x, origin.getY() - 1, z), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 8; dy++) set(new BlockPos(x, origin.getY() + dy, z), Blocks.AIR.defaultBlockState());
            }
        }
        owner.setPos(centerX + 0.5D, origin.getY(), centerZ + 0.5D);
        // Two blocks inside this encounter's own +X edge, placed before admission.
        int throwerX = centerX + radius - 2;
        drowned = spawnDrowned(throwerX - origin.getX() + 0.5D, 0, centerZ - origin.getZ() + 0.5D);
        drowned.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT));
        startBattle();
        // The other encounter's unit is created only now: this fixture's own start clears stray hostiles near the
        // member (which is what keeps earlier scenarios clean), so a unit placed before it would not survive to be
        // recruited. It stands two blocks inside the other encounter's left edge and outside this one's zone.
        Mob unit = (Mob) EntityType.RAVAGER.create(level);
        require(unit != null, "a ravager exists in this version");
        int unitX = centerX + radius + 6;
        unit.setPos(unitX + 0.5D, origin.getY(), centerZ + 0.5D);
        unit.setPersistenceRequired();
        require(level.addFreshEntity(unit), "the other encounter's unit joined the world");
        otherEncounterUnit = unit;
        GridPos throwerCell = battle.currentActorCell(drowned);
        require(throwerCell != null, "the thrower was really admitted at its edge cell" + diagnostics());
        require(battle.zone().radius() == radius && battle.zone().center().equals(new GridPos(centerX, origin.getY(), centerZ)),
                "the zone is the configured one around the founder's block: " + battle.zone().center() + " r="
                        + battle.zone().radius() + diagnostics());
        require(battle.zone().contains(throwerCell),
                "the thrower's own cell is inside this encounter: " + throwerCell + diagnostics());
        // The other founder sits far enough out that the two closed boxes cannot overlap.
        double founderX = centerX + 2.0D * radius + 4.5D;
        stranger2.setPos(founderX, origin.getY(), centerZ + 0.5D);
        boolean started = BattleManager.start(stranger2, true);
        require(started, "a real second encounter starts beside this one" + diagnostics());
        ServerBattle other = BattleManager.containing(stranger2);
        require(other != null && other != battle, "the second encounter is a different encounter");
        GridPos unitCell = other.currentActorCell(unit);
        require(unitCell != null, "the other encounter really admitted its unit" + diagnostics());
        require(other.zone().contains(unitCell), "that cell is inside the other encounter: " + unitCell + diagnostics());
        require(!battle.zone().contains(unitCell),
                "that cell is outside this encounter: " + unitCell + diagnostics());
        zoneGapBlocks = other.zone().bounds().minX() - battle.zone().bounds().maxX();
        require(zoneGapBlocks > 0,
                "the two zones really do not overlap (gap " + zoneGapBlocks + ")" + diagnostics());
        nearestForeign = drowned.distanceTo(unit);
        r5OtherHealthBefore = (int) unit.getHealth();
        // The product's own reach predicate on the live world is a precondition here, not a fallback: this case only
        // means something if a real trident can actually arrive.
        Vec3 spawn = DrownedTridents.spawnPoint(drowned, drowned.position());
        reachesForeign = DrownedTridents.nominalReaches(level, drowned, spawn, unit);
        require(reachesForeign,
                "fixture precondition: the native arc really reaches that body at " + nearestForeign + " blocks"
                        + diagnostics());
        require(!battle.acceptsTrident(drowned.getUUID(), unit),
                "the legality rule refuses that body before it is hit");
        Tactical.LOGGER.info("[Tactical] DROWNED fixture zone edge: radius={} throwerCell={} unitCell={} zoneGap={} "
                        + "nearest={} reaches={}",
                radius, throwerCell, unitCell, zoneGapBlocks, nearestForeign, reachesForeign);
        require(battle.fixtureNativeTrident(drowned, unit), "a real trident was fired at that body");
        scenarioStart = ticks;
    }

    private static GridPos unitCellOf(Mob mob) {
        return new GridPos(mob.getBlockX(), mob.getBlockY(), mob.getBlockZ());
    }

    private static void checkZoneEdge() {
        require(otherEncounterUnit != null, "the other encounter's unit exists" + diagnostics());
        var owner2 = BattleManager.containing(otherEncounterUnit);
        r5OtherOwnershipKept = owner2 != null && owner2 != battle;
        require(r5OtherOwnershipKept, "the other encounter still owns its unit");
        require(otherEncounterUnit.getHealth() == r5OtherHealthBefore,
                "the other encounter's unit keeps its health: " + otherEncounterUnit.getHealth());
        r5OtherEncounterCollision = otherEncounterRefusals >= 1;
        d6ForeignRefusals = otherEncounterRefusals;
        require(r5OtherEncounterCollision,
                "the real trident really reached that body and was refused there" + diagnostics());
        require(markSurvivedRefusal, "the same trident was still ruled when it reached that body");
        Tactical.LOGGER.info("[Tactical] DROWNED fixture zone edge result: refusals={} health={} ownerKept={} "
                        + "zoneGap={} nearest={} markSurvived={}",
                otherEncounterRefusals, otherEncounterUnit.getHealth(), r5OtherOwnershipKept, zoneGapBlocks,
                nearestForeign, markSurvivedRefusal);
    }

    // ------------------------------------------------------------------ reporting

    private static void complete(MinecraftServer server) {
        poseAfterRelease = drownedOutOfWater();
        Tactical.LOGGER.info("[Tactical] DROWNED SELFTEST PASS "
                        + "D1[offHand={} rod={} meleeHits={}] "
                        + "D2[launchSpeed={} nativeRaw={} settled={} airRatio={}] "
                        + "D3[ap={}->{} cancelledJoins={} pickable={}] "
                        + "D4[swimPose={} drift={} spawnInWater={} waterRatio={} shore={} drainedHolds={}] "
                        + "D5[markedFlying={} releasedAfterSettle={} leftover={}] "
                        + "D6[refusals={} nonLiving={} otherEncounterRefusals={} markSurvived={} legalCancelThenIllegal={} "
                        + "zoneGap={} nearestForeign={} reachesForeign={} otherOwnerKept={}] "
                        + "D7[refusedRow={} scenarioTridents={} interruptedRow={}] "
                        + "D8[waterY={} reachedLand={} landFeetInteger={} landStandable={} chunkRefused={} "
                        + "chunkStillUnloaded={} poseClearedOnAdmission={} poseDuringFlight={} poseClearedAfter={} "
                        + "poseRestoredAfterFailed={} timeoutReclaimed={} ownerExitCleaned={} finishCleaned={} "
                        + "lateEnchantRefused={} markInTransaction={} zeroShotFree={}]",
                offHandRefused, rodRefused, meleeHitsByDrowned,
                r2Launch, r2Raw, r2Applied, r2AirRatio,
                r3ApStart, r3ApLow, r3Cancelled, tridentPickable,
                r4Pose, r4Drift, r4SpawnInWater, r4WaterRatio, r4Shore, r4Drained,
                r5Marked, r5Released, r5Leftover,
                r6Refusals, r6NonLiving, d6ForeignRefusals, r6MarkSurvived, r5LegalCancelThenIllegal,
                zoneGapBlocks, nearestForeign, reachesForeign, r5OtherOwnershipKept,
                r7RefusedRow, d7ScenarioTridents, r1BlockedRouteStopped,
                r1WaterY, r1ReachedLand, r1LandFeetInteger, r1LandStandable, r2UnloadedRefused,
                r2StillUnloaded, r3LeftoverCleared, poseDuringFlightSeen, r3PoseClearedAfter,
                r3RestoredAfterFailedAdmission, r5TimeoutReclaimed, r5OwnerExitCleaned, r5FinishCleaned,
                r4LateEnchantRefused, r4PostJoinExceptionCharged, r4ZeroShotExceptionFree);
        finish(server);
    }

    private static boolean drownedOutOfWater() {
        return drowned != null && !drowned.isInWater();
    }

    private static boolean leftoverTridentInWorld() {
        if (lastTrident == null) return false;
        return !lastTrident.isRemoved() && level.getEntity(lastTrident.getId()) == lastTrident;
    }

    private static boolean journalHas(String key) {
        for (ServerPlayer recipient : List.of(owner, stranger)) {
            if (recipient == null) continue;
            for (var payload : ServerPackets.capturedFor(recipient.getUUID())) {
                if (!(payload instanceof BattleLogPacket log)) continue;
                for (BattleLogPacket.Entry entry : log.history()) {
                    if (entry.text().getContents() instanceof TranslatableContents contents
                            && key.equals(contents.getKey())) return true;
                }
            }
        }
        return false;
    }

    private static String diagnostics() {
        return " [stage=" + stage + " joins=" + tridentsJoined + " cancelled=" + cancelledJoins
                + " melee=" + meleeHitsByDrowned + " ap=" + apAtStart + "->" + apLowest
                + " marks=" + (battle == null ? -1 : DrownedTridents.markedFor(battle))
                + " phase=" + (battle == null ? "-" : battle.phaseName())
                + " playerTurn=" + (battle != null && battle.isPlayerTurnNow())
                + " current=" + (battle == null ? "-" : battle.currentActorId())
                + " busy=" + (battle != null && battle.isBusy())
                + " drowned=" + describe(drowned) + "]";
    }

    private static String describe(LivingEntity entity) {
        if (entity == null) return "none";
        return entity.getType().toShortString() + "@" + entity.blockPosition()
                + (entity.isRemoved() ? "(removed)" : "") + (entity.isAlive() ? "" : "(dead)");
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        if (battle != null) battle.finish(BattleText.literal("fixture: complete"));
        BattleManager.reset();
        if (lastTrident != null && !lastTrident.isRemoved()) lastTrident.discard();
        if (drowned != null) drowned.discard();
        if (bystander != null) bystander.discard();
        if (nonLiving != null) nonLiving.discard();
        BattleEffects.clear();
        ServerPackets.stopCapture();
        for (ServerPlayer player : List.of(owner, stranger)) {
            if (player != null && !player.isRemoved()) {
                level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            }
        }
        for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        server.halt(false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
