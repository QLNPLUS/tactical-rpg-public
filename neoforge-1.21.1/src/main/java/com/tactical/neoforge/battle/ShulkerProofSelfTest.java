package com.tactical.neoforge.battle;

import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Focused proof fixture for the two narrow gates the tactical034 A+B review still owes:
 * {@code -Dtactical.selftest=shulker_proof}.
 *
 * <p>It is deliberately independent of {@code ShulkerSelfTest}'s thirty-stage macro. Each group builds its own
 * throwaway arena and its own fresh encounter, asks one question, prints its own receipt line and then leaves the
 * world clean, so no group inherits a busy turn, a spent quota or a leftover projectile from another one.</p>
 *
 * <ul>
 *   <li><b>C1</b> one real native opening, driven by the controlled shell's own attack, displaces an admitted
 *       opposed ordinary body and carries its authoritative cell with it, while an admitted body of the shell's
 *       own side inside the same swept volume and a non-participant bystander are untouched, and the pushing shell
 *       itself never moves;</li>
 *   <li><b>R</b> a native spawn the world refuses at {@code EntityJoinLevelEvent} leaves no mark, no tracked
 *       flight, no registered projectile and no charge;</li>
 *   <li><b>M</b> a marked native shot whose real block impact is cancelled by a later listener keeps its
 *       ownership, and a non-manager member's partial exit really removes that projectile and its mark while the
 *       encounter stays active;</li>
 *   <li><b>X</b> a mark cannot be overwritten, and an owner that leaves the world drops its own exact shot in its
 *       own session without touching another session's still-flying shot;</li>
 *   <li><b>H</b> a relocation that really landed while the body carried a live levitation hold: the older hold is
 *       retired by the adoption itself, the landed feet and the authoritative cell still agree with the body on the
 *       following ticks, the native effect survives untouched, and the next own rise starts from the landed body
 *       ({@code -Dtactical.selftest=shulker_hold} runs this group alone).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ShulkerProofSelfTest {

    private static boolean enabled;
    /** Run only the isolated native replication group, so its own evidence never waits behind another group. */
    private static boolean qOnly;
    private static ServerLevel level;
    private static MinecraftServer server;
    private static BlockPos spawn;

    private static int stage;
    private static int lastStage = -1;
    private static int ticks;
    /** Ticks spent inside the current stage; every bounded wait below reads this. */
    private static int held;

    /** Encounters are driven exactly once per stage; the phase bookkeeping is per live encounter. */
    private static final java.util.IdentityHashMap<ServerBattle, Boolean> turnEnded =
            new java.util.IdentityHashMap<>();
    private static final java.util.IdentityHashMap<ServerBattle, Boolean> enemyPhaseSeen =
            new java.util.IdentityHashMap<>();

    // ---- C1 protected half: a body of the shell's own side in the swept cell -----------------------------
    private static ServerBattle protectedBattle;
    private static ServerPlayer memberProtected;
    private static Shulker protectedShell;
    private static Mob protectedBody;
    private static Mob protectedBystander;
    private static AABB protectedOpening;
    private static AABB protectedSweep;
    private static String protectedShape = "none";
    private static Vec3 protectedBefore;
    private static Vec3 protectedAfter;
    private static Vec3 protectedBystanderBefore;
    private static Vec3 protectedBystanderAfter;
    private static Vec3 protectedShellBefore;
    private static Vec3 protectedShellAfter;
    private static int protectedPeek = -1;
    private static int protectedWait = -1;

    // ---- C1 push half: the same opening geometry, holding a body it may displace --------------------------
    private static ServerBattle pushBattle;
    private static ServerPlayer memberPush;
    private static Shulker pushShell;
    private static Mob pushBody;
    private static AABB pushOpening;
    private static AABB pushSweep;
    private static String pushShape = "none";
    private static boolean sameOpeningGeometry;
    private static Vec3 pushBefore;
    private static AABB pushBeforeBox;
    private static GridPos pushCellBefore;
    private static Vec3 pushAfter;
    private static GridPos pushCellAfter;
    private static Vec3 pushRetained;
    private static Vec3 pushShellBefore;
    private static Vec3 pushShellAfter;

    // ---- R: a refused native spawn -------------------------------------------------------------------------
    private static ServerBattle battleB;
    private static ServerPlayer memberB;
    private static Shulker refusedShell;
    private static boolean cancelNextSpawn;
    private static int joinCancels;
    private static int joinCancelsBefore;
    private static int refusedApBefore = -1;
    private static int refusedApAfter = -1;
    private static int refusedPaidBefore = -1;
    private static int refusedPaidAfter = -1;

    // ---- M: a marked shot whose block impact is cancelled, then its target leaves ---------------------------
    private static ServerBattle battleC;
    private static ServerPlayer memberC1;
    private static ServerPlayer memberC2;
    private static Shulker shotShell;
    private static AABB shotOpening;
    private static ShulkerBullet markedBullet;
    private static ShulkerBullets.Owner markedOwner;
    private static int markedEpoch = -1;
    private static int markedCountBefore;
    private static int blockImpactCancels;
    private static int blockImpactCancelsBefore = -1;
    private static int markedAttempts;
    private static int markedWait;
    private static boolean targetDeparted;
    private static boolean departureDiscardedShot;
    private static boolean departureClearedMark;
    private static boolean encounterStayedActive;

    // ---- X: ownership isolation between two live sessions ---------------------------------------------------
    private static ServerBattle battleD;
    private static ServerBattle battleE;
    private static ServerPlayer memberD;
    private static ServerPlayer memberE;
    private static Shulker shellD;
    private static Shulker shellE;
    private static ShulkerBullet bulletD;
    private static ShulkerBullet bulletE;
    private static ShulkerBullets.Owner ownerDBefore;
    private static boolean markImmutable;
    private static boolean ownerShotDiscarded;
    private static boolean ownerMarkCleared;
    private static boolean otherShotKept;
    private static boolean otherMarkKept;
    private static boolean otherSessionKept;

    // ---- Q: one isolated native replication ---------------------------------------------------------------
    private static ServerBattle battleF;
    private static ServerPlayer memberF;
    private static Shulker parentShell;
    private static BlockPos parentCell;
    private static AABB parentOpening;
    private static Vec3 replicationTarget;
    private static int replicationEvents;
    private static int qEventsBefore;
    private static int qAttempts;
    private static int qWait;
    private static final java.util.Set<UUID> ownedShells = new java.util.HashSet<>();
    private static int staleShells;
    private static boolean qArenaLoaded;
    private static int qCleanPasses;
    private static int qOpenPeek = -1;
    private static int qOpenWait = -1;
    private static int qCandidates;
    private static int qCandidateBox;
    private static float parentHealthBefore = -1.0F;
    private static Vec3 parentBefore;
    private static Vec3 parentAfter;
    private static boolean parentMoved;
    private static boolean parentStayedHealthy;
    private static boolean authorizationSpent;
    private static Shulker nativeChild;
    private static boolean childRegistered;
    private static boolean childRostered;
    private static boolean childSameSide;
    private static boolean childHasSlot;
    private static int childAp = -1;
    private static boolean childFreshActions;
    private static boolean childVariantKept;
    private static boolean childNativeFlags;

    // ---- H: one relocation adopted while a levitation hold is live ------------------------------------------
    /** Run only this group, so its own evidence never waits behind another group. */
    private static boolean holdOnly;
    private static ServerBattle holdBattle;
    private static ServerPlayer holdMember;
    private static Shulker holdShell;
    private static BlockPos holdOrigin;
    private static Vec3 holdLanding;
    private static Vec3 holdTarget;
    private static Vec3 holdFeetBefore;
    private static double holdAnchor = -1.0D;
    private static Vec3 holdAfter;
    private static GridPos holdCellAfter;
    private static Vec3 holdKept;
    private static Vec3 holdNextFeet;
    private static java.lang.Double holdLeaseAfter;
    private static boolean holdEffectKept;
    private static int holdEventsBefore;
    private static int holdApBefore = -1;
    private static int holdPaidBefore = -1;
    private static int holdApAfter = -1;
    private static int holdPaidAfter = -1;
    private static int holdBulletsBefore;
    private static int holdBulletsAfter;
    private static int holdMemberApBefore = -1;
    private static int holdMemberApAfter = -1;
    private static int holdStableTicks;
    private static int holdAttempts;
    private static double holdNextRise = -1.0D;
    private static boolean holdNextRiseRead;
    private static int holdWait;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "shulker_proof".equals(System.getProperty("tactical.selftest"))
                || "shulker_proof_q".equals(System.getProperty("tactical.selftest"))
                || "shulker_hold".equals(System.getProperty("tactical.selftest"));
        qOnly = "shulker_proof_q".equals(System.getProperty("tactical.selftest"));
        holdOnly = "shulker_hold".equals(System.getProperty("tactical.selftest"));
        if (!enabled) return;
        if (qOnly) stage = 20;
        if (holdOnly) stage = 40;
        server = event.getServer();
        level = server.overworld();
        spawn = level.getSharedSpawnPos();
        // The fixture arena is its own world: no world mob may spawn onto it and turn a group's budget into a fight.
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        BattleManager.reset();
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF fixture armed, gate=shulker_proof");
    }

    /** Refuse the shell's own next native spawn, and count what the world really refused. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!enabled) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ShulkerBullet)) return;
        if (!cancelNextSpawn) return;
        cancelNextSpawn = false;
        joinCancels++;
        event.setCanceled(true);
    }

    /** Cancel a real block impact of a marked shot at the lowest priority - never a simulated one. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!enabled) return;
        if (!(event.getProjectile() instanceof ShulkerBullet bullet)) return;
        if (!ShulkerBullets.owns(bullet)) return;
        if (event.getRayTraceResult().getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
        blockImpactCancels++;
        event.setCanceled(true);
    }

    /** Count the native relocations this fixture sees, and give the Q group a pre-proved final point. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleport(net.neoforged.neoforge.event.entity.EntityTeleportEvent.EnderEntity event) {
        if (!enabled) return;
        if (!(event.getEntity() instanceof Shulker)) return;
        replicationEvents++;
        Vec3 target = holdTarget != null ? holdTarget : replicationTarget;
        if (target == null) return;
        event.setTargetX(target.x);
        event.setTargetY(target.y);
        event.setTargetZ(target.z);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (level != null) purgeStrays();
        if (!enabled) return;
        ticks++;
        if (stage != lastStage) {
            lastStage = stage;
            held = 0;
            turnEnded.clear();
            enemyPhaseSeen.clear();
        } else {
            held++;
        }
        try {
            require(ticks < 8_000, "focused proof deadline" + diagnostics());
            switch (stage) {
                case 0 -> { beginProtectedSetUp(); stage = 1; }
                // Protected half: a body of the pushing shell's OWN side stands in the cell vanilla's own opening
                // sweeps, and the shell's own attack opens that window over it.
                case 1 -> {
                    if (protectedPeek < 0) {
                        int raw = ShulkerNative.rawPeek(protectedShell);
                        if (raw > MotionRules.SHULKER_PEEK_CLOSED) {
                            protectedPeek = raw;
                            protectedWait = held;
                        } else {
                            require(held < 80, "the protected half's shell never opened its own window"
                                    + diagnostics());
                            drive(protectedBattle);
                            return;
                        }
                    }
                    // Vanilla's own animation steps 0.05 per tick, so a full open needs twenty ticks.
                    if (held - protectedWait < 30) return;
                    stage = 2;
                }
                case 2 -> {
                    checkProtectedHalf();
                    endArena(protectedBattle);
                    beginPushSetUp();
                    stage = 3;
                }
                // Push half: the same opening geometry in its own fresh encounter, now holding a body this shell
                // may really displace. That displacement is what proves the protected half's cell was swept.
                case 3 -> {
                    if (pushBody.position().x > pushBefore.x + 0.02D) {
                        stage = 4;
                        return;
                    }
                    require(held < 240, "the push half's shell never opened its window" + diagnostics());
                    drive(pushBattle);
                }
                // The push is vanilla's own animation: it really arrives in 0.05 steps, so the two readings are
                // taken only after the whole opening has happened.
                case 4 -> {
                    if (held < 30) return;
                    checkPushHalf();
                    stage = 5;
                }
                case 5 -> {
                    // The pin and the shot reconciliation must run over the displaced body at least once.
                    if (held < 10) return;
                    checkRetained();
                    stage = 6;
                }
                case 6 -> { reportOpeningGroups(); endArena(pushBattle); beginRefusedSetUp(); stage = 10; }
                case 10 -> { if (driveAll()) return; stage = 11; }
                case 11 -> {
                    if (joinCancels <= joinCancelsBefore) {
                        if (held < 80) return;
                        require(false, "the shell never attempted the native spawn this group refuses"
                                + diagnostics());
                    }
                    if (held < 8) return;
                    checkRefusedSpawn();
                    reportRefused();
                    stage = 12;
                }
                case 12 -> { endArena(battleB); beginMarkedSetUp(); stage = 13; }
                // The shot and the collision it meets are observed inside the same enemy phase: a marked bullet
                // can keep the encounter busy, so waiting for the phase to hand the turn back would wait for the
                // very fact this group is measuring.
                // The shot and the collision it meets are observed inside the same enemy phase: a marked bullet
                // can keep the encounter busy, so waiting for the phase to hand the turn back would wait for the
                // very fact this group is measuring. A shot whose bullet has not met the stone yet is replaced by
                // one more real shot on a fresh own turn - bounded, and never a fabricated projectile.
                case 13 -> {
                    markedBullet = ownedBulletOf(shotShell.getUUID(), markedBullet);
                    if (markedBullet != null && markedOwner == null) {
                        markedOwner = ShulkerBullets.markOf(markedBullet);
                        require(markedOwner != null, "the shot really carries an ownership mark");
                        markedEpoch = markedOwner.turnEpoch();
                        require(markedEpoch >= 1, "the mark names the own turn it was fired in, epoch=" + markedEpoch);
                        require(markedOwner.actorId().equals(shotShell.getUUID()),
                                "the mark names the shell that really fired it");
                        require(markedOwner.targetId().equals(memberC2.getUUID()),
                                "the shot was really aimed at the non-manager member, target="
                                        + markedOwner.targetId() + " expected=" + memberC2.getUUID());
                    }
                    if (markedBullet != null && blockImpactCancels > blockImpactCancelsBefore) {
                        checkCancelledImpact();
                        stage = 15;
                        return;
                    }
                    if (markedWait++ < 140) {
                        if (markedBullet == null) drive(battleC);
                        return;
                    }
                    require(markedAttempts < 4,
                            "the marked shot never met the native collision this group built" + markedTrace());
                    markedAttempts++;
                    markedWait = 0;
                    markedBullet = null;
                    markedOwner = null;
                    for (ShulkerBullet leftover : level.getEntitiesOfClass(ShulkerBullet.class,
                            new AABB(spawn).inflate(400))) {
                        leftover.discard();
                    }
                    blockImpactCancelsBefore = blockImpactCancels;
                    redrive(battleC);
                }
                case 15 -> {
                    if (held < 3) return;
                    checkCancelledImpact();
                    leaveTargetAndCheck();
                    reportMarked();
                    stage = 16;
                }
                case 16 -> { endArena(battleC); beginIsolationSetUp(); stage = 17; }
                case 17 -> {
                    bulletD = ownedBulletOf(shellD.getUUID(), bulletD);
                    bulletE = ownedBulletOf(shellE.getUUID(), bulletE);
                    if (bulletD == null || bulletE == null) {
                        drive(battleD);
                        drive(battleE);
                        if (held < 160) return;
                        require(false, "both sessions never held a marked shot at the same time, D=" + bulletD
                                + " E=" + bulletE + diagnostics());
                    }
                    stage = 18;
                }
                case 18 -> {
                    ownerDBefore = ShulkerBullets.markOf(bulletD);
                    require(ownerDBefore != null, "the first session's shot really carries a mark");
                    // A second claim on the same projectile must not be able to replace the first.
                    ShulkerBullets.mark(bulletD, new ShulkerBullets.Owner(UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), 99));
                    markImmutable = ownerDBefore.equals(ShulkerBullets.markOf(bulletD));
                    require(markImmutable, "a mark is immutable, now=" + ShulkerBullets.markOf(bulletD)
                            + " before=" + ownerDBefore);
                    // The owner really leaves the world: its own shot must go with it.
                    shellD.discard();
                    stage = 19;
                }
                case 19 -> {
                    if (held < 12) return;
                    checkOwnerCleanup();
                    reportIsolation();
                    stage = 20;
                }
                // Q: one isolated native replication in its own fresh arena and encounter.
                case 20 -> { beginReplicationSetUp(); stage = 21; }
                case 21 -> {
                    // The window under test is the one the parent's own attack opens on the real tick: nothing in
                    // this group writes it, and the group refuses to shoot at a closed shell.
                    if (qOpenPeek < 0) {
                        int peek = ShulkerNative.rawPeek(parentShell);
                        if (peek > MotionRules.SHULKER_PEEK_CLOSED) {
                            qOpenPeek = peek;
                            qOpenWait = held;
                        } else {
                            require(held < 120, "the parent never opened its own window" + replicationTrace());
                            if (held % 10 == 0) redrive(battleF);
                            return;
                        }
                    }
                    if (held - qOpenWait < 12) return;
                    stage = 22;
                }
                case 22 -> { fireReplicationBullet(); qAttempts++; stage = 23; }
                case 23 -> {
                    if (nativeChild == null) nativeChild = replicationChild();
                    if (nativeChild != null) {
                        stage = 24;
                        return;
                    }
                    boolean hit = parentShell.getHealth() < parentHealthBefore - 0.01F;
                    if (!hit) {
                        require(qWait++ < 140, "the same-family bullet never damaged the open parent"
                                + replicationTrace());
                        return;
                    }
                    // The hit landed. Two entirely different facts are told apart here: the native search drew no
                    // candidate at all (the EnderEntity event never ran - a precondition of this fixture), or the
                    // event ran and the landing was refused / no child registered (a product fact).
                    require(replicationEvents <= qEventsBefore,
                            "the native relocation really ran and produced no registered child" + replicationTrace());
                    require(qAttempts < 6, "the native ±8 search drew no attachable candidate in " + qAttempts
                            + " real hits" + replicationTrace());
                    parentHealthBefore = parentShell.getHealth();
                    qWait = 0;
                    stage = 22;
                }
                case 24 -> { checkReplication(); reportReplication(); stage = 25; }
                case 25 -> complete();
                // H: one relocation adopted while a levitation hold is live. Its own fresh arena and encounter, so
                // no other group's spent quota or floating body can stand in for the fact under test.
                case 40 -> { beginHoldSetUp(); stage = 41; }
                case 41 -> {
                    if (!holdOwnTurnBaseline()) return;
                    // The exact height this encounter is holding the body at, read from the live lease the shell's
                    // own turn really produced - never from a cell centre or a written position. It is read on
                    // every tick of this own turn, because the adoption may retire it while the turn is still open.
                    java.lang.Double lease = holdBattle.heldHeight(holdShell);
                    if (lease != null) holdAnchor = lease;
                    // The own turn's own rise is not the fact being measured: only the body standing on the point
                    // this fixture offered is the adopted native relocation.
                    if (holdShell.position().distanceToSqr(holdLanding) < 1.0E-6D) {
                        stage = 42;
                        return;
                    }
                    // A paid relocation may still be unresolved while this own turn is current, so nothing is
                    // concluded before the encounter itself hands the turn on.
                    if (holdBattle.isActorTurn(holdShell)) return;
                    // The own turn ended without a landed move: vanilla's own five-attempt ±8 search drew no
                    // candidate its support gate liked, or the product's own gate refused the point. A retry is a
                    // whole new own turn with a fresh draw and a fresh baseline.
                    holdAttempts++;
                    require(holdAttempts <= 6, "no own turn of the floating shell ever landed its relocation in "
                            + holdAttempts + " attempts, at=" + holdShell.position() + " lease="
                            + holdBattle.heldHeight(holdShell) + holdTrace());
                    holdApBefore = -1;
                    holdAnchor = -1.0D;
                }
                case 42 -> {
                    checkHoldAdoption();
                    stage = 43;
                }
                case 43 -> {
                    // The older hold's own tick, the grid pin and the shot reconciliation all have to run over the
                    // landed body at least once; only then is "the body really stayed" a statement about ticks.
                    if (held < 14) return;
                    holdKept = holdShell.position();
                    holdLeaseAfter = holdBattle.heldHeight(holdShell);
                    holdStableTicks = held;
                    require(holdKept.distanceToSqr(holdAfter) < 1.0E-6D,
                            "the landed body stays exactly at the feet this encounter retained, " + holdAfter
                                    + " to " + holdKept + " lease=" + holdLeaseAfter + holdTrace());
                    require(holdLeaseAfter == null,
                            "the older levitation hold really stays retired after the adoption, lease="
                                    + holdLeaseAfter + holdTrace());
                    require(holdShell.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),
                            "the native effect is untouched by the hand-over" + holdTrace());
                    require(holdBattle.currentActorCell(holdShell).equals(holdCellAfter),
                            "the authoritative cell still floors the real body, cell="
                                    + holdBattle.currentActorCell(holdShell) + " body=" + holdKept + holdTrace());
                    stage = 44;
                }
                case 44 -> {
                    // The ownership transition is only complete if the next own turn rises from where the body
                    // really landed. A turn whose rise is refused is reported, not failed: whether the world holds
                    // a free cell above a relocated body is the world's own answer, and this group is the hand-over.
                    if (!holdNextRiseRead) {
                        if (holdBattle.isPlayerTurnNow()) {
                            holdBattle.endPlayerTurn();
                            return;
                        }
                        if (!holdBattle.isActorTurn(holdShell)) {
                            require(holdWait++ < 90, "the shell never took another own turn" + holdTrace());
                            return;
                        }
                        java.lang.Double lease = holdBattle.heldHeight(holdShell);
                        holdNextRise = lease == null ? -1.0D : lease;
                        holdNextFeet = holdShell.position();
                        holdNextRiseRead = true;
                    }
                    if (holdNextRise >= 0.0D) {
                        require(holdNextFeet.distanceToSqr(holdLanding.add(0.0D, 1.0D, 0.0D)) < 1.0E-4D,
                                "a later own rise starts from the body this encounter landed, not from the anchor it "
                                        + "left, feet=" + holdNextFeet + " expected="
                                        + holdLanding.add(0.0D, 1.0D, 0.0D) + holdTrace());
                    }
                    reportHold();
                    stage = 45;
                }
                case 45 -> {
                    if (holdBattle != null && !holdBattle.finished()) {
                        holdBattle.finish(BattleText.literal("fixture: shulker hold group complete"));
                    }
                    for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(spawn).inflate(400))) {
                        if (leftover instanceof ServerPlayer) continue;
                        leftover.discard();
                    }
                    holdTarget = null;
                    replicationTarget = null;
                    stage = 25;
                }
                default -> { }
            }
        } catch (AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SHULKER PROOF FAILED at stage {}: {}", stage, failure.getMessage());
            server.halt(false);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] SHULKER PROOF CRASHED at stage {}", stage, failure);
            server.halt(false);
        }
    }

    // ------------------------------------------------------------------ C1: a real accepted opening push

    /**
     * One real native opening, driven by the controlled shell's own attack, over three bodies at once.
     *
     * <p>The pushing shell is a wall sample: glued to a pillar's west face, its shell opens east into the cell
     * beside it. An ordinary body of the other side is admitted through the friendly transaction and proved to
     * stand in the full-open box; an ordinary body of the shell's own side is admitted through the hostile
     * transaction and proved to stand inside the volume vanilla's own opening sweeps; a third body belongs to
     * nobody. Every baseline is the position the body really holds after admission - admission may legitimately
     * place a body, and only what it then holds is the thing that must not move.</p>
     */
    /** The volume vanilla's own opening pushes through, built with vanilla's own call. */
    private static AABB sweepOf(Shulker shell) {
        ShulkerAttachment node = ShulkerAttachment.of(shell);
        return Shulker.getProgressDeltaAabb(shell.getScale(), node.opening(), 1.0F, 0.0F)
                .move(shell.getX() - 0.5D, shell.getY(), shell.getZ() - 0.5D);
    }

    /** That volume in the shell's own block coordinates, so two arenas can be compared honestly. */
    private static String relativeShape(Shulker shell, AABB swept) {
        BlockPos cell = shell.blockPosition();
        return String.format(java.util.Locale.ROOT, "face=%s x%.3f..%.3f y%.3f..%.3f z%.3f..%.3f",
                ShulkerNative.attachFace(shell),
                swept.minX - cell.getX(), swept.maxX - cell.getX(),
                swept.minY - cell.getY(), swept.maxY - cell.getY(),
                swept.minZ - cell.getZ(), swept.maxZ - cell.getZ());
    }

    /** The pillar, the wall shell glued to its west face and one member of the group, in its own arena. */
    private static Shulker newWallArena(BlockPos arena) {
        BlockPos pillar = arena.offset(4, 0, 0);
        set(pillar, Blocks.STONE.defaultBlockState());
        set(pillar.above(), Blocks.STONE.defaultBlockState());
        return spawnShell(pillar.offset(1, 0, 0), Direction.WEST);
    }

    private static BlockPos pillarOf(BlockPos arena) {
        return arena.offset(4, 0, 0);
    }

    /**
     * Protected half: an ordinary body of the pushing shell's OWN side, standing in the cell vanilla's own
     * opening really sweeps, opened over by that shell's own attack.
     *
     * <p>Two facts were wrong in the earlier shape of this question and made it vacuous rather than a
     * false negative: the sample was itself a shulker - which vanilla never pushes, whatever the encounter
     * decides - and it was admitted through the friendly transaction, which is the PLAYER side while the pushing
     * shell is a HOSTILE participant, so the two were in fact opposed. The body is therefore an ordinary AI body
     * taken through the hostile transaction, and its baseline is the feet it holds after that admission, because
     * admission may legitimately place a body.</p>
     */
    private static void beginProtectedSetUp() {
        BlockPos arena = arena(0);
        Shulker shell = newWallArena(arena);
        BlockPos pillar = pillarOf(arena);
        memberProtected = spawnPlayer("proof-a", "ProofA", arena.offset(7, 0, 7));
        protectedShell = shell;
        // Both bodies and the shell are hostile, so the encounter's own start admits them: the shell is closed
        // for every one of these admissions, which is the only moment its own cell is really free.
        protectedBody = spawnOrdinary("proof-same-side", pillar.getX() + 2.5D, arena.getY(), pillar.getZ() + 0.5D);
        protectedBattle = startBattle(memberProtected);
        // The bystander belongs to nobody, so it is placed only after the encounter exists and was never part of
        // that encounter's own deployment.
        protectedBystander = spawnOrdinary("proof-bystander", pillar.getX() + 6.5D, arena.getY(),
                pillar.getZ() + 0.5D);
        require(protectedBattle.isParticipant(protectedShell), "the protected half's shell is really admitted"
                + diagnostics());
        require(protectedBattle.isParticipant(protectedBody), "the protected body is really admitted"
                + diagnostics());
        require(protectedBattle.sideOf(protectedBody) == protectedBattle.sideOf(protectedShell)
                        && !protectedBattle.opposed(protectedShell, protectedBody),
                "the protected body really shares the pushing shell's side, sides="
                        + protectedBattle.sideOf(protectedBody) + "/" + protectedBattle.sideOf(protectedShell));
        require(!protectedBattle.isParticipant(protectedBystander),
                "the bystander is really outside this encounter");
        require(ShulkerNative.attachFace(protectedShell) == Direction.WEST,
                "the protected half's shell keeps its wall pose, face="
                        + ShulkerNative.attachFace(protectedShell));
        protectedOpening = ShulkerAttachment.of(protectedShell).fullOpenBox(protectedShell);
        protectedSweep = sweepOf(protectedShell);
        protectedShape = relativeShape(protectedShell, protectedSweep);
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF geometry protected shell={} cell={} shape={} opening={} "
                        + "swept={} body={} bystander={}", protectedShell.position(), protectedShell.blockPosition(),
                protectedShape, protectedOpening, protectedSweep, protectedBody.getBoundingBox(),
                protectedBystander.getBoundingBox());
        require(protectedSweep.intersects(protectedBody.getBoundingBox()),
                "the protected body really stands in the volume vanilla's opening sweeps, swept=" + protectedSweep
                        + " body=" + protectedBody.getBoundingBox());
        // Baselines AFTER admission.
        protectedBefore = protectedBody.position();
        protectedBystanderBefore = protectedBystander.position();
        protectedShellBefore = protectedShell.position();
        protectedPeek = -1;
        require(!protectedBattle.isBusy(), "the protected half's encounter is not busy" + diagnostics());
    }

    private static void checkProtectedHalf() {
        protectedAfter = protectedBody.position();
        protectedBystanderAfter = protectedBystander.position();
        protectedShellAfter = protectedShell.position();
        require(protectedBody.isAlive() && protectedAfter.distanceToSqr(protectedBefore) < 1.0E-4D,
                "an admitted body of the shell's own side, standing in the swept cell, is never displaced by that "
                        + "opening, " + protectedBefore + " to " + protectedAfter);
        require(protectedBystander.isAlive()
                        && protectedBystanderAfter.distanceToSqr(protectedBystanderBefore) < 1.0E-4D,
                "a protected bystander is never displaced by that opening, " + protectedBystanderBefore + " to "
                        + protectedBystanderAfter);
        require(protectedShellAfter.distanceToSqr(protectedShellBefore) < 1.0E-4D,
                "the protected half's shell never moved itself, " + protectedShellBefore + " to "
                        + protectedShellAfter);
        require(protectedPeek > MotionRules.SHULKER_PEEK_CLOSED,
                "the window this half measured was really opened by the shell's own attack, seen=" + protectedPeek);
    }

    /**
     * Push half: the same opening geometry, holding an ordinary body of the other side, which the shell's own
     * attack must really displace through the encounter's validated handoff.
     *
     * <p>The opposed body is admitted after the encounter starts and while the shell is still closed, because
     * that is the only moment its own inflated full-open box does not cover the cell beside it.</p>
     */
    private static void beginPushSetUp() {
        BlockPos arena = arena(1);
        Shulker shell = newWallArena(arena);
        BlockPos pillar = pillarOf(arena);
        memberPush = spawnPlayer("proof-a-push", "ProofAPush", arena.offset(7, 0, 7));
        pushShell = shell;
        pushBattle = startBattle(memberPush);
        require(pushBattle.isParticipant(pushShell), "the push half's shell is really admitted" + diagnostics());
        pushBody = spawnOrdinary("proof-pushed", pillar.getX() + 2.5D, arena.getY(), pillar.getZ() + 0.5D);
        require(pushBattle.tryAddFriendly(pushBody), "the opposed ordinary body is really admitted"
                + diagnostics());
        require(pushBattle.opposed(pushShell, pushBody) && !pushBattle.allied(pushShell, pushBody),
                "the pushed body is really opposed to the pushing shell");
        pushOpening = ShulkerAttachment.of(pushShell).fullOpenBox(pushShell);
        pushSweep = sweepOf(pushShell);
        pushShape = relativeShape(pushShell, pushSweep);
        sameOpeningGeometry = pushShape.equals(protectedShape);
        require(sameOpeningGeometry, "the two halves use the very same opening geometry, protected="
                + protectedShape + " push=" + pushShape);
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF geometry push shell={} cell={} shape={} opening={} swept={} "
                + "body={}", pushShell.position(), pushShell.blockPosition(), pushShape, pushOpening, pushSweep,
                pushBody.getBoundingBox());
        require(pushOpening.intersects(pushBody.getBoundingBox()),
                "the pushed body really stands in the native full-open box, opening=" + pushOpening
                        + " body=" + pushBody.getBoundingBox());
        require(pushSweep.intersects(pushBody.getBoundingBox()),
                "the pushed body stands in the very cell the protected body was never moved out of, swept="
                        + pushSweep + " body=" + pushBody.getBoundingBox());
        pushBefore = pushBody.position();
        pushBeforeBox = pushBody.getBoundingBox();
        pushCellBefore = pushBattle.currentActorCell(pushBody);
        pushShellBefore = pushShell.position();
        require(pushCellBefore != null, "the pushed body already holds an authoritative cell");
        require(!pushBattle.isBusy(), "the push half's encounter is not busy" + diagnostics());
    }

    private static void checkPushHalf() {
        pushAfter = pushBody.position();
        pushCellAfter = pushBattle.currentActorCell(pushBody);
        pushShellAfter = pushShell.position();
        require(pushAfter.x > pushBefore.x + 0.02D, "the native opening really displaced the opposed body, x "
                + pushBefore.x + " to " + pushAfter.x + " (it stood in the swept cell) " + diagnostics());
        require(pushCellAfter != null && !pushCellAfter.equals(pushCellBefore),
                "the authoritative cell followed the displacement, " + pushCellBefore + " to " + pushCellAfter);
        require(pushShellAfter.distanceToSqr(pushShellBefore) < 1.0E-4D,
                "the pushing shell itself never moved, " + pushShellBefore + " to " + pushShellAfter);
    }

    private static void checkRetained() {
        pushRetained = pushBody.position();
        require(Math.abs(pushRetained.x - pushAfter.x) < 1.0E-3D
                        && Math.abs(pushRetained.z - pushAfter.z) < 1.0E-3D,
                "the displaced body's exact feet survived the pin and the shot reconciliation, now "
                        + pushRetained + " versus " + pushAfter);
    }

    private static void reportOpeningGroups() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS C1[protected={} to {} kept={} bystander={} to {} "
                        + "kept={} protectedShell={} to {} immovable={} pushed={} to {} box={} cell={} to {} "
                        + "retained={} pushShell={} to {} immovable={} sameGeometry={} shape={} openSeen={}]",
                protectedBefore, protectedAfter, protectedAfter.distanceToSqr(protectedBefore) < 1.0E-4D,
                protectedBystanderBefore, protectedBystanderAfter,
                protectedBystanderAfter.distanceToSqr(protectedBystanderBefore) < 1.0E-4D,
                protectedShellBefore, protectedShellAfter,
                protectedShellAfter.distanceToSqr(protectedShellBefore) < 1.0E-4D,
                pushBefore, pushAfter, pushBeforeBox, pushCellBefore, pushCellAfter, pushRetained,
                pushShellBefore, pushShellAfter, pushShellAfter.distanceToSqr(pushShellBefore) < 1.0E-4D,
                sameOpeningGeometry, protectedShape, protectedPeek);
    }

    // ------------------------------------------------------------------ R: a refused native spawn

    /**
     * A native spawn the world really refuses.
     *
     * <p>The shell's own attack runs its real vanilla spawn; this fixture cancels that spawn at the join event,
     * which is the one place a native projectile can still be refused. A refusal must leave no mark, no tracked
     * flight, no registered projectile and no charge: the entity never existed for this encounter.</p>
     */
    private static void beginRefusedSetUp() {
        BlockPos arena = arena(2);
        memberB = spawnPlayer("proof-b", "ProofB", arena.offset(6, 0, 6));
        refusedShell = spawnShell(arena, Direction.DOWN);
        battleB = startBattle(memberB);
        require(battleB.isParticipant(refusedShell), "the shell of the refused-spawn group is really admitted"
                + diagnostics());
        markedCountBefore = ShulkerBullets.size();
        joinCancelsBefore = joinCancels;
        cancelNextSpawn = true;
        refusedApBefore = battleB.actorAp(refusedShell);
        refusedPaidBefore = battleB.actorPaidActions(refusedShell);
        require(!battleB.isBusy(), "the encounter is not busy when the refused-spawn group starts"
                + diagnostics());
    }

    private static void checkRefusedSpawn() {
        refusedApAfter = battleB.actorAp(refusedShell);
        refusedPaidAfter = battleB.actorPaidActions(refusedShell);
        require(joinCancels > joinCancelsBefore,
                "a real native spawn of the shell's own shot was really refused, cancels=" + joinCancels
                        + " before=" + joinCancelsBefore);
        require(ShulkerBullets.size() == markedCountBefore,
                "a refused native spawn leaves no mark behind, marks=" + ShulkerBullets.size()
                        + " before=" + markedCountBefore);
        require(noBulletInArena(), "a refused native spawn registers no projectile at all" + diagnostics());
        require(refusedPaidAfter == refusedPaidBefore,
                "a refused native spawn is never billed as a paid action, " + refusedPaidBefore + " to "
                        + refusedPaidAfter);
        require(refusedApAfter >= refusedApBefore,
                "a refused native spawn costs no AP, " + refusedApBefore + " to " + refusedApAfter);
        require(!battleB.isBusy(), "no tracked flight outlives a refused native spawn" + diagnostics());
    }

    private static void reportRefused() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS R[cancels={} marks={} bullet={} ap={} to {} paid={} to {} "
                        + "busy={}]",
                joinCancels - joinCancelsBefore, ShulkerBullets.size(), !noBulletInArena(), refusedApBefore,
                refusedApAfter, refusedPaidBefore, refusedPaidAfter, battleB.isBusy());
    }

    // ------------------------------------------------------------------ M: marked shot, refused block impact, exit

    /**
     * A marked native shot, its real block impact cancelled by a later listener, and then its target walking out.
     *
     * <p>The collision is built away from the source shell's own full-open box, so this group never confuses
     * "the shot hit a wall" with "the shell's pose became illegal". The block impact is cancelled by this fixture
     * at {@code LOWEST}, which is exactly the case that must not turn a marked shot into an unmarked one.</p>
     */
    private static void beginMarkedSetUp() {
        BlockPos arena = arena(3);
        // A wall shell, not a floor one: a floor shell's bullet leaves its own body from the middle of the body
        // (its shell grows upward around the spawn point) and can meet its own shooter on the first tick, which
        // this encounter refuses by design. A wall shell's bullet leaves from the open edge, so the shot it fires
        // is really a shot in flight.
        shotShell = newWallArena(arena);
        BlockPos pillar = pillarOf(arena);
        memberC1 = spawnPlayer("proof-c1", "ProofC1", arena.offset(-4, 0, 8));
        battleC = startBattle(memberC1);
        require(battleC.isParticipant(shotShell), "the firing shell is really admitted" + diagnostics());
        shotOpening = ShulkerAttachment.of(shotShell).fullOpenBox(shotShell);
        BlockPos shellCell = shotShell.blockPosition();
        // The bullet of a wall shell steps only in the plane of its own glued face, so the target is placed along
        // that plane and the collision is built across it: a step of the shot's own flight really meets stone.
        memberC2 = spawnPlayer("proof-c2", "ProofC2", new BlockPos(shellCell.getX(), arena.getY(),
                shellCell.getZ() + 8));
        BattleEligibility.Denial denial = battleC.joinDirect(memberC2);
        require(denial == BattleEligibility.Denial.NONE, "the second member really joined, denial=" + denial);
        require(battleC.isMember(memberC1) && battleC.isMember(memberC2), "both members are really in the roster");
        // The shell must spend its shot on the NON-manager member: it targets the nearest opposed player.
        require(memberC2.position().distanceToSqr(shotShell.position())
                        < memberC1.position().distanceToSqr(shotShell.position()),
                "the non-manager member is the nearer target this group needs");
        // A real stone wall the shot's own flight must meet, built before anything is fired and proved away from
        // the shell's opening box: this group never confuses "the shot hit a wall" with "the shell's pose became
        // illegal".
        // A clamp the shot's own flight cannot leave. The earlier shape of this clamp left one vertical chimney
        // open (a fixed dx/dz skip applied to every dy) and had no cap above it, so on a freshly generated world
        // the shell's own bullet climbed out of the gap and the group never met its collision. The cavity is now
        // exactly the cells the shell's own FULL-OPEN box occupies - measured, not assumed - and every other cell
        // of the clamp is stone, including the ones straight above that cavity.
        int placed = 0;
        for (int dx = -1; dx <= 3; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 4; dy++) {
                    BlockPos pos = shellCell.offset(dx, dy, dz);
                    if (cavityCell(pos)) continue;      // the shell's own open window: this is the only air left
                    require(!new AABB(pos).intersects(shotOpening),
                            "no block of this collision stands in the firing shell's own opening box, block=" + pos
                                    + " opening=" + shotOpening);
                    if (blocksMemberCell(pos)) continue;
                    set(pos, Blocks.STONE.defaultBlockState());
                    placed++;
                }
            }
        }
        require(placed > 0, "the collision this group builds really exists in the world");
        // The cavity really is closed: every face of every cell the shell opens into meets stone or another
        // cavity cell, so a shot leaving on real flight has no way out of this clamp at all.
        java.util.List<BlockPos> cavity = new java.util.ArrayList<>();
        for (int dx = -1; dx <= 2; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = shellCell.offset(dx, dy, dz);
                    if (cavityCell(pos)) cavity.add(pos);
                }
            }
        }
        require(cavity.contains(shellCell),
                "the shell's own cell really belongs to the cavity this clamp leaves, cavity=" + cavity);
        for (BlockPos cell : cavity) {
            for (Direction face : Direction.values()) {
                BlockPos neighbour = cell.relative(face);
                if (cavityCell(neighbour)) continue;
                require(!level.getBlockState(neighbour).isAir(),
                        "the clamp really closes the cavity the shell opens into, open neighbour=" + neighbour
                                + " of cavity cell=" + cell + " opening=" + shotOpening);
            }
        }
        require(ShulkerAttachment.legal(level, shotShell, ShulkerAttachment.of(shotShell), null),
                "the firing shell still holds a legal pose inside the clamp this group built, opening="
                        + shotOpening);
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF marked clamp cell={} blocks={} cavity={} opening={} shell={} "
                        + "target={}", shellCell, placed, cavity, shotOpening, shotShell.position(),
                memberC2.position());
        markedBullet = null;
        markedOwner = null;
        markedEpoch = -1;
        blockImpactCancelsBefore = blockImpactCancels;
        markedCountBefore = ShulkerBullets.size();
    }

    /** Whether that cell really belongs to the box the firing shell opens into, measured block by block. */
    private static boolean cavityCell(BlockPos pos) {
        return new AABB(pos).deflate(1.0E-6D).intersects(shotOpening);
    }

    private static void checkCancelledImpact() {
        require(markedBullet != null && !isGone(markedBullet),
                "the marked shot still exists after its real block impact was cancelled");
        require(ShulkerBullets.owns(markedBullet),
                "a cancelled block impact never turns a marked shot into an unmarked one");
        require(blockImpactCancels > blockImpactCancelsBefore,
                "the cancelled impact was a real native block collision, cancels=" + blockImpactCancels
                        + " before=" + blockImpactCancelsBefore);
    }

    private static void leaveTargetAndCheck() {
        require(battleC.isMember(memberC2), "the departing member is still in the roster before it leaves");
        targetDeparted = battleC.leave(memberC2, BattleText.exitReason(BattleText.Exit.FLED));
        require(targetDeparted, "the non-manager member's own exit really happened");
        departureDiscardedShot = isGone(markedBullet);
        departureClearedMark = !ShulkerBullets.owns(markedBullet);
        encounterStayedActive = !battleC.finished() && battleC.isMember(memberC1);
        require(departureDiscardedShot, "the departed target really took its projectile with it");
        require(departureClearedMark, "the departed target really took its mark with it, marks="
                + ShulkerBullets.size());
        require(ShulkerBullets.size() == markedCountBefore, "no other mark was dropped by that exit, marks="
                + ShulkerBullets.size() + " before=" + markedCountBefore);
        require(encounterStayedActive, "the encounter stays active for the member that remains");
    }

    /** The compact trace for a marked shot that never met its stone. */
    private static String markedTrace() {
        return " [attempts=" + markedAttempts + " wait=" + markedWait + " marks=" + ShulkerBullets.size()
                + " owned=" + (markedBullet != null && ShulkerBullets.owns(markedBullet))
                + " bullet=" + (markedBullet == null ? "none" : String.valueOf(markedBullet.position()))
                + " blocks=" + blockImpactCancels + " before=" + blockImpactCancelsBefore
                + " ap=" + battleC.actorAp(shotShell) + " paid=" + battleC.actorPaidActions(shotShell)
                + " peek=" + ShulkerNative.rawPeek(shotShell) + " busy=" + battleC.isBusy() + "]";
    }

    private static void reportMarked() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS M[epoch={} actor={} target={} blockCancels={} "
                        + "stillOwned={} departed={} shotGone={} markGone={} active={} marks={}]",
                markedEpoch, markedOwner == null ? "none" : markedOwner.actorId(),
                markedOwner == null ? "none" : markedOwner.targetId(),
                blockImpactCancels - blockImpactCancelsBefore, true, targetDeparted, departureDiscardedShot,
                departureClearedMark, encounterStayedActive, ShulkerBullets.size());
    }

    // ------------------------------------------------------------------ X: two sessions, one owner leaving

    /**
     * Two live sessions, each with its own marked shot in the air, and one owner leaving the world.
     *
     * <p>The ownership table is per projectile but the cleanup is per actor, so the only honest way to prove the
     * cleanup is scoped is to have another session's shot in flight at the same moment. The mark is also asked
     * whether it can be replaced: a second claim on the same projectile must not move it.</p>
     */
    private static void beginIsolationSetUp() {
        BlockPos arenaD = arena(4);
        memberD = spawnPlayer("proof-d", "ProofD", arenaD.offset(6, 0, 6));
        shellD = spawnShell(arenaD, Direction.DOWN);
        battleD = startBattle(memberD);
        require(battleD.isParticipant(shellD), "the first isolation shell is really admitted" + diagnostics());
        BlockPos arenaE = arena(5);
        memberE = spawnPlayer("proof-e", "ProofE", arenaE.offset(6, 0, 6));
        shellE = spawnShell(arenaE, Direction.DOWN);
        battleE = startBattle(memberE);
        require(battleE.isParticipant(shellE), "the second isolation shell is really admitted" + diagnostics());
        require(battleD != battleE, "the two groups really hold two different encounters");
        bulletD = null;
        bulletE = null;
        ownerDBefore = null;
    }

    private static void checkOwnerCleanup() {
        ShulkerBullets.Owner ownerE = ShulkerBullets.markOf(bulletE);
        ownerShotDiscarded = isGone(bulletD);
        ownerMarkCleared = !ShulkerBullets.owns(bulletD);
        otherShotKept = bulletE != null && !isGone(bulletE);
        otherMarkKept = ownerE != null;
        otherSessionKept = ownerE != null && ownerE.sessionId().equals(battleE.sessionId())
                && !ownerE.sessionId().equals(battleD.sessionId());
        require(ownerShotDiscarded, "an owner that left the world really takes its own shot with it");
        require(ownerMarkCleared, "an owner that left the world really takes its own mark with it");
        require(otherShotKept, "another session's still-flying shot survives that cleanup");
        require(otherMarkKept, "another session's shot keeps its ownership");
        require(otherSessionKept, "the surviving mark really belongs to the other session, mark="
                + (ownerE == null ? "none" : ownerE.sessionId()) + " other=" + battleE.sessionId());
        require(ShulkerBullets.size() == 1, "exactly one mark survives, marks=" + ShulkerBullets.size());
    }

    private static void reportIsolation() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS X[immutable={} ownerShotGone={} ownerMarkGone={} "
                        + "otherShotKept={} otherMarkKept={} otherSession={} marks={}]",
                markImmutable, ownerShotDiscarded, ownerMarkCleared, otherShotKept, otherMarkKept, otherSessionKept,
                ShulkerBullets.size());
        // The two sessions were the evidence; the next group needs the world to itself again.
        endArena(battleE);
        endArena(battleD);
    }

    // ------------------------------------------------------------------ Q: one isolated native replication

    /**
     * One isolated native replication: an open, controlled parent, a real same-family bullet from an opposed
     * member of the same session, and vanilla's own five-attempt relocation producing a child.
     *
     * <p>Vanilla only replicates when {@code hitByShulkerBullet} sees an open shell AND {@code teleportSomewhere()}
     * really succeeds; that call draws five random ±8 offsets and needs an empty, collision-free, attachable cell,
     * so a single flat row of ground makes a real hit miss its candidate about three times in four. This group
     * therefore builds a support-rich neighbourhood, MEASURES how many of the native search's own candidates this
     * arena really offers with vanilla's own predicates, and gives the native event a final point it proves legal,
     * loaded, unoccupied and inside the battlefield before anything is fired. Nothing about the product's five
     * attempts, ±8 search, support gates or RNG is changed.</p>
     */
    private static void beginReplicationSetUp() {
        BlockPos arena = arena(6);
        parentCell = arena;
        memberF = spawnPlayer("proof-q", "ProofQ", arena.offset(0, 0, 9));
        parentShell = spawnShell(arena, Direction.DOWN);
        BlockPos feet = memberF.blockPosition();
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF replication arena={} member={} cell={} below={} feet={} "
                        + "head={} chunk={} shell={}", arena, memberF.position(), feet,
                level.getBlockState(feet.below()), level.getBlockState(feet), level.getBlockState(feet.above()),
                level.hasChunkAt(feet), parentShell.position());
        battleF = startBattle(memberF);
        require(battleF.isParticipant(parentShell), "the replication parent is really admitted"
                + replicationTrace());
        parentOpening = ShulkerAttachment.of(parentShell).fullOpenBox(parentShell);
        // A support-rich neighbourhood, built before anything is fired: every fourth level is a floor and every
        // fourth column a wall, so the native ±8 search has plenty of empty cells that are attachable from one
        // face with their own full-open box free.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                for (int dy = 0; dy <= 8; dy++) {
                    // The parent's whole column stays free: the group drops a real bullet onto it from above.
                    if (dx == 0 && dz == 0) continue;
                    // Pillars give sideways attach faces, one mid-level slab gives overhead ones, and the
                    // parent's own level stays open so the arena is a place a body can really stand in.
                    boolean pillar = dx % 4 == 0;
                    boolean slab = dy == 4;
                    if (!pillar && !slab) continue;
                    BlockPos pos = parentCell.offset(dx, dy, dz);
                    if (new AABB(pos).intersects(parentOpening)) continue;
                    if (blocksMemberCell(pos)) continue;
                    set(pos, Blocks.STONE.defaultBlockState());
                }
            }
        }
        require(ShulkerAttachment.legal(level, parentShell, ShulkerAttachment.of(parentShell), null),
                "the parent still holds a legal pose in the arena this group built");
        // How much of the native search's own ±8 box this arena really offers, asked with vanilla's predicates.
        int candidates = 0;
        int box = 0;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    box++;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos pos = parentCell.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) continue;
                    if (!level.getWorldBorder().isWithinBounds(pos)) continue;
                    if (!level.noCollision(parentShell, new AABB(pos).deflate(1.0E-6D))) continue;
                    if (ShulkerNative.findAttachableSurface(parentShell, pos) == null) continue;
                    candidates++;
                }
            }
        }
        qCandidates = candidates;
        qCandidateBox = box;
        require(candidates * 4 >= box,
                "the arena really offers the native ±8 search a quarter of its box at least, candidates="
                        + candidates + "/" + box);
        // The final point the native event will be re-pointed to: legal, loaded, unoccupied, inside the
        // battlefield, and not the cell the body already holds.
        BlockPos finalCell = null;
        for (int radius = 2; radius <= 6 && finalCell == null; radius++) {
            for (int dx = -radius; dx <= radius && finalCell == null; dx++) {
                for (int dz = -radius; dz <= radius && finalCell == null; dz++) {
                    BlockPos cell = parentCell.offset(dx, 0, dz);
                    if (cell.equals(parentCell)) continue;
                    GridPos grid = new GridPos(cell.getX(), cell.getY(), cell.getZ());
                    if (!ShulkerAttachment.legal(level, parentShell, ShulkerAttachment.at(grid, Direction.DOWN),
                            null)) continue;
                    if (!level.getChunkSource().hasChunk(cell.getX() >> 4, cell.getZ() >> 4)) continue;
                    boolean taken = false;
                    for (net.minecraft.world.entity.LivingEntity participant : battleF.participants()) {
                        GridPos occupied = battleF.currentActorCell(participant);
                        if (grid.equals(occupied)) taken = true;
                    }
                    if (taken) continue;
                    // The battlefield is a cylinder of radius 24 around the founder's cell: this point is well
                    // inside it, and the product's own gate is what finally accepts it.
                    if (Math.abs(cell.getX() - memberF.blockPosition().getX()) > 18
                            || Math.abs(cell.getZ() - memberF.blockPosition().getZ()) > 18) continue;
                    finalCell = cell;
                }
            }
        }
        require(finalCell != null, "a legal, loaded, unoccupied final point exists inside the battlefield");
        replicationTarget = new Vec3(finalCell.getX() + 0.5D, finalCell.getY(), finalCell.getZ() + 0.5D);
        qAttempts = 0;
        qWait = 0;
        qArenaLoaded = false;
        qCleanPasses = 0;
        qOpenPeek = -1;
        qOpenWait = -1;
        nativeChild = null;
        qEventsBefore = replicationEvents;
        parentHealthBefore = parentShell.getHealth();
        parentBefore = parentShell.position();
        require(parentShell.getHealth() > parentShell.getMaxHealth() * 0.5F,
                "the parent starts above half health, so the low-health branch is not the one under test, hp="
                        + parentShell.getHealth());
        require(battleF.actorAp(parentShell) >= 25,
                "the parent can really pay its own reaction, ap=" + battleF.actorAp(parentShell));
        require(!ShulkerNative.holdsAuthorization(parentShell),
                "no stale native relocation permission is open before this group");
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF geometry replication cell={} candidates={}/{} final={} "
                        + "hp={} ap={} opening={}", parentCell, candidates, box, replicationTarget,
                parentShell.getHealth(), battleF.actorAp(parentShell), parentOpening);
    }

    /**
     * One real same-family bullet from the opposed member, on a measured clear approach.
     *
     * <p>The earlier shape of this fixture dropped the bullet from 1.6 blocks above the whole body with a small
     * downward velocity, and on this venue it never established a native hit on the open parent: the measured
     * fact is that the launch did not become an actual collision, not a rule about which way a shot may ever
     * travel. Vanilla's constructor axis only excludes one axis from the FIRST direction choice - later
     * re-selections work from the axis the bullet is really moving on, and at close range it aims at its target -
     * so nothing here is claimed about a permanent flight plane. What this fixture does instead is prove a clear
     * real approach: a straight run of air cells beside the arena's own stone lattice, starting wholly outside
     * the exact full-open box, aimed at a point this fixture proves lies inside that box. Only the starting
     * position and a real initial velocity are prepared - the flight, the collision and the damage are the
     * native ones.</p>
     */
    private static void fireReplicationBullet() {
        AABB body = parentShell.getBoundingBox();
        BlockPos startCell = clearApproach();
        Vec3 start = new Vec3(startCell.getX() + 0.5D, startCell.getY() + 0.5D, startCell.getZ() + 0.5D);
        // The point this approach flies at: the middle of the parent's own cell, one half block up, i.e. inside
        // the box the shell opens into.
        Vec3 aim = parentShell.position().add(0.0D, 0.5D, 0.0D);
        require(parentOpening.contains(aim),
                "the point this approach really flies at lies inside the box the shell opens into, aim=" + aim
                        + " opening=" + parentOpening);
        Vec3 towards = aim.subtract(start);
        require(towards.lengthSqr() > 1.0E-6D, "the approach really has a direction, start=" + start + " aim=" + aim);
        towards = towards.normalize();
        ShulkerBullet bullet = new ShulkerBullet(level, memberF, parentShell, Direction.DOWN.getAxis());
        bullet.setPos(start.x, start.y, start.z);
        bullet.shoot(towards.x, towards.y, towards.z, 0.35F, 0.0F);
        require(!body.intersects(bullet.getBoundingBox()),
                "the bullet really starts outside the parent body, body=" + body + " bullet=" + bullet.position());
        require(!parentOpening.intersects(bullet.getBoundingBox()),
                "the bullet really starts wholly outside the box the shell opens into, opening=" + parentOpening
                        + " bullet=" + bullet.getBoundingBox());
        require(level.addFreshEntity(bullet), "the bullet really joined the world");
        parentHealthBefore = parentShell.getHealth();
        parentBefore = parentShell.position();
        qEventsBefore = replicationEvents;
        require(ShulkerNative.rawPeek(parentShell) > MotionRules.SHULKER_PEEK_CLOSED,
                "the parent is really open at the shot," + replicationTrace());
    }

    /**
     * A short cleared run in the plane the native shot really flies in, ending at the parent's own open box.
     *
     * <p>Read from the live world, not assumed: the cell the bullet starts in and every cell between it and the
     * parent must already be air, and the bullet's own box there must be wholly outside the full-open box.</p>
     */
    private static BlockPos clearApproach() {
        for (Direction side : new Direction[]{Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
            boolean clear = true;
            for (int step = 1; step <= 2 && clear; step++) {
                if (!level.getBlockState(parentCell.relative(side, step)).isAir()) clear = false;
            }
            if (!clear) continue;
            BlockPos start = parentCell.relative(side, 2);
            Vec3 centre = new Vec3(start.getX() + 0.5D, start.getY() + 0.5D, start.getZ() + 0.5D);
            if (parentOpening.contains(centre)) continue;
            return start;
        }
        require(false, "the arena really leaves one clear approach to the box the parent opens into"
                + replicationTrace());
        return parentCell;
    }

    private static Shulker replicationChild() {
        for (Shulker candidate : level.getEntitiesOfClass(Shulker.class, new AABB(parentCell).inflate(48))) {
            if (candidate != parentShell && !isGone(candidate)) return candidate;
        }
        return null;
    }

    private static void checkReplication() {
        parentAfter = parentShell.position();
        parentMoved = parentAfter.distanceToSqr(parentBefore) > 1.0E-4D;
        parentStayedHealthy = parentShell.getHealth() > parentShell.getMaxHealth() * 0.5F;
        authorizationSpent = !ShulkerNative.holdsAuthorization(parentShell);
        childRegistered = nativeChild != null && !isGone(nativeChild);
        childRostered = childRegistered && battleF.isParticipant(nativeChild);
        childSameSide = childRostered && battleF.sideOf(nativeChild) == battleF.sideOf(parentShell);
        childHasSlot = childRostered && battleF.hasTurnSlot(nativeChild);
        childAp = childRostered ? battleF.actorAp(nativeChild) : -1;
        childFreshActions = childRostered && battleF.actorPaidActions(nativeChild) == 0;
        childVariantKept = childRegistered && nativeChild.getVariant().equals(parentShell.getVariant());
        childNativeFlags = childRegistered && nativeChild.isNoAi();
        require(qOpenPeek > MotionRules.SHULKER_PEEK_CLOSED,
                "the parent was really open because its own attack opened it," + replicationTrace());
        require(parentMoved, "the native relocation really moved the parent, " + parentBefore + " to "
                + parentAfter + replicationTrace());
        require(replicationEvents > qEventsBefore,
                "the native relocation entry really ran," + replicationTrace());
        require(parentStayedHealthy, "the parent stayed above half health throughout, hp="
                + parentShell.getHealth());
        require(authorizationSpent, "the one-call native permission was really spent, not left open");
        require(childRegistered, "the native child really joined the world" + replicationTrace());
        require(childRostered, "the native child really joined the encounter" + replicationTrace());
        require(childSameSide, "the child really joined its parent's own side, side="
                + battleF.sideOf(nativeChild) + " parent=" + battleF.sideOf(parentShell));
        require(childHasSlot, "the child really holds its own turn slot");
        require(childAp > 0, "the child really has its own fresh AP, ap=" + childAp);
        require(childFreshActions, "the child really starts with no spent action, paid="
                + battleF.actorPaidActions(nativeChild));
        require(childVariantKept, "the child really keeps the native variant");
        require(childNativeFlags, "the child really joined with the native NoAI flag");
    }

    private static void reportReplication() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS Q[candidates={}/{} attempts={} peek={} hp={} moved={} "
                        + "to {} events={} authorizationSpent={} child={} rostered={} side={} slot={} ap={} "
                        + "fresh={} variant={} noAi={} final={}]",
                qCandidates, qCandidateBox, qAttempts, qOpenPeek, parentShell.getHealth(), parentMoved, parentAfter,
                replicationEvents - qEventsBefore, authorizationSpent, childRegistered, childRostered, childSameSide,
                childHasSlot, childAp, childFreshActions, childVariantKept, childNativeFlags, replicationTarget);
        if (battleF != null && !battleF.finished()) {
            battleF.finish(BattleText.literal("fixture: shulker proof group complete"));
        }
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(spawn).inflate(400))) {
            if (leftover instanceof ServerPlayer) continue;
            leftover.discard();
        }
        replicationTarget = null;
    }

    /** The compact trace the primary asked for when a native replication still does not happen. */
    private static String replicationTrace() {
        return " [attempts=" + qAttempts + " wait=" + qWait + " peek=" + ShulkerNative.rawPeek(parentShell)
                + " hp=" + parentShell.getHealth() + "/" + parentShell.getMaxHealth()
                + " events=" + (replicationEvents - qEventsBefore)
                + " authorized=" + ShulkerNative.holdsAuthorization(parentShell)
                + " moved=" + (parentBefore != null
                        && parentShell.position().distanceToSqr(parentBefore) > 1.0E-4D)
                + " pos=" + parentShell.position()
                + " final=" + replicationTarget
                + " children=" + level.getEntitiesOfClass(Shulker.class, new AABB(parentCell).inflate(48)).size()
                + " ap=" + battleF.actorAp(parentShell) + " paid=" + battleF.actorPaidActions(parentShell)
                + " candidates=" + qCandidates + "/" + qCandidateBox + "]";
    }

    // ------------------------------------------------------------------ H: a relocation adopted while held up

    /**
     * One real paid relocation, adopted while the shell still carries a live levitation hold.
     *
     * <p>The one question is who owns the body after a successful native hand-back. The shell is lifted by its own
     * turn's own rise - the native effect is on the body and the encounter holds it at the exact height the rise
     * reached - and only then does the same own turn buy its one relocation. Vanilla's own entry searches, fires
     * its cancelable event and really moves the body; this group offers that event the destination the case
     * already proved, and then asks the encounter whether the older hold is retired, whether the landed feet and
     * the authoritative cell still agree with the body on the following ticks, and whether the native effect
     * survived the hand-over untouched.</p>
     *
     * <p>Nothing here writes a cell, a seat, a hold or a position to make the case pass: the effect, the arena and
     * the one scoped re-point are fixture SETUP, and the relocation itself is the encounter's ordinary own-turn
     * action.</p>
     */
    private static void beginHoldSetUp() {
        BlockPos arena = arena(3);
        holdOrigin = arena;
        // The platform has to reach where the body is parked, because the landing this group offers stands on the
        // same real floor and a body off the slab would be an unsupported one instead of a floating one.
        for (int dx = -24; dx <= -13; dx++) {
            for (int dz = -4; dz <= 4; dz++) set(arena.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
        }
        holdMember = spawnPlayer("proof-hold", "ProofHold", arena.offset(10, 0, 0));
        // The shell first stands where the encounter's own start can really take it, and is then carried out of
        // vanilla's own twenty-block firing band: only where it really holds decides whether a shot is legal, and
        // that is exactly what makes its own turn buy a relocation instead of a bullet.
        holdShell = spawnShell(arena.offset(4, 0, 0), Direction.DOWN);
        holdBattle = startBattle(holdMember);
        require(holdBattle.isParticipant(holdShell), "the floating shell is really admitted" + holdTrace());
        require(holdBattle.isParticipant(holdMember), "the member is really admitted" + holdTrace());
        require(holdBattle.opposed(holdShell, holdMember),
                "the member is really the shell's opposed target" + holdTrace());
        holdShell.setPos(arena.getX() - 11.5D, arena.getY(), arena.getZ() + 0.5D);
        holdShell.setDeltaMovement(Vec3.ZERO);
        holdShell.fallDistance = 0.0F;
        // Twenty-two blocks apart: beyond the twenty-block shot band, so no shot is legal from where it holds.
        require(holdShell.position().distanceToSqr(holdMember.position()) > (double) MotionRules.SHULKER_SHOT_RANGE_SQ,
                "the shell really holds beyond its own shot band, " + holdShell.position() + " versus "
                        + holdMember.position() + holdTrace());
        // The landing: a legal supported down-face cell inside the band, on this group's own floor and well clear
        // of the member.
        holdLanding = new Vec3(arena.getX() + 0.5D, arena.getY(), arena.getZ() + 0.5D);
        require(ShulkerAttachment.resolveCell(level, holdShell,
                        new GridPos(arena.getX(), arena.getY(), arena.getZ()),
                        cell -> false, cell -> holdBattle.zone().contains(cell)) != null,
                "the landing this group offers is a legal attachment of this battlefield" + holdTrace());
        // The native effect. Its own turn start turns it into one real rise and a live hold; nothing here writes
        // either of those.
        holdShell.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.LEVITATION, 2400, 0));
        require(holdShell.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),
                "the native levitation effect really landed on the body" + holdTrace());
        holdTarget = holdLanding;
        holdEventsBefore = replicationEvents;
        holdFeetBefore = holdShell.position();
        holdAnchor = -1.0D;
        holdApBefore = -1;
        holdAttempts = 0;
        holdNextRiseRead = false;
        holdNextRise = -1.0D;
        if (holdBattle.isPlayerTurnNow()) holdBattle.endPlayerTurn();
    }

    /**
     * Take this group's baseline at the opening of the shell's own turn, and report whether one is in hand.
     *
     * <p>Only the <b>first</b> capture is gated by this unit's own turn and a clean paid-action counter: a turn
     * opening adds recovery and resets that counter, so a baseline taken on the previous turn would make every
     * price claim meaningless. Once a baseline exists the same own turn keeps being observed whatever it has
     * already paid - a paid relocation and its shot would otherwise be invisible to this group - and the rise that
     * same own turn produced is captured here as the live lease's held height, not as a cell centre and not as a
     * written position.</p>
     */
    private static boolean holdOwnTurnBaseline() {
        if (holdBattle == null || holdBattle.finished()) {
            require(false, "the hold group's encounter ended before its own turn" + holdTrace());
        }
        if (holdApBefore >= 0) return true;
        if (holdBattle.isPlayerTurnNow()) {
            holdBattle.endPlayerTurn();
            return false;
        }
        if (!holdBattle.isActorTurn(holdShell)) return false;
        if (holdBattle.actorPaidActions(holdShell) != 0) return false;
        holdApBefore = holdBattle.actorAp(holdShell);
        holdPaidBefore = holdBattle.actorPaidActions(holdShell);
        holdMemberApBefore = holdBattle.actorAp(holdMember);
        holdBulletsBefore = ShulkerBullets.size();
        holdEventsBefore = replicationEvents;
        holdFeetBefore = holdShell.position();
        java.lang.Double lease = holdBattle.heldHeight(holdShell);
        if (lease != null) holdAnchor = lease;
        return true;
    }

    /** The adoption itself, read the moment the body has really moved under its own paid action. */
    private static void checkHoldAdoption() {
        holdAfter = holdShell.position();
        holdCellAfter = holdBattle.currentActorCell(holdShell);
        holdApAfter = holdBattle.actorAp(holdShell);
        holdPaidAfter = holdBattle.actorPaidActions(holdShell);
        holdBulletsAfter = ShulkerBullets.size();
        holdMemberApAfter = holdBattle.actorAp(holdMember);
        holdLeaseAfter = holdBattle.heldHeight(holdShell);
        holdEffectKept = holdShell.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        int teleportPrice = com.tactical.core.combat.ApCalculator.paidActionCost(MotionRules.SHULKER_TELEPORT_COST, 0);
        int shotPrice = com.tactical.core.combat.ApCalculator.paidActionCost(MotionRules.SHULKER_BULLET_COST, 1);
        require(!holdBattle.finished(), "the encounter is still running after the adoption" + holdTrace());
        require(holdBattle.isParticipant(holdShell), "the landed shell is still a participant" + holdTrace());
        // The precondition the case is about: a real own-turn rise of the native effect, held at its exact height.
        require(holdAnchor >= 0.0D, "the shell's own turn really produced a live levitation hold, anchor="
                + holdAnchor + holdTrace());
        require(Math.abs(holdAnchor - holdFeetBefore.y) < 0.01D,
                "the hold is the rise of this own turn and nothing else, anchor=" + holdAnchor + " feet="
                        + holdFeetBefore + holdTrace());
        require(holdFeetBefore.y > holdOrigin.getY() + 0.99D,
                "the body was really floating above its parked cell before the relocation, feet="
                        + holdFeetBefore + holdTrace());
        require(replicationEvents > holdEventsBefore,
                "the relocation really drove vanilla's own cancelable entry, events="
                        + (replicationEvents - holdEventsBefore) + holdTrace());
        require(Math.abs(holdAfter.x - holdLanding.x) < 1.0E-6D && Math.abs(holdAfter.y - holdLanding.y) < 1.0E-6D
                        && Math.abs(holdAfter.z - holdLanding.z) < 1.0E-6D,
                "the native relocation really landed the body on the point the case proved, " + holdAfter
                        + " expected " + holdLanding + holdTrace());
        require(holdCellAfter != null && holdCellAfter.equals(new GridPos(
                        net.minecraft.util.Mth.floor(holdLanding.x),
                        net.minecraft.util.Mth.floor(holdLanding.y + 1.0E-7D),
                        net.minecraft.util.Mth.floor(holdLanding.z))),
                "the authoritative cell floors the real landed body, cell=" + holdCellAfter + holdTrace());
        // The fact under test: one owner. The older hold must be retired by the adoption itself, because the next
        // motion tick would otherwise drag the body back to the anchor it just left.
        require(holdLeaseAfter == null,
                "the older levitation hold is retired by the adoption itself, lease=" + holdLeaseAfter
                        + " body=" + holdAfter + holdTrace());
        require(holdEffectKept, "the native effect survives the hand-over untouched" + holdTrace());
        require(holdPaidAfter >= 1 && holdPaidAfter <= 2,
                "the own turn bought the relocation and at most the one shot it was for, paid=" + holdPaidAfter
                        + holdTrace());
        require(holdApBefore - holdApAfter == teleportPrice + (holdPaidAfter - 1) * shotPrice,
                "the relocation costs exactly its own price, spent=" + (holdApBefore - holdApAfter)
                        + " expected=" + (teleportPrice + (holdPaidAfter - 1) * shotPrice)
                        + " (teleport=" + teleportPrice + " shot=" + shotPrice + ")" + holdTrace());
        require(holdBulletsAfter - holdBulletsBefore == holdPaidAfter - 1,
                "only the one bought shot produced a projectile, bullets=" + holdBulletsBefore + " to "
                        + holdBulletsAfter + holdTrace());
        require(holdMemberApAfter >= holdMemberApBefore,
                "the member is never debited by the shell's own relocation, ap=" + holdMemberApBefore
                        + " to " + holdMemberApAfter + holdTrace());
    }

    private static void reportHold() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS H[anchor={} feetBefore={} landing={} body={} cell={} "
                        + "leaseAfter={} effectKept={} kept={} stableTicks={} paid={} to {} ap={} to {} memberAp={} "
                        + "to {} events={} bullets={} to {} attempts={} nextRise={} nextFeet={}]",
                holdAnchor, holdFeetBefore, holdLanding, holdAfter, holdCellAfter, holdLeaseAfter, holdEffectKept,
                holdKept, holdStableTicks, holdPaidBefore, holdPaidAfter, holdApBefore, holdApAfter,
                holdMemberApBefore, holdMemberApAfter, replicationEvents - holdEventsBefore, holdBulletsBefore,
                holdBulletsAfter, holdAttempts, holdNextRise, holdNextFeet);
        holdTarget = null;
    }

    private static String holdTrace() {
        return " [holder attempts=" + holdAttempts + " stage=" + stage + " held=" + held
                + " body=" + (holdShell == null ? "none" : holdShell.position())
                + " lease=" + (holdBattle == null || holdShell == null ? "none"
                : String.valueOf(holdBattle.heldHeight(holdShell)))
                + " ap=" + (holdBattle == null || holdShell == null ? -1 : holdBattle.actorAp(holdShell))
                + " paid=" + (holdBattle == null || holdShell == null ? -1
                : holdBattle.actorPaidActions(holdShell))
                + " events=" + (replicationEvents - holdEventsBefore)
                + " landing=" + holdLanding + "]";
    }

    // ------------------------------------------------------------------ helpers

    /** One throwaway platform per group, far enough apart that no encounter's battlefield reaches another's. */
    private static BlockPos arena(int index) {
        BlockPos center = new BlockPos(spawn.getX() + 80 * index, spawn.getY() + 40, spawn.getZ());
        // A dedicated server only generates the spawn area, so every arena's own columns are really loaded first:
        // a platform written into an unloaded chunk would leave the group standing on nothing.
        for (int cx = (center.getX() - 16) >> 4; cx <= (center.getX() + 16) >> 4; cx++) {
            for (int cz = (center.getZ() - 16) >> 4; cz <= (center.getZ() + 16) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        // The platform is cut out of whatever terrain stands there: an arena is a flat, open place at a known
        // height, not a slab laid under a hill.
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                for (int dy = 0; dy <= 8; dy++) {
                    set(center.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
                set(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
            }
        }
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(center).inflate(30))) {
            if (!(leftover instanceof ServerPlayer)) leftover.discard();
        }
        return center;
    }

    private static void set(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        level.setBlock(pos, state, 3);
    }

    private static boolean blocksMemberCell(BlockPos pos) {
        return !level.getEntitiesOfClass(ServerPlayer.class, new AABB(pos)).isEmpty();
    }

    private static ServerPlayer spawnPlayer(String key, String name, BlockPos at) {
        var profile = new com.mojang.authlib.GameProfile(
                UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),
                player, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        player.setPos(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        player.setHealth(200);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 64));
        level.addNewPlayer(player);
        return player;
    }

    /**
     * Remove shells this run never made: a dedicated server hands back the entities an earlier run saved in the
     * very cells these arenas reuse, one tick after their chunk arrives. A shell that belongs to a live encounter
     * (a native child really adopted by it) is never a stray, and neither is one this run spawned itself.
     */
    private static void purgeStrays() {
        for (int i = 0; i <= 6; i++) {
            BlockPos center = new BlockPos(spawn.getX() + 80 * i, spawn.getY() + 40, spawn.getZ());
            for (Shulker stray : level.getEntitiesOfClass(Shulker.class, new AABB(center).inflate(28))) {
                if (ownedShells.contains(stray.getUUID())) continue;
                if (BattleManager.containing(stray) != null) continue;
                stray.discard();
                staleShells++;
            }
            // An earlier run's member is saved with the world too, and a body with no connection can never act:
            // it would still sit in this group's encounter and take one of its turns. It is discarded only when
            // no encounter holds it, exactly like a stray shell.
            for (net.minecraft.server.level.ServerPlayer saved
                    : level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                    new AABB(center).inflate(28))) {
                if (saved.connection != null) continue;
                if (BattleManager.containing(saved) != null) continue;
                saved.discard();
                staleShells++;
            }
        }
    }

    private static Shulker spawnShell(BlockPos cell, Direction face) {
        Shulker mob = (Shulker) EntityType.SHULKER.create(level);
        require(mob != null, "a shulker exists in this version");
        ownedShells.add(mob.getUUID());
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        mob.setPos(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
        ShulkerNative.setAttachFace(mob, face);
        require(level.addFreshEntity(mob), "the shell really joined the world");
        return mob;
    }

    private static Mob spawnOrdinary(String key, double x, double y, double z) {
        net.minecraft.world.entity.monster.Zombie mob =
                (net.minecraft.world.entity.monster.Zombie) EntityType.ZOMBIE.create(level);
        require(mob != null, "an ordinary body exists in this version");
        mob.setNoAi(true);
        mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        mob.setHealth(200);
        mob.setPos(x, y, z);
        require(level.addFreshEntity(mob), "the ordinary body really joined the world: " + key);
        return mob;
    }

    private static ServerBattle startBattle(ServerPlayer player) {
        require(BattleManager.start(player, true), "the encounter starts" + diagnostics());
        ServerBattle started = BattleManager.findFor(player.getUUID());
        require(started != null && started.isMember(player), "the encounter exists and holds its founder");
        return started;
    }

    /** End the player's turn of every live encounter of this group exactly once, and report whether any is acting. */
    private static boolean driveAll() {
        boolean acting = drive(protectedBattle);
        acting = drive(pushBattle) | acting;
        acting = drive(battleB) | acting;
        acting = drive(battleC) | acting;
        acting = drive(battleD) | acting;
        acting = drive(battleE) | acting;
        return acting;
    }

    /** Ask for one more own turn of that encounter inside the same stage. */
    private static void redrive(ServerBattle battle) {
        turnEnded.remove(battle);
        enemyPhaseSeen.remove(battle);
        drive(battle);
    }

    private static boolean drive(ServerBattle battle) {
        if (battle == null || battle.finished()) return false;
        if (battle.isPlayerTurnNow()) {
            // The enemy phase this group needed has really run: it is the member's turn again.
            if (Boolean.TRUE.equals(turnEnded.get(battle)) && Boolean.TRUE.equals(enemyPhaseSeen.get(battle))) {
                return false;
            }
            battle.endPlayerTurn();
            turnEnded.put(battle, Boolean.TRUE);
            return true;
        }
        // Before the member ever held a turn (the deploy transition) nothing was ended yet, so this is not the
        // enemy phase the group is waiting for.
        if (Boolean.TRUE.equals(turnEnded.get(battle))) enemyPhaseSeen.put(battle, Boolean.TRUE);
        return true;
    }

    private static ShulkerBullet ownedBulletOf(UUID actorId, ShulkerBullet current) {
        if (current != null && !isGone(current) && ShulkerBullets.owns(current)) return current;
        for (ShulkerBullet bullet : level.getEntitiesOfClass(ShulkerBullet.class, new AABB(spawn).inflate(400))) {
            ShulkerBullets.Owner owner = ShulkerBullets.markOf(bullet);
            if (owner != null && owner.actorId().equals(actorId)) return bullet;
        }
        return null;
    }

    private static boolean noBulletInArena() {
        return level.getEntitiesOfClass(ShulkerBullet.class, new AABB(spawn).inflate(400)).isEmpty();
    }

    private static boolean isGone(Entity entity) {
        return entity == null || entity.isRemoved() || level.getEntity(entity.getId()) != entity;
    }

    private static void endArena(ServerBattle battle) {
        if (battle != null && !battle.finished()) {
            battle.finish(BattleText.literal("fixture: shulker proof group complete"));
        }
        for (Entity leftover : level.getEntitiesOfClass(Entity.class, new AABB(spawn).inflate(400))) {
            if (leftover instanceof ServerPlayer) continue;
            leftover.discard();
        }
        require(ShulkerBullets.size() == 0, "no mark outlives its group, marks=" + ShulkerBullets.size());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String diagnostics() {
        return " [stage=" + stage + " ticks=" + ticks + " held=" + held + " marks=" + ShulkerBullets.size()
                + " cancels=" + joinCancels + " blocks=" + blockImpactCancels + "]";
    }

    private static void complete() {
        Tactical.LOGGER.info("[Tactical] SHULKER PROOF PASS");
        server.halt(false);
    }

    /** Kept so a fixture that never ran cannot look like a pass in a log search. */
    static {
        if (Boolean.getBoolean("tactical.selftest.verbose")) {
            Tactical.LOGGER.info("[Tactical] shulker proof fixture class loaded, gate={}",
                    System.getProperty("tactical.selftest"));
        }
    }
}
