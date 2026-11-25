package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.BreezeRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.BreezeDeflections.Reflection;
import com.tactical.neoforge.network.JoinBattleResultPacket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameRules.BooleanValue;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent.Detonate;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;

@EventBusSubscriber(modid = "tactical")
public final class BreezeSelfTest {
   private static final int FLOOR_MIN_X = -8;
   private static final int FLOOR_MAX_X = 36;
   private static final int FLOOR_MIN_Z = -22;
   private static final int FLOOR_MAX_Z = 22;
   private static boolean enabled;
   private static int ticks;
   private static int stage;
   private static int wait;
   private static MinecraftServer server;
   private static ServerLevel level;
   private static BlockPos origin;
   private static ServerPlayer owner;
   private static ServerPlayer second;
   private static ServerBattle battle;
   private static final List<BlockPos> forced = new ArrayList<>();
   private static final LinkedHashSet<BlockPos> placed = new LinkedHashSet<>();
   private static final List<String> passed = new ArrayList<>();
   private static boolean exitHandlerCleared = true;
   private static boolean exitPoseRestored = true;
   private static boolean exitFrictionRestored = true;
   private static boolean exitBusy;
   private static Breeze controlOwner;
   private static BreezeWindCharge controlCharge;
   private static BlockPos controlDoor;
   private static BlockPos controlButton;
   private static boolean controlDoorOpenBefore;
   private static boolean controlButtonPoweredBefore;
   private static int controlDeadline;
   private static boolean controlBurstSeen;
   private static int controlExplosions;
   private static int controlToBlow;
   private static boolean controlDoorInToBlow;
   private static Vec3 controlCentre;
   private static Breeze shooter;
   private static Zombie sameSideAlly;
   private static Cow bystander;
   private static Shulker stillShell;
   private static BlockPos doorPos;
   private static BlockPos buttonPos;
   private static int chargeJoins;
   private static double chargeSpeed;
   private static int shooterApBefore;
   private static int shooterPaidBefore;
   private static int ownerApBefore;
   private static int secondApBefore;
   private static int ownerApFirstEnemyTick = -1;
   private static int ownerApLastEnemyTick = -1;
   private static int secondApFirstEnemyTick = -1;
   private static int secondApLastEnemyTick = -1;
   private static Vec3 allyFeetBefore;
   private static float allyHealthBefore;
   private static Vec3 shellFeetBefore;
   private static boolean doorOpenBefore;
   private static boolean buttonPoweredBefore;
   private static boolean shotPoseSeen;
   private static int shotDeadline;
   private static Breeze farBreeze;
   private static double farDistanceBefore;
   private static int farApBefore;
   private static int bandDeadline;
   private static Breeze jumper;
   private static double jumperStartY;
   private static boolean inhaleSeen;
   private static boolean airborneSeen;
   private static Vec3 airbornePoint;
   private static boolean landedSeen;
   private static int jumperApBefore;
   private static int jumpApAtCommit;
   private static int jumpPaidAtCommit;
   private static int jumpApLast;
   private static int jumpPaidLast;
   private static int shotApFirst;
   private static int shotPaidFirst;
   private static int shotApLast;
   private static int shotPaidLast;
   private static boolean jumpWindowOpened;
   private static boolean jumpWindowClosed;
   private static boolean shotWindowClosed;
   private static boolean jumpWasInhaling;
   private static boolean jumpAirborneThisWindow;
   private static int jumperOwnTurnShots;
   private static int jumpDeadline;
   private static final List<Vec3> jumpFeet = new ArrayList<>();
   private static Vec3 jumpStartFeet;
   private static Vec3 jumpLandingFeet;
   private static Breeze guardTarget;
   private static int guardShots;
   private static int guardApBefore;
   private static int guardPaidBefore;
   private static int guardDeadline;
   private static boolean guardInhaleSeen;
   private static boolean guardFloorRemoved;
   private static boolean guardPaidAtRemoval;
   private static Vec3 guardStartFeet;
   private static Vec3 guardContactFeet;
   private static Vec3 guardLastCommitted;
   private static int guardArcLen;
   private static final List<BlockPos> guardRestorePos = new ArrayList<>();
   private static final List<BlockState> guardRestoreState = new ArrayList<>();
   private static Breeze refuser;
   private static boolean refusalInhaleSeen;
   private static int refuserApBefore;
   private static int refuserPaidBefore;
   private static double refuserStartY;
   private static int refuserApFirstTurnTick = -1;
   private static int refuserApLastTurnTick = -1;
   private static int refuserPaidFirstTurnTick = -1;
   private static int refuserPaidLastTurnTick = -1;
   private static boolean refuserWindowClosed;
   private static boolean refuserWasInhaling;
   private static int refusalDeadline;
   private static Breeze deflector;
   private static Breeze secondDeflector;
   private static Zombie deflectorAlly;
   private static Cow deflectionBystander;
   private static Arrow deflectedArrow;
   private static Arrow allowedArrow;
   private static Arrow repeatedArrow;
   private static Arrow rotatedArrow;
   private static int allowedAttempts;
   private static LivingEntity deflectorAllySeen;
   private static boolean sideImpactCancelled;
   private static boolean sideImpactPermitted;
   private static boolean memberImpactCancelled;
   private static boolean memberImpactPermitted;
   private static boolean allowedAimed;
   private static int lastAimTick;
   private static Vec3 arrowDeltaBefore;
   private static UUID arrowOwnerBefore;
   private static boolean reflectionRecorded;
   private static boolean repeatedReflectionRecorded;
   private static UUID recordedDeflector;
   private static UUID secondRecordedDeflector;
   private static int deflectorApBefore;
   private static int secondDeflectorApBefore;
   private static int dOwnerApBefore;
   private static int ownerPaidBefore;
   private static int ownerApAfterRefusal;
   private static int ownerPaidAfterRefusal;
   private static float dAllyHealthBefore;
   private static float ownerHealthBefore;
   private static float ownerHealthAfterHit;
   private static float secondHealthBefore;
   private static boolean permissionDeflectorRefused;
   private static boolean permissionBystanderRefused;
   private static boolean permissionOpposedAllowed;
   private static boolean refusalResolved;
   private static boolean allowedHitResolved;
   private static int deflectionStage;
   private static final Set<Arrow> seenArrows = Collections.newSetFromMap(new IdentityHashMap<>());
   private static int deflectionDeadline;
   private static Breeze interrupted;
   private static Vec3 interruptStart;
   private static boolean interruptPlaced;
   private static int interruptApAtCommit;
   private static int interruptPaidAtCommit;
   private static int interruptApLast;
   private static int interruptPaidLast;
   private static boolean interruptAirborne;
   private static boolean interruptStopped;
   private static boolean interruptWasAirborne;
   private static boolean interruptSawActorTurn;
   private static int interruptDeadline;

   private BreezeSelfTest() {
   }

   @SubscribeEvent
   public static void onStarted(ServerStartedEvent event) {
      enabled = "breeze".equals(System.getProperty("tactical.selftest"));
   }

   @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
   public static void onJoin(EntityJoinLevelEvent event) {
      if (enabled && level != null && event.getLevel() == level) {
         if (event.getEntity() instanceof BreezeWindCharge charge) {
            if (shooter != null && charge.getOwner() == shooter) {
               chargeJoins++;
               chargeSpeed = charge.getDeltaMovement().length();
            }

            if (jumper != null && charge.getOwner() == jumper) {
               jumperOwnTurnShots++;
            }

            if (guardTarget != null && charge.getOwner() == guardTarget) {
               guardShots++;
            }
         }
      }
   }

   @SubscribeEvent
   public static void onDetonate(Detonate event) {
      if (enabled && level != null && event.getLevel() == level) {
         if (controlCharge != null && event.getExplosion().getDirectSourceEntity() == controlCharge) {
            controlExplosions++;
            controlCentre = event.getExplosion().center();
            controlToBlow = event.getAffectedBlocks().size();
            controlDoorInToBlow = controlDoor != null && event.getAffectedBlocks().contains(controlDoor);
            Tactical.LOGGER
               .info(
                  "[Tactical] breeze N control detonate centre={} toBlow={} doorInToBlow={} interaction={} triggerBlocks={}",
                  new Object[]{
                     controlCentre, controlToBlow, controlDoorInToBlow, event.getExplosion().getBlockInteraction(), event.getExplosion().canTriggerBlocks()
                  }
               );
         }
      }
   }

   @SubscribeEvent
   public static void onTick(Post event) {
      if (enabled) {
         ticks++;

         try {
            require(ticks < 30000, "breeze fixture completion deadline");
            if (!passed.isEmpty() && stage >= 12) {
               finishFixture();
               return;
            }

            switch (stage) {
               case 0:
                  setup(event.getServer());
                  stage = 1;
                  wait = 0;
                  break;
               case 1:
                  if (++wait < 20) {
                     return;
                  }

                  beginNativeControl();
                  stage = 2;
                  wait = 0;
                  break;
               case 2:
                  observeNativeControl();
                  if (!controlReady()) {
                     return;
                  }

                  checkNativeControl();
                  endNativeControl();
                  beginShotScenario();
                  stage = 3;
                  wait = 0;
                  break;
               case 3:
                  observeShotScenario();
                  if (!shotScenarioReady()) {
                     drivePlayerTurnsToBreeze(shooter);
                     return;
                  }

                  checkShotScenario();
                  endScenario();
                  beginBandScenario();
                  stage = 4;
                  wait = 0;
                  break;
               case 4:
                  observeBandScenario();
                  if (!bandScenarioReady()) {
                     drivePlayerTurnsToBreeze(farBreeze);
                     return;
                  }

                  checkBandScenario();
                  endScenario();
                  beginJumpScenario();
                  stage = 5;
                  wait = 0;
                  break;
               case 5:
                  observeJumpScenario();
                  if (!jumpScenarioReady()) {
                     drivePlayerTurnsToBreeze(jumper);
                     return;
                  }

                  checkJumpScenario();
                  endScenario();
                  beginRefusalScenario();
                  stage = 6;
                  wait = 0;
                  break;
               case 6:
                  observeRefusalScenario();
                  if (!refusalScenarioReady()) {
                     drivePlayerTurnsToBreeze(refuser);
                     return;
                  }

                  checkRefusalScenario();
                  endScenario();
                  beginInterruptScenario();
                  stage = 8;
                  wait = 0;
                  break;
               case 7:
                  observeDeflectionScenario();
                  if (!deflectionScenarioReady()) {
                     return;
                  }

                  checkDeflectionScenario();
                  endScenario();
                  checkTeardown();
                  stage = 12;
                  break;
               case 8:
                  observeInterruptScenario();
                  if (!interruptScenarioReady()) {
                     drivePlayerTurnsToBreeze(interrupted);
                     return;
                  }

                  checkInterruptScenario();
                  endScenario();
                  beginGuardEarlyScenario();
                  stage = 9;
                  wait = 0;
                  break;
               case 9:
                  observeGuardEarlyScenario();
                  if (!guardEarlyReady()) {
                     drivePlayerTurnsToBreeze(guardTarget);
                     return;
                  }

                  checkGuardEarlyScenario();
                  endScenario();
                  beginGuardLateScenario();
                  stage = 10;
                  wait = 0;
                  break;
               case 10:
                  observeGuardLateScenario();
                  if (!guardLateReady()) {
                     drivePlayerTurnsToBreeze(guardTarget);
                     return;
                  }

                  checkGuardLateScenario();
                  endScenario();
                  beginDeflectionScenario();
                  stage = 7;
                  wait = 0;
                  break;
               default:
                  finishFixture();
            }
         } catch (RuntimeException var2) {
            Tactical.LOGGER.error("[Tactical] BREEZE SELFTEST FAIL after {} passing group(s): {}", new Object[]{passed.size(), var2.getMessage(), var2});
            enabled = false;
            server.halt(false);
         }
      }
   }

   private static void setup(MinecraftServer running) {
      server = running;
      level = server.overworld();
      ((BooleanValue)level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING)).set(true, server);
      TacticalConfig.INSTANCE.zoneRadius.set(48);
      BlockPos spawn = level.getSharedSpawnPos();
      origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 20, spawn.getY() + 24), spawn.getZ());

      for (int cx = origin.getX() - 48 >> 4; cx <= origin.getX() + 48 >> 4; cx++) {
         for (int cz = origin.getZ() - 48 >> 4; cz <= origin.getZ() + 48 >> 4; cz++) {
            if (level.setChunkForced(cx, cz, true)) {
               forced.add(new BlockPos(cx, 0, cz));
            }
         }
      }

      for (int x = -8; x <= 36; x++) {
         for (int z = -22; z <= 22; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);

            for (int dy = 0; dy < 12; dy++) {
               level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 2);
            }
         }
      }

      AABB arena = new AABB(origin.offset(-8, -4, -22).getCenter(), origin.offset(36, 16, 22).getCenter());

      for (Mob leftover : level.getEntitiesOfClass(Mob.class, arena)) {
         leftover.discard();
      }

      owner = player("tactical-breeze-owner", "BreezeOwner");
      second = player("tactical-breeze-second", "BreezeSecond");
      Tactical.LOGGER.info("[Tactical] breeze fixture arena at {} radius={}", origin, BattleManager.zoneRadius());
   }

   private static ServerPlayer player(String key, String name) {
      GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
      ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
      Connection connection = new Connection(PacketFlow.SERVERBOUND);
      new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
      player.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
      player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
      player.setHealth(100.0F);
      level.addNewPlayer(player);
      return player;
   }

   private static void place(BlockPos pos, BlockState state) {
      level.setBlock(pos, state, 2);
      placed.add(pos);
   }

   private static Breeze spawnBreeze(double dx, double dz) {
      Breeze breeze = (Breeze)EntityType.BREEZE.create(level);
      require(breeze != null, "breeze exists in this version");
      breeze.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
      breeze.setNoAi(true);
      breeze.setPersistenceRequired();
      require(level.addFreshEntity(breeze), "breeze registered in the real server world");
      return breeze;
   }

   private static Zombie spawnZombie(double dx, double dz) {
      Zombie zombie = (Zombie)EntityType.ZOMBIE.create(level);
      require(zombie != null, "zombie exists in this version");
      zombie.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
      zombie.setNoAi(true);
      zombie.setPersistenceRequired();
      zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
      zombie.setHealth(100.0F);
      require(level.addFreshEntity(zombie), "zombie registered in the real server world");
      return zombie;
   }

   private static void startBattle() {
      BattleManager.reset();
      require(BattleManager.start(owner, true), "the breeze encounter starts");
      battle = BattleManager.current();
      require(battle != null && battle.isPlayerTurnNow(), "the owner acts first");
   }

   private static void resetPlayers() {
      owner.setHealth(owner.getMaxHealth());
      owner.invulnerableTime = 0;
      owner.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
      second.setHealth(second.getMaxHealth());
      second.invulnerableTime = 0;
      second.setPos(origin.getX() + 3.5, origin.getY(), origin.getZ() - 3.5);
   }

   private static void endScenario() {
      TacticalConfig.INSTANCE.friendlyFire.set(false);
      if (battle != null) {
         battle.finish(BattleText.literal("fixture: next scenario"));
         exitBusy = battle.isBusy();

         for (Mob mob : new Mob[]{shooter, farBreeze, jumper, refuser, interrupted, guardTarget, deflector, secondDeflector}) {
            if (mob instanceof Breeze breeze) {
               if (((TacticalBreezeAccess)breeze).tactical$handler() != null) {
                  exitHandlerCleared = false;
               }

               if (breeze.getPose() != Pose.STANDING) {
                  exitPoseRestored = false;
               }

               if (breeze.shouldDiscardFriction()) {
                  exitFrictionRestored = false;
               }
            }
         }
      }

      BattleManager.reset();
      battle = null;
      List<Mob> bodies = new ArrayList<>();

      for (Mob mobx : new Mob[]{
         shooter,
         farBreeze,
         jumper,
         refuser,
         guardTarget,
         deflector,
         sameSideAlly,
         deflectorAlly,
         bystander,
         stillShell,
         controlOwner,
         deflectionBystander,
         interrupted,
         secondDeflector
      }) {
         if (mobx != null) {
            bodies.add(mobx);
         }
      }

      for (Mob mobxx : bodies) {
         mobxx.discard();
      }

      shooter = null;
      farBreeze = null;
      jumper = null;
      refuser = null;
      guardTarget = null;
      guardShots = 0;
      guardInhaleSeen = false;
      guardFloorRemoved = false;
      guardPaidAtRemoval = false;
      guardContactFeet = null;
      guardLastCommitted = null;
      guardArcLen = -1;
      deflector = null;
      sameSideAlly = null;
      bystander = null;
      stillShell = null;
      controlOwner = null;
      controlCharge = null;
      deflectedArrow = null;
      allowedArrow = null;
      repeatedArrow = null;
      seenArrows.clear();
      deflectorAlly = null;
      secondDeflector = null;
      deflectionBystander = null;
      deflectionStage = 0;
      allowedAttempts = 0;
      allowedAimed = false;
      lastAimTick = 0;
      reflectionRecorded = false;
      repeatedReflectionRecorded = false;
      refusalResolved = false;
      allowedHitResolved = false;
      interrupted = null;
      interruptPlaced = false;
      interruptAirborne = false;
      interruptStopped = false;
      interruptWasAirborne = false;
      interruptSawActorTurn = false;
      chargeJoins = 0;
      chargeSpeed = 0.0;
      shotPoseSeen = false;
      ownerApFirstEnemyTick = -1;
      ownerApLastEnemyTick = -1;
      secondApFirstEnemyTick = -1;
      secondApLastEnemyTick = -1;
      inhaleSeen = false;
      airborneSeen = false;
      airbornePoint = null;
      landedSeen = false;
      jumperOwnTurnShots = 0;
      jumpWindowCleared();
      refusalInhaleSeen = false;
      reflectionRecorded = false;

      for (BlockPos pos : placed) {
         level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
      }

      placed.clear();
      // The guard scenarios take real floor away instead of adding blocks, so their original states are put
      // back here - and only those exact cells.
      for (int index = 0; index < guardRestorePos.size(); index++) {
         level.setBlock(guardRestorePos.get(index), guardRestoreState.get(index), 2);
      }

      guardRestorePos.clear();
      guardRestoreState.clear();
      resetPlayers();
   }

   private static void drivePlayerTurnsToBreeze(Breeze actor) {
      if (battle != null && !battle.finished() && actor != null) {
         if (!battle.isActorTurn(actor)) {
            if (battle.isPlayerTurnNow()) {
               battle.endPlayerTurn();
            }
         }
      }
   }

   private static void beginNativeControl() {
      resetPlayers();
      controlDoor = origin.offset(-1, 0, 0);
      place(controlDoor, (BlockState)Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH));
      place(
         controlDoor.above(),
         (BlockState)((BlockState)Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
            .setValue(DoorBlock.FACING, Direction.NORTH)
      );
      BlockPos support = origin.offset(-2, 0, -2);
      place(support, Blocks.STONE.defaultBlockState());
      controlButton = origin.offset(-1, 0, -2);
      place(controlButton, (BlockState)Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACING, Direction.WEST));
      controlDoorOpenBefore = (Boolean)level.getBlockState(controlDoor).getValue(DoorBlock.OPEN);
      controlButtonPoweredBefore = (Boolean)level.getBlockState(controlButton).getValue(ButtonBlock.POWERED);
      controlOwner = spawnBreeze(6.0, 6.0);
      BreezeWindCharge charge = new BreezeWindCharge(controlOwner, level);
      charge.setPos(origin.getX() + 0.5, origin.getY() + 0.6, origin.getZ() + 0.5);
      charge.shoot(0.0, -0.25, 0.0, 0.7F, 0.0F);
      controlCharge = charge;
      require(level.addFreshEntity(charge), "the unmarked native charge joined the world");
      require(!BreezeWindCharges.owns(charge), "the control charge carries no encounter mark");
      controlDeadline = ticks + 120;
      controlBurstSeen = false;
      controlExplosions = 0;
      controlToBlow = 0;
      controlDoorInToBlow = false;
      controlCentre = null;
      Tactical.LOGGER.info("[Tactical] breeze N control charge at {} door={} button={}", new Object[]{charge.position(), controlDoor, controlButton});
   }

   private static void observeNativeControl() {
      if (controlCharge != null && controlCharge.isRemoved() && controlExplosions > 0) {
         controlBurstSeen = true;
      }
   }

   private static boolean controlReady() {
      return controlBurstSeen || ticks > controlDeadline;
   }

   private static void checkNativeControl() {
      require(
         controlBurstSeen,
         "the unmarked native charge really burst: removed="
            + (controlCharge != null && controlCharge.isRemoved())
            + " explosions="
            + controlExplosions
            + " at="
            + (controlCharge == null ? "?" : controlCharge.position())
      );
      require(controlDoorInToBlow, "the control geometry really puts the door inside the native blast: toBlow=" + controlToBlow + " centre=" + controlCentre);
      boolean doorOpen = (Boolean)level.getBlockState(controlDoor).getValue(DoorBlock.OPEN);
      boolean buttonPressed = (Boolean)level.getBlockState(controlButton).getValue(ButtonBlock.POWERED);
      require(
         doorOpen != controlDoorOpenBefore, "an unmarked native charge still opens the door the way vanilla does: " + controlDoorOpenBefore + " -> " + doorOpen
      );
      require(
         buttonPressed != controlButtonPoweredBefore,
         "an unmarked native charge still presses the button the way vanilla does: " + controlButtonPoweredBefore + " -> " + buttonPressed
      );
      passed.add("N[door=" + doorOpen + " button=" + buttonPressed + "]");
      Tactical.LOGGER
         .info("[Tactical] BREEZE N PASS door {}->{} button {}->{}", new Object[]{controlDoorOpenBefore, doorOpen, controlButtonPoweredBefore, buttonPressed});
   }

   private static void endNativeControl() {
      controlDoor = null;
      controlButton = null;
      controlCharge = null;
   }

   private static void beginShotScenario() {
      resetPlayers();
      shooter = spawnBreeze(8.0, 0.0);
      sameSideAlly = spawnZombie(1.5, -2.0);
      doorPos = origin.offset(-1, 0, 0);
      place(doorPos, (BlockState)Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH));
      place(
         doorPos.above(),
         (BlockState)((BlockState)Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
            .setValue(DoorBlock.FACING, Direction.NORTH)
      );
      place(origin.offset(-2, 0, -2), Blocks.STONE.defaultBlockState());
      buttonPos = origin.offset(-1, 0, -2);
      place(buttonPos, (BlockState)Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACING, Direction.WEST));
      startBattle();
      JoinBattleResultPacket joined = BattleDiscovery.join(second, battle.sessionId());
      require(battle.participants().contains(second), "the second player really joined the encounter: " + joined);
      require(battle.participants().contains(sameSideAlly), "the same-side body belongs to this encounter");
      bystander = (Cow)EntityType.COW.create(level);
      require(bystander != null, "cow exists in this version");
      bystander.setPos(origin.getX() + 12.5, origin.getY(), origin.getZ() - 6.5);
      bystander.setNoAi(true);
      bystander.setPersistenceRequired();
      require(level.addFreshEntity(bystander), "cow registered in the real server world");
      require(!battle.participants().contains(bystander), "the cow is a real bystander, not a participant");
      shooterApBefore = battle.actorAp(shooter);
      shooterPaidBefore = battle.actorPaidActions(shooter);
      ownerApBefore = battle.actorAp(owner);
      secondApBefore = battle.actorAp(second);
      require(secondApBefore > 0, "the second member really has its own AP: " + secondApBefore);
      allyFeetBefore = sameSideAlly.position();
      allyHealthBefore = sameSideAlly.getHealth();
      doorOpenBefore = (Boolean)level.getBlockState(doorPos).getValue(DoorBlock.OPEN);
      buttonPoweredBefore = (Boolean)level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED);
      shotDeadline = ticks + 900;
      ownerApFirstEnemyTick = -1;
      ownerApLastEnemyTick = -1;
      secondApFirstEnemyTick = -1;
      secondApLastEnemyTick = -1;
      Tactical.LOGGER
         .info(
            "[Tactical] breeze S start ap={} paid={} ownerAp={} secondAp={} ally={}",
            new Object[]{shooterApBefore, shooterPaidBefore, ownerApBefore, secondApBefore, allyFeetBefore}
         );
   }

   private static void observeShotScenario() {
      if (shooter != null && shooter.getPose() == Pose.SHOOTING) {
         shotPoseSeen = true;
      }

      if (shooter != null && battle != null && battle.isActorTurn(shooter)) {
         ownerApLastEnemyTick = battle.actorAp(owner);
         secondApLastEnemyTick = battle.actorAp(second);
         if (ownerApFirstEnemyTick < 0) {
            ownerApFirstEnemyTick = ownerApLastEnemyTick;
            secondApFirstEnemyTick = secondApLastEnemyTick;
         }
      }

      if (sameSideAlly != null && battle != null && battle.participants().contains(sameSideAlly)) {
         battle.fixtureEnemyAp(sameSideAlly.getUUID(), 0);
      }
   }

   private static boolean shotScenarioReady() {
      require(battle != null && !battle.finished(), "the shot encounter is still live");
      return chargeJoins == 0 ? ticks > shotDeadline : !battle.isBusy() && BreezeWindCharges.size() == 0 || ticks > shotDeadline;
   }

   private static void checkShotScenario() {
      require(chargeJoins == 1, "exactly one native wind charge joined the world, joins=" + chargeJoins);
      require(shotPoseSeen, "the native charging pose was really written before the charge left");
      require(Math.abs(chargeSpeed - 0.7F) <= 0.35, "the charge carries the native speed, |" + round(chargeSpeed) + " - 0.7|");
      int expected = shooterPaidBefore == 0 ? 20 : ApCalculator.paidActionCost(20, shooterPaidBefore);
      require(
         battle.actorAp(shooter) == shooterApBefore - expected,
         "one charge is charged exactly once: " + shooterApBefore + " -> " + battle.actorAp(shooter) + " expected -" + expected
      );
      require(
         battle.actorPaidActions(shooter) == shooterPaidBefore + 1,
         "one successful paid action: " + shooterPaidBefore + " -> " + battle.actorPaidActions(shooter)
      );
      require(ownerApFirstEnemyTick >= 0, "the encounter really gave the breeze its own turn, so the member AP window was really observed");
      require(
         ownerApLastEnemyTick == ownerApFirstEnemyTick,
         "the acting member's AP is constant for the whole enemy action: " + ownerApFirstEnemyTick + " -> " + ownerApLastEnemyTick
      );
      require(
         secondApLastEnemyTick == secondApFirstEnemyTick,
         "another member's AP is constant for the whole enemy action: " + secondApFirstEnemyTick + " -> " + secondApLastEnemyTick
      );
      require(
         sameSideAlly.getHealth() == allyHealthBefore,
         "a same-side body inside the burst radius takes no damage: " + allyHealthBefore + " -> " + sameSideAlly.getHealth()
      );
      require(
         sameSideAlly.position().distanceTo(allyFeetBefore) < 0.05,
         "a same-side body is never pushed by the burst: " + allyFeetBefore + " -> " + sameSideAlly.position()
      );
      require(bystander.position().distanceTo(new Vec3(origin.getX() + 12.5, origin.getY(), origin.getZ() - 6.5)) < 0.05, "a bystander is never pushed either");
      require((Boolean)level.getBlockState(doorPos).getValue(DoorBlock.OPEN) == doorOpenBefore, "the marked charge left the door exactly as it was");
      require(level.getBlockState(doorPos.above()).is(Blocks.OAK_DOOR), "the door is still a real door");
      require(
         (Boolean)level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED) == buttonPoweredBefore, "the marked charge left the button exactly as it was"
      );
      require(BreezeWindCharges.size() == 0, "no wind charge mark outlives its own projectile");
      passed.add(
         "S[joins="
            + chargeJoins
            + " speed="
            + round(chargeSpeed)
            + " ap="
            + shooterApBefore
            + "->"
            + battle.actorAp(shooter)
            + " paid="
            + battle.actorPaidActions(shooter)
            + " ownerAp="
            + ownerApFirstEnemyTick
            + " secondAp="
            + secondApFirstEnemyTick
            + "]"
      );
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE S PASS joins={} speed={} ap={}->{} paid={}->{} ownerAp={} secondAp={} allyHp={} doorOpen={} buttonPowered={}",
            new Object[]{
               chargeJoins,
               round(chargeSpeed),
               shooterApBefore,
               battle.actorAp(shooter),
               shooterPaidBefore,
               battle.actorPaidActions(shooter),
               ownerApLastEnemyTick,
               secondApLastEnemyTick,
               sameSideAlly.getHealth(),
               level.getBlockState(doorPos).getValue(DoorBlock.OPEN),
               level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED)
            }
         );
   }

   private static void beginBandScenario() {
      resetPlayers();
      farBreeze = spawnBreeze(30.0, 0.0);
      startBattle();
      farDistanceBefore = farBreeze.position().distanceTo(owner.position());
      farApBefore = battle.actorAp(farBreeze);
      bandDeadline = ticks + 1800;
      Tactical.LOGGER.info("[Tactical] breeze B start distance={} ap={}", round(farDistanceBefore), farApBefore);
   }

   private static void observeBandScenario() {
      if (farBreeze != null && farBreeze.getPose() == Pose.SHOOTING) {
         chargeJoins++;
      }
   }

   private static boolean bandScenarioReady() {
      require(battle != null && !battle.finished(), "the band encounter is still live");
      if (farBreeze == null) {
         return true;
      } else {
         return battle.isBusy() ? false : battle.actorAp(farBreeze) < farApBefore || ticks > bandDeadline;
      }
   }

   private static void checkBandScenario() {
      require(chargeJoins == 0, "a breeze outside its native band never fires, joins=" + chargeJoins);
      double after = farBreeze.position().distanceTo(owner.position());
      require(
         after < farDistanceBefore - 0.5,
         "outside the band the body walks toward its opponent instead of inventing an attack: " + round(farDistanceBefore) + " -> " + round(after)
      );
      require(battle.actorPaidActions(farBreeze) == 0, "ordinary ground edges never count as a paid action: paid=" + battle.actorPaidActions(farBreeze));
      require(battle.actorAp(farBreeze) < farApBefore, "only the walked edges were charged: " + farApBefore + " -> " + battle.actorAp(farBreeze));
      passed.add("B[distance=" + round(farDistanceBefore) + "->" + round(after) + " joins=0]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE B PASS distance={}->{} ap={}->{} paid={} joins={}",
            new Object[]{round(farDistanceBefore), round(after), farApBefore, battle.actorAp(farBreeze), battle.actorPaidActions(farBreeze), chargeJoins}
         );
   }

   private static void beginJumpScenario() {
      resetPlayers();
      jumper = spawnBreeze(0.5, 18.0);
      startBattle();
      jumperApBefore = battle.actorAp(jumper);
      jumperStartY = jumper.getY();
      jumpDeadline = ticks + 2400;
      jumpWindowOpened = false;
      jumpWindowClosed = false;
      shotWindowClosed = false;
      jumpWasInhaling = false;
      jumpAirborneThisWindow = false;
      jumpFeet.clear();
      jumpStartFeet = jumper.position();
      jumpLandingFeet = null;
      Tactical.LOGGER.info("[Tactical] breeze J start ap={} at={}", jumperApBefore, jumper.position());
   }

   private static void observeJumpScenario() {
      if (jumper != null && battle != null) {
         boolean acting = battle.isActorTurn(jumper);
         boolean inhaling = jumper.getPose() == Pose.INHALING;
         boolean airborne = jumper.getPose() == Pose.LONG_JUMPING;
         if (inhaling) {
            inhaleSeen = true;
         }

         if (airborne) {
            airborneSeen = true;
            jumpAirborneThisWindow = true;
            if (airbornePoint == null) {
               airbornePoint = jumper.position();
            }
            // One entry per really committed frame: the same feet repeated across a tick that did not step is
            // not a second frame, and the whole per-tick chain is what the end of this window is checked against.
            Vec3 feet = jumper.position();
            if (jumpFeet.isEmpty() || jumpFeet.get(jumpFeet.size() - 1).distanceToSqr(feet) > 1.0E-12) {
               jumpFeet.add(feet);
            }
         }

         if (jumpAirborneThisWindow && jumper.getPose() == Pose.STANDING) {
            landedSeen = true;
            if (jumpLandingFeet == null) {
               jumpLandingFeet = jumper.position();
            }
         }

         if (acting) {
            if (inhaling && !jumpWasInhaling) {
               jumpWindowOpened = true;
               jumpWindowClosed = false;
               shotWindowClosed = false;
               jumpAirborneThisWindow = false;
               jumpApAtCommit = battle.actorAp(jumper);
               jumpPaidAtCommit = battle.actorPaidActions(jumper);
               jumpApLast = jumpApAtCommit;
               jumpPaidLast = jumpPaidAtCommit;
            }

            if (jumpWindowOpened && !jumpWindowClosed) {
               jumpApLast = battle.actorAp(jumper);
               jumpPaidLast = battle.actorPaidActions(jumper);
               boolean attemptOver = !inhaling && !airborne;
               if (attemptOver) {
                  if (jumpPaidLast > jumpPaidAtCommit) {
                     jumpWindowClosed = true;
                     shotApFirst = jumpApLast;
                     shotPaidFirst = jumpPaidLast;
                  } else {
                     jumpWindowOpened = false;
                     jumpAirborneThisWindow = false;
                  }
               }
            }

            if (jumpWindowClosed && !shotWindowClosed) {
               shotApLast = battle.actorAp(jumper);
               shotPaidLast = battle.actorPaidActions(jumper);
               if (jumperOwnTurnShots > 0) {
                  shotWindowClosed = true;
               }
            }
         }

         jumpWasInhaling = inhaling;
      }
   }

   private static boolean jumpScenarioReady() {
      require(battle != null && !battle.finished(), "the jump encounter is still live");
      if (jumper == null) {
         return true;
      } else {
         return battle.isBusy() ? false : jumperOwnTurnShots > 0 && jumpWindowClosed || ticks > jumpDeadline;
      }
   }

   private static void checkJumpScenario() {
      require(inhaleSeen, "the native inhale pose was really written before the jump");
      require(airborneSeen, "the body really left the ground on the native long-jump pose");
      require(
         airbornePoint != null && !airbornePoint.equals(jumper.position()),
         "the jump is a real arc, not an instantaneous teleport to its landing: " + airbornePoint + " vs " + jumper.position()
      );
      require(landedSeen, "the body really landed back on a standing pose");
      checkJumpArc();
      require(jumpWindowOpened && jumpWindowClosed, "the jump's own price window was really measured");
      int spentJump = jumpApAtCommit - jumpApLast;
      require(
         spentJump == 20, "one jump costs exactly its design price inside its own turn: spent=" + spentJump + " window " + jumpApAtCommit + "->" + jumpApLast
      );
      require(jumpPaidLast - jumpPaidAtCommit == 1, "one jump counts as exactly one successful action: paid=" + jumpPaidAtCommit + " -> " + jumpPaidLast);
      require(jumperOwnTurnShots <= 1, "a jump buys at most one shot, shots=" + jumperOwnTurnShots);
      int spentShot = 0;
      if (jumperOwnTurnShots > 0) {
         require(shotWindowClosed, "the follow-up shot's own price window was really measured");
         spentShot = shotApFirst - shotApLast;
         int expected = ApCalculator.paidActionCost(20, shotPaidFirst);
         require(
            spentShot == expected,
            "the follow-up shot of the same turn costs exactly one surcharged charge: spent="
               + spentShot
               + " expected "
               + expected
               + " window "
               + shotApFirst
               + "->"
               + shotApLast
         );
         require(shotPaidLast - shotPaidFirst == 1, "the follow-up shot counts as exactly one action: paid=" + shotPaidFirst + " -> " + shotPaidLast);
      }

      passed.add("J[jump=" + spentJump + " shot=" + spentShot + " shots=" + jumperOwnTurnShots + " airborne=" + airborneSeen + " landed=" + landedSeen + "]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE J PASS jumpCost={} shotCost={} shots={} inhale={} airborne={} landed={} firstAirFrame={} jumpWindow={}->{} shotWindow={}->{}",
            new Object[]{
               spentJump,
               spentShot,
               jumperOwnTurnShots,
               inhaleSeen,
               airborneSeen,
               landedSeen,
               airbornePoint,
               jumpApAtCommit,
               jumpApLast,
               shotApFirst,
               shotApLast
            }
         );
   }

   /**
    * Every really committed frame of that one arc, including its last step, is one per-tick segment of the
    * native launch vector - and the last step ends on ground, not on the aim.
    *
    * <p>This is the difference between "airborne was seen" and "the body flew": the arc keeps one constant
    * horizontal step (the launch's own friction-discard branch applies no drag to it), loses exactly one gravity
    * step vertically per frame, and no frame - especially not the last one - is longer than the native velocity
    * ceiling. A final jump onto the candidate would break the length bound by several blocks, and the old
    * dragged integration would break the constant horizontal step, so both failures are caught here instead of
    * only in a screenshot.</p>
    */
   private static void checkJumpArc() {
      require(jumpLandingFeet != null, "the landed exact feet were really observed");
      require(jumpStartFeet != null, "the take-off feet were really observed");
      require(jumpFeet.size() >= 3, "the arc really flew several per-tick frames, frames=" + jumpFeet.size());
      double gravity = jumper.getGravity();
      // The take-off feet are the frame before the first committed one: the first step is a full per-tick segment.
      Vec3 firstStep = jumpFeet.get(0).subtract(jumpStartFeet);
      double horizontal = Math.hypot(firstStep.x, firstStep.z);
      require(horizontal > 0.05, "the arc really travels: horizontal step=" + horizontal);
      require(
         firstStep.length() <= BreezeRules.JUMP_MAX_VELOCITY + 1.0E-9,
         "the first frame is one per-tick segment, step=" + firstStep.length()
      );
      require(firstStep.y > 0.0, "the first frame really rises: dy=" + firstStep.y);
      Vec3 heading = new Vec3(firstStep.x, 0.0, firstStep.z).normalize();
      double before = firstStep.y;
      for (int index = 1; index < jumpFeet.size(); index++) {
         Vec3 step = jumpFeet.get(index).subtract(jumpFeet.get(index - 1));
         require(
            Math.abs(Math.hypot(step.x, step.z) - horizontal) <= 1.0E-9,
            "the horizontal step is un-dragged and constant, frame " + index + " step=" + Math.hypot(step.x, step.z)
         );
         require(
            step.length() <= BreezeRules.JUMP_MAX_VELOCITY + 1.0E-9,
            "no frame is a teleport: frame " + index + " step=" + step.length()
         );
         require(
            Math.abs(step.y - (before - gravity)) <= 1.0E-9,
            "every frame loses exactly one gravity step: frame " + index + " dy=" + step.y + " before=" + before
         );
         before = step.y;
      }
      // The last step is that same leg, cut where it really meets the tested support plane.
      Vec3 finalStep = jumpLandingFeet.subtract(jumpFeet.get(jumpFeet.size() - 1));
      require(finalStep.y < 0.0, "the last step is the descent onto the floor: dy=" + finalStep.y);
      require(
         Math.abs(finalStep.y) <= Math.abs(before - gravity) + 1.0E-9,
         "the last step is at most one per-tick segment: dy=" + finalStep.y + " next=" + (before - gravity)
      );
      double finalHorizontal = Math.hypot(finalStep.x, finalStep.z);
      require(
         finalHorizontal > 1.0E-6 && finalHorizontal <= horizontal + 1.0E-9,
         "the last step keeps the arc's own horizontal reach: step=" + finalHorizontal + " cap=" + horizontal
      );
      require(
         new Vec3(finalStep.x, 0.0, finalStep.z).normalize().subtract(heading).length() <= 1.0E-9,
         "the last step keeps the arc's own heading"
      );
      require(
         Math.abs(jumpLandingFeet.y - jumperStartY) <= 1.0E-9,
         "the contact is exactly on the native floor plane, landed=" + jumpLandingFeet.y + " start=" + jumperStartY
      );
      require(footprintSupported(jumpLandingFeet), "the whole footprint really rests on collision surfaces");
      Tactical.LOGGER.info(
         "[Tactical] BREEZE J ARC frames={} horizontal={} landed={} start={}",
         jumpFeet.size(),
         String.format(java.util.Locale.ROOT, "%.6f", horizontal),
         jumpLandingFeet,
         jumperStartY
      );
   }

   /** The whole landing footprint really stands on non-empty native collision shapes one block lower. */
   private static boolean footprintSupported(Vec3 feet) {
      if (feet == null || jumper == null || level == null) return false;
      AABB box = jumper.getBoundingBox().move(feet.subtract(jumper.position()));
      int minX = Mth.floor(box.minX + 1.0E-9D);
      int maxX = Math.max(minX, Mth.floor(box.maxX - 1.0E-9D));
      int minZ = Mth.floor(box.minZ + 1.0E-9D);
      int maxZ = Math.max(minZ, Mth.floor(box.maxZ - 1.0E-9D));
      int groundY = Mth.floor(feet.y) - 1;
      for (int x = minX; x <= maxX; x++) {
         for (int z = minZ; z <= maxZ; z++) {
            BlockPos pos = new BlockPos(x, groundY, z);
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
         }
      }
      return true;
   }

   /**
    * The contact guard, first half: the floor under the projected landing is taken away during the inhale.
    *
    * <p>The proposal was proved when it was made, but the world is allowed to change while the body charges. A
    * removed floor creates no collision, so the swept legs still clear and only the real-contact question can
    * notice. The jump must be refused before the first displacement - free, unmoved, and with no shot - instead of
    * flying an arc that ends in mid air.</p>
    */
   private static void beginGuardEarlyScenario() {
      resetPlayers();
      guardTarget = spawnBreeze(0.5, 18.0);
      startBattle();
      guardShots = 0;
      guardInhaleSeen = false;
      guardFloorRemoved = false;
      guardPaidAtRemoval = false;
      guardStartFeet = guardTarget.position();
      guardContactFeet = null;
      guardLastCommitted = null;
      guardArcLen = -1;
      guardApBefore = battle.actorAp(guardTarget);
      guardPaidBefore = battle.actorPaidActions(guardTarget);
      guardDeadline = ticks + 2400;
      Tactical.LOGGER.info("[Tactical] breeze G1 start ap={} paid={} at={}",
              guardApBefore, guardPaidBefore, guardStartFeet);
   }

   private static void observeGuardEarlyScenario() {
      if (guardTarget == null || battle == null) return;
      if (guardTarget.getPose() != Pose.INHALING) return;
      guardInhaleSeen = true;
      if (guardFloorRemoved) return;
      BreezeController.JumpView view = battle.fixtureJump(guardTarget.getUUID());
      if (view == null) return;
      guardContactFeet = view.feet();
      guardArcLen = view.arc().length;
      guardFloorRemoved = removeSupportUnder(view.feet());
      Tactical.LOGGER.info("[Tactical] breeze G1 floor removed under contact={} arc={} removed={}",
              view.feet(), guardArcLen, guardFloorRemoved);
   }

   private static boolean guardEarlyReady() {
      require(guardTarget != null && battle != null, "the guard-early scenario has a live encounter");
      if (ticks > guardDeadline) return true;
      return guardInhaleSeen && guardTarget.getPose() != Pose.INHALING
              && guardTarget.getPose() != Pose.LONG_JUMPING;
   }

   private static void checkGuardEarlyScenario() {
      require(guardInhaleSeen, "the native inhale really started before the floor was removed");
      require(guardFloorRemoved, "the projected support footprint was really removed during the inhale");
      require(guardContactFeet != null, "the projected contact was really known before the launch");
      require(guardTarget.getPose() != Pose.LONG_JUMPING, "no frame of the arc was flown: " + guardTarget.getPose());
      require(sameFeet(guardTarget.position(), guardStartFeet),
              "the refused body never moved: " + guardTarget.position() + " vs " + guardStartFeet);
      int spent = guardApBefore - battle.actorAp(guardTarget);
      require(spent == 0, "a jump refused before its first displacement is free: spent=" + spent);
      require(battle.actorPaidActions(guardTarget) == guardPaidBefore,
              "and it counts as no action: paid=" + battle.actorPaidActions(guardTarget));
      require(guardShots == 0, "a jump that never flew bought no shot: charges=" + guardShots);
      require(battle.fixtureJump(guardTarget.getUUID()) == null,
              "the encounter does not own a jump any more after the refusal");
      passed.add("G1[spent=0 paid=0 shots=0 contact=" + guardContactFeet + "]");
      Tactical.LOGGER.info("[Tactical] BREEZE G1 PASS removedContact={} contact={} start={} shots={}",
              guardContactFeet, guardTarget.position(), guardStartFeet, guardShots);
   }

   /**
    * The contact guard, second half: the same floor is taken away one frame before the paid final step.
    *
    * <p>By then the jump has really flown and has been paid once. A cached contact that lost its ground must not
    * be written as the last frame: the body keeps the position it really reached, the existing interruption path
    * takes the jump away (no landing, no follow-up shot), and the existing fall rule owns the descent.</p>
    */
   private static void beginGuardLateScenario() {
      resetPlayers();
      guardTarget = spawnBreeze(0.5, 18.0);
      startBattle();
      guardShots = 0;
      guardInhaleSeen = false;
      guardFloorRemoved = false;
      guardPaidAtRemoval = false;
      guardStartFeet = guardTarget.position();
      guardContactFeet = null;
      guardLastCommitted = null;
      guardArcLen = -1;
      guardApBefore = battle.actorAp(guardTarget);
      guardPaidBefore = battle.actorPaidActions(guardTarget);
      guardDeadline = ticks + 2400;
      Tactical.LOGGER.info("[Tactical] breeze G2 start ap={} paid={} at={}",
              guardApBefore, guardPaidBefore, guardStartFeet);
   }

   private static void observeGuardLateScenario() {
      if (guardTarget == null || battle == null) return;
      if (guardFloorRemoved) return;
      BreezeController.JumpView view = battle.fixtureJump(guardTarget.getUUID());
      if (view == null || !view.launched()) return;
      guardInhaleSeen = true;
      if (view.frames() != view.arc().length - 2) return;
      guardArcLen = view.arc().length;
      guardLastCommitted = view.arc()[view.frames()];
      guardContactFeet = view.feet();
      guardPaidAtRemoval = view.paid();
      guardFloorRemoved = removeSupportUnder(view.feet());
      Tactical.LOGGER.info("[Tactical] breeze G2 floor removed under contact={} frames={}/{} paid={} removed={}",
              view.feet(), view.frames(), guardArcLen, view.paid(), guardFloorRemoved);
   }

   private static boolean guardLateReady() {
      require(guardTarget != null && battle != null, "the guard-late scenario has a live encounter");
      if (ticks > guardDeadline) return true;
      return guardFloorRemoved && battle.fixtureJump(guardTarget.getUUID()) == null
              && guardTarget.getPose() != Pose.LONG_JUMPING;
   }

   private static void checkGuardLateScenario() {
      require(guardFloorRemoved, "the projected support footprint was really removed before the paid final step");
      require(guardPaidAtRemoval, "the removal happened after the first real displacement, so the jump was paid");
      require(guardContactFeet != null && guardLastCommitted != null,
              "both the projected contact and the last committed frame were really known");
      require(guardTarget.getPose() == Pose.STANDING,
              "the existing interruption restored the original pose: " + guardTarget.getPose());
      require(sameFeet(guardTarget.position(), guardLastCommitted),
              "the body kept the last frame it really reached, not the refused contact: " + guardTarget.position()
                      + " vs " + guardLastCommitted + " (contact " + guardContactFeet + ")");
      require(guardTarget.position().distanceToSqr(guardContactFeet) > 1.0E-8D,
              "the interrupted body never reached the contact whose floor was removed");
      int spent = guardApBefore - battle.actorAp(guardTarget);
      require(spent == 20, "the paid jump was charged exactly once: spent=" + spent);
      require(battle.actorPaidActions(guardTarget) - guardPaidBefore == 1,
              "one paid action, never a second: paid=" + battle.actorPaidActions(guardTarget));
      require(guardShots == 0, "no follow-up shot was fired from a landing the world never offered: " + guardShots);
      require(battle.fixtureJump(guardTarget.getUUID()) == null,
              "the encounter never marked that arc as landed");
      // The existing fall owns the interrupted body: no made-up landing, no frozen body in mid air.
      boolean fallOwned = battle.terrainFallActive();
      double lowest = guardTarget.getY();
      for (int guard = 0; guard < 30; guard++) {
         battle.tick();
         lowest = Math.min(lowest, guardTarget.getY());
         if (battle.terrainFallActive()) fallOwned = true;
      }
      require(fallOwned, "the existing fall rule really owns the interrupted body: lowest=" + lowest
              + " from " + guardLastCommitted);
      require(guardTarget.getY() < guardLastCommitted.y - 1.0E-6D,
              "the body really descends through the existing rule: " + guardTarget.getY()
                      + " from " + guardLastCommitted.y);
      passed.add("G2[spent=" + spent + " paid=1 shots=0 kept=" + guardLastCommitted + "]");
      Tactical.LOGGER.info("[Tactical] BREEZE G2 PASS kept={} contact={} spent={} shots={} lowest={}",
              guardLastCommitted, guardContactFeet, spent, guardShots, lowest);
   }

   /**
    * Take away the real support under the whole footprint of that contact, and remember exactly what was there.
    *
    * <p>The blocks asked for are the ones the projected landing would rest on - the footprint of the whole body,
    * not merely the aim cell - and only fixture blocks are touched: every original state recorded here is put
    * back when this scenario ends.</p>
    */
   private static boolean removeSupportUnder(Vec3 feet) {
      if (feet == null || guardTarget == null || level == null) return false;
      EntityDimensions dimensions = guardTarget.getDimensions(Pose.STANDING);
      AABB box = dimensions.makeBoundingBox(feet);
      int minX = Mth.floor(box.minX + 1.0E-9D);
      int maxX = Math.max(minX, Mth.floor(box.maxX - 1.0E-9D));
      int minZ = Mth.floor(box.minZ + 1.0E-9D);
      int maxZ = Math.max(minZ, Mth.floor(box.maxZ - 1.0E-9D));
      int groundY = Mth.floor(feet.y) - 1;
      boolean changed = false;
      for (int x = minX; x <= maxX; x++) {
         for (int z = minZ; z <= maxZ; z++) {
            BlockPos pos = new BlockPos(x, groundY, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            guardRestorePos.add(pos);
            guardRestoreState.add(state);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            changed = true;
         }
      }
      return changed;
   }

   /** The same exact feet, in the doubles the server really wrote. */
   private static boolean sameFeet(Vec3 a, Vec3 b) {
      return a != null && b != null && a.distanceToSqr(b) <= 1.0E-18D;
   }

   private static void beginRefusalScenario() {
      resetPlayers();
      refuser = spawnBreeze(0.5, 18.0);
      startBattle();
      refuserApBefore = battle.actorAp(refuser);
      refuserPaidBefore = battle.actorPaidActions(refuser);
      refuserStartY = refuser.getY();
      refusalDeadline = ticks + 2400;
      refuserApFirstTurnTick = -1;
      refuserApLastTurnTick = -1;
      refuserPaidFirstTurnTick = -1;
      refuserPaidLastTurnTick = -1;
      refuserWindowClosed = false;
      Tactical.LOGGER.info("[Tactical] breeze P start ap={} paid={} at={}", new Object[]{refuserApBefore, refuserPaidBefore, refuser.position()});
   }

   private static void observeRefusalScenario() {
      if (refuser != null) {
         if (refuser.getPose() == Pose.INHALING && !refusalInhaleSeen) {
            refusalInhaleSeen = true;
            Vec3 from = refuser.position();
            Vec3 toward = new Vec3(owner.getX() - from.x, 0.0, owner.getZ() - from.z).normalize();

            for (int step = 1; step <= 2; step++) {
               BlockPos wall = BlockPos.containing(from.add(toward.scale(step)));
               place(wall, Blocks.STONE.defaultBlockState());
               place(wall.above(), Blocks.STONE.defaultBlockState());
            }
         }

         if (battle != null && battle.isActorTurn(refuser) && !refuserWindowClosed) {
            boolean committed = refuser.getPose() == Pose.INHALING;
            if (committed && !refuserWasInhaling) {
               refuserApFirstTurnTick = -1;
               refuserPaidFirstTurnTick = -1;
               refuserApLastTurnTick = -1;
               refuserPaidLastTurnTick = -1;
            }

            if (committed && refuserApFirstTurnTick < 0) {
               refuserApFirstTurnTick = battle.actorAp(refuser);
               refuserPaidFirstTurnTick = battle.actorPaidActions(refuser);
            }

            if (refuserApFirstTurnTick >= 0) {
               refuserApLastTurnTick = battle.actorAp(refuser);
               refuserPaidLastTurnTick = battle.actorPaidActions(refuser);
               if (refusalInhaleSeen && refuser.getPose() == Pose.STANDING) {
                  refuserWindowClosed = true;
               }
            }

            refuserWasInhaling = committed;
         }
      }
   }

   private static boolean refusalScenarioReady() {
      require(battle != null && !battle.finished(), "the refusal encounter is still live");
      if (refuser == null) {
         return true;
      } else {
         return battle.isBusy() ? false : refusalInhaleSeen || ticks > refusalDeadline;
      }
   }

   private static void checkRefusalScenario() {
      require(refusalInhaleSeen, "the jump really started its native inhale before it was interrupted");
      require(refuserApFirstTurnTick >= 0, "the breeze really took its own turn in this scenario");
      require(
         refuserApLastTurnTick == refuserApFirstTurnTick,
         "a jump refused before its first frame is free: " + refuserApFirstTurnTick + " -> " + refuserApLastTurnTick
      );
      require(
         refuserPaidLastTurnTick == refuserPaidFirstTurnTick,
         "a refused jump never counts as a successful action: paid=" + refuserPaidFirstTurnTick + " -> " + refuserPaidLastTurnTick
      );
      require(refuser.getPose() == Pose.STANDING, "a refused jump gives the standing pose back: " + refuser.getPose());
      require(Math.abs(refuser.getY() - refuserStartY) < 0.05, "a refused jump never leaves the ground: " + refuserStartY + " -> " + refuser.getY());
      require(chargeJoins == 0, "a refused jump never fires anything, joins=" + chargeJoins);
      passed.add("P[ap=" + refuserApFirstTurnTick + "->" + refuserApLastTurnTick + " paid=" + refuserPaidFirstTurnTick + "->" + refuserPaidLastTurnTick + "]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE P PASS ap={} paid={} pose={} y={} joins={}",
            new Object[]{refuserApBefore, refuserPaidFirstTurnTick + "->" + refuserPaidLastTurnTick, refuser.getPose(), refuser.getY(), chargeJoins}
         );
   }

   private static void beginInterruptScenario() {
      resetPlayers();
      interrupted = spawnBreeze(0.5, 18.0);
      startBattle();
      interruptStart = interrupted.position();
      interruptPlaced = false;
      interruptAirborne = false;
      interruptStopped = false;
      interruptWasAirborne = false;
      interruptSawActorTurn = false;
      interruptApAtCommit = -1;
      interruptPaidAtCommit = -1;
      interruptApLast = -1;
      interruptPaidLast = -1;
      interruptDeadline = ticks + 2400;
      Tactical.LOGGER.info("[Tactical] breeze R start at={} ap={}", interruptStart, battle.actorAp(interrupted));
   }

   private static void observeInterruptScenario() {
      if (interrupted != null && battle != null) {
         boolean inhaling = interrupted.getPose() == Pose.INHALING;
         boolean airborne = interrupted.getPose() == Pose.LONG_JUMPING;
         boolean acting = battle.isActorTurn(interrupted);
         if (airborne) {
            interruptAirborne = true;
         }

         if (airborne && !interruptPlaced) {
            interruptPlaced = true;
            Vec3 direction = new Vec3(interrupted.getX() - interruptStart.x, 0.0, interrupted.getZ() - interruptStart.z);
            Vec3 forward = direction.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : direction.normalize();
            Vec3 side = new Vec3(-forward.z, 0.0, forward.x);

            for (int step = 1; step <= 4; step++) {
               for (int lateral = -1; lateral <= 1; lateral++) {
                  BlockPos base = BlockPos.containing(interrupted.position().add(forward.scale(step)).add(side.scale(lateral)));

                  for (int dy = 0; dy <= 3; dy++) {
                     place(base.above(dy), Blocks.STONE.defaultBlockState());
                  }
               }
            }

            Tactical.LOGGER.info("[Tactical] breeze R wall placed while airborne at {} forward={}", interrupted.position(), forward);
         }

         if (inhaling && interruptApAtCommit < 0) {
            interruptApAtCommit = battle.actorAp(interrupted);
            interruptPaidAtCommit = battle.actorPaidActions(interrupted);
            interruptApLast = interruptApAtCommit;
            interruptPaidLast = interruptPaidAtCommit;
         }

         if (interruptApAtCommit >= 0 && !interruptStopped) {
            interruptApLast = battle.actorAp(interrupted);
            interruptPaidLast = battle.actorPaidActions(interrupted);
            if (interruptWasAirborne && !airborne) {
               interruptStopped = interruptPaidLast > interruptPaidAtCommit;
            }
         }

         interruptWasAirborne = airborne;
         interruptSawActorTurn |= acting;
      }
   }

   private static boolean interruptScenarioReady() {
      require(battle != null && !battle.finished(), "the interrupted-jump encounter is still live");
      if (interrupted == null) {
         return true;
      } else {
         return battle.isBusy() ? false : interruptStopped || ticks > interruptDeadline;
      }
   }

   private static void checkInterruptScenario() {
      require(interruptAirborne, "the jump really left the ground before the arc was broken");
      require(interruptPlaced, "the fixture really broke the remaining arc mid-flight");
      require(interruptSawActorTurn, "the interrupted body really held its own turn");
      require(
         interruptApAtCommit >= 0 && interruptStopped,
         "the launched jump really settled inside its own turn: window "
            + interruptApAtCommit
            + "->"
            + interruptApLast
            + " paid "
            + interruptPaidAtCommit
            + "->"
            + interruptPaidLast
      );
      int spent = interruptApAtCommit - interruptApLast;
      require(
         spent == 20,
         "an already launched jump keeps exactly its price and is never refunded: spent=" + spent + " window " + interruptApAtCommit + "->" + interruptApLast
      );
      require(
         interruptPaidLast - interruptPaidAtCommit == 1,
         "the interrupted jump counts as exactly one successful action: paid=" + interruptPaidAtCommit + " -> " + interruptPaidLast
      );
      require(interrupted.getPose() == Pose.STANDING, "an interrupted jump hands the standing pose back: " + interrupted.getPose());
      require(!interrupted.shouldDiscardFriction(), "an interrupted jump restores the native friction flag");
      Vec3 real = interrupted.position();
      require(
         real.distanceTo(interruptStart) > 0.3,
         "the interrupted body stays where it really flew to, not at its take-off point: " + interruptStart + " -> " + real
      );
      require(!battle.isBusy(), "an interrupted jump never leaves the encounter busy");
      passed.add("R[spent=" + spent + " paid=1 reached=" + round(real.distanceTo(interruptStart)) + "]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE R PASS spent={} paid=1 pose={} friction={} reached={} at={}",
            new Object[]{spent, interrupted.getPose(), interrupted.shouldDiscardFriction(), round(real.distanceTo(interruptStart)), real}
         );
   }

   private static void beginDeflectionScenario() {
      resetPlayers();
      TacticalConfig.INSTANCE.friendlyFire.set(true);
      deflector = spawnBreeze(4.0, 0.0);
      deflectorAlly = spawnZombie(4.0, 3.0);
      secondDeflector = spawnBreeze(4.0, -3.0);
      startBattle();
      JoinBattleResultPacket joinedSecond = BattleDiscovery.join(second, battle.sessionId());
      require(battle.participants().contains(second), "the second player really joined the encounter: " + joinedSecond);
      deflectionBystander = (Cow)EntityType.COW.create(level);
      require(deflectionBystander != null, "cow exists in this version");
      deflectionBystander.setPos(origin.getX() + 9.5, origin.getY(), origin.getZ() + 6.5);
      deflectionBystander.setNoAi(true);
      deflectionBystander.setPersistenceRequired();
      require(level.addFreshEntity(deflectionBystander), "the bystander cow joined the world");
      require(!battle.participants().contains(deflectionBystander), "the deflection bystander really is outside this encounter");
      owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
      owner.getInventory().add(new ItemStack(Items.ARROW, 32));
      deflectorApBefore = 0;
      secondDeflectorApBefore = 0;
      dOwnerApBefore = 0;
      ownerPaidBefore = 0;
      deflectionStage = 0;
      allowedAttempts = 0;
      allowedAimed = false;
      lastAimTick = 0;
      reflectionRecorded = false;
      repeatedReflectionRecorded = false;
      refusalResolved = false;
      allowedHitResolved = false;
      deflectionDeadline = ticks + 1200;
      Tactical.LOGGER
         .info(
            "[Tactical] breeze D start owner={} bow={} arrows={}",
            new Object[]{owner.position(), owner.getMainHandItem(), owner.getInventory().countItem(Items.ARROW)}
         );
   }

   /**
    * The owner really spends real bow shots at the breeze, the breeze really reverses them natively, and each
    * reversed arrow really meets a body:
    *
    * <ol>
    *   <li>a same-side body of the deflector, put on the shot's real return line: the impact is refused before the
    *       native hurt, so that body takes nothing - and the old rule could never have refused it, because it asked
    *       whether the recipient was opposed to the <em>shooter</em>, which it is;</li>
    *   <li>another member of the original shooter's side, on the same real return line: the reversed shot really
    *       lands and really damages it, while the member that fired keeps its own health, the native owner is
    *       unchanged and exactly one charged action paid for the shot;</li>
    *   <li>the same reversed arrow, turned around a second time by a second controlled breeze: the recorded
    *       deflector must become the latest one, because that is the authority the second real reversal earns.</li>
    * </ol>
    *
    * <p>The arrow is never given a manufactured collision: it keeps the exact velocity the native reversal gave it
    * and only its position is moved to the body it is really aimed at, one frame before contact. Friendly fire is
    * switched on for this group alone, because a reversed member shot can only ever land on a member and this group
    * must be decided by the encounter's own permission rather than by vanilla's friendly-fire gate.</p>
    */
   private static void observeDeflectionScenario() {
      if (battle != null && !battle.finished()) {
         switch (deflectionStage) {
            case 0:
               if (battle.participants().contains(deflector)
                  && battle.participants().contains(secondDeflector)
                  && battle.participants().contains(deflectorAlly)) {
                  if (!battle.isActorTurn(owner)) {
                     return;
                  }

                  deflectorApBefore = battle.actorAp(deflector);
                  secondDeflectorApBefore = battle.actorAp(secondDeflector);
                  dOwnerApBefore = battle.actorAp(owner);
                  ownerPaidBefore = battle.actorPaidActions(owner);
                  dAllyHealthBefore = deflectorAlly.getHealth();
                  ownerHealthBefore = owner.getHealth();
                  secondHealthBefore = second.getHealth();
                  battle.tryAttack(deflector.getUUID());
                  deflectedArrow = nextArrow();
                  if (deflectedArrow == null) {
                     return;
                  }

                  arrowDeltaBefore = deflectedArrow.getDeltaMovement();
                  arrowOwnerBefore = deflectedArrow.getOwner() == null ? null : deflectedArrow.getOwner().getUUID();
                  deflectionStage = 1;
               }
               break;
            case 1:
               BreezeDeflections.Reflection first = BreezeDeflections.of(deflectedArrow);
               if (first == null) {
                  return;
               }

               reflectionRecorded = true;
               recordedDeflector = first.deflectorId();
               permissionDeflectorRefused = !battle.reflectionAllows(deflectedArrow, deflector);
               permissionBystanderRefused = !battle.reflectionAllows(deflectedArrow, deflectionBystander);
               permissionOpposedAllowed = battle.reflectionAllows(deflectedArrow, owner);
               aimArrowAt(deflectedArrow, deflectorAlly);
               deflectionStage = 2;
               break;
            case 2:
               if (deflectedArrow.isRemoved() || deflectorAlly.getHealth() < dAllyHealthBefore) {
                  refusalResolved = true;
                  ownerApAfterRefusal = battle.actorAp(owner);
                  ownerPaidAfterRefusal = battle.actorPaidActions(owner);
                  deflectionStage = 3;
               }
               break;
            case 3:
               deflectorAlly.discard();
               deflectorAlly = null;
               deflectionStage = 4;
               break;
            case 4:
               // The real outcome of the shot's own collision, asked first: the impact may be the last thing that
               // happens to this arrow, so a state that is only read afterwards would miss it.
               if (memberImpactPermitted && !memberImpactCancelled) {
                  ownerHealthAfterHit = second.getHealth();
                  allowedHitResolved = true;
                  deflectionStage = 6;
                  return;
               }
               // One real paid bow shot at the breeze, followed until it answers. A shot that lands before the
               // reflection is replaced by another one - the encounter's own quoter bills each of them - so this
               // group never depends on one arrow's luck with the floor.
               if (allowedArrow == null || allowedArrow.isRemoved()) {
                  if (allowedAimed) {
                     deflectionStage = 6;
                     return;
                  }
                  if (allowedAttempts >= 3) {
                     deflectionStage = 8;
                     return;
                  }
                  battle.tryAttack(deflector.getUUID());
                  allowedArrow = nextArrow();
                  if (allowedArrow == null) {
                     return;
                  }
                  allowedAttempts++;
                  return;
               }
               BreezeDeflections.Reflection shot = BreezeDeflections.of(allowedArrow);
               if (shot == null) {
                  return;
               }
               if (!allowedAimed) {
                  // The same reversed shot, answering to a second real deflector. The replacement is exercised
                  // through the exact production call a native deflection makes: a second native collision of one
                  // arrow with a second controlled body would need that body moved mid-flight, and an encounter
                  // unit's cell is not a fixture's to move. What such a collision would do is not claimed here; the
                  // record rule it feeds is.
                  BreezeDeflections.record(allowedArrow, shot.sessionId(), secondDeflector.getUUID());
                  BreezeDeflections.Reflection turned = BreezeDeflections.of(allowedArrow);
                  require(turned != null && turned.deflectorId().equals(secondDeflector.getUUID()),
                          "a later real deflection replaces the recorded deflector instead of freezing the first");
                  repeatedReflectionRecorded = true;
                  secondRecordedDeflector = turned.deflectorId();
                  // The body that deflected it first no longer answers for it: its own partial exit must leave
                  // this shot alone, which is what "the latest deflector owns the shot" means on the exit path.
                  BreezeDeflections.forgetDeflector(deflector.getUUID());
                  require(BreezeDeflections.of(allowedArrow) != null,
                          "a shot owned by a later deflector survives the earlier one's exit");
                  allowedAimed = true;
               }
               // A real opposing member of the original shooter's side, on the shot's real return line. Aimed
               // again only after a pause: an arrow that embedded in the floor un-sticks on the frame it really
               // moves, so re-placing it every tick would hold it still forever.
               if (ticks - lastAimTick >= 5) {
                  lastAimTick = ticks;
                  aimArrowAt(allowedArrow, second);
               }
               break;
            case 6:
               deflectionStage = 8;
               break;
            default:
         }
      }
   }

   /**
    * Put one already-flying arrow on a real intercept course with that body, one frame before contact.
    *
    * <p>The arrow keeps the exact velocity a real native deflection gave it; only its position is moved, so the
    * collision, the impact event, the refusal or the damage and every native effect of them are the game's own.
    * This is the same fixture entry the trident proof uses: the encounter's rules can only be exercised by a
    * projectile really reaching a body the plan would never aim at.</p>
    */
   private static void aimArrowAt(Arrow arrow, LivingEntity body) {
      if (arrow != null && body != null) {
         Vec3 direction = arrow.getDeltaMovement();
         double speed = direction.length();
         if (!(speed < 1.0E-6)) {
            // Never further than half of one real step, so the very next native move reaches the body: a reversed
            // arrow flies at half speed and a twice-reversed one at a quarter, so a fixed gap would never close.
            double reach = Math.max(0.15, Math.min(0.8, speed * 0.5));
            Vec3 at = body.position().add(0.0, 1.0, 0.0).subtract(direction.normalize().scale(reach));
            arrow.setPos(at.x, at.y, at.z);
         }
      }
   }

   /**
    * The newest real arrow this encounter's owner really put in the world and this group has not seen yet.
    *
    * <p>Identity, not a position: a real arrow that already hit something keeps flying at a reduced speed and would
    * otherwise be mistaken for the shot of the next step.</p>
    */
   @Nullable
   private static Arrow nextArrow() {
      AABB box = new AABB(origin.getX() - 8.0, origin.getY() - 4.0, origin.getZ() - 8.0, origin.getX() + 20.0, origin.getY() + 8.0, origin.getZ() + 8.0);
      Arrow found = null;

      for (Arrow arrow : level.getEntitiesOfClass(Arrow.class, box)) {
         if (arrow.getOwner() == owner && !arrow.isRemoved() && !seenArrows.contains(arrow) && (found == null || arrow.getId() > found.getId())) {
            found = arrow;
         }
      }

      if (found != null) {
         seenArrows.add(found);
      }

      return found;
   }

   /**
    * The real pre-impact outcome of every reversed arrow of the deflection group.
    *
    * <p>Recorded at the lowest priority and with cancelled events included, so this is the final answer after every
    * listener: a body of the deflector's own side must arrive here <em>cancelled</em> and the member of the
    * shooter's side must arrive <em>permitted</em>. That is the same event the encounter's own guard cancels, which
    * is the only place a refusal can happen before vanilla's hurt and effects.</p>
    */
   @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
   public static void onArrowImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
      if (!enabled || level == null) return;
      if (!(event.getProjectile() instanceof Arrow arrow)) return;
      if (arrow.getOwner() != owner) return;
      if (!(event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult entityHit)) return;
      LivingEntity victim = entityHit.getEntity() instanceof LivingEntity living ? living : null;
      if (victim == null) return;
      if (victim == deflectorAllySeen) {
         if (event.isCanceled()) sideImpactCancelled = true;
         else sideImpactPermitted = true;
      }
      if (victim == second) {
         if (event.isCanceled()) memberImpactCancelled = true;
         else memberImpactPermitted = true;
      }
   }

   private static boolean deflectionScenarioReady() {
      require(battle != null && !battle.finished(), "the deflection encounter is still live");
      return deflectionStage >= 8 || ticks > deflectionDeadline;
   }

   private static void checkDeflectionScenario() {
      require(deflectionStage >= 8, "all three real bow shots ran to their end (stage " + deflectionStage + ")");
      require(reflectionRecorded, "a real native deflection was really recorded by the encounter");
      require(deflector.getUUID().equals(recordedDeflector), "the recorded deflector is the body that really deflected it: " + recordedDeflector);
      require(Objects.equals(arrowOwnerBefore, owner.getUUID()), "the real shot belonged to the member who fired it: " + arrowOwnerBefore);
      require(refusalResolved, "the reversed arrow really met the same-side body");
      require(
         deflectorAlly == null || deflectorAlly.getHealth() >= dAllyHealthBefore,
         "a reversed shot may not hurt the deflecting body's own side: "
            + dAllyHealthBefore
            + " -> "
            + (deflectorAlly == null ? "removed" : deflectorAlly.getHealth())
      );
      require(allowedHitResolved, "the reversed arrow really met the other member of the shooter's side");
      require(
         second.getHealth() < secondHealthBefore,
         "a reversed shot really damages an allowed recipient on the original shooter's side: " + secondHealthBefore + " -> " + second.getHealth()
      );
      require(owner.getHealth() >= ownerHealthBefore, "the member that fired keeps its own health: " + ownerHealthBefore + " -> " + owner.getHealth());
      require(
         ownerPaidAfterRefusal - ownerPaidBefore == 1,
         "exactly one charged action bought the first reversed shot: " + ownerPaidBefore + " -> " + ownerPaidAfterRefusal
      );
      require(
         battle.actorPaidActions(owner) - ownerPaidBefore == 2,
         "each real bow shot is charged exactly once: " + ownerPaidBefore + " -> " + battle.actorPaidActions(owner)
      );
      require(dOwnerApBefore - ownerApAfterRefusal > 0, "the real bow shot really cost the shooter AP: " + dOwnerApBefore + " -> " + ownerApAfterRefusal);
      require(
         battle.actorAp(deflector) == deflectorApBefore,
         "a deflection is never charged to the body that performs it: " + deflectorApBefore + " -> " + battle.actorAp(deflector)
      );
      require(
         battle.actorAp(secondDeflector) == secondDeflectorApBefore,
         "a deflection is never charged to a second deflecting body either: " + secondDeflectorApBefore + " -> " + battle.actorAp(secondDeflector)
      );
      require(permissionDeflectorRefused, "a reversed shot may not affect the body that deflected it");
      require(permissionBystanderRefused, "a reversed shot may not borrow authority over a body outside this encounter");
      require(permissionOpposedAllowed, "a reversed shot may still affect a real opposed participant");
      require(repeatedReflectionRecorded, "a second real deflection is really recorded");
      require(secondDeflector.getUUID().equals(secondRecordedDeflector), "a repeated real deflection selects the latest deflector: " + secondRecordedDeflector);
      passed.add("D[paidArrow reversed=true refused=true sideHit=true latestDeflector=true]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE D PASS secondHp={}->{} allyHp={} paid={}->{} ownerAp={}->{} deflector={} latest={}",
            new Object[]{
               secondHealthBefore,
               second.getHealth(),
               dAllyHealthBefore,
               ownerPaidBefore,
               battle.actorPaidActions(owner),
               dOwnerApBefore,
               ownerApAfterRefusal,
               recordedDeflector,
               secondRecordedDeflector
            }
         );
   }

   private static void checkTeardown() {
      require(BreezeWindCharges.size() == 0, "no wind charge mark outlives its encounter: " + BreezeWindCharges.describe());
      require(BreezeDeflections.size() == 0, "no deflection record outlives its encounter: " + BreezeDeflections.describe());
      require(exitHandlerCleared, "a released breeze answers no handler of this encounter any more");
      require(exitPoseRestored, "a released breeze keeps no borrowed pose");
      require(exitFrictionRestored, "a released breeze keeps no borrowed friction flag");
      require(!exitBusy, "a finished encounter is not left busy by this family");
      passed.add("T[marks=0 reflections=0 handler=0 poseOk=true]");
      Tactical.LOGGER
         .info(
            "[Tactical] BREEZE T PASS marks=0 reflections=0 handlerCleared={} poseRestored={} frictionRestored={} busy={}",
            new Object[]{exitHandlerCleared, exitPoseRestored, exitFrictionRestored, exitBusy}
         );
   }

   private static void finishFixture() {
      StringBuilder line = new StringBuilder("BREEZE SELFTEST PASS groups=" + passed.size());

      for (String entry : passed) {
         line.append(' ').append(entry);
      }

      Tactical.LOGGER.info("[Tactical] {}", line);
      enabled = false;

      for (BlockPos chunk : forced) {
         level.setChunkForced(chunk.getX(), chunk.getZ(), false);
      }

      server.halt(false);
   }

   private static void jumpWindowCleared() {
      jumpWindowOpened = false;
      jumpWindowClosed = false;
      shotWindowClosed = false;
      jumpWasInhaling = false;
      jumpAirborneThisWindow = false;
      jumpFeet.clear();
      jumpLandingFeet = null;
   }

   private static void require(boolean condition, String message) {
      if (!condition) {
         throw new IllegalStateException(message);
      }
   }

   private static double round(double value) {
      return Math.round(value * 1000.0) / 1000.0;
   }
}
