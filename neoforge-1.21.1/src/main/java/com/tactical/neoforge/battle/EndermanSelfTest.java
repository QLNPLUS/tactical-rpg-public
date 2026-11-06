package com.tactical.neoforge.battle;

import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.BattleRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Real loaded-server fixture for the enderman ruleset: {@code -Dtactical.selftest=enderman}.
 *
 * <p>F proves the foundation every other group stands on: the encounter can drive vanilla's own
 * {@code EnderMan#teleport(DDD)} - not {@code randomTeleport}, not {@code setPos} with hand-made particles - and
 * a cancelled {@code EnderEntity} event really stops it. Nothing is charged there because no encounter runs.</p>
 *
 * <p>E1-E8 run inside a real {@link ServerBattle}: a real roster, a real zone, the real quoted price, the real
 * native entry, real arrows and a real trident that really fly, and the authoritative server state read back
 * after every action. Every number the encounter charges is compared with the encounter's own state, never with
 * a client mirror.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class EndermanSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer second;
    private static ServerBattle battle;
    private static final List<BlockPos> forced = new ArrayList<>();
    private static int wait;
    private static boolean enemyPhaseSeen;
    private static int driveStage = -1;

    // ---- F: the native entry and the event veto ------------------------------------------------------------
    private static EnderMan enderman;
    private static boolean cancelTeleports;
    private static int teleportEvents;
    private static boolean eventWasCanceled;
    private static boolean nativeReturned;
    private static boolean reallyMoved;
    private static double movedDistance;
    private static boolean landedOnFloor;
    private static boolean vetoRefused;
    private static boolean vetoKeptPosition;
    private static boolean feetInteger;

    /** When set, the fixture's own LOWEST listener re-points every ender teleport to this point. */
    private static Vec3 repointTo;

    // ---- E1: an own-turn relocation with a paid melee follow-up --------------------------------------------
    private static int e1ApStart = -1;
    private static int e1ApEnd = -1;
    private static int e1Moved = -1;
    private static boolean e1CellSynced;
    private static boolean e1MeleeFollowUp;
    private static boolean e1OldCellFreed;
    private static GridPos e1From;
    private static GridPos e1To;

    // ---- E2: event re-points and the body gate -------------------------------------------------------------
    private static boolean e2LegalRepointUsed;
    private static boolean e2LegalCellSynced;
    private static int e2ApStart = -1;
    private static int e2ApEnd = -1;
    private static boolean e2NoMelee;
    private static int e2RefusedOccupied = -1;
    private static int e2RefusedDanger = -1;
    private static int e2RefusedZone = -1;
    private static int e2RefusedUnloaded = -1;
    private static int e2RangeApStart = -1;
    private static int e2RangeApEnd = -1;
    private static int e2RangeNativeCalls = -1;
    private static boolean e2RangeKept;
    private static Vec3 e2RangePosBefore;
    private static int e2SlabNativeCalls = -1;
    private static boolean e2SlabKept;
    private static int e2SlabApStart = -1;
    private static int e2SlabApEnd = -1;
    private static Vec3 e2SlabPosBefore;
    private static int e2NoopNativeCalls = -1;
    private static boolean e2NoopKept;
    private static int e2NoopApStart = -1;
    private static int e2NoopApEnd = -1;
    private static Vec3 e2NoopPosBefore;
    private static boolean e2LowCeilingRefused;
    private static boolean e2ClearBodyAllowed;
    private static boolean e2ThirdBlockRefused;

    // ---- E3/E4: bounded instant reactions ------------------------------------------------------------------
    private static int e3ArrowsFired;
    private static int e3NativeCalls;
    private static int e3Moves;
    private static int e3ApStart = -1;
    private static int e3ApEnd = -1;
    private static float e3HpStart = -1.0F;
    private static float e3HpAfterArrows = -1.0F;
    private static boolean e3ImmuneToArrows;
    private static boolean e3SameTurnEpoch;
    private static boolean e3OwnerApKept;
    private static int e3OwnerApStart = -1;
    private static Vec3 e3PosBefore;
    private static float e4MeleeHit = -1.0F;
    private static boolean e4MeleeMoved;
    private static float e4MeleeHpStart = -1.0F;
    private static int e4MeleeApStart = -1;
    private static int e4MeleeApAfter = -1;
    private static Vec3 e4MeleePosBefore;
    private static boolean e4ArrowAfterMelee;
    private static int e4ApAfterArrow = -1;
    private static int e4PoorApStart = -1;
    private static int e4PoorApAfter = -1;
    private static float e4PoorHpStart = -1.0F;
    private static float e4PoorHpAfter = -1.0F;
    private static int e4PoorNativeCalls = -1;
    private static boolean e4AnswerNegative;
    private static boolean e4AnswerNegativeHeld;

    private static boolean e4PoorMoved;
    private static Vec3 e4PoorPosBefore;
    private static int e4VetoApStart = -1;
    private static int e4VetoNativeCalls = -1;
    private static boolean e4VetoKept;
    private static int e4VetoApSpent = -1;
    private static boolean e4AfterVetoMoved;
    private static int e4AfterVetoAp = -1;
    private static int e4VetoPhase;
    private static Vec3 e4VetoPosBefore;
    private static int e4ShaftApStart = -1;
    private static int e4ShaftAp = -1;
    private static int e4ShaftNativeCalls = -1;
    private static boolean e4ShaftKept;
    private static boolean e4OpenedMoved;
    private static float e4StepHpStart = -1.0F;
    private static float e4StepHpAfter = -1.0F;
    private static int e4StepApStart = -1;
    private static int e4StepApSpent = -1;
    private static int e4StepNativeCalls = -1;
    private static int e4StepArrows;
    private static int e4StepHandovers;
    private static boolean e4StepWalking;
    private static GridPos e4StepCellBefore;
    private static Vec3 e4StepStart;
    private static int e4OpenedAp = -1;
    private static Vec3 e4ShaftPosBefore;

    // ---- E5: nobody else's attack may cost this unit anything ---------------------------------------------
    private static Mob bystander;
    private static EnderMan secondEnderman;
    private static int e5NativeCalls;
    private static float e5HpStart = -1.0F;
    private static float e5HpAfter = -1.0F;
    private static int e5ApBefore = -1;
    private static int e5ApAfter = -1;
    private static Vec3 e5PosBefore;
    private static boolean e5NoMove;
    private static int e5SecondApBefore = -1;
    private static int e5SecondApAfter = -1;
    private static boolean e5TridentMoved;
    private static int e5TridentApSpent = -1;
    private static int e5Phase;
    private static int e5OwnEventsBefore = -1;
    private static int e5OwnApBefore = -1;
    private static GridPos e5OwnCellBefore;
    private static int e5OwnTurnEvents = -1;
    private static int e5OwnTurnAp = -1;
    private static boolean e5OwnTurnCell;

    // ---- E6: the water clock ------------------------------------------------------------------------------
    private static float e6HpQuietStart = -1.0F;
    private static int e6RainPlayerTurnHits = -1;
    private static int e6RainOwnTurnHits = -1;
    private static float e6DrownAmount = -1.0F;
    private static boolean e6StayedPut;
    private static boolean e6Sheltered;
    private static boolean e6StillAlive;
    private static boolean e6Flooded;
    private static float e6BottleDamage = -1.0F;
    private static int e6SecondPlayerTurnHits = -1;
    private static int e6SecondOwnTurnHits = -1;

    // ---- E8: release, carried block and the untouched vanilla enderman -------------------------------------
    private static BlockState carriedBefore;
    private static String handBefore;
    private static Vec3 positionBeforeFinish;
    private static Vec3 admissionPosition;
    private static boolean e8HandlerCleared;
    private static boolean e8CarriedKept;
    private static boolean e8HandKept;
    private static boolean e8PositionKept;
    private static boolean e8WildTeleported;
    private static boolean e8WildHasNoHandler;
    private static Shulker shulker;
    private static boolean e8ShulkerIgnored;

    private static final List<String> damageLog = new ArrayList<>();

    private EndermanSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "enderman".equals(System.getProperty("tactical.selftest"));
    }

    /** Counts and re-points native ender teleports: the same event vanilla fires inside {@code teleport(DDD)}. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleportEvent(EntityTeleportEvent.EnderEntity event) {
        if (!enabled || !(event.getEntity() instanceof EnderMan)) return;
        teleportEvents++;
        if (cancelTeleports) {
            event.setCanceled(true);
            eventWasCanceled = true;
            return;
        }
        Vec3 target = repointTo;
        if (target != null) {
            event.setTargetX(target.x);
            event.setTargetY(target.y);
            event.setTargetZ(target.z);
        }
    }

    /** Authoritative damage on this fixture's endermen, kept apart from "the hurt call returned something". */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || level == null) return;
        if (!(event.getEntity() instanceof EnderMan)) return;
        damageLog.add(event.getSource().getMsgId() + ":" + event.getNewDamage());
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            require(ticks < 20_000, "enderman fixture completion deadline" + diagnostics());
            switch (stage) {
                case 0 -> { setup(event.getServer()); stage = 1; }
                case 1 -> { if (ticks < 100) return; checkNativeTeleport(); stage = 2; }
                case 2 -> { checkVeto(); stage = 3; }
                case 3 -> beginOwnTurnRelocation();
                case 4 -> { if (driveEnemyTurn()) return; checkOwnTurnRelocation(); stage = 5; }
                case 5 -> beginLegalRepoint();
                case 6 -> { if (driveEnemyTurn()) return; checkLegalRepoint(); stage = 7; }
                case 7 -> { checkIllegalRepoints(); checkBodyGate(); stage = 8; }
                case 8 -> beginArrowReaction();
                case 9 -> { if (stepArrows()) return; checkArrowReaction(); stage = 10; }
                case 10 -> beginFreshQuotaMelee();
                case 11 -> { if (stepFreshQuotaMelee()) return; stage = 12; }
                case 12 -> beginUnaffordableReaction();
                case 13 -> { if (stepUnaffordableReaction()) return; stage = 14; }
                case 14 -> beginVetoedReaction();
                case 15 -> { if (stepVetoedReaction()) return; stage = 16; }
                case 16 -> beginSealedReaction();
                case 17 -> { if (stepSealedReaction()) return; stage = 18; }
                case 18 -> beginRangeReject();
                case 19 -> { if (driveEnemyTurn()) return; checkRangeReject(); stage = 20; }
                case 20 -> beginSlabReject();
                case 21 -> { if (driveEnemyTurn()) return; checkSlabReject(); stage = 22; }
                case 22 -> beginNoopReject();
                case 23 -> { if (driveEnemyTurn()) return; checkNoopReject(); stage = 24; }
                case 24 -> beginSelfStepReaction();
                case 25 -> { if (stepSelfStepReaction()) return; stage = 26; }
                case 26 -> beginStrangerAndSecond();
                case 27 -> { if (stepStrangerAndSecond()) return; stage = 28; }
                case 28 -> beginWaterScene();
                case 29 -> { if (waitPlayerTicks(60)) return; checkQuietA(); stage = 30; }
                case 30 -> {
                    if (!e6Flooded) {
                        // Fresh water under the feet it really holds now, so this turn's exposure is real and
                        // not a leftover of the quiet window.
                        poolWithShelf(enderman.blockPosition());
                        e6Flooded = true;
                        wait = 0;
                        return;
                    }
                    if (waitTicks(3)) return;
                    if (driveEnemyTurn()) return;
                    checkOwnTurnA();
                    wait = 0;
                    stage = 31;
                }
                case 31 -> { if (wait == 0) fireWaterBottle(); if (waitTicks(40)) return; checkWaterBottle(); stage = 32; }
                case 32 -> { if (waitPlayerTicks(60)) return; checkQuietB(); stage = 33; }
                case 33 -> { if (driveEnemyTurn()) return; checkOwnTurnB(); stage = 34; }
                case 34 -> beginRelease();
                case 35 -> { checkRelease(); stage = 36; }
                case 36 -> { checkWildEnderman(); complete(event.getServer()); }
                default -> { }
            }
        } catch (AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] ENDERMAN SELFTEST FAILED at stage {}: {}", stage, failure.getMessage());
            fail(event.getServer());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] ENDERMAN SELFTEST CRASHED at stage {}", stage, failure);
            fail(event.getServer());
        }
    }

    // ------------------------------------------------------------------ arena

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(true, server);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        // The vanilla enderman attack number is only readable as its own constant on the neutral difficulty.
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 20, spawn.getY() + 24), spawn.getZ());
        for (int cx = (origin.getX() - 32) >> 4; cx <= (origin.getX() + 32) >> 4; cx++) {
            for (int cz = (origin.getZ() - 32) >> 4; cz <= (origin.getZ() + 32) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 8; dy++) set(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState());
            }
        }
        owner = player(server, "tactical-enderman-owner", "EndermanOwner");
        second = player(server, "tactical-enderman-second", "EndermanSecond");
        second.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 8.5D);
        // A reused run directory carries bodies from an aborted run; starting from an empty stage is what keeps
        // "the encounter admitted one enderman" from meaning "last night's fixture was still standing there".
        clearWorkingBox();
        // The foundation case runs before any encounter exists: no handler, therefore every vanilla behaviour.
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
    }

    private static void clearWorkingBox() {
        if (level == null || origin == null) return;
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(40))) {
            if (!(leftover instanceof ServerPlayer)) leftover.discard();
        }
    }

    private static EnderMan spawnEnderman(double dx, double dz) {
        EnderMan mob = (EnderMan) EntityType.ENDERMAN.create(level);
        require(mob != null, "an enderman exists in this version");
        mob.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        return mob;
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
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0.1D);
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        player.setHealth(200);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 64));
        level.addNewPlayer(player);
        return player;
    }

    /**
     * Start a real encounter with the enderman inside its zone, and face the owner exactly that far away.
     *
     * <p>The player acts first (its initiative is far higher), which is what lets the fixture hand the turn over
     * and watch the enderman's own turn resolve against authoritative state.</p>
     */
    private static void startBattle(double ownerDistance) {
        startBattleAt(new Vec3(origin.getX() + 0.5D + ownerDistance, origin.getY(), origin.getZ() + 0.5D));
    }

    /** The same admission, with the owner standing exactly where the scene needs it. */
    private static void startBattleAt(Vec3 ownerFeet) {
        repointTo = null;
        teleportEvents = 0;
        enemyPhaseSeen = false;
        wait = 0;
        owner.setPos(ownerFeet.x, ownerFeet.y, ownerFeet.z);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        com.tactical.neoforge.network.ServerPackets.startCapture();
        BattleManager.reset();
        if (DeveloperTrace.enabled()) {
            BlockPos at = owner.blockPosition();
            Tactical.LOGGER.info("[Tactical] ENDERMAN fixture founder probe: pos={} block={} standable={} "
                            + "below={} feet={} head={} standY-1={} standY+1={}", owner.position(), at,
                    LevelGridWorld.isStandable(level, at.getX(), at.getY(), at.getZ()),
                    level.getBlockState(at.below()), level.getBlockState(at), level.getBlockState(at.above()),
                    LevelGridWorld.isStandable(level, at.getX(), at.getY() - 1, at.getZ()),
                    LevelGridWorld.isStandable(level, at.getX(), at.getY() + 1, at.getZ()));
        }
        require(BattleManager.start(owner, true), "the enderman encounter starts");
        battle = BattleManager.current();
        require(battle != null, "the encounter exists");
        require(battle.isParticipant(enderman), "the enderman is a real participant" + diagnostics());
        require(enderman.isNoAi(), "the encounter really holds the unit's own AI");
        require(battle.isPlayerTurnNow(), "the high-initiative owner acts first" + diagnostics());
        require(((TacticalEndermanAccess) enderman).tactical$landingHandler() != null,
                "the admitted enderman answers one encounter handler");
    }

    private static void nextScenario() {
        if (battle != null) battle.finish(BattleText.literal("fixture: next scenario"));
        BattleManager.reset();
        battle = null;
        repointTo = null;
        cancelTeleports = false;
        wait = 0;
        if (enderman != null) {
            enderman.discard();
            enderman = null;
        }
        if (secondEnderman != null) {
            secondEnderman.discard();
            secondEnderman = null;
        }
        if (bystander != null) {
            bystander.discard();
            bystander = null;
        }
        // Everything this fixture itself put there, projectiles included: a real arrow lying on the floor is a
        // body a landing point may not contain, so it would silently poison the next scenario's gate.
        for (Entity stray : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(30))) {
            if (!(stray instanceof ServerPlayer)) stray.discard();
        }
        damageLog.clear();
        resetStage();
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
    }

    /**
     * Put the flat stage back exactly as {@link #setup} built it.
     *
     * <p>Scenes really change the terrain - a pool, a shelf, a sealed shaft, a slab - and blocks are not entities,
     * so they would otherwise leak into the next scene and silently change what "dry", "supported" or "standable"
     * mean there.</p>
     */
    private static void resetStage() {
        if (level == null || origin == null) return;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                set(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 8; dy++) set(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Hand the turn over exactly once and wait for it to come back; true while the enderman still acts. */
    private static boolean driveEnemyTurn() {
        if (battle == null || battle.finished()) return false;
        // One hand-over per stage: the marker makes a repeated call in the same stage wait for the answer
        // instead of passing the slot on again, which is what keeps a scenario to exactly one own turn.
        if (driveStage != stage) {
            driveStage = stage;
            enemyPhaseSeen = false;
        }
        if (!battle.isPlayerTurnNow()) {
            enemyPhaseSeen = true;
            return true;
        }
        // The enderman's own turn already ran and handed the slot back: this is the moment to judge it.
        if (enemyPhaseSeen) return false;
        battle.endPlayerTurn();
        return true;
    }

    private static boolean waitTicks(int count) {
        wait++;
        return wait < count;
    }

    /** Ticks that belong to the player's own turn: nothing may damage a controlled unit on wall-clock time. */
    private static boolean waitPlayerTicks(int count) {
        if (battle != null && !battle.finished() && !battle.isPlayerTurnNow()) return true;
        return waitTicks(count);
    }

    // ------------------------------------------------------------------ F: the foundation

    private static void checkNativeTeleport() {
        Vec3 before = enderman.position();
        teleportEvents = 0;
        nativeReturned = EndermanNative.teleportTo(enderman, origin.getX() + 6.5D, origin.getY(), origin.getZ() + 0.5D);
        Vec3 after = enderman.position();
        movedDistance = after.distanceTo(before);
        reallyMoved = movedDistance > 3.0D;
        BlockPos feet = enderman.blockPosition();
        feetInteger = Math.abs(enderman.getY() - origin.getY()) < 1.0E-6D;
        landedOnFloor = level.getBlockState(feet.below()).isSolid();
        require(teleportEvents >= 1, "vanilla's own EntityTeleportEvent.EnderEntity really fired");
        require(nativeReturned, "the native teleport entry returned true: " + diagnostics());
        require(reallyMoved, "the entity really moved, distance=" + movedDistance);
        require(landedOnFloor, "the landing point really has a support: " + feet);
        require(feetInteger, "the landing point sits on the support height: y=" + enderman.getY());
        require(level.noCollision(enderman, enderman.getBoundingBox()),
                "the landed body does not intersect a block");
    }

    private static void checkVeto() {
        Vec3 before = enderman.position();
        cancelTeleports = true;
        boolean answer = EndermanNative.teleportTo(enderman, origin.getX() - 6.5D, origin.getY(), origin.getZ() + 0.5D);
        cancelTeleports = false;
        double moved = enderman.position().distanceTo(before);
        vetoRefused = !answer && eventWasCanceled;
        vetoKeptPosition = moved < 0.01D;
        require(eventWasCanceled, "the veto really ran inside the native entry");
        require(vetoRefused, "a cancelled teleport is refused: answer=" + answer + diagnostics());
        require(vetoKeptPosition, "a refused teleport never moves the body, moved=" + moved);
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture F: native={} moved={} distance={} floor={} integer={} "
                        + "vetoRefused={} keptPosition={}",
                nativeReturned, reallyMoved, movedDistance, landedOnFloor, feetInteger, vetoRefused, vetoKeptPosition);
        enderman.discard();
        enderman = null;
    }

    // ------------------------------------------------------------------ E1: own turn relocation + paid melee

    private static void beginOwnTurnRelocation() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(9.0D);
        e1ApStart = battle.actorAp(enderman);
        e1From = battle.currentActorCell(enderman);
        wait = 0;
        stage = 4;
    }

    private static void checkOwnTurnRelocation() {
        e1ApEnd = battle.actorAp(enderman);
        e1To = battle.currentActorCell(enderman);
        e1Moved = e1To == null ? -1 : (int) Math.round(e1From.horizontalDistance(e1To));
        e1CellSynced = e1To != null && e1To.equals(new GridPos(enderman.blockPosition().getX(),
                enderman.blockPosition().getY(), enderman.blockPosition().getZ()));
        e1MeleeFollowUp = owner.getHealth() < owner.getMaxHealth();
        e1OldCellFreed = e1To != null && !e1To.equals(e1From);
        require(e1Moved > 0, "the enderman really relocated inside its own turn" + diagnostics());
        require(e1CellSynced, "the encounter's own cell is the cell the body really stands in: " + e1To);
        require(e1MeleeFollowUp, "the relocation was followed by a real melee on the owner");
        require(e1OldCellFreed, "the old cell is no longer this unit's cell");
        int teleportPrice = BattleRules.ENDERMAN_TELEPORT_COST;
        int meleePrice = ApCalculator.paidActionCost(ApCalculator.MELEE_COST, 1);
        require(e1ApStart - e1ApEnd == teleportPrice + meleePrice,
                "the relocation and the raised melee price were both charged (" + e1ApStart + " -> " + e1ApEnd
                        + ", expected " + (teleportPrice + meleePrice) + ")" + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E1: ap={}->{} from={} to={} moved={} cellSynced={} "
                        + "melee={} oldCellFreed={} ownerHp={}",
                e1ApStart, e1ApEnd, e1From, e1To, e1Moved, e1CellSynced, e1MeleeFollowUp, e1OldCellFreed,
                owner.getHealth());
        nextScenario();
        stage = 5;
    }

    // ------------------------------------------------------------------ E2: event re-points and the body gate

    private static void beginLegalRepoint() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(9.0D);
        e2ApStart = battle.actorAp(enderman);
        // A different, fully legal point two cells north of the owner and about 6.3 blocks away from the body:
        // only the event's own answer produces it, and it stays inside this ruleset's 8 block bound.
        repointTo = new Vec3(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 2.5D);
        wait = 0;
        stage = 6;
    }

    private static void checkLegalRepoint() {
        e2ApEnd = battle.actorAp(enderman);
        GridPos cell = battle.currentActorCell(enderman);
        GridPos expected = new GridPos(origin.getX() + 6, origin.getY(), origin.getZ() + 2);
        e2LegalRepointUsed = teleportEvents >= 1;
        e2LegalCellSynced = expected.equals(cell);
        e2NoMelee = owner.getHealth() == owner.getMaxHealth();
        require(e2LegalRepointUsed, "the native entry really fired its event for the re-point" + diagnostics());
        require(e2LegalCellSynced, "the point the event really chose is the committed cell: " + cell
                + " (expected " + expected + ")" + diagnostics());
        require(e2NoMelee, "a landing two cells away does not melee");
        require(e2LegalCellSynced, "the re-point stays inside the 8 block bound: " + expected);
        require(e2ApStart - e2ApEnd == BattleRules.ENDERMAN_TELEPORT_COST,
                "exactly the relocation itself was charged (" + e2ApStart + " -> " + e2ApEnd + ")" + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 legal re-point: events={} cell={} ap={}->{} melee={}",
                teleportEvents, cell, e2ApStart, e2ApEnd, e2NoMelee);
        nextScenario();
        stage = 7;
    }

    /**
     * The gate's own answers for the destinations an event could re-point to.
     *
     * <p>These are probes of the same gate every commit uses: each one is a point the world really contains, and
     * the answer has to be "refused" for its own reason. The end-to-end refusal of a legal-but-out-of-range, a
     * slabbish and a no-op destination is driven through real own turns in the E2 scenes below.</p>
     */
    private static void checkIllegalRepoints() {
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(5.0D);
        Vec3 ownerFeet = new Vec3(owner.getX(), owner.getY(), owner.getZ());
        e2RefusedOccupied = EndermanLanding.legal(level, enderman,
                new EndermanLanding.Landing(new GridPos((int) Math.floor(ownerFeet.x), (int) ownerFeet.y,
                        (int) Math.floor(ownerFeet.z)), new Vec3(Math.floor(ownerFeet.x) + 0.5D, ownerFeet.y,
                        Math.floor(ownerFeet.z) + 0.5D)),
                cell -> cell.equals(battle.currentActorCell(owner)), battle.zone()) ? 0 : 1;
        // A sealed lava cell: real lava, walled in so it cannot flow across the stage and poison later scenes.
        BlockPos lava = origin.offset(4, 0, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    set(lava.offset(dx, dy, dz),
                            dx == 0 && dz == 0 ? Blocks.LAVA.defaultBlockState() : Blocks.STONE.defaultBlockState());
                }
            }
        }
        e2RefusedDanger = EndermanLanding.legal(level, enderman,
                new EndermanLanding.Landing(new GridPos(origin.getX() + 4, origin.getY(), origin.getZ() + 4),
                        new Vec3(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 4.5D)),
                cell -> false, battle.zone()) ? 0 : 1;
        // A real, supported, loaded floor beyond this encounter's zone: only the zone refuses it.
        for (int dx = 38; dx <= 41; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= 4; dy++) set(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        }
        EndermanLanding.Landing far = new EndermanLanding.Landing(
                new GridPos(origin.getX() + 40, origin.getY(), origin.getZ()),
                new Vec3(origin.getX() + 40.5D, origin.getY(), origin.getZ() + 0.5D));
        require(EndermanLanding.legal(level, enderman, far, cell -> false, null),
                "the far floor really is a legal landing on its own: only the zone may refuse it");
        e2RefusedZone = EndermanLanding.legal(level, enderman, far, cell -> false, battle.zone()) ? 0 : 1;
        e2RefusedUnloaded = EndermanLanding.resolve(level, enderman,
                origin.getX() + 500.5D, origin.getY(), origin.getZ() + 500.5D) == null ? 1 : 0;
        require(e2RefusedOccupied == 1, "a destination inside another unit is refused");
        require(e2RefusedDanger == 1, "a destination inside lava is refused");
        require(e2RefusedZone == 1, "a destination outside this encounter's zone is refused");
        require(e2RefusedUnloaded == 1, "a destination in unloaded columns is refused by the resolve step");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 illegal: occupied={} danger={} zone={} unloaded={}",
                e2RefusedOccupied, e2RefusedDanger, e2RefusedZone, e2RefusedUnloaded);
    }

    private static void checkBodyGate() {
        // The 2.9 high body needs three blocks of room: a two block pocket is refused, three blocks are not.
        BlockPos low = origin.offset(-6, 0, -6);
        set(low.offset(0, 2, 0), Blocks.STONE.defaultBlockState());
        set(low.offset(0, 3, 0), Blocks.STONE.defaultBlockState());
        e2LowCeilingRefused = !EndermanLanding.legal(level, enderman,
                new EndermanLanding.Landing(new GridPos(low.getX(), low.getY(), low.getZ()),
                        new Vec3(low.getX() + 0.5D, low.getY(), low.getZ() + 0.5D)), cell -> false, null);
        BlockPos clear = origin.offset(-8, 0, -8);
        for (int dy = 2; dy <= 4; dy++) set(clear.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
        e2ClearBodyAllowed = EndermanLanding.legal(level, enderman,
                new EndermanLanding.Landing(new GridPos(clear.getX(), clear.getY(), clear.getZ()),
                        new Vec3(clear.getX() + 0.5D, clear.getY(), clear.getZ() + 0.5D)), cell -> false, null);
        BlockPos twoHigh = origin.offset(-10, 0, -10);
        set(twoHigh.offset(0, 2, 0), Blocks.STONE.defaultBlockState());
        e2ThirdBlockRefused = !EndermanLanding.legal(level, enderman,
                new EndermanLanding.Landing(new GridPos(twoHigh.getX(), twoHigh.getY(), twoHigh.getZ()),
                        new Vec3(twoHigh.getX() + 0.5D, twoHigh.getY(), twoHigh.getZ() + 0.5D)), cell -> false, null);
        require(e2LowCeilingRefused, "a two block ceiling is refused for a 2.9 high body");
        require(e2ThirdBlockRefused, "the third body block is really required");
        require(e2ClearBodyAllowed, "a clear three block body is allowed" + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 body: lowCeilingRefused={} thirdBlockRefused={} "
                + "clearAllowed={}", e2LowCeilingRefused, e2ThirdBlockRefused, e2ClearBodyAllowed);
        nextScenario();
        if (shulker == null) {
            shulker = (Shulker) EntityType.SHULKER.create(level);
            if (shulker != null) {
                shulker.setPos(origin.getX() + 18.5D, origin.getY(), origin.getZ() + 18.5D);
                level.addFreshEntity(shulker);
            }
        }
        stage = 8;
    }

    // ------------------------------------------------------------------ E2: the event's own answer, driven

    /**
     * A legal, supported, in-zone point that is simply further than this ruleset allows.
     *
     * <p>Driven through a real own turn: the native entry is reached, its event re-points the destination, and the
     * point that would really be used is refused because it is beyond the 8 block bound of where the body stood
     * when the request began - so nothing moves and nothing is charged.</p>
     */
    private static void beginRangeReject() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(9.0D);
        e2RangeApStart = battle.actorAp(enderman);
        e2RangePosBefore = enderman.position();
        teleportEvents = 0;
        // About 9.5 blocks away from the body, fully supported and inside the zone.
        repointTo = new Vec3(origin.getX() + 9.5D, origin.getY(), origin.getZ() + 3.5D);
        wait = 0;
        stage = 19;
    }

    private static void checkRangeReject() {
        e2RangeApEnd = battle.actorAp(enderman);
        e2RangeNativeCalls = teleportEvents;
        e2RangeKept = enderman.position().distanceTo(e2RangePosBefore) < 0.5D;
        require(e2RangeNativeCalls >= 1, "the native entry really ran and its event answered" + diagnostics());
        require(e2RangeKept, "a destination beyond the 8 block bound never moves the body: pos="
                + enderman.position() + " from=" + e2RangePosBefore + diagnostics());
        require(e2RangeApEnd == e2RangeApStart, "and nothing is charged (" + e2RangeApStart + " -> "
                + e2RangeApEnd + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 range: nativeCalls={} kept={} ap={}->{}",
                e2RangeNativeCalls, e2RangeKept, e2RangeApStart, e2RangeApEnd);
        nextScenario();
        stage = 20;
    }

    /** A point whose only support is a bottom slab: the real surface is half a block below the feet. */
    private static void beginSlabReject() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(9.0D);
        set(origin.offset(3, 0, 0), Blocks.STONE_SLAB.defaultBlockState());
        e2SlabApStart = battle.actorAp(enderman);
        e2SlabPosBefore = enderman.position();
        teleportEvents = 0;
        // The event re-points to the block height above the slab, where the body would really hang in the air.
        repointTo = new Vec3(origin.getX() + 3.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D);
        wait = 0;
        stage = 21;
    }

    private static void checkSlabReject() {
        e2SlabApEnd = battle.actorAp(enderman);
        e2SlabNativeCalls = teleportEvents;
        e2SlabKept = enderman.position().distanceTo(e2SlabPosBefore) < 0.5D;
        require(e2SlabNativeCalls >= 1, "the native entry really ran for the slab destination" + diagnostics());
        require(e2SlabKept, "a slabbish destination whose real surface is not at feet height never moves the body: "
                + enderman.position() + diagnostics());
        require(e2SlabApEnd == e2SlabApStart, "and nothing is charged (" + e2SlabApStart + " -> " + e2SlabApEnd + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 slab: nativeCalls={} kept={} ap={}->{}",
                e2SlabNativeCalls, e2SlabKept, e2SlabApStart, e2SlabApEnd);
        nextScenario();
        stage = 22;
    }

    /** The same parameters the body already occupies: a paid no-op, refused and free. */
    private static void beginNoopReject() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(9.0D);
        e2NoopApStart = battle.actorAp(enderman);
        e2NoopPosBefore = enderman.position();
        teleportEvents = 0;
        repointTo = new Vec3(e2NoopPosBefore.x, e2NoopPosBefore.y, e2NoopPosBefore.z);
        wait = 0;
        stage = 23;
    }

    private static void checkNoopReject() {
        e2NoopApEnd = battle.actorAp(enderman);
        e2NoopNativeCalls = teleportEvents;
        e2NoopKept = enderman.position().distanceTo(e2NoopPosBefore) < 0.01D;
        require(e2NoopNativeCalls >= 1, "the native entry really ran for the no-op destination" + diagnostics());
        require(e2NoopKept, "a relocation onto the cell the body already occupies is refused and changes nothing");
        require(e2NoopApEnd == e2NoopApStart, "and it is free (" + e2NoopApStart + " -> " + e2NoopApEnd + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E2 noop: nativeCalls={} kept={} ap={}->{}",
                e2NoopNativeCalls, e2NoopKept, e2NoopApStart, e2NoopApEnd);
        nextScenario();
        stage = 24;
    }

    // ------------------------------------------------------------------ E3/E4: bounded instant reactions

    private static void beginArrowReaction() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(4.0D);
        e3ApStart = battle.actorAp(enderman);
        e3OwnerApStart = battle.actorAp(owner);
        e3HpStart = enderman.getHealth();
        e3PosBefore = enderman.position();
        teleportEvents = 0;
        e3NativeCalls = 0;
        e3ArrowsFired = 0;
        e3Moves = 0;
        wait = 0;
        stage = 9;
    }

    /** Five real arrows really flying in one cycle may buy at most one relocation. */
    private static boolean stepArrows() {
        if (e3ArrowsFired < 5 && wait % 4 == 0) {
            fireArrow(owner, enderman);
            e3ArrowsFired++;
        }
        if (e3Moves == 0 && enderman.position().distanceTo(e3PosBefore) > 0.5D) e3Moves = 1;
        return waitTicks(80);
    }

    private static void fireArrow(LivingEntity shooter, LivingEntity target) {
        Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
        Vec3 direction = new Vec3(target.getX(), target.getEyeY(), target.getZ()).subtract(arrow.position());
        arrow.shoot(direction.x, direction.y, direction.z, 2.5F, 0.0F);
        level.addFreshEntity(arrow);
    }

    /**
     * A native projectile damage source owned by this encounter's opponent, for direct answer checks.
     *
     * <p>This is an API contract probe, not flight evidence: it asks the product's own hurt path what it
     * answers, while the real arrows elsewhere in the fixture carry the flight evidence.</p>
     */
    private static DamageSource projectileProbe() {
        Arrow probe = new Arrow(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        probe.setPos(enderman.position().add(0.0D, 1.0D, 0.0D));
        return level.damageSources().arrow(probe, owner);
    }

    private static void fireTrident(LivingEntity shooter, LivingEntity target) {
        ThrownTrident trident = new ThrownTrident(level, shooter, new ItemStack(Items.TRIDENT));
        trident.setPos(shooter.getX(), shooter.getEyeY() - 0.1D, shooter.getZ());
        Vec3 direction = target.position().add(0, 1.0D, 0).subtract(trident.position());
        trident.shoot(direction.x, direction.y, direction.z, 2.0F, 0.0F);
        level.addFreshEntity(trident);
    }

    private static void checkArrowReaction() {
        e3ApEnd = battle.actorAp(enderman);
        e3HpAfterArrows = enderman.getHealth();
        e3NativeCalls = teleportEvents;
        e3ImmuneToArrows = e3HpAfterArrows >= e3HpStart;
        e3SameTurnEpoch = battle.isPlayerTurnNow();
        e3OwnerApKept = battle.actorAp(owner) == e3OwnerApStart;
        require(e3ArrowsFired == 5, "five real arrows were fired at the unit" + diagnostics());
        require(e3Moves == 1, "five arrows in one cycle really bought exactly one relocation" + diagnostics());
        require(e3ApEnd == e3ApStart - BattleRules.ENDERMAN_TELEPORT_COST,
                "the reaction cost this unit its own fixed price once (" + e3ApStart + " -> " + e3ApEnd + ")");
        require(e3ImmuneToArrows, "the native projectile immunity is kept: hp " + e3HpStart + " -> " + e3HpAfterArrows);
        require(e3SameTurnEpoch, "the reaction never advanced the queue or the epoch");
        require(e3OwnerApKept, "the attacker's own AP is untouched by the reaction");
        require(e3NativeCalls <= BattleRules.ENDERMAN_HURT_CANDIDATES,
                "one hurt opens at most " + BattleRules.ENDERMAN_HURT_CANDIDATES + " landing attempts, saw "
                        + e3NativeCalls);
        require(e3NativeCalls <= 1, "the 64 attempt loop of every hurt really shared one search, saw "
                + e3NativeCalls + " native calls" + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E3/E4: arrows={} nativeCalls={} moves={} ap={}->{} "
                        + "hp={}->{} immune={} sameTurn={} ownerAp={}",
                e3ArrowsFired, e3NativeCalls, e3Moves, e3ApStart, e3ApEnd, e3HpStart, e3HpAfterArrows,
                e3ImmuneToArrows, e3SameTurnEpoch, e3OwnerApKept);
    }

    /**
     * A plain melee on a unit whose reaction quota is still unused.
     *
     * <p>This is the case the first review caught: vanilla never dodges a melee, so neither may this ruleset - and
     * the quota has to be provably untouched afterwards, which is why the next real arrow must still react.</p>
     */
    private static void beginFreshQuotaMelee() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(2.0D);
        e4MeleeHpStart = enderman.getHealth();
        e4MeleeApStart = battle.actorAp(enderman);
        e4MeleePosBefore = enderman.position();
        wait = 0;
        stage = 11;
    }

    private static boolean stepFreshQuotaMelee() {
        if (wait == 1) {
            owner.attack(enderman);
            e4MeleeHit = e4MeleeHpStart - enderman.getHealth();
            e4MeleeMoved = enderman.position().distanceTo(e4MeleePosBefore) > 0.5D;
            e4MeleeApAfter = battle.actorAp(enderman);
        }
        if (wait == 25) fireArrow(owner, enderman);
        if (wait == 55) {
            e4ArrowAfterMelee = enderman.position().distanceTo(e4MeleePosBefore) > 0.5D;
            e4ApAfterArrow = battle.actorAp(enderman);
        }
        if (waitTicks(80)) return true;
        require(e4MeleeHit > 0.0F, "a plain melee still really damages the enderman: " + e4MeleeHit + diagnostics());
        require(!e4MeleeMoved, "a plain melee never buys a free teleport" + diagnostics());
        require(e4MeleeApAfter == e4MeleeApStart, "a plain melee never costs this unit a reaction ("
                + e4MeleeApStart + " -> " + e4MeleeApAfter + ")");
        require(e4ArrowAfterMelee, "the melee did not consume the cycle's reaction: the next real arrow still "
                + "relocated the unit" + diagnostics());
        require(e4ApAfterArrow == e4MeleeApStart - BattleRules.ENDERMAN_TELEPORT_COST,
                "and that reaction cost exactly its own price (" + e4MeleeApStart + " -> " + e4ApAfterArrow + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E4 melee: damage={} moved={} ap={}->{} hp={}->{} "
                        + "arrowAfterMelee={} apAfterArrow={}", e4MeleeHit, e4MeleeMoved, e4MeleeApStart,
                e4MeleeApAfter, e4MeleeHpStart, enderman.getHealth(), e4ArrowAfterMelee, e4ApAfterArrow);
        nextScenario();
        return false;
    }

    /**
     * A real projectile hit on a unit that cannot pay the reaction.
     *
     * <p>The price gate comes before any search, so the evidence is: no landing attempt at all, no AP change, no
     * move, and the native projectile immunity still keeps its health.</p>
     */
    private static void beginUnaffordableReaction() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        // The max_ap attribute is a percentage of the ruleset's own cap, so 10% really lands below one price.
        enderman.getAttribute(TacticalAttributes.MAX_AP).setBaseValue(10);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(4.0D);
        e4PoorApStart = battle.actorAp(enderman);
        e4PoorHpStart = enderman.getHealth();
        e4PoorPosBefore = enderman.position();
        teleportEvents = 0;
        wait = 0;
        stage = 13;
    }

    private static boolean stepUnaffordableReaction() {
        if (wait == 10) fireArrow(owner, enderman);
        if (wait == 45) {
            e4PoorNativeCalls = teleportEvents;
            e4PoorMoved = enderman.position().distanceTo(e4PoorPosBefore) > 0.5D;
            e4PoorApAfter = battle.actorAp(enderman);
            e4PoorHpAfter = enderman.getHealth();
        }
        if (waitTicks(70)) return true;
        require(e4PoorApStart < BattleRules.ENDERMAN_TELEPORT_COST,
                "the fixture really made the reaction unaffordable: ap=" + e4PoorApStart);
        require(e4PoorNativeCalls == 0, "an unaffordable reaction opens no landing attempt at all, saw "
                + e4PoorNativeCalls + diagnostics());
        require(!e4PoorMoved, "an unaffordable reaction never moves the unit");
        require(e4PoorApAfter == e4PoorApStart, "and never charges it (" + e4PoorApStart + " -> " + e4PoorApAfter + ")");
        require(e4PoorHpAfter >= e4PoorHpStart, "the projectile branch never damages on its own: hp " + e4PoorHpStart
                + " -> " + e4PoorHpAfter);
        // Direct answer check: with no affordable dodge the branch must answer false, not a fabricated success.
        e4AnswerNegative = enderman.hurt(projectileProbe(), 5.0F);
        e4AnswerNegativeHeld = enderman.getHealth() >= e4PoorHpStart;
        require(!e4AnswerNegative, "an unaffordable dodge answers false, not a fabricated hurt success");
        require(e4AnswerNegativeHeld, "and the unit keeps its health anyway, because the branch never damages");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E4 unaffordable: ap={} nativeCalls={} moved={} hp={}->{} "
                        + "answer={}", e4PoorApStart, e4PoorNativeCalls, e4PoorMoved, e4PoorHpStart, e4PoorHpAfter,
                e4AnswerNegative);
        nextScenario();
        return false;
    }

    /** A vetoed native attempt spends nothing, and the same cycle may still react to the next real hit. */
    private static void beginVetoedReaction() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(4.0D);
        e4VetoApStart = battle.actorAp(enderman);
        e4VetoPosBefore = enderman.position();
        e4VetoPhase = 1;
        teleportEvents = 0;
        wait = 0;
        stage = 15;
    }

    private static boolean stepVetoedReaction() {
        if (e4VetoPhase == 1) {
            if (wait == 5) cancelTeleports = true;
            if (wait == 10) fireArrow(owner, enderman);
            if (wait == 45) {
                e4VetoNativeCalls = teleportEvents;
                e4VetoKept = enderman.position().distanceTo(e4VetoPosBefore) < 0.5D;
                e4VetoApSpent = e4VetoApStart - battle.actorAp(enderman);
                cancelTeleports = false;
                e4VetoPhase = 2;
                wait = 0;
                return true;
            }
            return waitTicks(70);
        }
        if (wait == 10) fireArrow(owner, enderman);
        if (wait == 45) {
            e4AfterVetoMoved = enderman.position().distanceTo(e4VetoPosBefore) > 0.5D;
            e4AfterVetoAp = battle.actorAp(enderman);
        }
        if (waitTicks(70)) return true;
        require(e4VetoNativeCalls >= 1, "the vetoed hit really reached the native entry, saw " + e4VetoNativeCalls);
        require(e4VetoKept, "a vetoed relocation leaves the body exactly where it was");
        require(e4VetoApSpent == 0, "a vetoed relocation charges nothing, saw " + e4VetoApSpent + " AP");
        require(e4AfterVetoMoved, "the failed attempt did not consume the cycle's reaction: the next real hit "
                + "still relocated the unit" + diagnostics());
        require(e4AfterVetoAp == e4VetoApStart - BattleRules.ENDERMAN_TELEPORT_COST,
                "and it cost exactly its own price (" + e4VetoApStart + " -> " + e4AfterVetoAp + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E4 vetoed: nativeCalls={} kept={} apSpent={} "
                        + "thenMoved={} thenAp={}", e4VetoNativeCalls, e4VetoKept, e4VetoApSpent, e4AfterVetoMoved,
                e4AfterVetoAp);
        nextScenario();
        return false;
    }

    /**
     * A real hit with no legal landing anywhere: a one column shaft with the unit at its bottom.
     *
     * <p>Every cell it may reach is solid stone, and the only air is the cell it already occupies - which the gate
     * refuses as a paid no-op. So the search finds nothing, costs nothing, and the cycle's reaction is still
     * available once the shaft is opened.</p>
     */
    private static void beginSealedReaction() {
        // A real one-deep pool across the whole reach: every cell this unit could land on would stand in water,
        // so the gate has no legal landing at all - and the pool is open, so a real arrow still reaches it.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(4.0D);
        floodPool(enderman.blockPosition(), 9);
        e4VetoPhase = 0;
        e4ShaftApStart = battle.actorAp(enderman);
        e4ShaftPosBefore = enderman.position();
        teleportEvents = 0;
        wait = 0;
        stage = 17;
    }

    /** Real water one block deep over a whole square, with the air above it left clear. */
    private static void floodPool(BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                set(centre.offset(dx, 0, dz), Blocks.WATER.defaultBlockState());
                for (int dy = 1; dy <= 4; dy++) set(centre.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static boolean stepSealedReaction() {
        if (e4VetoPhase <= 2) {
            if (wait == 10) fireArrow(owner, enderman);
            if (wait == 45) {
                e4ShaftNativeCalls = teleportEvents;
                e4ShaftKept = enderman.position().distanceTo(e4ShaftPosBefore) < 0.5D;
                e4ShaftAp = battle.actorAp(enderman);
                // Raise a real one block shelf out of the pool: its top is a dry, supported cell that no water
                // can flow back onto, and the same cycle still owns its reaction - which is exactly what a
                // refused attempt must not have spent.
                GridPos cell = battle.currentActorCell(enderman);
                BlockPos feet = new BlockPos(cell.x(), cell.y(), cell.z());
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        set(feet.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                        set(feet.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
                        set(feet.offset(dx, 2, dz), Blocks.AIR.defaultBlockState());
                        set(feet.offset(dx, 3, dz), Blocks.AIR.defaultBlockState());
                    }
                }
                e4VetoPhase = 3;
                wait = 0;
                return true;
            }
            return waitTicks(70);
        }
        if (wait == 10) fireArrow(owner, enderman);
        if (wait == 60) {
            e4OpenedMoved = enderman.position().distanceTo(e4ShaftPosBefore) > 0.5D;
            e4OpenedAp = battle.actorAp(enderman);
        }
        if (waitTicks(90)) return true;
        require(e4ShaftNativeCalls == 0, "no legal landing means no native attempt at all, saw "
                + e4ShaftNativeCalls + diagnostics());
        require(e4ShaftKept, "the unit stays exactly where it was: pos=" + enderman.position() + " from="
                + e4ShaftPosBefore);
        require(e4ShaftAp == e4ShaftApStart, "and nothing was charged (" + e4ShaftApStart + " -> " + e4ShaftAp + ")");
        require(e4OpenedMoved, "with one dry cell opened the same cycle still relocated the unit" + diagnostics());
        require(e4OpenedAp == e4ShaftApStart - BattleRules.ENDERMAN_TELEPORT_COST,
                "and charged exactly its own price (" + e4ShaftApStart + " -> " + e4OpenedAp + ")");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E4 sealed: nativeCalls={} kept={} ap={} thenMoved={} "
                        + "thenAp={}", e4ShaftNativeCalls, e4ShaftKept, e4ShaftAp, e4OpenedMoved, e4OpenedAp);
        nextScenario();
        return false;
    }

    // ------------------------------------------------------------------ E4: its own step is not interruptible

    /**
     * A real hit while that unit is itself walking its own turn.
     *
     * <p>The relocation must not steal the flow that already owns this body: no native attempt, no charge, no
     * displacement beyond the step it was already taking - the hit is absorbed by the branch answer and the walk
     * finishes on its own path.</p>
     */
    private static void beginSelfStepReaction() {
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        // Exactly the short range: the unit really walks its own turn instead of teleporting, which gives the
        // hit a real stepping body to arrive in the middle of.
        startBattle(6.0D);
        e4StepHpStart = enderman.getHealth();
        e4StepApStart = battle.actorAp(enderman);
        e4StepCellBefore = battle.currentActorCell(enderman);
        e4StepStart = enderman.position();
        e4StepWalking = false;
        e4StepArrows = 0;
        e4StepHandovers = 0;
        teleportEvents = 0;
        wait = 0;
        stage = 25;
    }

    private static boolean stepSelfStepReaction() {
        if (!e4StepWalking) {
            if (battle.isPlayerTurnNow()) {
                // The scheduler is active-time based: a fast member may take the slot again and again, so the
                // waiting player has to keep passing it on until the enderman's own gauge fills.
                require(++e4StepHandovers < 400, "the unit never took its own walking turn" + diagnostics());
                battle.endPlayerTurn();
                return true;
            }
            if (enderman.position().distanceTo(e4StepStart) > 0.2D) e4StepWalking = true;
            if (e4StepWalking && e4StepArrows == 0) {
                fireArrow(owner, enderman);
                e4StepArrows = 1;
            }
            require(++wait < 600, "the unit never started walking" + diagnostics());
            return true;
        }
        if (!battle.isPlayerTurnNow()) return true;
        e4StepNativeCalls = teleportEvents;
        e4StepHpAfter = enderman.getHealth();
        e4StepApSpent = e4StepApStart - battle.actorAp(enderman);
        GridPos cell = battle.currentActorCell(enderman);
        require(e4StepArrows == 1, "a real arrow really hit the unit while it was stepping" + diagnostics());
        require(e4StepNativeCalls == 0, "a body owned by its own step opens no relocation at all, saw "
                + e4StepNativeCalls + diagnostics());
        require(e4StepHpAfter >= e4StepHpStart, "the native projectile immunity is kept: hp " + e4StepHpStart
                + " -> " + e4StepHpAfter);
        require(cell != null && e4StepCellBefore.manhattanDistance(cell) <= 8,
                "the step finished on its own path, cell=" + cell);
        require(e4StepApSpent >= 0 && e4StepApSpent != BattleRules.ENDERMAN_TELEPORT_COST,
                "the hit never bought a relocation charge, ap spent=" + e4StepApSpent);
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E4 busy: arrow={} nativeCalls={} hp={}->{} apSpent={} "
                        + "cell={}", e4StepArrows, e4StepNativeCalls, e4StepHpStart, e4StepHpAfter, e4StepApSpent,
                cell);
        nextScenario();
        return false;
    }

    // ------------------------------------------------------------------ E5: nobody else's attack costs anything

    private static void beginStrangerAndSecond() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        secondEnderman = spawnEnderman(0.5D, 4.5D);
        require(level.addFreshEntity(secondEnderman), "the second enderman really joined the world");
        startBattle(4.0D);
        require(battle.isParticipant(secondEnderman), "the second enderman is a real participant as well");
        bystander = (Mob) EntityType.RAVAGER.create(level);
        require(bystander != null, "a ravager exists in this version");
        bystander.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 4.5D);
        // No AI, so it never acquires a member target and can never be recruited mid-scenario: the body under
        // test has to stay an outsider for the whole case, not become a roster member a few ticks in.
        bystander.setNoAi(true);
        bystander.setPersistenceRequired();
        require(level.addFreshEntity(bystander), "the bystander joined the world");
        require(!battle.isParticipant(bystander), "the bystander is not part of this encounter");
        teleportEvents = 0;
        e5NativeCalls = 0;
        e5ApBefore = battle.actorAp(enderman);
        e5SecondApBefore = battle.actorAp(secondEnderman);
        e5HpStart = enderman.getHealth();
        e5PosBefore = enderman.position();
        e5Phase = 0;
        wait = 0;
        stage = 27;
    }

    /** One bystander's arrow, then one real trident from this encounter's own opponent. */
    private static boolean stepStrangerAndSecond() {
        if (e5Phase == 0) {
            if (wait == 10) fireArrow(bystander, enderman);
            if (wait == 40) sampleStranger();
            if (waitTicks(60)) return true;
            require(e5NoMove, "a bystander's arrow never moves this unit" + diagnostics());
            require(e5ApAfter == e5ApBefore, "a bystander's arrow never costs this unit AP (" + e5ApBefore + " -> "
                    + e5ApAfter + ")");
            require(e5SecondApAfter == e5SecondApBefore, "another enderman keeps its own quota untouched ("
                    + e5SecondApBefore + " -> " + e5SecondApAfter + ")");
            require(e5HpAfter <= e5HpStart, "the native projectile immunity is a damage answer, not a relocation");
            require(teleportEvents - e5NativeCalls == 0, "a bystander's attack opens no landing search at all, saw "
                    + (teleportEvents - e5NativeCalls));
            Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E5 bystander: nativeCalls={} ap={}->{} secondAp={}->{} "
                            + "noMove={} hp={}->{}", teleportEvents - e5NativeCalls, e5ApBefore, e5ApAfter,
                    e5SecondApBefore, e5SecondApAfter, e5NoMove, e5HpStart, e5HpAfter);
            // The isolation has to survive into the unit's own next turn: a bystander's arrow must not have left
            // an escape request that this turn would pay for.
            e5Phase = 1;
            e5OwnEventsBefore = teleportEvents;
            e5OwnApBefore = battle.actorAp(enderman);
            e5OwnCellBefore = battle.currentActorCell(enderman);
            e5OwnTurnEvents = 0;
            return true;
        }
        if (e5Phase == 1) {
            if (driveEnemyTurn()) return true;
            e5OwnTurnEvents = teleportEvents - e5OwnEventsBefore;
            e5OwnTurnAp = e5OwnApBefore - battle.actorAp(enderman);
            GridPos ownCell = battle.currentActorCell(enderman);
            GridPos opponentCell = battle.currentActorCell(owner);
            // The unit may still walk its ordinary approach; what it must not do is relocate or move away to
            // escape something a bystander's arrow never gave it.
            e5OwnTurnCell = ownCell != null && opponentCell != null
                    && ownCell.manhattanDistance(opponentCell) <= e5OwnCellBefore.manhattanDistance(opponentCell);
            require(e5OwnTurnEvents == 0, "the bystander's arrow left no escape request: its own turn opened no "
                    + "relocation at all, saw " + e5OwnTurnEvents + diagnostics());
            require(e5OwnTurnCell, "and that own turn never moved away from the opponent: cell=" + ownCell
                    + " opponent=" + opponentCell);
            Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E5 own turn: nativeCalls={} apSpent={} cell={}",
                    e5OwnTurnEvents, e5OwnTurnAp, ownCell);
            e5Phase = 2;
            wait = 0;
            e5PosBefore = enderman.position();
            e5ApBefore = battle.actorAp(enderman);
            return true;
        }
        if (wait == 10) fireTrident(owner, enderman);
        if (wait == 40) {
            e5TridentMoved = enderman.position().distanceTo(e5PosBefore) > 0.5D;
            e5TridentApSpent = e5ApBefore - battle.actorAp(enderman);
        }
        if (waitTicks(60)) return true;
        require(e5TridentMoved, "a real trident from this encounter's opponent buys the reaction" + diagnostics());
        require(e5TridentApSpent == BattleRules.ENDERMAN_TELEPORT_COST,
                "that reaction cost exactly its own price, saw " + e5TridentApSpent);
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E5 trident: moved={} apSpent={} pos={}", e5TridentMoved,
                e5TridentApSpent, enderman.position());
        nextScenario();
        return false;
    }

    private static void sampleStranger() {
        e5HpAfter = enderman.getHealth();
        e5ApAfter = battle.actorAp(enderman);
        e5SecondApAfter = battle.actorAp(secondEnderman);
        e5NoMove = enderman.position().distanceTo(e5PosBefore) < 0.5D;
        e5NativeCalls = teleportEvents;
    }

    // ------------------------------------------------------------------ E6: the water clock (real rain)

    /**
     * Real water in the cell the encounter itself priced, and a dry neighbour one step away.
     *
     * <p>Every later scene builds on this one: the quiet window, the own-turn settlement and the escape walk all
     * happen around the same unit, so a scenario can never borrow another one's numbers.</p>
     */
    private static void beginWaterScene() {
        // Every scene starts from an empty field: the previous encounter is closed and its bodies gone.
        nextScenario();
        enderman = spawnEnderman(0.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the enderman really joined the world");
        startBattle(6.0D);
        admissionPosition = enderman.position();
        poolWithShelf(enderman.blockPosition());
        damageLog.clear();
        wait = 0;
        e6HpQuietStart = enderman.getHealth();
        stage = 29;
    }

    /**
     * A one block water hole with a real stone shelf all around it.
     *
     * <p>The shelf is what makes this scene honest. Real water spreads over open ground within a few ticks, so a
     * plain puddle would silently wet every "dry" cell the unit was supposed to walk to; a solid ring keeps the
     * hole a hole, and the shelf top is a cell the 2.9 high body can really step onto.</p>
     */
    private static void poolWithShelf(BlockPos feet) {
        set(feet.below(), Blocks.STONE.defaultBlockState());
        set(feet, Blocks.WATER.defaultBlockState());
        set(feet.above(), Blocks.AIR.defaultBlockState());
        set(feet.above(2), Blocks.AIR.defaultBlockState());
        set(feet.above(3), Blocks.AIR.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                set(feet.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                set(feet.offset(dx, 1, dz), Blocks.AIR.defaultBlockState());
                set(feet.offset(dx, 2, dz), Blocks.AIR.defaultBlockState());
                set(feet.offset(dx, 3, dz), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** A flooded chamber: real water over the whole floor area and a stone ceiling above it. */
    private static void floodChamber(BlockPos centre, int radius, int depth) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                set(centre.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 0; dy <= depth - 1; dy++) {
                    set(centre.offset(dx, dy, dz), Blocks.WATER.defaultBlockState());
                }
                set(centre.offset(dx, depth, dz), Blocks.STONE.defaultBlockState());
            }
        }
    }

    private static void checkQuietA() {
        e6RainPlayerTurnHits = drownHits();
        require(drownHits() == 0, "no wall-clock water damage while another unit's turn runs, saw " + drownHits()
                + " " + damageLog + diagnostics());
        require(enderman.getHealth() >= e6HpQuietStart, "the unit keeps its health across the quiet window: "
                + e6HpQuietStart + " -> " + enderman.getHealth());
        require(enderman.isInWater(), "the enderman really stands in real water: " + enderman.position());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E6 quiet in water: hp={}->{} drownHits={} inWater={}",
                e6HpQuietStart, enderman.getHealth(), drownHits(), enderman.isInWater());
        damageLog.clear();
    }

    private static void checkOwnTurnA() {
        e6RainOwnTurnHits = drownHits();
        e6DrownAmount = drownAmount();
        e6StillAlive = enderman.isAlive();
        e6Sheltered = !EndermanLanding.exposed(level, enderman, enderman.position());
        GridPos cell = battle.currentActorCell(enderman);
        require(e6RainOwnTurnHits == 1, "its own turn settles exactly one native water hit, saw "
                + e6RainOwnTurnHits + " " + damageLog + diagnostics());
        require(Math.abs(e6DrownAmount - 1.0F) < 1.0E-4F, "the settled hit is the native 1.0 drown hit, saw "
                + e6DrownAmount);
        require(e6Sheltered, "with a dry, sheltered cell one step away the unit really walked out of the water: pos="
                + enderman.position() + " cell=" + cell + " ap=" + battle.actorAp(enderman) + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E6 settle: drownHits={} amount={} dry={} cell={} ap={}",
                e6RainOwnTurnHits, e6DrownAmount, e6Sheltered, cell, battle.actorAp(enderman));
        damageLog.clear();
    }

    private static void checkQuietB() {
        e6SecondPlayerTurnHits = drownHits();
        require(e6SecondPlayerTurnHits == 0, "the quiet window holds no wall-clock water damage in the chamber, saw "
                + e6SecondPlayerTurnHits + " " + damageLog + diagnostics());
        damageLog.clear();
    }

    private static void checkOwnTurnB() {
        e6SecondOwnTurnHits = drownHits();
        e6StayedPut = enderman.isInWater();
        require(e6SecondOwnTurnHits == 1, "the second own turn settles exactly one native water hit, saw "
                + e6SecondOwnTurnHits + " " + damageLog + diagnostics());
        require(e6StayedPut, "with no dry, sheltered landing anywhere in range the unit stays in the water instead "
                + "of buying a move that fixes nothing: pos=" + enderman.position() + diagnostics());
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E6 limited retreat: drownHits={} stillWet={} ap={} pos={}",
                e6SecondOwnTurnHits, e6StayedPut, battle.actorAp(enderman), enderman.position());
        damageLog.clear();
        wait = 0;
    }

    private static void fireWaterBottle() {
        ThrownPotion potion = new ThrownPotion(level, owner);
        potion.setItem(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.WATER));
        potion.setPos(owner.getX(), owner.getEyeY() - 0.2D, owner.getZ());
        Vec3 direction = enderman.position().add(0, 1.0D, 0).subtract(potion.position());
        potion.shoot(direction.x, direction.y, direction.z, 1.2F, 0.0F);
        require(level.addFreshEntity(potion), "the water bottle really joined the world");
    }

    private static void checkWaterBottle() {
        // A real water bottle splash: vanilla's own clean-water path, not a fake hurt call.
        require(!damageLog.isEmpty(), "the water bottle really hit the unit and went through vanilla's own "
                + "clean-water path: " + enderman.position() + " " + diagnostics());
        e6BottleDamage = 1.0F;
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E6 bottle: log={} pos={}", damageLog, enderman.position());
        // The flooded chamber: the same clock, but nowhere dry to go anywhere inside its reach.
        floodChamber(enderman.blockPosition(), 10, 3);
        damageLog.clear();
        wait = 0;
    }

    private static void beginRelease() {
        // E8: the carried block and the hand belong to the entity, not to the admission snapshot.
        enderman.setCarriedBlock(Blocks.DIRT.defaultBlockState());
        enderman.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
        carriedBefore = enderman.getCarriedBlock();
        handBefore = enderman.getMainHandItem().copy().toString();
        positionBeforeFinish = enderman.position();
        battle.finish(BattleText.literal("fixture: release"));
        // Read the release atomically in the same tick it happens: a released enderman in water is a plain
        // vanilla enderman again, and vanilla is then free to teleport it on its own water damage.
        e8HandlerCleared = ((TacticalEndermanAccess) enderman).tactical$landingHandler() == null;
        e8CarriedKept = enderman.getCarriedBlock() == carriedBefore;
        e8HandKept = enderman.getMainHandItem().copy().toString().equals(handBefore);
        e8PositionKept = enderman.position().distanceTo(positionBeforeFinish) < 0.01D
                && positionBeforeFinish.distanceTo(admissionPosition) > 2.0D;
        stage = 35;
    }

    private static void checkRelease() {
        require(e8HandlerCleared, "the encounter's routing handler is gone after the encounter ended");
        require(e8CarriedKept, "the carried block is the entity's own value, never an admission snapshot");
        require(e8HandKept, "the equipment is untouched by the release");
        require(e8PositionKept, "the final position is kept at the release, not rolled back to the admission "
                + "snapshot: at=" + positionBeforeFinish + " admitted=" + admissionPosition);
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E8 release: handlerCleared={} carriedKept={} handKept={} "
                        + "positionKept={} noAi={}", e8HandlerCleared, e8CarriedKept, e8HandKept, e8PositionKept,
                enderman.isNoAi());
    }

    private static void checkWildEnderman() {
        // An enderman nobody admitted keeps every vanilla behaviour: its own unowned-damage teleport still runs.
        enderman.discard();
        enderman = spawnEnderman(3.5D, 0.5D);
        require(level.addFreshEntity(enderman), "the wild enderman really joined the world");
        e8WildHasNoHandler = ((TacticalEndermanAccess) enderman).tactical$landingHandler() == null;
        DamageSource generic = level.damageSources().generic();
        for (int attempt = 0; attempt < 12 && !e8WildTeleported; attempt++) {
            Vec3 start = enderman.position();
            enderman.hurt(generic, 1.0F);
            e8WildTeleported = enderman.position().distanceTo(start) > 1.0D;
            enderman.setHealth(enderman.getMaxHealth());
        }
        e8ShulkerIgnored = shulker != null && !(shulker instanceof TacticalEndermanAccess);
        require(e8WildHasNoHandler, "an enderman outside any encounter answers no handler");
        require(e8WildTeleported, "a wild enderman still teleports on its own, at=" + enderman.position());
        require(e8ShulkerIgnored, "the enderman rules never install on another vanilla type");
        Tactical.LOGGER.info("[Tactical] ENDERMAN fixture E8 wild: noHandler={} teleported={} shulkerIgnored={} at={}",
                e8WildHasNoHandler, e8WildTeleported, e8ShulkerIgnored, enderman.position());
    }

    private static int drownHits() {
        int count = 0;
        for (String row : damageLog) if (row.startsWith("drown:")) count++;
        return count;
    }

    private static float drownAmount() {
        for (String row : damageLog) {
            if (row.startsWith("drown:")) return Float.parseFloat(row.substring("drown:".length()));
        }
        return -1.0F;
    }

    // ------------------------------------------------------------------ report

    private static void complete(MinecraftServer server) {
        Tactical.LOGGER.info("[Tactical] ENDERMAN SELFTEST PASS "
                        + "F[nativeReturned={} moved={} distance={} landedOnFloor={} feetInteger={} vetoRefused={} "
                        + "keptPosition={}] "
                        + "E1[ap={}->{} from={} to={} moved={} cellSynced={} melee={} oldCellFreed={}] "
                        + "E2[legalRepoint={} cell={} ap={}->{} noMelee={} occupied={} danger={} zone={} unloaded={} "
                        + "rangeCalls={} rangeKept={} rangeAp={} slabCalls={} slabKept={} slabAp={} "
                        + "noopCalls={} noopKept={} noopAp={} lowCeiling={} thirdBlock={} clearBody={}] "
                        + "E3[arrows={} nativeCalls={} moves={} ap={}->{} hp={}->{} immune={} sameTurn={} "
                        + "ownerApKept={}] "
                        + "E4[meleeDamage={} meleeMoved={} meleeAp={} arrowAfterMelee={} apAfterArrow={} "
                        + "poorAp={} poorCalls={} poorMoved={} poorHp={}->{} answerFalse={} answerFalseHeld={} "
                        + "vetoCalls={} vetoKept={} vetoApSpent={} "
                        + "thenMoved={} thenAp={} sealedCalls={} sealedKept={} sealedAp={} openedMoved={} openedAp={}] "
                        + "E5[bystanderCalls={} ap={}->{} secondAp={}->{} noMove={} ownTurnAp={} ownTurnCell={} "
                        + "tridentMoved={} tridentAp={}] "
                        + "E6[drownInPlayerTurn={} drownInOwnTurn={} amount={} sheltered={} stillAlive={} "
                        + "secondPlayerTurn={} secondOwnTurn={} bottle={}] "
                        + "E8[handlerCleared={} carriedKept={} handKept={} positionKept={} wildNoHandler={} "
                        + "wildTeleported={} shulkerIgnored={}]",
                nativeReturned, reallyMoved, movedDistance, landedOnFloor, feetInteger, vetoRefused, vetoKeptPosition,
                e1ApStart, e1ApEnd, e1From, e1To, e1Moved, e1CellSynced, e1MeleeFollowUp, e1OldCellFreed,
                e2LegalRepointUsed, e2LegalCellSynced, e2ApStart, e2ApEnd, e2NoMelee, e2RefusedOccupied,
                e2RefusedDanger, e2RefusedZone, e2RefusedUnloaded, e2RangeNativeCalls, e2RangeKept, e2RangeApEnd,
                e2SlabNativeCalls, e2SlabKept, e2SlabApEnd, e2NoopNativeCalls, e2NoopKept, e2NoopApEnd,
                e2LowCeilingRefused, e2ThirdBlockRefused, e2ClearBodyAllowed,
                e3ArrowsFired, e3NativeCalls, e3Moves, e3ApStart, e3ApEnd, e3HpStart, e3HpAfterArrows,
                e3ImmuneToArrows, e3SameTurnEpoch, e3OwnerApKept,
                e4MeleeHit, e4MeleeMoved, e4MeleeApAfter, e4ArrowAfterMelee, e4ApAfterArrow,
                e4PoorApStart, e4PoorNativeCalls, e4PoorMoved, e4PoorHpStart, e4PoorHpAfter,
                e4AnswerNegative, e4AnswerNegativeHeld, e4VetoNativeCalls, e4VetoKept, e4VetoApSpent, e4AfterVetoMoved, e4AfterVetoAp,
                e4ShaftNativeCalls, e4ShaftKept, e4ShaftAp, e4OpenedMoved, e4OpenedAp,
                e5NativeCalls, e5ApBefore, e5ApAfter, e5SecondApBefore, e5SecondApAfter, e5NoMove, e5OwnTurnAp,
                e5OwnTurnCell, e5TridentMoved, e5TridentApSpent,
                e6RainPlayerTurnHits, e6RainOwnTurnHits, e6DrownAmount, e6Sheltered, e6StillAlive,
                e6SecondPlayerTurnHits, e6SecondOwnTurnHits, e6BottleDamage,
                e8HandlerCleared, e8CarriedKept, e8HandKept, e8PositionKept, e8WildHasNoHandler, e8WildTeleported,
                e8ShulkerIgnored);
        finish(server);
    }

    private static void fail(MinecraftServer server) {
        Tactical.LOGGER.error("[Tactical] ENDERMAN SELFTEST result: stage={} ticks={} teleportEvents={} "
                        + "e1Ap={}->{} e3Ap={}->{} e5Ap={}->{} rainOwnerTurn={} rainOwnTurn={} log={}",
                stage, ticks, teleportEvents, e1ApStart, e1ApEnd, e3ApStart, e3ApEnd, e5ApBefore, e5ApAfter,
                e6RainPlayerTurnHits, e6RainOwnTurnHits, damageLog);
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        if (battle != null) {
            try {
                battle.finish(BattleText.literal("fixture: end"));
            } catch (RuntimeException ignored) {
                // A failed assertion must not stop the teardown.
            }
            BattleManager.reset();
            battle = null;
        }
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(40))) {
            if (!(entity instanceof ServerPlayer)) entity.discard();
        }
        for (ServerPlayer player : List.of(owner, second)) {
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

    private static String diagnostics() {
        return " [stage=" + stage + " ticks=" + ticks + " wait=" + wait + " pos="
                + (enderman == null ? "none" : enderman.position()) + " events=" + teleportEvents + "]";
    }
}
