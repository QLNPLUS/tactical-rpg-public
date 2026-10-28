package com.tactical.neoforge.battle;

import com.tactical.core.combat.BattleRules;
import com.tactical.core.grid.GridPos;
import com.tactical.core.zone.BattleZone;
import com.tactical.core.zone.ZoneSeparation;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.battle.multi.SessionIndex;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encounter registry, ownership arbitration and intent routing.
 *
 * <p>Several encounters may run at the same time. Every route resolves its target from the
 * acting entity's own UUID ({@link #findFor(UUID)} / {@link #containing(Entity)}) and never
 * from an arbitrary first encounter. {@link #current()} remains only as the single-encounter
 * compatibility view for fixtures and commands: it answers null as soon as two encounters are
 * live, which keeps a forgotten single-encounter assumption visible instead of guessing.</p>
 *
 * <p>Ownership is exclusive and is claimed before a unit is mutated: a player or NPC belongs to
 * one encounter, and a refused claim leaves both encounters untouched. All calls run on the
 * server thread.</p>
 */
public final class BattleManager {

    private static final SessionIndex<ServerBattle> INDEX = new SessionIndex<>();
    private static boolean autoTrigger = true;
    private static int scanCooldown;

    private BattleManager() {
    }

    // ------------------------------------------------------------------ lookups

    /** The only live encounter, or null; with two or more encounters no caller may guess one. */
    public static ServerBattle current() {
        return INDEX.primary();
    }

    /** Immutable snapshot of every live encounter, in creation order. */
    public static List<ServerBattle> active() {
        return INDEX.all();
    }

    public static ServerBattle findFor(UUID actorId) {
        return INDEX.findFor(actorId);
    }

    public static ServerBattle findById(UUID sessionId) {
        return INDEX.findById(sessionId);
    }

    /** The encounter that currently owns this actor UUID, or null. Never inferred by position. */
    public static ServerBattle ownerOf(UUID actorId) {
        return INDEX.findFor(actorId);
    }

    public static SessionIndex<ServerBattle> sessions() {
        return INDEX;
    }

    /**
     * The encounter that actually contains this actor instance.
     *
     * <p>Both players and NPCs resolve through the same UUID index, so a mob never falls back to
     * "the first encounter". Membership is then re-checked against the exact entity instance, so
     * a same-UUID respawn or a stale object is not mistaken for the participant.</p>
     */
    public static ServerBattle containing(Entity entity) {
        if (entity == null) return null;
        ServerBattle battle = INDEX.findFor(entity.getUUID());
        return battle != null && battle.isParticipant(entity) ? battle : null;
    }

    /**
     * The encounter that may accept this brand-new unit as a reinforcement.
     *
     * <p>Deliberately not {@link #containing}: a candidate is by definition not a member yet, so
     * membership cannot be the criterion. An unowned unit inside exactly one encounter's zone
     * resolves there; a unit that already belongs to an encounter is never moved to another one.</p>
     */
    public static ServerBattle candidateFor(Entity entity) {
        if (entity == null) return null;
        if (INDEX.ownerOf(entity.getUUID()) != null) return null;
        for (ServerBattle battle : INDEX.all()) {
            if (!battle.finished() && battle.acceptsNewcomer(entity)) return battle;
        }
        return null;
    }

    /**
     * Every encounter holding a live invitation for this player (empty when none).
     *
     * <p>Expiry is evaluated against {@code now}, so a stale invitation cannot make the legacy
     * accept look ambiguous. Choosing between several real invitations needs a session id from the
     * client, which the join protocol does not carry yet - that stays with the discovery/join work,
     * and until then the parameter-less accept refuses instead of picking one.</p>
     */
    public static List<ServerBattle> sessionsInviting(UUID playerId, long now) {
        if (playerId == null) return List.of();
        List<ServerBattle> inviting = new ArrayList<>();
        for (ServerBattle battle : INDEX.all()) {
            // invitation() prunes an expired entry, so a stale invite cannot fake an ambiguity.
            if (battle.finished() || battle.session().invitation(playerId, now) == null) continue;
            inviting.add(battle);
        }
        return List.copyOf(inviting);
    }

    // ------------------------------------------------------------------ ownership

    static boolean registerBattle(ServerBattle battle) {
        return battle != null && INDEX.register(battle.sessionId(), battle);
    }

    static void unregisterBattle(ServerBattle battle) {
        if (battle != null) INDEX.remove(battle.sessionId());
    }

    /**
     * Claim one actor (player or NPC) for this encounter before it is mutated.
     *
     * @return false when the actor belongs to another encounter; nothing changes then.
     */
    static boolean claimActor(ServerBattle battle, UUID actorId) {
        return battle != null && INDEX.bind(battle.sessionId(), actorId);
    }

    /**
     * Claim with the reason, so a rollback can release only a claim this call actually created.
     * Releasing a claim this encounter already held would unbind a legitimate member.
     */
    static SessionIndex.Claim claimMember(ServerBattle battle, UUID actorId) {
        return battle == null ? SessionIndex.Claim.UNKNOWN_SESSION : INDEX.claim(battle.sessionId(), actorId);
    }

    /** Release one actor, but only from the encounter that is expected to own it. */
    static void releaseActor(ServerBattle battle, UUID actorId) {
        if (battle != null) INDEX.unbind(battle.sessionId(), actorId);
    }

    /**
     * True when a battlefield would share space with a different live encounter.
     *
     * <p>Same dimension only: two encounters in different dimensions are independent even when
     * their coordinates coincide. The comparison is the conservative closed-box test from
     * {@link ZoneSeparation}, so a near-miss in a box corner is also refused.</p>
     */
    static boolean zoneConflicts(ServerBattle candidate, BattleZone zone) {
        if (candidate == null || zone == null) return false;
        for (ServerBattle other : INDEX.all()) {
            if (other == candidate || other.finished()) continue;
            if (other.level() != candidate.level()) continue;
            if (ZoneSeparation.overlap(other.zone().bounds(), zone.bounds())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ tick

    private static boolean autoTriggerEnabled() {
        try {
            return autoTrigger && TacticalConfig.INSTANCE.autoTrigger.get();
        } catch (Exception ignored) {
            return autoTrigger;
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        tickFleeImmunity(event.getServer());
        // Members whose entry bootstrap could not be published are repaired here, with a bounded
        // backoff, and never at the cost of their membership or the roster.
        com.tactical.neoforge.battle.multi.PendingBootstraps.tick(event.getServer(),
                event.getServer().getTickCount());

        // Tick and retire each encounter on its own: one encounter's turn, finish or empty roster
        // never stops the others from advancing in the same server tick.
        for (ServerBattle battle : INDEX.all()) {
            if (battle.finished()) {
                INDEX.remove(battle.sessionId());
                continue;
            }
            battle.tick();
            if (battle.finished() || battle.memberCount() == 0) INDEX.remove(battle.sessionId());
        }

        if (!autoTriggerEnabled()) {
            return;
        }
        if (--scanCooldown > 0) {
            return;
        }
        scanCooldown = 10;
        scanForNewEncounters(event.getServer());
    }

    /**
     * Start encounters for players who are not fighting yet.
     *
     * <p>A player who is already in a battle is skipped, never used as a reason to stop scanning:
     * an aggro'd bystander elsewhere in the world still gets its own encounter.</p>
     */
    private static void scanForNewEncounters(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (findFor(player.getUUID()) != null) continue;
                if (hasAggro(level, player)) start(player);
            }
        }
    }

    private static boolean hasAggro(ServerLevel level, ServerPlayer player) {
        if (!player.isAlive() || player.isRemoved() || player.isSpectator() || isImmune(player)) {
            return false;
        }
        AABB area = player.getBoundingBox().inflate(zoneRadius());
        return !level.getEntitiesOfClass(Mob.class, area,
                mob -> mob.isAlive() && mob instanceof net.minecraft.world.entity.monster.Enemy
                        && mob.getTarget() == player).isEmpty();
    }

    public static int zoneRadius() {
        try {
            return TacticalConfig.INSTANCE.zoneRadius.get();
        } catch (Exception ignored) {
            return BattleRules.ZONE_RADIUS;
        }
    }

    /** Aggro suppression granted by a successful flee. */
    public static void grantFleeImmunity(ServerPlayer player) {
        FleeProtection.give(player);
        message(player, "脱离战斗，10 秒内敌对生物不会重新锁定你");
    }

    public static boolean isImmune(ServerPlayer player) {
        return FleeProtection.has(player);
    }

    private static void tickFleeImmunity(net.minecraft.server.MinecraftServer server) {
        FleeProtection.tick(server);
    }

    // ------------------------------------------------------------------ lifecycle

    static void onMemberJoined(ServerBattle battle, UUID playerId) {
        // The claim itself happens before the member is admitted; this only makes the coupling
        // explicit for the founder path and stays idempotent for an already claimed session.
        claimActor(battle, playerId);
    }

    static void onMemberLeft(ServerBattle battle, UUID playerId) {
        releaseActor(battle, playerId);
        // The session entry is removed by the encounter's own teardown (finish) or by the caller
        // after it. Removing it here would drop the encounter and every unit's ownership claim
        // before finish() has released its units and effects.
    }

    /** Called by the encounter itself once it has cleaned up its own units and state. */
    static void onBattleFinished(ServerBattle battle) {
        unregisterBattle(battle);
    }

    public static boolean start(ServerPlayer player) {
        return start(player, false);
    }

    public static boolean start(ServerPlayer player, boolean force) {
        if (player == null || !player.isAlive() || player.isRemoved() || player.isSpectator()) return false;
        // A fighter never founds a second encounter by accident; other players keeping their own
        // battle no longer blocks this one.
        if (findFor(player.getUUID()) != null) {
            message(player, "你已在战斗中");
            return false;
        }
        ServerBattle created;
        try {
            created = new ServerBattle((ServerLevel) player.level(), player);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] battle construction failed", failure);
            message(player, "战斗创建失败，请稍后重试");
            return false;
        }
        boolean started = false;
        try {
            // Register and claim the founder, and refuse an overlapping battlefield, before start()
            // mutates any entity. A refused start therefore leaves every other encounter untouched.
            if (!registerBattle(created) || !claimActor(created, player.getUUID())) {
                message(player, "战斗创建冲突，请稍后重试");
                return false;
            }
            if (zoneConflicts(created, created.zone())) {
                message(player, "附近已有其它战斗正在进行，请先结束或离开那里");
                return false;
            }
            if (!created.start(force)) {
                message(player, force
                        ? "附近没有可以接管的敌对生物"
                        : "附近没有对你有仇恨的敌对生物");
                return false;
            }
            started = true;
            message(player, "进入回合制战斗 · 左键点格子移动 · /tactical turn end 结束回合");
            return true;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] battle start failed; discarding this encounter", failure);
            message(player, "战斗开始失败，已放弃本场");
            return false;
        } finally {
            // Whatever step refused, returned early or threw, the registry entry and the founder's
            // claim never outlive this call.
            if (!started) {
                INDEX.remove(created.sessionId());
                INDEX.unbind(created.sessionId(), player.getUUID());
            }
        }
    }

    private static void message(ServerPlayer player, String text) {
        if (player != null && player.connection != null) {
            player.sendSystemMessage(Component.literal("[Tactical] " + text));
        }
    }

    /** Explicit stop of one player; other members keep the encounter. */
    public static void stop(ServerPlayer player) {
        leave(player, "stopped by command");
    }

    /**
     * Identity-scoped exit. An unrelated player's death/logout is a no-op, and a member
     * leaving never ends the encounter for the remaining players.
     */
    public static boolean leave(ServerPlayer player, String reason) {
        ServerBattle battle = findFor(player.getUUID());
        if (battle == null) return false;
        boolean removed = battle.leave(player, reason);
        if (battle.finished() || battle.memberCount() == 0) INDEX.remove(battle.sessionId());
        return removed;
    }

    public static boolean endForOwner(ServerPlayer player, String reason) {
        return leave(player, reason);
    }

    /** Respawn boundary: release the stale member instance, never admit the replacement. */
    static boolean releaseReplacedMember(ServerBattle battle, ServerPlayer replacement, String reason) {
        if (battle == null || replacement == null) return false;
        boolean released = battle.releaseReplacedMember(replacement.getUUID(), replacement, reason);
        if (released && (battle.finished() || battle.memberCount() == 0)) INDEX.remove(battle.sessionId());
        return released;
    }

    // ------------------------------------------------------------------ invites

    /** Only an existing member (the manager) can invite; never joins anybody automatically. */
    public static BattleEligibility.Denial invite(ServerPlayer requester, ServerPlayer target) {
        if (requester == null || target == null) return BattleEligibility.Denial.UNRESOLVED;
        ServerBattle battle = findFor(requester.getUUID());
        if (battle == null) {
            message(requester, "你不在战斗中");
            return BattleEligibility.Denial.UNRESOLVED;
        }
        BattleEligibility.Denial denial = battle.invite(requester, target);
        if (denial == BattleEligibility.Denial.NONE) {
            offerInvite(target, requester, battle.sessionId());
        } else {
            message(requester, BattleEligibility.describe(denial));
        }
        return denial;
    }

    private static void offerInvite(ServerPlayer target, ServerPlayer requester, UUID sessionId) {
        if (target == null || target.connection == null) return;
        // The link carries the session, so accepting a second invitation never lands in the first one.
        String acceptCommand = sessionId == null
                ? "/tactical battle accept"
                : "/tactical battle accept " + sessionId;
        String declineCommand = sessionId == null
                ? "/tactical battle decline"
                : "/tactical battle decline " + sessionId;
        Component accept = Component.literal("[接受]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, acceptCommand)));
        Component decline = Component.literal("[拒绝]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, declineCommand)));
        target.sendSystemMessage(Component.literal("[Tactical] " + requester.getGameProfile().getName()
                + " 邀请你加入回合制战斗  ").append(accept).append(Component.literal("  ")).append(decline));
    }

    /**
     * Explicit accept for one named invitation.
     *
     * <p>The command and the chat link carry the session id, so a player invited to two encounters
     * joins the one they clicked instead of whichever the registry happens to list first.</p>
     */
    public static BattleEligibility.Denial accept(ServerPlayer player, UUID sessionId) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        if (sessionId == null) return accept(player);
        if (findFor(player.getUUID()) != null) {
            message(player, "你已在战斗中");
            return BattleEligibility.Denial.ALREADY_MEMBER;
        }
        ServerBattle battle = findById(sessionId);
        if (battle == null || battle.finished()) {
            message(player, BattleEligibility.describe(BattleEligibility.Denial.SESSION_CLOSED));
            return BattleEligibility.Denial.SESSION_CLOSED;
        }
        if (battle.session().invitation(player.getUUID(), player.level().getGameTime()) == null) {
            message(player, BattleEligibility.describe(BattleEligibility.Denial.NO_INVITE));
            return BattleEligibility.Denial.NO_INVITE;
        }
        BattleEligibility.Denial denial = battle.join(player);
        if (denial == BattleEligibility.Denial.NONE) {
            announceJoin(player, battle);
        } else {
            message(player, BattleEligibility.describe(denial));
        }
        return denial;
    }

    /**
     * Answer the invitation's UI with the encounter that was entered.
     *
     * <p>The membership transition, the full snapshot, the journal history and the warning snapshot were
     * already published by the commit itself, so this is only the reply the page is waiting for. No
     * client-side authorization depends on it.</p>
     */
    static void announceJoin(ServerPlayer player, ServerBattle battle) {
        message(player, "已加入战斗 · 等待自己的回合");
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                new com.tactical.neoforge.network.JoinBattleResultPacket(battle.sessionId(),
                        BattleEligibility.Denial.NONE, ""));
    }

    /** Explicit decline for one named invitation; the others stay pending. */
    public static BattleEligibility.Denial decline(ServerPlayer player, UUID sessionId) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        if (sessionId == null) return decline(player);
        ServerBattle battle = findById(sessionId);
        if (battle == null || battle.finished()) {
            message(player, BattleEligibility.describe(BattleEligibility.Denial.SESSION_CLOSED));
            return BattleEligibility.Denial.SESSION_CLOSED;
        }
        BattleEligibility.Denial denial = battle.decline(player);
        if (denial == BattleEligibility.Denial.NONE) message(player, "已拒绝邀请");
        else message(player, BattleEligibility.describe(denial));
        return denial;
    }

    /**
     * Explicit accept; the invitation is re-validated against live state before joining.
     *
     * <p>The legacy command carries no session id, so it is only usable while exactly one
     * encounter invites this player. Several simultaneous invitations are reported instead of
     * joining whichever encounter happens to be first; a session-scoped accept belongs to the
     * upcoming join protocol.</p>
     */
    public static BattleEligibility.Denial accept(ServerPlayer player) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        if (findFor(player.getUUID()) != null) {
            message(player, "你已在战斗中");
            return BattleEligibility.Denial.ALREADY_MEMBER;
        }
        List<ServerBattle> inviting = sessionsInviting(player.getUUID(), player.level().getGameTime());
        if (inviting.isEmpty()) {
            message(player, BattleEligibility.describe(BattleEligibility.Denial.NO_INVITE));
            return BattleEligibility.Denial.NO_INVITE;
        }
        if (inviting.size() > 1) {
            // Never guess: joining the wrong encounter would move resources between battles.
            message(player, "你有 " + inviting.size() + " 个战斗邀请，请在战斗列表中选择要加入的一场");
            return BattleEligibility.Denial.UNRESOLVED;
        }
        ServerBattle battle = inviting.get(0);
        BattleEligibility.Denial denial = battle.join(player);
        if (denial == BattleEligibility.Denial.NONE) {
            announceJoin(player, battle);
        } else {
            message(player, BattleEligibility.describe(denial));
        }
        return denial;
    }

    /** Fixture-only accept for headless self tests; identical validation minus the socket. */
    static BattleEligibility.Denial acceptHeadless(ServerPlayer player) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        List<ServerBattle> inviting = sessionsInviting(player.getUUID(), player.level().getGameTime());
        if (inviting.size() != 1) return BattleEligibility.Denial.NO_INVITE;
        return inviting.get(0).joinHeadless(player);
    }

    public static BattleEligibility.Denial decline(ServerPlayer player) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        List<ServerBattle> inviting = sessionsInviting(player.getUUID(), player.level().getGameTime());
        if (inviting.isEmpty()) {
            message(player, BattleEligibility.describe(BattleEligibility.Denial.NO_INVITE));
            return BattleEligibility.Denial.NO_INVITE;
        }
        if (inviting.size() > 1) {
            message(player, "你有 " + inviting.size() + " 个战斗邀请，请在战斗列表中选择要拒绝的一场");
            return BattleEligibility.Denial.UNRESOLVED;
        }
        BattleEligibility.Denial denial = inviting.get(0).decline(player);
        if (denial == BattleEligibility.Denial.NONE) message(player, "已拒绝邀请");
        else message(player, BattleEligibility.describe(denial));
        return denial;
    }

    // ------------------------------------------------------------------ intents

    private static ServerBattle memberBattle(ServerPlayer player) {
        return player == null ? null : findFor(player.getUUID());
    }

    public static void move(ServerPlayer player, ActionStamp stamp, int x, int y, int z) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.tryMove(player, stamp, new GridPos(x, y, z));
    }

    public static void chooseMove(ServerPlayer player, ActionStamp stamp, UUID token, int mode) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.chooseMove(player, stamp, token, mode);
    }

    public static void attack(ServerPlayer player, ActionStamp stamp, UUID target) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.tryAttack(player, stamp, target);
    }

    public static int adjustAp(ServerPlayer target, int delta) {
        ServerBattle battle = memberBattle(target);
        return battle == null || battle.finished() ? -1 : battle.adjustAp(target, delta);
    }

    public static void endTurn(ServerPlayer player, ActionStamp stamp) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.endPlayerTurn(player, stamp);
    }

    public static void openInventory(ServerPlayer player) {
        // Legacy packet is deliberately inert; the client E key opens vanilla InventoryScreen.
    }

    public static void swapSlots(ServerPlayer player, ActionStamp stamp, int first, int second) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.swapSlots(player, stamp, first, second);
    }

    public static void inventoryAction(ServerPlayer player, ActionStamp stamp, int slot, int action) {
        ServerBattle battle = memberBattle(player);
        if (battle == null) return;
        if (action == com.tactical.neoforge.network.InventoryActionPacket.DROP) {
            battle.dropSlot(player, stamp, slot);
        } else if (action == com.tactical.neoforge.network.InventoryActionPacket.SWAP_MAIN) {
            battle.swapHand(player, stamp, slot, false);
        } else if (action == com.tactical.neoforge.network.InventoryActionPacket.SWAP_OFF) {
            battle.swapHand(player, stamp, slot, true);
        }
    }

    public static void endTurnFacing(ServerPlayer player, ActionStamp stamp, int yaw) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.endPlayerTurnFacing(player, stamp, yaw);
    }

    public static void flee(ServerPlayer player, ActionStamp stamp) {
        ServerBattle battle = memberBattle(player);
        if (battle == null) return;
        battle.tryFlee(player, stamp);
        if (battle.finished() || battle.memberCount() == 0) INDEX.remove(battle.sessionId());
    }

    public static void defend(ServerPlayer player, ActionStamp stamp) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.tryDefend(player, stamp);
    }

    public static void defend(ServerPlayer player, ActionStamp stamp, int yaw) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.tryDefendFacing(player, stamp, yaw);
    }

    public static void placeBlock(ServerPlayer player, ActionStamp stamp, int slot,
                                  net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face,
                                  net.minecraft.world.phys.Vec3 hit) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.placeBlock(player, stamp, slot, pos, face, hit);
    }

    public static void useItem(ServerPlayer player, ActionStamp stamp, int slot) {
        ServerBattle battle = memberBattle(player);
        if (battle != null) battle.useItem(player, stamp, slot);
    }

    // ---- fixture-only wrappers -------------------------------------------------
    // These exist so in-game self tests can drive a single-member encounter directly.
    // Network handlers must always call the stamped forms above.

    /**
     * Fixture-only pre-check: the caller must be this exact member instance. A replaced player that
     * merely shares the UUID is not allowed to drive the encounter through a fixture wrapper.
     */
    private static ServerBattle fixtureBattle(ServerPlayer player) {
        ServerBattle battle = memberBattle(player);
        return battle != null && battle.isMember(player) ? battle : null;
    }

    public static void move(ServerPlayer player, int x, int y, int z) {
        ServerBattle battle = fixtureBattle(player);
        if (battle != null) battle.tryMove(new GridPos(x, y, z));
    }

    public static void attack(ServerPlayer player, UUID target) {
        ServerBattle battle = fixtureBattle(player);
        if (battle != null) battle.tryAttack(target);
    }

    public static void endTurn(ServerPlayer player) {
        ServerBattle battle = fixtureBattle(player);
        if (battle != null) battle.endPlayerTurn();
    }

    public static void endTurnFacing(ServerPlayer player, int yaw) {
        ServerBattle battle = fixtureBattle(player);
        if (battle != null) battle.endPlayerTurnFacing(player, battle.stamp(), yaw);
    }

    public static void defend(ServerPlayer player) {
        ServerBattle battle = fixtureBattle(player);
        if (battle != null) battle.tryDefend();
    }

    public static void flee(ServerPlayer player) {
        ServerBattle battle = fixtureBattle(player);
        if (battle == null) return;
        battle.tryFlee();
        if (battle.finished() || battle.memberCount() == 0) INDEX.remove(battle.sessionId());
    }

    public static void setAutoTrigger(boolean enabled) {
        autoTrigger = enabled;
    }

    public static boolean autoTrigger() {
        return autoTrigger;
    }

    public static boolean isParticipant(Entity entity) {
        if (entity == null) return false;
        ServerBattle battle = INDEX.findFor(entity.getUUID());
        return battle != null && battle.isParticipant(entity);
    }

    /**
     * Test/administrative teardown of every encounter, one by one.
     *
     * <p>Each encounter finishes its own units and state before the registry is cleared, so no
     * encounter is left holding effects or entity ownership.</p>
     */
    public static void reset() {
        for (ServerBattle battle : INDEX.all()) battle.finish("reset");
        INDEX.clear();
        FleeProtection.clear();
        // The published memberships end, but the per-player counters keep increasing: the server process
        // (and its epoch) continues, so a lower generation would be refused by the very client that
        // already applied the exit.
        com.tactical.neoforge.battle.multi.MembershipGenerations.endMemberships();
        com.tactical.neoforge.battle.multi.PendingBootstraps.clear();
    }
}
