package com.tactical.neoforge.battle;

import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.ShulkerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole tactical adapter of the exact vanilla shulker type, as one dedicated controller.
 *
 * <p>Everything a shell decides lives here: its attachment node, its per-kind action quotas, its visible shell
 * window, the one frozen relocation proposal of an own turn, its native reaction scope and the hand-over of a
 * native-born child. The encounter supplies only the facts this controller is not allowed to know: identity,
 * AP, roster, the battlefield and the journal. That boundary is deliberate - a 11600-line encounter class with a
 * six-hundred-line algorithm block inside it is not reviewable, and the algorithms do not belong to it.</p>
 *
 * <p>Five native facts shape every decision below, and each of them is a whole design constraint on its own:</p>
 * <ol>
 *   <li><b>the shell is glued to a face</b>, not to a floor: legality is six-face, and the opening is measured at
 *       full open rather than at the peek the body happens to carry;</li>
 *   <li><b>the relocation fires an event every listener may re-point</b>, so the landing is judged at the exact
 *       post-event seam and the validated face is carried into vanilla's own {@code setAttachFace};</li>
 *   <li><b>the reaction lives inside {@code hurt}</b>, in one of two native branches, so its permission and its
 *       child window are scoped to that one synchronous call and settled on the way out;</li>
 *   <li><b>the replication happens after the relocation returns</b>, so it is observed at the world's own
 *       {@code addFreshEntity} receipt and never created here;</li>
 *   <li><b>vanilla's teleport writes the raw peek to zero directly</b>, so every shell write compares the
 *       body's own value instead of a cache.</li>
 * </ol>
 */
public final class ShulkerController {

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

        boolean isParticipant(Entity entity);

        boolean opposed(LivingEntity a, LivingEntity b);

        boolean allied(LivingEntity a, LivingEntity b);

        /** Whether that unit is the actor of the current turn. */
        boolean actorTurn(LivingEntity unit);

        /** Whether the encounter is carrying something that forbids a new action. */
        boolean encounterBusy();

        /** Whether the encounter's own motion driver owns that body right now. */
        boolean heldByMotion(UUID id);

        int bulletBase();

        int teleportBase();

        /** The shared successful-action surcharge, applied to a base price. */
        int surcharge(int base, int paidActions);

        @javax.annotation.Nullable
        LivingEntity opponent(Mob actor);

        @javax.annotation.Nullable
        GridPos cellOf(LivingEntity entity);

        void faceTowards(Mob actor, GridPos cell);

        /** The unit behind that exact mob, or {@code null} when it is not this encounter's. */
        @javax.annotation.Nullable
        Unit unit(Mob mob);

        List<Unit> units();

        void journal(String channel, Component text);

        void stateChanged();

        /** Take one native-born child through capacity, side, attachment, claim and queue. */
        boolean admitChild(Unit parent, Shulker child);

        /** Fire exactly one native bullet and report every projectile the world really registered. */
        int fireBullet(Mob actor, LivingEntity target, java.util.function.Consumer<Entity> joined);

        /**
         * Adopt one displacement vanilla already wrote, through the encounter's validated handoff.
         *
         * @param pusher the body whose own opening caused it; it is the only body exempt from the swept proof
         */
        boolean adoptPush(LivingEntity body, Vec3 displacement, UUID pusher);

        /** Leave the encounter by name, keeping the body where it really is. */
        void releaseUnit(Unit unit, String journalKey);

        /** Adopt a landed native position as this unit's authoritative cell. */
        void landed(Unit unit, ShulkerAttachment node);
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

    /** One frozen relocation proposal of one own turn. Never rewritten once it is made. */
    private record Proposal(ShulkerAttachment landing, int epoch, UUID target) {}

    /** The controlled state of one shell. Dropped in one place, so a freed shell is a plain shell again. */
    private static final class Run {
        ShulkerHandler handler;
        /** The face the body really holds, re-read at its own turn start and after every relocation. */
        ShulkerAttachment node;
        /** Per-kind quotas of this own turn and this own cycle. */
        int shotsUsed;
        int maneuversUsed;
        /**
         * This own turn already spent its one maneuver <em>attempt</em>, whether or not vanilla really moved.
         *
         * <p>A refused relocation is free, but it is still this turn's attempt: without this the planner would ask
         * again on the next snapshot and the same native search would run every tick of the same turn.</p>
         */
        boolean maneuverAttempted;
        int reactionsUsed;
        /** A native reaction permit is open right now, inside one {@code hurt}. */
        boolean reactionPending;
        int reactionCost;
        Vec3 reactionOrigin;
        /**
         * One frame per native {@code hurt} call really in progress on this body, innermost last.
         *
         * <p>A depth counter could not say which frame was returning, so a nested return used to zero the outer
         * scope's state as well. The stack answers that exactly: only the frame that empties the stack settles,
         * revokes and closes.</p>
         */
        final java.util.ArrayDeque<Vec3> hurtFrames = new java.util.ArrayDeque<>();
        /** The shell is visibly open for the opponent's opportunity. */
        boolean windowOpen;
        /** The one bounded proposal of this own turn, with the epoch and target it was made for. */
        Proposal proposal;
        boolean proposalScanned;
        int proposalEpoch = -1;
        UUID proposalTarget;
        /** The native pose and shell state this encounter must give back if the admission fails. */
        ShulkerPose original;
    }

    private final Seat seat;
    private final Map<UUID, Run> runs = new LinkedHashMap<>();

    public ShulkerController(Seat seat) {
        this.seat = seat;
    }

    /** Exact type only: no other golem, no mob that happens to share a superclass. */
    public static boolean isActor(Mob mob) {
        return mob != null && mob.getType() == EntityType.SHULKER;
    }

    /** Whether that exact body is currently controlled by this encounter. */
    public boolean controls(UUID id) {
        return id != null && runs.containsKey(id);
    }

    /**
     * Whether that body is really carried by a legal attachment of its own.
     *
     * <p>A shell is not a body standing on a floor: it is glued to one of the six faces of its own block, so a
     * ceiling shell with nothing underneath is perfectly supported and a wall shell has no floor at all. Asking the
     * generic "is there a block below" question about one of them would drop a body that vanilla itself considers
     * entirely legal - which is exactly what a hanging shell would then be: a permanent fall.</p>
     */
    public boolean heldByItsOwnFace(LivingEntity body) {
        if (body == null || !(body instanceof Shulker shulker)) return false;
        Run run = runs.get(shulker.getUUID());
        if (run == null || !seat.live()) return false;
        if (!shulker.isAlive() || shulker.isRemoved() || shulker.level() != seat.level()) return false;
        ShulkerAttachment node = ShulkerAttachment.of(shulker);
        return ShulkerAttachment.legal(seat.level(), shulker, node, null)
                && ShulkerAttachment.insideZone(shulker, node, seat::insideZone);
    }

    public String describe() {
        if (runs.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<UUID, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            if (builder.length() > 0) builder.append(',');
            builder.append(entry.getKey()).append('@')
                    .append(run.node == null ? "?" : run.node.cell().toString())
                    .append(run.windowOpen ? "/open" : "/closed")
                    .append("/shots=").append(run.shotsUsed)
                    .append("/man=").append(run.maneuversUsed)
                    .append("/reactions=").append(run.reactionsUsed);
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Take one shell into this encounter and return whether it was taken.
     *
     * <p>The original native pose is snapshotted here, before anything is written, so a failed admission can give
     * the creature back exactly as it was. The shell is then closed through vanilla's own setter, which applies
     * vanilla's covered armour and plays vanilla's close sound once.</p>
     */
    public boolean install(Unit unit) {
        if (unit == null || !isActor(unit.mob()) || !(unit.mob() instanceof Shulker shulker)) return false;
        if (runs.containsKey(shulker.getUUID())) release(unit);
        Run run = new Run();
        run.original = ShulkerPose.of(shulker);
        run.handler = new Handler(unit);
        run.node = ShulkerAttachment.of(shulker);
        runs.put(shulker.getUUID(), run);
        ShulkerNative.access(shulker).tactical$setHandler(run.handler);
        closeShell(unit, run);
        return true;
    }

    /** Give one shell back to its own vanilla tick, keeping its real final position and its real effects. */
    public void release(Unit unit) {
        if (unit == null || unit.mob() == null) return;
        Run run = runs.remove(unit.mob().getUUID());
        if (run != null) {
            run.handler = null;
            run.node = null;
            run.reactionPending = false;
            run.hurtFrames.clear();
            run.windowOpen = false;
            run.proposal = null;
            run.proposalScanned = false;
            run.proposalTarget = null;
        }
        if (unit.mob() instanceof Shulker shulker) {
            ShulkerNative.revoke(shulker);
            ShulkerLandingGate.disarm(shulker);
            ShulkerChildren.close(shulker);
            if (shulker instanceof TacticalShulkerAccess access) access.tactical$setHandler(null);
        }
    }

    /**
     * Give one shell back <em>and</em> undo what the admission wrote: the exact attach face, the exact raw peek
     * with its covered-armour state, and the real position the creature had before this encounter touched it.
     *
     * <p>Rollback only. A normal exit keeps the body where it really is, because it really went there.</p>
     */
    public void restore(Unit unit) {
        if (unit == null || unit.mob() == null) return;
        Run run = runs.get(unit.mob().getUUID());
        ShulkerPose original = run == null ? null : run.original;
        release(unit);
        if (original != null && unit.mob() instanceof Shulker shulker) {
            try {
                original.restore(shulker);
            } catch (RuntimeException failure) {
                Tactical.LOGGER.error("[Tactical] shulker rollback failed for {}", shulker.getUUID(), failure);
            }
        }
    }

    /** Drop every controlled shell: encounter teardown. */
    public void clear() {
        for (Unit unit : List.copyOf(seat.units())) {
            if (controls(unit.mob().getUUID())) release(unit);
        }
        runs.clear();
    }

    /** A forced fall owns a body; a shell in mid-air is not a shell without support. */
    public void beginTurn(Unit unit) {
        Run run = live(unit);
        if (run == null) return;
        Shulker shulker = (Shulker) unit.mob();
        run.node = ShulkerAttachment.of(shulker);
        run.shotsUsed = 0;
        run.maneuversUsed = 0;
        run.maneuverAttempted = false;
        run.reactionsUsed = 0;
        run.reactionPending = false;
        run.reactionOrigin = null;
        run.proposal = null;
        run.proposalScanned = false;
        run.proposalEpoch = -1;
        run.proposalTarget = null;
        // The visible window lasts through the opponent's opportunity and ends at this unit's own next turn.
        if (run.windowOpen) closeShell(unit, run);
    }

    /**
     * A unit's own turn is ending.
     *
     * <p>The open shell is a window for the opponent, and it belongs to the shot that opened it: a turn that really
     * fired hands the still-open shell to the opponent's opportunity, and a turn that ends without firing - nothing
     * to shoot, no target, or a deliberate wait - closes it, because nothing was opened for. That distinction is
     * what keeps the window from being a wall-clock accident in either direction.</p>
     */
    public void onTurnEnding(Mob mob) {
        Unit unit = unitOf(mob);
        Run run = unit == null ? null : runs.get(mob.getUUID());
        if (run == null || !run.windowOpen) return;
        if (run.shotsUsed > 0) return;
        closeShell(unit, run);
    }

    // ------------------------------------------------------------------ per-tick maintenance

    /**
     * Keep every controlled shell honest, once per encounter tick.
     *
     * <p>Two facts can quietly stop being true between two own turns: the block a body is glued to can be
     * removed, and the raw peek the encounter believes in can be overwritten by vanilla's own teleport (which
     * writes the data value directly, without the armour edit or the sound). The first is re-proved against the
     * live world - a legal face at the same cell is re-glued through vanilla's own setter, a legal pose at the
     * same point is adopted as it is, and a body with no legal pose at all leaves the encounter by name instead of
     * hanging as a permanent busy unit. The second is repaired by comparing the body's own raw value, never a
     * cache, so a reopen is never skipped.</p>
     */
    public void maintain() {
        if (!seat.live()) return;
        for (Unit unit : List.copyOf(seat.units())) {
            Run run = runs.get(unit.mob().getUUID());
            if (run == null || !(unit.mob() instanceof Shulker shulker)) continue;
            if (!shulker.isAlive() || shulker.isRemoved() || shulker.level() != seat.level()) continue;
            // A floating body is not glued to anything: the native effect is what keeps it up, and asking it for a
            // face would release a unit the encounter is legitimately holding.
            if (seat.heldByMotion(shulker.getUUID())) continue;
            syncShell(unit, run, shulker);
            ShulkerAttachment node = ShulkerAttachment.of(shulker);
            if (ShulkerAttachment.legal(seat.level(), shulker, node, null)
                    && ShulkerAttachment.insideZone(shulker, node, seat::insideZone)) {
                run.node = node;
                continue;
            }
            Direction face = ShulkerNative.findAttachableSurface(shulker, shulker.blockPosition());
            ShulkerAttachment repaired = face == null ? null : ShulkerAttachment.at(node.cell(), face);
            if (repaired != null && ShulkerAttachment.legal(seat.level(), shulker, repaired, null)
                    && ShulkerAttachment.insideZone(shulker, repaired, seat::insideZone)) {
                ShulkerNative.setAttachFace(shulker, face);
                run.node = repaired;
                seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_SHULKER_REATTACH,
                        BattleText.name(shulker), BattleText.literal(repaired.cell().toString())));
                seat.stateChanged();
                continue;
            }
            ShulkerAttachment resolved = ShulkerAttachment.resolve(seat.level(), shulker, node.feet(),
                    cell -> !seat.cellFree(cell, shulker.getUUID()), seat::insideZone);
            if (resolved != null) {
                ShulkerNative.setAttachFace(shulker, resolved.face());
                shulker.setPos(resolved.feet().x, resolved.feet().y, resolved.feet().z);
                shulker.setDeltaMovement(Vec3.ZERO);
                shulker.fallDistance = 0.0F;
                run.node = resolved;
                seat.landed(unit, resolved);
                seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_SHULKER_REATTACH,
                        BattleText.name(shulker), BattleText.literal(resolved.cell().toString())));
                seat.stateChanged();
                continue;
            }
            seat.releaseUnit(unit, BattleText.KEY_ENEMY_SHULKER_RELEASED);
        }
    }

    // ------------------------------------------------------------------ planner answers

    /** What the planner is allowed to know, read from the same state that prices and commits. */
    public ShulkerState state(Unit unit) {
        Run run = live(unit);
        if (run == null) return ShulkerState.NONE;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy()) {
            // A body that cannot act now answers "nothing is available", so a planner never proposes into a busy
            // encounter. The flag is the same one every other adapter reads.
            return new ShulkerState(true, false, 0, false, 0, true, true);
        }
        boolean shotUsed = !MotionRules.withinQuota(run.shotsUsed, MotionRules.SHULKER_SHOT_QUOTA);
        boolean relocated = !MotionRules.withinQuota(run.maneuversUsed, MotionRules.SHULKER_MANEUVER_QUOTA);
        return new ShulkerState(true, canShoot(unit, run), shotCost(unit),
                canManeuver(unit, run), maneuverCost(unit), shotUsed, relocated);
    }

    private boolean canShoot(Unit unit, Run run) {
        if (!MotionRules.withinQuota(run.shotsUsed, MotionRules.SHULKER_SHOT_QUOTA)) return false;
        LivingEntity target = target(unit);
        if (target == null) return false;
        if (unit.mob().distanceToSqr(target) >= MotionRules.SHULKER_SHOT_RANGE_SQ) return false;
        int cost = shotCost(unit);
        if (cost <= 0 || unit.ap() < cost) return false;
        return holdsLegalFace(unit);
    }

    private boolean canManeuver(Unit unit, Run run) {
        if (run.maneuverAttempted) return false;
        if (!MotionRules.withinQuota(run.maneuversUsed, MotionRules.SHULKER_MANEUVER_QUOTA)) return false;
        int cost = maneuverCost(unit);
        if (cost <= 0 || unit.ap() < cost) return false;
        LivingEntity target = target(unit);
        if (target == null) return false;
        // A relocation exists to buy a shot: one made while a shot is already legal trades a shot for a move.
        if (canShoot(unit, run)) return false;
        return proposal(unit, run, target) != null;
    }

    private boolean holdsLegalFace(Unit unit) {
        Shulker shulker = (Shulker) unit.mob();
        ShulkerAttachment node = ShulkerAttachment.of(shulker);
        return ShulkerAttachment.legal(seat.level(), shulker, node, null)
                && ShulkerAttachment.insideZone(shulker, node, seat::insideZone);
    }

    private int shotCost(Unit unit) {
        return seat.surcharge(seat.bulletBase(), unit.paidActions());
    }

    private int maneuverCost(Unit unit) {
        return seat.surcharge(seat.teleportBase(), unit.paidActions());
    }

    /** A reaction is never a main action: it neither reads nor advances the paid-action surcharge. */
    private int reactionCost() {
        return Math.max(0, seat.teleportBase());
    }

    // ------------------------------------------------------------------ the one native bullet

    /**
     * One native shulker bullet, and its opening window.
     *
     * <p>The shell opens first - that is the visible window this unit's attack lives in - and the bullet is a real
     * {@code ShulkerBullet} whose own tick curves it, whose own hit deals the native damage and the native
     * levitation, and which can be destroyed in flight by anyone. The price is committed once, and only when the
     * world really took the projectile. Each shot is marked with its own immutable ownership, which is what lets
     * a later impact question be answered without guessing whose shot it was.</p>
     */
    public boolean shoot(Unit unit) {
        Run run = live(unit);
        if (run == null) return false;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy()) return false;
        if (!canShoot(unit, run)) return false;
        LivingEntity target = target(unit);
        if (target == null) return false;
        int cost = shotCost(unit);
        Shulker shulker = (Shulker) unit.mob();
        Direction.Axis axis = ShulkerNative.attachFace(shulker).getAxis();
        openShell(unit, run, shulker);
        GridPos targetCell = seat.cellOf(target);
        if (targetCell != null) seat.faceTowards(shulker, targetCell);
        ShulkerBullets.Owner ownership = new ShulkerBullets.Owner(seat.sessionId(), shulker.getUUID(),
                target.getUUID(), unit.ownTurns());
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int fired = seat.fireBullet(shulker, target, joined -> {
            try {
                if (joined instanceof ShulkerBullet bullet) ShulkerBullets.mark(bullet, ownership);
            } catch (RuntimeException failure) {
                nativeFailure[0] = failure;
            }
        });
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native shulker bullet marking failed for {} ({} joined)",
                    shulker.getUUID(), Math.max(0, fired), nativeFailure[0]);
        }
        if (fired <= 0) {
            seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_SHULKER_NO_SHOT,
                    BattleText.name(shulker)));
            seat.stateChanged();
            return false;
        }
        unit.ap(Math.max(0, unit.ap() - cost));
        unit.paidActionsIncremented();
        run.shotsUsed++;
        seat.journal("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_SHULKER_BULLET,
                BattleText.name(shulker), BattleText.name(target)));
        seat.stateChanged();
        return true;
    }

    // ------------------------------------------------------------------ the one native relocation

    /**
     * One native relocation, driven through vanilla's own entry.
     *
     * <p>Valid only for a real need this turn: no legal shot from where the body holds, and a landing exists that
     * passes the attachment, body, occupancy, chunk and battlefield gates. The landing is the one frozen proposal
     * of this own turn, re-proved here against the live world rather than searched again. Vanilla keeps its five
     * attempts, its {@code ±8} search and the cancelable {@code EnderEntity} event; the final point is judged at
     * the post-event seam, before anything is charged and before anything moves.</p>
     */
    public boolean relocate(Unit unit) {
        Run run = live(unit);
        if (run == null) return false;
        if (!seat.actorTurn(unit.mob()) || seat.encounterBusy()) return false;
        if (!canManeuver(unit, run)) {
            if (DeveloperTrace.enabled()) {
                LivingEntity target = target(unit);
                Tactical.LOGGER.info("[Tactical] dev-trace shulker maneuver refused session={} tick={} uuid={} "
                                + "maneuversUsed={}/{} cost={} ap={} target={} canShoot={} proposal={} exhausted={}",
                        seat.sessionId(), seat.tick(), unit.mob().getUUID(),
                        run.maneuversUsed, MotionRules.SHULKER_MANEUVER_QUOTA, maneuverCost(unit), unit.ap(),
                        target == null ? "none" : target.getName().getString(), canShoot(unit, run),
                        run.proposal == null ? "none" : run.proposal.landing().cell().toString(),
                        run.proposalScanned);
            }
            seat.journal("enemy", BattleText.translatable(BattleText.KEY_ENEMY_SHULKER_NO_RELOCATION,
                    BattleText.name(unit.mob())));
            seat.stateChanged();
            return false;
        }
        int cost = maneuverCost(unit);
        // This own turn's one attempt is spent by the attempt itself; the price is only committed when the native
        // entry really moved the body, so a refused/vetoed/no-op try stays free.
        run.maneuverAttempted = true;
        if (!commitRelocation(unit, run)) return false;
        unit.ap(Math.max(0, unit.ap() - cost));
        unit.paidActionsIncremented();
        run.maneuversUsed++;
        return true;
    }

    /**
     * The one native relocation driver, shared by the own-turn maneuver and the bounded reaction.
     *
     * <p>Three things happen around the native call and nothing else: the landing gate is armed so the point the
     * {@code EnderEntity} event finally names is judged after every listener ran, the child window is opened so a
     * replication this call causes is noticed, and the result is committed from where the body really is. There is
     * deliberately no repair afterwards: a landing this encounter would have to fix up is a landing it should
     * have refused.</p>
     */
    private boolean commitRelocation(Unit unit, Run run) {
        if (!(unit.mob() instanceof Shulker shulker)) return false;
        Vec3 origin = shulker.position();
        ShulkerLandingGate.arm(shulker, this::mayLand);
        ShulkerChildren.open(shulker, run.handler);
        boolean moved;
        try {
            moved = ShulkerNative.teleportSomewhere(shulker);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] native shulker relocation failed for {}; nothing was charged",
                    shulker.getUUID(), failure);
            moved = false;
        } finally {
            ShulkerLandingGate.disarm(shulker);
            ShulkerChildren.close(shulker);
        }
        Vec3 after = shulker.position();
        if (!moved || after.distanceToSqr(origin) < 0.25D) {
            // A relocation onto its own spot is a paid no-op; vanilla answered true but nothing really changed.
            run.node = ShulkerAttachment.of(shulker);
            return false;
        }
        commitLanded(unit, run, shulker, false);
        return true;
    }

    /** Adopt a landed native position: the node, the authoritative cell and the real fractional feet. */
    private void commitLanded(Unit unit, Run run, Shulker shulker, boolean reaction) {
        ShulkerAttachment node = ShulkerAttachment.of(shulker);
        shulker.setDeltaMovement(Vec3.ZERO);
        shulker.fallDistance = 0.0F;
        run.node = node;
        seat.landed(unit, node);
        seat.journal("enemy", BattleText.translatable(
                reaction ? BattleText.KEY_ENEMY_SHULKER_REACTION : BattleText.KEY_ENEMY_SHULKER_TELEPORT,
                BattleText.name(shulker), BattleText.literal(node.cell().toString())));
        seat.stateChanged();
    }

    /**
     * Judge the cell the native event finally named.
     *
     * <p>Asked with every listener already done and nothing moved yet, so the answer is about the point that will
     * really be used. Refused with {@code null} when the cell is outside the battlefield, unloaded, occupied, or
     * holds no legal full-open attachment - and also when it is the cell the body already holds, which would be a
     * landed no-op charged at full price. The returned face is the validated one, carried into vanilla's own
     * {@code setAttachFace}: the face vanilla computed came from the pre-event candidate and may name a wall that
     * is not there.</p>
     */
    @javax.annotation.Nullable
    private Direction mayLand(Shulker shulker, GridPos finalCell) {
        Unit unit = unitOf(shulker);
        Run run = unit == null ? null : runs.get(shulker.getUUID());
        if (run == null || finalCell == null) return null;
        if (!seat.live() || !seat.isParticipant(shulker)) return null;
        if (!seat.insideZone(finalCell)) return null;
        ShulkerAttachment node = ShulkerAttachment.resolveCell(seat.level(), shulker, finalCell,
                cell -> !seat.cellFree(cell, shulker.getUUID()), seat::insideZone);
        if (node == null) return null;
        if (!ShulkerAttachment.insideZone(shulker, node, seat::insideZone)) return null;
        ShulkerAttachment current = run.node != null ? run.node : ShulkerAttachment.of(shulker);
        if (current.cell().equals(node.cell())) return null;
        return node.face();
    }

    // ------------------------------------------------------------------ the native reaction

    /**
     * Native {@code hurt} entry: open this body's own scope.
     *
     * <p>The child window is opened here rather than around a relocation, because vanilla's replication happens
     * after {@code teleportSomewhere} returned, inside {@code hitByShulkerBullet} - a scope only the hurt call
     * encloses.</p>
     */
    public void beginHurt(Shulker shulker) {
        Unit unit = unitOf(shulker);
        Run run = unit == null ? null : runs.get(shulker.getUUID());
        if (run == null) return;
        Vec3 origin = shulker.position();
        run.hurtFrames.push(origin);
        if (run.hurtFrames.size() > 1) return;
        run.reactionOrigin = origin;
        ShulkerChildren.open(shulker, run.handler);
    }

    /**
     * Native {@code hurt} return: settle this body's own result and drop everything the scope opened.
     *
     * <p>The real receipt is the body's own movement inside this one call. A reaction that vanilla refused, an
     * event vetoed or a random branch that never ran moved nothing and stays free; a permission that was never
     * used is revoked here and cannot be spent by anything later.</p>
     */
    public void endHurt(Shulker shulker) {
        Unit unit = unitOf(shulker);
        Run run = unit == null ? null : runs.get(shulker.getUUID());
        if (run == null) return;
        if (!run.hurtFrames.isEmpty()) run.hurtFrames.pop();
        // A nested frame returning leaves the outer scope exactly as it was: no settlement, no revocation and no
        // window close, because that scope is still running.
        if (!run.hurtFrames.isEmpty()) return;
        try {
            settleReaction(unit, run, shulker);
        } finally {
            run.reactionPending = false;
            run.reactionOrigin = null;
            ShulkerNative.revoke(shulker);
            ShulkerLandingGate.disarm(shulker);
            ShulkerChildren.close(shulker);
        }
    }

    private void settleReaction(Unit unit, Run run, Shulker shulker) {
        if (!run.reactionPending) return;
        Vec3 origin = run.reactionOrigin;
        int cost = run.reactionCost;
        run.reactionPending = false;
        if (origin == null) return;
        if (!shulker.isAlive() || shulker.isRemoved()) return;
        Vec3 after = shulker.position();
        if (after.distanceToSqr(origin) < 0.25D) return;
        run.reactionsUsed++;
        unit.ap(Math.max(0, unit.ap() - cost));
        commitLanded(unit, run, shulker, true);
    }

    /**
     * Decide one bounded reaction to real damage from this encounter's other side.
     *
     * <p>Called from the incoming-damage hook, inside the native hurt scope and before vanilla's damage path runs,
     * because the one reaction a shell may take lives inside it. Authorizing it here - only when the cycle quota
     * is free, the source is an accepted opposing participant of this encounter, and the unit can really pay the
     * base price - keeps the native order while never letting a bystander's or another encounter's hit buy a free
     * relocation.</p>
     */
    public void incomingDamage(Unit unit, DamageSource source) {
        Run run = live(unit);
        if (run == null) return;
        if (!MotionRules.withinQuota(run.reactionsUsed, MotionRules.SHULKER_REACTION_QUOTA)) return;
        if (run.reactionPending) return;
        if (!(unit.mob() instanceof Shulker shulker)) return;
        if (!sourceOpposed(shulker, source)) return;
        int cost = reactionCost();
        if (cost <= 0 || unit.ap() < cost) return;
        run.reactionPending = true;
        run.reactionCost = cost;
        if (run.reactionOrigin == null) run.reactionOrigin = shulker.position();
        ShulkerNative.authorizeOnce(shulker);
        ShulkerLandingGate.arm(shulker, this::mayLand);
    }

    /** A hit is only this unit's business when its source really belongs to the other side of this encounter. */
    private boolean sourceOpposed(Shulker shulker, DamageSource source) {
        if (source == null) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living && living != shulker && seat.isParticipant(living)
                && seat.opposed(shulker, living)) return true;
        Entity direct = source.getDirectEntity();
        return direct instanceof LivingEntity living && living != shulker && seat.isParticipant(living)
                && seat.opposed(shulker, living);
    }

    // ------------------------------------------------------------------ the native routing seam

    /** The routing seam a controlled shell's native code answers to. */
    private final class Handler implements ShulkerHandler {
        private final Unit unit;

        private Handler(Unit unit) {
            this.unit = unit;
        }

        @Override
        public boolean tactical$push(Entity pushed, MoverType type, Vec3 motion) {
            return handlePush(unit, pushed, motion);
        }

        @Override
        public boolean tactical$mayChangeAttachment(Shulker shulker, BlockPos cell, Direction face) {
            if (!shulkerIs(shulker, unit)) return true;
            ShulkerAttachment candidate = ShulkerAttachment.at(
                    new GridPos(cell.getX(), cell.getY(), cell.getZ()), face);
            return ShulkerAttachment.insideZone(shulker, candidate, seat::insideZone);
        }

        @Override
        public void tactical$onNativeChild(Shulker parent, Shulker child) {
            if (!shulkerIs(parent, unit)) return;
            seat.admitChild(unit, child);
        }
    }

    /**
     * The shell's own opening pushes everything nearby. What it may do is this encounter's decision.
     *
     * @return true when this call handled the displacement, so vanilla's own {@code move} must not run
     */
    private boolean handlePush(Unit unit, Entity pushed, Vec3 motion) {
        if (pushed == null || motion == null) return false;
        if (!(unit.mob() instanceof Shulker shulker)) return false;
        if (!shulkerIs(shulker, unit) && !seat.isParticipant(shulker)) return false;
        // Vanilla never pushes another shulker, and never pushes a body without physics; leave those alone.
        if (pushed instanceof Shulker || pushed.noPhysics) return false;
        if (!(pushed instanceof LivingEntity body)) return false;
        Shulker pusher = (Shulker) unit.mob();
        // A protected body - this encounter's own side, a bystander, another encounter's member - is never shoved.
        if (!seat.isParticipant(body)) return true;
        if (!seat.opposed(pusher, body) || seat.allied(pusher, body)) return true;
        // An allowed body keeps vanilla's accepted vector, carried through the encounter's validated handoff so
        // the grid pin and the shot reconciliation cannot undo it afterwards. Only the pushing shell is exempt
        // from the swept proof - it is legitimately inside the box its own opening measured.
        seat.adoptPush(body, motion, pusher.getUUID());
        return true;
    }

    private boolean shulkerIs(Shulker shulker, Unit unit) {
        return shulker != null && unit != null && unit.mob() == shulker;
    }

    // ------------------------------------------------------------------ helpers

    private Unit unitOf(Mob mob) {
        return mob == null ? null : seat.unit(mob);
    }

    private Unit unitOf(Shulker shulker) {
        return shulker == null ? null : seat.unit(shulker);
    }

    @javax.annotation.Nullable
    private Run live(Unit unit) {
        if (unit == null || unit.mob() == null) return null;
        Run run = runs.get(unit.mob().getUUID());
        if (run == null) return null;
        if (!seat.live()) return null;
        if (!(unit.mob() instanceof Shulker shulker)) return null;
        if (!shulker.isAlive() || shulker.isRemoved() || shulker.level() != seat.level()) return null;
        return run;
    }

    @javax.annotation.Nullable
    private LivingEntity target(Unit unit) {
        LivingEntity target = seat.opponent(unit.mob());
        if (target == null || !target.isAlive() || target.isRemoved() || target.level() != seat.level()) return null;
        return seat.opposed(unit.mob(), target) ? target : null;
    }

    @javax.annotation.Nullable
    private Proposal proposal(Unit unit, Run run, LivingEntity target) {
        int epoch = unit.ownTurns();
        if (run.proposalScanned && run.proposalEpoch == epoch && target.getUUID().equals(run.proposalTarget)) {
            return run.proposal;
        }
        Shulker shulker = (Shulker) unit.mob();
        GridPos origin = ShulkerAttachment.of(shulker).cell();
        ShulkerAttachment landing = ShulkerAttachment.resolveNeighbourhood(seat.level(), shulker, origin,
                seat::insideZone, cell -> !seat.cellFree(cell, unit.mob().getUUID()), target.position());
        run.proposal = landing == null ? null : new Proposal(landing, epoch, target.getUUID());
        run.proposalScanned = true;
        run.proposalEpoch = epoch;
        run.proposalTarget = target.getUUID();
        return run.proposal;
    }

    private void openShell(Unit unit, Run run, Shulker shulker) {
        run.windowOpen = true;
        writeShell(shulker, MotionRules.SHULKER_PEEK_OPEN);
    }

    private void closeShell(Unit unit, Run run) {
        run.windowOpen = false;
        if (unit.mob() instanceof Shulker shulker) writeShell(shulker, MotionRules.SHULKER_PEEK_CLOSED);
    }

    /**
     * Write one raw peek through vanilla's own setter, only when the body's own value really differs.
     *
     * <p>The comparison is deliberately against the entity, not against a cached number: vanilla's teleport
     * writes the peek data to zero directly, so a cache would believe the shell is still open and skip the
     * reopen the player can plainly see is missing.</p>
     */
    private void writeShell(Shulker shulker, int peek) {
        if (ShulkerNative.rawPeek(shulker) == peek) return;
        ShulkerNative.setRawPeek(shulker, peek);
    }

    /** Once per tick, keep the visible shell state and the native peek in agreement. */
    private void syncShell(Unit unit, Run run, Shulker shulker) {
        int wanted = run.windowOpen ? MotionRules.SHULKER_PEEK_OPEN : MotionRules.SHULKER_PEEK_CLOSED;
        writeShell(shulker, wanted);
    }

    /** Diagnostic list for the fixture and the trace. */
    public List<String> describeAll() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, Run> entry : runs.entrySet()) {
            Run run = entry.getValue();
            lines.add(entry.getKey() + "@" + (run.node == null ? "?" : run.node.cell().toString())
                    + (run.windowOpen ? "/open" : "/closed")
                    + "/shots=" + run.shotsUsed + "/man=" + run.maneuversUsed
                    + "/reactions=" + run.reactionsUsed);
        }
        return lines;
    }
}
