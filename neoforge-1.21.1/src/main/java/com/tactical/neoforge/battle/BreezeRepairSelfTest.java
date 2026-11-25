package com.tactical.neoforge.battle;

import com.tactical.core.combat.BreezeRules;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The focused repair fixture of the breeze family: {@code -Dtactical.selftest=breeze_repair}.
 *
 * <p>Every group answers one of the six findings of the phase-C review on a real dedicated server, from real
 * entities, real projectiles, real explosions and the encounter's real books - never from a client mirror and never
 * from a helper called directly in place of the native seam it stands for:</p>
 *
 * <ul>
 *   <li><b>H</b> direct wind-charge impact: a marked charge really strikes its locked target for the native single
 *       point of direct damage, a marked charge really driven into the firing body's own side is refused before the
 *       native hurt, and a native spawn the world really refuses costs nothing at all;</li>
 *   <li><b>X</b> burst scope: a nested unmarked explosion inside a marked burst is not filtered by the outer frame,
 *       and a callback that throws leaves no armed frame and no captured data behind;</li>
 *   <li><b>K</b> block interaction: a marked burst reaches no block callback at all while the unmarked control in
 *       the same geometry really opens the same door and presses the same button, with the gust particles and the
 *       interaction kind unchanged;</li>
 *   <li><b>W</b> the shot barrier: a real long-band shot is still in the air when the native recovery ends, the
 *       turn is still the breeze's, and the queue advances exactly once when the shot really resolves;</li>
 *   <li><b>E</b> a firing body that leaves inside its own burst callback: the same exact burst refuses every body
 *       afterwards and the departure takes only its own shot with it;</li>
 *   <li><b>T</b> a locked non-manager member that leaves inside the direct hit of its own marked charge: the charge
 *       is really unmarked and removed there, its owned origin survives that call, and the burst that still executes
 *       on that stack is deny-all - no hurt, no push, no block callback - while an unmarked control in the same
 *       geometry still reaches its blocks;</li>
 *   <li><b>P</b> a real ordinary living body across the exact first swept leg of a pending native jump: the arc is
 *       read before its first frame, and the jump is refused without being paid for, with the pose and the busy
 *       state given back.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BreezeRepairSelfTest {

    private static final int FLOOR_MIN_X = -8;
    private static final int FLOOR_MAX_X = 40;
    private static final int FLOOR_MIN_Z = -24;
    private static final int FLOOR_MAX_Z = 24;

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static int[] plan = new int[0];
    private static int planIndex;
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

    // ---- H: direct impact ----------------------------------------------------------------------------------
    private static Breeze striker;
    private static Zombie strikerAlly;
    private static BreezeWindCharge liveCharge;
    private static LivingEntity lockedTarget;
    private static int directEvents;
    private static float directRaw;
    private static int appliedEvents;
    private static float appliedAmount;
    private static int burstEvents;
    private static int otherIncoming;
    private static String otherIncomingTag = "none";
    private static int chargeJoins;
    private static boolean cancelNextCharge;
    private static int strikerApBefore;
    private static int strikerPaidBefore;
    private static float strikeHealthBefore;
    private static float allyHealthBefore;
    private static boolean directHitDone;
    /** The measured facts of that one direct hit, frozen at the moment it was judged. */
    private static int directAtCheck;
    private static float directRawAtCheck;
    private static float appliedAtCheck;
    private static float dropAtCheck;
    /**
     * The tick from which a joined charge is the one a scenario really uses.
     *
     * <p>A freshly started encounter is still finishing its own deployment transition, and a charge fired on its
     * very first tick meets a body whose damage is still answered by that transition - measured directly: the same
     * charge's native hit left the target at exactly the same health and with no damage immunity consumed, while the
     * next charge one turn later really applied its point. A proof about a direct hit therefore selects a charge
     * fired after that window instead of racing it, and records the window it used.</p>
     */
    private static int chargeSettleAt;
    /**
     * Wind-charge direct hits this encounter has really applied to the watched body so far.
     *
     * <p>Measured, not assumed: the first wind-charge hit of a freshly started encounter reaches the native callback
     * and leaves the target at exactly the same health with no damage immunity consumed ({@code hp=100 -> 100,
     * iframe=0 -> 0}), while the next one applies exactly its point and sets {@code iframe=20}. No damage event of any
     * kind is fired for that first hit and no product guard is involved, so the cause is not attributed here. What a
     * proof about a direct hit needs is a hit the body really accepted, so a scenario waits for one measured applied
     * hit before it picks the charge it will measure.</p>
     */
    private static int warmHits;
    /** The one breeze whose own shot the current group watches; {@code null} when the group watches none. */
    private static Breeze watchedOwner;
    private static boolean refusalDone;
    private static boolean cancelledSpawnDone;
    private static int cancelledFlightAfter;
    private static int cancelledMarksAfter;
    private static int cancelledApAfter;
    private static int cancelledPaidAfter = -1;
    private static int cancelledPaidAtRefusal = -1;
    private static int cancelledApAtRefusal = -1;
    private static int cancelledEpochAtRefusal = -1;
    private static boolean cancelledTurnRolled;
    private static int hStage;
    private static int hDeadline;
    private static int hProbe;

    // ---- X: burst scope ------------------------------------------------------------------------------------
    private static Breeze burster;
    private static BreezeWindCharge xCharge;
    private static BreezeWindCharge ordinaryCharge;
    private static int ordinaryToBlow = -1;
    private static Zombie burstAlly;
    private static BlockPos burstAnchor;
    private static Explosion outerExplosion;
    private static boolean outerProtects;
    private static boolean nestedUnfiltered;
    private static boolean nestedSeen;
    private static boolean throwArmed;
    private static boolean throwDone;
    private static boolean outerProtectsAfterNested;
    private static String xTupleBefore = "none";
    private static String xTupleAfter = "none";
    private static boolean sentinelCaught;
    private static boolean sentinelFrameArmed;
    private static boolean sentinelExplosionNamed;
    private static int sentinelCaptured = -1;
    private static boolean sentinelLeftNoFrame;
    private static int sentinelMarksBefore = -1;
    private static int sentinelMarksAfter = -1;
    private static boolean sentinelOrdinaryNative;
    private static int sentinelOrdinaryToBlow = -1;
    private static BreezeWindCharge sentinelOrdinary;
    private static BlockPos xControlDoorPos;
    private static boolean xControlDoorBefore;
    private static boolean xControlDoorAfter;
    private static boolean allyWasParticipant;
    private static boolean armedAfterReturn;
    private static boolean capturedAfterReturn;
    private static int marksAfterReturn;
    private static int xStage;
    private static int xDeadline;

    // ---- T: a marked charge whose locked target leaves inside its own direct-hit callback --------------------
    private static Breeze exitBreeze;
    private static BreezeWindCharge exitCharge;
    private static BreezeWindCharge exitWild;
    private static LivingEntity exitTarget;
    private static boolean exitInsideCallback;
    private static boolean exitMarkBeforePresent;
    private static boolean exitMarkAfterRemoved;
    private static boolean exitOriginRetained;
    private static boolean exitMemberLeft;
    private static boolean exitManagerKept;
    private static boolean exitTargetStillInWorld;
    private static boolean exitBurstArmed;
    private static boolean exitBurstProtectsDeparted;
    private static boolean exitBurstProtectsManager;
    private static int exitBurstToBlow = -1;
    private static int exitBurstCallbacks = -1;
    private static boolean exitFrameCleared;
    private static boolean exitOriginCleared;
    private static int exitManagerIncoming;
    private static float exitManagerHpBefore;
    private static float exitManagerHpAfter;
    private static Vec3 exitManagerVelocityBefore;
    private static Vec3 exitManagerVelocityAfter;
    private static float exitDepartedHpBefore;
    private static float exitDepartedHpAfter;
    private static Vec3 exitDepartedVelocityBefore;
    private static Vec3 exitDepartedVelocityAfter;
    private static int exitWildToBlow = -1;
    private static float exitWildBodyHpBefore;
    private static float exitWildBodyHpAfter;
    private static BlockPos exitDoorPos;
    private static boolean exitDoorBefore;
    private static boolean exitDoorAfterMarked;
    private static boolean exitDoorAfterWild;
    private static int tStage;
    private static int tDeadline;

    // ---- P: the first jump leg refuses a real body before the jump is paid for ------------------------------
    private static Breeze arcJumper;
    private static Vec3 arcStart;
    private static Vec3[] arcFrames = new Vec3[0];
    private static int arcFramesAtBlock = -1;
    private static int arcEpochAtBlock = -1;
    private static int arcApAtBlock = -1;
    private static int arcPaidAtBlock = -1;
    private static boolean arcLegsHeld;
    private static boolean arcBodyInFirstLeg;
    private static boolean arcBodyOnlyInFirstLeg;
    private static boolean arcRefusedFree;
    private static int arcApAfter = -1;
    private static int arcPaidAfter = -1;
    private static double arcMovedAfter = -1.0D;
    private static boolean arcPoseClean;
    private static boolean arcNotBusy;
    private static int pStage;
    private static int pDeadline;

    // ---- K: block interaction ------------------------------------------------------------------------------
    private static Breeze blocker;
    private static BreezeWindCharge markCharge;
    private static BreezeWindCharge controlCharge;
    private static BlockPos doorPos;
    private static BlockPos buttonPos;
    private static boolean doorBefore;
    private static boolean buttonBefore;
    private static boolean markedDoorChanged;
    private static boolean markedButtonChanged;
    private static int markedCallbacks;
    private static int markedToBlow;
    private static boolean markedTriggerKept;
    private static ParticleOptionsSeen markedParticles = ParticleOptionsSeen.NONE;
    private static boolean controlDoorChanged;
    private static boolean controlButtonChanged;
    private static int noBlockInteraction;
    private static int kStage;
    private static int kDeadline;

    /** What the marked explosion's own particle and interaction facts really were. */
    private enum ParticleOptionsSeen { NONE, LARGE_GUST, SMALL_GUST, OTHER }

    // ---- W: the shot barrier --------------------------------------------------------------------------------
    private static Breeze barrier;
    private static int barrierTurns;
    private static int actorFlipTicks;
    private static boolean stillActingAfterRecovery;
    private static boolean shotPendingAfterRecovery;
    private static int ownerApAtRecovery;
    private static int ownerApAfterHandBack;
    private static int ownerOwnTurnsAtRecovery = -1;
    private static int ownerOwnTurnsAfterHandBack = -1;
    private static boolean barrierResolved;
    private static int wStage;
    private static int wDeadline;

    // ---- E: a firing body that leaves inside its own burst callback -----------------------------------------
    private static Breeze leaver;
    private static Zombie extraHostile;
    private static Cow arcBody;
    private static BreezeWindCharge leaverCharge;
    private static BreezeWindCharge eCharge;
    private static LivingEntity eMember;
    private static boolean exitProtectsBefore;
    private static boolean exitProtectsAfter;
    private static boolean exitRan;
    private static float exitMemberBefore;
    private static float exitMemberAfter;
    private static boolean chargeGoneWithActor;
    private static float memberHealthBefore;
    private static float memberHealthAfter;
    private static int reflectionsAfterActorExit = -1;
    private static int eStage;
    private static int eDeadline;

    /** The stage number of every group this run really executes, in order. */
    private static int[] plannedGroups() {
        java.util.List<Integer> stages = new java.util.ArrayList<>(8);
        if (group("H")) stages.add(2);
        if (group("X")) stages.add(3);
        if (group("K")) stages.add(4);
        if (group("W")) stages.add(5);
        if (group("E")) stages.add(6);
        if (group("T")) stages.add(7);
        if (group("P")) stages.add(8);
        int[] answer = new int[stages.size()];
        for (int index = 0; index < stages.size(); index++) answer[index] = stages.get(index);
        return answer;
    }

    /** Begin the group that stage number belongs to; every begin does its own arena reset and encounter start. */
    private static void enter(int groupStage) {
        switch (groupStage) {
            case 2 -> beginDirectImpact();
            case 3 -> beginBurstScope();
            case 4 -> beginBlockInteraction();
            case 5 -> beginShotBarrier();
            case 6 -> beginPartialExit();
            case 7 -> beginTargetExit();
            case 8 -> beginPrelaunchArc();
            default -> throw new IllegalStateException("unknown repair group stage " + groupStage);
        }
    }

    /** Finish the current group and either begin the next planned one or end the run. */
    private static void advanceFrom(int groupStage) {
        endScenario();
        planIndex++;
        while (planIndex < plan.length) {
            int next = plan[planIndex];
            if (next == groupStage) {
                planIndex++;
                continue;
            }
            enter(next);
            stage = next;
            wait = 0;
            return;
        }
        stage = 12;
    }

    /** Which groups this run really executes: {@code -Dtactical.repair.groups=H,X,K,W,E}, all by default. */
    private static boolean group(String key) {
        String requested = System.getProperty("tactical.repair.groups");
        if (requested == null || requested.isBlank()) return true;
        for (String entry : requested.split(",")) {
            if (entry.trim().equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    /** Marks a skipped group as skipped without inventing a pass for it. */
    private static void skipTo(int nextStage) {
        Tactical.LOGGER.info("[Tactical] breeze-repair skipping groups before stage {}", nextStage);
    }

    /** The one deliberate failure a fixture may throw out of a real native callback and catch at its own boundary. */
    private static final class CallbackSentinel extends IllegalStateException {
        CallbackSentinel(String message) {
            super(message);
        }
    }

    private static final CallbackSentinel SENTINEL = new CallbackSentinel("breeze-repair: callback sentinel");

    private BreezeRepairSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "breeze_repair".equals(System.getProperty("tactical.selftest"));
        if (!enabled) return;
        server = event.getServer();
    }

    /** Every real wind charge this fixture's breezes really created. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!enabled || level == null || event.getLevel() != level) return;
        if (!(event.getEntity() instanceof BreezeWindCharge charge)) return;
        if (cancelNextCharge && charge.getOwner() instanceof Breeze isBreeze && isBreeze == striker) {
            // A native spawn the world refuses: exactly what a cancelled join event is. The books are read right
            // here, on the same stack as the refusal, so no later turn-start refresh can be mistaken for a cost.
            cancelNextCharge = false;
            cancelledApAtRefusal = battle == null ? -1 : battle.actorAp(striker);
            cancelledEpochAtRefusal = battle == null ? -1 : battle.fixtureOwnTurns(striker.getUUID());
            cancelledPaidAtRefusal = battle == null ? -1 : battle.actorPaidActions(striker);
            cancelledMarksAfter = BreezeWindCharges.size();
            cancelledSpawnDone = true;
            event.setCanceled(true);
            Tactical.LOGGER.info("[Tactical] breeze-repair H cancelled native charge spawn");
            return;
        }
        chargeJoins++;
        if (watchedOwner != null && charge.getOwner() == watchedOwner && ticks >= chargeSettleAt) liveCharge = charge;
        // The nested control has the same native owner but is not the group's owned outer shot.
        // Freeze the outer identity; a reentrant join must not redirect the outer listener to its own control.
        if (charge.getOwner() == burster && xCharge == null) xCharge = charge;
        if (charge.getOwner() == leaver) {
            leaverCharge = charge;
            eCharge = charge;
        }
        // While group T is still warming up, the current shot is the candidate: the one it measures is frozen the
        // moment it starts measuring (tStage 1) and is never re-pointed after that.
        if (charge.getOwner() == exitBreeze && tStage == 0 && ticks >= chargeSettleAt) exitCharge = charge;
        if (charge.getOwner() == blocker) markCharge = charge;
    }

    /**
     * The real facts of every marked and control explosion this fixture watches.
     *
     * <p>Three experiments run inside native callbacks here:</p>
     *
     * <ul>
     *   <li><b>X nesting</b> - inside the marked burst of the breeze's own shot, an unmarked charge's own explosion
     *       really runs and the encounter's burst scope is asked about the same protected body before and after;</li>
     *   <li><b>X exception</b> - the same marked charge's own native {@code explode} is invoked again through a
     *       fixture-guarded handle; this callback throws one unique sentinel out of it, which is the exact case a
     *       {@code RETURN} injector could not clean;</li>
     *   <li><b>T exit</b> - the marked charge's burst of group T is asked whether it still narrows anything, after
     *       the body whose hurt it was fired at really left inside that same direct-hit call.</li>
     * </ul>
     */
    @SubscribeEvent
    public static void onDetonate(ExplosionEvent.Detonate event) {
        if (!enabled || level == null || event.getLevel() != level) return;
        Explosion explosion = event.getExplosion();
        Entity source = explosion.getDirectSourceEntity();
        if (DeveloperTrace.enabled()) {
            // One compact line per real explosion: what produced it and what its own block list really was.
            Tactical.LOGGER.info("[Tactical] breeze-repair detonate source={} blocks={} entities={} group={}/{}",
                    source == null ? "none" : source.getType() + "#" + source.getId(),
                    event.getAffectedBlocks().size(), event.getAffectedEntities().size(), xStage, tStage);
        }
        if (source == sentinelOrdinary && sentinelOrdinary != null) {
            sentinelOrdinaryToBlow = event.getAffectedBlocks().size();
            return;
        }
        if (source == ordinaryCharge && ordinaryCharge != null) {
            // A charge no encounter fired: its own block list must be exactly what vanilla computed.
            ordinaryToBlow = event.getAffectedBlocks().size();
            return;
        }
        if (source == exitWild && exitWild != null) {
            // The untouched native control of group T: an unmarked charge bursting in the very geometry the marked
            // one just used. Its own block callbacks are what a wild burst really reaches.
            exitWildToBlow = event.getAffectedBlocks().size();
            return;
        }
        if (source == exitCharge && exitCharge != null) {
            // The marked burst of a charge whose locked target left inside that charge's own direct-hit call. The
            // question is only what this exact explosion is still allowed to do once the encounter revoked the shot.
            exitBurstArmed = BreezeBurst.armed();
            exitBurstToBlow = event.getAffectedBlocks().size();
            exitBurstProtectsDeparted = BreezeBurst.protects(explosion, exitTarget);
            exitBurstProtectsManager = BreezeBurst.protects(explosion, owner);
            return;
        }
        if (source == eCharge && eCharge != null) {
            // A real owned burst whose own source leaves INSIDE the native callback. The already-running native
            // method cannot be cancelled by a discard, so the question is only whether this exact burst is still
            // allowed to touch anybody once the body that fired it is gone.
            exitRan = true;
            exitProtectsBefore = BreezeBurst.protects(explosion, eMember);
            leaver.discard();
            exitProtectsAfter = BreezeBurst.protects(explosion, eMember);
            exitMemberBefore = eMember.getHealth();
            Tactical.LOGGER.info("[Tactical] breeze-repair E exit-in-callback member={} protectsBefore={} "
                    + "protectsAfter={} memberHp={}", eMember.getUUID(), exitProtectsBefore, exitProtectsAfter,
                    eMember.getHealth());
            return;
        }
        boolean blockGroup = source == markCharge && markCharge != null;
        boolean scopeGroup = source == xCharge && xCharge != null;
        if (!blockGroup && !scopeGroup) return;
        if (blockGroup) {
            blockFacts(explosion, event);
            return;
        }
        if (throwArmed) {
            // The exception experiment: the real native explode was invoked synchronously at this fixture's own
            // boundary, the frame and its explosion are really bound here, and this throw is the one failure it is
            // meant to survive. Only the fixture's own boundary catches it, and only this sentinel counts.
            sentinelFrameArmed = BreezeBurst.armed();
            BreezeWindCharge armedCharge = xCharge;
            sentinelExplosionNamed = armedCharge != null && explosion.getDirectSourceEntity() == armedCharge;
            sentinelCaptured = BreezeBurst.captured().size();
            throwDone = true;
            throw SENTINEL;
        }
        outerExplosion = explosion;
        // The protected body is asked about inside the same native call, while the frame really is armed.
        xTupleBefore = burstTuple(explosion);
        outerProtects = BreezeBurst.protects(explosion, burstAlly);
        if (!nestedSeen && burster != null && burstAlly != null && burstAnchor != null) {
            // A nested, unmarked native explosion inside the marked one: it must not inherit the outer frame.
            BreezeWindCharge nested = new BreezeWindCharge(burster, level);
            nested.setPos(burstAnchor.getX() + 0.5D, burstAnchor.getY() + 0.6D, burstAnchor.getZ() + 0.5D);
            nested.shoot(0.0D, -0.25D, 0.0D, BreezeRules.SHOT_SPEED, 0.0F);
            require(level.addFreshEntity(nested), "the nested unmarked charge joined the world");
            // The nested charge has served its purpose once its own explosion really ran: leaving it in the world
            // would let it burst again later, in the middle of another group's geometry.
            nested.discard();
            // The exact native breeze path: the wind charge's own damage calculator, radius and particles. A
            // different calculator would hurt or move the very body this question is about, which is not the seam
            // under test.
            Explosion inner = level.explode(nested, null,
                    net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge.EXPLOSION_DAMAGE_CALCULATOR,
                    burstAnchor.getX() + 0.5D, burstAnchor.getY() + 0.6D, burstAnchor.getZ() + 0.5D,
                    3.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.TRIGGER,
                    ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE,
                    net.minecraft.sounds.SoundEvents.BREEZE_WIND_CHARGE_BURST);
            nestedSeen = inner != null;
            nestedUnfiltered = inner != null && !BreezeBurst.protects(inner, burstAlly);
            allyWasParticipant = battle != null && burstAlly != null && battle.participants().contains(burstAlly);
            // Asked again after the nested native call returned: the outer frame must still be the one answering.
            outerProtectsAfterNested = BreezeBurst.protects(explosion, burstAlly);
            xTupleAfter = burstTuple(explosion);
            Tactical.LOGGER.info("[Tactical] breeze-repair X nested outerProtects={} nestedUnfiltered={} "
                            + "outerAfterNested={} allyParticipant={} before[{}] after[{}]", outerProtects,
                    nestedUnfiltered, outerProtectsAfterNested, allyWasParticipant, xTupleBefore, xTupleAfter);
        }
    }

    /**
     * One compact exact tuple of the burst question: the exact identities, the real relations and the frame state.
     *
     * <p>Nothing here decides anything; it is the evidence a reviewer needs to separate "the body really is a
     * protected same-side participant" from "the scope answered false because the body stopped being one".</p>
     */
    private static String burstTuple(Explosion explosion) {
        if (explosion == null || burster == null || burstAlly == null) return "none";
        return "source=" + (explosion.getDirectSourceEntity() == null
                        ? "none" : explosion.getDirectSourceEntity().getUUID())
                + " frame=" + BreezeBurst.armed()
                + " protects=" + BreezeBurst.protects(explosion, burstAlly)
                + " actor=" + burster.getUUID()
                + " body=" + burstAlly.getUUID()
                + " participant=" + (battle != null && battle.participants().contains(burstAlly))
                + " ally=" + (battle != null && battle.allied(burster, burstAlly))
                + " opposed=" + (battle != null && battle.opposed(burster, burstAlly))
                + " alive=" + burstAlly.isAlive() + "/" + !burstAlly.isRemoved()
                + " hp=" + burstAlly.getHealth()
                + " pos=" + burstAlly.position();
    }

    /** The block-interaction facts of the marked burst of group K. */
    private static void blockFacts(Explosion explosion, ExplosionEvent.Detonate event) {
        markedToBlow = event.getAffectedBlocks().size();
        markedTriggerKept = explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK
                && explosion.interactsWithBlocks();
        markedParticles = explosion.getLargeExplosionParticles() == ParticleTypes.GUST_EMITTER_LARGE
                ? ParticleOptionsSeen.LARGE_GUST
                : explosion.getLargeExplosionParticles() == ParticleTypes.GUST_EMITTER_SMALL
                        ? ParticleOptionsSeen.SMALL_GUST : ParticleOptionsSeen.OTHER;
        noBlockInteraction = markedToBlow;
    }

    /**
     * The real damage events of the direct-impact target, classified by their exact source before any modifier runs.
     *
     * <p>The native charge deals exactly one point of direct damage and its burst deals none; what a body's health
     * really loses is that point after the encounter's existing modifiers. Counting the events and summing their
     * raw amounts separates the two, so neither a doubled hit nor a burst that starts damaging can hide behind a
     * plausible health delta.</p>
     *
     * <p>The count is exact rather than "anything that reached this body": only a wind-charge hit whose direct entity
     * is <em>this</em> charge is the claim. A burst of the same charge is counted separately, and any other real
     * attacker that reaches the body during a long group is counted and tagged instead of being folded into the
     * wind-charge number.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onIncoming(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!enabled) return;
        if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.WIND_CHARGE)
                && ((lockedTarget != null && event.getEntity() == lockedTarget)
                        || (exitTarget != null && event.getEntity() == exitTarget))) {
            warmHits++;
        }
        if (DeveloperTrace.enabled() && tStage <= 2) {
            Tactical.LOGGER.info("[Tactical] breeze-repair T any-incoming body={}#{} source={} amount={}",
                    event.getEntity().getType(), event.getEntity().getId(), describeSource(event.getSource()),
                    event.getAmount());
        }
        // Group T: everything that reaches the manager between its target's exit and the end of that burst.
        if (tStage >= 1 && tStage <= 2 && event.getEntity() == owner) exitManagerIncoming++;
        if (lockedTarget == null) return;
        if (event.getEntity() != lockedTarget) return;
        net.minecraft.world.damagesource.DamageSource source = event.getSource();
        boolean fromThisCharge = source.getDirectEntity() == liveCharge && liveCharge != null;
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] breeze-repair H incoming body={} source={} direct={} charge={} "
                            + "amount={}", event.getEntity(), describeSource(source),
                    source.getDirectEntity() == null ? "none" : source.getDirectEntity().getId(),
                    liveCharge == null ? "none" : liveCharge.getId(), event.getAmount());
        }
        if (fromThisCharge && source.is(net.minecraft.world.damagesource.DamageTypes.WIND_CHARGE)) {
            directEvents++;
            directRaw += event.getAmount();
            return;
        }
        if (fromThisCharge && source.is(net.minecraft.world.damagesource.DamageTypes.EXPLOSION)) {
            burstEvents++;
            return;
        }
        otherIncoming++;
        otherIncomingTag = describeSource(source);
    }

    /** The damage this target really took, after every modifier: one event means no burst addition either. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onApplied(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!enabled || lockedTarget == null) return;
        if (event.getEntity() != lockedTarget) return;
        net.minecraft.world.damagesource.DamageSource source = event.getSource();
        if (source.getDirectEntity() != liveCharge || liveCharge == null) return;
        if (!source.is(net.minecraft.world.damagesource.DamageTypes.WIND_CHARGE)) return;
        appliedEvents++;
        appliedAmount += event.getNewDamage();
    }

    /**
     * Group T's one deliberate member exit, taken inside that member's own native direct-hit callback.
     *
     * <p>This is the real exit path the encounter already has ({@code leave} → {@code removeMember} →
     * {@code BreezeWindCharges.forgetTarget}), not a fixture discard: the member really leaves the field while the
     * manager keeps the encounter running, and the charge that struck it is really unmarked and removed on this
     * stack. The native {@code onHitEntity} then walks on into that same charge's burst - which is exactly the case
     * the source repair covers.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTargetDamaged(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!enabled) return;
        if (exitTarget != null && event.getEntity() == exitTarget && (tStage == 1 || tStage == 2)
                && DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] breeze-repair T target-damaged source={} direct={} type={} amount={} "
                            + "charge={}", describeSource(event.getSource()),
                    event.getSource().getDirectEntity() == null ? "none"
                            : event.getSource().getDirectEntity().getId(),
                    event.getSource().getMsgId(), event.getNewDamage(),
                    exitCharge == null ? "none" : exitCharge.getId());
        }
        if (!enabled || tStage != 1 || exitCharge == null || exitTarget == null) return;
        if (event.getEntity() != exitTarget) return;
        if (event.getSource().getDirectEntity() != exitCharge) return;
        if (!event.getSource().is(net.minecraft.world.damagesource.DamageTypes.WIND_CHARGE)) return;
        if (exitInsideCallback) return;
        exitInsideCallback = true;
        exitMarkBeforePresent = BreezeWindCharges.markOf(exitCharge) != null;
        exitMemberLeft = exitTarget instanceof ServerPlayer member
                && battle != null && battle.leave(member, BattleText.literal("breeze-repair: target exit"));
        exitMarkAfterRemoved = !BreezeWindCharges.owns(exitCharge);
        exitOriginRetained = BreezeHitOrigins.originOf(exitCharge) != null;
        exitManagerKept = battle != null && !battle.finished() && battle.participants().contains(owner);
        exitTargetStillInWorld = !exitTarget.isRemoved();
        // Taken AFTER the exit and still inside the native hit, so these are the real baseline the burst that runs a
        // few instructions later must not change.
        exitDepartedHpBefore = exitTarget.getHealth();
        exitDepartedVelocityBefore = exitTarget.getDeltaMovement();
        exitManagerHpBefore = owner.getHealth();
        exitManagerVelocityBefore = owner.getDeltaMovement();
        exitManagerIncoming = 0;
        tStage = 2;
        Tactical.LOGGER.info("[Tactical] breeze-repair T inside-callback target={} left={} markBefore={} "
                        + "markAfter={} originRetained={} managerKept={} targetHp={}", exitTarget.getUUID(),
                exitMemberLeft, exitMarkBeforePresent, !exitMarkAfterRemoved, exitOriginRetained, exitManagerKept,
                exitTarget.getHealth());
    }

    /** A compact, honest tag of one damage source: its direct entity kind and its damage type. */
    private static String describeSource(net.minecraft.world.damagesource.DamageSource source) {
        Entity direct = source.getDirectEntity();
        return (direct == null ? "none" : direct.getType().toString()) + "/" + source.getMsgId();
    }

    /**
     * Every incoming damage event on the watched bodies, cancellations included.
     *
     * <p>An ordinary listener never sees a cancelled event, so a hit that some other guard refused would look
     * exactly like a hit that never happened. This one asks for cancellations too and says which it was.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onIncomingAnywhere(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!enabled || !DeveloperTrace.enabled()) return;
        boolean watched = (exitTarget != null && event.getEntity() == exitTarget && tStage <= 2)
                || (lockedTarget != null && event.getEntity() == lockedTarget);
        if (!watched) return;
        Tactical.LOGGER.info("[Tactical] breeze-repair incoming-any body={}#{} source={} amount={} cancelled={}",
                event.getEntity().getType(), event.getEntity().getId(), describeSource(event.getSource()),
                event.getAmount(), event.isCanceled());
    }

    /** Every real impact a wind charge of this fixture reached, so a missing hit is never guessed at. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onWindChargeImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!enabled || !DeveloperTrace.enabled()) return;
        if (!(event.getProjectile() instanceof BreezeWindCharge charge)) return;
        if (level == null || charge.level() != level) return;
        boolean watched = charge == exitCharge || charge == xCharge || charge == liveCharge;
        if (!watched) return;
        String victim = "none";
        if (event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit) {
            victim = hit.getEntity().getType() + "#" + hit.getEntity().getId() + "/" + hit.getEntity().getUUID()
                    + "/iframe=" + (hit.getEntity() instanceof LivingEntity living ? living.invulnerableTime : -1)
                    + "/hp=" + (hit.getEntity() instanceof LivingEntity living2 ? living2.getHealth() : -1.0F);
        }
        Tactical.LOGGER.info("[Tactical] breeze-repair impact charge={} at={} result={} victim={} cancelled={} "
                        + "group={}/{}", charge.getId(), charge.position(), event.getRayTraceResult().getType(), victim,
                event.isCanceled(), xStage, tStage);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            require(ticks < 30_000, "breeze repair fixture completion deadline");
            if (stage >= 12 && !passed.isEmpty()) {
                finishFixture();
                return;
            }
            switch (stage) {
                case 0 -> {
                    setup(event.getServer());
                    stage = 1;
                    wait = 0;
                }
                case 1 -> {
                    if (++wait < 20) return;
                    plan = plannedGroups();
                    require(plan.length > 0, "at least one repair group must be selected");
                    planIndex = 0;
                    enter(plan[0]);
                    stage = plan[0];
                    wait = 0;
                }
                case 2 -> {
                    observeDirectImpact();
                    if (!directImpactReady()) {
                        drivePlayerTurnsToBreeze(striker);
                        return;
                    }
                    checkDirectImpact();
                    advanceFrom(2);
                }
                case 3 -> {
                    observeBurstScope();
                    if (!burstScopeReady()) {
                        // The marked burst is only judged once its own side really belongs to this encounter.
                        if (battle != null && burstAlly != null && battle.participants().contains(burstAlly)) {
                            drivePlayerTurnsToBreeze(burster);
                        }
                        return;
                    }
                    checkBurstScope();
                    advanceFrom(3);
                }
                case 4 -> {
                    observeBlockInteraction();
                    if (!blockInteractionReady()) {
                        drivePlayerTurnsToBreeze(blocker);
                        return;
                    }
                    checkBlockInteraction();
                    advanceFrom(4);
                }
                case 5 -> {
                    observeShotBarrier();
                    if (!shotBarrierReady()) {
                        drivePlayerTurnsToBreeze(barrier);
                        return;
                    }
                    checkShotBarrier();
                    advanceFrom(5);
                }
                case 6 -> {
                    observePartialExit();
                    if (!partialExitReady()) {
                        drivePlayerTurnsToBreeze(leaver);
                        return;
                    }
                    checkPartialExit();
                    advanceFrom(6);
                }
                case 7 -> {
                    observeTargetExit();
                    if (!targetExitReady()) {
                        drivePlayerTurnsToBreeze(exitBreeze);
                        return;
                    }
                    checkTargetExit();
                    advanceFrom(7);
                }
                case 8 -> {
                    observePrelaunchArc();
                    if (!prelaunchArcReady()) {
                        drivePlayerTurnsToBreeze(arcJumper);
                        return;
                    }
                    checkPrelaunchArc();
                    advanceFrom(8);
                }
                default -> finishFixture();
            }
        } catch (IllegalStateException failure) {
            Tactical.LOGGER.error("[Tactical] BREEZE-REPAIR SELFTEST FAIL after {} passing group(s): {}",
                    passed.size(), failure.getMessage(), failure);
            enabled = false;
            server.halt(false);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] BREEZE-REPAIR SELFTEST FAIL after {} passing group(s): {}",
                    passed.size(), failure.getMessage(), failure);
            enabled = false;
            server.halt(false);
        }
    }

    // ------------------------------------------------------------------
    // setup, arena and plumbing
    // ------------------------------------------------------------------

    private static void setup(MinecraftServer running) {
        server = running;
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
        TacticalConfig.INSTANCE.zoneRadius.set(56);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 20, spawn.getY() + 24), spawn.getZ());
        for (int cx = (origin.getX() - 56) >> 4; cx <= (origin.getX() + 56) >> 4; cx++) {
            for (int cz = (origin.getZ() - 56) >> 4; cz <= (origin.getZ() + 56) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = FLOOR_MIN_X; x <= FLOOR_MAX_X; x++) {
            for (int z = FLOOR_MIN_Z; z <= FLOOR_MAX_Z; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 0; dy < 14; dy++) {
                    level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        AABB arena = new AABB(origin.offset(FLOOR_MIN_X, -4, FLOOR_MIN_Z).getCenter(),
                origin.offset(FLOOR_MAX_X, 18, FLOOR_MAX_Z).getCenter());
        for (Mob leftover : level.getEntitiesOfClass(Mob.class, arena)) leftover.discard();
        owner = player("tactical-repair-owner", "RepairOwner");
        second = player("tactical-repair-second", "RepairSecond");
        Tactical.LOGGER.info("[Tactical] breeze-repair arena at {} radius={}", origin, BattleManager.zoneRadius());
    }

    private static ServerPlayer player(String key, String name) {
        var profile = new com.mojang.authlib.GameProfile(
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        player.setHealth(100);
        level.addNewPlayer(player);
        return player;
    }

    private static void place(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
        placed.add(pos);
    }

    private static Breeze spawnBreeze(double dx, double dz) {
        Breeze breeze = EntityType.BREEZE.create(level);
        require(breeze != null, "breeze exists in this version");
        breeze.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        breeze.setNoAi(true);
        breeze.setPersistenceRequired();
        require(level.addFreshEntity(breeze), "breeze registered in the real server world");
        return breeze;
    }

    private static Zombie spawnZombie(double dx, double dz) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        require(zombie != null, "zombie exists in this version");
        zombie.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100);
        zombie.setHealth(100);
        require(level.addFreshEntity(zombie), "zombie registered in the real server world");
        return zombie;
    }

    private static void startBattle() {
        BattleManager.reset();
        require(BattleManager.start(owner, true), "the repair encounter starts");
        battle = BattleManager.current();
        require(battle != null && battle.isPlayerTurnNow(), "the owner acts first");
        BattleDiscovery.join(second, battle.sessionId());
        require(battle.participants().contains(second), "the second player really joined the encounter");
    }

    private static void resetPlayers() {
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        second.setHealth(second.getMaxHealth());
        second.invulnerableTime = 0;
        second.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() - 3.5D);
    }

    private static void endScenario() {
        if (battle != null) battle.finish(BattleText.literal("breeze-repair: next scenario"));
        BattleManager.reset();
        battle = null;
        List<Mob> bodies = new ArrayList<>();
        for (Mob mob : new Mob[]{striker, strikerAlly, burster, burstAlly, blocker, barrier, leaver, extraHostile,
                arcBody, exitBreeze, arcJumper}) {
            if (mob != null) bodies.add(mob);
        }
        for (Mob mob : bodies) mob.discard();
        striker = null;
        strikerAlly = null;
        burster = null;
        burstAlly = null;
        blocker = null;
        barrier = null;
        leaver = null;
        extraHostile = null;
        arcBody = null;
        exitBreeze = null;
        arcJumper = null;
        watchedOwner = null;
        liveCharge = null;
        lockedTarget = null;
        eCharge = null;
        eMember = null;
        exitRan = false;
        exitProtectsBefore = false;
        exitProtectsAfter = false;
        leaverCharge = null;
        markCharge = null;
        controlCharge = null;
        outerExplosion = null;
        chargeJoins = 0;
        cancelNextCharge = false;
        hStage = 0;
        directAtCheck = 0;
        directRawAtCheck = 0.0F;
        appliedAtCheck = 0.0F;
        dropAtCheck = 0.0F;
        warmHits = 0;
        xStage = 0;
        kStage = 0;
        wStage = 0;
        eStage = 0;
        tStage = 0;
        pStage = 0;
        directHitDone = false;
        refusalDone = false;
        cancelledSpawnDone = false;
        directEvents = 0;
        directRaw = 0.0F;
        appliedEvents = 0;
        appliedAmount = 0.0F;
        burstEvents = 0;
        otherIncoming = 0;
        otherIncomingTag = "none";
        nestedSeen = false;
        nestedUnfiltered = false;
        outerProtectsAfterNested = false;
        allyWasParticipant = false;
        xTupleBefore = "none";
        xTupleAfter = "none";
        sentinelCaught = false;
        sentinelFrameArmed = false;
        sentinelExplosionNamed = false;
        sentinelCaptured = -1;
        sentinelLeftNoFrame = false;
        sentinelMarksBefore = -1;
        sentinelMarksAfter = -1;
        sentinelOrdinaryNative = false;
        sentinelOrdinaryToBlow = -1;
        sentinelOrdinary = null;
        xCharge = null;
        ordinaryCharge = null;
        ordinaryToBlow = -1;
        outerProtects = false;
        throwArmed = false;
        throwDone = false;
        armedAfterReturn = true;
        capturedAfterReturn = true;
        marksAfterReturn = -1;
        exitCharge = null;
        exitWild = null;
        exitTarget = null;
        exitInsideCallback = false;
        exitMarkBeforePresent = false;
        exitMarkAfterRemoved = false;
        exitOriginRetained = false;
        exitMemberLeft = false;
        exitManagerKept = false;
        exitTargetStillInWorld = false;
        exitBurstArmed = false;
        exitBurstProtectsDeparted = false;
        exitBurstProtectsManager = false;
        exitBurstToBlow = -1;
        exitBurstCallbacks = -1;
        exitFrameCleared = false;
        exitOriginCleared = false;
        exitManagerIncoming = 0;
        exitWildToBlow = -1;
        arcStart = null;
        arcFrames = new Vec3[0];
        arcFramesAtBlock = -1;
        arcEpochAtBlock = -1;
        arcApAtBlock = -1;
        arcPaidAtBlock = -1;
        arcLegsHeld = false;
        arcBodyInFirstLeg = false;
        arcBodyOnlyInFirstLeg = false;
        arcRefusedFree = false;
        arcApAfter = -1;
        arcPaidAfter = -1;
        arcMovedAfter = -1.0D;
        arcPoseClean = false;
        arcNotBusy = false;
        markedDoorChanged = false;
        markedButtonChanged = false;
        controlDoorChanged = false;
        controlButtonChanged = false;
        markedTriggerKept = false;
        markedParticles = ParticleOptionsSeen.NONE;
        markedToBlow = 0;
        noBlockInteraction = 0;
        stillActingAfterRecovery = false;
        shotPendingAfterRecovery = false;
        barrierResolved = false;
        actorFlipTicks = 0;
        chargeGoneWithActor = false;
        for (BlockPos pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        placed.clear();
        resetPlayers();
    }

    /** End every player turn until that actor really holds the turn, so its own AI plans in a real turn. */
    private static void drivePlayerTurnsToBreeze(Breeze actor) {
        if (battle == null || battle.finished() || actor == null) return;
        if (battle.isActorTurn(actor)) return;
        if (!battle.isPlayerTurnNow()) return;
        battle.endPlayerTurn();
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finishFixture() {
        StringBuilder line = new StringBuilder("BREEZE-REPAIR SELFTEST PASS groups=" + passed.size());
        for (String entry : passed) line.append(' ').append(entry);
        Tactical.LOGGER.info("[Tactical] {}", line);
        enabled = false;
        for (BlockPos pos : placed) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        placed.clear();
        for (BlockPos pos : forced) level.setChunkForced(pos.getX(), pos.getZ(), false);
        forced.clear();
        if (battle != null) battle.finish(BattleText.literal("breeze-repair: done"));
        BattleManager.reset();
        server.halt(false);
    }

    // ------------------------------------------------------------------
    // H: the direct impact of a marked wind charge
    // ------------------------------------------------------------------

    /**
     * One real breeze, one real hostile ally and the real locked target.
     *
     * <p>Three steps, each on a charge the encounter really fired: the charge really hits the body it was aimed at
     * for the native single point of direct damage; the next one is driven into the firing body's own side and is
     * refused before any native effect; the third one is a native spawn the world really refuses.</p>
     */
    private static void beginDirectImpact() {
        resetPlayers();
        striker = spawnBreeze(8.0D, 0.0D);
        watchedOwner = striker;
        // The own-side body stands out of melee reach of the locked target on purpose: a melee ally would keep the
        // target's damage immunity hot and put its own hits into a measurement that must belong to one wind charge.
        strikerAlly = spawnZombie(6.0D, 3.5D);
        startBattle();
        strikerApBefore = 0;
        strikerPaidBefore = 0;
        strikerAlly.setHealth(100);
        allyHealthBefore = strikerAlly.getHealth();
        strikeHealthBefore = owner.getHealth();
        hStage = 0;
        liveCharge = null;
        warmHits = 0;
        chargeSettleAt = ticks + 20;
        hDeadline = ticks + 1400;
        Tactical.LOGGER.info("[Tactical] breeze-repair H start owner={} breeze={} ally={}", owner.position(),
                striker.position(), strikerAlly.position());
    }

    private static void observeDirectImpact() {
        if (battle == null || battle.finished()) return;
        if (!battle.participants().contains(striker) || !battle.participants().contains(strikerAlly)) {
            Tactical.LOGGER.info("[Tactical] breeze-repair H waiting for participants breeze={} ally={}",
                    battle.participants().contains(striker), battle.participants().contains(strikerAlly));
            return;
        }
        switch (hStage) {
            case 0 -> {
                // The real shot of the encounter, from the breeze's own turn, aimed at the body it was really
                // fired at: the charge's own immutable mark names that body, never a fixture guess.
                if (liveCharge == null || liveCharge.isRemoved()) return;
                BreezeWindCharges.Owner mark = BreezeWindCharges.markOf(liveCharge);
                require(mark != null, "the encounter's own charge really carries its immutable ownership");
                lockedTarget = livingParticipant(mark.targetId());
                require(lockedTarget != null,
                        "the charge's own locked target is still a live participant of this encounter: "
                                + mark.targetId());
                require(lockedTarget != striker, "the locked target is never the firing body itself");
                Tactical.LOGGER.info("[Tactical] breeze-repair H locked target={} markTarget={} owner={} second={}",
                        lockedTarget, mark.targetId(), owner.getUUID(), second.getUUID());
                if (warmHits == 0) return;
                strikerApBefore = battle.actorAp(striker);
                strikerPaidBefore = battle.actorPaidActions(striker);
                strikeHealthBefore = lockedTarget.getHealth();
                directEvents = 0;
                directRaw = 0.0F;
                appliedEvents = 0;
                appliedAmount = 0.0F;
                burstEvents = 0;
                otherIncoming = 0;
                otherIncomingTag = "none";
                // The charge is put on its own real collision course with the body it was really fired at.
                aimChargeAt(liveCharge, lockedTarget);
                hStage = 1;
            }
            case 1 -> {
                if (DeveloperTrace.enabled() && hProbe < 6 && liveCharge != null && !liveCharge.isRemoved()) {
                    Vec3 delta = liveCharge.getDeltaMovement();
                    AABB box = liveCharge.getBoundingBox().expandTowards(delta).inflate(1.0D);
                    List<Entity> near = level.getEntities(liveCharge, box, e -> true);
                    StringBuilder names = new StringBuilder();
                    for (Entity e : near) names.append(e.getType()).append('#').append(e.getId()).append(' ');
                    hProbe++;
                    Tactical.LOGGER.info("[Tactical] breeze-repair H probe charge={} at={} move={} box={} "
                                    + "near=[{}] targetHittable={} targetBox={} overlap={}", liveCharge.getId(),
                            liveCharge.position(), delta, box, names,
                            lockedTarget == null ? "none" : lockedTarget.canBeHitByProjectile(),
                            lockedTarget == null ? "none" : lockedTarget.getBoundingBox(),
                            lockedTarget != null && lockedTarget.getBoundingBox().intersects(box));
                }
                if (directEvents == 0) return;
                if (lockedTarget != null) {
                    directHitDone = true;
                    directAtCheck = directEvents;
                    directRawAtCheck = directRaw;
                    appliedAtCheck = appliedAmount;
                    dropAtCheck = strikeHealthBefore - lockedTarget.getHealth();
                    require(directEvents == 1,
                            "a marked charge deals exactly one direct hit before it bursts: events=" + directEvents);
                    float lost = strikeHealthBefore - lockedTarget.getHealth();
                    require(appliedEvents == 1,
                            "exactly one damage event of that charge is really applied to that body: applied="
                                    + appliedEvents);
                    require(burstEvents == 0,
                            "the burst of that same charge reaches this body with no damage event at all: "
                                    + burstEvents);
                    require(lost > 0.0F && lost <= appliedAmount + 1.0E-4F,
                            "the target's whole loss comes from that one applied hit and never from a second one, "
                                    + "so the burst adds no entity damage: lost=" + lost + " applied="
                                    + appliedAmount);
                    require(lost > 0.0F,
                            "the direct hit really lowers the target's health: " + strikeHealthBefore + " -> "
                                    + lockedTarget.getHealth());
                    require(!BreezeWindCharges.owns(liveCharge),
                            "the mark of a charge that really burst is gone");
                    hStage = 2;
                }
            }
            case 2 -> {
                if (liveCharge != null && !liveCharge.isRemoved()) return;
                if (!battle.isActorTurn(striker)) return;
                if (BreezeWindCharges.size() != 0) return;
                strikerAlly.setHealth(100);
                allyHealthBefore = strikerAlly.getHealth();
                hStage = 3;
            }
            case 3 -> {
                // The next real shot of the same breeze, driven into its own side.
                if (liveCharge == null || liveCharge.isRemoved()) return;
                aimChargeAt(liveCharge, strikerAlly);
                hStage = 4;
            }
            case 4 -> {
                if (liveCharge != null && !liveCharge.isRemoved()) return;
                refusalDone = true;
                require(Math.abs(strikerAlly.getHealth() - allyHealthBefore) <= 1.0E-4F,
                        "a marked charge may not hurt the firing body's own side: " + allyHealthBefore + " -> "
                                + strikerAlly.getHealth());
                require(BreezeWindCharges.size() == 0,
                        "a refused direct impact takes the charge's own mark with it: "
                                + BreezeWindCharges.describe());
                hStage = 5;
            }
            case 5 -> {
                if (!battle.isActorTurn(striker)) return;
                // A native spawn the world really refuses, on the same real breeze and the same real turn.
                strikerApBefore = battle.actorAp(striker);
                strikerPaidBefore = battle.actorPaidActions(striker);
                cancelNextCharge = true;
                chargeJoins = 0;
                hStage = 6;
            }
            case 6 -> {
                if (cancelNextCharge || !cancelledSpawnDone) return;
                // The tracker's own books settle after the tick: a join cancelled by a later listener may have been
                // seen by the tracker on the same stack, and its cleanup is the tick's job.
                cancelledFlightAfter = battle.fixturePendingFlights();
                if (battle.fixtureOwnTurns(striker.getUUID()) == cancelledEpochAtRefusal) {
                    // Same own turn as the refusal: nothing may have been debited for it, and the per-turn paid
                    // counter may not have moved either.
                    cancelledApAfter = battle.actorAp(striker);
                    cancelledPaidAfter = battle.actorPaidActions(striker);
                } else {
                    // The refusal really ended that turn; a new own turn legitimately grants fresh AP, so the AP
                    // equality is not claimed here - only the cumulative receipts and the empty mark/flight books.
                    cancelledTurnRolled = true;
                    cancelledApAfter = cancelledApAtRefusal;
                    cancelledPaidAfter = cancelledPaidAtRefusal;
                }
                hStage = 7;
            }
            default -> { }
        }
    }

    /** The exact live participant the encounter holds behind that id, or {@code null}. */
    @javax.annotation.Nullable
    private static LivingEntity livingParticipant(UUID id) {
        if (id == null || battle == null) return null;
        for (LivingEntity candidate : battle.participants()) {
            if (candidate.getUUID().equals(id) && candidate.isAlive() && !candidate.isRemoved()) return candidate;
        }
        return null;
    }

    /**
     * Put one really tracked charge on a real collision course with that body.
     *
     * <p>The standoff is a measured requirement, not styling. Vanilla finds a projectile's entity hit by clipping the
     * tick's movement segment against the target's box inflated by {@code 0.3}, and that clip only reports a
     * <em>crossing</em> face: a segment that starts inside the inflated box crosses nothing and is reported as a miss,
     * however deep inside the body it starts. With this charge's {@code 0.7} blocks per tick, a standoff of less than
     * about three blocks leaves the segment before contact starting inside that inflation - which is exactly how an
     * earlier fixture "aimed" a charge straight through its target without a single hit. Three blocks out keeps the
     * approach a real entry, so the native hit, the native damage and the native burst all really happen; a charge
     * that is already that far out is left exactly where the encounter aimed it.</p>
     */
    private static void aimChargeAt(BreezeWindCharge charge, LivingEntity body) {
        Vec3 eye = body.position().add(0.0D, 1.0D, 0.0D);
        if (charge.position().distanceTo(eye) >= 2.6D) return;
        Vec3 direction = charge.getDeltaMovement();
        Vec3 forward = direction.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : direction.normalize();
        Vec3 at = eye.subtract(forward.scale(3.0D));
        charge.setPos(at.x, at.y, at.z);
        charge.setDeltaMovement(forward.scale(Math.max(0.7D, direction.length())));
    }

    private static boolean directImpactReady() {
        require(battle != null && !battle.finished(), "the direct-impact encounter is still live");
        return hStage >= 7 || ticks > hDeadline;
    }

    private static void checkDirectImpact() {
        require(directHitDone, "a marked charge really dealt its native direct damage to the locked target");
        require(refusalDone, "a marked charge really met the firing body's own side and was refused");
        require(cancelledSpawnDone, "a native spawn the world refused really ended the charge attempt");
        require(cancelledApAtRefusal >= 0, "the refusal really happened, so its AP is a real reading");
        require(cancelledApAfter == cancelledApAtRefusal,
                "a refused native spawn is never charged: " + cancelledApAtRefusal + " -> " + cancelledApAfter
                        + (cancelledTurnRolled ? " (new own turn after the refusal)" : ""));
        require(cancelledPaidAfter == cancelledPaidAtRefusal,
                "a refused native spawn counts no paid action: " + cancelledPaidAtRefusal + " -> "
                        + cancelledPaidAfter);
        require(cancelledMarksAfter == 0, "a refused native spawn leaves no mark: " + cancelledMarksAfter);
        require(cancelledFlightAfter == 0, "a refused native spawn leaves no tracked flight: " + cancelledFlightAfter);
        passed.add("H[directEvents=1 burstDamage=0 refusedOwnSide=true cancelledSpawnFree=true]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR H PASS nativeBasis={} direct={} raw={} applied={} "
                        + "burstEvents={} otherIncoming={}[{}] dropAtHit={} ap={}->{} marks={} flights={}",
                BreezeRules.DIRECT_HIT_DAMAGE, directAtCheck, directRawAtCheck, appliedAtCheck, burstEvents,
                otherIncoming, otherIncomingTag, dropAtCheck, cancelledApAtRefusal, cancelledApAfter,
                cancelledMarksAfter, cancelledFlightAfter);
    }

    // ------------------------------------------------------------------
    // X: the burst scope
    // ------------------------------------------------------------------

    private static void beginBurstScope() {
        resetPlayers();
        burster = spawnBreeze(8.0D, 0.0D);
        burstAlly = spawnZombie(1.5D, -2.5D);
        burstAnchor = origin.offset(0, 0, 0);
        startBattle();
        xStage = 0;
        xCharge = null;
        // The sentinel is thrown only inside the fixture's own guarded invocation, never during an ordinary world
        // tick: an uncaught throw there is a ReportedException that really kills the server.
        throwArmed = false;
        throwDone = false;
        ordinaryCharge = null;
        ordinaryToBlow = -1;
        outerExplosion = null;
        nestedSeen = false;
        nestedUnfiltered = false;
        outerProtectsAfterNested = false;
        allyWasParticipant = false;
        xTupleBefore = "none";
        xTupleAfter = "none";
        sentinelCaught = false;
        sentinelFrameArmed = false;
        sentinelExplosionNamed = false;
        sentinelCaptured = -1;
        sentinelLeftNoFrame = false;
        sentinelMarksBefore = -1;
        sentinelMarksAfter = -1;
        sentinelOrdinaryNative = false;
        sentinelOrdinaryToBlow = -1;
        sentinelOrdinary = null;
        armedAfterReturn = true;
        capturedAfterReturn = true;
        marksAfterReturn = -1;
        xDeadline = ticks + 1400;
        Tactical.LOGGER.info("[Tactical] breeze-repair X start burster={} ally={}", burster.position(),
                burstAlly.position());
    }

    private static void observeBurstScope() {
        if (battle == null || battle.finished()) return;
        switch (xStage) {
            case 0 -> {
                // The encounter's own marked charge, still in the air. Its own native explode is invoked here, at
                // this fixture's boundary, because that is the only place an intentional throw can be caught.
                if (xCharge == null || xCharge.isRemoved()) return;
                require(BreezeWindCharges.markOf(xCharge) != null,
                        "the charge whose exception scope is exercised really is this encounter's own");
                sentinelExperiment();
                xStage = 1;
            }
            case 1 -> {
                // The same charge now flies on and really bursts on its own: the nesting experiment happens there.
                if (!throwDone) return;
                if (xCharge != null && !xCharge.isRemoved()) return;
                armedAfterReturn = BreezeBurst.armed();
                capturedAfterReturn = !BreezeBurst.captured().isEmpty();
                marksAfterReturn = BreezeWindCharges.size();
                // An ordinary, unmarked charge in the same real call path, with one real door beside it: a burst no
                // encounter fired must still reach its own blocks natively.
                xControlDoorPos = origin.offset(-1, 0, 0);
                place(xControlDoorPos, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.FACING, Direction.NORTH));
                place(xControlDoorPos.above(), Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.NORTH));
                xControlDoorBefore = level.getBlockState(xControlDoorPos).getValue(DoorBlock.OPEN);
                BreezeWindCharge ordinary = new BreezeWindCharge(burster, level);
                ordinary.setPos(origin.getX() + 0.5D, origin.getY() + 0.6D, origin.getZ() + 0.5D);
                ordinary.shoot(0.0D, -0.25D, 0.0D, BreezeRules.SHOT_SPEED, 0.0F);
                sentinelOrdinary = ordinary;
                require(level.addFreshEntity(ordinary), "the ordinary charge joined the world");
                xStage = 2;
            }
            case 2 -> {
                if (sentinelOrdinary != null && !sentinelOrdinary.isRemoved()) return;
                xControlDoorAfter = xControlDoorPos == null
                        ? xControlDoorBefore : level.getBlockState(xControlDoorPos).getValue(DoorBlock.OPEN);
                sentinelOrdinaryNative = sentinelOrdinaryToBlow > 0 && xControlDoorAfter != xControlDoorBefore;
                xStage = 3;
            }
            default -> { }
        }
    }

    /**
     * The exception scope of one marked burst, exercised exactly and caught only here.
     *
     * <p>The real native {@code BreezeWindCharge.explode} is invoked synchronously through a fixture-only reflective
     * handle, so the arming wrap and its {@code finally} really run. Inside that call the real detonate callback
     * throws the one unique sentinel this fixture knows; only that sentinel is caught, at this fixture's own
     * invocation boundary. Nothing about a hit, a child or a product rule is synthesized - the exception scope
     * under test is the exact one the world would use, entered exactly the way the world would enter it.</p>
     */
    private static void sentinelExperiment() {
        throwArmed = true;
        throwDone = false;
        sentinelMarksBefore = BreezeWindCharges.size();
        Vec3 centre = xCharge.position();
        try {
            java.lang.reflect.Method explode = BreezeWindCharge.class.getDeclaredMethod("explode", Vec3.class);
            explode.setAccessible(true);
            explode.invoke(xCharge, centre);
            sentinelCaught = false;
        } catch (java.lang.reflect.InvocationTargetException failure) {
            sentinelCaught = failure.getCause() == SENTINEL;
            if (!sentinelCaught) {
                throw new IllegalStateException("the native explosion failed for another reason: "
                        + failure.getCause(), failure.getCause());
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("the native explode handle is not reachable", failure);
        } finally {
            throwArmed = false;
        }
        // Read immediately after the native call returned: this is the frame/capture/mark cleanup claim.
        sentinelLeftNoFrame = !BreezeBurst.armed();
        sentinelMarksAfter = BreezeWindCharges.size();
    }

    private static boolean burstScopeReady() {
        require(battle != null && !battle.finished(), "the burst-scope encounter is still live");
        return xStage >= 3 || ticks > xDeadline;
    }

    private static void checkBurstScope() {
        require(outerExplosion != null, "a marked charge really burst inside this encounter");
        require(allyWasParticipant, "the burst's own side really belongs to this encounter");
        require(!xTupleBefore.equals("none"),
                "the very first question of the burst was asked with real identities: " + xTupleBefore);
        require(outerProtects, "the marked burst really protects its own side");
        require(nestedSeen, "a nested unmarked explosion really ran inside the marked one");
        require(nestedUnfiltered, "a nested unmarked explosion is not filtered by the outer burst scope");
        require(outerProtectsAfterNested,
                "the outer marked frame still owns its answer after the nested explosion returned");
        require(!xTupleAfter.equals("none"),
                "the outer frame still answers with the same real identities afterwards: " + xTupleAfter);
        require(!armedAfterReturn, "no armed frame survives the marked burst's own native call");
        require(!capturedAfterReturn, "no captured body survives the marked burst's own native call");
        require(sentinelCaught,
                "the real native explode threw the fixture's sentinel and it was caught only at the fixture boundary");
        require(sentinelFrameArmed, "the marked burst really was armed inside that native call");
        require(sentinelExplosionNamed, "the armed frame was bound to the exact explosion of that exact charge");
        require(sentinelCaptured == 0, "no body was captured before the callback threw: " + sentinelCaptured);
        require(sentinelLeftNoFrame, "a throwing native explode leaves no armed frame behind");
        require(sentinelMarksAfter == sentinelMarksBefore,
                "a throwing native explode invents and drops no ownership: " + sentinelMarksBefore + " -> "
                        + sentinelMarksAfter);
        require(sentinelOrdinaryNative,
                "an ordinary charge still opens the same door and keeps its own native block list afterwards ("
                        + sentinelOrdinaryToBlow + ", door " + xControlDoorBefore + " -> " + xControlDoorAfter + ")");
        passed.add("X[nestedUnfiltered=true outerRestored=true sentinelClean=true ordinaryNative=true]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR X PASS outerProtects={} nestedUnfiltered={} "
                        + "outerAfterNested={} before[{}] after[{}] sentinel={} sentinelArmed={} sentinelCaptured={} "
                        + "sentinelFrameCleared={} marks={}->{} ordinaryToBlow={}", outerProtects, nestedUnfiltered,
                outerProtectsAfterNested, xTupleBefore, xTupleAfter, sentinelCaught, sentinelFrameArmed,
                sentinelCaptured, sentinelLeftNoFrame, sentinelMarksBefore, sentinelMarksAfter,
                sentinelOrdinaryToBlow);
    }

    // ------------------------------------------------------------------
    // K: the block interaction of a marked burst
    // ------------------------------------------------------------------

    private static void beginBlockInteraction() {
        resetPlayers();
        blocker = spawnBreeze(6.0D, 6.0D);
        doorPos = origin.offset(-1, 0, 0);
        place(doorPos, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH));
        place(doorPos.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.NORTH));
        place(origin.offset(-2, 0, -2), Blocks.STONE.defaultBlockState());
        buttonPos = origin.offset(-1, 0, -2);
        place(buttonPos, Blocks.STONE_BUTTON.defaultBlockState().setValue(ButtonBlock.FACING, Direction.WEST));
        doorBefore = level.getBlockState(doorPos).getValue(DoorBlock.OPEN);
        buttonBefore = level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED);
        startBattle();
        kStage = 0;
        markCharge = null;
        controlCharge = null;
        kDeadline = ticks + 1400;
    }

    private static void observeBlockInteraction() {
        if (battle == null || battle.finished()) return;
        switch (kStage) {
            case 0 -> {
                // The encounter's own marked charge, detonated exactly where the door and the button are.
                if (markCharge == null || markCharge.isRemoved()) return;
                markCharge.setPos(origin.getX() + 0.5D, origin.getY() + 0.6D, origin.getZ() + 0.5D);
                markCharge.setDeltaMovement(0.0D, -0.7D, 0.0D);
                kStage = 1;
            }
            case 1 -> {
                if (markCharge != null && !markCharge.isRemoved()) return;
                markedDoorChanged = level.getBlockState(doorPos).getValue(DoorBlock.OPEN) != doorBefore;
                markedButtonChanged = level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED) != buttonBefore;
                markedCallbacks = BreezeBurst.lastBlockCallbacks();
                // The untouched native control in exactly the same geometry, with a charge no encounter fired.
                BreezeWindCharge control = new BreezeWindCharge(blocker, level);
                control.setPos(origin.getX() + 0.5D, origin.getY() + 0.6D, origin.getZ() + 0.5D);
                control.shoot(0.0D, -0.25D, 0.0D, BreezeRules.SHOT_SPEED, 0.0F);
                controlCharge = control;
                require(level.addFreshEntity(control), "the unmarked control charge joined the world");
                require(!BreezeWindCharges.owns(control), "the control charge carries no encounter mark");
                kStage = 2;
            }
            case 2 -> {
                if (controlCharge != null && !controlCharge.isRemoved()) return;
                controlDoorChanged = level.getBlockState(doorPos).getValue(DoorBlock.OPEN) != doorBefore;
                controlButtonChanged = level.getBlockState(buttonPos).getValue(ButtonBlock.POWERED) != buttonBefore;
                kStage = 3;
            }
            default -> { }
        }
    }

    private static boolean blockInteractionReady() {
        require(battle != null && !battle.finished(), "the block-interaction encounter is still live");
        return kStage >= 3 || ticks > kDeadline;
    }

    private static void checkBlockInteraction() {
        require(markedCallbacks == 0,
                "a marked burst reaches no block callback at all: " + markedCallbacks);
        require(!markedDoorChanged, "a marked burst never opens the door");
        require(!markedButtonChanged, "a marked burst never presses the button");
        require(markedTriggerKept,
                "the marked burst keeps its native interaction kind and its own particle choice");
        require(markedParticles == ParticleOptionsSeen.LARGE_GUST,
                "the marked burst keeps the native large gust emitter: " + markedParticles);
        require(controlDoorChanged && controlButtonChanged,
                "the unmarked control really opens the same door and presses the same button");
        passed.add("K[markedCallbacks=0 controlDoor=true controlButton=true particles=largeGust]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR K PASS markedCallbacks={} door={} button={} control={}/{} "
                        + "particles={}", markedCallbacks, markedDoorChanged, markedButtonChanged, controlDoorChanged,
                controlButtonChanged, markedParticles);
    }

    // ------------------------------------------------------------------
    // W: the flight barrier of a wind charge
    // ------------------------------------------------------------------

    private static void beginShotBarrier() {
        resetPlayers();
        // Fifteen blocks: inside the native band and far enough that the shot outlives the four native recovery
        // ticks by a wide margin.
        barrier = spawnBreeze(15.0D, 0.0D);
        watchedOwner = barrier;
        startBattle();
        wStage = 0;
        barrierTurns = 0;
        actorFlipTicks = 0;
        stillActingAfterRecovery = false;
        shotPendingAfterRecovery = false;
        ownerOwnTurnsAtRecovery = -1;
        ownerOwnTurnsAfterHandBack = -1;
        wDeadline = ticks + 1400;
    }

    private static void observeShotBarrier() {
        if (battle == null || battle.finished()) return;
        boolean acting = battle.isActorTurn(barrier);
        switch (wStage) {
            case 0 -> {
                if (liveCharge == null || liveCharge.isRemoved()) {
                    if (liveCharge != null) wStage = 2;
                    return;
                }
                if (!acting) return;
                ownerApAtRecovery = battle.actorAp(owner);
                ownerOwnTurnsAtRecovery = battle.fixtureOwnTurns(barrier.getUUID());
                wStage = 1;
                wait = ticks;
            }
            case 1 -> {
                if (!acting) {
                    actorFlipTicks++;
                    // The turn moved away from the breeze: only a really resolved shot may do that.
                    wStage = 3;
                    return;
                }
                if (ticks - wait >= BreezeRules.RECOVER_TICKS + 1) {
                    stillActingAfterRecovery = acting;
                    shotPendingAfterRecovery = battle.fixturePendingFlights() > 0;
                    require(stillActingAfterRecovery,
                            "the breeze still holds its own turn after the native recovery while its shot flies");
                    require(shotPendingAfterRecovery,
                            "the shot that turn paid for is really still in the air after the native recovery");
                }
            }
            case 2 -> {
                // The charge left the world; the barrier must have released the turn exactly once.
                if (acting) return;
                wStage = 3;
            }
            case 3 -> {
                if (acting) {
                    barrierTurns++;
                    wStage = 4;
                }
            }
            default -> { }
        }
        if (wStage >= 3 && ownerOwnTurnsAfterHandBack < 0 && !battle.isActorTurn(barrier)) {
            ownerApAfterHandBack = battle.actorAp(owner);
            ownerOwnTurnsAfterHandBack = battle.fixtureOwnTurns(barrier.getUUID());
            barrierResolved = true;
        }
    }

    private static boolean shotBarrierReady() {
        require(battle != null && !battle.finished(), "the barrier encounter is still live");
        return barrierResolved || ticks > wDeadline;
    }

    private static void checkShotBarrier() {
        require(stillActingAfterRecovery,
                "the breeze really still held its turn after the native recovery");
        require(shotPendingAfterRecovery,
                "the shot really was still in the air at that moment");
        require(barrierResolved, "the shot really resolved and the turn really went back");
        require(actorFlipTicks == 1,
                "the queue advanced away from the breeze exactly once for one shot: flips=" + actorFlipTicks);
        require(ownerOwnTurnsAfterHandBack == ownerOwnTurnsAtRecovery + 0,
                "the breeze took no second own turn from one charge: " + ownerOwnTurnsAtRecovery + " -> "
                        + ownerOwnTurnsAfterHandBack);
        passed.add("W[stillActing=true pending=true advance=1]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR W PASS stillActing={} pending={} flips={} returns={} "
                + "ownerAp={}->{}", stillActingAfterRecovery, shotPendingAfterRecovery, actorFlipTicks, barrierTurns,
                ownerApAtRecovery, ownerApAfterHandBack);
    }

    // ------------------------------------------------------------------
    // E: a firing body that leaves inside its own burst callback
    // ------------------------------------------------------------------

    private static void beginPartialExit() {
        resetPlayers();
        leaver = spawnBreeze(9.0D, 0.0D);
        watchedOwner = leaver;
        extraHostile = spawnZombie(9.0D, 3.0D);
        startBattle();
        memberHealthBefore = owner.getHealth();
        reflectionsAfterActorExit = -1;
        chargeGoneWithActor = false;
        eStage = 0;
        eDeadline = ticks + 1600;
    }

    private static void observePartialExit() {
        if (battle == null || battle.finished()) return;
        switch (eStage) {
            case 0 -> {
                if (ticks % 20 == 0) {
                    Tactical.LOGGER.info("[Tactical] breeze-repair E trace joins={} charge={} leaverPart={} "
                                    + "hostilePart={} acting={} leaves={} target={}", chargeJoins,
                            leaverCharge == null ? "none" : leaverCharge.getId(),
                            battle.participants().contains(leaver),
                            battle.participants().contains(extraHostile), battle.isActorTurn(leaver),
                            battle.isActorTurn(extraHostile), extraHostile.getTarget());
                }
                if (leaverCharge == null || leaverCharge.isRemoved()) return;
                BreezeWindCharges.Owner mark = BreezeWindCharges.markOf(leaverCharge);
                require(mark != null, "the exiting actor's charge really carries its immutable ownership");
                eMember = livingParticipant(mark.targetId());
                require(eMember != null, "the burst's own locked target is still a live participant: "
                        + mark.targetId());
                exitMemberBefore = eMember.getHealth();
                // The owned burst happens right at that body, which the encounter may legitimately still affect
                // while its own source is answerable.
                aimChargeAt(leaverCharge, eMember);
                eStage = 1;
            }
            case 1 -> {
                if (!exitRan) return;
                exitMemberAfter = eMember == null ? -1.0F : eMember.getHealth();
                chargeGoneWithActor = BreezeWindCharges.size() == 0;
                reflectionsAfterActorExit = BreezeDeflections.size();
                eStage = 2;
            }
            default -> { }
        }
    }

    private static boolean partialExitReady() {
        require(battle != null && !battle.finished(), "the partial-exit encounter is still live");
        return eStage >= 2 || ticks > eDeadline;
    }

    private static void checkPartialExit() {
        require(exitRan, "a marked burst really ran its own native callback inside this encounter");
        require(!exitProtectsBefore,
                "while its source is answerable, the owned burst may still affect an opposed participant");
        require(exitProtectsAfter,
                "once the firing body left inside that callback, the same exact burst refuses every body");
        require(Math.abs(exitMemberAfter - exitMemberBefore) <= 1.0E-4F,
                "the body the burst happened on takes nothing after its source left: " + exitMemberBefore + " -> "
                        + exitMemberAfter);
        require(chargeGoneWithActor, "the departing actor's own charge leaves with it");
        require(reflectionsAfterActorExit == 0, "no reversed shot of a departed body survives it");
        passed.add("E[callbackExitRefused=true actorExitClean=true]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR E PASS protectsBefore={} protectsAfter={} memberHp={}->{} "
                        + "marks={} reflections={}", exitProtectsBefore, exitProtectsAfter, exitMemberBefore,
                exitMemberAfter, BreezeWindCharges.size(), reflectionsAfterActorExit);
    }

    // ------------------------------------------------------------------
    // T: the locked target leaves inside the direct hit of its own marked charge
    // ------------------------------------------------------------------

    /**
     * One real marked charge, one real non-manager member target, and the exit that member really takes mid-callback.
     *
     * <p>The manager stands close enough to the impact point that a burst admitted as a wild one would really reach
     * it, and the target itself stays a live body standing exactly where the burst happens - so "it took nothing" is
     * a statement about a body that is in range, not about an empty volume.</p>
     */
    private static void beginTargetExit() {
        resetPlayers();
        exitBreeze = spawnBreeze(8.0D, 0.0D);
        watchedOwner = exitBreeze;
        // The manager is moved next to the non-manager target: it must really be inside the burst radius for the
        // "took nothing" measurement to mean anything, and it must stay the farther of the two opponents so the
        // charge is really locked on the non-manager.
        owner.setPos(origin.getX() + 1.0D, origin.getY(), origin.getZ() - 4.0D);
        // One real door beside the impact point, off the line between the impact and the manager: a burst that is
        // not narrowed would really open it, and a burst of the encounter's own charge must not.
        exitDoorPos = origin.offset(4, 0, -5);
        place(exitDoorPos, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH));
        place(exitDoorPos.above(), Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.FACING, Direction.NORTH));
        exitDoorBefore = level.getBlockState(exitDoorPos).getValue(DoorBlock.OPEN);
        startBattle();
        exitCharge = null;
        exitWild = null;
        exitTarget = null;
        exitInsideCallback = false;
        exitMarkBeforePresent = false;
        exitMarkAfterRemoved = false;
        exitOriginRetained = false;
        exitMemberLeft = false;
        exitManagerKept = false;
        exitTargetStillInWorld = false;
        exitBurstArmed = false;
        exitBurstProtectsDeparted = false;
        exitBurstProtectsManager = false;
        exitBurstToBlow = -1;
        exitBurstCallbacks = -1;
        exitFrameCleared = false;
        exitOriginCleared = false;
        exitManagerIncoming = 0;
        exitManagerHpBefore = owner.getHealth();
        exitManagerHpAfter = owner.getHealth();
        exitManagerVelocityBefore = owner.getDeltaMovement();
        exitManagerVelocityAfter = owner.getDeltaMovement();
        exitDepartedHpBefore = second.getHealth();
        exitDepartedHpAfter = second.getHealth();
        exitDepartedVelocityBefore = second.getDeltaMovement();
        exitDepartedVelocityAfter = second.getDeltaMovement();
        exitWildToBlow = -1;
        exitWildBodyHpBefore = -1.0F;
        exitWildBodyHpAfter = -1.0F;
        tStage = 0;
        warmHits = 0;
        chargeSettleAt = ticks + 20;
        tDeadline = ticks + 1600;
        Tactical.LOGGER.info("[Tactical] breeze-repair T start breeze={} manager={}#{} second={}#{} managerDist={} useChargeFrom={}",
                exitBreeze.position(), owner.position(), owner.getId(), second.position(), second.getId(),
                round(owner.position().distanceTo(Vec3.atCenterOf(origin))), chargeSettleAt);
    }
    private static void observeTargetExit() {
        if (battle == null || battle.finished()) return;
        switch (tStage) {
            case 0 -> {
                if (exitCharge == null || exitCharge.isRemoved()) return;
                BreezeWindCharges.Owner mark = BreezeWindCharges.markOf(exitCharge);
                require(mark != null, "the striking charge really carries this encounter's ownership");
                LivingEntity marked = livingParticipant(mark.targetId());
                require(marked != null, "the charge's own locked target is still a live participant: "
                        + mark.targetId());
                require(marked != owner,
                        "the locked target of this charge is the non-manager member, so its exit cannot end the "
                                + "encounter: " + mark.targetId());
                exitTarget = marked;
                if (warmHits == 0) return;
                tStage = 1;
                aimChargeAt(exitCharge, exitTarget);
                Tactical.LOGGER.info("[Tactical] breeze-repair T aim charge={} at={} move={} target={} at={} hp={}",
                        exitCharge.getId(), exitCharge.position(), exitCharge.getDeltaMovement(),
                        exitTarget.getUUID(), exitTarget.position(), exitTarget.getHealth());
            }
            case 1 -> {
                if (!exitInsideCallback) return;
                // The native hit is still on its stack; the burst of that same charge happens on this stack too.
                if (exitCharge != null && !exitCharge.isRemoved()) return;
                tStage = 2;
            }
            case 2 -> {
                exitBurstCallbacks = BreezeBurst.lastBlockCallbacks();
                exitFrameCleared = !BreezeBurst.armed();
                exitOriginCleared = BreezeHitOrigins.originOf(exitCharge) == null;
                exitDoorAfterMarked = exitDoorPos == null
                        ? exitDoorBefore : level.getBlockState(exitDoorPos).getValue(DoorBlock.OPEN);
                exitManagerHpAfter = owner.getHealth();
                exitManagerVelocityAfter = owner.getDeltaMovement();
                exitDepartedHpAfter = exitTarget == null ? -1.0F : exitTarget.getHealth();
                exitDepartedVelocityAfter = exitTarget == null ? null : exitTarget.getDeltaMovement();
                tStage = 3;
            }
            case 3 -> {
                // The untouched native control of this group: an unmarked charge bursting in the same geometry, next
                // to the same untouched door. What it really reaches is what a burst with no encounter frame does.
                if (ticks % 10 != 0) return;
                BreezeWindCharge wild = new BreezeWindCharge(exitBreeze, level);
                wild.setPos(origin.getX() + 3.5D, origin.getY() + 0.6D, origin.getZ() - 3.5D);
                wild.shoot(0.0D, -0.25D, 0.0D, BreezeRules.SHOT_SPEED, 0.0F);
                exitWild = wild;
                require(level.addFreshEntity(wild), "the unmarked control charge joined the world");
                require(!BreezeWindCharges.owns(wild), "the control charge carries no encounter mark");
                exitWildBodyHpBefore = exitTarget == null ? -1.0F : exitTarget.getHealth();
                tStage = 4;
            }
            case 4 -> {
                if (exitWild != null && !exitWild.isRemoved()) return;
                exitDoorAfterWild = exitDoorPos == null
                        ? exitDoorBefore : level.getBlockState(exitDoorPos).getValue(DoorBlock.OPEN);
                exitWildBodyHpAfter = exitTarget == null ? -1.0F : exitTarget.getHealth();
                tStage = 5;
            }
            default -> { }
        }
    }

    private static boolean targetExitReady() {
        require(battle != null, "the target-exit encounter still exists");
        return tStage >= 5 || ticks > tDeadline;
    }

    private static void checkTargetExit() {
        require(exitInsideCallback, "the target really left inside its own charge's direct-hit callback");
        require(exitMarkBeforePresent, "before that exit the striking charge really was marked");
        require(exitMemberLeft, "the member really took the encounter's own exit path inside the callback");
        require(exitMarkAfterRemoved,
                "that exit really unmarked the charge inside the same callback, before any burst");
        require(exitOriginRetained,
                "the immutable owned origin of that exact charge survives the cleanup until its call returns");
        require(exitManagerKept, "another member keeps the encounter active, so this is a partial exit");
        require(exitTargetStillInWorld, "the departed member is still a real living body standing at the impact");
        require(exitBurstArmed, "the burst of that revoked shot is still admitted as one of this encounter's own");
        require(exitBurstProtectsDeparted,
                "the revoked burst refuses the body its own direct hit was fired at");
        require(exitBurstProtectsManager,
                "the revoked burst refuses even the opposed manager: it is deny-all, not a weak filter");
        require(exitBurstCallbacks == 0,
                "the revoked burst reaches no real block callback: " + exitBurstCallbacks);
        require(exitDoorAfterMarked == exitDoorBefore,
                "the door beside the revoked burst is left alone: " + exitDoorBefore + " -> " + exitDoorAfterMarked);
        require(exitManagerIncoming == 0,
                "the manager standing inside the radius takes no damage event from that burst: " + exitManagerIncoming);
        require(Math.abs(exitManagerHpAfter - exitManagerHpBefore) <= 1.0E-4F,
                "the manager's health is untouched by the revoked burst: " + exitManagerHpBefore + " -> "
                        + exitManagerHpAfter);
        require(sameVector(exitManagerVelocityAfter, exitManagerVelocityBefore),
                "the manager is not pushed by the revoked burst: " + exitManagerVelocityBefore + " -> "
                        + exitManagerVelocityAfter);
        require(Math.abs(exitDepartedHpAfter - exitDepartedHpBefore) <= 1.0E-4F,
                "the departed body takes nothing beyond the one direct hit: " + exitDepartedHpBefore + " -> "
                        + exitDepartedHpAfter);
        // The departed body's own velocity is NOT used as a burst measurement: the native direct hit it just took
        // applies vanilla's own hurt knockback (magnitude 0.4, horizontal) after the damage event, so its velocity
        // changes for a reason that has nothing to do with the burst. The push claim is carried by the manager, which
        // stands inside the burst radius and was hit by nothing else.
        // The block-callback counter belongs to an armed marked burst only (it is filled when that frame is
        // disarmed), so a wild burst cannot be read through it. Its own block list and the door beside it are what
        // the native path shows instead.
        require(exitWildToBlow > 0,
                "the unmarked control keeps its own native block list: " + exitWildToBlow);
        require(exitDoorAfterWild != exitDoorBefore,
                "the unmarked control really opens that same door: " + exitDoorBefore + " -> " + exitDoorAfterWild);
        require(exitFrameCleared, "no armed frame survives the revoked burst's own native call");
        require(exitOriginCleared, "no direct-hit origin survives the end of that native call");
        passed.add("T[markRemovedMidHit=true originKept=true burstDenyAll=true wildNative=true]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR T PASS markBefore={} markAfter={} originKept={} "
                        + "left={} managerKept={} burstArmed={} protectsDeparted={} protectsManager={} "
                        + "burstCallbacks={} managedDoor={} managerIncoming={} managerHp={}->{} "
                        + "wildToBlow={} wildDoor={} frameCleared={} originCleared={}", exitMarkBeforePresent,
                !exitMarkAfterRemoved, exitOriginRetained, exitMemberLeft, exitManagerKept, exitBurstArmed,
                exitBurstProtectsDeparted, exitBurstProtectsManager, exitBurstCallbacks, exitDoorAfterMarked,
                exitManagerIncoming, exitManagerHpBefore, exitManagerHpAfter, exitWildToBlow,
                exitDoorAfterWild, exitFrameCleared, exitOriginCleared);
    }

    /** Whether two velocity vectors are the same within a tenth of a block per tick. */
    private static boolean sameVector(@javax.annotation.Nullable Vec3 first, @javax.annotation.Nullable Vec3 second) {
        if (first == null || second == null) return first == second;
        return first.distanceToSqr(second) <= 1.0E-2D;
    }

    // ------------------------------------------------------------------
    // P: a real body across the first swept leg refuses the jump before it is paid for
    // ------------------------------------------------------------------

    /**
     * One breeze far outside its band, so its own planner really chooses the native long jump.
     *
     * <p>The fixture reads the arc that is really pending - its epoch, its price and whether it already left the
     * ground - and puts one ordinary living body across the exact first swept leg while the body is still inhaling.
     * What is asserted afterwards is the encounter's own account of that jump, not a coordinate guess: no frame was
     * ever written, nothing was charged, and the pose and the busy state are back to normal.</p>
     */
    private static void beginPrelaunchArc() {
        resetPlayers();
        arcJumper = spawnBreeze(0.5D, 18.0D);
        startBattle();
        arcStart = null;
        arcFrames = new Vec3[0];
        arcFramesAtBlock = -1;
        arcEpochAtBlock = -1;
        arcApAtBlock = -1;
        arcPaidAtBlock = -1;
        arcLegsHeld = false;
        arcBodyInFirstLeg = false;
        arcBodyOnlyInFirstLeg = false;
        arcRefusedFree = false;
        arcApAfter = -1;
        arcPaidAfter = -1;
        arcMovedAfter = -1.0D;
        arcPoseClean = false;
        arcNotBusy = false;
        pStage = 0;
        pDeadline = ticks + 2400;
        Tactical.LOGGER.info("[Tactical] breeze-repair P start jumper={} ap={}", arcJumper.position(),
                battle.actorAp(arcJumper));
    }

    private static void observePrelaunchArc() {
        if (battle == null || battle.finished()) return;
        UUID id = arcJumper == null ? null : arcJumper.getUUID();
        switch (pStage) {
            case 0 -> {
                BreezeController.JumpView view = arcJumper == null ? null : battle.fixtureJump(id);
                if (view == null) return;
                if (view.launched() || view.frames() > 0) {
                    throw new IllegalStateException("the jump really left the ground before a body was across its "
                            + "first leg: frames=" + view.frames() + " launched=" + view.launched());
                }
                require(view.arc().length >= 3, "the pending arc really has at least one airborne frame");
                arcStart = arcJumper.position();
                arcFrames = view.arc().clone();
                arcFramesAtBlock = view.frames();
                arcEpochAtBlock = view.epoch();
                arcPaidAtBlock = battle.actorPaidActions(arcJumper);
                arcApAtBlock = battle.actorAp(arcJumper);
                require(arcJumper.getPose() == net.minecraft.world.entity.Pose.INHALING,
                        "the native inhale of the jump really is the state this body is in: "
                                + arcJumper.getPose());
                // One ordinary living body across the exact first swept leg of the pending arc.
                Vec3 firstLegFrom = arcFrames[0];
                Vec3 firstLegTo = arcFrames[1];
                AABB swept = BodyGeometry.swept(arcJumper, firstLegFrom, firstLegTo);
                require(swept != null, "the real swept box of the first leg exists");
                Vec3 placeAt = placementInFirstLeg(arcJumper, swept, firstLegFrom, firstLegTo);
                arcBody = EntityType.COW.create(level);
                require(arcBody != null, "cow exists in this version");
                arcBody.setNoAi(true);
                arcBody.setPersistenceRequired();
                arcBody.setPos(placeAt.x, arcStart.y, placeAt.z);
                require(level.addFreshEntity(arcBody), "the ordinary body joined the world");
                require(!battle.participants().contains(arcBody),
                        "the body in the arc is an ordinary outsider, not a participant");
                arcBodyInFirstLeg = AmphibiousMovement.overlapsBody(level, arcJumper, swept);
                arcBodyOnlyInFirstLeg = !overlapsAnyLaterLeg(arcJumper, arcFrames, arcBody.getBoundingBox());
                arcLegsHeld = true;
                Tactical.LOGGER.info("[Tactical] breeze-repair P blocked firstLeg {}->{} body={} inLeg={} "
                                + "onlyFirstLeg={} frames={} epoch={} ap={} paid={}", firstLegFrom, firstLegTo,
                        arcBody.position(), arcBodyInFirstLeg, arcBodyOnlyInFirstLeg, view.frames(), view.epoch(),
                        arcApAtBlock, arcPaidAtBlock);
                pStage = 1;
            }
            case 1 -> {
                BreezeController.JumpView view = battle.fixtureJump(id);
                boolean airborne = arcJumper.getPose() == net.minecraft.world.entity.Pose.LONG_JUMPING;
                if (airborne) {
                    throw new IllegalStateException("the blocked arc really flew a frame instead of refusing: "
                            + arcJumper.position());
                }
                if (view != null) return;
                // The pending jump is gone and the body never left the ground.
                if (battle.isBusy()) return;
                arcApAfter = battle.actorAp(arcJumper);
                arcPaidAfter = battle.actorPaidActions(arcJumper);
                arcMovedAfter = arcJumper.position().distanceTo(arcStart);
                arcPoseClean = arcJumper.getPose() != net.minecraft.world.entity.Pose.INHALING
                        && arcJumper.getPose() != net.minecraft.world.entity.Pose.LONG_JUMPING;
                arcNotBusy = !battle.isBusy();
                if (arcPaidAfter == 0 && arcApAfter == arcApAtBlock) {
                    arcRefusedFree = true;
                    pStage = 2;
                } else if (ticks > pDeadline) {
                    pStage = 2;
                }
            }
            default -> { }
        }
    }

    /**
     * A point inside the first leg's swept box, as far from the jumper's own box as the sweep allows.
     *
     * <p>The sweep starts at the jumper's feet, so the two boxes overlap at the start; placing the body at the far
     * end of the swept box keeps it from being pushed by the jumper while still standing across that exact leg.</p>
     */
    private static Vec3 placementInFirstLeg(Breeze jumper, AABB swept, Vec3 from, Vec3 to) {
        Vec3 best = new Vec3((from.x + to.x) / 2.0D, from.y, (from.z + to.z) / 2.0D);
        double bestDistance = -1.0D;
        // Nine deterministic samples along the first leg; the one farthest from the jumper's own box wins.
        for (int step = 1; step <= 9; step++) {
            Vec3 sample = from.lerp(to, step / 9.0D);
            AABB candidateBox = BodyGeometry.swept(jumper, sample, sample);
            if (candidateBox == null) continue;
            if (!swept.contains(sample)) continue;
            double distance = candidateBox.getCenter()
                    .distanceToSqr(jumper.getBoundingBox().getCenter());
            if (distance <= bestDistance) continue;
            bestDistance = distance;
            best = new Vec3(sample.x, from.y, sample.z);
        }
        return best;
    }

    /** Whether that body box also lies across any leg after the first, so the first leg is the one that refused. */
    private static boolean overlapsAnyLaterLeg(Breeze jumper, Vec3[] arc, AABB body) {
        for (int index = 2; index < arc.length; index++) {
            AABB swept = BodyGeometry.swept(jumper, arc[index - 1], arc[index]);
            if (swept == null) continue;
            if (swept.intersects(body)) return true;
        }
        return false;
    }

    private static boolean prelaunchArcReady() {
        require(battle != null && !battle.finished(), "the arc encounter is still live");
        return pStage >= 2 || ticks > pDeadline;
    }

    private static void checkPrelaunchArc() {
        require(arcLegsHeld, "one real pending jump arc was read before its first frame");
        require(arcFramesAtBlock == 0 && arcPaidAtBlock == 0,
                "the arc was read before any frame and before any payment: frames=" + arcFramesAtBlock + " paid="
                        + arcPaidAtBlock);
        require(arcEpochAtBlock >= 0, "the pending jump really carries its own-turn epoch: " + arcEpochAtBlock);
        require(arcBodyInFirstLeg,
                "the ordinary body really stands across the exact first swept leg of that pending arc");
        require(arcRefusedFree, "the blocked jump was refused without being paid for");
        require(arcPaidAfter == 0, "a refused jump counts no paid action: " + arcPaidAfter);
        require(arcApAfter == arcApAtBlock,
                "a refused jump spends no AP: " + arcApAtBlock + " -> " + arcApAfter);
        require(arcMovedAfter >= 0.0D && arcMovedAfter <= 0.5D,
                "the body never took its first jump displacement: moved=" + round(arcMovedAfter));
        require(arcPoseClean, "the jump pose is given back after the refusal: " + arcJumper.getPose());
        require(arcNotBusy, "the encounter is not left waiting on a jump that never flew");
        passed.add("P[prefirstFrame=true bodyAcrossFirstLeg=true refusedFree=true poseClean=true]");
        Tactical.LOGGER.info("[Tactical] BREEZE-REPAIR P PASS framesAtBlock={} epoch={} ap={}->{} paid={}->{} "
                        + "moved={} pose={} inFirstLeg={} onlyFirstLeg={}", arcFramesAtBlock, arcEpochAtBlock,
                arcApAtBlock, arcApAfter, arcPaidAtBlock, arcPaidAfter, round(arcMovedAfter), arcJumper.getPose(),
                arcBodyInFirstLeg, arcBodyOnlyInFirstLeg);
    }
}
