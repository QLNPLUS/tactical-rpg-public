package com.tactical.neoforge.battle;

import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Real loaded-server fixture for the shulker ruleset: {@code -Dtactical.selftest=shulker}.
 *
 * <p>Every group below answers one finite question from the repair list, and answers it from the authoritative
 * server state - the real entity, the real AP balance, the real turn track - never from a client mirror and never
 * from a geometric hit test standing in for an action.</p>
 *
 * <ul>
 *   <li><b>F</b> the foundation: a real floor, wall and ceiling attachment, a closed shell refusing an arrow, and
 *       vanilla's own relocation honouring a vetoing listener;</li>
 *   <li><b>A1</b> the levitation translation: two successive own turns rise once each while the effect lasts, a
 *       plain player is lifted by the same rule, another actor's turn does not drift the body, and the disappearance
 *       of the effect hands a real position to the descent rule;</li>
 *   <li><b>A2</b> the shell's own push: an ordinary body of the pushing shell's own side, standing inside the
 *       volume its own opening really sweeps, is never displaced, and the immovable shell itself never moves;
 *       the sweep's own reach is proved by the C1 group, whose opposed body at the same geometry really moves;</li>
 *   <li><b>A3</b> the landing gate: a re-pointed destination that is out of the battlefield is refused with no
 *       movement and no charge, while a legal one lands;</li>
 *   <li><b>A4</b> a real native replication: a real same-family bullet hit on an open shell produces a child the
 *       world really registered, which joins its parent's side with fresh resources and its own turn slot;</li>
 *   <li><b>A5</b> the per-kind quotas: one relocation followed by exactly one real shot in the same own turn, at the
 *       two prices, and no second maneuver;</li>
 *   <li><b>A6</b> the shell window: open after a shot, still open after the opponent's opportunity, closed at the
 *       unit's own next turn.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ShulkerSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer second;
    private static ServerBattle battle;
    private static int wait;
    private static boolean enemyPhaseSeen;
    private static int driveStage = -1;
    private static int memberRounds;
    private static int lastStage = -1;

    // ---- F: attachment, shell and the native relocation ------------------------------------------------------
    private static Shulker floorShell;
    private static Shulker wallShell;
    private static Shulker ceilingShell;
    private static Shulker floatingShell;
    private static boolean floorLegal;
    private static boolean wallLegal;
    private static boolean ceilingLegal;
    private static boolean floatingRefused;
    private static float closedArrowDamage = -1.0F;
    private static boolean closedArrowRefused;
    private static float openArrowDamage = -1.0F;
    private static int teleportEvents;
    private static boolean vetoRelocation;
    private static boolean vetoRefused;
    private static boolean vetoKeptPosition;
    /** When set, the fixture re-points every native relocation to this point. */
    private static Vec3 repointTo;
    private static boolean nativeRelocated;
    private static boolean quantised;

    // ---- the fighting bodies ----------------------------------------------------------------------------------
    private static Shulker shell;
    /** The protected body of the pushing shell's own side: an ordinary body, never a shell. */
    private static net.minecraft.world.entity.monster.Zombie friendly;
    private static int bulletsSeen;

    // ---- A1: the levitation translation ----------------------------------------------------------------------
    private static double riseBase = -1.0D;
    private static double riseFirst = -1.0D;
    private static double riseSecond = -1.0D;
    private static double afterOtherTurn = -1.0D;
    private static boolean noDriftOnOtherTurn;
    private static double memberFeetBefore = -1.0D;
    private static double memberFeetAfter = -1.0D;
    private static int memberApBefore = -1;
    private static int memberApAfter = -1;
    private static boolean held;
    private static boolean noAiKept;
    private static double expiredFeet = -1.0D;
    private static boolean fallStarted;
    private static boolean fallSeen;
    private static int fallWaits;

    // ---- A2: the shell's own push ----------------------------------------------------------------------------
    /** The wall shell whose own attack opens the window this group measures. */
    private static Shulker pushShell;
    private static boolean friendlySameSide;
    private static boolean friendlyInSweep;
    private static String friendlySide = "none";
    private static int pushPeek = -1;
    private static Vec3 friendlyAt;
    private static Vec3 friendlyAtAfter;
    private static boolean friendlyKept;
    private static int shellBlockXBefore;
    private static int shellBlockXAfter;
    private static boolean shellImmovable;
    private static double ownerPushBefore = -1.0D;
    private static double ownerPushAfter = -1.0D;
    private static boolean ownerPushed;
    private static Vec3 ownerCellBefore;
    private static GridPos ownerCellAfter;

    // ---- A3/A5: relocation, its gate and the follow-up shot --------------------------------------------------
    private static int relocApBefore = -1;
    private static int relocApAfter = -1;
    private static int relocPaidBefore = -1;
    private static int relocPaidAfter = -1;
    private static Vec3 relocBefore;
    private static Vec3 relocAfter;
    private static int bulletsBeforeFollowUp;
    private static int bulletsAfterFollowUp;
    private static int followUpApBefore = -1;
    private static int followUpApAfter = -1;
    private static boolean secondManeuverRefused;
    private static int refusedApBefore = -1;
    private static int refusedApAfter = -1;
    private static Vec3 refusedBefore;
    private static Vec3 refusedAfter;
    private static boolean refusedKeptPosition;
    private static boolean refusedFree;
    private static boolean refusedEventSeen;
    private static int refusedPaidAfter = -1;
    private static boolean refusalSkipped;
    private static boolean refusalParked;
    private static int refusalAttempts;
    private static int refusedEventsBefore = -1;
    private static int followUpEventsBefore = -1;
    private static boolean turnTimedOut;
    private static boolean followUpSkipped;
    private static int followUpAttempts;
    private static boolean followUpParked;
    private static int followUpTurnsUsed;
    private static final int FOLLOW_UP_MAX_TURNS = 14;

    // ---- A4: the native replication --------------------------------------------------------------------------
    private static int childCountBefore;
    /** How many of the native ±8 relocation candidates this arena really offers, measured with vanilla's predicates. */
    private static int replicationCandidates = -1;
    private static Shulker nativeChild;
    private static boolean childRegistered;
    private static boolean childRostered;
    private static boolean childHasSlot;
    private static int childAp = -1;
    private static boolean childSameSide;
    private static boolean childFreshActions;
    private static boolean childVariantKept;
    private static boolean childNativeFlags;
    private static boolean replicationSkipped;
    private static int replicationAttempts;
    private static String replicationSkippedBecause = "none";
    private static int peekAtReplication = -1;
    private static float shellHealthAtReplication = -1.0F;
    private static Vec3 bulletAt;
    private static float shellHealthBeforeHit = -1.0F;
    private static boolean bulletReallyHit;
    private static float hitDamage = -1.0F;
    private static boolean nativeLevitation;

    // ---- B1: a rostered wall and ceiling shell --------------------------------------------------------------
    private static BlockPos wallSampleBlock;
    private static BlockPos ceilingSampleBlock;
    private static boolean rosteredWallAdmitted;
    private static boolean rosteredCeilingAdmitted;
    private static boolean wallPoseKept;
    private static boolean ceilingPoseKept;

    // ---- B2: a post-handler fault rollback -------------------------------------------------------------------
    private static Shulker rollbackShell;
    private static Direction rollbackFaceBefore;
    private static int rollbackPeekBefore = -1;
    private static Vec3 rollbackAtBefore;
    private static boolean rollbackCoveredBefore;
    private static boolean rollbackAbsorbedBefore;
    private static boolean rollbackRefused;
    private static boolean rollbackFaceKept;
    private static boolean rollbackPeekKept;
    private static boolean rollbackAtKept;
    private static boolean rollbackHandlerCleared;
    private static boolean rollbackNoClaim;
    private static boolean rollbackCoveredKept;

    // ---- A6: the shell window ---------------------------------------------------------------------------------
    private static int peekAfterShot = -1;
    private static int peekAfterEnemyTurn = -1;
    private static boolean windowSurvived;
    private static int peekAfterOwnTurn = -1;
    private static boolean windowClosed;

    // ---- C1: a real accepted opening push ---------------------------------------------------------------
    private static Shulker wallProof;
    private static net.minecraft.world.entity.monster.Zombie pushBody;
    private static Vec3 pushAt;
    private static Vec3 pushAfter;
    private static Vec3 pushShellAt;
    private static net.minecraft.world.entity.monster.Zombie bystander;
    private static Vec3 pushBystanderAt;
    private static GridPos pushCellBefore;
    private static GridPos pushCellAfter;
    private static boolean pushMoved;
    private static boolean pushCellFollowed;
    private static boolean pushRetained;
    private static boolean pushShellImmovable;
    private static boolean pushBystanderKept;

    // ---- C2: marked shot ownership on exit --------------------------------------------------------------
    private static BlockPos markedWall;
    private static ShulkerBullet markedBullet;
    private static int markedBulletsBefore = -1;
    private static int markedEventsBefore = -1;
    private static int blockImpactCancels;
    private static int markedWaits;
    private static int markedAttempts;
    private static boolean markedImpactCancelled;
    private static boolean markedSecondJoined;
    private static boolean markedTargetGone;

    // ---- teardown ---------------------------------------------------------------------------------------------
    private static boolean handlerCleared;
    private static boolean noAiRestored;
    private static boolean noHold;
    private static boolean noChildPending;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "shulker".equals(System.getProperty("tactical.selftest"));
    }

    /** Counts and re-points the events vanilla's own relocation fires. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleportEvent(EntityTeleportEvent.EnderEntity event) {
        if (!enabled || !(event.getEntity() instanceof Shulker)) return;
        teleportEvents++;
        if (vetoRelocation) {
            event.setCanceled(true);
            return;
        }
        Vec3 target = repointTo;
        if (target != null) {
            event.setTargetX(target.x);
            event.setTargetY(target.y);
            event.setTargetZ(target.z);
        }
    }

    /**
     * Cancel a marked shulker bullet's block impact at the lowest priority.
     *
     * <p>This is a real cancellation of a real native impact - the case that must not turn a marked shot into an
     * unmarked one - not a simulation of one.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectileImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!enabled) return;
        if (!(event.getProjectile() instanceof ShulkerBullet bullet)) return;
        if (!ShulkerBullets.owns(bullet)) return;
        if (event.getRayTraceResult().getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        blockImpactCancels++;
        event.setCanceled(true);
    }

    /** Counts the native projectiles that really joined this level. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!enabled) return;
        if (event.getLevel().isClientSide()) return;
        if (event.getEntity() instanceof ShulkerBullet) bulletsSeen++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        if (stage != lastStage) { lastStage = stage; wait = 0; }
        // Another encounter may take over between stages; every assertion must read the live one.
        ServerBattle live = BattleManager.current();
        if (live != null && live != battle) { battle = live; driveStage = -1; enemyPhaseSeen = false; }
        try {
            require(ticks < 12_000, "shulker fixture completion deadline" + diagnostics());
            switch (stage) {
                case 0 -> { setup(event.getServer()); stage = 1; }
                case 1 -> { checkAttachment(); stage = 2; }
                case 2 -> { checkShellDefence(); stage = 3; }
                case 3 -> { checkNativeRelocation(); stage = 4; }
                case 4 -> { beginEncounter(); stage = 5; }
                case 5 -> { checkAdmission(); stage = 6; }
                // B1 (AB7): a rostered wall shell and a rostered ceiling shell keep the pose they were admitted on,
                // across real ticks of a running encounter - not only as a geometry sample before admission.
                case 6 -> {
                    if (driveEnemyTurn()) return;
                    checkRosteredPoses();
                    // The two pose samples have proved their point. Leaving them in the roster would keep the
                    // encounter busy with their own shots and would steal the turns the later groups need.
                    if (wallShell != null) wallShell.discard();
                    if (ceilingShell != null) ceilingShell.discard();
                    stage = 7;
                }
                // A6: the shell fires, and the opponent's opportunity begins with the shell still open.
                case 7 -> { if (driveEnemyTurn()) return; peekAfterShot = peek(); stage = 8; }
                case 8 -> { peekAfterEnemyTurn = peek(); windowSurvived = peekAfterShot > 0; stage = 9; }
                // A2 runs before the turn-driven groups: the body has to be protected by the shell's own opening,
                // not by anything a relocation or a shot resolution did in between.
                case 9 -> {
                    beginPushScenario();
                    stage = 90;
                }
                // The shell's own attack opens the window; vanilla's own animation then sweeps real ticks, so the
                // group waits for the raw value to move and for the animation to finish before it reads feet.
                case 90 -> {
                    if (pushPeek < 0) {
                        // Another body of this encounter may take the field first, so this group cycles real
                        // player turns until THIS shell really gets its own, instead of using the encounter's
                        // arbitrary first opportunity and then waiting forever for an actor that never acts.
                        if (waitForOwnOpening(pushShell, 420)) return;
                        pushPeek = ShulkerNative.rawPeek(pushShell);
                        wait = 0;   // vanilla's own animation starts on this tick
                    }
                    // Vanilla animates one 0.05 step per tick, so a full open needs 20; 30 leaves margin.
                    if (waitTicks(30)) return;
                    stage = 91;
                }
                case 91 -> {
                    checkPush();
                    // The sample has proved its point: leaving it in the roster would compete for the turns the
                    // later groups need, and it is not what any of them is about.
                    if (pushShell != null) pushShell.discard();
                    if (friendly != null) friendly.discard();
                    // A native bullet of this group may have landed on the member; give the body back exactly the
                    // state the next group expects instead of carrying this group's damage or lift.
                    owner.removeEffect(MobEffects.LEVITATION);
                    owner.setHealth(owner.getMaxHealth());
                    stage = 10;
                }
                // A3: a landing the gate must refuse, and the window that closes with that same own turn.
                // A3: the landing gate must refuse a re-pointed destination that is not this encounter's place.
                // The baseline is taken at this unit's own turn opening - after its recovery and with no paid action
                // yet - so "cost exactly nothing" is a statement about the same turn the attempt happened in.
                case 10 -> {
                    if (!prepareTurn("refusal")) return;
                    stage = 11;
                }
                case 11 -> {
                    if (battle.isActorTurn(shell)) {
                        if (waitTicks(60)) return;
                        turnTimedOut = true;
                    }
                    // The native search is vanilla's own and may draw no candidate its support gate likes in one
                    // own turn. Retrying means a whole new own turn with a fresh baseline, never a re-rolled
                    // candidate inside the same turn.
                    if (teleportEvents == refusedEventsBefore && ++refusalAttempts <= FOLLOW_UP_MAX_TURNS) {
                        // Vanilla's own search drew no candidate its support gate liked in that own turn, so no
                        // event was ever fired. A fresh own turn is a fresh draw; nothing old is carried over.
                        refusalParked = false;
                        refusedApBefore = -1;
                        turnTimedOut = false;
                        stage = 10;
                        return;
                    }
                    checkRefusal();
                    stage = 12;
                }
                // A5: the maneuver this shot was bought for, measured inside the same own turn.
                case 12 -> {
                    if (!prepareTurn("followUp")) return;
                    stage = 13;
                }
                case 13 -> {
                    if (battle.isActorTurn(shell)) {
                        if (waitTicks(90)) return;
                        turnTimedOut = true;
                    }
                    repointTo = null;
                    if (checkFollowUp()) stage = 14;
                    else { followUpParked = false; stage = 12; }
                }
                // A4: a real native replication observed at the world's own receipt. The shell has to be open for
                // vanilla's own replication branch to run at all, and the encounter's own window is what opens it -
                // so the group first lets the shell fire from inside its band.
                case 14 -> { beginReplicationSetup(); stage = 15; }
                case 15 -> { if (driveEnemyTurn()) return; beginReplication(); stage = 16; }
                case 16 -> {
                    if (waitTicks(80)) return;
                    if (checkReplication()) stage = 17; else stage = 15;
                }
                // B2 (AB9): a fault after the handler and the pose were installed must give an originally open wall
                // shell back exactly, with no stale claim, handler, permit or hold.
                case 17 -> { beginRollbackScenario(); stage = 18; }
                case 18 -> { checkRollback(); stage = 19; }
                // A1 last: the levitation translation leaves a body held in mid air, so it must not run in front
                // of the groups that need a settled, acting shell.
                case 19 -> { beginRiseScenario(); stage = 20; }
                case 20 -> { if (driveEnemyTurn()) return; riseFirst = heldOrFeet(shell); stage = 21; }
                case 21 -> { afterOtherTurn = heldOrFeet(shell); stage = 22; }
                case 22 -> { if (driveEnemyTurn()) return; riseSecond = heldOrFeet(shell); checkSecondRise(); stage = 23; }
                case 23 -> { checkHeld(); owner.removeEffect(MobEffects.LEVITATION); stage = 24; }
                // The member's own lift waits until no hold and no descent of that body is still running.
                case 24 -> {
                    if (battle.heldHeight(owner) != null || battle.terrainFallActive()) return;
                    beginMemberRise(); stage = 25;
                }
                case 25 -> {
                    if (battle.terrainFallActive()) return;
                    if (battle.heldHeight(owner) != null || memberRounds >= 8) {
                        checkMemberRise(); beginExpiry(); stage = 26; return;
                    }
                    if (!owner.hasEffect(MobEffects.LEVITATION)) {
                        owner.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 2400, 0));
                    }
                    memberRounds++;
                    if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
                }
                case 26 -> {
                    // The descent owns that body on the first tick that really has no support, and the exact tick
                    // depends on where the encounter's own tick sits in the server tick, so the group waits for the
                    // fact instead of for a fixed count.
                    fallSeen = fallSeen || battle.terrainFallActive() || feetOf(shell) < expiredFeet - 0.4D;
                    if (!fallSeen && fallWaits++ < 80) return;
                    checkFall(); stage = 27;
                }
                // C1: one real native opening push on a real opposed participant.
                case 27 -> { beginPushProof(); stage = 28; }
                // The pushing shell's own turn is what opens it; vanilla then animates the peek and pushes. The
                // watched body is not necessarily the encounter's first actor, so the turns are cycled until THIS
                // shell really opens rather than handed over once to whoever the encounter picks.
                case 28 -> {
                    if (waitForOwnOpening(wallProof, 420)) return;
                    stage = 29;
                }
                case 29 -> {
                    if (waitTicks(24)) return;
                    checkPushProof();
                    // Printed at its own group, so its receipt survives whatever a later group does.
                    Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS C1[moved={} at={} to {} cell={} to {} "
                                    + "retained={} shellImmovable={} bystanderKept={}]",
                            pushMoved, pushAt, pushAfter, pushCellBefore, pushCellAfter, pushRetained,
                            pushShellImmovable, pushBystanderKept);
                    stage = 30;
                }
                // C2: a marked shot's ownership under a cancelled block impact and a departing target.
                case 30 -> { beginMarkedExit(); stage = 31; }
                case 31 -> {
                    if (markedBullet == null) {
                        markedBullet = firstOwnedBullet();
                        if (markedBullet == null) {
                            if (markedWaits++ < 60) return;
                            markedAttempts++;
                            require(markedAttempts <= 6, "the encounter never produced a marked shot in "
                                    + markedAttempts + " own turns, marks=" + ShulkerBullets.size());
                            markedWaits = 0;
                            beginMarkedExit();
                            return;
                        }
                    }
                    // The projectile is alive and owned right now; wait for its real block impact to be cancelled.
                    markedImpactCancelled = blockImpactCancels > markedEventsBefore;
                    if (!markedImpactCancelled && markedWaits++ < 60) return;
                    checkMarkedExit();
                    stage = 32;
                }
                case 32 -> { checkTeardown(); complete(event.getServer()); }
                default -> { }
            }
        } catch (AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SHULKER SELFTEST FAILED at stage {}: {}", stage, failure.getMessage());
            fail(event.getServer());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] SHULKER SELFTEST CRASHED at stage {}", stage, failure);
            fail(event.getServer());
        }
    }

    // ------------------------------------------------------------------ setup

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        // The fixture arena is its own world: no world mob may spawn onto it and turn a group's turn budget into a
        // fight. This is the fixture's own game rule, not a product behaviour change.
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        origin = new BlockPos(level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getY() + 40,
                level.getSharedSpawnPos().getZ());
        // A flat stone platform with a wall pillar and a ceiling slab: three real attachment surfaces.
        // Deliberately wide: the native relocation search walks +/-8 around the body, so a body parked out of the
        // firing band still has to stand in the middle of supported ground for its own candidates to be real.
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                set(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            }
        }
        set(origin.offset(2, 0, 0), Blocks.STONE.defaultBlockState());
        set(origin.offset(2, 1, 0), Blocks.STONE.defaultBlockState());
        set(origin.offset(-2, 3, 0), Blocks.STONE.defaultBlockState());
        owner = player(server, "tactical-shulker-owner", "ShulkerOwner");
        second = player(server, "tactical-shulker-second", "ShulkerSecond");
        second.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 8.5D);
        clearWorkingBox();
        floorShell = spawnShell(0.5D, 0.5D);
        // A wall holds a shulker on the side of a solid block: the body sits beside it, glued to that face.
        wallShell = spawnShell(3.5D, 0.5D);
        wallShell.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D);
        ceilingShell = spawnShell(-1.5D, 0.5D);
        ceilingShell.setPos(origin.getX() - 1.5D, origin.getY() + 2.0D, origin.getZ() + 0.5D);
        floatingShell = spawnShell(5.5D, 5.5D);
        floatingShell.setPos(origin.getX() + 5.5D, origin.getY() + 6.0D, origin.getZ() + 5.5D);
    }

    /** One shell glued to the side of the stone pillar built for it, facing away from it. */
    private static Shulker wallSample() {
        Shulker mob = (Shulker) EntityType.SHULKER.create(level);
        require(mob != null, "a shulker exists in this version");
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        // Beside the pillar, glued to its west face, so its shell opens east into open air.
        mob.setPos(wallSampleBlock.getX() + 1.5D, wallSampleBlock.getY(), wallSampleBlock.getZ() + 0.5D);
        ShulkerNative.setAttachFace(mob, Direction.WEST);
        require(level.addFreshEntity(mob), "the wall sample really joined the world");
        require(ShulkerAttachment.legal(level, mob, ShulkerAttachment.of(mob), null),
                "the wall sample really holds a legal wall pose before admission");
        return mob;
    }

    /** One shell hanging under the stone slab built for it. */
    private static Shulker ceilingSample() {
        Shulker mob = (Shulker) EntityType.SHULKER.create(level);
        require(mob != null, "a shulker exists in this version");
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        mob.setPos(ceilingSampleBlock.getX() + 0.5D, ceilingSampleBlock.getY() - 1.0D,
                ceilingSampleBlock.getZ() + 0.5D);
        ShulkerNative.setAttachFace(mob, Direction.UP);
        require(level.addFreshEntity(mob), "the ceiling sample really joined the world");
        require(ShulkerAttachment.legal(level, mob, ShulkerAttachment.of(mob), null),
                "the ceiling sample really holds a legal ceiling pose before admission");
        return mob;
    }

    private static Shulker spawnShell(double dx, double dz) {
        Shulker mob = (Shulker) EntityType.SHULKER.create(level);
        require(mob != null, "a shulker exists in this version");
        mob.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        require(level.addFreshEntity(mob), "the shulker really joined the world");
        return mob;
    }

    private static void clearWorkingBox() {
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(40))) {
            if (!(leftover instanceof ServerPlayer)) leftover.discard();
        }
    }

    private static void set(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
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

    // ------------------------------------------------------------------ F

    private static void checkAttachment() {
        ShulkerAttachment floor = ShulkerAttachment.of(floorShell);
        ShulkerAttachment wall = ShulkerAttachment.of(wallShell);
        ShulkerAttachment ceiling = ShulkerAttachment.of(ceilingShell);
        ShulkerAttachment floating = ShulkerAttachment.of(floatingShell);
        floorLegal = ShulkerAttachment.legal(level, floorShell, floor, null);
        wallLegal = ShulkerAttachment.legal(level, wallShell, wall, null);
        ceilingLegal = ShulkerAttachment.legal(level, ceilingShell, ceiling, null);
        floatingRefused = !ShulkerAttachment.legal(level, floatingShell, floating, null);
        require(floorLegal, "a floor attachment is legal");
        require(wallLegal, "a wall attachment is legal");
        require(ceilingLegal, "a ceiling attachment is legal");
        require(floatingRefused, "an unsupported body is not a legal attachment");
    }

    private static void checkShellDefence() {
        // Closed: vanilla answers an arrow with a refusal, before armour is even consulted.
        ShulkerNative.setRawPeek(floorShell, MotionRules.SHULKER_PEEK_CLOSED);
        Arrow closedArrow = new Arrow(level, floorShell, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        closedArrowRefused = !floorShell.hurt(level.damageSources().arrow(closedArrow, owner), 4.0F);
        closedArrowDamage = floorShell.getHealth();
        floorShell.setHealth(200.0F);
        require(closedArrowRefused, "a closed shell refuses an arrow");
        // Open: the same hit really lands.
        ShulkerNative.setRawPeek(floorShell, MotionRules.SHULKER_PEEK_OPEN);
        Arrow openArrow = new Arrow(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        floorShell.hurt(level.damageSources().arrow(openArrow, owner), 4.0F);
        openArrowDamage = floorShell.getHealth();
        floorShell.setHealth(200.0F);
        ShulkerNative.setRawPeek(floorShell, MotionRules.SHULKER_PEEK_CLOSED);
        require(openArrowDamage < 200.0F, "an open shell takes a real arrow hit");
    }

    private static void checkNativeRelocation() {
        // A vetoing listener must stop vanilla's own entry from moving the body at all.
        Vec3 before = floorShell.position();
        vetoRelocation = true;
        repointTo = null;
        teleportEvents = 0;
        boolean vetoReturned = false;
        for (int attempt = 0; attempt < 80 && teleportEvents == 0; attempt++) {
            vetoReturned = ShulkerNative.teleportSomewhere(floorShell);
        }
        vetoRelocation = false;
        vetoRefused = !vetoReturned;
        vetoKeptPosition = floorShell.position().distanceToSqr(before) < 0.25D;
        require(teleportEvents > 0, "the native relocation really fired the event");
        require(vetoRefused, "a vetoed relocation returns false");
        require(vetoKeptPosition, "a vetoed relocation does not move the body");
        // Without a veto the same entry really relocates onto the point its event finally names.
        repointTo = new Vec3(origin.getX() + 6.5D, origin.getY(), origin.getZ() + 6.5D);
        nativeRelocated = false;
        for (int attempt = 0; attempt < 80 && !nativeRelocated; attempt++) {
            nativeRelocated = ShulkerNative.teleportSomewhere(floorShell);
        }
        repointTo = null;
        Vec3 after = floorShell.position();
        quantised = Math.abs(after.x - (Math.floor(after.x) + 0.5D)) < 1.0E-6D
                && Math.abs(after.z - (Math.floor(after.z) + 0.5D)) < 1.0E-6D
                && Math.abs(after.y - Math.floor(after.y + 1.0E-7D)) < 1.0E-6D;
        require(nativeRelocated, "the native relocation really moved the body");
        require(quantised, "a relocation lands on a quantised block centre");
    }

    // ------------------------------------------------------------------ encounter

    private static void beginEncounter() {
        // The F samples have proved their point and leave the world entirely: any shell left standing here would
        // compete for the roster cap and for turns the later groups need.
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(origin).inflate(48))) {
            if (!(leftover instanceof ServerPlayer)) leftover.discard();
        }
        // Two of the fighting bodies are taken on a wall and under a slab on purpose: the admission path has to
        // keep those poses, not re-place them on a floor row.
        wallSampleBlock = origin.offset(4, 0, 0);
        set(wallSampleBlock, Blocks.STONE.defaultBlockState());
        set(wallSampleBlock.above(), Blocks.STONE.defaultBlockState());
        ceilingSampleBlock = origin.offset(-4, 3, 0);
        set(ceilingSampleBlock, Blocks.STONE.defaultBlockState());
        wallShell = wallSample();
        ceilingShell = ceilingSample();
        shell = spawnShell(0.5D, 0.5D);
        bulletsSeen = 0;
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 5.5D);
        owner.setHealth(owner.getMaxHealth());
        BattleManager.reset();
        require(BattleManager.start(owner, true), "the shulker encounter starts");
        battle = BattleManager.current();
        require(battle != null, "the encounter exists");
    }

    private static void checkAdmission() {
        require(battle.isParticipant(shell), "the shulker is a real participant" + diagnostics());
        require(shell.isNoAi(), "the encounter holds the shulker's own AI");
        require(TacticalShulkerAccess.class.isInstance(shell)
                        && ((TacticalShulkerAccess) shell).tactical$handler() != null,
                "the admitted shulker answers one encounter handler");
        require(battle.isPlayerTurnNow(), "the high-initiative owner acts first" + diagnostics());
    }

    // ------------------------------------------------------------------ A2: the shell's own push

    /**
     * Put one ordinary body of the pushing shell's OWN side where its opening really sweeps, then let the shell's
     * own attack open the window.
     *
     * <p>Two facts were wrong in the earlier shape of this group and made it vacuous rather than false-negative:
     * the sample was itself a shulker - which vanilla's own opening never pushes, whatever the encounter decides -
     * and it was admitted through the friendly transaction, which is the PLAYER side while the pushing shell is a
     * HOSTILE participant, so the two were actually opposed. The baseline was also taken before admission, while
     * admission may legitimately place a body. This group therefore uses the wall sample (a floor shell opens
     * upward through its own column, where no second standing body may ever be), an ordinary hostile body taken
     * through the hostile transaction so that it really shares the shell's side, the cell vanilla's own opening
     * really sweeps, and the feet that body holds after that admission.</p>
     */
    private static void beginPushScenario() {
        pushShell = wallSample();
        require(battle.tryAbsorb(pushShell), "the pushing wall shell is really admitted" + diagnostics());
        require(battle.isParticipant(pushShell), "the pushing wall shell is a participant");
        // The protected body stands in the cell vanilla's own opening really sweeps: the strip a wall shell grows
        // through as it opens. That strip is one cell wide, so this group holds only that body.
        friendly = spawnOrdinaryZombie(wallSampleBlock.getX() + 2.5D, wallSampleBlock.getZ() + 0.5D);
        require(battle.tryAbsorb(friendly), "the same-side ordinary body is really admitted" + diagnostics());
        friendlySide = String.valueOf(battle.sideOf(friendly)) + "/" + battle.sideOf(pushShell);
        friendlySameSide = battle.isParticipant(friendly)
                && battle.sideOf(friendly) == battle.sideOf(pushShell)
                && battle.allied(pushShell, friendly) && !battle.opposed(pushShell, friendly);
        require(friendlySameSide, "the protected body really shares the pushing shell's side, sides="
                + friendlySide + diagnostics());
        ShulkerAttachment node = ShulkerAttachment.of(pushShell);
        AABB swept = Shulker.getProgressDeltaAabb(pushShell.getScale(), node.opening(), 1.0F, 0.0F)
                .move(pushShell.getX() - 0.5D, pushShell.getY(), pushShell.getZ() - 0.5D);
        friendlyInSweep = swept.intersects(friendly.getBoundingBox());
        require(friendlyInSweep, "the protected body really stands inside the volume vanilla's opening sweeps, "
                + "swept=" + swept + " body=" + friendly.getBoundingBox() + " face=" + node.face());
        // Baseline AFTER admission: admission may legally place the body, and only what it really holds counts.
        friendlyAt = friendly.position();
        shellBlockXBefore = pushShell.getBlockX();
        pushPeek = -1;
        require(pushShell.isAlive(), "the pushing shell is still alive before its own opening");
        require(friendly.isAlive(), "the protected body is alive before the shell opens");
    }

    private static void checkPush() {
        friendlyAtAfter = friendly.isAlive() ? friendly.position() : null;
        friendlyKept = friendly.isAlive() && friendlyAtAfter != null
                && friendlyAtAfter.distanceToSqr(friendlyAt) < 1.0E-4D;
        require(friendly.isAlive(), "the protected body is still alive after the shell opened");
        require(friendlyKept, "an opening shell never displaces a body of its own side, "
                + friendlyAt + " to " + friendlyAtAfter + diagnostics());
        shellBlockXAfter = pushShell.getBlockX();
        shellImmovable = shellBlockXAfter == shellBlockXBefore;
        require(shellImmovable, "the native shell itself is never pushed, x " + shellBlockXBefore + " to "
                + shellBlockXAfter + diagnostics());
        require(pushPeek > MotionRules.SHULKER_PEEK_CLOSED,
                "the window this group measured was really opened by the shell's own attack, seen=" + pushPeek);
    }

    // ------------------------------------------------------------------ A1: the levitation translation

    private static void beginRiseScenario() {
        if (friendly != null && !friendly.isRemoved()) friendly.discard();
        // The native child has proved its point too, and it stands on the very cell its parent returned to: two
        // bodies cannot hold one cell, so leaving it would refuse every later move of the parent for the wrong
        // reason.
        for (Shulker other : level.getEntitiesOfClass(Shulker.class, new AABB(origin).inflate(48))) {
            if (other != shell) other.discard();
        }
        // The member's own balance is read before the lift so the group can prove the lift costs nothing.
        memberApBefore = battle.actorAp(owner);
        shell.removeEffect(MobEffects.LEVITATION);
        shell.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 2400, 0));
        require(shell.hasEffect(MobEffects.LEVITATION), "the native levitation effect really landed on the body");
        riseBase = heldOrFeet(shell);
        riseFirst = -1.0D;
        riseSecond = -1.0D;
        afterOtherTurn = -1.0D;
    }

    /** The height the encounter is holding that body at, or its real feet when it holds nothing. */
    private static double heldOrFeet(Entity body) {
        java.lang.Double held = battle.heldHeight((net.minecraft.world.entity.LivingEntity) body);
        return held == null ? feetOf(body) : held;
    }

    private static void checkSecondRise() {
        require(riseFirst > riseBase + 0.9D,
                "the first own turn of a floating body really rose one block, " + riseBase + " to " + riseFirst);
        require(riseSecond > riseFirst + 0.9D,
                "a second own turn rises again from where the hold really is, " + riseFirst + " to " + riseSecond);
        // The height measured while another actor's turn was current must be the height the first rise left.
        noDriftOnOtherTurn = afterOtherTurn > 0.0D && Math.abs(afterOtherTurn - riseFirst) < 0.2D;
        require(noDriftOnOtherTurn, "another actor's turn does not drift or lower a held body, "
                + riseFirst + " versus " + afterOtherTurn);
        require(shell.isNoAi(), "the rise never turns the unit's AI back on");
    }

    private static void checkHeld() {
        held = shell.getDeltaMovement().lengthSqr() < 1.0E-6D;
        noAiKept = shell.isNoAi();
        require(held, "the body is held still, y=" + shell.getY());
        require(noAiKept, "the borrowed NoAI flag survives the hold");
    }

    /** A plain player member is lifted by exactly the same rule. */
    private static void beginMemberRise() {
        memberApBefore = battle.actorAp(owner);
        memberFeetBefore = heldOrFeet(owner);
        owner.removeEffect(MobEffects.LEVITATION);
        owner.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 2400, 0));
        require(owner.hasEffect(MobEffects.LEVITATION), "the native effect landed on the member as well");
    }

    private static void checkMemberRise() {
        memberApAfter = battle.actorAp(owner);
        memberFeetAfter = heldOrFeet(owner);
        java.lang.Double heldMember = battle.heldHeight(owner);
        require(heldMember != null || memberFeetAfter > memberFeetBefore + 0.9D,
                "a floating member is lifted by the same rule at their own turn start, "
                        + memberFeetBefore + " to " + memberFeetAfter + " held=" + heldMember);
        require(memberApAfter >= memberApBefore,
                "being lifted never debits the lifted member, " + memberApBefore + " to " + memberApAfter);
        owner.removeEffect(MobEffects.LEVITATION);
    }

    private static void beginExpiry() {
        riseSecond = Math.max(riseSecond, heldOrFeet(shell));
        expiredFeet = feetOf(shell);
        shell.removeEffect(MobEffects.LEVITATION);
        require(!shell.hasEffect(MobEffects.LEVITATION), "the effect is really gone");
    }

    private static void checkFall() {
        require(battle.heldHeight(shell) == null, "the hold really ends with its native cause");
        fallStarted = fallSeen || battle.terrainFallActive() || feetOf(shell) < expiredFeet - 0.4D;
        require(fallStarted, "an expired effect hands the body to the descent rule, y=" + feetOf(shell));
    }

    /**
     * Park the body for one measured own turn, and take the baseline at that turn's real opening.
     *
     * <p>Two things are deliberately not done here: the baseline is never read from a preceding turn's balance - a
     * turn opening adds recovery and resets the paid-action counter, so a stale baseline makes every "cost nothing"
     * or "cost exactly two prices" claim meaningless - and nothing is re-rolled inside a turn. The group therefore
     * waits for this unit's own turn to be current with a clean paid-action counter, and only then records the
     * balance, the event counter and the projectile counter it will be compared against.</p>
     *
     * @return true once the turn is prepared and its baseline is recorded
     */
    private static boolean prepareTurn(String what) {
        shell.removeEffect(MobEffects.LEVITATION);
        if (battle.heldHeight(shell) != null) return false;
        turnTimedOut = false;
        boolean followUp = "followUp".equals(what);
        if (followUp ? !followUpParked : !refusalParked) {
            moveShellOutOfBand();
            if (followUp) {
                beginFollowUpScenario();
                followUpParked = true;
            } else {
                beginRefusalScenario();
                refusalParked = true;
            }
        }
        if (battle.isPlayerTurnNow()) {
            battle.endPlayerTurn();
            return false;
        }
        if (!battle.isActorTurn(shell)) return false;
        if (battle.actorPaidActions(shell) != 0) return false;
        // This unit's own turn is really open: recovery is in, the paid-action counter is clean.
        if (followUp) {
            if (relocApBefore >= 0) return true;
            relocApBefore = battle.actorAp(shell);
            relocPaidBefore = battle.actorPaidActions(shell);
            relocBefore = shell.position();
            bulletsBeforeFollowUp = bulletsSeen;
            followUpEventsBefore = teleportEvents;
            return true;
        }
        if (refusedApBefore >= 0) return true;
        refusedApBefore = battle.actorAp(shell);
        refusedEventsBefore = teleportEvents;
        refusedBefore = shell.position();
        return true;
    }

    // ------------------------------------------------------------------ A3: a landing the gate must refuse

    /**
     * Put the shell far enough from the opponent that no shot is legal, and re-point its next relocation outside
     * the battlefield.
     *
     * <p>The opponent is deliberately not moved: a member of this encounter that is teleported around by a test
     * loses its connection state, and the encounter would then legitimately end through "all players left". So the
     * geometry is changed on the shell's side instead - it stands on the platform, inside the battlefield, but out
     * of vanilla's own twenty-block firing band.</p>
     *
     * <p>The event still offers a perfectly legal-looking point for the world - a supported block centre - but it is
     * not this encounter's place. The gate is the only thing that can refuse it, and a refusal must move nothing and
     * cost nothing.</p>
     */
    private static void beginRefusalScenario() {
        // Far outside the battlefield, on a surface that is otherwise perfectly legal.
        repointTo = new Vec3(origin.getX() - 22.5D, origin.getY(), origin.getZ() + 32.5D);
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
    }

    /** Park the shell on the platform at the far edge: legal ground, well beyond the native firing band. */
    private static void moveShellOutOfBand() {
        shell.setPos(origin.getX() - 22 + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        shell.setDeltaMovement(Vec3.ZERO);
        shell.fallDistance = 0.0F;
    }

    private static void checkRefusal() {
        refusedAfter = shell.position();
        refusedApAfter = battle.actorAp(shell);
        refusedPaidAfter = battle.actorPaidActions(shell);
        repointTo = null;
        peekAfterOwnTurn = peek();
        windowClosed = peekAfterOwnTurn == MotionRules.SHULKER_PEEK_CLOSED;
        require(!turnTimedOut, "this unit's own turn really ended inside its budget" + diagnostics());
        require(battle.isParticipant(shell), "the shell is still a participant of this encounter");
        // A real veto means the native entry really reached its cancelable event and this encounter refused the
        // point it finally named - not that the unit happened to fire instead.
        refusedEventSeen = teleportEvents > refusedEventsBefore;
        require(refusedEventSeen, "the native relocation really fired the event and this encounter vetoed it, "
                + refusedEventsBefore + " to " + teleportEvents);
        refusedKeptPosition = refusedAfter.distanceToSqr(refusedBefore) < 0.25D;
        require(refusedKeptPosition, "a landing outside the battlefield moves nothing, "
                + refusedBefore + " to " + refusedAfter);
        refusedFree = refusedPaidAfter == 0 && refusedApAfter == refusedApBefore;
        require(refusedFree, "a refused landing costs exactly nothing in the same own turn, ap "
                + refusedApBefore + " to " + refusedApAfter + " paid=" + refusedPaidAfter);
    }

    // ------------------------------------------------------------------ A5: relocation then one shot

    /**
     * Buy a relocation that really lands inside the battlefield, then the one shot it was bought for.
     *
     * <p>The body stands beyond the native firing band, so no shot is legal where it holds. A legal landing point
     * inside the band is offered by the event listener, so the relocation has a deterministic place to land; what is
     * being proved is the pair of prices and the two per-kind quotas, not the search.</p>
     */
    private static void beginFollowUpScenario() {
        // A legal node on the platform, inside the firing band of the opponent who never moved.
        repointTo = new Vec3(origin.getX() - 9.5D, origin.getY(), origin.getZ() + 0.5D);
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
    }

    private static boolean checkFollowUp() {
        relocAfter = shell.position();
        relocPaidAfter = battle.actorPaidActions(shell);
        relocApAfter = battle.actorAp(shell);
        bulletsAfterFollowUp = bulletsSeen;
        int teleportPrice = ApCalculator.paidActionCost(MotionRules.SHULKER_TELEPORT_COST, 0);
        int shotPrice = ApCalculator.paidActionCost(MotionRules.SHULKER_BULLET_COST, 1);
        require(!turnTimedOut, "this unit's own turn really ended inside its budget" + diagnostics());
        require(battle.isParticipant(shell), "the shell is still a participant of this encounter");
        boolean moved = relocAfter.distanceToSqr(relocBefore) >= 0.25D;
        if (!moved) {
            // The native search is vanilla's own and may legitimately draw no candidate its support gate likes.
            // Retrying means a whole new own turn with a fresh baseline - never a re-rolled candidate inside one.
            followUpAttempts++;
            require(followUpAttempts <= FOLLOW_UP_MAX_TURNS,
                    "no own turn of this unit ever landed its relocation in " + followUpAttempts
                            + " attempts, from " + relocBefore);
            return false;
        }
        require(relocPaidAfter == 2, "the own turn took exactly two paid actions (maneuver + one shot), paid="
                + relocPaidAfter + " ap=" + relocApBefore + " to " + relocApAfter + " at=" + relocAfter
                + " bullets=" + bulletsBeforeFollowUp + " to " + bulletsAfterFollowUp);
        require(bulletsAfterFollowUp - bulletsBeforeFollowUp == 1,
                "the follow-up shot really produced exactly one projectile, "
                        + bulletsBeforeFollowUp + " to " + bulletsAfterFollowUp);
        int spent = relocApBefore - relocApAfter;
        require(spent == teleportPrice + shotPrice,
                "the pair costs exactly the relocation plus the second-action quote, spent=" + spent
                        + " (expected " + (teleportPrice + shotPrice) + ")");
        secondManeuverRefused = shell.position().distanceToSqr(relocAfter) < 0.25D;
        require(secondManeuverRefused, "no second maneuver was taken in the same own turn");
        require(followUpEventsBefore >= 0 && teleportEvents > followUpEventsBefore,
                "the maneuver really drove the native relocation entry");
        followUpTurnsUsed = followUpAttempts + 1;
        return true;
    }

    // ------------------------------------------------------------------ A4: the native replication

    /**
     * Drive one real same-family bullet into an open shell of this encounter.
     *
     * <p>The bullet is a real {@code ShulkerBullet} owned by an opposing participant, so vanilla's own damage path
     * runs the same-family branch. With exactly one shell within the native replication scan, vanilla's own roll
     * always produces a child; nothing here creates, re-rolls or moves one.</p>
     */
    /** Put the shell back inside its firing band, so its own attack open the shell the reaction needs. */
    private static void beginReplicationSetup() {
        shell.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        shell.setDeltaMovement(Vec3.ZERO);
        shell.fallDistance = 0.0F;
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
    }

    private static void beginReplication() {
        peekAtReplication = ShulkerNative.rawPeek(shell);
        shellHealthAtReplication = shell.getHealth();
        // Exactly one shell may be within the native scan radius, or the native roll could legitimately skip.
        for (Shulker other : level.getEntitiesOfClass(Shulker.class, new AABB(origin).inflate(48))) {
            if (other != shell) other.discard();
        }
        buildReplicationNeighbourhood();
        replicationCandidates = countRelocationCandidates();
        ShulkerNative.setRawPeek(shell, MotionRules.SHULKER_PEEK_OPEN);
        childCountBefore = level.getEntitiesOfClass(Shulker.class, new AABB(origin).inflate(48)).size();
        // A legal landing for the reaction, inside the battlefield and not the cell the body already holds: proven
        // here with the same predicates the encounter's own landing gate asks, exactly as the accepted Q proof of
        // this path does, instead of naming a fixed offset that may hold a body or have no free opening.
        repointTo = provenRepointTarget();
        require(repointTo != null, "a legal, loaded, unoccupied landing exists for this reaction, at="
                + shell.blockPosition() + " candidates=" + replicationCandidates);
        // Spawned OUTSIDE the whole target body and given a real native owner, so the hit comes from the bullet's
        // own tick and its own collision sweep instead of from a body that already overlaps its target.
        AABB body = shell.getBoundingBox();
        Vec3 from = new Vec3(shell.getX(), Math.max(body.maxY, shell.getY()) + 1.6D, shell.getZ());
        ShulkerBullet bullet = new ShulkerBullet(level, owner, shell, Direction.DOWN.getAxis());
        bullet.setPos(from.x, from.y, from.z);
        bullet.shoot(0.0D, -0.2D, 0.0D, 0.15F, 0.0F);
        bulletAt = from;
        shellHealthBeforeHit = shell.getHealth();
        level.addFreshEntity(bullet);
        require(!body.intersects(bullet.getBoundingBox()),
                "the bullet really starts outside the target body, body=" + body + " bullet=" + bullet.position());
    }

    /**
     * A support-rich neighbourhood for the native relocation search this group needs.
     *
     * <p>Vanilla's {@code teleportSomewhere} draws its own ±8 offsets and needs an empty, collision-free cell that
     * has an attachable face, so a single flat row of ground offers the search almost nothing and a real hit then
     * misses its candidate about three times in four. The accepted Q proof of this same product path builds exactly
     * this kind of arena for the same reason; this group now does too, before anything is fired. Nothing about the
     * product's search, its landing gate or its random draw is changed, no cell is written after a hit, and the
     * group still needs a real native relocation and a real world-registered child.</p>
     */
    private static void buildReplicationNeighbourhood() {
        BlockPos centre = shell.blockPosition();
        AABB opening = ShulkerAttachment.of(shell).fullOpenBox(shell);
        AABB memberBox = owner.getBoundingBox().inflate(2.0D);
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                for (int dy = 0; dy <= 8; dy++) {
                    // The shell's own column stays free: the bullet is dropped onto it from above.
                    if (dx == 0 && dz == 0) continue;
                    boolean pillar = dx % 4 == 0;
                    boolean slab = dy == 4;
                    if (!pillar && !slab) continue;
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (new AABB(pos).intersects(opening)) continue;
                    if (new AABB(pos).intersects(memberBox)) continue;
                    set(pos, Blocks.STONE.defaultBlockState());
                }
            }
        }
    }

    /**
     * The final point this group re-points the native relocation to, proven with the predicates the encounter's own
     * landing gate asks: loaded, not the cell the body already holds, not a cell another participant holds, and a
     * legal full-open attachment. The event's re-point is the accepted product seam (the Q proof of this same path
     * uses it too); the alternative is naming a fixed offset that may hold a body or have no free opening.
     */
    private static Vec3 provenRepointTarget() {
        BlockPos centre = shell.blockPosition();
        for (int radius = 2; radius <= 6; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = 0; dy >= -1; dy--) {
                        BlockPos cell = centre.offset(dx, dy, dz);
                        if (cell.equals(centre)) continue;
                        if (!level.getChunkSource().hasChunk(cell.getX() >> 4, cell.getZ() >> 4)) continue;
                        GridPos grid = new GridPos(cell.getX(), cell.getY(), cell.getZ());
                        boolean taken = false;
                        for (net.minecraft.world.entity.LivingEntity participant : battle.participants()) {
                            if (grid.equals(battle.currentActorCell(participant))) taken = true;
                        }
                        if (taken) continue;
                        if (!ShulkerAttachment.legal(level, shell, ShulkerAttachment.at(grid, Direction.DOWN),
                                null)) continue;
                        return new Vec3(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
                    }
                }
            }
        }
        return null;
    }

    /** How much of the native ±8 box this arena really offers, asked with vanilla's own candidate predicates. */    private static int countRelocationCandidates() {
        BlockPos centre = shell.blockPosition();
        int candidates = 0;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) continue;
                    if (!level.getWorldBorder().isWithinBounds(pos)) continue;
                    if (!level.noCollision(shell, new AABB(pos).deflate(1.0E-6D))) continue;
                    if (ShulkerNative.findAttachableSurface(shell, pos) == null) continue;
                    candidates++;
                }
            }
        }
        return candidates;
    }

    private static boolean checkReplication() {
        repointTo = null;
        java.util.List<Shulker> shells = level.getEntitiesOfClass(Shulker.class, new AABB(origin).inflate(48));
        // The chain is proved in order, so a failure names the first fact that did not happen.
        bulletReallyHit = shell.getHealth() < shellHealthBeforeHit;
        if (!bulletReallyHit) {
            // The shot these groups fire is a real curved native projectile, so one draw may legitimately miss a
            // shell that closed or was already reached by its own next turn. Retrying means a fresh own turn that
            // opens the shell again - never a fabricated hit and never a weakened assertion.
            replicationAttempts++;
            require(replicationAttempts <= 3, "no real native bullet ever damaged the shell in "
                    + replicationAttempts + " attempts, health=" + shell.getHealth()
                    + " spawned at " + bulletAt + " peek=" + peekAtReplication);
            for (ShulkerBullet leftover : level.getEntitiesOfClass(ShulkerBullet.class,
                    new AABB(origin).inflate(48))) {
                leftover.discard();
            }
            return false;
        }
        hitDamage = shellHealthBeforeHit - shell.getHealth();
        nativeLevitation = shell.hasEffect(MobEffects.LEVITATION);
        childRegistered = shells.stream().anyMatch(mob -> mob != shell);
        nativeChild = shells.stream().filter(mob -> mob != shell).findFirst().orElse(null);
        if (nativeChild == null) {
            // The hit landed but vanilla's own relocation drew no candidate its support gate liked, so its
            // replication branch never ran. Retry a fresh real hit on a freshly opened shell; bounded, and never a
            // fabricated child.
            replicationAttempts++;
            require(replicationAttempts <= 4, "the native hit produced no world-registered child in "
                    + replicationAttempts + " attempts, shells=" + shells.size() + " before=" + childCountBefore
                    + " events=" + teleportEvents + " reactionPeek=" + peekAtReplication
                            + " candidates=" + replicationCandidates + " at=" + shell.blockPosition());
            for (ShulkerBullet leftover : level.getEntitiesOfClass(ShulkerBullet.class,
                    new AABB(origin).inflate(48))) {
                leftover.discard();
            }
            return false;
        }
        childRostered = battle.isParticipant(nativeChild);
        childSameSide = childRostered && battle.sideOf(nativeChild) == BattleSide.HOSTILE;
        childHasSlot = battle.hasTurnSlot(nativeChild);
        childAp = battle.actorAp(nativeChild);
        // A child is its own unit: fresh resources, no inherited spent action of its parent.
        childFreshActions = battle.actorPaidActions(nativeChild) == 0;
        childVariantKept = nativeChild.getVariant().equals(shell.getVariant());
        childNativeFlags = nativeChild.isNoAi();
        require(childRegistered, "the child really joined the world");
        require(childRostered, "the native child joined the encounter");
        require(childSameSide, "the child joined its parent's own side");
        require(childHasSlot, "the admitted child really holds a turn slot");
        require(childAp >= 0, "the admitted child carries its own resource balance");
        require(childFreshActions, "the child inherits none of its parent's spent actions");
        require(childVariantKept, "the child keeps the native colour vanilla copied onto it");
        require(childNativeFlags, "the admitted child is held by the encounter's own NoAI");
        return true;
    }

    // ------------------------------------------------------------------ B1: a rostered wall and ceiling shell

    /**
     * Whether a shell the encounter really admitted on a wall or under a ceiling still holds that pose.
     *
     * <p>The fixture's own F group proves the geometry per attachment. This group proves the admission path uses
     * it: an admitted body must not be re-placed onto an ordinary floor row by the shared deployment search, and
     * its node must still be the one it holds after real ticks of a running encounter.</p>
     */
    private static void checkRosteredPoses() {
        rosteredWallAdmitted = battle.isParticipant(wallShell);
        rosteredCeilingAdmitted = battle.isParticipant(ceilingShell);
        require(rosteredWallAdmitted, "the wall shell was really admitted" + diagnostics());
        require(rosteredCeilingAdmitted, "the ceiling shell was really admitted [wall="
                + (wallShell == null ? "null" : wallShell.getUUID().toString())
                + " ceiling=" + (ceilingShell == null ? "null" : ceilingShell.getUUID().toString())
                + " at=" + (ceilingShell == null ? "null" : ceilingShell.position().toString())
                + "]" + diagnostics());
        ShulkerAttachment wallNode = ShulkerAttachment.of(wallShell);
        ShulkerAttachment ceilingNode = ShulkerAttachment.of(ceilingShell);
        wallPoseKept = wallNode.face().getAxis().isHorizontal()
                && ShulkerAttachment.legal(level, wallShell, wallNode, null)
                // A shell glued to the pillar's west face stands in the cell east of the pillar.
                && wallShell.getBlockX() == wallSampleBlock.getX() + 1;
        ceilingPoseKept = ceilingNode.face() == Direction.UP
                && ShulkerAttachment.legal(level, ceilingShell, ceilingNode, null)
                // A shell glued to the underside of the slab hangs in the cell below it.
                && ceilingShell.getBlockY() == ceilingSampleBlock.getY() - 1;
        require(wallPoseKept, "an admitted wall shell still holds a horizontal face at its own cell, face="
                + wallNode.face() + " at " + wallShell.position());
        require(ceilingPoseKept, "an admitted ceiling shell still holds the ceiling face, face="
                + ceilingNode.face() + " at " + ceilingShell.position());
    }

    // ------------------------------------------------------------------ B2: a post-handler fault rollback

    /**
     * Fail an absorb after the handler and the pose were installed, and prove the shell comes back exactly.
     *
     * <p>The sample is an <em>open</em> wall shell that this encounter does not own yet: the admission writes its
     * attach face and closes its shell, then a fixture-only fault aborts the transaction. What must come back is
     * the face, the raw peek and the covered-armour state it had, its own position, and no claim, handler, permit
     * or hold of any kind.</p>
     */
    private static void beginRollbackScenario() {
        rollbackShell = wallSample();
        ShulkerNative.setRawPeek(rollbackShell, MotionRules.SHULKER_PEEK_OPEN);
        rollbackFaceBefore = ShulkerNative.attachFace(rollbackShell);
        rollbackPeekBefore = ShulkerNative.rawPeek(rollbackShell);
        rollbackAtBefore = rollbackShell.position();
        rollbackCoveredBefore = rollbackShell.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                .getModifier(net.minecraft.resources.ResourceLocation.withDefaultNamespace("covered")) != null;
        rollbackAbsorbedBefore = battle.isParticipant(rollbackShell);
        require(!rollbackAbsorbedBefore, "the rollback sample is not part of the encounter yet");
        require(rollbackPeekBefore > MotionRules.SHULKER_PEEK_CLOSED, "the rollback sample really starts open");
        ServerBattle.injectFault(ServerBattle.FaultPoint.ABSORB_AFTER_ADMIT);
        battle.tryAbsorb(rollbackShell);
    }

    private static void checkRollback() {
        rollbackRefused = !battle.isParticipant(rollbackShell);
        rollbackFaceKept = ShulkerNative.attachFace(rollbackShell) == rollbackFaceBefore;
        rollbackPeekKept = ShulkerNative.rawPeek(rollbackShell) == rollbackPeekBefore;
        rollbackAtKept = rollbackShell.position().distanceToSqr(rollbackAtBefore) < 0.25D;
        rollbackHandlerCleared = !(rollbackShell instanceof TacticalShulkerAccess access)
                || access.tactical$handler() == null;
        rollbackNoClaim = BattleManager.containing(rollbackShell) == null;
        rollbackCoveredKept = rollbackShell.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.ARMOR)
                .getModifier(net.minecraft.resources.ResourceLocation.withDefaultNamespace("covered")) != null
                == rollbackCoveredBefore;
        require(rollbackRefused, "the faulted admission really rolled back" + diagnostics());
        require(rollbackFaceKept, "the rolled-back shell keeps the attach face it had, before=" + rollbackFaceBefore
                + " after=" + ShulkerNative.attachFace(rollbackShell));
        require(rollbackPeekKept, "the rolled-back shell keeps the raw peek it had, before=" + rollbackPeekBefore
                + " after=" + ShulkerNative.rawPeek(rollbackShell));
        require(rollbackCoveredKept, "the rolled-back shell keeps its covered-armour state");
        require(rollbackAtKept, "the rolled-back shell keeps the position it had, at " + rollbackShell.position());
        require(rollbackHandlerCleared, "the rolled-back shell answers no encounter handler");
        require(rollbackNoClaim, "the rolled-back shell leaves no claim behind");
        rollbackShell.discard();
    }

    // ------------------------------------------------------------------ C1: a real accepted opening push

    /**
     * One real native opening push on a real opposed participant.
     *
     * <p>A floor shell opens upward into its own column, where no second body may stand, so the fixture uses the
     * wall sample: a shell glued to a pillar's west face opens east, and its full-open box really reaches into the
     * cell beside it. An ordinary AI body of the other side is admitted there through the friendly transaction, so
     * the actor being pushed is a real participant of this encounter and a real opponent of the pushing shell.</p>
     */
    private static void beginPushProof() {
        wallProof = wallSample();
        require(battle.tryAbsorb(wallProof),
                "the pushing wall shell is really admitted" + diagnostics());
        require(battle.isParticipant(wallProof), "the pushing wall shell is a participant");
        pushBody = spawnOrdinaryZombie(wallSampleBlock.getX() + 2.5D, wallSampleBlock.getZ() + 0.5D);
        require(battle.tryAddFriendly(pushBody), "the ordinary opposing body is really admitted" + diagnostics());
        require(battle.opposed(wallProof, pushBody) && !battle.allied(wallProof, pushBody),
                "the pushed body is really opposed to the pushing shell");
        ShulkerAttachment node = ShulkerAttachment.of(wallProof);
        AABB opening = node.fullOpenBox(wallProof);
        pushAt = pushBody.position();
        pushCellBefore = battle.currentActorCell(pushBody);
        pushShellAt = wallProof.position();
        // A real bystander: a body of nobody's side, standing well outside the opening box. It is never admitted,
        // so nothing but a wrongly routed push could ever move it.
        bystander = spawnOrdinaryZombie(wallSampleBlock.getX() + 6.5D, wallSampleBlock.getZ() + 0.5D);
        pushBystanderAt = bystander.position();
        require(!battle.isParticipant(bystander), "the bystander is really outside this encounter");
        require(opening.intersects(pushBody.getBoundingBox()),
                "the pushed body really intersects the native opening, opening=" + opening
                        + " body=" + pushBody.getBoundingBox() + " face=" + node.face());
        require(pushCellBefore != null, "the pushed body already holds an authoritative cell");
        // The shell must be opened by its own attack, not by a direct peek write: the encounter owns the visible
        // window and rewrites the raw peek to match it on its own tick, so a written value would be undone before
        // vanilla's animation ever pushed anything. The next stage therefore gives it a real own turn.
    }

    /** After the animation has really pushed: displacement, authority, immovability and the bystander. */
    private static void checkPushProof() {
        pushAfter = pushBody.position();
        pushCellAfter = battle.currentActorCell(pushBody);
        pushMoved = pushAfter.x > pushAt.x + 0.02D;
        require(pushMoved, "the native opening really displaced the opposed body, x " + pushAt.x + " to "
                + pushAfter.x + " (opening box was proved to intersect it)");
        pushCellFollowed = pushCellAfter != null && !pushCellAfter.equals(pushCellBefore);
        require(pushCellFollowed, "the authoritative cell followed the displacement, " + pushCellBefore
                + " to " + pushCellAfter);
        // Let the pin and the shot reconciliation run over it: neither may pull the body back to the old centre.
        pushRetained = Math.abs(pushBody.getX() - pushAfter.x) < 1.0E-3D
                && Math.abs(pushBody.getZ() - pushAfter.z) < 1.0E-3D;
        require(pushRetained, "the retained real feet survived the pin and the reconciliation, now "
                + pushBody.position() + " versus " + pushAfter);
        pushShellImmovable = wallProof.position().distanceToSqr(pushShellAt) < 1.0E-4D;
        require(pushShellImmovable, "the pushing shell itself never moved");
        pushBystanderKept = bystander.isAlive()
                && bystander.position().distanceToSqr(pushBystanderAt) < 1.0E-4D;
        require(pushBystanderKept, "a protected bystander of the same field never moved");
        pushBody.discard();
        if (bystander != null) bystander.discard();
        // The pushing shell stays: it is a ground-level opponent of the member in its own firing band, which is
        // exactly what the marked-shot group needs to fire a real, tracked native bullet.
        require(battle.isParticipant(wallProof), "the pushing shell is still a participant for the shot group");
    }

    // ------------------------------------------------------------------ C2: marked shot ownership on exit

    /**
     * A real marked shot whose block impact is cancelled keeps its protection, and a target that leaves really
     * takes the projectile and its mark with it.
     */
    private static void beginMarkedExit() {
        // A real wall across the shot's path: a bullet that curves must still meet stone on the way.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                set(new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + 2),
                        Blocks.STONE.defaultBlockState());
            }
        }
        markedWall = origin.offset(0, 0, 2);
        // A wall in the shot's own path, so the projectile really meets a block instead of open air.
        markedBulletsBefore = ShulkerBullets.size();
        markedEventsBefore = blockImpactCancels;
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
    }

    private static int wallProofCellX() {
        return origin.getX() + 1;
    }

    private static void checkMarkedExit() {
        require(markedBullet != null, "the encounter really fired a marked shot, marked="
                + ShulkerBullets.size() + " blocks=" + blockImpactCancels + " before=" + markedEventsBefore);
        require(ShulkerBullets.owns(markedBullet), "the marked shot is owned at the moment it exists");
        // The fixture cancelled a real block impact; the product must NOT have forgotten the ownership there.
        markedImpactCancelled = blockImpactCancels > markedEventsBefore;
        require(markedImpactCancelled, "a real block impact of the marked shot was really cancelled by a "
                + "later listener, cancels=" + blockImpactCancels + " before=" + markedEventsBefore);
        require(ShulkerBullets.owns(markedBullet),
                "a cancelled block impact never turns a marked shot into an unmarked one");
        // The target leaves the still-running encounter: the projectile and its mark go with it.
        boolean joined = battle.isMember(second)
                || battle.joinDirect(second) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE;
        markedSecondJoined = joined;
        battle.leave(owner, BattleText.exitReason(BattleText.Exit.FLED));
        markedTargetGone = shulkerBulletGone(markedBullet);
        require(markedTargetGone, "the departed target really took its projectile with it");
        require(ShulkerBullets.size() == 0, "the departed target really took its mark with it, left="
                + ShulkerBullets.size());
        if (markedWall != null) set(markedWall, Blocks.AIR.defaultBlockState());
    }

    private static ShulkerBullet firstOwnedBullet() {
        return level.getEntitiesOfClass(ShulkerBullet.class, new AABB(origin).inflate(48))
                .stream().filter(ShulkerBullets::owns).findFirst().orElse(null);
    }

    private static boolean shulkerBulletGone(Entity bullet) {
        return bullet == null || bullet.isRemoved()
                || level.getEntity(bullet.getId()) != bullet;
    }

    /** One ordinary AI body of either side; only the fact that it is not a shell matters here. */
    private static net.minecraft.world.entity.monster.Zombie spawnOrdinaryZombie(double x, double z) {
        net.minecraft.world.entity.monster.Zombie mob =
                (net.minecraft.world.entity.monster.Zombie) EntityType.ZOMBIE.create(level);
        require(mob != null, "a zombie exists in this version");
        mob.setNoAi(true);
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        mob.setPos(x, origin.getY(), z);
        require(level.addFreshEntity(mob), "the ordinary body really joined the world");
        return mob;
    }

    // ------------------------------------------------------------------ teardown

    private static void checkTeardown() {
        if (battle != null) battle.finish(BattleText.literal("fixture: shulker complete"));
        handlerCleared = !(shell instanceof TacticalShulkerAccess access) || access.tactical$handler() == null;
        noAiRestored = shell.isNoAi();
        noHold = !battle.isBusy();
        noChildPending = ShulkerBullets.size() == 0;
        require(handlerCleared, "the released shulker answers no encounter handler any more");
        require(noAiRestored, "the borrowed NoAI flag is given back as it was");
        require(noHold, "no controlled hold outlives the encounter");
        require(noChildPending, "no marked shot outlives the encounter");
    }

    // ------------------------------------------------------------------ helpers

    private static double feetOf(Entity entity) {
        return entity.getY();
    }

    private static int peek() {
        return ShulkerNative.rawPeek(shell);
    }

    /**
     * Cycle real player turns until this exact shell of the encounter really opens its own window.
     *
     * <p>{@link #driveEnemyTurn()} hands the encounter exactly one enemy opportunity chosen by the encounter
     * itself. A group that cares <em>which</em> body acted therefore cannot use it: the shell it watches is often
     * not the first actor, and the helper then returns false for the rest of the stage while nothing opens. This
     * keeps ending the player's own turn while the watched shell is still closed, and reports whether the group
     * has to wait another tick. The window itself is never written: only the shell's own native attack opens it.</p>
     */
    private static boolean waitForOwnOpening(Shulker watched, int budget) {
        require(watched != null && watched.isAlive() && battle != null && battle.isParticipant(watched),
                "the watched shell of this group is really alive in the encounter" + diagnostics());
        if (ShulkerNative.rawPeek(watched) > MotionRules.SHULKER_PEEK_CLOSED) return false;
        require(waitTicks(budget), "the watched shell never opened its own window within " + budget
                + " ticks" + diagnostics());
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
        return true;
    }

    private static boolean driveEnemyTurn() {
        if (battle == null || battle.finished()) return false;
        if (driveStage != stage) {
            driveStage = stage;
            enemyPhaseSeen = false;
        }
        if (!battle.isPlayerTurnNow()) {
            enemyPhaseSeen = true;
            return true;
        }
        if (enemyPhaseSeen) return false;
        battle.endPlayerTurn();
        return true;
    }

    private static boolean waitTicks(int count) {
        wait++;
        return wait < count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String diagnostics() {
        return " [stage=" + stage + " ticks=" + ticks + " wait=" + wait + " pos="
                + (shell == null ? "none" : shell.position()) + " events=" + teleportEvents
                + " bullets=" + bulletsSeen
                + " participant=" + (battle != null && shell != null && battle.isParticipant(shell))
                + " finished=" + (battle != null && battle.finished())
                + " end=" + (battle == null ? "none" : battle.endCodeName())
                + " alive=" + (shell != null && shell.isAlive())
                + " ap=" + (battle == null || shell == null ? -1 : battle.actorAp(shell))
                + " paid=" + (battle == null || shell == null ? -1 : battle.actorPaidActions(shell))
                + "]";
    }

    private static void complete(MinecraftServer server) {
        // One sub-group per line, so every printed value stays paired with its own name and a shifted argument
        // list can never masquerade as evidence.
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS");
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS F[floor={} wall={} ceiling={} floatingRefused={} "
                        + "closedArrowRefused={} closedHp={} openHp={} events={} vetoRefused={} vetoKept={} "
                        + "relocated={} quantised={}]",
                floorLegal, wallLegal, ceilingLegal, floatingRefused, closedArrowRefused, closedArrowDamage,
                openArrowDamage, teleportEvents, vetoRefused, vetoKeptPosition, nativeRelocated, quantised);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A1[base={} rise1={} rise2={} otherTurn={} noDrift={} "
                        + "held={} noAi={} memberFeet={} to {} memberAp={} to {} expired={} fall={}]",
                riseBase, riseFirst, riseSecond, afterOtherTurn, noDriftOnOtherTurn, held, noAiKept,
                memberFeetBefore, memberFeetAfter, memberApBefore, memberApAfter, expiredFeet, fallStarted);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A2[sameSide={} sides={} swept={} peek={} at={} to {} "
                        + "kept={} shellX={} to {} immovable={}]",
                friendlySameSide, friendlySide, friendlyInSweep, pushPeek, friendlyAt, friendlyAtAfter, friendlyKept,
                shellBlockXBefore, shellBlockXAfter, shellImmovable);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A3[at={} to {} kept={} free={} skipped={} ap={} to {}]",
                refusedBefore, refusedAfter, refusedKeptPosition, refusedFree, refusalSkipped,
                refusedApBefore, refusedApAfter);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A4[skipped={} why={} child={} rostered={} side={} "
                        + "slot={} ap={} fresh={} variant={} noAi={}]",
                replicationSkipped, replicationSkippedBecause, childRegistered, childRostered, childSameSide,
                childHasSlot, childAp, childFreshActions, childVariantKept, childNativeFlags);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A5[skipped={} paid={} to {} ap={} to {} "
                        + "bullets={} to {} secondManeuverRefused={}]",
                followUpSkipped, relocPaidBefore, relocPaidAfter, relocApBefore, relocApAfter,
                bulletsBeforeFollowUp, bulletsAfterFollowUp, secondManeuverRefused);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS A6[peekAfterShot={} peekAfterEnemyTurn={} "
                        + "windowSurvived={} peekAfterOwnTurn={} windowClosed={}]",
                peekAfterShot, peekAfterEnemyTurn, windowSurvived, peekAfterOwnTurn, windowClosed);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS B1[wallAdmitted={} ceilingAdmitted={} wallPose={} "
                        + "ceilingPose={}]",
                rosteredWallAdmitted, rosteredCeilingAdmitted, wallPoseKept, ceilingPoseKept);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS B2[refused={} face={} peek={} covered={} at={} "
                        + "handlerCleared={} noClaim={}]",
                rollbackRefused, rollbackFaceKept, rollbackPeekKept, rollbackCoveredKept, rollbackAtKept,
                rollbackHandlerCleared, rollbackNoClaim);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS C1[moved={} at={} to {} cell={} to {} retained={} "
                        + "shellImmovable={} bystanderKept={}]",
                pushMoved, pushAt, pushAfter, pushCellBefore, pushCellAfter, pushRetained,
                pushShellImmovable, pushBystanderKept);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS C2[marked={} blockImpactCancelled={} cancels={} "
                        + "stillOwned=true secondJoined={} targetGone={}]",
                markedBulletsBefore, markedImpactCancelled, blockImpactCancels, markedSecondJoined,
                markedTargetGone);
        Tactical.LOGGER.info("[Tactical] SHULKER SELFTEST PASS T[handlerCleared={} noAi={} noHold={} noShot={}]",
                handlerCleared, noAiRestored, noHold, noChildPending);
        server.halt(false);
    }
    private static void fail(MinecraftServer server) {
        Tactical.LOGGER.error("[Tactical] SHULKER SELFTEST FAILED{}", diagnostics());
        server.halt(false);
    }

    /** Kept so a fixture that never ran cannot look like a pass in a log search. */
    static {
        if (Boolean.getBoolean("tactical.selftest.verbose")) {
            Tactical.LOGGER.info("[Tactical] shulker fixture class loaded, gate={}", System.getProperty("tactical.selftest"));
        }
    }
}
