package com.tactical.neoforge.battle;

import com.tactical.core.combat.BreezeRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.BreezeState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole tactical adapter of the exact vanilla breeze type, as one dedicated controller.
 *
 * <p>Everything a breeze decides lives here: its per-kind action quotas, the one frozen long-jump proposal of an
 * own turn, the native inhale/recover timeline of its wind charge, the one facing decision before the charge
 * leaves, and the bounded retreat it may walk instead of inventing a melee it does not have. The encounter supplies
 * only the facts a controller may not know: identity, AP, roster, battlefield, journal and the real entity writes.
 * That boundary is deliberate - an eleven-thousand-line encounter class with another six-hundred-line algorithm in
 * it is not reviewable, and the algorithms do not belong to it.</p>
 *
 * <p>Five native facts shape every decision below:</p>
 * <ol>
 *   <li><b>the charge has its own timeline</b> - 15 ticks of inhale, one facing decision, then 4 ticks of recovery;
 *       the projectile exists only if the native facing test and the band still hold at that exact moment;</li>
 *   <li><b>the jump is one solved arc</b> - vanilla's own {@code LongJumpUtil} answer, its five angles and its
 *       {@code 1.4} velocity ceiling, with the arc proof vanilla skips added here;</li>
 *   <li><b>the band is {@code (2, 16)}</b> - nearer than two blocks there is no shot at all, so a body that is too
 *       close steps away over ordinary per-edge movement instead of punching;</li>
 *   <li><b>nothing is charged before it exists</b> - a canceled charge, a refused facing or an interrupted
 *       pre-launch jump is free, while a charge that really joined the world and a body that really left the
 *       ground keep their price;</li>
 *   <li><b>the two quotas are per kind</b> - never the shared surcharge clock, which a jump advances and a shot
 *       reads, and which would otherwise make the one shot a jump exists for impossible.</li>
 * </ol>
 */
public final class BreezeController {

    /** The encounter's answers. Nothing here decides anything; it only reports its own facts. */
    public interface Seat {
        ServerLevel level();

        long tick();

        UUID sessionId();

        /** False once the encounter is finishing or ending: nothing new may begin. */
        boolean live();

        boolean insideZone(GridPos cell);

        /** True when that cell is free for this body: every other participant and its reservation counts. */
        boolean cellFree(GridPos cell, UUID bodyId);

        /** True when that cell is a real standing spot in the live world for a ground body. */
        boolean standable(GridPos cell);

        boolean isParticipant(Entity entity);

        boolean opposed(LivingEntity a, LivingEntity b);

        boolean allied(LivingEntity a, LivingEntity b);

        /** Whether that unit is the actor of the current turn. */
        boolean actorTurn(LivingEntity unit);

        /** Whether the encounter is carrying something that forbids a new action. */
        boolean encounterBusy();

        /** Whether the encounter's own motion driver owns that body right now. */
        boolean heldByMotion(UUID id);

        int windChargeBase();

        int jumpBase();

        /** The shared successful-action surcharge, applied to a base price. */
        int surcharge(int base, int paidActions);

        /** The per-edge price of ordinary ground movement for that unit, read from its real movement cost. */
        int moveEdgeCost(Unit unit);

        @javax.annotation.Nullable
        LivingEntity opponent(Mob actor);

        @javax.annotation.Nullable
        GridPos cellOf(LivingEntity entity);

        /** Turn the body's head and body towards that cell: one facing decision, never a search. */
        void faceTowards(Mob actor, GridPos cell);

        /** Vanilla's own {@code lookAt(EYES, target.position())}, as the native shot does before firing. */
        void aimAt(Mob actor, LivingEntity target);

        /**
         * The native {@code Shoot.isFacingTarget} answer read from the body's real view vector.
         *
         * <p>Asked once, after the one aim, and never re-decided per tick: a breeze that is not looking at its
         * target loses the charge instead of turning until it is.</p>
         */
        boolean facingTarget(Mob actor, LivingEntity target);

        /** Fire exactly one native wind charge and report every projectile the world really registered. */
        int fireWindCharge(Mob actor, LivingEntity target, java.util.function.Consumer<Entity> joined);

        /**
         * Whether that exact projectile is one live flight this encounter really registered.
         *
         * <p>The gate every per-projectile authority of this family is granted behind. A deflection record is a
         * relation filter over where a reversed shot may land, so it may only ever describe a shot this encounter
         * really fired and still tracks: a wild, a foreign or an already-gone projectile answers {@code false} and
         * therefore never acquires one.</p>
         */
        boolean recognizedFlight(net.minecraft.world.entity.projectile.Projectile projectile);

        /** Play the native inhale sound of one wind charge. */
        void playInhaleSound(Mob actor);

        /** Play the native charge sound of one long jump; vanilla uses its own sound for that, not the inhale. */
        void playJumpChargeSound(Mob actor);

        /** Play the native release sound of one charge leaving the snout. */
        void playShotSound(Mob actor);

        /** Play the native launch sound of one long jump. */
        void playJumpSound(Mob actor);

        /** Play the native landing sound of one long jump. */
        void playLandSound(Mob actor);

        /** Write the native pose the presentation asks for. */
        void pose(Mob actor, Pose pose);

        /** Write the native friction discard flag vanilla's own jump writes. */
        void discardFriction(Mob actor, boolean discard);

        /** Write the body's own yaw to its body rotation, as the native jump does before it launches. */
        void faceBodyRotation(Mob actor);

        /** Read the pose the body really has right now, so a refusal can put it back exactly. */
        Pose poseOf(Mob actor);

        /** A unit behind that exact mob, or {@code null} when it is not this encounter's. */
        @javax.annotation.Nullable
        Unit unit(Mob mob);

        List<Unit> units();

        void journal(String channel, Component text);

        void stateChanged();

        /** Adopt a landed native position as this unit's authoritative cell. */
        void landed(Unit unit, GridPos cell, Vec3 feet);

        /** Leave the encounter by name, keeping the body where it really is. */
        void releaseUnit(Unit unit, String journalKey);

        /** The difficulty id the native spread is computed from. */
        int difficultyId();
    }

    /** The encounter's own record of one unit. Implemented by its roster entry, not by a copy. */
    public interface Unit {
        Mob mob();

        GridPos cell();

        void cell(GridPos cell);

        void airOffset(Vec3 offset);

        int ap();

        void ap(int value);

        int paidActions();

        /** Count one successful paid action: this is the surcharge clock, not a per-kind quota. */
        void paidActionsIncremented();

        /** How many own turns this unit has had: the proposal epoch. */
        int ownTurns();

        BattleSide side();
    }

    /** What one encounter tick did: whether anything changed, and whose turn is now over. */
    public record Tick(boolean changed, @javax.annotation.Nullable UUID handBack) {
        static final Tick IDLE = new Tick(false, null);
    }

    /** One native charge's bounded timeline. */
    private static final class Charge {
        final UUID targetId;
        final int epoch;
        final int cost;
        int inhale = BreezeRules.INHALE_TICKS;
        boolean released;
        int recovery;

        Charge(UUID targetId, int epoch, int cost) {
            this.targetId = targetId;
            this.epoch = epoch;
            this.cost = cost;
        }
    }

    /** One native long jump in flight. */
    private static final class Jump {
        final BreezeJump.Proposal proposal;
        final int epoch;
        final int cost;
        final Pose original;
        int inhale = BreezeRules.JUMP_INHALE_TICKS;
        int frames;
        boolean launched;
        boolean paid;

        Jump(BreezeJump.Proposal proposal, int epoch, int cost, Pose original) {
            this.proposal = proposal;
            this.epoch = epoch;
            this.cost = cost;
            this.original = original;
        }
    }

    /** How one jump frame ended. */
    public enum JumpStep {
        /** Nothing to step: the unit is not jumping. */
        IDLE,
        /** Still inhaling, or the frame really advanced; the body still owns the turn. */
        ADVANCED,
        /** The body reached its landing; the run is over and the turn may continue. */
        LANDED,
        /** The jump never left the ground and cost nothing; it is refused and the turn ends. */
        REFUSED,
        /** The jump really started and cannot continue; the body stops where it is and the fall rule owns it. */
        STOPPED
    }

    /** The controlled state of one breeze. Dropped in one place, so a freed breeze is a plain breeze again. */
    private static final class Run {
        BreezeHandler handler;
        int shotsUsed;
        int maneuversUsed;
        Charge charge;
        Jump jump;
        /** The one bounded jump proposal of this own turn, with the epoch and target it was made for. */
        BreezeJump.Proposal proposal;
        boolean proposalScanned;
        int proposalEpoch = -1;
        UUID proposalTarget;
        /** The pose and friction flag vanilla had at admission, so a release can give them back. */
        Pose originalPose;
        boolean originalDiscardFriction;
    }

    private final Seat seat;
    private final Map<UUID, Run> runs = new LinkedHashMap<>();

    public BreezeController(Seat seat) {
        this.seat = seat;
    }

    /** Exact type only: no other monster and no mob sharing a superclass. */
    public static boolean isActor(Mob mob) {
        return mob != null && mob.getType() == EntityType.BREEZE;
    }

    /** Whether that exact body is currently controlled by this encounter. */
    public boolean controls(UUID id) {
        return id != null && runs.containsKey(id);
    }

    public String describe() {
        if (runs.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<UUID, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            if (builder.length() > 0) builder.append(',');
            builder.append(entry.getKey())
                    .append("/shots=").append(run.shotsUsed)
                    .append("/man=").append(run.maneuversUsed)
                    .append(run.charge == null ? "/idle" : run.charge.released ? "/recovering" : "/inhaling")
                    .append(run.jump == null ? "" : "/jumping=" + run.jump.frames);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Take one breeze into this encounter and return whether it was taken.
     *
     * <p>The pose and the friction flag are snapshotted before anything is written, so a refused admission can give
     * the creature back exactly as it was, and a normal exit keeps whatever the body really ended up with.</p>
     */
    public boolean install(Unit unit) {
        if (unit == null || !isActor(unit.mob()) || !(unit.mob() instanceof Breeze breeze)) return false;
        if (runs.containsKey(breeze.getUUID())) release(unit);
        Run run = new Run();
        run.handler = new Handler(unit);
        run.originalPose = breeze.getPose();
        run.originalDiscardFriction = breeze.shouldDiscardFriction();
        runs.put(breeze.getUUID(), run);
        ((TacticalBreezeAccess) breeze).tactical$setHandler(run.handler);
        // A controlled breeze never runs its own Brain, its own goals or its own shooting: only the physical tick
        // and its presentation remain, exactly like every other body this encounter holds.
        return true;
    }

    /** Give one breeze back to its own vanilla tick, keeping its real final position and its real effects. */
    public void release(Unit unit) {
        if (unit == null || unit.mob() == null) return;
        Run run = runs.remove(unit.mob().getUUID());
        if (run != null) {
            run.handler = null;
            run.charge = null;
            run.jump = null;
            run.proposal = null;
            run.proposalScanned = false;
            run.proposalTarget = null;
        }
        if (unit.mob() instanceof Breeze breeze) {
            BreezeWindCharges.forgetActor(breeze.getUUID());
            if (breeze instanceof TacticalBreezeAccess access) access.tactical$setHandler(null);
            if (run != null) {
                seat.pose(breeze, run.originalPose);
                seat.discardFriction(breeze, run.originalDiscardFriction);
            }
        }
    }

    /** Drop every controlled breeze: encounter teardown. */
    public void clear() {
        for (Unit unit : List.copyOf(seat.units())) {
            if (controls(unit.mob().getUUID())) release(unit);
        }
        runs.clear();
    }

    /** Start one own turn of a controlled breeze: both quotas and the cached proposal go back to zero. */
    public void beginTurn(Unit unit) {
        Run run = live(unit);
        if (run == null) return;
        run.shotsUsed = 0;
        run.maneuversUsed = 0;
        run.proposal = null;
        run.proposalScanned = false;
        run.proposalEpoch = -1;
        run.proposalTarget = null;
        // A jump or charge that somehow outlived its own turn is dropped, and the body keeps where it really is.
        if (run.jump != null) {
            seat.discardFriction(unit.mob(), false);
            seat.pose(unit.mob(), run.jump.original);
            run.jump = null;
        }
        run.charge = null;
    }

    /** True while this breeze's own native charge is still running its timeline. */
    public boolean charging(UUID id) {
        Run run = id == null ? null : runs.get(id);
        return run != null && run.charge != null;
    }

    /** True while that breeze is flying a jump, so its own animation owns the turn. */
    public boolean jumping(UUID id) {
        Run run = id == null ? null : runs.get(id);
        return run != null && run.jump != null;
    }

    /** True while any controlled breeze holds the encounter with a charge or a jump. */
    public boolean busy() {
        for (Run run : runs.values()) {
            if (run.charge != null || run.jump != null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ per-tick timeline

    /**
     * One tick of every controlled breeze's own native timeline.
     *
     * <p>The charge inhales for the native count, decides its facing exactly once, spawns the real projectile or
     * cancels for free, and then recovers before it hands the turn back. A jump is advanced by the encounter's own
     * step instead, because a body in the air owns the tie to its own frames.</p>
     */
    public Tick tick() {
        boolean changed = false;
        UUID handBack = null;
        for (Unit unit : List.copyOf(seat.units())) {
            Run run = runs.get(unit.mob().getUUID());
            if (run == null || run.charge == null) continue;
            Charge charge = run.charge;
            LivingEntity target = target(unit);
            if (target == null || !seat.live() || !seat.actorTurn(unit.mob()) || run.jump != null) {
                if (DeveloperTrace.enabled()) {
                    Tactical.LOGGER.info("[Tactical] dev-trace breeze charge dropped session={} uuid={} "
                                    + "targetNull={} live={} actorTurn={} jumping={} opponent={}", seat.sessionId(),
                            unit.mob().getUUID(), target == null, seat.live(), seat.actorTurn(unit.mob()),
                            run.jump != null, seat.opponent(unit.mob()));
                }
                // The encounter, the actor or the locked opponent stopped being real: nothing was spawned, so
                // nothing is owed and no recovery has to run.
                run.charge = null;
                seat.pose(unit.mob(), run.originalPose);
                changed = true;
                handBack = unit.mob().getUUID();
                continue;
            }
            if (!charge.released) {
                if (--charge.inhale > 0) {
                    changed = true;
                    continue;
                }
                boolean fired = release(unit, run, charge, target);
                changed = true;
                if (!fired) {
                    run.charge = null;
                    handBack = unit.mob().getUUID();
                }
                continue;
            }
            if (--charge.recovery > 0) {
                changed = true;
                continue;
            }
            run.charge = null;
            seat.pose(unit.mob(), run.originalPose);
            changed = true;
            handBack = unit.mob().getUUID();
        }
        return changed || handBack != null ? new Tick(changed, handBack) : Tick.IDLE;
    }

    /**
     * The one native release: aim once, decide the facing once, and only then spawn the real charge.
     *
     * <p>Everything is re-proved here because the timeline really ran: the target may have died, the band may have
     * been lost, the turn may have moved on. A refusal produces no projectile, so nothing is charged and no
     * recovery is owed.</p>
     */
    private boolean release(Unit unit, Run run, Charge charge, LivingEntity target) {
        if (!target.getUUID().equals(charge.targetId)) {
            // The one line that must be answerable: which body this charge was committed against, which body is
            // locked now, and the facts that decide the band. A frozen charge may never quietly follow a new lock.
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused target session={} uuid={} "
                                + "frozen={} current={} currentPos={} frozenPos={} distanceSq={}", seat.sessionId(),
                        unit.mob().getUUID(), charge.targetId, target.getUUID(), target.position(),
                        seat.cellOf(target), unit.mob().distanceToSqr(target.position()));
            }
            return refused(unit);
        }
        if (unit.ownTurns() != charge.epoch) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused epoch session={} uuid={} "
                                + "ownTurns={} epoch={} ap={} cost={} shots={}", seat.sessionId(),
                        unit.mob().getUUID(), unit.ownTurns(), charge.epoch, unit.ap(), charge.cost, run.shotsUsed);
            }
            return false;
        }
        if (!BreezeRules.withinQuota(run.shotsUsed, BreezeRules.SHOT_QUOTA)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused quota session={} uuid={} "
                        + "shots={}", seat.sessionId(), unit.mob().getUUID(), run.shotsUsed);
            }
            return false;
        }
        if (unit.ap() < charge.cost) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused ap session={} uuid={} ap={} "
                        + "cost={}", seat.sessionId(), unit.mob().getUUID(), unit.ap(), charge.cost);
            }
            return false;
        }
        if (!BreezeRules.withinShotBand(unit.mob().distanceToSqr(target))) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused band session={} uuid={} "
                        + "distanceSq={}", seat.sessionId(), unit.mob().getUUID(),
                        unit.mob().distanceToSqr(target));
            }
            return refused(unit);
        }
        seat.aimAt(unit.mob(), target);
        if (!seat.facingTarget(unit.mob(), target)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze release refused facing session={} uuid={} "
                                + "view={} to={}", seat.sessionId(), unit.mob().getUUID(),
                        ((Breeze) unit.mob()).getViewVector(1.0F),
                        target.position().subtract(unit.mob().position()));
            }
            // One facing decision, and it failed: the native charge is lost instead of turning until it hits.
            return refused(unit);
        }
        Breeze breeze = (Breeze) unit.mob();
        seat.playShotSound(breeze);
        BreezeWindCharges.Owner owner = new BreezeWindCharges.Owner(seat.sessionId(), breeze.getUUID(),
                target.getUUID(), unit.ownTurns());
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int fired = seat.fireWindCharge(breeze, target, joined -> {
            try {
                if (joined instanceof BreezeWindCharge shot) BreezeWindCharges.mark(shot, owner);
            } catch (RuntimeException failure) {
                nativeFailure[0] = failure;
            }
        });
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native breeze wind charge marking failed for {} ({} joined)",
                    breeze.getUUID(), Math.max(0, fired), nativeFailure[0]);
        }
        if (fired <= 0) return refused(unit);
        // The price is committed once, after the world really took the projectile.
        unit.ap(Math.max(0, unit.ap() - charge.cost));
        unit.paidActionsIncremented();
        run.shotsUsed++;
        charge.released = true;
        charge.recovery = BreezeRules.RECOVER_TICKS;
        seat.journal("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_BREEZE_WIND_CHARGE,
                BattleText.name(breeze), BattleText.name(target)));
        seat.stateChanged();
        return true;
    }

    /** One named refusal: nothing joined the world and nothing was charged. */
    private boolean refused(Unit unit) {
        seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_BREEZE_NO_SHOT,
                BattleText.name(unit.mob())));
        seat.stateChanged();
        return false;
    }

    // ------------------------------------------------------------------ planner answers

    /** What the planner is allowed to know, read from the same state that prices and commits. */
    public BreezeState state(Unit unit) {
        Run run = live(unit);
        if (run == null) return BreezeState.NONE;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy() || run.charge != null || run.jump != null) {
            return new BreezeState(true, false, 0, false, 0, true, true);
        }
        boolean shotUsed = !BreezeRules.withinQuota(run.shotsUsed, BreezeRules.SHOT_QUOTA);
        boolean maneuvered = !BreezeRules.withinQuota(run.maneuversUsed, BreezeRules.MANEUVER_QUOTA);
        return new BreezeState(true, canShoot(unit, run), shotCost(unit),
                canJump(unit, run), jumpCost(unit), shotUsed, maneuvered);
    }

    private boolean canShoot(Unit unit, Run run) {
        if (!BreezeRules.withinQuota(run.shotsUsed, BreezeRules.SHOT_QUOTA)) return false;
        LivingEntity target = target(unit);
        if (target == null) return false;
        if (!BreezeRules.withinShotBand(unit.mob().distanceToSqr(target))) return false;
        int cost = shotCost(unit);
        return cost > 0 && unit.ap() >= cost;
    }

    private boolean canJump(Unit unit, Run run) {
        if (!BreezeRules.withinQuota(run.maneuversUsed, BreezeRules.MANEUVER_QUOTA)) return false;
        LivingEntity target = target(unit);
        if (target == null) return false;
        // A jump exists to buy a shot: one made while a shot is already legal trades the attack for a move.
        if (canShoot(unit, run)) return false;
        int cost = jumpCost(unit);
        if (cost <= 0 || unit.ap() < cost) return false;
        if (!(unit.mob() instanceof Breeze breeze)) return false;
        double distance = breeze.position().distanceTo(target.position());
        int free = freeBlocksAbove(breeze);
        boolean onGround = standsOnRealSupport(unit, breeze);
        if (!BreezeRules.jumpAllowed(onGround, breeze.isInWater(), shouldSwim(breeze), free, distance)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump gates refused session={} tick={} uuid={} "
                                + "onGround={} nativeOnGround={} inWater={} swim={} freeAbove={} distance={}",
                        seat.sessionId(), seat.tick(), breeze.getUUID(), onGround, breeze.onGround(),
                        breeze.isInWater(), shouldSwim(breeze), free, distance);
            }
            return false;
        }
        boolean resolved = proposal(unit, run, target) != null;
        if (!resolved && DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace breeze jump proposal refused session={} tick={} uuid={} "
                    + "distance={} from={} target={}", seat.sessionId(), seat.tick(), breeze.getUUID(), distance,
                    breeze.position(), target.position());
        }
        return resolved;
    }

    private int shotCost(Unit unit) {
        return seat.surcharge(seat.windChargeBase(), unit.paidActions());
    }

    private int jumpCost(Unit unit) {
        return seat.surcharge(seat.jumpBase(), unit.paidActions());
    }

    /**
     * Whether this body is really standing on something the encounter would accept as a floor.
     *
     * <p>Vanilla's own take-off test asks {@code onGround()}, which is a fact about a body its own physics really
     * moves. A controlled body of this encounter does not move itself - the encounter's grid and its own animation
     * own every displacement - so its {@code onGround} flag can stay false while it stands perfectly well on the
     * floor it was admitted on. The real question is therefore asked of the encounter's own terrain: the body holds
     * a cell that is a legal stand. A body the motion driver is holding up (levitation) is deliberately not one:
     * that body is floating, not standing, which is exactly the native restriction's intent.</p>
     */
    private boolean standsOnRealSupport(Unit unit, Breeze breeze) {
        if (breeze.onGround()) return true;
        GridPos cell = unit.cell();
        if (cell == null) return false;
        if (seat.heldByMotion(breeze.getUUID())) return false;
        return seat.standable(cell);
    }

    /**
     * The native {@code Swim.shouldSwim} question, asked of the same body state vanilla's own gate reads.
     *
     * <p>Vanilla's {@code LongJump.canRun} refuses a body that is in the water-swim state. Its own
     * {@code Swim.shouldSwim} is a behaviour helper, not a public predicate, so the one fact it establishes for a
     * breeze is reproduced here: a body whose eyes are under water swims. Nothing else is claimed.</p>
     */
    private static boolean shouldSwim(Breeze breeze) {
        return breeze.isInWater() && breeze.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
    }

    /** Whole free blocks above the body, bounded by the native required count. */
    private static int freeBlocksAbove(Breeze breeze) {
        AABB box = breeze.getBoundingBox();
        int free = 0;
        for (int step = 1; step <= BreezeRules.JUMP_REQUIRED_AIR_BLOCKS; step++) {
            AABB probe = box.move(0.0D, step, 0.0D);
            if (!breeze.level().noCollision(breeze, probe)) break;
            free = step;
        }
        return free;
    }

    // ------------------------------------------------------------------ the one native charge

    /**
     * Commit the one wind charge of this own turn and start its native timeline.
     *
     * <p>Nothing exists yet: this opens the native inhale and the pose. The projectile is spawned by the tick that
     * finishes the inhale, and only if the band, the facing and the price all still hold at that exact moment - so
     * a commitment that is refused later costs nothing at all.</p>
     */
    public boolean shoot(Unit unit) {
        Run run = live(unit);
        if (run == null) return false;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy() || run.jump != null) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze shoot refused session={} uuid={} actorTurn={} "
                                + "busy={} jumping={}", seat.sessionId(), unit.mob().getUUID(),
                        seat.actorTurn(unit.mob()), seat.encounterBusy(), run.jump != null);
            }
            return false;
        }
        if (!canShoot(unit, run)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze cannot shoot session={} uuid={} target={} "
                                + "distanceSq={} ap={} cost={} shots={}", seat.sessionId(), unit.mob().getUUID(),
                        target(unit), target(unit) == null ? -1.0D
                                : unit.mob().distanceToSqr(target(unit)), unit.ap(), shotCost(unit), run.shotsUsed);
            }
            return refused(unit);
        }
        LivingEntity target = target(unit);
        if (target == null) return false;
        run.charge = new Charge(target.getUUID(), unit.ownTurns(), shotCost(unit));
        // The native attack pose is the visible charge; the one facing decision is taken at the release, exactly
        // where vanilla takes it, not per tick during the inhale.
        seat.pose(unit.mob(), Pose.SHOOTING);
        seat.playInhaleSound(unit.mob());
        seat.stateChanged();
        return true;
    }

    // ------------------------------------------------------------------ the one ground slide

    /**
     * The one bounded ground route of this own turn: real ground edges, priced per edge.
     *
     * <p>A breeze has no melee, so a body that is nearer than its native band cannot simply punch something, and a
     * body further out than the band cannot reach it - the native habit in both cases is ordinary sliding, never an
     * invented attack. So the route steps away when the opponent is too close and toward it when it is too far,
     * over the same per-edge price and the same terrain gate every other body walks with. The route is at most two
     * edges and never more than the AP the same state quotes, so it can neither wander the battlefield nor spend a
     * turn's resources on a search.</p>
     */
    public List<GridPos> slide(Unit unit) {
        Run run = live(unit);
        if (run == null) return List.of();
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy() || run.charge != null || run.jump != null) {
            return List.of();
        }
        LivingEntity target = target(unit);
        if (target == null) return List.of();
        // A legal shot or a legal jump is a better answer than walking.
        if (canShoot(unit, run) || canJump(unit, run)) return List.of();
        GridPos start = unit.cell();
        if (start == null) return List.of();
        int edge = seat.moveEdgeCost(unit);
        if (edge <= 0) return List.of();
        int budget = Math.min(2, unit.ap() / edge);
        if (budget <= 0) return List.of();
        // Nearer than the band means stepping out; further out than the band means closing in. Both are the same
        // ordinary per-edge movement, and neither advances the manoeuvre quota.
        boolean retreat = BreezeRules.withinShotBand(unit.mob().distanceToSqr(target))
                || unit.mob().distanceToSqr(target) < (double) BreezeRules.SHOT_RANGE_MIN_SQ;
        List<GridPos> route = new ArrayList<>(budget);
        GridPos current = start;
        double distance = target.position().distanceToSqr(centerOf(current));
        for (int step = 0; step < budget; step++) {
            GridPos chosen = null;
            double best = distance;
            for (GridPos candidate : neighbours(current)) {
                if (!seat.insideZone(candidate) || !seat.cellFree(candidate, unit.mob().getUUID())) continue;
                if (!seat.standable(candidate)) continue;
                double candidateDistance = target.position().distanceToSqr(centerOf(candidate));
                // Retreat needs a strictly larger distance, approach a strictly smaller one; the first candidate
                // that improves the chosen direction wins, so the plan never flip-flops between two equal cells.
                if (retreat ? candidateDistance <= best + 1.0E-6D : candidateDistance >= best - 1.0E-6D) continue;
                chosen = candidate;
                best = candidateDistance;
            }
            if (chosen == null) break;
            route.add(chosen);
            current = chosen;
            distance = best;
        }
        return List.copyOf(route);
    }

    /** The four ordinary ground neighbours of one cell, in a stable order. */
    private static List<GridPos> neighbours(GridPos cell) {
        return List.of(new GridPos(cell.x() + 1, cell.y(), cell.z()),
                new GridPos(cell.x(), cell.y(), cell.z() + 1),
                new GridPos(cell.x() - 1, cell.y(), cell.z()),
                new GridPos(cell.x(), cell.y(), cell.z() - 1));
    }

    private static Vec3 centerOf(GridPos cell) {
        return new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
    }

    // ------------------------------------------------------------------ the one native jump

    /**
     * Commit the one cached jump of this own turn: the native inhale starts here, and the body leaves the ground
     * only on the frame the encounter really proves.
     */
    public boolean jump(Unit unit) {
        Run run = live(unit);
        if (run == null) return false;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy()) return false;
        if (!canJump(unit, run)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump refused at gates session={} tick={} "
                        + "uuid={} ap={} paid={} maneuvers={}", seat.sessionId(), seat.tick(),
                        unit.mob().getUUID(), unit.ap(), unit.paidActions(), run.maneuversUsed);
            }
            seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_BREEZE_NO_JUMP,
                    BattleText.name(unit.mob())));
            seat.stateChanged();
            return false;
        }
        LivingEntity target = target(unit);
        if (target == null) return false;
        BreezeJump.Proposal proposal = proposal(unit, run, target);
        if (proposal == null) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump refused at commit session={} tick={} "
                        + "uuid={} (no proved landing)", seat.sessionId(), seat.tick(), unit.mob().getUUID());
            }
            return false;
        }
        Pose original = seat.poseOf(unit.mob());
        run.jump = new Jump(proposal, unit.ownTurns(), jumpCost(unit), original);
        // The native charge pose and sound come first: 10 ticks of inhale before a single frame of the arc.
        seat.pose(unit.mob(), Pose.INHALING);
        seat.playJumpChargeSound(unit.mob());
        seat.stateChanged();
        return true;
    }

    /**
     * One frame of the native jump.
     *
     * <p>The whole arc is proved once at launch; here every leg is proved again against the live world before the
     * body is written, because the world may have changed in between - including the last leg, which is asked
     * whether the ground its contact was solved onto still exists. The price is committed on the first real
     * displacement only - a jump refused before that is free - and once the body has left the ground it is never
     * rewound: an interrupted jump stops exactly where the body really is and hands the descent to the existing
     * fall rule.</p>
     */
    public JumpStep stepJump(Unit unit) {
        Run run = live(unit);
        if (run == null || run.jump == null) return JumpStep.IDLE;
        Jump jump = run.jump;
        if (!(unit.mob() instanceof Breeze breeze)) {
            run.jump = null;
            return JumpStep.STOPPED;
        }
        // The per-turn quota is a commit-time decision and is deliberately not re-asked here: the run itself is
        // what spent it, so re-checking it would kill the arc on the very frame that paid for it. What may still
        // invalidate a flight is the real world: the encounter ending, the turn moving on, or the body leaving.
        if (!seat.live() || !seat.actorTurn(breeze) || unit.ownTurns() != jump.epoch) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump abandoned session={} tick={} uuid={} "
                                + "live={} actorTurn={} maneuvers={} paid={}", seat.sessionId(), seat.tick(),
                        breeze.getUUID(), seat.live(), seat.actorTurn(breeze), run.maneuversUsed, jump.paid);
            }
            return stopJump(unit, run, jump, !jump.paid);
        }
        // The arc was solved for one exact opponent. If that locked target is gone - dead, removed, replaced, or no
        // longer this body's opposed opponent - this frame has nothing left to fly for, and continuing would land
        // the body on a plan about a body that no longer exists. Checked here, before the first frame and again
        // before every leg, so a stale arc can never be flown to its end.
        if (!frozenTargetHolds(unit, jump)) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump target lost session={} tick={} uuid={} "
                                + "frozen={} frames={} paid={}", seat.sessionId(), seat.tick(), breeze.getUUID(),
                        jump.proposal.targetId(), jump.frames, jump.paid);
            }
            return stopJump(unit, run, jump, !jump.paid);
        }
        if (!jump.launched) {
            if (--jump.inhale > 0) return JumpStep.ADVANCED;
            // The price was quoted at commit time; before the first leg it is asked once more against the live AP,
            // because the body may have spent AP on something else in the meantime. After the first real
            // displacement the jump is bought and this question is never asked again.
            if (!jump.paid && unit.ap() < jump.cost) {
                if (DeveloperTrace.enabled()) {
                    Tactical.LOGGER.info("[Tactical] dev-trace breeze jump refused ap session={} tick={} uuid={} "
                                    + "ap={} cost={}", seat.sessionId(), seat.tick(), unit.mob().getUUID(),
                            unit.ap(), jump.cost);
                }
                return stopJump(unit, run, jump, true);
            }
            if (!launchHolds(unit, jump)) {
                if (DeveloperTrace.enabled()) {
                    Tactical.LOGGER.info("[Tactical] dev-trace breeze jump launch arc refused session={} tick={} "
                                    + "uuid={} from={} landing={} frames={}", seat.sessionId(), seat.tick(),
                            unit.mob().getUUID(), unit.mob().position(), jump.proposal.cell(),
                            jump.proposal.frames().length);
                }
                return stopJump(unit, run, jump, true);
            }
            // Native launch presentation: the long-jump pose, the facing write, the friction discard and the sound.
            seat.playJumpSound(breeze);
            seat.pose(breeze, Pose.LONG_JUMPING);
            seat.faceBodyRotation(breeze);
            seat.discardFriction(breeze, true);
            jump.launched = true;
        }
        int next = jump.frames + 1;
        Vec3 point = jump.proposal.frame(next);
        boolean finalFrame = next >= jump.proposal.frames().length - 1;
        // The last leg is asked for the ground as well as for clearance: the arc was solved onto a support plane
        // that may have been taken away since, and a removed floor creates no collision for a swept leg to find.
        boolean legHolds = finalFrame
                ? BreezeJump.landingStillHolds(seat.level(), breeze, breeze.position(), point, gates(unit))
                : BreezeJump.legStillHolds(seat.level(), breeze, breeze.position(), point, gates(unit));
        if (!legHolds) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace breeze jump leg refused session={} tick={} uuid={} "
                                + "from={} to={} frames={} paid={} final={}", seat.sessionId(), seat.tick(),
                        breeze.getUUID(), breeze.position(), point, jump.frames, jump.paid, finalFrame);
            }
            return stopJump(unit, run, jump, !jump.paid);
        }
        breeze.setPos(point.x, point.y, point.z);
        breeze.setDeltaMovement(Vec3.ZERO);
        breeze.fallDistance = 0.0F;
        jump.frames = next;
        if (!jump.paid) {
            // The first real displacement is the moment the jump is bought: the frozen quote, one paid action and
            // the single manoeuvre this own turn may take, all at this one point.
            jump.paid = true;
            unit.ap(Math.max(0, unit.ap() - jump.cost));
            unit.paidActionsIncremented();
            run.maneuversUsed++;
            seat.stateChanged();
        }
        if (next >= jump.proposal.frames().length - 1) {
            seat.playLandSound(breeze);
            seat.pose(breeze, jump.original);
            seat.discardFriction(breeze, false);
            run.jump = null;
            seat.landed(unit, jump.proposal.cell(), jump.proposal.feet());
            seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_BREEZE_JUMP,
                    BattleText.name(breeze), BattleText.literal(jump.proposal.cell().toString())));
            seat.stateChanged();
            return JumpStep.LANDED;
        }
        return JumpStep.ADVANCED;
    }

    /**
     * Stop a jump that can no longer be flown.
     *
     * @param free true when the body never left the ground, so nothing was charged and nothing is owed
     */
    private JumpStep stopJump(Unit unit, Run run, Jump jump, boolean free) {
        run.jump = null;
        if (unit.mob() instanceof Breeze breeze) {
            seat.pose(breeze, jump.original);
            seat.discardFriction(breeze, false);
            breeze.setDeltaMovement(Vec3.ZERO);
            breeze.fallDistance = 0.0F;
        }
        seat.stateChanged();
        if (free && !jump.paid) {
            seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_BREEZE_NO_JUMP,
                    BattleText.name(unit.mob())));
            return JumpStep.REFUSED;
        }
        // The body really flew: it keeps its price and stops exactly where it is, so the existing fall rule owns
        // whatever the real position now needs.
        GridPos real = BreezeJump.cellOf(unit.mob().position());
        unit.cell(real);
        unit.airOffset(unit.mob().position().subtract(centerOf(real)));
        return JumpStep.STOPPED;
    }

    /** The launch gate: every frame of the proved arc still holds against the live world, and its landing is real. */
    private boolean launchHolds(Unit unit, Jump jump) {
        if (!(unit.mob() instanceof Breeze breeze)) return false;
        if (!frozenTargetHolds(unit, jump)) return false;
        BreezeJump.Proposal proposal = jump.proposal;
        int last = proposal.frames().length - 1;
        for (int index = 1; index <= last; index++) {
            Vec3 from = proposal.frame(index - 1);
            Vec3 to = proposal.frame(index);
            // The one leg that ends the flight is asked whether its contact still stands on real ground; the
            // others are only asked for clearance.
            boolean holds = index == last
                    ? BreezeJump.landingStillHolds(seat.level(), breeze, from, to, gates(unit))
                    : BreezeJump.legStillHolds(seat.level(), breeze, from, to, gates(unit));
            if (!holds) return false;
        }
        return true;
    }

    /**
     * The one opponent this jump was solved for, still live and still this unit's opposed opponent.
     *
     * <p>The arc, the landing and the follow-up shot all exist for that body: a jump whose target left would keep
     * spending a paid manoeuvre on a plan about something that is no longer there.</p>
     */
    private boolean frozenTargetHolds(Unit unit, Jump jump) {
        LivingEntity target = target(unit);
        return target != null && target.getUUID().equals(jump.proposal.targetId());
    }

    /** The geometry the arc proof asks this encounter. */
    private BreezeJump.Gates gates(Unit unit) {
        UUID id = unit.mob().getUUID();
        return new BreezeJump.Gates() {
            @Override public boolean insideZone(GridPos cell) { return seat.insideZone(cell); }

            @Override public boolean cellFree(GridPos cell) { return seat.cellFree(cell, id); }

            @Override public boolean bodyInsideZone(AABB box) {
                return BodyGeometry.boxInsideZone(box, seat::insideZone);
            }
        };
    }

    @javax.annotation.Nullable
    private BreezeJump.Proposal proposal(Unit unit, Run run, LivingEntity target) {
        int epoch = unit.ownTurns();
        if (run.proposalScanned && run.proposalEpoch == epoch && target.getUUID().equals(run.proposalTarget)) {
            return run.proposal;
        }
        BreezeJump.Proposal resolved = null;
        if (unit.mob() instanceof Breeze breeze) {
            resolved = BreezeJump.resolve(seat.level(), breeze, target, breeze.getRandom(), gates(unit));
        }
        run.proposal = resolved;
        run.proposalScanned = true;
        run.proposalEpoch = epoch;
        run.proposalTarget = target.getUUID();
        return resolved;
    }

    // ------------------------------------------------------------------ the deflection seam

    /** The routing seam a controlled breeze's own deflection answers to. */
    private final class Handler implements BreezeHandler {
        private final Unit unit;

        private Handler(Unit unit) {
            this.unit = unit;
        }

        @Override
        public void tactical$deflected(net.minecraft.world.entity.projectile.Projectile projectile) {
            // A real native deflection of this body: the projectile keeps the owner vanilla gave it, and this
            // encounter records only its own narrow relation authority over where the reversed shot may land.
            // Recorded for one of this encounter's own live flights only, so a wild, a foreign or an already gone
            // shot can never borrow an authority this encounter never granted it.
            if (live(unit) == null) return;
            if (!seat.recognizedFlight(projectile)) return;
            BreezeDeflections.record(projectile, seat.sessionId(), unit.mob().getUUID());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Read-only view of the one cached jump this unit really holds right now, or {@code null} when it holds none.
     *
     * <p>It exists so a fixture can place a real body across the exact first swept leg of the arc that is really
     * pending, instead of guessing a coordinate: the arc, the epoch, the price and whether the jump has already left
     * the ground are the encounter's own facts, and a proof about "before the first physical frame" has to read them
     * rather than re-derive them. Nothing here decides anything and nothing is written.</p>
     */
    @javax.annotation.Nullable
    JumpView fixtureJumpView(UUID id) {
        Run run = id == null ? null : runs.get(id);
        if (run == null || run.jump == null) return null;
        Jump jump = run.jump;
        BreezeJump.Proposal proposal = jump.proposal;
        return new JumpView(jump.frames, jump.launched, jump.paid, jump.cost, jump.inhale, jump.epoch,
                proposal.frames().clone(), proposal.feet(), proposal.targetId(), run.maneuversUsed);
    }

    /** The immutable facts of one pending native jump frame, for a fixture's readback only. */
    record JumpView(int frames, boolean launched, boolean paid, int cost, int inhale, int epoch, Vec3[] arc,
                    Vec3 feet, UUID targetId, int maneuvers) { }

    @javax.annotation.Nullable
    private Run live(Unit unit) {
        if (unit == null || unit.mob() == null) return null;
        Run run = runs.get(unit.mob().getUUID());
        if (run == null) return null;
        if (!seat.live()) return null;
        if (!(unit.mob() instanceof Breeze breeze)) return null;
        if (!breeze.isAlive() || breeze.isRemoved() || breeze.level() != seat.level()) return null;
        return run;
    }

    @javax.annotation.Nullable
    private LivingEntity target(Unit unit) {
        LivingEntity target = seat.opponent(unit.mob());
        if (target == null || !target.isAlive() || target.isRemoved() || target.level() != seat.level()) return null;
        return seat.opposed(unit.mob(), target) ? target : null;
    }
}
