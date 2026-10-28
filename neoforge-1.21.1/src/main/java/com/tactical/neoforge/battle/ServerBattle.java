package com.tactical.neoforge.battle;

import com.tactical.core.combat.ApCalculator;
import com.tactical.core.combat.BattlePhase;
import com.tactical.core.combat.BattleRules;
import com.tactical.core.combat.CombatantSnapshot;
import com.tactical.core.combat.Team;
import com.tactical.core.combat.TurnScheduler;
import com.tactical.core.grid.GridMovementRules;
import com.tactical.core.grid.GridNavigator;
import com.tactical.core.grid.GridWorld;
import com.tactical.core.grid.MovementRoutes;
import com.tactical.core.grid.DropTrajectory;
import com.tactical.core.zone.BattleZone;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.battle.multi.BattleRelationPolicy;
import com.tactical.neoforge.battle.multi.BattleSession;
import com.tactical.neoforge.battle.multi.PlayerParticipant;
import com.tactical.neoforge.battle.multi.SessionIndex;
import com.tactical.neoforge.network.MoveChoiceOfferPacket;
import com.tactical.neoforge.battle.ai.*;
import com.tactical.core.combat.ProjectileAim;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.skill.TacticalSkills;
import com.tactical.neoforge.skill.SkillAvailability;
import com.tactical.neoforge.skill.SkillTarget;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.network.PlayerStepPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One turn-based encounter. Server-authoritative: the client only sends intents
 * and renders the snapshot.
 *
 * <p>Multiplayer scope A: still one encounter for the whole server, but every real
 * player keeps its own {@link PlayerParticipant}. The shared ATB queue, enemy roster,
 * zone and log are common; AP, MP, cooldowns, escalation, defence, occupancy and
 * movement animation are per player. Nothing here reads a global "owner" player for
 * authority: intents are resolved from the real connection identity plus an
 * {@link ActionStamp} bound to this session and this turn generation.</p>
 */
public final class ServerBattle {

    public static final int ZONE_RADIUS = BattleRules.ZONE_RADIUS;
    public static final int ZONE_HEIGHT = BattleRules.ZONE_HEIGHT;
    public static final int PARTICIPANT_CAP = BattleRules.PARTICIPANT_CAP;

    private static final int PLAYER_STEP_TICKS = 3;
    private static final int ENEMY_STEP_TICKS = 3;
    private static final int AI_TURN_DELAY = 6;
    private static final ResourceLocation REACH_MODIFIER =
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_reach");

    /** One live player member: connection identity plus its own resource block. */
    private static final class PlayerMember {
        final ServerPlayer entity;
        final PlayerParticipant state;
        ItemStack lastSyncedWeapon = ItemStack.EMPTY;

        PlayerMember(ServerPlayer entity, PlayerParticipant state) {
            this.entity = entity;
            this.state = state;
            state.entityId(entity.getId());
        }
        UUID id() { return entity.getUUID(); }
        String name() { return entity.getGameProfile().getName(); }
    }

    private final ServerLevel level;
    private final ServerPlayer creator;
    private final GridPos center;
    private final LevelGridWorld world;
    private final GridNavigator navigator;
    private final BattleProjectiles projectiles;
    private final BattleJournal journal;
    private final BattleAiSession ai;
    private final BattleSession session;
    private final Map<UUID, PlayerMember> members = new LinkedHashMap<>();
    private BattleRelationPolicy relations = BattleRelationPolicy.pve();
    private BattleZone zone;

    private final TurnScheduler queue = new TurnScheduler();
    // Includes both hostile and friendly mobs; side-sensitive rules must use hostileCount().
    private final Map<UUID, EnemyState> enemies = new LinkedHashMap<>();
    private final List<Mob> bystanders = new ArrayList<>();
    private int aggroPulse;

    private final int zoneHeight;
    private final int participantCap;
    private final int playerCapacity;
    private final int stepTicks;
    private int zoneRadius;

    private BattlePhase phase = BattlePhase.DEPLOYING;
    private int aiDelay;
    private boolean finished;
    private boolean ending;
    private int actionCount;
    private int stateRevision;
    private final Map<Integer,Float> lastHealth=new java.util.HashMap<>();
    private boolean awaitingShotResolution;
    private UUID pendingEnemyShot;
    private int shotRecoveryTicks;

    private List<GridPos> enemyPath = List.of();
    private int enemyPathIndex;
    private boolean enemyMoving;
    private boolean enemyStepping;
    private GridPos enemyStepTarget;
    private Vec3 enemyStepFrom;
    private int enemyStepProgress;
    private int enemyDiveAttackIndex=-1;
    private boolean enemyDiveAttacked;
    private int enemyDiveReservedCost;

    public ServerBattle(ServerLevel level, ServerPlayer creator) {
        this.level = level;
        this.creator = creator;
        this.center = new GridPos(creator.getBlockX(), creator.getBlockY(), creator.getBlockZ());
        this.world = new LevelGridWorld(level);
        this.navigator = new GridNavigator(new GridWorld() {
            public GridPos standCell(int x,int z,int y) { return world.standCell(x,z,y); }
            public boolean isPassable(GridPos cell) { return zone.contains(cell); }
        });
        this.projectiles = new BattleProjectiles(level, creator);
        this.zoneRadius = intConfig(TacticalConfig.INSTANCE.zoneRadius, BattleRules.ZONE_RADIUS);
        this.zoneHeight = intConfig(TacticalConfig.INSTANCE.zoneHeight, BattleRules.ZONE_HEIGHT);
        this.participantCap = intConfig(TacticalConfig.INSTANCE.participantCap, BattleRules.PARTICIPANT_CAP);
        this.playerCapacity = intConfig(TacticalConfig.INSTANCE.maxBattlePlayers, 4);
        this.stepTicks = intConfig(TacticalConfig.INSTANCE.playerStepTicks, PLAYER_STEP_TICKS);
        String shape;
        try { shape=TacticalConfig.INSTANCE.zoneShape.get(); } catch(Exception ignored) { shape="tactical:cylinder"; }
        try { zone=BattleZones.create(shape,center,zoneRadius,zoneHeight); }
        catch(IllegalArgumentException exception) {
            Tactical.LOGGER.warn("Invalid battle zone factory {}; using cylinder",shape);
            zone=BattleZones.create("tactical:cylinder",center,zoneRadius,zoneHeight);
        }
        this.session = new BattleSession(playerCapacity);
        // The journal and the AI warnings speak with the encounter's own session id; a private id
        // would make legitimate packets look like another encounter's traffic on the client.
        this.journal = new BattleJournal(session.sessionId(), this::recipients);
        this.ai = new BattleAiSession(level, session.sessionId(), () -> !finished && !members.isEmpty(),
                this::broadcast);
    }

    private static int intConfig(net.neoforged.neoforge.common.ModConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // life cycle
    // ------------------------------------------------------------------

    public boolean start() {
        return start(false);
    }

    public boolean start(boolean force) {
        // Ownership is claimed before any entity is touched, so a refused start rolls back by
        // dropping this encounter from the registry alone - no mob keeps a changed AI state.
        if (!BattleManager.claimActor(this, creator.getUUID())) {
            return false;
        }
        Vec3 creatorOriginalPosition = creator.position();
        try {
            // Even the read-only cell query stays inside the transaction: any failure below must
            // leave this encounter with no claim, no unit, no roster and the founder at its spot.
            GridPos creatorCell = world.surfaceCell(creator.getBlockX(), creator.getBlockZ(), creator.getBlockY());
            if (creatorCell == null) {
                Tactical.LOGGER.warn("[Tactical] player has no standable cell");
                BattleManager.releaseActor(this, creator.getUUID());
                return false;
            }
            return deployEncounter(force, creatorCell);
        } catch (RuntimeException failure) {
            // Bounded transaction: everything this call acquired is given back, and nothing that
            // existed before it is touched.
            Tactical.LOGGER.error("[Tactical] battle start failed; rolling this encounter back", failure);
            rollbackStart(creatorOriginalPosition);
            return false;
        }
    }

    private boolean deployEncounter(boolean force, GridPos creatorCell) {
        AABB area = zoneBounds();

        List<Mob> candidates = level.getEntitiesOfClass(Mob.class, area,
                        mob -> mob.isAlive() && !mob.isRemoved() && isHostile(mob)
                                && zone.contains(cellOf(mob)) && (force || mob.getTarget() == creator))
                .stream()
                .sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(creator)))
                .toList();

        if (candidates.isEmpty()) {
            return false;
        }

        for (Mob mob : candidates) {
            if(hostileCount()>=participantCap)break;
            var safe=deploymentPosition(mob,
                    pos->occupied(new GridPos(pos.getX(),pos.getY(),pos.getZ())),
                    pos->zone.contains(new GridPos(pos.getX(),pos.getY(),pos.getZ())));
            if(safe.isEmpty()) continue;
            Vec3 position=safe.get();
            GridPos cell=new GridPos(net.minecraft.util.Mth.floor(position.x),net.minecraft.util.Mth.floor(position.y),net.minecraft.util.Mth.floor(position.z));
            admit(mob, cell, position, BattleSide.HOSTILE);
        }

        if (hostileCount() == 0) {
            return false;
        }

        // Overflow/nonparticipants retain ordinary AI and aggro. They can join as soon as
        // a roster slot opens; clearing targets here would erase reinforcement intent.
        // The founder is admitted by a different code path than join(), but the contract is the
        // same: nothing may keep a container or a cursor stack open once the player is authoritative.
        settleOpenContainer(creator);
        PlayerParticipant founder = participantFor(creator, creatorCell);
        session.addMember(founder);
        PlayerMember member = new PlayerMember(creator, founder);
        members.put(creator.getUUID(), member);
        BattleManager.onMemberJoined(this, creator.getUUID());

        snapPlayer(member, creatorCell);
        extendReach(member);

        queue.rebuild(roster());
        refreshTacticalAttributes();
        phase = BattlePhase.DEPLOYING;

        Tactical.LOGGER.info("[Tactical] battle start center={} enemies={} playerInit={} capacity={}",
                center, hostileCount(), founder.initiative(), playerCapacity);
        // The founder enters through the same path as any other member: one authoritative transition,
        // its own full snapshot, the full journal and the full warning snapshot.
        publishActivation(creator, null);
        journal.record("battle", "战斗开始：" + hostileCount() + " 个敌人加入");
        beginTurn(queue.advance());
        // Fixture-only failure point at the very end of deployment: everything is already acquired.
        fault(FaultPoint.START_AFTER_ADMISSION);
        return true;
    }

    /**
     * Bounded start rollback: give back exactly what this call acquired and restore the units it
     * moved. Nothing outside this encounter is touched, and no other encounter is consulted.
     */
    private void rollbackStart(Vec3 creatorOriginalPosition) {
        // Recipients that may already have received an active snapshot during deployment.
        List<ServerPlayer> reached = memberPlayers();
        for (EnemyState state : List.copyOf(enemies.values())) restoreAdmission(state);
        enemies.clear();
        ai.clear();
        for (String id : List.copyOf(queue.order())) queue.remove(id);
        // Discard the journal while it still has its recipients: the CLEAR has to reach every client
        // that already got START/UPDATE for an encounter that will never exist. Removing the members
        // first would leave the journal without a single recipient.
        try {
            journal.clear();
        } catch (RuntimeException ignored) {
            // A failed publish must not block teardown.
        }
        for (PlayerMember member : List.copyOf(members.values())) {
            members.remove(member.id());
            try {
                restoreReach(member);
            } catch (RuntimeException ignored) {
                // A rollback must not stop halfway on a secondary failure.
            }
            BattleEffects.forget(member.entity);
            BattleManager.releaseActor(this, member.id());
        }
        members.clear();
        session.close();
        finished = true;
        phase = BattlePhase.ENDED;
        if (creator != null && !creator.isRemoved() && creatorOriginalPosition != null) {
            creator.setPos(creatorOriginalPosition.x, creatorOriginalPosition.y, creatorOriginalPosition.z);
            creator.setDeltaMovement(Vec3.ZERO);
        }
        // The founder's own claim is released even when the failure happened before it was added
        // to the roster; releasing an unowned actor is a no-op.
        if (creator != null) BattleManager.releaseActor(this, creator.getUUID());
        // Correct every client that was already told the encounter exists. Each push is guarded, so
        // one broken connection cannot stop the teardown.
        for (ServerPlayer recipient : reached) {
            try {
                // A start that already published an entry is corrected by a higher exit generation,
                // never by pretending the entry never happened.
                compensateExit(recipient);
            } catch (RuntimeException ignored) {
                // Keep correcting the remaining recipients.
            }
        }
        BattleManager.onBattleFinished(this);
    }

    private PlayerParticipant participantFor(ServerPlayer player, GridPos cell) {
        int maxAp = TacticalAttributes.maxAp(player);
        int moveCost = ApCalculator.moveCost(attribute(player, Attributes.MOVEMENT_SPEED));
        int initiative = ApCalculator.initiative(
                attribute(player, Attributes.ATTACK_SPEED),
                attribute(player, Attributes.MOVEMENT_SPEED),
                level.random.nextInt(10));
        return new PlayerParticipant(player.getUUID(), player.getGameProfile().getName(), cell,
                initialAp(maxAp), maxAp, moveCost, initiative);
    }

    private List<CombatantSnapshot> roster() {
        List<CombatantSnapshot> roster = new ArrayList<>();
        for (PlayerMember member : members.values()) {
            PlayerParticipant state = member.state;
            roster.add(new CombatantSnapshot(state.playerId().toString(), member.name(), Team.PLAYER,
                    state.cell(), (int) member.entity.getHealth(), (int) member.entity.getMaxHealth(),
                    state.initiative(), state.ap(), state.maxAp()));
        }
        for (Map.Entry<UUID, EnemyState> entry : enemies.entrySet()) {
            EnemyState state = entry.getValue();
            roster.add(new CombatantSnapshot(entry.getKey().toString(),
                    state.mob.getDisplayName().getString(), state.side == BattleSide.PLAYER ? Team.PLAYER : Team.ENEMY, state.cell,
                    (int) state.mob.getHealth(), (int) state.mob.getMaxHealth(),
                    state.initiative, state.ap, state.maxAp));
        }
        return roster;
    }

    public void tick() {
        if (finished) {
            return;
        }
        pruneMembers();
        if (finished) return;
        if (members.isEmpty()) {
            finish("no participants");
            return;
        }

        boolean attributesChanged=refreshTacticalAttributes();
        boolean healthChanged=healthChanged();
        boolean weaponChanged=weaponChanged();
        boolean projectileChanged = projectiles.tick();
        removeDeadEnemies();
        if (finished) return;
        ai.tick();
        // Vanilla gravity still runs with NoAI. Only airborne-policy actors are pinned,
        // and never while their authoritative own-turn trajectory is advancing.
        for(var entry:enemies.entrySet()) {
            EnemyState air=entry.getValue();
            if(air.flying&&!(enemyMoving&&entry.getKey().toString().equals(queue.current()))) {
                Vec3 held=AerialMovement.feet(air.cell,air.airOffset);
                if(AerialMovement.isClearAir(level,air.mob,held)) air.mob.setPos(held);
                air.mob.setDeltaMovement(Vec3.ZERO);air.mob.fallDistance=0;
            }
        }
        if (awaitingShotResolution && !projectiles.pending()) {
            if (shotRecoveryTicks < 0) shotRecoveryTicks = hasMovingEnemies() ? 4 : 0;
            if (shotRecoveryTicks > 0) {
                shotRecoveryTicks--;
            } else {
                reconcileShotKnockback();
                awaitingShotResolution = false;
                UUID shooter=pendingEnemyShot;
                pendingEnemyShot=null;
                if(shooter!=null && shooter.toString().equals(queue.current())) {
                    advanceTurn();
                    if(finished) return;
                }
                projectileChanged = true;
            }
        }
        if (projectileChanged || attributesChanged || healthChanged || weaponChanged) {
            sendState();
        }

        pulseAggro();

        boolean anyMoving = false;
        for (PlayerMember member : List.copyOf(members.values())) {
            if (!member.state.moving()) continue;
            stepPlayer(member);
            anyMoving = true;
        }
        if (anyMoving) return;
        for (PlayerMember member : members.values()) pinPlayer(member);
        if (rangedBusy()) return;

        if (phase == BattlePhase.ENEMY_TURN) {
            if (aiDelay > 0) {
                aiDelay--;
                return;
            }
            if (enemyMoving) {
                stepEnemy();
            } else {
                planEnemyTurn();
            }
        }
    }

    public void finish(String reason) {
        if (finished || ending) {
            return;
        }
        ending = true;
        journal.finish(reason);
        TacticalSkills.onBattleEnd(this);
        finished = true;
        phase = BattlePhase.ENDED;
        projectiles.clear();
        ai.clear();
        // Effects belong to the actor, so they are released per unit of THIS encounter. A global
        // clear would also drop another running encounter's effect clocks, burning baselines and
        // food settlement. Capture the roster before the maps are emptied.
        List<LivingEntity> released = new ArrayList<>(members.size() + enemies.size());
        for (PlayerMember member : members.values()) released.add(member.entity);
        for (EnemyState state : enemies.values()) released.add(state.mob);
        awaitingShotResolution = false;
        pendingEnemyShot=null;
        // Dead/disconnected players still own a connection and must receive the exit snapshot.
        for (PlayerMember member : List.copyOf(members.values())) {
            releaseMemberVisuals(member, true);
            publishExit(member);
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity, inactiveState(member));
        }
        members.clear();
        session.close();
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        for (EnemyState state : enemies.values()) {
            state.mob.setNoAi(state.originalNoAi);
            state.mob.setTarget(null);
        }
        enemies.clear();
        for (Mob bystander : bystanders) {
            bystander.setNoAi(false);
            bystander.setTarget(null);
        }
        bystanders.clear();
        for (LivingEntity actor : released) BattleEffects.forget(actor);
        // Drop this encounter's registry entries and ownership claims; other encounters keep theirs.
        BattleManager.onBattleFinished(this);
        Tactical.LOGGER.info("[Tactical] battle end: {}", reason);
    }

    public boolean finished() {
        return finished;
    }

    /** The dimension this encounter owns units in; two encounters there may not share space. */
    public ServerLevel level() {
        return level;
    }

    /** Legacy name kept for self tests: this exact player instance is a live member. */
    public boolean isOwner(ServerPlayer candidate) {
        return isMember(candidate);
    }

    public boolean isMember(ServerPlayer candidate) {
        if (finished || candidate == null) return false;
        PlayerMember member = members.get(candidate.getUUID());
        return member != null && member.entity == candidate;
    }

    public boolean isMember(UUID playerId) {
        return !finished && session.isMember(playerId);
    }

    public boolean isManager(ServerPlayer candidate) {
        PlayerMember member = candidate == null ? null : members.get(candidate.getUUID());
        return member != null && member.entity == candidate && session.isManager(candidate.getUUID());
    }

    public UUID managerId() { return session.managerId(); }

    public UUID sessionId() { return session.sessionId(); }

    public long turnEpoch() { return session.turnEpoch(); }

    /** Current stamp clients must echo; read-only and never trusted as an identity claim. */
    public ActionStamp stamp() { return session.stamp(); }

    public int memberCount() { return members.size(); }

    /** Shared ATB queue size, used by the multiplayer fixture readback. */
    public int turnQueueSize() { return queue.order().size(); }

    public int playerCapacity() { return playerCapacity; }

    public List<PlayerParticipant> participantStates() { return session.members(); }

    /** Legacy single-member read: the manager's live entity, or null once nobody is left. */
    public ServerPlayer player() {
        PlayerMember manager = managerMember();
        return manager == null ? null : manager.entity;
    }

    public BattleSession session() { return session; }

    private PlayerMember managerMember() {
        PlayerMember member = session.managerId() == null ? null : members.get(session.managerId());
        if (member != null) return member;
        return members.isEmpty() ? null : members.values().iterator().next();
    }

    /** The member whose turn it currently is, when that actor is a player. */
    private PlayerMember actingMember() {
        String current = queue.current();
        if (current != null) {
            PlayerMember acting = members.get(UUID.fromString(current));
            if (acting != null) return acting;
        }
        return managerMember();
    }

    private List<ServerPlayer> recipients() {
        List<ServerPlayer> result = new ArrayList<>(members.size());
        // Production only ever addresses a live transport; a fixture that observes publications also
        // wants the attempts for a member whose channel cannot carry them.
        boolean observing = com.tactical.neoforge.network.ServerPackets.isCapturing();
        for (PlayerMember member : members.values()) {
            if (observing || com.tactical.neoforge.network.ServerPackets.canReceive(member.entity)) {
                result.add(member.entity);
            }
        }
        return result;
    }

    /** Live member connections, join order; used by per-recipient skill/telegraph sync. */
    public List<ServerPlayer> memberPlayers() { return recipients(); }

    /** The member entity this encounter holds for one id, or null. Never resolves through PlayerList. */
    public ServerPlayer memberPlayer(UUID playerId) {
        if (playerId == null) return null;
        PlayerMember member = members.get(playerId);
        return member == null ? null : member.entity;
    }

    private void broadcast(CustomPacketPayload packet) {
        for (ServerPlayer recipient : recipients()) {
            com.tactical.neoforge.network.ServerPackets.send(recipient, packet);
        }
    }

    // ------------------------------------------------------------------
    // membership
    // ------------------------------------------------------------------

    private BattleEligibility.Facts factsFor(ServerPlayer candidate) {
        return factsFor(candidate, true);
    }

    private BattleEligibility.Facts factsFor(ServerPlayer candidate, boolean requireConnection) {
        boolean online = requireConnection
                ? candidate.connection != null && !candidate.hasDisconnected()
                // Headless self-test fixtures have no ClientConnection but are really in the level.
                : candidate.isAlive() && !candidate.isRemoved();
        boolean alive = candidate.isAlive() && !candidate.isRemoved();
        boolean sameDimension = candidate.level() == level;
        GridPos cell = cellOf(candidate);
        boolean insideZone = sameDimension && zone.contains(cell)
                && world.surfaceCell(candidate.getBlockX(), candidate.getBlockZ(), candidate.getBlockY()) != null;
        return BattleEligibility.Facts.of(online, alive, candidate.isSpectator(), sameDimension, insideZone,
                session.isMember(candidate.getUUID()), members.size(), playerCapacity,
                finished || ending || session.closed());
    }

    /**
     * Invitation management only: the manager may invite, nobody may act for another player.
     *
     * <p>Permission is decided first, so a real but unprivileged requester - including a player
     * who is not a member at all - is told {@code NOT_MANAGER} instead of an ambiguous
     * "unresolved". {@code UNRESOLVED} is kept for a null/unresolvable argument and for a stale
     * {@code ServerPlayer} instance that shares a member's UUID: that instance must never act on
     * the member's behalf. No invitation path is relaxed by this ordering.</p>
     */
    public BattleEligibility.Denial invite(ServerPlayer requester, ServerPlayer target) {
        if (requester == null || target == null) return BattleEligibility.Denial.UNRESOLVED;
        if (finished || ending || session.closed()) return BattleEligibility.Denial.SESSION_CLOSED;
        PlayerMember member = members.get(requester.getUUID());
        // Same UUID, different live object: a replaced player instance cannot borrow authority.
        if (member != null && member.entity != requester) return BattleEligibility.Denial.UNRESOLVED;
        if (!session.isManager(requester.getUUID())) return BattleEligibility.Denial.NOT_MANAGER;
        BattleSession.InviteResult result = session.invite(requester.getUUID(), target.getUUID(),
                level.getGameTime(), BattleSession.DEFAULT_INVITE_TTL_TICKS, factsFor(target));
        if (result.accepted()) {
            journal.record("battle", member.name() + " 邀请 " + target.getGameProfile().getName() + " 参战");
        }
        return result.denial();
    }

    public BattleEligibility.Denial pendingInvite(UUID invitee) {
        return session.invitation(invitee, level.getGameTime()) == null
                ? BattleEligibility.Denial.NO_INVITE : BattleEligibility.Denial.NONE;
    }

    /** Display name of the encounter's manager, for the nearby list. Empty when unknown. */
    public String managerName() {
        UUID manager = session.managerId();
        return manager == null ? "" : nameOf(manager.toString());
    }

    /**
     * Why a direct opt-in join would be refused right now, or {@code NONE}.
     *
     * <p>Same rules as the invitation path - online, alive, not spectator, same dimension, inside the
     * zone, not already in this roster, capacity, and a safe action boundary - but no invitation is
     * required. The nearby list and the join intent both resolve through this one place, so the
     * summary a player sees cannot promise something the join would refuse for another reason.</p>
     */
    public BattleEligibility.Denial directJoinDenial(ServerPlayer player) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        if (finished || ending) return BattleEligibility.Denial.SESSION_CLOSED;
        BattleEligibility.Denial denial = BattleEligibility.evaluate(factsFor(player));
        if (denial != BattleEligibility.Denial.NONE) return denial;
        GridPos cell = world.surfaceCell(player.getBlockX(), player.getBlockZ(), player.getBlockY());
        if (cell == null) return BattleEligibility.Denial.UNRESOLVED;
        return BattleEligibility.evaluateJoinBoundary(isBusy(), !reserved(cell));
    }

    /** Direct opt-in join from the nearby list; the invitation path is untouched. */
    public BattleEligibility.Denial joinDirect(ServerPlayer player) {
        return join(player, level.getGameTime(), true, false);
    }

    /**
     * Explicit accept. Eligibility, dimension, zone and capacity are re-validated against
     * live state; the joining player starts from zero ATB charge at its own standable cell.
     */
    public BattleEligibility.Denial join(ServerPlayer player) {
        return join(player, level.getGameTime());
    }

    /** Fixture-only join used by headless server self tests; still re-validates every rule. */
    BattleEligibility.Denial joinHeadless(ServerPlayer player) {
        return join(player, level.getGameTime(), false);
    }

    public BattleEligibility.Denial join(ServerPlayer player, long now) {
        return join(player, now, true, true);
    }

    private BattleEligibility.Denial join(ServerPlayer player, long now, boolean requireConnection) {
        return join(player, now, requireConnection, true);
    }

    /**
     * @param requireInvitation true for the explicit invitation flow; false for a direct opt-in join
     *                          from the nearby list, which must not need anybody's approval.
     */
    private BattleEligibility.Denial join(ServerPlayer player, long now, boolean requireConnection,
                                          boolean requireInvitation) {
        if (finished || ending) return BattleEligibility.Denial.SESSION_CLOSED;
        // Everything from here on runs inside one transaction: the pieces it can acquire are the
        // player's ownership claim, the session member slot, the roster/queue entry, the reach
        // modifiers, the consumed invitation and the player's position.
        Vec3 originalPosition = player.position();
        BattleSession.Invitation pendingInvitation = null;
        SessionIndex.Claim claim = SessionIndex.Claim.UNKNOWN_SESSION;
        PlayerMember member = null;
        try {
            BattleEligibility.Facts facts = factsFor(player, requireConnection);
            GridPos cell = world.surfaceCell(player.getBlockX(), player.getBlockZ(), player.getBlockY());
            PlayerParticipant resolved = facts.insideZone() && cell != null ? participantFor(player, cell) : null;
            // Safe action boundary: never insert a unit into a running animation, an unresolved
            // projectile or an occupied destination. The invitation stays pending so it can be refused
            // and retried instead of disturbing the current action.
            if (resolved != null) {
                // Only a resolvable candidate reaches the boundary check; an ineligible player still
                // gets the precise eligibility denial from the session below.
                BattleEligibility.Denial boundary = BattleEligibility.evaluateJoinBoundary(isBusy(), !reserved(cell));
                if (boundary != BattleEligibility.Denial.NONE) return boundary;
            }
            // Ownership is claimed before the session admits the member. A player who already belongs
            // to another encounter is refused here, not silently moved between battles.
            claim = BattleManager.claimMember(this, player.getUUID());
            if (claim != SessionIndex.Claim.NEW && claim != SessionIndex.Claim.OWNED) {
                return BattleEligibility.Denial.ALREADY_MEMBER;
            }
            pendingInvitation = session.invitation(player.getUUID(), now);
            BattleSession.JoinResult result = requireInvitation
                    ? session.accept(player.getUUID(), now, facts, resolved)
                    : session.acceptDirect(player.getUUID(), now, facts, resolved);
            if (!result.joined()) {
                // A refusal gives back only a claim this very call created; an existing member of
                // this encounter keeps its legitimate binding.
                if (claim == SessionIndex.Claim.NEW) BattleManager.releaseActor(this, player.getUUID());
                return result.denial();
            }
            member = new PlayerMember(player, result.member());
            // Commit order is deliberate. Container settlement comes first and only returns stacks to
            // the inventory, or drops them as item entities: it never deletes an item, so a failure
            // after it leaves the container conserved. Everything after it is this encounter's own
            // bookkeeping and is undone by rollbackJoin.
            settleOpenContainer(player);
            members.put(player.getUUID(), member);
            snapPlayer(member, member.state.cell());
            // Fixture-only failure point: the member exists and the player already moved.
            fault(FaultPoint.JOIN_AFTER_MEMBER);
            extendReach(member);
            queue.add(player.getUUID().toString(), TacticalAttributes.chargeRate(player, member.state.initiative()));
            // Membership is committed: publish the transition, this member's full snapshot, the complete
            // journal history and the complete warning snapshot - in that order - and then tell the
            // remaining members about the new roster.
            publishActivation(player, member.name() + " 加入战斗");
            sendState();
            // Fixture-only failure point: the join is fully published at this line.
            fault(FaultPoint.JOIN_AFTER_PUBLISH);
            return BattleEligibility.Denial.NONE;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] join failed for {}; rolling only this admission back",
                    player.getUUID(), failure);
            rollbackJoin(player, member, claim == SessionIndex.Claim.NEW, originalPosition, pendingInvitation);
            return BattleEligibility.Denial.UNRESOLVED;
        }
    }

    /**
     * Give back only this join.
     *
     * <p>Releases the roster and queue entry, the reach modifiers, this member's effect clocks, the
     * session slot, this call's own ownership claim, the consumed invitation and the player's
     * previous position. A claim this encounter already held is never released, and the container
     * settlement is not undone because it conserves items by construction.</p>
     */
    private void rollbackJoin(ServerPlayer player, PlayerMember member, boolean newlyClaimed,
                              Vec3 originalPosition, BattleSession.Invitation pendingInvitation) {
        if (member != null) {
            UUID id = member.id();
            members.remove(id);
            queue.remove(id.toString());
            try {
                restoreReach(member);
            } catch (RuntimeException ignored) {
                // A rollback must not stop halfway on a secondary failure.
            }
            BattleEffects.forget(member.entity);
            try {
                session.leave(id);
            } catch (RuntimeException ignored) {
                // The roster above is authoritative; the session slot is best-effort here.
            }
            if (newlyClaimed) BattleManager.releaseActor(this, id);
        } else if (newlyClaimed && player != null) {
            BattleManager.releaseActor(this, player.getUUID());
        }
        // The player was moved to its grid cell; put it back where the join started.
        if (player != null && !player.isRemoved() && originalPosition != null) {
            player.setPos(originalPosition.x, originalPosition.y, originalPosition.z);
            player.setDeltaMovement(Vec3.ZERO);
        }
        // A consumed invitation must survive a failed join, otherwise the player cannot retry.
        if (pendingInvitation != null) session.restoreInvitation(pendingInvitation);
        // The joining player may already have received an active snapshot for an encounter it is no
        // longer in, and the remaining members may already have seen the enlarged roster.
        if (player != null) {
            try {
                compensateExit(player);
            } catch (RuntimeException ignored) {
                // A failed correction must not block the rest of the rollback.
            }
        }
        try {
            sendState();
        } catch (RuntimeException ignored) {
            // A failed resync must not turn into a second cleanup failure.
        }
    }

    /**
     * Close and settle whatever container the player had open before it becomes authoritative.
     *
     * <p>Vanilla settles a cursor stack in {@link AbstractContainerMenu#removed}: the stack is put
     * back into the inventory (or dropped when the player is gone) and the cursor is cleared. The
     * server only reaches that path through {@code ServerPlayer#doCloseContainer}, which never runs
     * for the always-open own inventory (menu id 0). A cursor stack held over the inventory used to
     * survive the join, and an external menu was merely swapped out by {@code Player#closeContainer}
     * without anybody owning its cursor any more. Both shapes are settled here, and a real client is
     * told to close its screen first so its local copy cannot resurrect the retired menu.</p>
     */
    private void settleOpenContainer(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) return;
        if (player.connection != null) player.connection.send(new ClientboundContainerClosePacket(menu.containerId));
        if (menu != player.inventoryMenu) {
            // Vanilla close: settle the cursor, fire the close event, then swap to the inventory menu.
            player.doCloseContainer();
            return;
        }
        // The own inventory stays the active menu, so run the same settlement without swapping it,
        // then resync slots and cursor so the client matches the authoritative state.
        menu.removed(player);
        menu.broadcastChanges();
    }

    public BattleEligibility.Denial decline(ServerPlayer player) {
        if (player == null) return BattleEligibility.Denial.UNRESOLVED;
        return session.decline(player.getUUID());
    }

    /**
     * Lifecycle-only: release the member record whose live entity was replaced by a respawn.
     *
     * <p>A respawn creates a new {@code ServerPlayer} with the same UUID, so the stored instance is
     * stale and must never regain authority. This removes only that stale member - other members
     * keep the encounter - and never admits the replacement.</p>
     */
    boolean releaseReplacedMember(UUID playerId, ServerPlayer replacement, String reason) {
        PlayerMember member = members.get(playerId);
        if (member == null || member.entity == replacement) return false;
        if (member.entity.isAlive() && !member.entity.isRemoved()) return false;
        removeMember(member, reason);
        return true;
    }

    /** Explicit exit of one player; every other member keeps playing. */
    public boolean leave(ServerPlayer player, String reason) {
        if (player == null || finished) return false;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return false;
        removeMember(member, reason);
        return true;
    }

    private void removeMember(PlayerMember member, String reason) {
        UUID id = member.id();
        boolean acting = id.toString().equals(queue.current());
        boolean wasManager = session.isManager(id);
        members.remove(id);
        queue.remove(id.toString());
        releaseMemberVisuals(member, false);
        BattleSession.LeaveResult result = session.leave(id);
        BattleManager.onMemberLeft(this, id);
        // The exit is a membership transition of its own, so its generation is above the entry's.
        publishExit(member);
        if (!finished) com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity, inactiveState(member));
        journal.record("battle", member.name() + " 离开战斗 · " + reason
                + (result.managerChanged() && result.newManager() != null
                ? " · 管理权移交给 " + nameOf(result.newManager().toString()) : ""));
        if (finished) return;
        if (result.finished() || members.isEmpty()) {
            finish("all players left");
            return;
        }
        if (acting) {
            advanceTurn();
            return;
        }
        sendState();
    }

    /** Individual exit: own animation, own reach modifier, own effects only. */
    private void releaseMemberVisuals(PlayerMember member, boolean battleEnd) {
        member.state.clearMotion();
        restoreReach(member);
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity,
                new PlayerStepPacket(member.state.cell(), List.of(), stepTicks, member.entity.getYRot(), false));
        if (member.entity.isAlive() && member.entity.level() == level && !member.entity.hasDisconnected()) {
            if (battleEnd || !BattleManager.isImmune(member.entity)) snapPlayer(member, member.state.cell());
        }
        BattleEffects.forget(member.entity);
    }

    private void pruneMembers() {
        for (PlayerMember member : List.copyOf(members.values())) {
            String reason = null;
            if (!member.entity.isAlive()) reason = "player defeated";
            else if (member.entity.isRemoved()) reason = "player removed";
            else if (member.entity.connection != null && member.entity.hasDisconnected()) reason = "player disconnected";
            else if (member.entity.level() != level) reason = "player changed level";
            if (reason == null) continue;
            removeMember(member, reason);
            if (finished) return;
        }
    }

    /**
     * True when this encounter could still take {@code entity} as a reinforcement right now:
     * same level, inside the zone and the roster is not closed. Membership is not required and
     * not implied - admission stays with the explicit recruitment call.
     */
    public boolean acceptsNewcomer(Entity entity) {
        if (finished || ending || entity == null || entity.isRemoved()) return false;
        if (entity.level() != level) return false;
        return zone.contains(cellOf(entity));
    }

    public boolean isParticipant(Entity entity) {
        if (finished) return false;
        if (entity instanceof ServerPlayer p && members.containsKey(p.getUUID())) {
            PlayerMember member = members.get(p.getUUID());
            return member != null && member.entity == p;
        }
        return entity instanceof Mob mob
                && enemies.containsKey(mob.getUUID()) && enemies.get(mob.getUUID()).mob == mob;
    }

    /** Null denotes a nonparticipant; entity identity matters across respawns/replacements. */
    public BattleSide sideOf(LivingEntity actor) {
        if (!isParticipant(actor)) return null;
        return actor instanceof ServerPlayer ? BattleSide.PLAYER : enemies.get(actor.getUUID()).side;
    }

    /**
     * Unified friendly/hostile decision; every target and friendly-fire rule routes here.
     * Real UUIDs are passed so a future faction policy can resolve actual allegiance
     * instead of only this encounter's default side.
     */
    public BattleRelationPolicy.Relation relationOf(LivingEntity first, LivingEntity second) {
        if (first == null || second == null) return BattleRelationPolicy.Relation.UNRELATED;
        return relations.relationOf(refOf(first), refOf(second));
    }

    private BattleRelationPolicy.CombatantRef refOf(LivingEntity actor) {
        return new BattleRelationPolicy.CombatantRef(actor.getUUID(), sideOf(actor));
    }

    public boolean allied(LivingEntity first, LivingEntity second) {
        return relationOf(first, second) == BattleRelationPolicy.Relation.ALLY;
    }

    /**
     * Friendly-fire and target protection cover the player side only.
     *
     * <p>Two enemy-side actors are on the same side but never "protected allies": vanilla collateral
     * between them (a creeper blast catching another hostile, a hostile retargeting another hostile)
     * must keep working. The identity policy still decides, so a future faction rule can split the
     * player side without touching this call site.</p>
     */
    public boolean protectedAllies(LivingEntity first, LivingEntity second) {
        return sideOf(first) == BattleSide.PLAYER && sideOf(second) == BattleSide.PLAYER
                && relations.allied(refOf(first), refOf(second));
    }

    public boolean opposed(LivingEntity first, LivingEntity second) {
        return relationOf(first, second) == BattleRelationPolicy.Relation.OPPOSED;
    }

    public BattleRelationPolicy relationPolicy() { return relations; }

    /** Replacement point reserved for a later faction/PvP rule set; PvE remains the default. */
    public void relationPolicy(BattleRelationPolicy policy) {
        if (policy != null) this.relations = policy;
    }

    /** Immutable snapshot of living participants, join order first. */
    public List<LivingEntity> participants() {
        if (finished) return List.of();
        List<LivingEntity> result = new ArrayList<>();
        for (PlayerMember member : members.values())
            if (member.entity.isAlive() && !member.entity.isRemoved()) result.add(member.entity);
        for (EnemyState state : enemies.values())
            if (state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level) result.add(state.mob);
        return List.copyOf(result);
    }

    public List<Mob> friendlyParticipants() {
        if (finished) return List.of();
        return enemies.values().stream().filter(state -> state.side == BattleSide.PLAYER
                && state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level)
                .map(state -> state.mob).toList();
    }

    /** Safe, explicit recruitment only. Friendly slots never consume the hostile cap. */
    public boolean tryAddFriendly(Mob mob) {
        if (mob == null || finished || ending || queue.current() == null || members.isEmpty()
                || mob.level() != level || !mob.isAlive() || mob.isRemoved()
                || enemies.containsKey(mob.getUUID()) || enemies.values().stream()
                        .filter(state -> state.side == BattleSide.PLAYER).count() >= BattleParticipation.MAX_FRIENDLIES) return false;
        var safe = deploymentPosition(mob,
                pos -> occupied(new GridPos(pos.getX(), pos.getY(), pos.getZ())),
                pos -> zone.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
        if (safe.isEmpty()) return false;
        Vec3 position = safe.get();
        GridPos cell = new GridPos(net.minecraft.util.Mth.floor(position.x), net.minecraft.util.Mth.floor(position.y), net.minecraft.util.Mth.floor(position.z));
        EnemyState state = admit(mob, cell, position, BattleSide.PLAYER);
        if (state == null) return false;
        try {
            queue.add(mob.getUUID().toString(), TacticalAttributes.chargeRate(mob, state.initiative));
            journal.record("battle", "友方加入战场: " + mob.getDisplayName().getString());
            sendState();
            return true;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] friendly recruitment failed for {}; rolling it back",
                    mob.getUUID(), failure);
            queue.remove(mob.getUUID().toString());
            enemies.remove(mob.getUUID());
            restoreAdmission(state);
            try {
                sendState();
            } catch (RuntimeException ignored) {
                // A failed correction must not block the rollback that already happened.
            }
            return false;
        }
    }

    /**
     * Admit one mob into this encounter's roster as a bounded transaction.
     *
     * <p>Shared by {@link #start(boolean)}, {@link #tryAddFriendly(Mob)} and
     * {@link #tryAbsorb(Mob)}, so ownership is claimed in exactly one place and always before the
     * first mutation. A unit already owned by another encounter is refused with no side effect.
     * Any failure after the first mutation restores the unit's own AI/persistence/target/position
     * and releases only the claim this call made, so the caller keeps a clean encounter.</p>
     *
     * @return the admitted state, or null when the claim was refused or the admission rolled back.
     */
    private EnemyState admit(Mob mob, GridPos cell, Vec3 position, BattleSide side) {
        if (!BattleManager.claimActor(this, mob.getUUID())) return null;
        boolean originalNoAi = mob.isNoAi();
        boolean originalPersistenceRequired = mob.isPersistenceRequired();
        Vec3 originalPosition = mob.position();
        LivingEntity originalTarget = mob.getTarget();
        float originalFallDistance = mob.fallDistance;
        try {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mob.getNavigation().stop();
            mob.setDeltaMovement(Vec3.ZERO);
            mob.setTarget(side == BattleSide.HOSTILE ? nearestPlayer(mob) : null);
            mob.setPos(position);
            mob.fallDistance = 0;
            // Fixture-only: fail after the unit has been mutated, so the rollback is observable.
            fault(FaultPoint.ADMISSION);
            int maxAp = TacticalAttributes.maxAp(mob);
            int moveCost = ApCalculator.moveCost(attribute(mob, Attributes.MOVEMENT_SPEED));
            int initiative = ApCalculator.initiative(attribute(mob, Attributes.ATTACK_SPEED),
                    attribute(mob, Attributes.MOVEMENT_SPEED), level.random.nextInt(10));
            EnemyState state = new EnemyState(mob, cell, initialAp(maxAp), maxAp, moveCost, initiative, side,
                    originalNoAi, originalPersistenceRequired, originalPosition, originalTarget, originalFallDistance);
            enemies.put(mob.getUUID(), state);
            ai.add(mob);
            return state;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] admission failed for {}; rolling this unit back",
                    mob.getUUID(), failure);
            EnemyState partial = enemies.remove(mob.getUUID());
            if (partial != null) restoreAdmission(partial);
            else {
                ai.remove(mob);
                restoreUnit(mob, originalNoAi, originalPersistenceRequired, originalPosition, originalTarget,
                        originalFallDistance);
                BattleManager.releaseActor(this, mob.getUUID());
            }
            return null;
        }
    }

    /** Fixture-only controlled failure points; each injected point fires exactly once. */
    enum FaultPoint {
        /** Inside admission, after the unit was mutated. */
        ADMISSION,
        /** At the very end of deployment: founder, units, queue and reach are already committed. */
        START_AFTER_ADMISSION,
        /** After a join added the member and moved it to its cell. */
        JOIN_AFTER_MEMBER,
        /** After a join published the enlarged roster to every recipient. */
        JOIN_AFTER_PUBLISH,
        /** Inside the entry bootstrap, after the transition snapshot and before the rest. */
        BOOTSTRAP,
        /** After an absorb was admitted and its zone/reach/queue were committed. */
        ABSORB_AFTER_ADMIT
    }

    private static final java.util.Map<FaultPoint, Integer> FAULTS = new java.util.EnumMap<>(FaultPoint.class);

    /** Fixture-only: throw once at {@code point}, so a rollback path can be proven to run. */
    static void injectFault(FaultPoint point) {
        injectFault(point, 1);
    }

    /** Fixture-only: throw {@code times} at {@code point}, so repeated failures can be proven. */
    static void injectFault(FaultPoint point, int times) {
        if (point != null && times > 0) FAULTS.merge(point, times, Integer::sum);
    }

    /** Fixture-only: how many injected faults are still pending (0 proves they were all consumed). */
    static int pendingFaults() {
        int pending = 0;
        for (int count : FAULTS.values()) pending += count;
        return pending;
    }

    private static void fault(FaultPoint point) {
        Integer remaining = FAULTS.get(point);
        if (remaining == null || remaining <= 0) return;
        if (remaining == 1) FAULTS.remove(point); else FAULTS.put(point, remaining - 1);
        throw new IllegalStateException("injected " + point + " fault");
    }

    /**
     * Give back one admitted unit: its own AI/persistence/target/position, its roster slot, its
     * effect clocks and its ownership claim. Used for admission/start rollbacks only.
     */
    private void restoreAdmission(EnemyState state) {
        if (state == null || state.mob == null) return;
        ai.remove(state.mob);
        restoreUnit(state.mob, state.originalNoAi, state.originalPersistenceRequired,
                state.originalPosition, state.originalTarget, state.originalFallDistance);
        BattleManager.releaseActor(this, state.mob.getUUID());
    }

    /** Put one unit's own state back; touches nothing else in the world. */
    private void restoreUnit(Mob mob, boolean originalNoAi, boolean originalPersistenceRequired,
                             Vec3 originalPosition, LivingEntity originalTarget, float originalFallDistance) {
        BattleEffects.forget(mob);
        mob.getNavigation().stop();
        mob.setNoAi(originalNoAi);
        // Vanilla has no "set false"; the accessor restores the exact original flag in both
        // directions, so a rolled-back unit is not left permanently persistent.
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) mob)
                .tactical$persistenceRequired(originalPersistenceRequired);
        mob.setTarget(originalTarget);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.fallDistance = originalFallDistance;
        if (originalPosition != null) mob.setPos(originalPosition.x, originalPosition.y, originalPosition.z);
    }

    private int hostileCount() {
        return (int) enemies.values().stream().filter(state -> state.side == BattleSide.HOSTILE
                && state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level).count();
    }

    public boolean isActorTurn(LivingEntity actor) {
        return !ending && actor != null && isParticipant(actor) && actor.isAlive() && !actor.isRemoved()
                && actor.level() == level && actor.getUUID().toString().equals(queue.current());
    }

    public boolean isBusy() { return anyPlayerMoving() || enemyMoving || rangedBusy(); }

    private boolean anyPlayerMoving() {
        for (PlayerMember member : members.values()) if (member.state.moving()) return true;
        return false;
    }

    public int actorAp(LivingEntity actor) {
        if (sideOf(actor) == null) return 0;
        return actor instanceof ServerPlayer p ? memberState(p).ap() : enemies.get(actor.getUUID()).ap;
    }

    public int quoteActorActionCost(LivingEntity actor, int base, boolean ramp) {
        if (base < 0 || sideOf(actor) == null) return Integer.MAX_VALUE;
        int paid = actor instanceof ServerPlayer p ? memberState(p).paidActions() : enemies.get(actor.getUUID()).paidActions;
        return ramp ? quote(base, paid) : base;
    }

    /** Called once by a successful authoritative action; quoting has no side effects. */
    public boolean spendActorAction(LivingEntity actor, int quotedCost, boolean countPaid) {
        if (!isActorTurn(actor) || isBusy() || quotedCost < 0 || actorAp(actor) < quotedCost) return false;
        if (actor instanceof ServerPlayer p) {
            PlayerParticipant state = memberState(p);
            if (countPaid) state.commitPaidAction(quotedCost);
            else state.ap(state.ap() - quotedCost);
        } else {
            EnemyState state = enemies.get(actor.getUUID());
            state.ap -= quotedCost;
            if (countPaid) state.paidActions++;
        }
        return true;
    }

    /** Roll back one reserved action after a cancelled native effect; never refill above cap. */
    public void refundActorAction(LivingEntity actor, int amount, boolean countedPaid) {
        if (finished || sideOf(actor) == null || amount < 0) return;
        if (actor instanceof ServerPlayer p) {
            memberState(p).refund(amount, countedPaid);
        } else {
            EnemyState state = enemies.get(actor.getUUID());
            state.ap = (int) Math.min(state.maxAp, (long) state.ap + amount);
            if (countedPaid) state.paidActions = Math.max(0, state.paidActions - 1);
        }
    }

    /** Reconcile committed external effects without automatically ending the acting player's turn. */
    public void resolveExternalAction(LivingEntity actor) {
        if (finished || !isParticipant(actor)) return;
        actionCount++;
        removeDeadEnemies();
        if (!finished) sendState();
    }

    /**
     * Nearest legal opposing actor; ties break on UUID so multi-target choice is deterministic.
     * Legality is the identity-bearing relation strategy, never a hard-coded side comparison.
     */
    public LivingEntity primaryOpponent(Mob actor) {
        BattleSide side = sideOf(actor);
        if (side == null) return null;
        // Historical target pools are part of the frozen behaviour: an enemy-side actor hunts real
        // players and must never chase a friendly NPC, while an allied NPC hunts enemy-side mobs and
        // never a player. Legality inside the pool still comes from the identity-bearing policy, so a
        // replaced relation policy does apply to target selection.
        if (side == BattleSide.HOSTILE) return nearestOpposedPlayer(actor);
        return nearestOpposedEnemy(actor);
    }

    private LivingEntity nearestOpposedPlayer(Mob actor) {
        return members.values().stream()
                .map(member -> member.entity)
                .filter(p -> p.isAlive() && !p.isRemoved() && p.level() == level)
                .filter(p -> {
                    PlayerParticipant state = memberState(p);
                    return state != null && zone.contains(state.cell());
                })
                .filter(p -> opposed(actor, p))
                .min(Comparator.comparingDouble((ServerPlayer p) -> p.distanceToSqr(actor))
                        .thenComparing(p -> p.getUUID().toString()))
                .orElse(null);
    }

    private LivingEntity nearestOpposedEnemy(Mob actor) {
        return enemies.values().stream()
                .map(state -> state.mob)
                .filter(mob -> mob != actor && mob.isAlive() && !mob.isRemoved() && mob.level() == level)
                .filter(mob -> opposed(actor, mob))
                .min(Comparator.comparingDouble((Mob mob) -> mob.distanceToSqr(actor))
                        .thenComparing(mob -> mob.getUUID().toString()))
                .orElse(null);
    }

    /** Closest living member inside the zone; equal distances resolve by UUID, never by iteration order. */
    private ServerPlayer nearestPlayer(Mob actor) {
        return members.values().stream()
                .map(member -> member.entity)
                .filter(p -> p.isAlive() && !p.isRemoved() && p.level() == level)
                .filter(p -> {
                    PlayerParticipant state = memberState(p);
                    return state != null && zone.contains(state.cell());
                })
                .min(Comparator.comparingDouble((ServerPlayer p) -> p.distanceToSqr(actor))
                        .thenComparing(p -> p.getUUID().toString()))
                .orElse(null);
    }

    /** UUID of the actor the turn track currently points at; null when the queue is empty. */
    public UUID currentActorId() {
        String current = queue.current();
        return current == null ? null : UUID.fromString(current);
    }

    /** Authoritative tactical feet cell; null for entities outside this encounter. */
    public GridPos currentActorCell(LivingEntity actor) {
        return sideOf(actor) == null ? null : participantCell(actor);
    }

    /** Face a validated native action and synchronize that actor's rendered frame. */
    public void faceActorTowards(LivingEntity caster, GridPos target) {
        if (target == null || !isParticipant(caster)) return;
        faceDirection(caster, target);
        if (caster instanceof ServerPlayer p) sendPlayerMotion(member(p), List.of());
    }

    private GridPos participantCell(LivingEntity actor) {
        if (actor instanceof ServerPlayer p) {
            PlayerParticipant state = memberState(p);
            return state == null ? null : state.cell();
        }
        EnemyState state = actor == null ? null : enemies.get(actor.getUUID());
        return state != null && state.mob == actor ? state.cell : null;
    }

    private PlayerParticipant memberState(ServerPlayer player) {
        PlayerMember member = player == null ? null : members.get(player.getUUID());
        return member != null && member.entity == player ? member.state : null;
    }

    private PlayerMember member(ServerPlayer player) {
        PlayerMember member = player == null ? null : members.get(player.getUUID());
        return member != null && member.entity == player ? member : null;
    }

    /**
     * Pull an outside attacker into the battle: expand the zone to cover it and
     * add it to the turn queue. Only hostile mobs can be absorbed.
     */
    public boolean tryAbsorb(Mob mob) {
        if (mob == null || finished || ending || members.isEmpty() || !hasUnprotectedMember()
                || mob.level()!=level
                || !mob.isAlive() || mob.isRemoved() || hostileCount()>=participantCap
                || enemies.containsKey(mob.getUUID()) || !isHostile(mob)) {
            return false;
        }
        var safe=deploymentPosition(mob,
                pos->occupied(new GridPos(pos.getX(),pos.getY(),pos.getZ())),pos->true);
        if(safe.isEmpty()) return false;
        Vec3 position=safe.get();
        GridPos cell=new GridPos(net.minecraft.util.Mth.floor(position.x),net.minecraft.util.Mth.floor(position.y),net.minecraft.util.Mth.floor(position.z));
        // Growing this battlefield must never swallow another live encounter's space. The check
        // runs on the would-be zone, before anything changes.
        BattleZone expanded = zone.including(cell);
        if (BattleManager.zoneConflicts(this, expanded)) return false;

        // Admission is the only step that can still refuse (a unit already owned elsewhere, or a
        // rolled-back admission). It runs before the zone, reach and queue change, so a refusal
        // leaves this encounter exactly as it was.
        EnemyState state = admit(mob, cell, position, BattleSide.HOSTILE);
        if (state == null) return false;
        // Everything after the admission is committed together, so a failure here gives back the
        // grown battlefield, the extended reach, the queue entry and the admitted unit itself.
        BattleZone previousZone = zone;
        int previousRadius = zoneRadius;
        try {
            zone = expanded;
            zoneRadius = zone.radius();
            extendReachAll();
            queue.add(mob.getUUID().toString(), TacticalAttributes.chargeRate(mob, state.initiative));
            message("新的敌人加入战场: " + mob.getDisplayName().getString());
            sendState();
            // Fixture-only failure point: the absorb is fully committed at this line.
            fault(FaultPoint.ABSORB_AFTER_ADMIT);
            return true;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] absorb failed for {}; rolling this absorb back",
                    mob.getUUID(), failure);
            queue.remove(mob.getUUID().toString());
            enemies.remove(mob.getUUID());
            zone = previousZone;
            zoneRadius = previousRadius;
            try {
                extendReachAll();
            } catch (RuntimeException ignored) {
                // A rollback must not stop halfway on a secondary failure.
            }
            restoreAdmission(state);
            try {
                journal.record("battle", "新的敌人未能加入战场，已回滚");
            } catch (RuntimeException ignored) {
                // Journal delivery is best-effort here.
            }
            // The clients may already have seen the grown battlefield and the extra unit; the
            // corrective snapshot is the last thing they are told, after the state is authoritative.
            try {
                sendState();
            } catch (RuntimeException ignored) {
                // A failed correction must not block the rollback that already happened.
            }
            return false;
        }
    }

    /** Flee grace: while every living member is protected no new hostile may be absorbed. */
    private boolean hasUnprotectedMember() {
        for (PlayerMember member : members.values()) {
            if (member.entity.isAlive() && !BattleManager.isImmune(member.entity)) return true;
        }
        return false;
    }

    /** Vanilla pick range is ~4.5 blocks; the grid needs to be clickable much further out. */
    private void extendReachAll() {
        for (PlayerMember member : members.values()) extendReach(member);
    }

    private void extendReach(PlayerMember member) {
        extend(member.entity, Attributes.BLOCK_INTERACTION_RANGE, zoneRadius + 16.0D);
        extend(member.entity, Attributes.ENTITY_INTERACTION_RANGE, zoneRadius + 8.0D);
    }

    private void extend(ServerPlayer player,
                        net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                        double amount) {
        var instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(REACH_MODIFIER);
        instance.addTransientModifier(new AttributeModifier(
                REACH_MODIFIER, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    private void restoreReach(PlayerMember member) {
        for (var attribute : List.of(Attributes.BLOCK_INTERACTION_RANGE, Attributes.ENTITY_INTERACTION_RANGE)) {
            var instance = member.entity.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(REACH_MODIFIER);
            }
        }
    }

    // ------------------------------------------------------------------
    // stamped player intents
    // ------------------------------------------------------------------

    /**
     * Resolve the real connection identity and the frozen session/turn stamp.
     *
     * <p>Nothing in a payload can name an actor: the {@code ServerPlayer} comes from the
     * connection context, must be this exact member instance, and its request must belong
     * to the live session and the live turn generation. Rejections mutate no state.</p>
     */
    private PlayerMember authorize(ServerPlayer actor, ActionStamp stamp) {
        if (finished || ending || actor == null || stamp == null) return null;
        if (!session.accepts(stamp)) return null;
        PlayerMember member = members.get(actor.getUUID());
        if (member == null || member.entity != actor) return null;
        if (!actor.isAlive() || actor.isRemoved() || actor.level() != level) return null;
        return member;
    }

    private boolean canAct(PlayerMember member) {
        return !finished && phase == BattlePhase.PLAYER_TURN && !member.state.moving()
                && isActorTurn(member.entity) && !isBusy();
    }

    private PlayerMember authorizeActing(ServerPlayer actor, ActionStamp stamp) {
        PlayerMember member = authorize(actor, stamp);
        return member != null && canAct(member) ? member : null;
    }

    public boolean tryMove(ServerPlayer actor, ActionStamp stamp, GridPos target) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return false;
        return tryMove(member, target);
    }

    private boolean tryMove(PlayerMember member, GridPos target) {
        PlayerParticipant state = member.state;
        if (!zone.contains(target) || occupied(target) || !LevelGridWorld.isStandable(level,target.x(),target.y(),target.z())) {
            reject("落点不可站立、被占用或超出战斗区域"); return false;
        }
        MovementRoutes.Options options = movementOptions(member, target);
        if (options.choice()) {
            PlayerParticipant.PendingMove offer = new PlayerParticipant.PendingMove(UUID.randomUUID(), state.cell(),
                    target, level.getGameTime()+1200, options.walk().size()*state.moveCost());
            state.pendingMove(offer);
            journal.record("move", "选择移动方式：绕路 " + offer.walkCost() + " AP / 直接跳下 " + state.moveCost() + " AP");
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity,
                    new MoveChoiceOfferPacket(offer.token(),target,
                        offer.walkCost(),state.moveCost(),state.cell().y()-target.y()));
            return true;
        }
        if (!options.walk().isEmpty()) return startPlayerMove(member, options.walk(),false);
        if (!options.drop().isEmpty()) return startPlayerMove(member, options.drop(),true);
        reject("没有可通行且 AP 足够的路线");
        return false;
    }

    private MovementRoutes.Options movementOptions(PlayerMember member, GridPos target) {
        PlayerParticipant state = member.state;
        int budget=state.moveCost()<=0?0:state.ap()/state.moveCost();
        return MovementRoutes.plan(navigator,state.cell(),target,this::passable,
                (from,to)->DropClearance.canDrop(level,member.entity,from,to,
                        intConfig(TacticalConfig.INSTANCE.maxDropHeight,8),zone::contains),budget,
                (from,to)->DropClearance.canStep(level,member.entity,from,to,zone::contains));
    }

    /** Fixture entry point: only the acting member of this encounter can move. */
    public boolean tryMove(GridPos target) {
        PlayerMember acting = actingMember();
        return acting != null && canAct(acting) && tryMove(acting, target);
    }

    public MovementRoutes.Options movementOptions(GridPos target) {
        PlayerMember acting = actingMember();
        return acting == null ? null : movementOptions(acting, target);
    }

    public void chooseMove(ServerPlayer actor, ActionStamp stamp, UUID token, int mode) {
        PlayerMember member = authorize(actor, stamp);
        if (member == null) return;
        PlayerParticipant state = member.state;
        PlayerParticipant.PendingMove offer = state.pendingMove();
        if (offer==null || !offer.token().equals(token)) { reject("移动选项已失效"); return; }
        state.pendingMove(null);
        if(mode==-1) { journal.record("move","取消移动"); return; }
        if(!canAct(member) || !offer.from().equals(state.cell()) || level.getGameTime()>offer.expires()
                || (mode!=0 && mode!=1)) { reject("回合或位置已变化，请重新选择落点"); return; }
        // Terrain, occupied cells, AP and zone may all change while the choice is visible.
        MovementRoutes.Options current=movementOptions(member,offer.target());
        List<GridPos> path=mode==1?current.drop():current.walk();
        if(path.isEmpty()) { reject("所选路线已不可用，请重新选择"); return; }
        if(mode==0 && path.size()*state.moveCost()!=offer.walkCost()) { reject("地形改变了路线费用，请重新选择落点"); return; }
        startPlayerMove(member,path,mode==1);
    }

    /** Legacy single-member entry point for internal fixtures; network paths must use the stamped form. */
    public void chooseMove(UUID token,int mode) {
        PlayerMember acting = actingMember();
        if (acting == null) return;
        PlayerParticipant.PendingMove offer = acting.state.pendingMove();
        if (offer == null || !offer.token().equals(token)) { reject("移动选项已失效"); return; }
        acting.state.pendingMove(null);
        if (mode == -1) { journal.record("move", "取消移动"); return; }
        if (!canAct(acting) || !offer.from().equals(acting.state.cell()) || level.getGameTime() > offer.expires()
                || (mode != 0 && mode != 1)) { reject("回合或位置已变化，请重新选择落点"); return; }
        MovementRoutes.Options current = movementOptions(acting, offer.target());
        List<GridPos> path = mode == 1 ? current.drop() : current.walk();
        if (path.isEmpty()) { reject("所选路线已不可用，请重新选择"); return; }
        if (mode == 0 && path.size() * acting.state.moveCost() != offer.walkCost()) {
            reject("地形改变了路线费用，请重新选择落点"); return;
        }
        startPlayerMove(acting, path, mode == 1);
    }

    private boolean startPlayerMove(PlayerMember member, List<GridPos> path, boolean drop) {
        PlayerParticipant state = member.state;
        int unit = state.moveCost();
        int cost = path.size() * unit;
        if(path.isEmpty()||cost>state.ap()) { reject("AP 不足"); return false; }
        state.pendingMove(null);
        int dropTicks = Math.max(8,(state.cell().y()-path.get(0).y())*3);
        state.ap(state.ap()-cost);
        state.beginMove(path, drop, stepTicks, dropTicks);
        actionCount++;
        journal.record("move",member.name()+ (drop?" 直接跳下 ":" 沿路线移动 ")
                +state.cell()+" → "+path.get(path.size()-1)+"（"+cost+" AP）");
        sendState(); sendPlayerMotion(member, path);
        return true;
    }

    public void tryAttack(ServerPlayer actor, ActionStamp stamp, UUID targetId) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        tryAttack(member, targetId);
    }

    private void tryAttack(PlayerMember member, UUID targetId) {
        if (finished || member.state.moving() || rangedBusy()) return;
        EnemyState enemy = enemies.get(targetId);
        if (enemy == null || !enemy.mob.isAlive() || !opposed(member.entity, enemy.mob)) {
            return;
        }
        int kind = weaponKind(member.entity);
        if (kind == 0) {
            tryMeleeAttack(member, enemy);
        } else {
            tryRangedAttack(member, enemy, kind);
        }
    }

    /** Legacy single-member entry point for internal fixtures. */
    public void tryAttack(UUID targetId) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        tryAttack(acting, targetId);
    }

    private void tryMeleeAttack(PlayerMember member, EnemyState enemy) {
        PlayerParticipant state = member.state;
        if (!inMeleeRange(state.cell(), enemy.cell)) {
            reject("目标不在近战范围内");
            return;
        }
        int cost = currentMeleeCost(member);
        if (state.ap() < cost) {
            reject("AP 不足");
            return;
        }
        if (!hasLineOfSight(state.cell(), enemy.cell, member.entity)) { reject("目标被障碍物阻挡"); return; }
        faceDirection(member.entity, enemy.cell);
        sendPlayerMotion(member, List.of());
        journal.record("attack",member.name()+" 近战攻击 "+enemy.mob.getDisplayName().getString());
        member.entity.swing(InteractionHand.MAIN_HAND, true);
        if (!NativeAttackReceipt.run(member.entity, enemy.mob, () -> member.entity.attack(enemy.mob))) {
            reject("近战攻击未生效"); return;
        }
        state.commitPaidAction(cost);
        actionCount++;
        removeDeadEnemies();
        if (!finished) sendState();
    }

    private void tryRangedAttack(PlayerMember member, EnemyState enemy, int kind) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int range = weaponRange(state.cell(), enemy.cell, kind);
        double distance = Math.hypot(state.cell().x() - enemy.cell.x(), state.cell().z() - enemy.cell.z());
        if (distance > range + 0.001D) {
            reject("超出射程 (" + String.format("%.0f", distance) + "/" + range + ")");
            return;
        }
        // Facing never gates a ranged intent. Collision is checked along its ballistic arc below.
        int cost = switch (kind) { case 2 -> currentCrossbowCost(member); default -> currentBowCost(member); };
        if (state.ap() < cost) {
            reject("AP 不足");
            return;
        }
        ItemStack weapon = player.getMainHandItem();
        boolean loaded = kind == 2 && CrossbowItem.isCharged(weapon);
        if (!loaded && player.getProjectile(weapon).isEmpty()) {
            reject("没有箭");
            return;
        }
        faceDirection(player, enemy.cell);
        ChargedProjectiles charged=weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.EMPTY);
        boolean rocket=kind==2 && (loaded?charged.contains(Items.FIREWORK_ROCKET):player.getProjectile(weapon).is(Items.FIREWORK_ROCKET));
        float speed=kind==2?(rocket?1.6F:3.15F):3.0F;
        int pierce=kind==2?net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                        .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING),weapon):0;
        BallisticShot.Plan plan=BallisticShot.plan(level,player,state.cell(),enemy.mob,speed,!rocket,pierce);
        if(plan==null) {
            sendPlayerMotion(member, List.of());
            reject("转向后仍无法射中：弹道被方块或生物阻挡，或目标无法到达");
            sendState();return;
        }
        int count = projectiles.capture(player,plan,() -> fireRanged(member, enemy, weapon, kind,plan));
        if (count <= 0) {
            reject("射击未发出");
            return;
        }
        journal.record("attack",member.name()+" 使用"+(kind==2?"弩":"弓")+"射击 "+enemy.mob.getDisplayName().getString());
        state.commitPaidAction(cost);
        awaitingShotResolution = true;
        shotRecoveryTicks = -1;
        player.swing(InteractionHand.MAIN_HAND, true);
        sendPlayerMotion(member, List.of());
        actionCount++;
        sendState();
    }

    private void fireRanged(PlayerMember member, EnemyState enemy, ItemStack weapon, int kind, BallisticShot.Plan plan) {
        ServerPlayer player = member.entity;
        player.setYRot(plan.yaw());
        player.setYHeadRot(plan.yaw());
        player.setYBodyRot(plan.yaw());
        player.setXRot(plan.pitch());
        player.xRotO=plan.pitch();
        if (kind == 2 && weapon.getItem() instanceof CrossbowItem crossbow) {
            // AP abstracts charge time, not ammo: vanilla draw handles Multishot,
            // consumption and existing charged stacks (including the last arrow).
            if (!CrossbowItem.isCharged(weapon)) crossbow.releaseUsing(weapon, level, player, 0);
            ChargedProjectiles charged = weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            float speed = charged.contains(Items.FIREWORK_ROCKET) ? 1.6F : 3.15F;
            crossbow.performShooting(level, player, InteractionHand.MAIN_HAND, weapon, speed, 1.0F, null);
        } else if (weapon.getItem() instanceof BowItem bow) {
            // Invoke release directly: startUsingItem is intentionally prohibited
            // by BattleItemLock. Vanilla still owns loose hooks, Infinity, Power,
            // Flame, Punch, tipped/spectral arrows, durability and stats.
            bow.releaseUsing(weapon, level, player, bow.getUseDuration(weapon, player) - BowItem.MAX_DRAW_DURATION);
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** Raise a guard until the next own turn and end that player's turn. */
    public void tryDefend(ServerPlayer actor, ActionStamp stamp) {
        tryDefendFacing(actor, stamp, Math.round(actor == null ? 0F : actor.getYRot()));
    }

    public void tryDefendFacing(ServerPlayer actor, ActionStamp stamp, int yaw) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        tryDefendFacing(member, yaw);
    }

    private void tryDefendFacing(PlayerMember member, int yaw) {
        PlayerParticipant state = member.state;
        if (state.ap()<25){reject("AP不足，防御需要25 AP");sendState();return;}
        if(yaw!=0&&yaw!=90&&yaw!=180&&yaw!=270){reject("无效防御朝向");return;}
        ServerPlayer player = member.entity;
        player.setYRot(yaw);player.setYHeadRot(yaw);player.setYBodyRot(yaw);
        player.yRotO=player.yHeadRotO=player.yBodyRotO=yaw;
        state.ap(state.ap()-25);state.defending(true);actionCount++;sendPlayerMotion(member, List.of());
        journal.record("block",(hasShield(member)?"举盾防御":"进入防御姿态")+" · 消耗25 AP · 朝向"+yaw);
        advanceTurn();
    }

    /** Fixture entry point: raises guard for whoever is acting now. */
    public void tryDefend() {
        PlayerMember acting = actingMember();
        if (acting != null && canAct(acting)) tryDefendFacing(acting, Math.round(acting.entity.getYRot()));
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void tryDefendFacing(int yaw) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        tryDefendFacing(acting, yaw);
    }

    public boolean isDefending() {
        PlayerMember acting = actingMember();
        return acting != null && acting.state.defending();
    }

    public boolean isDefending(ServerPlayer player) {
        PlayerParticipant state = memberState(player);
        return state != null && state.defending();
    }

    public boolean isPlayerTurnNow() {
        PlayerMember acting = actingMember();
        return acting != null && !finished && phase == BattlePhase.PLAYER_TURN
                && !acting.state.moving() && !rangedBusy();
    }

    public boolean isPlayerTurnNow(ServerPlayer player) {
        PlayerMember member = member(player);
        return member != null && !finished && phase == BattlePhase.PLAYER_TURN
                && !member.state.moving() && !rangedBusy() && isActorTurn(player);
    }

    public void swapSlots(ServerPlayer actor, ActionStamp stamp, int first, int second) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) swapSlots(member, first, second);
    }

    private void swapSlots(PlayerMember member, int first, int second) {
        var inventory = member.entity.getInventory();
        if (first < 0 || first >= 36 || second < 0 || second >= 36 || first == second) {
            return;
        }
        var stack = inventory.getItem(first);
        inventory.setItem(first, inventory.getItem(second));
        inventory.setItem(second, stack);
        member.entity.inventoryMenu.broadcastChanges();
    }

    public void dropSlot(ServerPlayer actor, ActionStamp stamp, int slot) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        dropSlot(member, slot);
    }

    private void dropSlot(PlayerMember member, int slot) {
        var inventory = member.entity.getInventory();
        var stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        member.entity.drop(stack.copy(), false);
        inventory.setItem(slot, ItemStack.EMPTY);
        member.entity.inventoryMenu.broadcastChanges();
    }

    public void swapHand(ServerPlayer actor, ActionStamp stamp, int slot, boolean offhand) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        swapHand(member, slot, offhand);
    }

    private void swapHand(PlayerMember member, int slot, boolean offhand) {
        var inventory = member.entity.getInventory();
        if (slot < 0 || slot >= 36) {
            return;
        }
        if (offhand) {
            var stack = inventory.getItem(slot);
            inventory.setItem(slot, inventory.offhand.get(0));
            inventory.offhand.set(0, stack);
        } else {
            int selected = inventory.selected;
            var stack = inventory.getItem(slot);
            inventory.setItem(slot, inventory.getItem(selected));
            inventory.setItem(selected, stack);
        }
        member.entity.inventoryMenu.broadcastChanges();
    }

    public boolean hasShield() {
        PlayerMember acting = actingMember();
        return acting != null && hasShield(acting);
    }

    /** True when this exact member holds a shield in its off hand. */
    public boolean hasShield(LivingEntity actor) {
        PlayerMember member = actor instanceof ServerPlayer p ? member(p) : null;
        return member != null && hasShield(member);
    }

    private boolean hasShield(PlayerMember member) {
        return member.entity.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
    }

    /** Place one block from the chosen inventory slot at the clicked position. */
    public void placeBlock(ServerPlayer actor, ActionStamp stamp, int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) placeBlock(member, slot, pos, face, hitLocation);
    }

    private void placeBlock(PlayerMember member, int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentPlaceCost(member);
        if (state.ap() < cost) {
            message("AP 不足");
            return;
        }
        if(slot<0||slot>8||slot!=player.getInventory().selected) { reject("主手物品已改变，请重新选择道具");return; }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            message("该物品不是方块");
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 256.0D) {
            message("太远了");
            return;
        }
        if(!zone.contains(new GridPos(pos.getX(),pos.getY(),pos.getZ()))) {reject("放置位置超出战斗区域");return;}
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(hitLocation, face, pos, false));
        if (BattleToolUse.run(player,()->blockItem.place(context)).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, true);
            state.commitPaidAction(cost);
            actionCount++;
            level.playSound(null, pos, blockItem.getBlock().defaultBlockState().getSoundType().getPlaceSound(),
                    SoundSource.PLAYERS, 1.0F, 1.0F);
            sendState();
        } else {
            message("这里不能放置");
        }
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void placeBlock(int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        placeBlock(acting, slot, pos, face, hitLocation);
    }

    /** Use one inventory item (food, potions, or the item's own use action). */
    public void useItem(ServerPlayer actor, ActionStamp stamp, int slot) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) useItem(member, slot);
    }

    private void useItem(PlayerMember member, int slot) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentItemCost(member);
        if (state.ap() < cost) {
            message("AP 不足");
            return;
        }
        if(slot<0||slot>8||slot!=player.getInventory().selected) {reject("主手物品已改变，请重新选择道具");return;}
        ItemStack stack=player.getMainHandItem();
        if(stack.isEmpty()) {reject("主手没有可用道具");return;}
        if(stack.getItem() instanceof BowItem||stack.getItem() instanceof CrossbowItem||stack.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            reject("武器请使用攻击，盾牌请使用防御");return;
        }
        var result=BattleToolUse.run(player,()->stack.use(level,player,InteractionHand.MAIN_HAND));
        if(!result.getResult().consumesAction()) {reject("当前主手道具不能使用");return;}
        ItemStack next=result.getObject();
        if (stack.getUseDuration(player) > 0) {
            // A cancelled native Start event can still leave Item.use returning CONSUME.
            // It has not committed an item use and must not consume AP or escalation.
            if (!player.isUsingItem() || player.getUseItem() != stack) {
                reject("道具使用被取消"); return;
            }
            ItemStack before = stack.copy();
            int remaining = player.getUseItemRemainingTicks();
            next=BattleToolUse.run(player,()->net.neoforged.neoforge.event.EventHooks.onItemUseFinish(
                    player, before, remaining, stack.finishUsingItem(level,player)));
            player.stopUsingItem();
        }
        player.getInventory().setItem(slot,next);
        player.inventoryMenu.broadcastChanges();
        player.swing(InteractionHand.MAIN_HAND,true);
        state.commitPaidAction(cost);actionCount++;
        journal.record("inventory",member.name()+" 使用主手道具（"+cost+" AP）");
        sendState();
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void useItem(int slot) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        useItem(acting, slot);
    }

    public void endPlayerTurn(ServerPlayer actor, ActionStamp stamp) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) advanceTurn();
    }

    /** Face the chosen direction before handing the turn over. */
    public void endPlayerTurnFacing(ServerPlayer actor, ActionStamp stamp, int yaw) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        endPlayerTurnFacing(member, yaw);
    }

    private void endPlayerTurnFacing(PlayerMember member, int yaw) {
        ServerPlayer player = member.entity;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        player.yRotO = player.yHeadRotO = player.yBodyRotO = yaw;
        sendPlayerMotion(member, List.of());
        advanceTurn();
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void endPlayerTurn() {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        advanceTurn();
    }

    /** Spend AP for a flee attempt; success removes only this player from the encounter. */
    public boolean tryFlee(ServerPlayer actor, ActionStamp stamp) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return false;
        return tryFlee(member);
    }

    private boolean tryFlee(PlayerMember member) {
        PlayerParticipant state = member.state;
        if (state.ap() < BattleRules.FLEE_COST) {
            reject("AP 不足，无法逃跑");
            return false;
        }
        state.ap(state.ap()-BattleRules.FLEE_COST);
        double speed = attribute(member.entity, Attributes.MOVEMENT_SPEED);
        double chance = BattleRules.FLEE_BASE_CHANCE
                + (speed - 0.1D) * BattleRules.FLEE_SPEED_BONUS
                - hostileCount() * BattleRules.FLEE_PER_ENEMY_PENALTY;
        chance = net.minecraft.util.Mth.clamp(chance, 0.1D, 0.9D);
        if (level.random.nextDouble() < chance) {
            BattleManager.grantFleeImmunity(member.entity);
            removeMember(member, "fled");
            return true;
        }
        state.ap(0);
        message("逃跑失败（成功率 " + Math.round(chance * 100.0D) + "%）");
        advanceTurn();
        return false;
    }

    /** Legacy fixture entry point bound to the acting player. */
    public boolean tryFlee() {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return false;
        return tryFlee(acting);
    }

    // ------------------------------------------------------------------
    // movement animation
    // ------------------------------------------------------------------

    private void stepPlayer(PlayerMember member) {
        PlayerParticipant state = member.state;
        ServerPlayer player = member.entity;
        if (!state.stepping()) {
            if (state.pathIndex() >= state.path().size()) {
                state.moving(false);
                state.beginMove(List.of(), false, stepTicks, stepTicks);
                snapPlayer(member, state.cell());
                sendPlayerMotion(member, List.of());
                sendState();
                return;
            }
            List<GridPos> path = state.path();
            state.stepTarget(path.get(state.pathIndex()));
            state.pathIndex(state.pathIndex() + 1);
            faceDirection(player, state.stepTarget());
            state.stepProgress(0);
            state.stepping(true);
        }
        state.stepProgress(state.stepProgress() + 1);
        int motion = Math.max(1, state.motionTicks());
        double progress = Math.min(1.0D, state.stepProgress() / (double) motion);
        var point=state.dropping()?DropTrajectory.sample(state.cell(),state.stepTarget(),progress):
                com.tactical.core.grid.GridTrajectory.sampleStep(state.cell(),state.stepTarget(),progress);
        Vec3 position=new Vec3(point.x(),point.y(),point.z());
        double previousProgress=Math.max(0,(state.stepProgress()-1)/(double)motion);
        if(!DropClearance.clearTrajectorySegment(level,player,state.cell(),state.stepTarget(),previousProgress,progress,state.dropping())) {
            int remaining = state.path().size() - state.pathIndex() + 1;
            state.ap(state.ap() + remaining * state.moveCost());
            state.moving(false);
            state.beginMove(List.of(), false, stepTicks, stepTicks);
            if(DropClearance.clearSegment(level,player,centerOf(state.cell()),centerOf(state.cell()))) snapPlayer(member, state.cell());
            else { finish("移动路径被动态障碍阻挡"); return; }
            journal.record("reject","移动途中出现障碍，已停止并退还未完成步数的 AP");
            sendPlayerMotion(member, List.of());sendState();return;
        }
        setPlayerPosition(member, position);
        if (progress >= 1.0D) {
            int fallen=state.dropping()?state.cell().y()-state.stepTarget().y():0;
            state.cell(state.stepTarget());
            if(fallen>3) player.causeFallDamage(fallen,1.0F,player.damageSources().fall());
            state.stepping(false);
            sendState();
        }
    }

    private java.util.Optional<Vec3> deploymentPosition(Mob mob,java.util.function.Predicate<BlockPos> occupied,java.util.function.Predicate<BlockPos> allowed) {
        return AerialMovement.supports(mob)?AerialMovement.findNearestClearAir(level,mob,occupied,allowed):BattleDeployment.findNearestStandable(level,mob,occupied,allowed);
    }

    private void stepEnemy() {
        EnemyState enemy = enemies.get(UUID.fromString(queue.current()));
        if (enemy == null) {
            enemyMoving = false;
            advanceTurn();
            return;
        }
        if (!enemyStepping) {
            if (enemyPathIndex >= enemyPath.size()) {
                enemyMoving = false;
                enemyPath = List.of();
                if(enemy.flying){enemy.mob.setPos(AerialMovement.feet(enemy.cell,enemy.airOffset));enemy.mob.setDeltaMovement(Vec3.ZERO);}
                if(enemyDiveAttackIndex>=0){enemyDiveAttackIndex=-1;advanceTurn();}
                else if (primaryOpponent(enemy.mob) == null) advanceTurn();
                else resolveAiAction(enemy,ai.afterMovement(aiContext(enemy)));
                return;
            }
            enemyStepTarget = enemyPath.get(enemyPathIndex++);
            enemyStepFrom = enemy.mob.position();
            faceDirection(enemy.mob, enemyStepTarget);
            enemyStepProgress = 0;
            enemyStepping = true;
        }
        enemyStepProgress++;
        double progress = Math.min(1.0D, enemyStepProgress / (double) Math.max(1, stepTicks));
        Vec3 target=enemy.flying?AerialMovement.feet(enemyStepTarget,enemy.airOffset):centerOf(enemyStepTarget);
        Vec3 next=enemyStepFrom.lerp(target,progress);
        if(enemy.flying&&!AerialMovement.isSegmentClear(level,enemy.mob,enemy.mob.position(),next)) {
            int unused=enemyPath.size()-enemyPathIndex+1;
            enemy.ap=Math.min(enemy.maxAp,enemy.ap+unused*enemy.moveCost+enemyDiveReservedCost);
            enemyDiveReservedCost=0;
            enemyMoving=false;enemyStepping=false;enemyPath=List.of();enemyDiveAttackIndex=-1;
            journal.record("enemy",enemy.mob.getDisplayName().getString()+" 的飞行路径出现障碍，停止并退还未执行费用");
            advanceTurn();return;
        }
        enemy.mob.setPos(next);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        if (progress >= 1.0D) {
            enemy.cell = enemyStepTarget;
            enemy.mob.setPos(target);
            enemyStepping = false;
            if(enemyDiveAttackIndex==enemyPathIndex-1&&!enemyDiveAttacked) {
                enemyDiveAttacked=true;
                int reserved = enemyDiveReservedCost;
                enemyDiveReservedCost = 0;
                AiContext strikeContext = aiContext(enemy);
                if(strikeContext.target() != null && strikeContext.inMeleeRange(enemy.cell,strikeContext.targetCell())
                        && strikeContext.hasLineOfSight(enemy.cell,strikeContext.targetCell())) {
                    if (performEnemyMelee(enemy)) enemy.paidActions++;
                    else enemy.ap=Math.min(enemy.maxAp,enemy.ap+reserved);
                } else {
                    enemy.ap=Math.min(enemy.maxAp,enemy.ap+reserved);
                    journal.record("enemy","目标已移动，俯冲攻击未命中");
                }
                sendState();
            }
        }
    }

    private static void faceDirection(LivingEntity mob, GridPos target) {
        double dx = target.x() + 0.5D - mob.getX();
        double dz = target.z() + 0.5D - mob.getZ();
        if (dx * dx + dz * dz < 1.0E-4D) {
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
        mob.yRotO = mob.yHeadRotO = mob.yBodyRotO = yaw;
    }

    private boolean performEnemyMelee(EnemyState enemy) {
        LivingEntity target = primaryOpponent(enemy.mob);
        if (target == null || !opposed(enemy.mob, target)
                || enemy.mob.getAttribute(Attributes.ATTACK_DAMAGE)==null) return false;
        faceDirection(enemy.mob, participantCell(target));
        journal.record("attack", enemy.mob.getDisplayName().getString() + " 攻击 " + target.getDisplayName().getString());
        enemy.mob.swing(InteractionHand.MAIN_HAND);
        enemy.mob.setTarget(target);
        return enemy.mob.doHurtTarget(target);
    }

    /** Legacy name: attacks this actor's primary opponent, never its allied owner. */
    private void attackPlayer(EnemyState enemy) {
        int cost = enemyMeleeCost(enemy);
        AiContext context = aiContext(enemy);
        if (context.target() == null || enemy.ap < cost || !context.inMeleeRange(enemy.cell, context.targetCell())
                || !context.hasLineOfSight(enemy.cell, context.targetCell())) return;
        if (performEnemyMelee(enemy)) { enemy.ap -= cost; enemy.paidActions++; }
    }

    private void shootPlayer(EnemyState enemy) {
        int cost = enemyBowCost(enemy);
        LivingEntity target = primaryOpponent(enemy.mob);
        if (!(enemy.mob instanceof net.minecraft.world.entity.monster.RangedAttackMob ranged)
                || target == null || !opposed(enemy.mob, target) || enemy.ap < cost
                || !(enemy.mob.getMainHandItem().getItem() instanceof BowItem)) return;
        BallisticShot.Plan shot = BallisticShot.plan(level, enemy.mob, enemy.cell, target, 1.6F, true, 0);
        if (shot == null) { journal.record("enemy", enemy.mob.getDisplayName().getString() + " 无法射中，寻找新的射击位置"); return; }
        enemy.mob.setYRot(shot.yaw()); enemy.mob.setYHeadRot(shot.yaw()); enemy.mob.setYBodyRot(shot.yaw()); enemy.mob.setXRot(shot.pitch());
        enemy.mob.setTarget(target);
        int fired = projectiles.capture(enemy.mob, shot, () -> ranged.performRangedAttack(target, 1.0F));
        if (fired <= 0) return;
        enemy.ap -= cost;
        enemy.paidActions++;
        pendingEnemyShot = enemy.mob.getUUID();
        awaitingShotResolution = true;
        shotRecoveryTicks = -1;
        journal.record("attack", enemy.mob.getDisplayName().getString() + " 拉弓射击 " + target.getDisplayName().getString());
        sendState();
    }

    private AiContext aiContext(EnemyState state) {
        LivingEntity opponent = primaryOpponent(state.mob);
        GridPos opponentCell = participantCell(opponent);
        // No-op planning is guarded by planEnemyTurn/afterMovement; retain non-null cells for old adapters.
        GridPos selectedCell = opponentCell == null ? state.cell : opponentCell;
        return new AiContext() {
            public Mob actor() { return state.mob; }
            public GridPos actorCell() { return state.cell; }
            public LivingEntity target() { return opponent; }
            public GridPos targetCell() { return selectedCell; }
            public GridPos playerCell() { return targetCell(); }
            public int ap() { return state.ap; }
            public int moveCost() { return state.moveCost; }
            public boolean flying() { return state.flying; }
            public List<SkillAvailability> availableSkills() { return List.copyOf(TacticalSkills.available(ServerBattle.this, state.mob)); }
            public boolean castSkill(ResourceLocation id, SkillTarget target) {
                if (state.skillUsed || !isActorTurn(state.mob) || isBusy()) return false;
                boolean success = TacticalSkills.cast(ServerBattle.this, state.mob, id, target);
                if (success) state.skillUsed = true;
                return success;
            }
            public List<GridPos> airNeighbors(GridPos from) { return AerialMovement.neighbors(level, state.mob, from, state.airOffset, this::occupied, zone::contains); }
            public List<GridPos> pathToPlayer() {
                if (opponent == null) return List.of();
                return state.flying ? AerialMovement.approach(state.cell, state.ap / Math.max(1, state.moveCost), this::airNeighbors,
                        c -> inMeleeRange(c, selectedCell), c -> c.manhattanDistance(selectedCell))
                        : navigator.path(state.cell, selectedCell, ServerBattle.this::passable, 4096, true);
            }
            public List<GridPos> standableNeighbors(GridPos cell) { return state.flying ? airNeighbors(cell) : navigator.neighbours(cell); }
            public boolean occupied(GridPos cell) {
                for (PlayerMember member : members.values())
                    if (member.state.cell().equals(cell)) return true;
                for (EnemyState other : enemies.values())
                    if (other != state && other.mob.isAlive() && !other.mob.isRemoved() && other.cell.equals(cell)) return true;
                return false;
            }
            public boolean inMeleeRange(GridPos from, GridPos to) { return state.flying ? Math.abs(from.x()-to.x())<=1 && Math.abs(from.y()-to.y())<=1 && Math.abs(from.z()-to.z())<=1 : ServerBattle.inMeleeRange(from,to); }
            public boolean hasLineOfSight(GridPos from, GridPos to) { return ServerBattle.this.hasLineOfSight(from,to,state.mob); }
            public int meleeCost() { return enemyMeleeCost(state); }
            public int rangedCost() { return enemyBowCost(state); }
            public boolean canRangedAttack(GridPos from) {
                return opponent != null && state.mob instanceof net.minecraft.world.entity.monster.RangedAttackMob
                        && state.mob.getMainHandItem().getItem() instanceof BowItem
                        && Math.hypot(from.x()-selectedCell.x(),from.z()-selectedCell.z())<=16.001
                        && BallisticShot.plan(level,state.mob,from,opponent,1.6F,true,0)!=null;
            }
            public void rangedAttackPlayer() { ServerBattle.this.shootPlayer(state); }
            public void attackPlayer() { ServerBattle.this.attackPlayer(state); }
            public void log(String text) { journal.record("enemy",text); }
        };
    }

    private void resolveAiAction(EnemyState enemy,AiAction action) {
        if(action.kind()==AiAction.Kind.MOVE||action.kind()==AiAction.Kind.DIVE) {
            List<GridPos> steps=action.path();
            if(steps.isEmpty()) {advanceTurn();return;}
            int attackCost=action.kind()==AiAction.Kind.DIVE?enemyMeleeCost(enemy):0;
            long total=(long)steps.size()*enemy.moveCost+attackCost;
            if(total>enemy.ap){advanceTurn();return;}
            int cost=(int)total;
            enemy.ap-=cost;
            enemyDiveReservedCost=attackCost;
            enemyDiveAttackIndex=action.kind()==AiAction.Kind.DIVE?action.attackIndex():-1;enemyDiveAttacked=false;
            enemyPath=List.copyOf(steps);enemyPathIndex=0;enemyStepping=false;enemyMoving=true;
            if(enemy.flying)journal.record("enemy",enemy.mob.getDisplayName().getString()+" 飞行行动 · "+cost+" AP（含往返与攻击）");
            journal.record("move",enemy.mob.getDisplayName().getString()+" 移动至 "+steps.get(steps.size()-1));
            sendState();return;
        }
        AiResolution result=ai.resolveInstant(aiContext(enemy),action);
        if(result==AiResolution.ROSTER_CHANGED) {
            removeDeadEnemies();
            pruneMembers();
            return;
        }
        if (finished || !enemy.mob.getUUID().toString().equals(queue.current())) return;
        if(pendingEnemyShot!=null && rangedBusy()) return;
        advanceTurn();
    }

    private void planEnemyTurn() {
        String id=queue.current();
        if(id==null) {finish("empty turn queue");return;}
        EnemyState enemy=enemies.get(UUID.fromString(id));
        if(enemy==null || primaryOpponent(enemy.mob) == null) {advanceTurn();return;}
        resolveAiAction(enemy,ai.planTurn(aiContext(enemy)));
    }

    // ------------------------------------------------------------------
    // turn flow
    // ------------------------------------------------------------------

    private void beginTurn(String actorId) {
        if (actorId == null) {
            finish("empty turn queue");
            return;
        }
        // Every authoritative turn start invalidates all intents from the previous turn.
        session.advanceTurnEpoch();
        // Death can remove the current actor halfway through its path. Clear
        // that animation even when the next actor is a player, or moving=true
        // would leave the newly refreshed grid permanently unclickable.
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        enemyDiveAttackIndex=-1;enemyDiveAttacked=false;enemyDiveReservedCost=0;
        for (PlayerMember member : members.values()) member.state.pendingMove(null);
        UUID id = UUID.fromString(actorId);
        PlayerMember member = members.get(id);
        if (member != null) {
            if (!member.entity.isAlive() || member.entity.isRemoved() || member.entity.level() != level) {
                removeMember(member, "player defeated");
                return;
            }
            phase = BattlePhase.PLAYER_TURN;
            TacticalSkills.onTurnStart(this, member.entity);
            BattleEffects.onTurnStart(member.entity);
            if(!member.entity.isAlive()) { removeMember(member, "player defeated"); return; }
            PlayerParticipant state = member.state;
            state.maxAp(TacticalAttributes.maxAp(member.entity));
            state.moveCost(ApCalculator.moveCost(attribute(member.entity,Attributes.MOVEMENT_SPEED)));
            state.ap(ApCalculator.beginOwnTurnAp(state.ap(),state.maxAp(),
                    TacticalAttributes.apRecovery(member.entity),!state.takenTurn()));
            state.takenTurn(true);
            state.resetPaidActions();
            state.defending(false);
        } else {
            EnemyState enemy = enemies.get(id);
            if (enemy == null) {
                queue.remove(actorId);
                advanceTurn();
                return;
            }
            phase = BattlePhase.ENEMY_TURN;
            if (enemy.mob.isAlive() && !enemy.mob.isRemoved() && enemy.mob.level() == level) {
                TacticalSkills.onTurnStart(this, enemy.mob);
                BattleEffects.onTurnStart(enemy.mob);
                if(!enemy.mob.isAlive()) {removeDeadEnemies();return;}
                enemy.maxAp=TacticalAttributes.maxAp(enemy.mob);
                enemy.moveCost=ApCalculator.moveCost(attribute(enemy.mob,Attributes.MOVEMENT_SPEED));
                enemy.ap=ApCalculator.beginOwnTurnAp(enemy.ap,enemy.maxAp,TacticalAttributes.apRecovery(enemy.mob),!enemy.hasTakenTurn);
                enemy.hasTakenTurn=true;
                enemy.paidActions=0;
                enemy.skillUsed=false;
            }
            aiDelay = AI_TURN_DELAY;
            enemyMoving = false;
            enemyStepping = false;
            enemyPath = List.of();
        }
        String name=nameOf(actorId);
        journal.record("turn", name+" 的回合");
        sendState();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new com.tactical.neoforge.skill.SkillStateChangedEvent(this, true));
    }

    private void advanceTurn() {
        if (finished) return;
        refreshTacticalAttributes();
        String next = queue.advance();
        beginTurn(next);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Keep normal outside AI alive so real aggro can reinforce this encounter. */
    private void pulseAggro() {
        if(--aggroPulse>0)return;
        aggroPulse=10;
        recruitNearbyAggro();
    }

    private void recruitNearbyAggro() {
        if(finished||members.isEmpty()||hostileCount()>=participantCap)return;
        var candidates=level.getEntitiesOfClass(Mob.class,zoneBounds().inflate(8),
                mob->mob.isAlive()&&!mob.isRemoved()&&isHostile(mob)&&targetsActiveMember(mob)
                        &&!enemies.containsKey(mob.getUUID())).stream()
                .sorted(Comparator.comparingDouble((Mob mob)->mob.distanceToSqr(nearestTargetPoint(mob)))
                        .thenComparing(mob->mob.getUUID().toString())).toList();
        for(Mob mob:candidates) {
            if(hostileCount()>=participantCap)break;
            tryAbsorb(mob);
        }
    }

    /** A mob may reinforce only when it actually hunts a live, non-protected member. */
    private boolean targetsActiveMember(Mob mob) {
        if (!(mob.getTarget() instanceof ServerPlayer target)) return false;
        PlayerMember member = members.get(target.getUUID());
        return member != null && member.entity == target && !BattleManager.isImmune(target);
    }

    private Vec3 nearestTargetPoint(Mob mob) {
        ServerPlayer target = nearestPlayer(mob);
        return target == null ? mob.position() : target.position();
    }

    private void removeDeadEnemies() {
        boolean changed = false;
        boolean removedCurrent = false;
        String current = queue.current();
        var iterator = enemies.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EnemyState> entry = iterator.next();
            if (entry.getValue().mob.isDeadOrDying() || !entry.getValue().mob.isAlive() || entry.getValue().mob.isRemoved() || entry.getValue().mob.level() != level) {
                if (current != null && current.equals(entry.getKey().toString())) removedCurrent = true;
                queue.remove(entry.getKey().toString());
                BattleEffects.forget(entry.getValue().mob);
                ai.remove(entry.getValue().mob);
                // A departed unit gives up its ownership claim, but only this encounter's: another
                // encounter's identical UUID or a same-UUID respawn must keep its own routing.
                BattleManager.releaseActor(this, entry.getKey());
                entry.getValue().mob.setNoAi(entry.getValue().originalNoAi);
                entry.getValue().mob.setTarget(null);
                iterator.remove();
                changed = true;
            }
        }
        // Reuse newly freed capacity before victory/turn selection, even when the old
        // roster was emptied in this tick. Do not force a player into a second battle.
        if(changed)recruitNearbyAggro();
        if (hostileCount() == 0) {
            finish("all enemies defeated");
        } else if (removedCurrent && phase == BattlePhase.ENEMY_TURN && !finished) {
            advanceTurn();
        } else if (changed && !finished) {
            // Occupancy, queue, HP and reachable overlay change in the same
            // snapshot, even if death came from a projectile or outside command.
            sendState();
        }
    }

    private boolean healthChanged() {
        boolean changed = false;
        for (PlayerMember member : members.values()) {
            Float prior=lastHealth.put(member.entity.getId(),member.entity.getHealth());
            if(prior==null||Float.compare(prior,member.entity.getHealth())!=0)changed=true;
        }
        for(EnemyState enemy:enemies.values()) {
            Float prior=lastHealth.put(enemy.mob.getId(),enemy.mob.getHealth());
            if(prior==null||Float.compare(prior,enemy.mob.getHealth())!=0)changed=true;
        }
        return changed;
    }

    private boolean weaponChanged() {
        boolean changed = false;
        for (PlayerMember member : members.values()) {
            ItemStack current = member.entity.getMainHandItem();
            if (!ItemStack.isSameItemSameComponents(member.lastSyncedWeapon, current)) changed = true;
            member.lastSyncedWeapon = current.copy();
        }
        return changed;
    }

    private boolean refreshTacticalAttributes() {
        boolean changed = false;
        for (PlayerMember member : members.values()) {
            PlayerParticipant state = member.state;
            int cap=TacticalAttributes.maxAp(member.entity);
            changed|=cap!=state.maxAp();
            state.maxAp(cap);
            state.clampAp();
            int recovery=TacticalAttributes.apRecovery(member.entity);
            changed|=state.lastRecovery()!=recovery;
            state.lastRecovery(recovery);
            double rate=TacticalAttributes.chargeRate(member.entity,state.initiative());
            changed|=Double.compare(queue.rateOf(member.id().toString()),rate)!=0;
            queue.updateRate(member.id().toString(),rate);
        }
        for(var entry:enemies.entrySet()) {
            EnemyState state=entry.getValue();int enemyCap=TacticalAttributes.maxAp(state.mob);
            changed|=state.maxAp!=enemyCap;state.maxAp=enemyCap;state.ap=Math.max(0,Math.min(state.ap,enemyCap));
            double rate=TacticalAttributes.chargeRate(state.mob,state.initiative);
            changed|=Double.compare(queue.rateOf(entry.getKey().toString()),rate)!=0;
            queue.updateRate(entry.getKey().toString(),rate);
        }
        return changed;
    }

    public int adjustAp(ServerPlayer target, int delta) {
        PlayerMember member = member(target);
        if(finished||member==null)return -1;
        refreshTacticalAttributes();
        PlayerParticipant state=member.state;int before=state.ap();
        state.ap((int)Math.max(0,Math.min((long)state.maxAp(),(long)before+delta)));
        journal.record("action","管理员调整 AP："+before+" → "+state.ap());
        sendState();return state.ap();
    }

    public int adjustAp(int delta) {
        PlayerMember acting = actingMember();
        return acting == null ? -1 : adjustAp(acting.entity, delta);
    }

    public int currentAp() {
        PlayerMember acting = actingMember();
        return acting == null ? 0 : acting.state.ap();
    }

    public int currentAp(ServerPlayer player) {
        PlayerParticipant state = memberState(player);
        return state == null ? 0 : state.ap();
    }

    public int currentMaxAp(){
        PlayerMember acting = actingMember();
        return acting == null ? 0 : acting.state.maxAp();
    }

    public GridPos playerCell() {
        PlayerMember acting = actingMember();
        return acting == null ? null : acting.state.cell();
    }

    public GridPos playerCell(ServerPlayer player) {
        PlayerParticipant state = memberState(player);
        return state == null ? null : state.cell();
    }

    private static int initialAp(int cap) {
        return ApCalculator.initialAp(cap,intConfig(TacticalConfig.INSTANCE.initialAp,ApCalculator.INITIAL_AP));
    }

    private static int quote(int base,int count) { return ApCalculator.paidActionCost(base,count); }

    private int enemyMeleeCost(EnemyState enemy) { return quote(intConfig(TacticalConfig.INSTANCE.meleeApCost,ApCalculator.MELEE_COST),enemy.paidActions); }

    private int enemyBowCost(EnemyState enemy) { return quote(intConfig(TacticalConfig.INSTANCE.bowApCost,BattleRules.BOW_COST),enemy.paidActions); }

    /** Read-only quotes; previewing an intent never reserves AP or advances the counter. */
    private int currentMeleeCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.meleeApCost,ApCalculator.MELEE_COST),member.state.paidActions()); }
    private int currentBowCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.bowApCost,BattleRules.BOW_COST),member.state.paidActions()); }
    private int currentCrossbowCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.crossbowApCost,BattleRules.CROSSBOW_COST),member.state.paidActions()); }
    private int currentItemCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.itemApCost,ApCalculator.ITEM_COST),member.state.paidActions()); }
    private int currentPlaceCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.placeApCost,ApCalculator.PLACE_COST),member.state.paidActions()); }

    public int currentAttackCost(ServerPlayer player) {
        int kind = weaponKind(player);
        return switch(kind) { case 1 -> quote(intConfig(TacticalConfig.INSTANCE.bowApCost,BattleRules.BOW_COST),paidActions(player));
            case 2 -> quote(intConfig(TacticalConfig.INSTANCE.crossbowApCost,BattleRules.CROSSBOW_COST),paidActions(player));
            default -> quote(intConfig(TacticalConfig.INSTANCE.meleeApCost,ApCalculator.MELEE_COST),paidActions(player)); };
    }

    private int paidActions(ServerPlayer player) {
        PlayerParticipant state = memberState(player);
        return state == null ? 0 : state.paidActions();
    }

    public int currentMeleeCost() { PlayerMember m=actingMember(); return m==null?0:currentMeleeCost(m); }
    public int currentBowCost() { PlayerMember m=actingMember(); return m==null?0:currentBowCost(m); }
    public int currentCrossbowCost() { PlayerMember m=actingMember(); return m==null?0:currentCrossbowCost(m); }
    public int currentItemCost() { PlayerMember m=actingMember(); return m==null?0:currentItemCost(m); }
    public int currentPlaceCost() { PlayerMember m=actingMember(); return m==null?0:currentPlaceCost(m); }
    public int currentAttackCost() { PlayerMember m=actingMember(); return m==null?0:currentAttackCost(m.entity); }
    public int successfulPaidActions() { PlayerMember m=actingMember(); return m==null?0:m.state.paidActions(); }
    public int successfulPaidActions(ServerPlayer player) { return paidActions(player); }
    public int quoteAttackCost() { return currentAttackCost(); }
    public int quoteItemCost() { return currentItemCost(); }
    public int quotePlaceCost() { return currentPlaceCost(); }
    public int paidActionCount() { return successfulPaidActions(); }

    private boolean rangedBusy() {
        return projectiles.pending() || awaitingShotResolution;
    }

    private boolean hasMovingEnemies() {
        for (EnemyState state : enemies.values()) {
            Vec3 velocity = state.mob.getDeltaMovement();
            if (velocity.horizontalDistanceSqr() > 1.0E-4D
                    || (!state.mob.onGround() && Math.abs(state.mob.getY() - state.cell.y()) > 0.05D)) return true;
        }
        return false;
    }

    private void reconcileShotKnockback() {
        for (EnemyState state : enemies.values()) {
            Mob mob = state.mob;
            if (!mob.isAlive()) continue;
            if(state.flying) {
                if(AerialMovement.isClearAir(level,mob,mob.position())){
                    state.cell=cellOf(mob);state.airOffset=mob.position().subtract(centerOf(state.cell));
                }
                mob.setDeltaMovement(Vec3.ZERO);mob.fallDistance=0;continue;
            }
            GridPos landing = world.surfaceCell(mob.getBlockX(), mob.getBlockZ(), mob.getBlockY());
            if (landing != null && (landing.equals(state.cell) || !occupied(landing))
                    && zone.contains(landing)) {
                state.cell = landing;
            }
            // Vanilla damage/enchantments have already applied. Resolve their
            // physical tail onto a safe grid cell before any next action/turn.
            moveEntity(mob, state.cell);
            mob.fallDistance = 0;
        }
    }

    private boolean passable(GridPos cell) {
        return zone.contains(cell) && !occupied(cell);
    }

    /** Occupied now, or reserved by a member whose authoritative move has not finished yet. */
    private boolean reserved(GridPos cell) {
        if (occupied(cell)) return true;
        for (PlayerMember member : members.values()) {
            PlayerParticipant state = member.state;
            if (!state.moving()) continue;
            GridPos target = state.stepTarget();
            if (target != null && target.equals(cell)) return true;
            List<GridPos> path = state.path();
            for (int index = state.pathIndex(); index < path.size(); index++) {
                if (path.get(index).equals(cell)) return true;
            }
        }
        return false;
    }

    private boolean occupied(GridPos cell) {
        for (PlayerMember member : members.values()) if (member.state.cell().equals(cell)) return true;
        for (EnemyState state : enemies.values()) {
            if (state.mob.isAlive() && !state.mob.isRemoved() && state.cell.equals(cell)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostile(Mob mob) {
        return mob instanceof net.minecraft.world.entity.monster.Enemy;
    }

    private static boolean inMeleeRange(GridPos a, GridPos b) {
        return GridMovementRules.canStep(a, b);
    }

    /**
     * Authoritative line of sight for one viewer.
     *
     * <p>The viewer is mandatory: {@code CollisionContext.of(null)} builds a context whose
     * {@code isDescending()} throws, and the {@code (Entity)} overload silently accepts null.
     * A player action passes that player, an AI observation passes that mob - never a fixed
     * manager, because entity-dependent collision shapes make the viewer part of the answer.
     * Geometry, block/fluid masks and the tolerance below are unchanged.</p>
     */
    private boolean hasLineOfSight(GridPos from, GridPos to, LivingEntity viewer) {
        java.util.Objects.requireNonNull(viewer, "line-of-sight viewer");
        Vec3 start = new Vec3(from.x() + 0.5D, from.y() + 1.5D, from.z() + 0.5D);
        Vec3 end = new Vec3(to.x() + 0.5D, to.y() + 1.0D, to.z() + 0.5D);
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return hit.getLocation().distanceToSqr(start) >= end.distanceToSqr(start) - 0.05D;
    }

    public int weaponKind(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof CrossbowItem) {
            return 2;
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BowItem) {
            return 1;
        }
        return 0;
    }

    public int weaponKind() {
        PlayerMember acting = actingMember();
        return acting == null ? 0 : weaponKind(acting.entity);
    }

    public int weaponRangeFor(ServerPlayer player, GridPos target) {
        int kind = weaponKind(player);
        return kind == 0 ? 1 : weaponRange(playerCell(player), target, kind);
    }

    public int weaponRangeFor(GridPos target) { return weaponKind() == 0 ? 1 : weaponRange(playerCell(), target, weaponKind()); }

    /** Empty string means native weapon geometry/ballistics permit this opposing participant. */
    public String previewWeaponAttack(LivingEntity caster, LivingEntity target) {
        BattleSide casterSide = sideOf(caster), targetSide = sideOf(target);
        if (casterSide == null || targetSide == null || !caster.isAlive() || !target.isAlive()
                || caster.isRemoved() || target.isRemoved() || caster.level() != level || target.level() != level) return "目标不在战斗中";
        // Identity-bearing opposition: never side-only, so a faction policy applies here too.
        if (!opposed(caster, target)) return "不能攻击友方";
        GridPos from = participantCell(caster), to = participantCell(target);
        if (caster instanceof ServerPlayer player) {
            PlayerMember member = member(player);
            if (member == null || from == null) return "目标不在战斗中";
            int kind = weaponKind(player);
            if (kind == 0) return inMeleeRange(from,to) && hasLineOfSight(from,to,caster) ? "" : "目标不在近战范围内或被阻挡";
            if (Math.hypot(from.x()-to.x(),from.z()-to.z()) > weaponRange(from, to, kind)+.001) return "超出射程";
            ItemStack weapon = player.getMainHandItem();
            boolean loaded = kind == 2 && CrossbowItem.isCharged(weapon);
            ChargedProjectiles charged = weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.EMPTY);
            boolean rocket = kind == 2 && (loaded ? charged.contains(Items.FIREWORK_ROCKET) : player.getProjectile(weapon).is(Items.FIREWORK_ROCKET));
            int pierce = kind == 2 ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING), weapon) : 0;
            return BallisticShot.plan(level,player,from,target,kind==2?(rocket?1.6F:3.15F):3F,!rocket,pierce) != null ? "" : "弹道被阻挡";
        }
        Mob mob = (Mob) caster;
        if (mob instanceof net.minecraft.world.entity.monster.RangedAttackMob && mob.getMainHandItem().getItem() instanceof BowItem)
            return Math.hypot(from.x()-to.x(),from.z()-to.z()) <= 16.001
                    && BallisticShot.plan(level,mob,from,target,1.6F,true,0) != null ? "" : "超出射程或弹道被阻挡";
        EnemyState state = enemies.get(mob.getUUID());
        return aiContext(state).inMeleeRange(from,to) && hasLineOfSight(from,to,caster) ? "" : "目标不在近战范围内或被阻挡";
    }

    private int weaponRange(GridPos from, GridPos target, int kind) {
        int base = kind == 2 ? 24 : 16;
        int dy = from.y() - target.y();
        if (dy > 0) {
            base += Math.min(4, (dy / 2) * 2);
        }
        return base;
    }

    private void pinPlayer(PlayerMember member) {
        PlayerParticipant state = member.state;
        if (state.cell() == null) {
            return;
        }
        Vec3 target = centerOf(state.cell());
        if (member.entity.position().distanceToSqr(target) > 0.25D) {
            snapPlayer(member, state.cell());
        }
        member.entity.setDeltaMovement(Vec3.ZERO);
    }

    private static Vec3 centerOf(GridPos cell) {
        return new Vec3(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
    }

    private void setPlayerPosition(PlayerMember member, Vec3 position) {
        // The owning client follows one trajectory packet. Teleport packets here
        // reset its interpolation every tick and fight Dungeons' camera.
        ServerPlayer player = member.entity;
        player.setPos(position.x, position.y, position.z);
        if (player.connection != null) player.connection.resetPosition();
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
    }

    private void sendPlayerMotion(PlayerMember member, List<GridPos> path) {
        if (member == null) return;
        PlayerParticipant state = member.state;
        ServerPlayer player = member.entity;
        if (state.cell() != null) {
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player, new PlayerStepPacket(state.cell(), path,
                    path.isEmpty()?stepTicks:state.motionTicks(), player.getYRot(),!path.isEmpty()&&state.dropping()));
        }
    }

    private void snapPlayer(PlayerMember member, GridPos cell) {
        if (cell == null) return;
        ServerPlayer player = member.entity;
        if (player.connection == null) {
            player.setPos(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
        } else {
            player.teleportTo(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
        }
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void moveEntity(Mob mob, GridPos cell) {
        mob.teleportTo(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
        mob.setDeltaMovement(Vec3.ZERO);
    }

    private static double attribute(LivingEntity entity,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = entity.getAttribute(attribute);
        return instance == null ? (attribute == Attributes.MOVEMENT_SPEED ? 0.1D : 1.0D) : instance.getValue();
    }

    private void reject(String message) {
        journal.record("reject",message);
    }

    private void message(String text) { journal.record("info",text); }

    public BattleJournal journal() { return journal; }
    public BattleZone zone() { return zone; }
    public com.tactical.neoforge.network.CreeperTelegraphPacket creeperSnapshot() { return ai.snapshot(); }
    private static GridPos cellOf(Entity entity) { return new GridPos(entity.getBlockX(),entity.getBlockY(),entity.getBlockZ()); }
    private AABB zoneBounds() {
        var b=zone.bounds();return new AABB(b.minX(),b.minY(),b.minZ(),b.maxX()+1,b.maxY()+1,b.maxZ()+1);
    }

    public List<GridPos> reachableCells(ServerPlayer player) {
        PlayerMember member = member(player);
        if (member == null) return List.of();
        PlayerParticipant state = member.state;
        int maxSteps = state.moveCost() <= 0 ? 0 : state.ap() / state.moveCost();
        var cells=new java.util.LinkedHashSet<>(navigator.reachable(state.cell(),maxSteps,this::passable,
                (from,to)->DropClearance.canStep(level,member.entity,from,to,zone::contains)).keySet());
        if(maxSteps>0) for(var direction:com.tactical.core.grid.GridDirection.values()) {
            int x=state.cell().x()+direction.dx,z=state.cell().z()+direction.dz;
            for(int down=1;down<=intConfig(TacticalConfig.INSTANCE.maxDropHeight,8);down++) {
                GridPos cell=new GridPos(x,state.cell().y()-down,z);
                if(passable(cell)&&DropClearance.canDrop(level,member.entity,state.cell(),cell,
                        intConfig(TacticalConfig.INSTANCE.maxDropHeight,8),zone::contains)) { cells.add(cell); break; }
            }
        }
        return new ArrayList<>(cells);
    }

    public List<GridPos> reachableCells() {
        PlayerMember acting = actingMember();
        return acting == null ? List.of() : reachableCells(acting.entity);
    }

    public int pendingProjectiles() {
        return projectiles.pendingCount();
    }

    public int stateRevision() {
        return stateRevision;
    }

    public String phaseName() {
        return phase.name();
    }

    public int enemyCount() {
        return hostileCount();
    }

    /**
     * Authoritative resync for one encounter.
     *
     * <p>Used after an explicit transition (a confirmed join) so the client always receives a snapshot
     * <em>after</em> it was told which encounter it is entering. A client that had just left this same
     * encounter re-opens it on that authorization instead of treating the snapshot as stale.</p>
     */
    public void resendState() {
        if (finished) return;
        sendState();
    }

    public String firstEnemyId() {
        return enemies.values().stream().filter(state -> state.side == BattleSide.HOSTILE && state.mob.isAlive())
                .findFirst().map(state -> state.mob.getUUID().toString()).orElse(null);
    }

    public String debugEnemies() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<UUID, EnemyState> entry : enemies.entrySet()) {
            EnemyState state = entry.getValue();
            builder.append(state.mob.getDisplayName().getString())
                    .append("@").append(state.cell)
                    .append(" hp=").append(String.format("%.1f", state.mob.getHealth()))
                    .append("; ");
        }
        return builder.toString();
    }

    /**
     * Exit snapshot for one member.
     *
     * <p>It names this encounter, so a member who left here and already joined another encounter
     * with its own epoch is not wiped by this older clear.</p>
     */
    private BattleStatePacket inactiveState(PlayerMember member) {
        return new BattleStatePacket(false, BattlePhase.ENDED.ordinal(), 1, 0, 0, 0,
                0, 0, 0, new int[0], "", List.of(), 0, "", 0, 0,
                center.x(), center.y(), center.z(), zoneRadius, false, -1, new int[0], new int[0], 0, false,
                new int[0], 0, 0, 0, 0, 0, session.sessionId(), 0L, 0L, 0L);
    }

    private String nameOf(String id) {
        if (id == null) {
            return "";
        }
        UUID uuid = UUID.fromString(id);
        PlayerMember member = members.get(uuid);
        if (member != null) return member.name();
        EnemyState state = enemies.get(uuid);
        return state == null ? "?" : state.mob.getDisplayName().getString();
    }

    private int entityIdOf(UUID uuid) {
        PlayerMember member = members.get(uuid);
        if (member != null) return member.entity.getId();
        EnemyState state = enemies.get(uuid);
        return state == null ? -1 : state.mob.getId();
    }

    /** The parts of a snapshot every member shares; the per-member parts are added per recipient. */
    private record SharedSnapshot(List<String> order, int[] gauges, int[] turnEntityIds, String actorName,
                                  int actorEntityId, int[] participants, boolean anyMoving,
                                  UUID sessionId, long epoch) {
    }

    private SharedSnapshot sharedSnapshot() {
        List<String> order = new ArrayList<>();
        int[] gauges = new int[queue.order().size()];
        int[] turnEntityIds = new int[queue.order().size()];
        int gaugeIndex = 0;
        for (String id : queue.order()) {
            UUID uuid = UUID.fromString(id);
            order.add(nameOf(id));
            turnEntityIds[gaugeIndex] = entityIdOf(uuid);
            gauges[gaugeIndex++] = (int) Math.round(queue.gaugeOf(id) * 100.0D);
        }
        String actorName = nameOf(queue.current());
        UUID actorUuid = queue.current() == null ? null : UUID.fromString(queue.current());
        int actorEntityId = actorUuid == null ? -1 : entityIdOf(actorUuid);

        int[] participants = new int[(members.size() + enemies.size()) * 3];
        int participantIndex = 0;
        for (PlayerMember member : members.values()) {
            participants[participantIndex++] = member.entity.getId();
            participants[participantIndex++] = (int) Math.ceil(member.entity.getHealth());
            participants[participantIndex++] = (int) Math.ceil(member.entity.getMaxHealth());
        }
        for (EnemyState state : enemies.values()) {
            participants[participantIndex++] = state.mob.getId();
            participants[participantIndex++] = (int) Math.ceil(state.mob.getHealth());
            participants[participantIndex++] = (int) Math.ceil(state.mob.getMaxHealth());
        }
        return new SharedSnapshot(order, gauges, turnEntityIds, actorName, actorEntityId, participants,
                anyPlayerMoving() || enemyMoving || rangedBusy(), session.sessionId(), session.turnEpoch());
    }

    private void sendState() {
        if (finished) return;
        ai.sendSnapshot();
        stateRevision++;
        if (recipients().isEmpty()) return;
        SharedSnapshot shared = sharedSnapshot();
        for (PlayerMember member : List.copyOf(members.values())) sendStateTo(member, shared);
    }

    /**
     * Publish the complete snapshot to exactly one member.
     *
     * <p>Used for the member whose membership transition must be followed by its own snapshot, without
     * touching anybody else's state or revision.</p>
     */
    void sendStateTo(ServerPlayer player) {
        if (finished || player == null) return;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return;
        sendStateTo(member, sharedSnapshot());
    }

    private void sendStateTo(PlayerMember member, SharedSnapshot shared) {
        ServerPlayer player = member.entity;
        boolean deliver = com.tactical.neoforge.network.ServerPackets.canReceive(player);
        // Unchanged fast path, except while a fixture observes publications: then the attempt is
        // recorded even for a connection that cannot carry it.
        if (!deliver && !com.tactical.neoforge.network.ServerPackets.isCapturing()) return;
        PlayerParticipant state = member.state;
        member.lastSyncedWeapon = player.getMainHandItem().copy();
        // Own resources, own reachable set, own equipment and own quote counters.
        List<GridPos> reachable = reachableCells(player);
        int[] flattened = new int[reachable.size() * 3];
        int index = 0;
        for (GridPos cell : reachable) {
            flattened[index++] = cell.x();
            flattened[index++] = cell.y();
            flattened[index++] = cell.z();
        }
        int kind = weaponKind(player);
        String weapon = player.getMainHandItem().isEmpty()
                ? "空手" : player.getMainHandItem().getHoverName().getString();
        GridPos ownCell = state.cell();
        BattleStatePacket packet = new BattleStatePacket(
                true, phase.ordinal(), queue.turnCount(), state.ap(), state.maxAp(), state.moveCost(),
                ownCell.x(), ownCell.y(), ownCell.z(), flattened, shared.actorName(), shared.order(),
                queue.turnCount(), weapon, kind, kind == 0 ? 0 : weaponRangeForDisplay(kind),
                center.x(), center.y(), center.z(), zoneRadius,
                state.moving() || shared.anyMoving(), shared.actorEntityId(), shared.participants(),
                shared.gauges(), actionCount, state.defending(), shared.turnEntityIds(),
                currentAttackCost(player), currentItemCost(member), currentPlaceCost(member),
                TacticalAttributes.apRecovery(player), state.paidActions(),
                shared.sessionId(), shared.epoch(),
                // Stamped by ServerPackets for this recipient at delivery time.
                0L, 0L);
        // Through the single publication choke point, so this recipient's membership generation is
        // stamped at this very moment and a fixture sees exactly what was attempted.
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player, packet);
    }

    /**
     * Publish the authoritative entry of one player.
     *
     * <p>The order is the contract: the membership transition (carried by the full snapshot) comes
     * first, then the complete journal history and the complete warning snapshot for that member.
     * Nothing here is allowed to fail the join: a failed publication is corrected by a newer exit
     * generation instead of silently rolling back a membership the server already committed.</p>
     */
    private void publishActivation(ServerPlayer player, String journalLine) {
        com.tactical.neoforge.battle.multi.MembershipGenerations.activate(player, sessionId());
        com.tactical.neoforge.battle.AttackRangeService.forget(player);
        // The transition and the member's own full snapshot.
        sendStateTo(player);
        try {
            publishBootstrap(player, journalLine);
        } catch (RuntimeException failure) {
            // The membership and the roster are both committed and already public. Withdrawing the
            // membership while keeping the roster (or the reverse) would split server and client for
            // good, so this transaction only ever repairs: the authoritative snapshot is republished at
            // the same generation and the failed parts are attempted once more.
            Tactical.LOGGER.error("[Tactical] bootstrap publication failed for {}; resyncing the committed "
                    + "membership instead of withdrawing it", player.getUUID(), failure);
            try {
                sendStateTo(player);
                publishBootstrap(player, journalLine);
            } catch (RuntimeException retry) {
                // Still consistent: the roster and the membership stay authoritative, and the missing
                // parts are registered as a debt that the server tick retries with a bounded backoff.
                // Nothing is withdrawn and nothing is claimed to have healed.
                Tactical.LOGGER.error("[Tactical] bootstrap resync failed for {}; registering a bounded retry",
                        player.getUUID(), retry);
                com.tactical.neoforge.battle.multi.PendingBootstraps.schedule(player, sessionId(),
                        com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(player),
                        level.getServer().getTickCount());
            }
        }
    }

    /** The parts of an entry that follow the transition: journal history, warnings, skills. */
    private void publishBootstrap(ServerPlayer player, String journalLine) {
        // The journal is the part that carries the entry itself, so it goes first; what can fail here
        // is the rest, and that is exactly what the bounded retry owes afterwards.
        if (journalLine != null) journal.record("battle", journalLine);
        else journal.sendFullTo(player);
        fault(FaultPoint.BOOTSTRAP);
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player, ai.snapshot());
        com.tactical.neoforge.network.SkillNetwork.forceSync(this, player);
    }

    /**
     * Republish the complete entry for one member whose bootstrap was lost.
     *
     * <p>Used by the bounded retry only. It is the same content the entry publishes: the authoritative
     * snapshot, the full journal history, the full warning snapshot and the skills. It never changes
     * membership, so a failure here can only be retried again.</p>
     */
    public void retryBootstrap(ServerPlayer player) {
        if (finished || player == null) return;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return;
        sendStateTo(player);
        // Same publication routine as the entry itself, so a retry fails and recovers exactly where the
        // original attempt did instead of in a separate, differently ordered path.
        publishBootstrap(player, null);
    }

    /** Publish an exit: the membership ends, and the client stops showing this encounter. */
    private void publishExit(PlayerMember member) {
        if (member == null) return;
        // A membership that ends can no longer receive its owed bootstrap.
        com.tactical.neoforge.battle.multi.PendingBootstraps.forget(member.id());
        com.tactical.neoforge.battle.multi.MembershipGenerations.deactivate(member.entity, sessionId());
        com.tactical.neoforge.battle.AttackRangeService.forget(member.entity);
    }

    /** Higher-generation exit that corrects a membership which could not be bootstrapped. */
    private void compensateExit(ServerPlayer player) {
        if (player == null) return;
        try {
            com.tactical.neoforge.battle.multi.MembershipGenerations.deactivate(player, sessionId());
            com.tactical.neoforge.battle.AttackRangeService.forget(player);
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                    BattleStatePacket.inactive(session.sessionId()));
        } catch (RuntimeException ignored) {
            // A correction that cannot be delivered is still better than a stale membership.
        }
    }

    private int weaponRangeForDisplay(int kind) {
        return kind == 2 ? 24 : 16;
    }

    private static final class EnemyState {
        final Mob mob;
        final BattleSide side;
        final boolean originalNoAi;
        /** Admission transaction snapshot: what this encounter must give back if it fails. */
        final boolean originalPersistenceRequired;
        final Vec3 originalPosition;
        final LivingEntity originalTarget;
        final float originalFallDistance;
        boolean skillUsed;
        GridPos cell;
        int ap;
        int maxAp;
        int paidActions;
        boolean hasTakenTurn;
        int moveCost;
        final int initiative;
        final boolean flying;
        Vec3 airOffset;

        EnemyState(Mob mob, GridPos cell, int ap, int maxAp, int moveCost, int initiative, BattleSide side,
                   boolean originalNoAi, boolean originalPersistenceRequired, Vec3 originalPosition,
                   LivingEntity originalTarget, float originalFallDistance) {
            this.mob = mob;
            this.side = side;
            this.originalNoAi = originalNoAi;
            this.originalPersistenceRequired = originalPersistenceRequired;
            this.originalPosition = originalPosition;
            this.originalTarget = originalTarget;
            this.originalFallDistance = originalFallDistance;
            this.flying=AerialMovement.supports(mob);
            this.airOffset=mob.position().subtract(new Vec3(cell.x()+.5,cell.y(),cell.z()+.5));
            this.cell = cell;
            this.ap = ap;
            this.maxAp = maxAp;
            this.moveCost = moveCost;
            this.initiative = initiative;
        }
    }
}
