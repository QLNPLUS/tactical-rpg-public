package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-encounter exclusion of the mobs that the roster cap refuses.
 *
 * <p>The cap has always been a pure refusal: a rejected mob kept its AI and its aggro, which let it
 * keep crowding the participants even though its damage never landed. This class turns the refusal
 * into an exit: the mob loses its relationship with this encounter's members, is placed outside the
 * battlefield, and stays unable to lock back on or touch a member while the encounter is full.</p>
 *
 * <p>Scope is deliberately narrow, because several encounters can run at once:</p>
 * <ul>
 *   <li>only mobs that no encounter owns ({@link BattleManager#ownerOf(UUID)} is null) are touched -
 *       participants, players and another encounter's units are never moved. Someone's pet or a
 *       leashed animal is not relocated either;</li>
 *   <li>hostility is not the criterion. A refused attacker is usually a monster, but a neutral or
 *       plain mob displaces a participant by collision just as well, so any unowned mob that is
 *       inside this battlefield, hunts a member of this encounter, or is in physical contact with
 *       one is an intruder;</li>
 *   <li>aggro is cleared only for this encounter's member UUIDs, so an unrelated player's fight is
 *       left exactly as it was;</li>
 *   <li>the suppression lives only while {@code hostileCount() >= participantCap}. The moment a
 *       hostile slot frees, everything here is released and ordinary re-acquisition, refill and
 *       attack-absorption take over again. There is no timer and no permanent block.</li>
 * </ul>
 *
 * <p>The mob's own flags are never rewritten: no {@code setNoAi}, no gravity or persistence change.
 * Only target/anger/Brain memories and (when a safe place exists) its position change.</p>
 */
final class MobIsolation {

    /** How far beyond the zone bounds an intruder is still looked for. */
    private static final int SCAN_INFLATE = 8;
    /** Physical contact range squared: a mob this close displaces participants without targeting them. */
    private static final double CONTACT_RADIUS_SQR = 4.0D;
    /** Re-scan cadence while the encounter stays full. */
    private static final int SCAN_TICKS = 10;
    /** Retry cadence after a failed placement attempt. */
    private static final int RETRY_TICKS = 20;
    /** Bounded retries; after that the mob stays suppressed but is not moved again. */
    private static final int MAX_ATTEMPTS = 5;

    private static final class Entry {
        final Mob mob;
        /** Why this unit is being held out; the two reasons are released by different questions. */
        final Hold hold;
        /** The faction a split child would join, so a retry never turns a friendly child into an enemy. */
        final BattleSide side;
        /** Placement retries: the existing bounded outside-placement policy, shared with cap refusals. */
        int retryAt;
        int attempts;
        boolean placed;
        /** Taking-in retries for a split child, kept separate so neither budget can exhaust the other. */
        int takeRetryAt;
        int takeAttempts;

        Entry(Mob mob, int retryAt, Hold hold, BattleSide side) {
            this.mob = mob;
            this.retryAt = retryAt;
            this.hold = hold;
            this.side = side;
        }
    }

    /**
     * The two reasons a unit can be held out.
     *
     * <p>{@link #CAP_REFUSED} is the original rule and is released the instant the roster has room again.
     * {@link #SPLIT_BORN} is the one this family needed: the roster may well have a free slot while the
     * terrain has no legal place for that particular body, and the child must stay off the members until a
     * legal admission really succeeds - releasing it on a free slot alone would let it walk straight back
     * in and hit somebody this encounter had just refused to take.</p>
     */
    enum Hold {
        CAP_REFUSED,
        SPLIT_BORN
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private int scanCooldown;

    /** True while this mob is held out of this encounter. Identity is checked, not only the UUID. */
    boolean suppresses(Mob mob) {
        if (mob == null || mob.isRemoved()) return false;
        Entry entry = entries.get(mob.getUUID());
        return entry != null && entry.mob == mob && entry.mob.isAlive();
    }

    /** True only for a unit held out because a native split could not be taken; used by the contact rule. */
    boolean holdsSplitChild(Mob mob) {
        if (mob == null || mob.isRemoved()) return false;
        Entry entry = entries.get(mob.getUUID());
        return entry != null && entry.mob == mob && entry.mob.isAlive() && entry.hold == Hold.SPLIT_BORN;
    }

    /** Live suppressed count; used only for diagnostics and tests. */
    int size() {
        return entries.size();
    }

    /**
     * Called once per server tick from {@link ServerBattle#tick()}.
     *
     * <p>Release is checked every tick and costs nothing; the level scan runs on
     * {@link #SCAN_TICKS} because it is a query. Every <em>registered</em> unit is re-cleaned every
     * tick: a Brain mob can write {@code ATTACK_TARGET}/{@code ANGRY_AT} again without ever touching
     * {@code Mob.setTarget}, so a one-shot cleanup after the teleport would not hold. The cost is
     * bounded by how many units this encounter actually holds out, never by the world.</p>
     */
    void maintain(ServerBattle battle) {
        if (battle == null || battle.finished()) {
            entries.clear();
            return;
        }
        // Release has to be immediate: a freed slot must restore ordinary recruitment, not wait
        // for a timer. Also release when there is nobody left to protect.
        if (battle.memberPlayers().isEmpty()) {
            entries.clear();
            return;
        }
        // A cap refusal lasts exactly as long as the fullness that created it. A split-born hold is not
        // about fullness at all: it lasts until the encounter can legally take that body, and it is released
        // by the retry below or by the end of the encounter - never by a free slot alone.
        boolean full = IsolationPolicy.holdsWhileFull(battle.hostileCount(), battle.hostileCapacity());
        entries.values().removeIf(entry -> entry.hold == Hold.CAP_REFUSED && !full);
        prune(battle);
        maintainRegistered(battle);
        retrySplitChildren(battle);
        if (!full) return;
        if (--scanCooldown > 0) return;
        scanCooldown = SCAN_TICKS;
        scan(battle);
    }

    /**
     * Hand one split child to this encounter's exclusion.
     *
     * <p>Same rules as any other exclusion - identity-checked, unowned only, relationship cleared, placed by
     * the existing legal-outside search with the same bounded retries - but it does not require the roster to
     * be full, because a slot with no legal foot surface is exactly what a newborn wide body can hit. The
     * child's own flags are never touched here.</p>
     */
    void holdSplitChild(ServerBattle battle, Mob mob, BattleSide side) {
        if (battle == null || mob == null || battle.finished()) return;
        if (!eligible(battle, mob)) return;
        Entry existing = entries.get(mob.getUUID());
        if (existing != null && existing.mob == mob) return;
        Entry entry = new Entry(mob, battle.tickCount(), Hold.SPLIT_BORN, side);
        entry.takeRetryAt = battle.tickCount();
        entries.put(mob.getUUID(), entry);
        int room = Math.max(0, battle.hostileCapacity() - battle.hostileCount());
        isolate(battle, mob, battle.memberPlayers(), battle.tickCount());
        // The two reasons are different problems and are named as such: a full roster, or room with no legal
        // foot surface for that body. Neither is a deletion and neither is a silent pass.
        Tactical.LOGGER.info("[Tactical] split child {} ({}) kept out of encounter {}: {} (room left={})",
                mob.getUUID(), BattleDeployment.stanceOf(mob), battle.sessionId(),
                room <= 0 ? "the roster is full" : "no legal foot surface for its body", room);
    }

    /**
     * Retry one held split child: place it if that failed, then try to take it back in.
     *
     * <p>The two budgets are separate. A body that could not be placed outside still gets its five bounded
     * placement attempts, and a body that was placed still gets five bounded attempts to be taken back in through
     * the ordinary admission - one budget can never exhaust the other, and neither becomes an unbounded scan. When
     * both are spent the child simply stays suppressed until the encounter ends.</p>
     */
    private void retrySplitChildren(ServerBattle battle) {
        for (Entry entry : List.copyOf(entries.values())) {
            if (entry.hold != Hold.SPLIT_BORN) continue;
            Mob mob = entry.mob;
            if (mob == null || mob.isRemoved() || !mob.isAlive() || mob.level() != battle.level()) continue;
            int now = battle.tickCount();
            if (!entry.placed && IsolationPolicy.retryDue(entry.attempts, entry.retryAt, now, MAX_ATTEMPTS)) {
                // Placement is best effort: a body with no exit anywhere stays suppressed where it is, and that
                // must not stop the encounter from trying to take it back in later.
                isolate(battle, mob, battle.memberPlayers(), now);
            }
            if (!battle.splits().takeDue(entry.takeAttempts, entry.takeRetryAt, now)) continue;
            if (battle.retrySplitChild(mob, entry.side)) {
                // Admitted: the roster owns it now, so the exclusion has nothing left to do.
                entries.remove(mob.getUUID());
                continue;
            }
            entry.takeAttempts++;
            entry.takeRetryAt = now + battle.splits().takeRetryDelay();
        }
    }

    /**
     * Immediate hand-off from an attack that {@code tryAbsorb} had to refuse.
     *
     * <p>Called from the damage gate, so the first contact is already an exit even if the mob is
     * outside the periodic scan's reach.</p>
     */
    void offer(ServerBattle battle, Mob mob) {
        if (battle == null || mob == null) return;
        if (!IsolationPolicy.holdsWhileFull(battle.hostileCount(), battle.hostileCapacity())) return;
        if (!eligible(battle, mob)) return;
        if (suppresses(mob)) return;
        isolate(battle, mob, battle.memberPlayers(), battle.tickCount());
    }

    /** Forget everything: the encounter ended, emptied, or changed dimension. */
    void clear() {
        entries.clear();
        scanCooldown = 0;
    }

    // ------------------------------------------------------------------ scan

    private void scan(ServerBattle battle) {
        ServerLevel level = battle.level();
        List<ServerPlayer> members = battle.memberPlayers();
        if (members.isEmpty()) return;
        AABB area = battle.zoneBounds().inflate(SCAN_INFLATE);
        List<Mob> candidates = new ArrayList<>(level.getEntitiesOfClass(Mob.class, area,
                mob -> eligible(battle, mob) && intrudes(battle, mob, members)));
        if (candidates.isEmpty()) return;
        candidates.sort(Comparator
                .comparingDouble((Mob mob) -> nearestMemberSqr(mob, members))
                .thenComparing(mob -> mob.getUUID().toString()));
        int now = battle.tickCount();
        for (Mob mob : candidates) {
            if (!IsolationPolicy.holdsWhileFull(battle.hostileCount(), battle.hostileCapacity())) return;
            Entry existing = entries.get(mob.getUUID());
            if (existing != null && existing.mob == mob
                    && !IsolationPolicy.retryDue(existing.attempts, existing.retryAt, now, MAX_ATTEMPTS)) {
                continue;
            }
            isolate(battle, mob, members, now);
        }
    }

    /**
     * A mob is only moved when it actually intrudes: inside this battlefield, hunting one of this
     * encounter's members, or close enough to displace one without a target.
     */
    private boolean intrudes(ServerBattle battle, Mob mob, List<ServerPlayer> members) {
        GridPos cell = new GridPos(Mth.floor(mob.getX()), Mth.floor(mob.getY()), Mth.floor(mob.getZ()));
        boolean targetsMember = mob.getTarget() instanceof ServerPlayer target
                && isMember(members, target)
                || brainTargetsMember(mob, members);
        return IsolationPolicy.intrudes(battle.zone().contains(cell), targetsMember,
                nearestMemberSqr(mob, members), CONTACT_RADIUS_SQR);
    }

    /** A Brain mob can hunt a member through memory only, with {@code Mob.getTarget()} staying null. */
    private static boolean brainTargetsMember(Mob mob, List<ServerPlayer> members) {
        if (mob.getBrain() == null) return false;
        Object attack = AggroCleanup.memory(mob.getBrain(),
                net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (attack instanceof ServerPlayer target && isMember(members, target)) return true;
        Object angryAt = AggroCleanup.memory(mob.getBrain(),
                net.minecraft.world.entity.ai.memory.MemoryModuleType.ANGRY_AT).orElse(null);
        if (angryAt instanceof UUID id) {
            for (ServerPlayer member : members) if (member.getUUID().equals(id)) return true;
        }
        return false;
    }

    private static boolean isMember(List<ServerPlayer> members, ServerPlayer candidate) {
        for (ServerPlayer member : members) if (member == candidate) return true;
        return false;
    }

    private static double nearestMemberSqr(Mob mob, List<ServerPlayer> members) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer member : members) best = Math.min(best, mob.distanceToSqr(member));
        return best;
    }

    /**
     * Only unowned, live, non-riding mobs of this level may be moved. Hostility is not required: a
     * passive or neutral mob can displace a participant by collision too.
     */
    private boolean eligible(ServerBattle battle, Mob mob) {
        if (mob == null || !mob.isAlive() || mob.isRemoved() || mob.isPassenger()) return false;
        if (mob.level() != battle.level()) return false;
        // Never take a unit that belongs to any encounter, including this one.
        if (BattleManager.ownerOf(mob.getUUID()) != null) return false;
        // Somebody's animal is not an intruder. The contact gate still keeps it from displacing a
        // member; it is simply never relocated.
        if (mob.isLeashed()) return false;
        return !(mob instanceof OwnableEntity owned && owned.getOwner() instanceof Player);
    }

    // ------------------------------------------------------------------ isolation

    private void isolate(ServerBattle battle, Mob mob, List<ServerPlayer> members, int now) {
        Entry entry = entries.get(mob.getUUID());
        if (entry == null || entry.mob != mob) {
            entry = new Entry(mob, now, Hold.CAP_REFUSED, null);
            entries.put(mob.getUUID(), entry);
        }
        // Clear the relationship first, so even a failed placement is already unreachable.
        clearRelationship(mob, members);
        Optional<Vec3> destination = BattleDeployment.findOutsideStandable(
                battle.level(), mob, battle.zone().center(), battle.zone().radius(),
                // A family body's candidate is proved loaded before anything is read there: the same
                // "whole body, every touched column, loaded first" rule its own deployment gate uses, added
                // narrowly by exact type and leaving every other mob's rule exactly as it was.
                box -> insideAnyZone(battle, box)
                        || (SlimeGround.supports(mob) && !BodyGeometry.loaded(battle.level(), box)));
        if (destination.isEmpty()) {
            entry.retryAt = now + RETRY_TICKS;
            entry.attempts++;
            entry.placed = false;
            if (entry.attempts == 1) {
                // One line per unit: a refused mob that cannot be placed is still suppressed,
                // but an operator needs to know the terrain left no safe exit.
                Tactical.LOGGER.info("[Tactical] no safe exit for {} at {} (stance {}); kept out by suppression only",
                        mob.getUUID(), mob.blockPosition(), BattleDeployment.stanceOf(mob));
            }
            return;
        }
        Vec3 feet = destination.get();
        Vec3 from = mob.position();
        mob.getNavigation().stop();
        mob.teleportTo(feet.x, feet.y, feet.z);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = 0;
        // A teleport can rebuild path/Brain state, so clear again at the destination. The per-tick
        // maintenance below is what keeps it true afterwards.
        clearRelationship(mob, members);
        entry.placed = true;
        entry.attempts = 0;
        entry.retryAt = now;
        if (DeveloperTrace.enabled()) {
            // Development trace: the authoritative from/to of an excluded unit, so an acceptance
            // scene never has to infer "it left" from the absence of a failure line.
            Tactical.LOGGER.info("[Tactical] dev-trace isolation session={} tick={} uuid={} tags={} from={} to={} zoneRadius={} hostiles={}",
                    battle.sessionId(), battle.tickCount(), mob.getUUID(), mob.getTags(),
                    from, mob.blockPosition(), battle.zone().radius(), battle.hostileCount());
        }
    }

    /**
     * Clears this mob's relationship with this encounter's members only.
     *
     * <p>A target that belongs to some other player's fight is deliberately left alone: moving the
     * mob out of this battlefield is this encounter's business, rewriting an unrelated player's
     * aggro is not.</p>
     */
    private void clearRelationship(Mob mob, List<ServerPlayer> members) {
        for (ServerPlayer member : members) AggroCleanup.clear(mob, member.getUUID());
    }

    /** Re-clean every registered unit; a Brain memory can reappear without a target-change event. */
    private void maintainRegistered(ServerBattle battle) {
        if (entries.isEmpty()) return;
        List<ServerPlayer> members = battle.memberPlayers();
        if (members.isEmpty()) return;
        for (Entry entry : entries.values()) {
            Mob mob = entry.mob;
            if (mob == null || mob.isRemoved() || !mob.isAlive()) continue;
            clearRelationship(mob, members);
        }
    }

    /** True when the body would overlap this encounter's battlefield or any other live one. */
    private boolean insideAnyZone(ServerBattle battle, AABB box) {
        if (BattleDeployment.overlapsZone(box, battle.zone().bounds())) return true;
        return BattleManager.insideAnyZone(battle.level(), box, battle);
    }

    // ------------------------------------------------------------------ cleanup

    /** Drop entries whose unit died, changed dimension, or is now owned by any encounter. */
    private void prune(ServerBattle battle) {
        entries.values().removeIf(entry -> {
            Mob mob = entry.mob;
            if (mob == null || mob.isRemoved() || !mob.isAlive() || mob.level() != battle.level()) return true;
            // Any claim ends the exclusion, including this encounter admitting the unit itself:
            // a legitimate participant must never be kept suppressed by stale state.
            return BattleManager.ownerOf(mob.getUUID()) != null;
        });
    }
}
