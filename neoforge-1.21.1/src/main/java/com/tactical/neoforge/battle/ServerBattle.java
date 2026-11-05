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
import com.tactical.neoforge.api.companion.CompanionAbility;
import com.tactical.neoforge.api.companion.CompanionAdapter;
import com.tactical.neoforge.battle.terrain.TerrainFall;
import com.tactical.neoforge.network.MoveChoiceOfferPacket;
import com.tactical.neoforge.registry.companion.CompanionRegistry;
import com.tactical.neoforge.battle.ai.*;
import com.tactical.core.combat.ProjectileAim;
import com.tactical.core.combat.RangedWeapon;
import com.tactical.core.combat.ThrowProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.skill.TacticalSkills;
import com.tactical.neoforge.skill.SkillAvailability;
import com.tactical.neoforge.skill.SkillText;
import com.tactical.neoforge.skill.SkillTarget;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.network.PlayerStepPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
    /** The shared no-shield guard price, in AP. One constant for every defending actor. */
    private static final int DEFEND_COST = 25;
    /** Guard multipliers by holder: a shield narrows the hit, a plain stance halves it. */
    private static final double DEFEND_SHIELD_FACTOR = 0.3D;
    private static final double DEFEND_STANCE_FACTOR = 0.5D;
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
    /**
     * The one blaze salvo this encounter is currently driving, or null.
     *
     * <p>Only the actor whose own turn is running can own it, so a single field is the whole state: the stage
     * arithmetic and its hard deadline live in {@link BlazeVolley}, and every exit path (actor gone, target
     * gone, deadline, encounter end) clears it here.</p>
     */
    private BlazeVolley blazeVolley;

    private final TurnScheduler queue = new TurnScheduler();
    // Includes both hostile and friendly mobs; side-sensitive rules must use hostileCount().
    private final Map<UUID, EnemyState> enemies = new LinkedHashMap<>();
    private final List<Mob> bystanders = new ArrayList<>();
    /**
     * Companions that already left this encounter for a lifecycle reason. They are remembered so a
     * recruitment scan cannot silently bring the same unit back and hand it a fresh initial AP pool.
     */
    private final java.util.Set<UUID> departedCompanions = new java.util.LinkedHashSet<>();
    private int aggroPulse;
    /** Hostiles the roster cap refuses: evicted from the battlefield and held out while it stays full. */
    private final MobIsolation isolation = new MobIsolation();
    private int ticks;

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
    /**
     * The one self-drink this encounter is presenting right now, or null.
     *
     * <p>It is a bounded, vanilla-styled hold: the borrowed bottle in the hand, vanilla's own drinking flag and
     * a countdown, held for as many ticks as the potion's own use duration. The turn stays busy for exactly that
     * window and the dose is settled once, at the end of it - never per tick, and never a second time from
     * vanilla's own {@code aiStep}, which is skipped while this encounter controls her.</p>
     */
    private UUID pendingWitchDrink;
    private int pendingWitchDrinkTicks;
    private WitchBrew pendingWitchBrew;
    /** The stack the presentation borrowed from her hand; given back when the pose ends. */
    private ItemStack pendingWitchBorrowed = ItemStack.EMPTY;
    /** The exact bottle this presentation put in her hand, so only that slot is ever written back. */
    private ItemStack pendingWitchBottle = ItemStack.EMPTY;

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

        // Cap-refused mobs keep their own AI state (no setNoAi, no removal) but not their
        // relationship with this encounter: MobIsolation moves them out of the battlefield and
        // clears this encounter's members from their target/Brain memories while the roster stays
        // full. The moment a slot frees, suppression ends and ordinary recruitment owns them again.
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
        journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_STARTED, hostileCount()));
        beginTurn(queue.advance());
        // Only now is there an acting slot, which is what a companion admission needs.
        recruitCompanions();
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
        ticks++;
        if (finished) {
            return;
        }
        pruneMembers();
        if (finished) return;
        pruneInvalidCompanions();
        if (finished) return;
        if (members.isEmpty()) {
            finish(BattleText.endReason(BattleText.End.NO_PARTICIPANTS));
            return;
        }

        boolean attributesChanged=refreshTacticalAttributes();
        boolean healthChanged=healthChanged();
        boolean weaponChanged=weaponChanged();
        boolean projectileChanged = projectiles.tick();
        removeDeadEnemies();
        if (finished) return;
        // The blaze salvo owns the turn while it runs: charge first, then its three shots, then hand back.
        tickBlazeVolley();
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
        // A presented drink owns the turn for its bounded window; settle it once it runs out.
        tickWitchDrink();
        if (finished) return;
        if (witchDrinking()) {
            if (projectileChanged || attributesChanged || healthChanged || weaponChanged) sendState();
            return;
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
        // Cap-refused hostiles are held outside the battlefield for as long as the roster stays
        // full. Release happens inside maintain(), on the same tick a hostile slot frees.
        isolation.maintain(this);

        boolean anyMoving = false;
        for (PlayerMember member : List.copyOf(members.values())) {
            if (!member.state.moving()) continue;
            stepPlayer(member);
            anyMoving = true;
        }
        if (anyMoving) return;
        // A forced fall owns the encounter until every affected unit has landed: no pinning, no queue
        // advance and no new command while it runs.
        if (stepTerrainFall()) return;
        for (PlayerMember member : members.values()) pinPlayer(member);
        beginTerrainFallIfNeeded();
        if (terrainFall.busy()) return;
        if (rangedBusy()) return;

        // A mob's own trajectory advances in both mob phases: an automatic enemy turn and a companion turn
        // that its controller started. Only the automatic phase goes on to plan anything by itself.
        if (enemyMoving && (phase == BattlePhase.ENEMY_TURN || phase == BattlePhase.COMPANION_TURN)) {
            stepEnemy();
            return;
        }
        if (phase == BattlePhase.ENEMY_TURN) {
            if (aiDelay > 0) {
                aiDelay--;
                return;
            }
            planEnemyTurn();
        }
    }

    /** Legacy form: the reason is already text (an operator or fixture token) and stays literal. */
    public void finish(String reason) {
        finish(BattleText.literal(reason));
    }

    /** End this encounter with structure, so every member reads the reason in its own language. */
    public void finish(Component reason) {
        if (finished || ending) {
            return;
        }
        ending = true;
        journal.finish(reason);
        TacticalSkills.onBattleEnd(this);
        finished = true;
        phase = BattlePhase.ENDED;
        abortWitchDrink(null);
        projectiles.clear();
        BattlePotionSplash.clear(this);
        // No salvo and no marked fireball may outlive the encounter: the charge bit and the collision rule both
        // belong to this encounter's control, not to the entity.
        BlazeVolley endingVolley = blazeVolley;
        blazeVolley = null;
        if (endingVolley != null) setBlazeCharged(enemies.get(endingVolley.actorId()), false);
        BlazeFireballs.clearBattle(this);
        ai.clear();
        // Effects belong to the actor, so they are released per unit of THIS encounter. A global
        // clear would also drop another running encounter's effect clocks, burning baselines and
        // food settlement. Capture the roster before the maps are emptied.
        List<LivingEntity> released = new ArrayList<>(members.size() + enemies.size());
        for (PlayerMember member : members.values()) released.add(member.entity);
        for (EnemyState state : enemies.values()) released.add(state.mob);
        awaitingShotResolution = false;
        pendingEnemyShot=null;
        // No fall may outlive the encounter: its state and its reservations. The client interpolation of
        // each member is cancelled by releaseMemberVisuals below, on the same exit snapshot.
        terrainFall.clear();
        pendingTurnAdvance = false;
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
        for (EnemyState state : List.copyOf(enemies.values())) {
            if (state.companionAdapter != null) {
                // One release path for every exit: the same flags, the same pose, the same claim release.
                releaseCompanion(state, true);
                continue;
            }
            state.mob.setNoAi(state.originalNoAi);
            state.mob.setTarget(null);
            releaseWitchState(state);
        }
        enemies.clear();
        for (Mob bystander : bystanders) {
            bystander.setNoAi(false);
            bystander.setTarget(null);
        }
        bystanders.clear();
        // Isolated hostiles were never mutated (no AI/persistence change) and are not teleported
        // back; only this encounter's suppression ends with the encounter.
        isolation.clear();
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
            journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_INVITE,
                    BattleText.name(member.name()), BattleText.name(target.getGameProfile().getName())));
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
            publishActivation(player, BattleText.translatable(BattleText.KEY_BATTLE_JOIN,
                    BattleText.name(member.name())));
            sendState();
            // A member that arrives with its companion standing beside it brings that companion along now.
            recruitCompanions();
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
    /** Legacy form: the reason is already text and stays literal. */
    boolean releaseReplacedMember(UUID playerId, ServerPlayer replacement, String reason) {
        return releaseReplacedMember(playerId, replacement,
                reason == null ? null : BattleText.literal(reason));
    }

    boolean releaseReplacedMember(UUID playerId, ServerPlayer replacement, Component reason) {
        PlayerMember member = members.get(playerId);
        if (member == null || member.entity == replacement) return false;
        if (member.entity.isAlive() && !member.entity.isRemoved()) return false;
        removeMember(member, reason);
        return true;
    }

    /** Legacy form: the reason is already text and stays literal. */
    public boolean leave(ServerPlayer player, String reason) {
        return leave(player, BattleText.literal(reason));
    }

    /** Explicit exit of one player; every other member keeps playing. */
    public boolean leave(ServerPlayer player, Component reason) {
        if (player == null || finished) return false;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return false;
        removeMember(member, reason);
        return true;
    }

    private void removeMember(PlayerMember member, Component reason) {
        UUID id = member.id();
        // A dead or departing member stops falling with the encounter, not after it. Its client side
        // interpolation is cancelled further down by releaseMemberVisuals, which is the one place that
        // sends the empty trajectory for a member leaving.
        if (terrainFall.unit(id) != null) terrainFall.end(id);
        boolean acting = id.toString().equals(queue.current());
        boolean wasManager = session.isManager(id);
        members.remove(id);
        queue.remove(id.toString());
        // A controller that can no longer give orders takes its companions with it. They are never handed to
        // another player and never quietly turned back into AI, and one of them may be the acting unit.
        boolean companionWasActing = releaseCompanionsOf(id, false);
        releaseMemberVisuals(member, false);
        BattleSession.LeaveResult result = session.leave(id);
        BattleManager.onMemberLeft(this, id);
        // The exit is a membership transition of its own, so its generation is above the entry's.
        publishExit(member);
        if (!finished) com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity, inactiveState(member));
        MutableComponent row = BattleText.translatable(BattleText.KEY_BATTLE_LEAVE,
                BattleText.name(member.name()), reason);
        if (result.managerChanged() && result.newManager() != null) {
            // Appended structure, not an assembled sentence: the handover segment resolves on its own.
            row = row.append(BattleText.translatable(BattleText.KEY_BATTLE_HANDOVER,
                    BattleText.name(nameOf(result.newManager().toString()))));
        }
        journal.record("battle", row);
        if (finished) return;
        if (result.finished() || members.isEmpty()) {
            finish(BattleText.endReason(BattleText.End.ALL_PLAYERS_LEFT));
            return;
        }
        if (acting) {
            advanceTurn();
            return;
        }
        // The departing controller's companion owned the acting slot: give that slot on exactly once, so no
        // order is ever waited for on behalf of a player the encounter no longer has.
        if (companionWasActing) {
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

    /**
     * Release every control record that is no longer valid, once per tick.
     *
     * <p>Death, removal and a dimension change are already handled by {@link #removeDeadEnemies()}; what this
     * covers is the relationship itself: an adapter that no longer claims the unit, a controller that changed
     * hands, a controller that is no longer a member, or a kind that is no longer eligible. Without it the
     * queue could wait forever on a unit nobody may order, and a live wolf could stay under a stale owner.</p>
     */
    private void pruneInvalidCompanions() {
        if (finished || ending || members.isEmpty()) return;
        boolean releasedActor = false;
        for (EnemyState state : List.copyOf(enemies.values())) {
            if (state.companionAdapter == null) continue;
            Mob mob = state.mob;
            // The liveness cases belong to removeDeadEnemies, which also advances the turn.
            if (!mob.isAlive() || mob.isRemoved() || mob.level() != level) continue;
            CompanionAdapter adapter = state.companionAdapter;
            String why = null;
            try {
                UUID live = adapter.supports(mob) ? adapter.controllerId(mob) : null;
                if (live == null) why = "no controller";
                else if (!live.equals(state.companionOwner)) why = "controller changed";
                else if (!members.containsKey(live)) why = "controller is not a member";
                else if (!adapter.canJoin(level, mob, live)) why = "no longer eligible";
            } catch (RuntimeException failure) {
                // A broken adapter must not keep the record alive, and must not stop the other candidates.
                Tactical.LOGGER.error("[Tactical] companion eligibility check failed for {}; releasing it",
                        mob.getUUID(), failure);
                why = "adapter failure";
            }
            if (why == null) continue;
            if (mob.getUUID().toString().equals(queue.current())) releasedActor = true;
            enemies.remove(mob.getUUID());
            releaseCompanion(state, false);
            Tactical.LOGGER.info("[Tactical] companion {} released: {}", mob.getUUID(), why);
            journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_COMPANION_RELEASED,
                    BattleText.name(mob)));
        }
        if (releasedActor && !finished) {
            if (phase == BattlePhase.COMPANION_TURN) advanceTurn();
            else sendState();
        }
    }

    private void pruneMembers() {
        for (PlayerMember member : List.copyOf(members.values())) {
            // A stable code per exit, so the row's wording is decided at the display boundary only.
            BattleText.Exit exit = null;
            if (!member.entity.isAlive()) exit = BattleText.Exit.DEFEATED;
            else if (member.entity.isRemoved()) exit = BattleText.Exit.REMOVED;
            else if (member.entity.connection != null && member.entity.hasDisconnected()) exit = BattleText.Exit.DISCONNECTED;
            else if (member.entity.level() != level) exit = BattleText.Exit.LEVEL_CHANGED;
            if (exit == null) continue;
            Component reason = BattleText.exitReason(exit);
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
            journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_FRIENDLY, BattleText.name(mob)));
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
        // Declared outside the transaction so a failed admission can still give the blaze its own charge back.
        boolean originalBlazeCharged = false;
        TacticalWitchAccess.WitchDrink witchDrink = null;
        WitchBrew pendingDrink = null;
        try {
            // A witch can already be mid-drink when she is admitted. Taking it out of vanilla belongs to this
            // admission transaction: nothing may settle it for free on wall-clock time, and if the admission
            // fails the claim is released with it. Only a real vanilla drinking action is ever taken, so a
            // witch holding an axe or a spare bottle is left exactly as she is.
            if (mob instanceof Witch witch) {
                witchDrink = witchAccess(witch).tactical$takeDrink();
                if (witchDrink != null) pendingDrink = WitchBrews.brewOf(witchDrink.potion());
            }
            // A blaze can be admitted while vanilla already has it charged. NoAI only stops the goal that would
            // clear that bit, so the encounter takes the transient state out of the entity here: the only charge
            // shown from now on is this session's own 60 ticks.
            originalBlazeCharged = isBlazeActor(mob) && blazeCharged(mob);
            if (isBlazeActor(mob)) setBlazeCharged(mob, false);
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
                    originalNoAi, originalPersistenceRequired, originalPosition, originalTarget, originalFallDistance,
                    originalBlazeCharged);
            state.witchDrink = witchDrink;
            state.pendingDrink = pendingDrink;
            // One cooldown object per unit, so two witches can never advance each other's drink.
            state.drink = new WitchDrinkCooldown(witchDrinkCooldownTurns());
            enemies.put(mob.getUUID(), state);
            ai.add(mob);
            if (DeveloperTrace.enabled()) {
                // Development trace: which exact unit joined which encounter. Needed because a client
                // mirror cannot tell two same-typed mobs apart.
                Tactical.LOGGER.info("[Tactical] dev-trace admit session={} tick={} phase={} uuid={} tags={} at={} hostiles={}",
                        session.sessionId(), ticks, phase, mob.getUUID(), mob.getTags(),
                        mob.blockPosition(), hostileCount());
            }
            return state;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] admission failed for {}; rolling this unit back",
                    mob.getUUID(), failure);
            EnemyState partial = enemies.remove(mob.getUUID());
            if (partial != null) restoreAdmission(partial);
            else {
                ai.remove(mob);
                restoreUnit(mob, originalNoAi, originalPersistenceRequired, originalPosition, originalTarget,
                        originalFallDistance, originalBlazeCharged);
                if (witchDrink != null && mob instanceof Witch witch) {
                    witchAccess(witch).tactical$restoreDrink(witchDrink);
                }
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
                state.originalPosition, state.originalTarget, state.originalFallDistance,
                state.originalBlazeCharged);
        // A rolled-back admission never charged for the drink it froze, so the unit gets it back untouched.
        if (state.witchDrink != null && state.mob instanceof Witch witch) {
            witchAccess(witch).tactical$restoreDrink(state.witchDrink);
        }
        state.witchDrink = null;
        state.pendingDrink = null;
        BattleManager.releaseActor(this, state.mob.getUUID());
    }

    /** Put one unit's own state back; touches nothing else in the world. */
    private void restoreUnit(Mob mob, boolean originalNoAi, boolean originalPersistenceRequired,
                             Vec3 originalPosition, LivingEntity originalTarget, float originalFallDistance,
                             boolean originalBlazeCharged) {
        BattleEffects.forget(mob);
        mob.getNavigation().stop();
        // A failed admission gives the entity back its own transient charge bit, because this encounter never
        // owned it long enough to keep it.
        if (isBlazeActor(mob)) setBlazeCharged(mob, originalBlazeCharged);
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

    int hostileCount() {
        return (int) enemies.values().stream().filter(state -> state.side == BattleSide.HOSTILE
                && state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level).count();
    }

    /** Hostile roster limit for this encounter; the only threshold isolation and refill share. */
    int hostileCapacity() {
        return participantCap;
    }

    /** True while a further hostile would be refused; used by the contact-push guard. */
    boolean hostileAtCap() {
        return hostileCount() >= participantCap;
    }

    /** True when this encounter is currently holding {@code mob} outside the battlefield. */
    boolean holdsOutside(Mob mob) {
        return isolation.suppresses(mob);
    }

    /** How many units this encounter is currently holding out; diagnostics and tests only. */
    int heldCount() {
        return isolation.size();
    }

    /**
     * True when this encounter must keep {@code mob} off its members by physical contact.
     *
     * <p>Hostility is deliberately <em>not</em> part of this test. A refused attacker is usually an
     * {@code Enemy}, but a neutral or plain mob can displace a participant just as well by collision,
     * so the collision rule covers every unowned mob while the roster is full. Callers
     * ({@link BattleContactGuard#isolationFor}) already exclude participants, players and any unit
     * another encounter owns.</p>
     */
    boolean protectsFromContact(Mob mob) {
        if (finished || ending || mob == null) return false;
        // Fullness is the only switch. A stale exclusion entry must never outlive the fullness
        // that created it: the instant a slot frees, ordinary contact and recruitment resume.
        return IsolationPolicy.holdsWhileFull(hostileCount(), participantCap);
    }

    /**
     * Exit path for an attack that admission had to refuse. Never evicts while a slot is free:
     * then the ordinary absorb/refill rules own the mob.
     */
    public void isolateIntruder(Mob mob) {
        if (finished || ending || mob == null) return;
        if (hostileCount() < participantCap) return;
        isolation.offer(this, mob);
    }

    int tickCount() {
        return ticks;
    }

    public boolean isActorTurn(LivingEntity actor) {
        return !ending && actor != null && isParticipant(actor) && actor.isAlive() && !actor.isRemoved()
                && actor.level() == level && actor.getUUID().toString().equals(queue.current());
    }

    public boolean isBusy() {
        return anyPlayerMoving() || enemyMoving || rangedBusy() || witchDrinking() || terrainFall.busy();
    }

    /**
     * Whether this member may change its own growth profile right now.
     *
     * <p>Being a participant no longer forbids it: what forbids it is not being able to act. The condition is
     * the same one every other command of this encounter uses - this player is the acting member of a player
     * turn and nothing is in flight (no movement, no projectile, no fall, no enemy action). A waiting member, an
     * enemy turn and an action already underway are all refused; viewing the page is never affected.</p>
     *
     * <p>It is a query, not a transaction: nothing here spends a point, opens a turn or moves the queue. The
     * growth transaction itself stays in {@code ProgressionService}, which asks this question once.</p>
     */
    public boolean allowsCharacterMutation(ServerPlayer player) {
        if (finished || ending || player == null) return false;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return false;
        if (!player.isAlive() || player.isRemoved() || player.level() != level) return false;
        if (phase != BattlePhase.PLAYER_TURN) return false;
        if (!isActorTurn(player)) return false;
        return !isBusy();
    }

    /**
     * Republishes what a committed allocation changed, without starting a turn.
     *
     * <p>A point of endurance moves this member's real max AP and charge rate, and a point of vigor or mind
     * moves the max health or MP every participant can already see. The caps, the clamp and the queue rate are
     * therefore re-read through the same refresh the turn flow uses, and the encounter publishes its normal
     * snapshot. Nothing is granted: AP is never raised to a new maximum (only clamped down to a lowered one),
     * no recovery is applied, no turn is started and no other member's resources are touched.</p>
     */
    public void refreshGrowth(ServerPlayer player) {
        if (finished || player == null) return;
        PlayerMember member = members.get(player.getUUID());
        if (member == null || member.entity != player) return;
        refreshTacticalAttributes();
        sendState();
    }

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
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        String bestId = null;
        for (PlayerMember member : members.values()) {
            ServerPlayer p = member.entity;
            if (!p.isAlive() || p.isRemoved() || p.level() != level) continue;
            PlayerParticipant state = memberState(p);
            if (state == null || !zone.contains(state.cell())) continue;
            if (!opposed(actor, p)) continue;
            double distance = p.distanceToSqr(actor);
            String id = p.getUUID().toString();
            if (distance < bestDistance || (distance == bestDistance && (bestId == null || id.compareTo(bestId) < 0))) {
                best = p;
                bestDistance = distance;
                bestId = id;
            }
        }
        // Controlled companions are legal targets too, otherwise a companion's guard would never be used.
        // The older automatic friendly mobs are not in this pool, so their behaviour is unchanged.
        for (EnemyState state : enemies.values()) {
            Mob mob = state.mob;
            if (state.companionAdapter == null || mob == actor) continue;
            if (!mob.isAlive() || mob.isRemoved() || mob.level() != level) continue;
            if (!zone.contains(state.cell)) continue;
            if (!opposed(actor, mob)) continue;
            double distance = mob.distanceToSqr(actor);
            String id = mob.getUUID().toString();
            if (distance < bestDistance || (distance == bestDistance && (bestId == null || id.compareTo(bestId) < 0))) {
                best = mob;
                bestDistance = distance;
                bestId = id;
            }
        }
        return best;
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
            message(BattleText.translatable(BattleText.KEY_ENEMY_JOINED, BattleText.name(mob)));
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
                journal.record("battle", BattleText.translatable(BattleText.KEY_ENEMY_JOIN_ROLLED_BACK));
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

    /**
     * The companion the connected player is allowed to command right now, or null.
     *
     * <p>Nothing in a payload names the actor: the requester comes from the connection, must be this exact
     * member instance, and the acting slot must be a companion that resolves to this requester - first from
     * the control record made at recruitment, then again from the adapter's live controller answer, so a
     * companion that changed hands mid-battle stops answering to its previous owner. Every other gate the
     * player's own orders pass (live session stamp, turn epoch, alive, this level, nothing in flight) is
     * re-applied here, so a companion order can never be cheaper than an own-body order.</p>
     */
    private EnemyState authorizeCompanion(ServerPlayer requester, ActionStamp stamp) {
        if (finished || ending || requester == null || stamp == null) return null;
        if (!session.accepts(stamp)) return null;
        PlayerMember member = members.get(requester.getUUID());
        if (member == null || member.entity != requester) return null;
        if (!requester.isAlive() || requester.isRemoved() || requester.level() != level) return null;
        if (phase != BattlePhase.COMPANION_TURN) return null;
        EnemyState state = actingCompanion();
        if (state == null || !requester.getUUID().equals(state.companionOwner)) return null;
        // Liveness is checked here, not left to the next battle tick: a dead, removed or dimension-changed
        // companion must refuse this order instead of charging for it.
        Mob pet = state.mob;
        if (!pet.isAlive() || pet.isRemoved() || pet.level() != level) return null;
        if (isBusy()) return null;
        // live re-resolution: the adapter owns "who controls this mob", the battle only holds the record
        CompanionAdapter adapter = state.companionAdapter;
        if (adapter == null || !adapter.supports(state.mob)) return null;
        return requester.getUUID().equals(adapter.controllerId(state.mob)) ? state : null;
    }

    /** The companion that owns the acting slot, or null while the slot belongs to a player or a hostile. */
    private EnemyState actingCompanion() {
        String current = queue.current();
        if (current == null) return null;
        EnemyState state = enemies.get(UUID.fromString(current));
        if (state == null || state.companionAdapter == null || state.side != BattleSide.PLAYER) return null;
        // The projection and every order resolve through this: a stale record for a dead or unloaded unit
        // must never become "the actor this player commands".
        Mob pet = state.mob;
        if (!pet.isAlive() || pet.isRemoved() || pet.level() != level) return null;
        return state;
    }

    public boolean tryMove(ServerPlayer actor, ActionStamp stamp, GridPos target) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) return tryMove(member, target);
        EnemyState companion = authorizeCompanion(actor, stamp);
        return companion != null && companionMove(companion, target);
    }

    private boolean tryMove(PlayerMember member, GridPos target) {
        PlayerParticipant state = member.state;
        if (!zone.contains(target) || occupied(target) || !LevelGridWorld.isStandable(level,target.x(),target.y(),target.z())) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_MOVE_TARGET)); return false;
        }
        MovementRoutes.Options options = movementOptions(member, target);
        if (options.choice()) {
            PlayerParticipant.PendingMove offer = new PlayerParticipant.PendingMove(UUID.randomUUID(), state.cell(),
                    target, level.getGameTime()+1200, options.walk().size()*state.moveCost());
            state.pendingMove(offer);
            journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_OFFER,
                    offer.walkCost(), state.moveCost()));
            com.tactical.neoforge.network.ServerPackets.sendUncheckedType(member.entity,
                    new MoveChoiceOfferPacket(offer.token(),target,
                        offer.walkCost(),state.moveCost(),state.cell().y()-target.y()));
            return true;
        }
        if (!options.walk().isEmpty()) return startPlayerMove(member, options.walk(),false);
        if (!options.drop().isEmpty()) return startPlayerMove(member, options.drop(),true);
        reject(BattleText.translatable(BattleText.KEY_REJECT_NO_ROUTE));
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
        if (offer==null || !offer.token().equals(token)) { reject(BattleText.translatable(BattleText.KEY_REJECT_PENDING_MOVE)); return; }
        state.pendingMove(null);
        if(mode==-1) { journal.record("move",BattleText.translatable(BattleText.KEY_MOVE_CANCELLED)); return; }
        if(!canAct(member) || !offer.from().equals(state.cell()) || level.getGameTime()>offer.expires()
                || (mode!=0 && mode!=1)) { reject(BattleText.translatable(BattleText.KEY_REJECT_MOVE_STALE)); return; }
        // Terrain, occupied cells, AP and zone may all change while the choice is visible.
        MovementRoutes.Options current=movementOptions(member,offer.target());
        List<GridPos> path=mode==1?current.drop():current.walk();
        if(path.isEmpty()) { reject(BattleText.translatable(BattleText.KEY_REJECT_ROUTE_GONE)); return; }
        if(mode==0 && path.size()*state.moveCost()!=offer.walkCost()) { reject(BattleText.translatable(BattleText.KEY_REJECT_ROUTE_COST)); return; }
        startPlayerMove(member,path,mode==1);
    }

    /** Legacy single-member entry point for internal fixtures; network paths must use the stamped form. */
    public void chooseMove(UUID token,int mode) {
        PlayerMember acting = actingMember();
        if (acting == null) return;
        PlayerParticipant.PendingMove offer = acting.state.pendingMove();
        if (offer == null || !offer.token().equals(token)) { reject(BattleText.translatable(BattleText.KEY_REJECT_PENDING_MOVE)); return; }
        acting.state.pendingMove(null);
        if (mode == -1) { journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_CANCELLED)); return; }
        if (!canAct(acting) || !offer.from().equals(acting.state.cell()) || level.getGameTime() > offer.expires()
                || (mode != 0 && mode != 1)) { reject(BattleText.translatable(BattleText.KEY_REJECT_MOVE_STALE)); return; }
        MovementRoutes.Options current = movementOptions(acting, offer.target());
        List<GridPos> path = mode == 1 ? current.drop() : current.walk();
        if (path.isEmpty()) { reject(BattleText.translatable(BattleText.KEY_REJECT_ROUTE_GONE)); return; }
        if (mode == 0 && path.size() * acting.state.moveCost() != offer.walkCost()) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_ROUTE_COST)); return;
        }
        startPlayerMove(acting, path, mode == 1);
    }

    private boolean startPlayerMove(PlayerMember member, List<GridPos> path, boolean drop) {
        PlayerParticipant state = member.state;
        int unit = state.moveCost();
        int cost = path.size() * unit;
        if(path.isEmpty()||cost>state.ap()) { reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH)); return false; }
        state.pendingMove(null);
        int dropTicks = Math.max(8,(state.cell().y()-path.get(0).y())*3);
        state.ap(state.ap()-cost);
        state.beginMove(path, drop, stepTicks, dropTicks);
        actionCount++;
        journal.record("move", BattleText.translatable(
                drop ? BattleText.KEY_MOVE_DROP : BattleText.KEY_MOVE_WALK,
                BattleText.name(member.name()),
                // Grid coordinates are data, not wording: they stay literal and identical in both languages.
                BattleText.literal(state.cell().toString()),
                BattleText.literal(path.get(path.size()-1).toString()), cost));
        sendState(); sendPlayerMotion(member, path);
        return true;
    }

    public void tryAttack(ServerPlayer actor, ActionStamp stamp, UUID targetId) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) {
            tryAttack(member, targetId);
            return;
        }
        EnemyState companion = authorizeCompanion(actor, stamp);
        if (companion != null) companionAttack(companion, targetId);
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
            reject(BattleText.translatable(BattleText.KEY_REJECT_MELEE_RANGE));
            return;
        }
        int cost = currentMeleeCost(member);
        if (state.ap() < cost) {
            reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        if (!hasLineOfSight(state.cell(), enemy.cell, member.entity)) { reject(BattleText.translatable(BattleText.KEY_REJECT_BLOCKED)); return; }
        faceDirection(member.entity, enemy.cell);
        sendPlayerMotion(member, List.of());
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_MELEE,
                BattleText.name(member.name()), BattleText.name(enemy.mob)));
        member.entity.swing(InteractionHand.MAIN_HAND, true);
        if (!NativeAttackReceipt.run(member.entity, enemy.mob, () -> member.entity.attack(enemy.mob))) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_MELEE_NO_EFFECT)); return;
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
            // The distance is formatted on the argument side, so the template carries no conversion.
            reject(BattleText.translatable(BattleText.KEY_REJECT_OUT_OF_RANGE,
                    BattleText.whole(distance), range));
            return;
        }
        // Facing never gates a ranged intent. Collision is checked along its ballistic arc below.
        int cost = switch (kind) { case 2 -> currentCrossbowCost(member); default -> currentBowCost(member); };
        if (state.ap() < cost) {
            reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        ItemStack weapon = player.getMainHandItem();
        boolean loaded = kind == 2 && CrossbowItem.isCharged(weapon);
        if (!loaded && player.getProjectile(weapon).isEmpty()) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_NO_AMMO));
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
            reject(BattleText.translatable(BattleText.KEY_REJECT_NO_BALLISTIC));
            sendState();return;
        }
        int count = projectiles.capture(player,plan,() -> fireRanged(member, enemy, weapon, kind,plan));
        if (count <= 0) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_SHOT_NOT_FIRED));
            return;
        }
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_RANGED,
                BattleText.name(member.name()),
                BattleText.translatable(kind == 2 ? BattleText.KEY_WEAPON_CROSSBOW : BattleText.KEY_WEAPON_BOW),
                BattleText.name(enemy.mob)));
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
        EnemyState companion = authorizeCompanion(actor, stamp);
        if (companion != null) {
            companionDefend(companion, Math.round(companion.mob.getYRot()));
            return;
        }
        tryDefendFacing(actor, stamp, Math.round(actor == null ? 0F : actor.getYRot()));
    }

    public void tryDefendFacing(ServerPlayer actor, ActionStamp stamp, int yaw) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) {
            tryDefendFacing(member, yaw);
            return;
        }
        EnemyState companion = authorizeCompanion(actor, stamp);
        if (companion != null) companionDefend(companion, yaw);
    }

    private void tryDefendFacing(PlayerMember member, int yaw) {
        PlayerParticipant state = member.state;
        if (state.ap()<DEFEND_COST){reject(BattleText.translatable(BattleText.KEY_REJECT_DEFEND_AP, DEFEND_COST));sendState();return;}
        if(yaw!=0&&yaw!=90&&yaw!=180&&yaw!=270){reject(BattleText.translatable(BattleText.KEY_REJECT_DEFEND_FACING));return;}
        ServerPlayer player = member.entity;
        setActorFacing(player, yaw);
        state.ap(state.ap()-DEFEND_COST);state.defending(true);actionCount++;sendPlayerMotion(member, List.of());
        journal.record("block", BattleText.translatable(BattleText.KEY_BLOCK,
                BattleText.translatable(hasShield(member) ? BattleText.KEY_BLOCK_SHIELD : BattleText.KEY_BLOCK_STANCE),
                DEFEND_COST, BattleText.literal(Integer.toString(yaw))));
        advanceTurn();
    }

    /** Write one absolute facing onto any actor, including its interpolation origins. */
    private static void setActorFacing(LivingEntity actor, int yaw) {
        actor.setYRot(yaw);
        actor.setYHeadRot(yaw);
        actor.setYBodyRot(yaw);
        actor.yRotO = actor.yHeadRotO = actor.yBodyRotO = yaw;
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

    /** The guard of any actor, so one participant's stance never protects another. */
    public boolean isDefending(LivingEntity actor) {
        if (actor == null || finished) return false;
        if (actor instanceof ServerPlayer player) return isDefending(player);
        EnemyState state = enemies.get(actor.getUUID());
        return state != null && state.mob == actor && state.defending;
    }

    /** Narrower guard factor for a shield holder, wider for a plain stance. */
    public static double guardFactor(boolean shield, boolean guarding) {
        if (!guarding) return 1.0D;
        return shield ? DEFEND_SHIELD_FACTOR : DEFEND_STANCE_FACTOR;
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

    /** True when this actor holds a shield in its off hand; a companion's own off hand counts too. */
    public boolean hasShield(LivingEntity actor) {
        if (actor == null) return false;
        if (actor instanceof ServerPlayer player) {
            PlayerMember member = member(player);
            return member != null && hasShield(member);
        }
        return isCompanion(actor)
                && actor.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
    }

    private boolean hasShield(PlayerMember member) {
        return member.entity.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
    }

    /** Place one block from the chosen inventory slot at the clicked position. */
    public void placeBlock(ServerPlayer actor, ActionStamp stamp, int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) placeBlock(member, slot, pos, face, hitLocation);
    }

    /**
     * Place one block through the target version's own placement chain.
     *
     * <p>{@code ItemStack.useOn} is the same entry the vanilla server uses: it runs the placement through
     * {@code CommonHooks.onPlaceItemIntoWorld}, which asks whether this player may build here, captures
     * the block snapshot, fires the place event and rolls the world back when a listener cancels it.
     * Calling {@code BlockItem.place} directly - what this method used to do - skipped all of that, so a
     * protected position was not protected for placement while it was for breaking.</p>
     *
     * <p>Every gate the click used to have is still checked here, in the same order, and the extra ones a
     * block action needs are added: the world border and the build height. Nothing is charged unless the
     * world really changed.</p>
     */
    private void placeBlock(PlayerMember member, int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentPlaceCost(member);
        if (state.ap() < cost) {
            message(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        if(slot<0||slot>8||slot!=player.getInventory().selected) { reject(BattleText.translatable(BattleText.KEY_REJECT_HELD_ITEM_CHANGED));return; }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            message(BattleText.translatable(BattleText.KEY_INFO_NOT_A_BLOCK));
            return;
        }
        // The hit the client reports builds the placement context, so it is input: a finite point that
        // really lies on the face it names.
        if (!legalHit(hitLocation, pos, face)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_PLACE_HIT));
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > BLOCK_REACH_SQR) {
            message(BattleText.translatable(BattleText.KEY_INFO_TOO_FAR));
            return;
        }
        if(!zone.contains(new GridPos(pos.getX(),pos.getY(),pos.getZ()))) {reject(BattleText.translatable(BattleText.KEY_REJECT_PLACE_OUTSIDE));return;}
        if (!legalBuildTarget(player, pos)) { reject(BattleText.translatable(BattleText.KEY_REJECT_BUILD_LIMIT)); return; }
        if (!blockSight(state.cell(), pos, hitLocation, player)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_SIGHT));
            return;
        }
        BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(hitLocation, face, pos, false));
        // The anchor is not the destination: a replaceable clicked block is placed into, anything else is
        // placed against, and an item may move the context again. The cell the block will really occupy is
        // the one the battle rules must accept - an anchor inside the zone with the block landing outside
        // it is exactly what this refuses.
        BlockPlaceContext resolved;
        try {
            resolved = blockItem.updatePlacementContext(context);
        } catch (RuntimeException unsupported) {
            resolved = null;
        }
        if (resolved == null || !legalPlacementCell(player, pos, resolved.getClickedPos())) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_PLACE_DEST));
            return;
        }
        // A single context is checked above; anything the item does beyond it - a second snapshot of a
        // bed or a door, a chained context update - is checked inside the native place event, whose
        // cancellation rolls the world and the item back instead of deleting blocks afterwards.
        InteractionResult placement = BattleToolUse.run(player,
                () -> BlockPlaceGuard.run(position -> legalPlacementCell(player, pos, position),
                        () -> stack.useOn(context)));
        if (placement.consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND, true);
            state.commitPaidAction(cost);
            actionCount++;
            level.playSound(null, pos, blockItem.getBlock().defaultBlockState().getSoundType().getPlaceSound(),
                    SoundSource.PLAYERS, 1.0F, 1.0F);
            beginTerrainFallIfNeeded();
            sendState();
        } else {
            message(BattleText.translatable(BattleText.KEY_INFO_CANNOT_PLACE));
        }
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void placeBlock(int slot, BlockPos pos, Direction face, Vec3 hitLocation) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        placeBlock(acting, slot, pos, face, hitLocation);
    }

    // ------------------------------------------------------------------
    // breaking one block
    // ------------------------------------------------------------------

    /**
     * Break the block the actor points at, with whatever the main hand holds.
     *
     * <p>The native {@code ServerPlayerGameMode.destroyBlock} does the destruction itself: it fires the
     * break event, refuses protected and restricted positions, applies the tool's durability and the
     * tool-destroyed event, and lets the block drop its own loot through {@code playerDestroy}. Nothing
     * here copies loot or guesses durability - what the vanilla call does not check is added as an
     * explicit gate in front of it (reach, build limits, world border, spawn protection, line of sight,
     * difficulty of the block, tool suitability), and nothing is charged unless the world really
     * changed.</p>
     */
    public void breakBlock(ServerPlayer actor, ActionStamp stamp, int slot, BlockPos pos) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) breakBlock(member, slot, pos);
    }

    private void breakBlock(PlayerMember member, int slot, BlockPos pos) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentBreakCost(member);
        if (state.ap() < cost) {
            message(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        // The client's claimed hotbar slot must still be the real one: the price, the tool and the
        // durability all belong to whatever is really held when the server handles the intent.
        if (slot < 0 || slot > 8 || slot != player.getInventory().selected) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_HELD_ITEM_CHANGED));
            return;
        }
        // The block command places while a block is held. A break intent that arrives with a block in
        // the same slot is a stale mode, and the server - not the client's per-frame refresh - is what
        // refuses it: otherwise the old mode would become a second, unpriced way to break.
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_HELD_BLOCK));
            return;
        }
        if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > BLOCK_REACH_SQR) {
            message(BattleText.translatable(BattleText.KEY_INFO_TOO_FAR));
            return;
        }
        GridPos cell = new GridPos(pos.getX(), pos.getY(), pos.getZ());
        if (!zone.contains(cell)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_OUTSIDE));
            return;
        }
        if (!legalBuildTarget(player, pos)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BUILD_LIMIT));
            return;
        }
        BlockState target = level.getBlockState(pos);
        if (target.isAir() || target.liquid()) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_TARGET));
            return;
        }
        if (target.getDestroySpeed(level, pos) < 0.0F) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_UNBREAKABLE));
            return;
        }
        // "A block that needs the right tool" is a property of the block, not of the drop: a block that
        // can be collected bare-handed stays collectable bare-handed, and one that cannot refuses the
        // attempt instead of breaking silently without its drop.
        ItemStack tool = player.getMainHandItem();
        if (target.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(target)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_TOOL));
            return;
        }
        if (!blockSight(state.cell(), pos, nearestVisiblePoint(state.cell(), pos), player)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_SIGHT));
            return;
        }
        // Spawn protection and every other may-interact rule are not part of destroyBlock, so they are
        // asked here; a refusal costs nothing.
        if (!level.mayInteract(player, pos)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_PROTECTED));
            return;
        }
        // Everything that stands on this block must have somewhere legal to land inside this zone
        // before the block goes away; otherwise the unit would be dropped out of the battle.
        if (!precheckLosingSupport(pos)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BREAK_NO_LANDING));
            return;
        }
        boolean destroyed = BattleToolUse.run(player, () -> player.gameMode.destroyBlock(pos));
        // destroyBlock reports true even when the block itself refused to go (onDestroyedByPlayer), so
        // the world is the authority: if the state at that position is still the same one, nothing was
        // broken and nothing may be charged.
        if (!destroyed || level.getBlockState(pos) == target) {
            message(BattleText.translatable(BattleText.KEY_INFO_CANNOT_BREAK));
            return;
        }
        player.swing(InteractionHand.MAIN_HAND, true);
        state.commitPaidAction(cost);
        actionCount++;
        beginTerrainFallIfNeeded();
        sendState();
    }

    /** Legacy fixture entry point bound to the acting player. */
    public void breakBlock(int slot, BlockPos pos) {
        PlayerMember acting = actingMember();
        if (acting == null || !canAct(acting)) return;
        breakBlock(acting, slot, pos);
    }

    /** Both block commands use one tactical reach, measured from the actor to the block's centre. */
    private static final double BLOCK_REACH_SQR = 256.0D;

    /**
     * The gates the native placement and break paths do not apply themselves: the world border, the
     * build height and the may-interact rules of this level (spawn protection).
     */
    private boolean legalBuildTarget(ServerPlayer player, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(pos)) return false;
        return level.mayInteract(player, pos);
    }

    /**
     * Whether this battle allows a placed block to occupy exactly this cell.
     *
     * <p>It is asked of the destination - the cell the block will really fill - and again from the native
     * place event for every snapshot of a multi-block placement, so a rule cannot be satisfied by the
     * clicked block while the block itself lands somewhere else.</p>
     */
    private boolean legalPlacementCell(ServerPlayer player, BlockPos anchor, BlockPos destination) {
        if (destination == null || anchor == null) return false;
        // A placement may only touch the clicked block's own neighbourhood: an item that wants to write
        // somewhere else in the world is not this command's business.
        if (destination.distManhattan(anchor) > 1) return false;
        if (!zone.contains(new GridPos(destination.getX(), destination.getY(), destination.getZ()))) return false;
        if (player.distanceToSqr(destination.getX() + 0.5D, destination.getY() + 0.5D,
                destination.getZ() + 0.5D) > BLOCK_REACH_SQR) return false;
        return legalBuildTarget(player, destination);
    }

    /**
     * The reported hit point must be finite and lie on the target's real outline surface.
     *
     * <p>Vanilla builds the placement context from it, so a point far away, NaN or off the block would
     * let the client choose a cell other than the one it highlighted. The surface is the state's own
     * shape - the same outline the client's pick uses - and never an assumed unit cube: a slab's top is
     * half a block up, a stair's tread lives inside the cell, and soil heights differ per block.</p>
     *
     * <p>The reported face only has to be one face of that shape at that point. It is the isometric
     * camera's pick, not the actor's eye ray, so requiring the two to name the same face would refuse
     * legal side and high placements; the independent check is {@link #blockSight}, which asks whether a
     * block is the first thing the actor's eye meets.</p>
     */
    private boolean legalHit(Vec3 hit, BlockPos pos, Direction face) {
        if (hit == null || face == null || pos == null) return false;
        if (!Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)) return false;
        // A state's shape is written in its own block space, so it is moved into the world before the hit
        // point - which is a world position - is compared with it. Comparing the two spaces directly made
        // every placement of a full cube fail: the top face is 1.0 locally and 80.0 for a block at y=79.
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos)
                .move(pos.getX(), pos.getY(), pos.getZ());
        if (shape.isEmpty()) return false;
        // The camera's ray lands on a shape surface, so the tolerance only absorbs float rounding.
        double tolerance = 0.02D;
        for (AABB box : shape.toAabbs()) {
            if (!box.inflate(tolerance).contains(hit)) continue;
            double plane = switch (face) {
                case UP -> box.maxY;
                case DOWN -> box.minY;
                case EAST -> box.maxX;
                case WEST -> box.minX;
                case SOUTH -> box.maxZ;
                case NORTH -> box.minZ;
            };
            double value = switch (face) {
                case UP, DOWN -> hit.y;
                case EAST, WEST -> hit.x;
                case SOUTH, NORTH -> hit.z;
            };
            if (Math.abs(value - plane) <= tolerance) return true;
        }
        return false;
    }

    /**
     * Whether a block is the first thing the actor's eye meets.
     *
     * <p>It is the block commands' sight test, and deliberately its own: the entity sight test aims at a
     * one-block-high end point, which refuses a block the actor can plainly see from the side and accepts a
     * ray that ends early. Here the ray runs to the block's centre and the first collision must be the
     * target itself - the same block the client highlighted - so a side or high face is visible while
     * anything standing in front of it is not. The attack and AI sight tests are untouched.</p>
     */
    private boolean blockSight(GridPos from, BlockPos target, Vec3 aim, LivingEntity viewer) {
        Vec3 start = eyeOf(from);
        // The aim point lies on the target's own surface, so it is nudged just inside before the ray runs:
        // a ray that stops exactly on a face can be reported as the neighbouring block on a shared edge.
        Vec3 inside = aim.add(Vec3.atCenterOf(target).subtract(aim).scale(1.0E-3D));
        BlockHitResult hit = level.clip(new ClipContext(start, inside, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    /** Where the actor's eyes are for a block sight test. */
    private static Vec3 eyeOf(GridPos from) {
        return new Vec3(from.x() + 0.5D, from.y() + 1.5D, from.z() + 0.5D);
    }

    /**
     * The part of the target nearest the actor's eyes, i.e. the piece of it that is actually visible.
     *
     * <p>Aiming at the block's centre is too strict for a block set into a floor or a wall: the ray to the
     * centre of a floor block two cells away grazes the neighbouring block's top edge and the target looks
     * hidden. Any visible part counts, so the aim is clamped onto the shape's own box; when the eyes are
     * already inside that box there is nothing to aim at but the centre.</p>
     */
    private Vec3 nearestVisiblePoint(GridPos from, BlockPos target) {
        Vec3 eye = eyeOf(from);
        VoxelShape shape = level.getBlockState(target).getShape(level, target)
                .move(target.getX(), target.getY(), target.getZ());
        if (shape.isEmpty()) return Vec3.atCenterOf(target);
        AABB box = shape.bounds();
        Vec3 clamped = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        return clamped.distanceToSqr(eye) < 1.0E-6D ? Vec3.atCenterOf(target) : clamped;
    }

    /** Use one inventory item (food, potions, or the item's own use action). */
    public void useItem(ServerPlayer actor, ActionStamp stamp, int slot) {
        useItem(actor, stamp, slot, null);
    }

    /**
     * Use one inventory item, either on the actor itself (no target) or on one entity.
     *
     * <p>The entity path is a separate branch, not a rewrite of the self use: it runs the native
     * {@code Player.interactOn} inside the already authorized item scope, so the item's own
     * {@code interactLivingEntity} decides whether it supports an entity at all. There is no path from
     * here to "give the target anything": a PASS, a cancelled event or a gone target spends nothing.</p>
     */
    public void useItem(ServerPlayer actor, ActionStamp stamp, int slot, java.util.UUID target) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member == null) return;
        if (target == null) useItem(member, slot);
        else useItemOn(member, slot, target);
    }

    /** Padding the vanilla interact packet uses when it measures the reach of an entity interaction. */
    private static final double ENTITY_INTERACT_PADDING = 1.0D;

    /**
     * The entity reach this player has <em>without</em> the encounter's own extension.
     *
     * <p>An encounter widens {@code ENTITY_INTERACTION_RANGE} so grid cells can be clicked far away.
     * That number says nothing about how far an item can be handed to someone, so the item branch
     * subtracts the encounter's own modifier and keeps the native range (3, or 5 in creative) plus the
     * one block padding the vanilla interact packet allows.</p>
     */
    private static boolean withinItemReach(ServerPlayer player, Entity target) {
        var instance = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE);
        if (instance == null) return false;
        double reach = instance.getValue();
        for (var modifier : instance.getModifiers()) {
            if (REACH_MODIFIER.equals(modifier.id())
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                reach -= modifier.amount();
            }
        }
        double allowed = Math.max(0.0D, reach) + ENTITY_INTERACT_PADDING;
        return target.getBoundingBox().distanceToSqr(player.getEyePosition()) < allowed * allowed;
    }

    private void useItemOn(PlayerMember member, int slot, java.util.UUID targetId) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentItemCost(member);
        if (state.ap() < cost) {
            message(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        if(slot<0||slot>8||slot!=player.getInventory().selected) {reject(BattleText.translatable(BattleText.KEY_REJECT_HELD_ITEM_CHANGED));return;}
        ItemStack stack=player.getMainHandItem();
        if(stack.isEmpty()) {reject(BattleText.translatable(BattleText.KEY_REJECT_NO_ITEM));return;}
        if(stack.getItem() instanceof BowItem||stack.getItem() instanceof CrossbowItem||stack.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_WEAPON_NOT_ITEM));return;
        }
        if(stack.getItem() instanceof BlockItem) {
            // Placing is the block command's job; the item entry must not remain a second, unpriced path
            // to the same world change.
            reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_IS_BLOCK));return;
        }
        Entity target = targetId == null ? null : level.getEntity(targetId);
        if (target == null) { reject(BattleText.translatable(BattleText.KEY_REJECT_TARGET_GONE));return; }
        ServerBattle owner = BattleManager.containing(target);
        boolean sameLevel = target.level() == level;
        // Only this encounter's own participants: a bystander inside the zone, a unit of another fight or
        // a unit that already left is not an item target.
        boolean inThisBattle = owner == this;
        ItemTargetRule.Denial refusal = ItemTargetRule.refusal(sameLevel, target.isAlive(), target.isRemoved(), target == player,
                owner != null && owner != this,
                !inThisBattle,
                target instanceof ServerPlayer other && !isMember(other),
                // The reach of an item hand-over is the native entity interaction range, never the
                // battle's extended pick range and never the weapon's range.
                sameLevel && withinItemReach(player, target),
                sameLevel && player.hasLineOfSight(target));
        // The rule layer answers with a stable code; the wording is created here, at the display boundary.
        if (refusal != ItemTargetRule.Denial.NONE) { reject(BattleText.itemRefusal(refusal));return; }
        if (player.getMainHandItem().getItem() instanceof BlockItem) {
            // The item command no longer places blocks, so a block can never be a use-on-entity target
            // either: this is the server-side half of the entry split, not a client courtesy.
            reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_IS_BLOCK));return;
        }
        net.minecraft.world.inventory.AbstractContainerMenu menuBefore = player.containerMenu;
        NativeEntityUseReceipt.Outcome outcome = NativeEntityUseReceipt.run(player, target, InteractionHand.MAIN_HAND,
                () -> BattleToolUse.run(player, () -> player.interactOn(target, InteractionHand.MAIN_HAND)));
        // A native entity interaction may try to open its own menu. Battle permissions never covered a
        // trade or storage screen, so one that appeared is closed again and the action is refused: the
        // item scope is not a way to escape into vanilla menus. The close goes through the server's own
        // path (removed() + transferState + close packet), never by swapping the menu field by hand.
        if (player.containerMenu != menuBefore) {
            // ServerPlayer.closeContainer() also tells the client; a headless fixture player has no real
            // connection and uses the same close without the packet.
            if (player.connection != null) player.closeContainer();
            else player.doCloseContainer();
            player.inventoryMenu.broadcastChanges();
            reject(BattleText.translatable(BattleText.KEY_REJECT_MENU_ESCAPE));return;
        }
        if (outcome.cancelled()) { reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_CANCELLED));return; }
        if (!outcome.committed()) { reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_UNSUPPORTED));return; }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.swing(InteractionHand.MAIN_HAND, true);
        state.commitPaidAction(cost);actionCount++;
        journal.record("inventory", BattleText.translatable(BattleText.KEY_ITEM_ON_TARGET,
                BattleText.name(member.name()), BattleText.name(target), cost));
        sendState();
    }

    private void useItem(PlayerMember member, int slot) {
        ServerPlayer player = member.entity;
        PlayerParticipant state = member.state;
        int cost = currentItemCost(member);
        if (state.ap() < cost) {
            message(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        if(slot<0||slot>8||slot!=player.getInventory().selected) {reject(BattleText.translatable(BattleText.KEY_REJECT_HELD_ITEM_CHANGED));return;}
        ItemStack stack=player.getMainHandItem();
        if(stack.isEmpty()) {reject(BattleText.translatable(BattleText.KEY_REJECT_NO_ITEM));return;}
        if(stack.getItem() instanceof BowItem||stack.getItem() instanceof CrossbowItem||stack.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_WEAPON_NOT_ITEM));return;
        }
        var result=BattleToolUse.run(player,()->stack.use(level,player,InteractionHand.MAIN_HAND));
        if(!result.getResult().consumesAction()) {reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_CANNOT_USE));return;}
        ItemStack next=result.getObject();
        if (stack.getUseDuration(player) > 0) {
            // A cancelled native Start event can still leave Item.use returning CONSUME.
            // It has not committed an item use and must not consume AP or escalation.
            if (!player.isUsingItem() || player.getUseItem() != stack) {
                reject(BattleText.translatable(BattleText.KEY_REJECT_ITEM_CANCELLED)); return;
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
        journal.record("inventory", BattleText.translatable(BattleText.KEY_ITEM_SELF,
                BattleText.name(member.name()), cost));
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
        if (member != null) {
            advanceTurn();
            return;
        }
        EnemyState companion = authorizeCompanion(actor, stamp);
        if (companion != null) companionEndTurn(companion, null);
    }

    /** Face the chosen direction before handing the turn over. */
    public void endPlayerTurnFacing(ServerPlayer actor, ActionStamp stamp, int yaw) {
        PlayerMember member = authorizeActing(actor, stamp);
        if (member != null) {
            endPlayerTurnFacing(member, yaw);
            return;
        }
        EnemyState companion = authorizeCompanion(actor, stamp);
        if (companion != null) companionEndTurn(companion, yaw);
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
            reject(BattleText.translatable(BattleText.KEY_REJECT_FLEE_AP));
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
            removeMember(member, BattleText.exitReason(BattleText.Exit.FLED));
            return true;
        }
        state.ap(0);
        message(BattleText.translatable(BattleText.KEY_INFO_FLEE_FAILED, Math.round(chance * 100.0D)));
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
            else { finish(BattleText.endReason(BattleText.End.PATH_BLOCKED)); return; }
            journal.record("reject",BattleText.translatable(BattleText.KEY_REJECT_PATH_BLOCKED));
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
                enemyStepping = false;
                if(enemy.flying){enemy.mob.setPos(AerialMovement.feet(enemy.cell,enemy.airOffset));enemy.mob.setDeltaMovement(Vec3.ZERO);}
                // A commanded companion never continues on its own: the controller decides whether to attack,
                // step again or hand the turn over. No AI follow-up and no automatic turn advance here.
                if (enemy.companionAdapter != null) {
                    enemyDiveAttackIndex = -1;
                    enemyDiveAttacked = false;
                    enemyDiveReservedCost = 0;
                    sendState();
                    return;
                }
                if(enemyDiveAttackIndex>=0){enemyDiveAttackIndex=-1;advanceTurn();}
                else if (!targetLost(enemy)) resolveAiAction(enemy,ai.afterMovement(aiContext(enemy)));
                else if (!replanAfterLostTarget(enemy)) advanceTurn();
                return;
            }
            GridPos stepTarget = enemyPath.get(enemyPathIndex);
            // The terrain this route was planned through may have changed under it: somebody closed the door
            // again, a block went up, a unit stepped in. Only a door-aware actor rechecks its ground steps here,
            // so no other ground AI changes; the steps it will not walk are given back and the turn ends
            // instead of walking through a solid cell.
            DoorRouteTerrain stepTerrain = doorTerrain(enemy);
            if (stepTerrain != null && !stepTerrain.canEnter(enemy.cell, stepTarget)) {
                int unused = enemyPath.size() - enemyPathIndex;
                enemy.ap = Math.min(enemy.maxAp, enemy.ap + unused * enemy.moveCost + enemyDiveReservedCost);
                enemyDiveReservedCost = 0;
                enemyMoving = false;
                enemyStepping = false;
                enemyPath = List.of();
                enemyDiveAttackIndex = -1;
                journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_ROUTE_BLOCKED,
                        BattleText.name(enemy.mob)));
                sendState();
                advanceTurn();
                return;
            }
            enemyStepTarget = stepTarget;
            enemyPathIndex++;
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
            journal.record("enemy",BattleText.translatable(BattleText.KEY_ENEMY_FLIGHT_BLOCKED,
                    BattleText.name(enemy.mob)));
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
                    if (performMelee(enemy, strikeContext.target())) enemy.paidActions++;
                    else enemy.ap=Math.min(enemy.maxAp,enemy.ap+reserved);
                } else {
                    enemy.ap=Math.min(enemy.maxAp,enemy.ap+reserved);
                    journal.record("enemy",BattleText.translatable(BattleText.KEY_ENEMY_DIVE_MISSED));
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

    /**
     * The entity this actor's action must hit: the planned lock while one exists, the legacy current opponent
     * when the turn never locked one.
     *
     * <p>A lock that was established and then lost answers {@code null} instead of quietly picking a new
     * victim, which is what keeps planning and execution on one UUID.</p>
     */
    private LivingEntity actionOpponent(EnemyState state) {
        LivingEntity locked = lockedOpponent(state);
        return locked == null && state.lockedTarget == null ? primaryOpponent(state.mob) : locked;
    }

    /** True when this turn really locked an opponent and that opponent is no longer usable. */
    private boolean targetLost(EnemyState state) {
        return state.lockedTarget != null && lockedOpponent(state) == null;
    }

    /**
     * The opponent this own turn was planned against, re-validated against the live world.
     *
     * <p>Planning and execution have to name the same entity: a plan that says "shoot A" may never resolve
     * into a fresh nearest-target lookup that shoots B. Anything that makes the locked entity illegal (death,
     * a removed participant, leaving the field or zone, no longer opposed) yields {@code null}, and the caller
     * may replan exactly once before the turn ends.</p>
     */
    private LivingEntity lockedOpponent(EnemyState state) {
        if (state.lockedTarget == null) return null;
        LivingEntity entity = participantEntity(state.lockedTarget);
        if (entity == null || entity == state.mob || !entity.isAlive() || entity.isRemoved()
                || entity.level() != level || !opposed(state.mob, entity)) return null;
        GridPos cell = participantCell(entity);
        return cell != null && zone.contains(cell) ? entity : null;
    }

    /**
     * Establish this own turn's opponent lock; false when the actor has no legal opponent at all.
     *
     * <p>The lock is one UUID for the whole turn: planning, the walk and the real strike all name it, and a
     * lost lock allows exactly one bounded replan.</p>
     */
    private boolean lockOpponent(EnemyState state) {
        LivingEntity next = reachableOpponentFirst(state);
        state.lockedTarget = next == null ? null : next.getUUID();
        return next != null;
    }

    /**
     * One bounded replan when the opponent a turn was planned against is gone.
     *
     * @return true when a new plan was already dispatched, so the caller must not also end the turn
     */
    private boolean replanAfterLostTarget(EnemyState state) {
        if (state.targetReplans >= 1 || finished) return false;
        LivingEntity next = reachableOpponentFirst(state);
        if (next == null) return false;
        state.targetReplans++;
        state.lockedTarget = next.getUUID();
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_RETARGET,
                BattleText.name(state.mob), BattleText.name(next)));
        resolveAiAction(state, ai.planTurn(aiContext(state)));
        return true;
    }

    /** A main action that must hit the locked opponent, so losing that opponent cannot be silently re-aimed. */
    private static boolean targetedAction(AiAction.Kind kind) {
        return kind == AiAction.Kind.MELEE || kind == AiAction.Kind.RANGED || kind == AiAction.Kind.SKILL
                || kind == AiAction.Kind.THROW_POTION || kind == AiAction.Kind.FIREBALL_VOLLEY;
    }

    /**
     * The ranged loadout this actor's own adapter fires, resolved against the adapter's declared weapon type.
     *
     * <p>An AI actor is only armed with the weapon its adapter can actually fire, in the hand the native
     * firing code will use. A caster without a registered adapter has nothing declared, so it falls back to the
     * item-driven rule the preview path always used and never gains a new ability here.</p>
     */
    private MobRangedLoadout mobLoadout(Mob actor) {
        if (actor == null) return null;
        TacticalMobAi adapter = ai.adapter(actor);
        RangedWeapon declared = adapter == null ? null : adapter.rangedWeapon();
        return declared == null ? MobRangedLoadout.of(actor) : MobRangedLoadout.of(declared, actor);
    }
    /**
     * Authoritative legality of this actor's real shot from a hypothetical feet cell.
     *
     * <p>The numbers come from the weapon that would fire it, so a crossbow is never validated on bow
     * parameters. Whether the loaded ammunition is supported is decided at the shot itself, where the refusal
     * can be recorded and the real charge kept: refusing here would let the actor wander around its own
     * unsupported rocket with no explanation.</p>
     */
    private boolean canShootFrom(EnemyState state, LivingEntity opponent, GridPos from) {
        if (opponent == null || from == null) return false;
        MobRangedLoadout loadout = mobLoadout(state.mob);
        if (loadout == null || !(state.mob instanceof net.minecraft.world.entity.monster.RangedAttackMob)) return false;
        if (loadout.weapon() == RangedWeapon.CROSSBOW
                && !(state.mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob)) return false;
        GridPos target = participantCell(opponent);
        if (target == null) return false;
        ItemStack weapon = loadout.stack(state.mob);
        ItemStack ammo = ammoFor(state.mob, loadout, weapon);
        return Math.hypot(from.x()-target.x(), from.z()-target.z()) <= 16.001
                && BallisticShot.plan(level, state.mob, from, opponent, loadout.weapon(),
                        piercingOf(weapon, ammo)) != null;
    }

    /**
     * One native mob melee against an explicitly chosen target.
     *
     * <p>The automatic AI passes its own primary opponent, a commanded companion passes the participant its
     * controller picked; the damage exit, the sound, the swing, the journal row and the attribution are the
     * same vanilla ones either way. Returns false when nothing was hurt, so the caller can refuse the action
     * instead of charging for it.</p>
     */
    private boolean performMelee(EnemyState enemy, LivingEntity target) {
        if (target == null || !opposed(enemy.mob, target)
                || target.level() != level || !target.isAlive() || target.isRemoved()
                || enemy.mob.getAttribute(Attributes.ATTACK_DAMAGE)==null) return false;
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(enemy.mob, targetCell);
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_MELEE,
                BattleText.name(enemy.mob), BattleText.name(target)));
        enemy.mob.swing(InteractionHand.MAIN_HAND);
        enemy.mob.setTarget(target);
        return enemy.mob.doHurtTarget(target);
    }

    /** Legacy name: attacks this actor's locked opponent, never its allied owner. */
    private void attackPlayer(EnemyState enemy) {
        int cost = mobMeleeCost(enemy);
        AiContext context = aiContext(enemy);
        if (context.target() == null || enemy.ap < cost || !context.inMeleeRange(enemy.cell, context.targetCell())
                || !context.hasLineOfSight(enemy.cell, context.targetCell())) return;
        if (performMelee(enemy, context.target())) { enemy.ap -= cost; enemy.paidActions++; }
    }

    // ------------------------------------------------------------------
    // wooden doors: route snapshot and the one real break
    // ------------------------------------------------------------------

    /** Local door-route search budget. It is bounded, local and never force-loads a chunk. */
    private static final int DOOR_ROUTE_BUDGET = 2048;
    /** Fixture-only budget override; 0 keeps the shipped budget. */
    private static int fixtureDoorRouteBudget;

    /**
     * Fixture-only: shrink (or restore with 0) the door-route budget.
     *
     * <p>The undecided branch - "the search ran out of budget, so nothing is proven" - cannot be reached on a
     * small fixture map without a fake, because a real battle zone always fits inside the shipped budget. This
     * is the same kind of fixture-only hook as the injected admission faults, and it never changes behaviour
     * unless a fixture asks for it.</p>
     */
    static void fixtureDoorRouteBudget(int budget) { fixtureDoorRouteBudget = Math.max(0, budget); }

    private static int doorRouteBudget() {
        return fixtureDoorRouteBudget > 0 ? fixtureDoorRouteBudget : DOOR_ROUTE_BUDGET;
    }

    /**
     * Fixture-only: make the real removal call fail, so the transactional recovery can be exercised.
     *
     * <p>A door never refuses removal by itself, so the "removal failed after one half was already cleared"
     * path has no natural trigger. Like the injected admission faults, this changes nothing unless a fixture
     * asks for it, and the recovery it exercises is the shipped code.</p>
     */
    private static boolean fixtureRefuseDoorRemoval;

    /** Fixture-only: refuse (or allow, with false) the next real door removals. */
    static void fixtureRefuseDoorRemoval(boolean refuse) { fixtureRefuseDoorRemoval = refuse; }

    /**
     * The door-aware terrain, but only for an actor whose own adapter declares that it breaks doors.
     *
     * <p>Every other melee actor keeps the plain navigator, so no other creature gains a door adjacency. The
     * same view also decides what an <em>already open</em> door means, because the plain terrain adapter refuses
     * the thin leaf of an open door as well.</p>
     */
    private DoorRouteTerrain doorTerrain(EnemyState state) {
        if (state == null || state.flying) return null;
        TacticalMobAi adapter = ai.adapter(state.mob);
        if (adapter == null || !adapter.breaksDoors()) return null;
        return new DoorRouteTerrain(level, state.mob, zone::contains, this::occupied);
    }

    /**
     * Everything the door tactics need, read once from the same terrain the action is charged on.
     *
     * <p>The door-free route is asked first and always. The wooden-door route is only asked when the first
     * answer was a real "no way": a budget or loaded-area limit stays undecided and can never justify a break.
     * A route is AP-independent on purpose, so a detour this turn cannot finish still counts as a detour.</p>
     */
    private VindicatorRoute vindicatorRoute(EnemyState state) {
        LivingEntity opponent = actionOpponent(state);
        GridPos target = participantCell(opponent);
        DoorRouteTerrain terrain = doorTerrain(state);
        if (opponent == null || target == null || terrain == null) return VindicatorRoute.NONE;
        int budget = doorRouteBudget();
        DoorRoutePlanner.Route open = undecided(DoorRoutePlanner.open(terrain, state.cell,
                engagementCells(state, target, false), budget), terrain);
        DoorRoutePlanner.Route doors = open.status() == DoorRoutePlanner.Status.UNREACHABLE
                ? undecided(DoorRoutePlanner.throughDoors(terrain, state.cell,
                        engagementCells(state, target, true), budget), terrain)
                : DoorRoutePlanner.Route.UNREACHABLE;
        return new VindicatorRoute(open, doors, canBreakWoodenDoors(), mobDoorBreakCost(state));
    }

    /**
     * The cells from which this actor could really strike that opponent, in the world this search assumes.
     *
     * <p>A cell that is merely next to the opponent is not an engagement cell: a wall, a closed door or an
     * open leaf between the two cells blocks the swing, and planning to a cell the actor cannot actually hit
     * from would produce a route that ends in nothing. The open search therefore uses the real line of sight.
     * The door search uses a virtual line in which a closed wooden door is already gone - such a route is only
     * chosen when that door is the one this turn would break, and the real line of sight is asked again at the
     * moment the axe really swings.</p>
     */
    private Predicate<GridPos> engagementCells(EnemyState state, GridPos target, boolean throughDoors) {
        return cell -> !cell.equals(target) && !occupied(cell) && inMeleeRange(cell, target)
                && (throughDoors ? hasLineOfSightThroughDoors(cell, target, state.mob)
                                 : hasLineOfSight(cell, target, state.mob));
    }

    /**
     * Exact native sight in the virtual stance after removing the door the candidate cell stands in.
     * Doors elsewhere are not part of that stance and keep their real collision shapes.
     */
    private boolean hasLineOfSightThroughDoors(GridPos from, GridPos to, LivingEntity viewer) {
        java.util.Objects.requireNonNull(viewer, "line-of-sight viewer");
        Vec3 start = new Vec3(from.x() + 0.5D, from.y() + 1.5D, from.z() + 0.5D);
        Vec3 end = new Vec3(to.x() + 0.5D, to.y() + 1.0D, to.z() + 0.5D);
        // A sight query must not force-load an otherwise unknown corner chunk.
        for (int x = (int) Math.floor(Math.min(start.x, end.x)); x <= (int) Math.floor(Math.max(start.x, end.x)); x++) {
            for (int y = (int) Math.floor(Math.min(start.y, end.y)); y <= (int) Math.floor(Math.max(start.y, end.y)); y++) {
                for (int z = (int) Math.floor(Math.min(start.z, end.z)); z <= (int) Math.floor(Math.max(start.z, end.z)); z++) {
                    if (!level.isLoaded(new BlockPos(x, y, z))) return false;
                }
            }
        }
        BlockPos lower = blockOf(from);
        BlockPos upper = lower.above();
        if (!level.isLoaded(lower) || !level.isLoaded(upper)) return false;
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(upper);
        boolean ignoreCandidateDoor = isClosedWoodenDoor(lowerState, lower, DoubleBlockHalf.LOWER)
                && isClosedWoodenDoor(upperState, upper, DoubleBlockHalf.UPPER)
                && upperState.is(lowerState.getBlock());
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer) {
            @Override
            public net.minecraft.world.phys.shapes.VoxelShape getBlockShape(BlockState blockState,
                    net.minecraft.world.level.BlockGetter world, BlockPos pos) {
                if (ignoreCandidateDoor && (pos.equals(lower) || pos.equals(upper))) {
                    return net.minecraft.world.phys.shapes.Shapes.empty();
                }
                return super.getBlockShape(blockState, world, pos);
            }
        });
        return hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(start) >= end.distanceToSqr(start) - 0.05D;
    }

    /**
     * The same legal hostile pool {@link #primaryOpponent} draws from, nearest first with the UUID tie-break.
     *
     * <p>Only the hostile pool is exposed: a door-capable actor may prefer a reachable opponent over a nearer
     * one that is sealed away, and that is a question about this battle's opponents. The older friendly
     * target pool and the global aggro rules are not touched.</p>
     */
    private List<LivingEntity> hostileOpponentPool(Mob actor) {
        List<LivingEntity> pool = new ArrayList<>();
        for (PlayerMember member : members.values()) {
            ServerPlayer player = member.entity;
            if (!player.isAlive() || player.isRemoved() || player.level() != level) continue;
            PlayerParticipant state = memberState(player);
            if (state == null || !zone.contains(state.cell())) continue;
            if (!opposed(actor, player)) continue;
            pool.add(player);
        }
        for (EnemyState state : enemies.values()) {
            Mob mob = state.mob;
            if (state.companionAdapter == null || mob == actor) continue;
            if (!mob.isAlive() || mob.isRemoved() || mob.level() != level) continue;
            if (!zone.contains(state.cell)) continue;
            if (!opposed(actor, mob)) continue;
            pool.add(mob);
        }
        pool.sort(Comparator.comparingDouble((LivingEntity entity) -> entity.distanceToSqr(actor))
                .thenComparing(entity -> entity.getUUID().toString()));
        return pool;
    }

    /**
     * Within the same legal pool, take the nearest opponent that can really be reached and hit without
     * breaking anything; only then fall back to the nearest opponent at all.
     *
     * <p>This is the approved "a reachable opponent in the same pool wins over a nearer one behind a wall"
     * rule, and it is asked only of an actor whose adapter breaks doors - every other actor keeps the plain
     * nearest rule it always had. The order is distance then UUID, so the choice is stable and cannot
     * oscillate between two candidates at the same distance.</p>
     */
    private LivingEntity reachableOpponentFirst(EnemyState state) {
        LivingEntity nearest = primaryOpponent(state.mob);
        if (nearest == null || state.side != BattleSide.HOSTILE) return nearest;
        DoorRouteTerrain terrain = doorTerrain(state);
        if (terrain == null) return nearest;
        for (LivingEntity candidate : hostileOpponentPool(state.mob)) {
            GridPos target = participantCell(candidate);
            if (target == null) continue;
            if (DoorRoutePlanner.open(terrain, state.cell, engagementCells(state, target, false),
                    doorRouteBudget()).found()) {
                // The pool is nearest first, so the first reachable candidate is the nearest reachable one -
                // including the nearest opponent itself when it really can be reached.
                return candidate;
            }
        }
        return nearest;
    }

    /** An unloaded column is not a wall: the exhaustive search answer becomes "not decided". */
    private static DoorRoutePlanner.Route undecided(DoorRoutePlanner.Route route, DoorRouteTerrain terrain) {
        return route.status() == DoorRoutePlanner.Status.UNREACHABLE && terrain.sawUnloaded()
                ? DoorRoutePlanner.Route.LIMIT_REACHED : route;
    }

    /**
     * Encounter-level permission for a wooden door: ordinary or hard difficulty and mob griefing.
     *
     * <p>This is what the plan needs to know. The per-block rule, the entity-griefing event and the cancellable
     * break event are asked again at the break itself, where a refusal costs nothing.</p>
     */
    private boolean canBreakWoodenDoors() {
        Difficulty difficulty = level.getDifficulty();
        if (difficulty != Difficulty.NORMAL && difficulty != Difficulty.HARD) return false;
        return level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    /** One door pair is one object, so one break has exactly one price. */
    private int mobDoorBreakCost(EnemyState enemy) {
        return quote(intConfig(TacticalConfig.INSTANCE.breakApCost, ApCalculator.BREAK_COST), enemy.paidActions);
    }

    /**
     * Break one closed wooden door pair as this own turn's main action.
     *
     * <p>Order matters. Everything is re-checked against the live world after the plan was made: the same pair
     * is still there, still closed, still wooden, loaded and inside this battle; this actor still stands in
     * front of it; the real vanilla protection chain allows it; the events did not swap the door out from under
     * the removal; nothing that stands on the upper half would lose its footing. Only then do the two blocks go
     * away - as one object, with one drop - and only then is the price committed once. A refusal charges
     * nothing and keeps the remaining AP, and the turn always ends afterwards so nobody gets a free axe swing
     * through the new doorway.</p>
     *
     * <p>The vanilla chain is {@code CommonHooks.canEntityDestroy}: it already checks the loaded chunk, the
     * entity-griefing event, the block's own {@code canEntityDestroy} and the cancellable
     * {@code LivingDestroyBlockEvent}. Nothing is posted a second time here, and the player-only break event is
     * not borrowed as if it were entity protection.</p>
     */
    private boolean breakDoor(EnemyState state, DoorRoutePlanner.Door door) {
        if (door == null || !(state.mob instanceof Vindicator) || state.flying) return false;
        if (!isActorTurn(state.mob) || isBusy()) return false;
        int cost = mobDoorBreakCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        BlockPos lower = blockOf(door.lower());
        BlockPos upper = blockOf(door.upper());
        if (!zone.contains(door.lower()) || !zone.contains(door.upper())
                || !level.isLoaded(lower) || !level.isLoaded(upper)) return refuseDoorBreak(state);
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(upper);
        // The same pair, still closed, still one wooden door - read now, not from the plan.
        if (!isClosedWoodenDoor(lowerState, lower, DoubleBlockHalf.LOWER)
                || !isClosedWoodenDoor(upperState, upper, DoubleBlockHalf.UPPER)
                || !upperState.is(lowerState.getBlock())) return refuseDoorBreak(state);
        // The approved standing: the actor is in the cell right in front of that door, on its layer.
        DoorRouteTerrain terrain = doorTerrain(state);
        if (terrain == null || !terrain.standsInFrontOf(state.cell, door)
                || !terrain.canEnterThroughDoor(state.cell, door.lower(), door)) return refuseDoorBreak(state);
        if (!CommonHooks.canEntityDestroy(level, lower, state.mob)
                || !CommonHooks.canEntityDestroy(level, upper, state.mob)) return refuseDoorBreak(state);
        // A callback may have replaced either half; the pair this action priced has to still be the pair here.
        if (level.getBlockState(lower) != lowerState || level.getBlockState(upper) != upperState) {
            return refuseDoorBreak(state);
        }
        // Nothing may be dropped out of the battle: a unit can stand on the upper half, so the same support
        // gate the block commands use runs first - and it runs on the world where both halves are gone, which
        // is the object this action really removes.
        if (!precheckLosingSupport(lower, upper)) return refuseDoorBreak(state);

        // One object, one drop, one transaction. Removing either half with shape updates makes the other half's
        // own updateShape destroy it - and that nested destroy drops the door's loot a second time. So the upper
        // half is cleared with the shape switch off (clients are still told) and only the lower half goes
        // through the real destroy path: that path drops the door's own loot exactly once, plays the real break
        // particles and still publishes the block-destroy event, and the remaining neighbours of the pair are
        // updated there.
        level.setBlock(upper, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        boolean destroyed = !fixtureRefuseDoorRemoval && level.destroyBlock(lower, true, state.mob, 512);
        level.levelEvent(1021, lower, 0);
        // Success is the world's own answer, not one return value: both halves really gone, and no half left
        // behind. A refusal that already took one half away is repaired before giving up, so the object the
        // plan priced is never left half open.
        boolean gone = level.getBlockState(lower).isAir() && level.getBlockState(upper).isAir();
        if (!destroyed || !gone) {
            if (level.getBlockState(lower) == lowerState && level.getBlockState(upper).isAir()) {
                // Give back only the half this call cleared, and only while that position is still empty: an
                // external callback that put something else there keeps its own block, and nothing is dropped
                // a second time by this restore.
                level.setBlock(upper, upperState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            return refuseDoorBreak(state);
        }

        state.ap -= cost;
        state.paidActions++;
        journal.record("attack", BattleText.translatable(BattleText.KEY_ENEMY_BREAK_DOOR,
                BattleText.name(state.mob), BattleText.literal(door.lower().toString())));
        // The removal may have taken somebody's footing; the existing fall gate and the existing reachability
        // refresh do the rest on the real world.
        beginTerrainFallIfNeeded();
        sendState();
        return true;
    }

    /** A refused break changes nothing: no door, no price, no paid action. */
    private boolean refuseDoorBreak(EnemyState state) {
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_DOOR_REFUSED,
                BattleText.name(state.mob)));
        sendState();
        return false;
    }

    /** The same "one wooden door half" rule the terrain view uses, stated once for the removal path. */
    private static boolean isClosedWoodenDoor(BlockState blockState, BlockPos pos, DoubleBlockHalf half) {
        return blockState.getBlock() instanceof DoorBlock door && blockState.is(BlockTags.WOODEN_DOORS)
                && door.type().canOpenByHand() && !blockState.getValue(DoorBlock.OPEN)
                && blockState.getValue(DoorBlock.HALF) == half;
    }

    private static BlockPos blockOf(GridPos cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }


    /**
     * One native ranged main action, for the weapon this actor's own adapter fires.
     *
     * <p>The price is frozen before any native side effect. A shot that breaks the crossbow used to be
     * repriced at zero after the fact, which made a real shot free; the quote is read from the weapon the turn
     * really holds, before loading and before firing, and that same number is committed once a real projectile
     * exists.</p>
     *
     * <p>Loading and firing are one bounded transaction over the two hand stacks, the real charged
     * projectiles, the charge pose and the actor's own aim. Vanilla clears the charged projectiles and spends
     * durability before the projectile joins the world, so a cancelled join leaves real changes behind:
     * producing no projectile at all restores exactly those hand stacks and gives the actor no cost. Anything
     * that did produce a projectile is final: a later miss, block or cancelled hit refunds nothing.</p>
     */
    private void shootOpponent(EnemyState enemy) {
        MobRangedLoadout loadout = mobLoadout(enemy.mob);
        LivingEntity target = actionOpponent(enemy);
        if (loadout == null || target == null
                || !(enemy.mob instanceof net.minecraft.world.entity.monster.RangedAttackMob ranged)) return;
        if (loadout.weapon() == RangedWeapon.CROSSBOW
                && !(enemy.mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob)) return;
        // Frozen here, before the load and before the native fire: this is the price that will be committed.
        int cost = mobRangedCost(enemy);
        if (cost <= 0 || enemy.ap < cost) return;
        RangedTransaction transaction = RangedTransaction.begin(enemy.mob, loadout);
        if (loadout.weapon() == RangedWeapon.CROSSBOW) {
            ItemStack held = loadout.stack(enemy.mob);
            if (loadedFirework(held)) {
                // A loaded rocket is real ammunition this ruleset does not model. Refuse the shot and keep the
                // real load: firing it on arrow numbers would be a passing fixture with wrong physics.
                refuseShot(enemy, BattleText.KEY_ENEMY_ROCKET_UNSUPPORTED);
                return;
            }
            if (!CrossbowItem.isCharged(held)) {
                boolean loaded = false;
                try {
                    loaded = loadCrossbow(enemy.mob, loadout);
                } catch (RuntimeException failure) {
                    Tactical.LOGGER.error("[Tactical] native crossbow load failed for {}", enemy.mob.getUUID(), failure);
                }
                if (!loaded) {
                    transaction.restore();
                    refuseShot(enemy, BattleText.KEY_ENEMY_NO_SHOT);
                    return;
                }
            }
        }
        // The native load ran on the real stack: re-check that this action still points at the same opponent
        // and that the hand still holds the weapon this shot was planned for.
        if (actionOpponent(enemy) != target || !transaction.stillHoldsWeapon()) {
            transaction.restore();
            refuseShot(enemy, BattleText.KEY_ENEMY_NO_SHOT);
            return;
        }
        ItemStack weapon = loadout.stack(enemy.mob);
        ItemStack ammo = ammoFor(enemy.mob, loadout, weapon);
        BallisticShot.Plan shot = BallisticShot.plan(level, enemy.mob, enemy.cell, target,
                loadout.weapon(), piercingOf(weapon, ammo));
        if (shot == null) {
            transaction.restore();
            refuseShot(enemy, BattleText.KEY_ENEMY_NO_SHOT);
            return;
        }
        enemy.mob.setYRot(shot.yaw()); enemy.mob.setYHeadRot(shot.yaw()); enemy.mob.setYBodyRot(shot.yaw()); enemy.mob.setXRot(shot.pitch());
        enemy.mob.setTarget(target);
        Runnable fire = loadout.weapon() == RangedWeapon.CROSSBOW
                ? () -> ((net.minecraft.world.entity.monster.CrossbowAttackMob) ranged)
                        .performCrossbowAttack(enemy.mob, loadout.weapon().speed())
                : () -> ranged.performRangedAttack(target, 1.0F);
        // Catch inside the capture scope: a later callback can fail after an earlier salvo projectile joined.
        // Capture must still finish its authoritative spawned-entity count before deciding whether to refund.
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int fired = projectiles.capture(enemy.mob, shot, () -> {
            try {
                fire.run();
            } catch (RuntimeException failure) {
                nativeFailure[0] = failure;
            }
        });
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native ranged fire failed for {} after {} projectile(s)",
                    enemy.mob.getUUID(), fired, nativeFailure[0]);
        }
        if (fired <= 0) {
            transaction.restore();
            refuseShot(enemy, BattleText.KEY_ENEMY_NO_SHOT);
            return;
        }
        enemy.ap -= cost;
        enemy.paidActions++;
        pendingEnemyShot = enemy.mob.getUUID();
        awaitingShotResolution = true;
        shotRecoveryTicks = -1;
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_RANGED,
                BattleText.name(enemy.mob), BattleText.name(target)));
        sendState();
    }

    /** Record one ranged refusal as structure; the refusal itself changed nothing and costs nothing. */
    private void refuseShot(EnemyState enemy, String key) {
        journal.record("enemy", BattleText.translatable(key, BattleText.name(enemy.mob)));
        sendState();
    }

    // ------------------------------------------------------------------
    // blaze: one native salvo per own turn
    // ------------------------------------------------------------------

    /** A blaze is the one actor this ruleset gives a native fireball; never a superclass check. */
    private static boolean isBlazeActor(Mob mob) {
        return mob != null && mob.getType() == EntityType.BLAZE;
    }

    /**
     * Quoted price of this actor's next salvo, or 0 when it has no native fireball at all.
     *
     * <p>20 AP is the ruleset's own design value for one salvo and is configurable; the +25% per prior paid
     * action of this own turn is applied by the same quote every other main action uses.</p>
     */
    private int blazeVolleyCost(EnemyState enemy) {
        if (enemy == null || !isBlazeActor(enemy.mob)) return 0;
        return quote(intConfig(TacticalConfig.INSTANCE.blazeVolleyApCost, BlazeVolley.DEFAULT_AP_COST),
                enemy.paidActions);
    }

    /**
     * Authoritative legality of one native salvo from a hypothetical feet cell.
     *
     * <p>The ceiling is the ruleset's 16 blocks and is additionally clamped by the entity's real follow range,
     * so the mod's number never overrides what the body can actually see. A blocked line is a real refusal.</p>
     */
    private boolean canFireballFrom(EnemyState state, LivingEntity opponent, GridPos from) {
        if (state == null || opponent == null || from == null || !isBlazeActor(state.mob)) return false;
        if (opponent.level() != level || !opponent.isAlive() || opponent.isRemoved()) return false;
        if (!opposed(state.mob, opponent)) return false;
        if (participantCell(opponent) == null) return false;
        // The real geometry, not the grid centre: an airborne blaze keeps a fractional offset from its cell and
        // the native fireball leaves from half its body height plus half a block, aimed at the target's own mid
        // point. The distance ceiling is the same decision on the same two points.
        Vec3 origin = fireballOrigin(state, from);
        Vec3 aim = new Vec3(opponent.getX(), opponent.getY(0.5D), opponent.getZ());
        if (origin == null || aim.distanceTo(origin) > fireballRangeLimit(state)) return false;
        return fireballLineClear(state, origin, aim);
    }

    /** Where this actor's native fireball would really leave from, for the cell it would stand at. */
    private Vec3 fireballOrigin(EnemyState state, GridPos from) {
        if (state == null || from == null || state.mob == null) return null;
        Vec3 feet = AerialMovement.feet(from, state.airOffset);
        return new Vec3(feet.x, feet.y + state.mob.getBbHeight() * 0.5D + 0.5D, feet.z);
    }

    private double fireballRangeLimit(EnemyState state) {
        return Math.min(BlazeTacticalAi.MAXIMUM_RANGE, state.mob.getAttributeValue(Attributes.FOLLOW_RANGE));
    }

    /** The native straight line to the target mid point; a blocked line is a real refusal, not a lob. */
    private boolean fireballLineClear(EnemyState state, Vec3 origin, Vec3 aim) {
        if (origin == null || aim == null) return false;
        net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
                origin, aim, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, state.mob));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(origin) >= aim.distanceToSqr(origin) - 0.05D;
    }

    /**
     * Whether the body itself could melee from that cell.
     *
     * <p>Vanilla's blaze picks melee at {@code distanceToSqr < 4.0} and needs a line of sight; an aerial actor is
     * cube-adjacent to cells two blocks away, so grid adjacency must not be read as reach here.</p>
     */
    private boolean inNativeMeleeReach(EnemyState state, LivingEntity opponent, GridPos from) {
        if (state == null || opponent == null || from == null) return false;
        if (opponent.level() != level || !opponent.isAlive() || opponent.isRemoved()) return false;
        GridPos target = participantCell(opponent);
        if (target == null) return false;
        // Real position, including the airborne offset the encounter preserved: a blaze nine tenths of a block
        // away from its cell centre is not at the cell centre for vanilla's own d0 < 4.0 gate.
        Vec3 feet = AerialMovement.feet(from, state.airOffset);
        double dx = feet.x - opponent.getX();
        double dy = feet.y - opponent.getY();
        double dz = feet.z - opponent.getZ();
        return dx * dx + dy * dy + dz * dz < 4.0D && hasLineOfSight(from, target, state.mob);
    }

    /**
     * Start one salvo: pre-check everything, freeze the price, own the charge - charge nothing yet.
     *
     * <p>Nothing about the entity is borrowed or mutated except vanilla's own charge bit, which is cleared on
     * every exit path. The paid commit happens in {@link #fireBlazeShot} and only once a fireball really joined
     * the world, so a refused or cancelled salvo leaves AP, the paid-action count and the bar untouched.</p>
     *
     * @return true when this own turn now owns a running salvo
     */
    private boolean startBlazeVolley(EnemyState state) {
        if (state == null || !isBlazeActor(state.mob) || blazeVolley != null) return false;
        LivingEntity target = actionOpponent(state);
        if (target == null || !isActorTurn(state.mob) || isBusy()) return false;
        int cost = blazeVolleyCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        if (!canFireballFrom(state, target, state.cell)) {
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_NO_FIREBALL,
                    BattleText.name(state.mob)));
            sendState();
            return false;
        }
        blazeVolley = new BlazeVolley(state.mob.getUUID(), target.getUUID(), turnEpoch(), cost, ticks);
        setBlazeCharged(state, true);
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_FIREBALL_CHARGE,
                BattleText.name(state.mob), BlazeVolley.CHARGE_TICKS));
        sendState();
        return true;
    }

    /**
     * Advance the running salvo by one server tick.
     *
     * <p>The salvo holds the turn: {@link #rangedBusy()} reports it, so nothing else acts, no other unit is
     * planned and the queue cannot advance. Every path ends here with a bounded hand-back.</p>
     */
    private void tickBlazeVolley() {
        BlazeVolley volley = blazeVolley;
        if (volley == null) return;
        EnemyState state = enemies.get(volley.actorId());
        boolean sameTurn = !ending && state != null && state.mob.isAlive() && !state.mob.isRemoved()
                && state.mob.level() == level && volley.actorId().toString().equals(queue.current())
                && volley.epoch() == turnEpoch();
        if (!sameTurn) {
            stopBlazeVolley();
            return;
        }
        if (volley.expired(ticks)) {
            // The hard ceiling reclaims whatever is still flying for this actor, so the deadline cannot leave a
            // marked projectile behind the turned page.
            BlazeFireballs.clearOwner(this, volley.actorId());
            stopBlazeVolley();
            if (!finished && !ending) advanceTurn();
            return;
        }
        if (volley.charging() && volley.chargeDue(ticks)) {
            volley.settleCharge();
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_FIREBALL_READY,
                    BattleText.name(state.mob)));
            sendState();
        }
        if (volley.shotDue(ticks) && !fireBlazeShot(state, volley)) {
            // A shot that could not be fired ends the volley: no retry, no re-aim, no refund.
            volley.stopRemaining();
        }
        if (volley.complete(ticks, projectiles.pending())) {
            stopBlazeVolley();
            if (!finished && !ending) advanceTurn();
        }
    }

    /**
     * Fire exactly one real fireball, and commit the frozen price only when the world really took it.
     *
     * <p>The capture scope is what makes the outcome authoritative: a join that an event cancelled produces no
     * tracked projectile, so nothing is charged and no mark is left behind. A failure thrown after an earlier
     * shot already joined leaves that shot in the world and does not refund it.</p>
     */
    private boolean fireBlazeShot(EnemyState state, BlazeVolley volley) {
        LivingEntity target = actionOpponent(state);
        if (target == null || !target.getUUID().equals(volley.targetId())) return false;
        if (target.level() != level || !target.isAlive() || target.isRemoved() || !opposed(state.mob, target)) {
            return false;
        }
        if (!canFireballFrom(state, target, state.cell)) return false;
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(state.mob, targetCell);
        boolean first = volley.shotsFired() == 0;
        if (first && state.ap < volley.price()) {
            // The charge ran for 60 ticks; a legal state change in between must not buy a free fireball or push
            // the balance negative. The quote itself is never re-read and nothing already fired is taken back.
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_NO_FIREBALL,
                    BattleText.name(state.mob)));
            sendState();
            return false;
        }
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int fired = projectiles.capture(state.mob, null, () -> {
            try {
                BlazeFireballs.launch(level, (Blaze) state.mob, target, this, volley.targetId());
            } catch (RuntimeException failure) {
                nativeFailure[0] = failure;
            }
        });
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native blaze fireball failed for {} after {} projectile(s)",
                    state.mob.getUUID(), Math.max(0, fired), nativeFailure[0]);
        }
        if (fired <= 0) {
            if (first) {
                // A salvo that never produced a fireball is a refusal, not a paid miss: say so and charge nothing.
                journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_NO_FIREBALL,
                        BattleText.name(state.mob)));
                sendState();
            }
            return false;
        }
        volley.onShotFired(ticks);
        if (first) {
            // One paid main action for the whole salvo, committed at the first projectile that really exists.
            state.ap -= volley.price();
            state.paidActions++;
            journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_FIREBALL,
                    BattleText.name(state.mob), BattleText.name(target)));
        }
        sendState();
        return true;
    }

    /** Clear the running salvo and its charge presentation; never touches a projectile already in flight. */
    private void stopBlazeVolley() {
        BlazeVolley volley = blazeVolley;
        blazeVolley = null;
        if (volley == null) return;
        setBlazeCharged(enemies.get(volley.actorId()), false);
    }

    private static boolean blazeCharged(Mob mob) {
        return mob instanceof com.tactical.neoforge.battle.ai.TacticalBlazeAccess access && access.tactical$isCharged();
    }

    private static void setBlazeCharged(Mob mob, boolean charged) {
        if (mob instanceof com.tactical.neoforge.battle.ai.TacticalBlazeAccess access) {
            access.tactical$setCharged(charged);
        }
    }

    private static void setBlazeCharged(EnemyState state, boolean charged) {
        if (state != null && state.mob instanceof com.tactical.neoforge.battle.ai.TacticalBlazeAccess access) {
            access.tactical$setCharged(charged);
        }
    }

    /**
     * End this encounter's salvo for that exact actor and give its charge presentation back.
     *
     * <p>Used by every exit: death, removal, unload, an abandoned fall and a normal release. The {@code fallback}
     * state is the one the caller still holds when the roster entry is already gone, so the charge bit is cleared
     * even then and a released blaze never keeps a burning pose it did not earn.</p>
     */
    private void dropBlazeVolleyFor(UUID actorId, EnemyState fallback) {
        if (actorId == null) return;
        // Order matters: the actor's real fireballs are discarded while the encounter still owns them, so no
        // projectile is left in the sky after the unit stops being controlled.
        BlazeFireballs.clearOwner(this, actorId);
        BlazeVolley volley = blazeVolley;
        if (volley != null && actorId.equals(volley.actorId())) blazeVolley = null;
        EnemyState state = enemies.get(actorId);
        setBlazeCharged(state != null ? state : fallback, false);
    }

    /**
     * Whether one of this encounter's marked fireballs may affect a candidate where it really landed.
     *
     * <p>Only this encounter's living, opposed participants count: the shooter itself, a same-side unit, a
     * bystander, an uninvited pet and another encounter's unit are all refused, and a non-living body is refused
     * by the caller before this is ever asked. Because the refusal happens at the collision entry, the target is
     * never ignited, hurt or given post-attack enchantment effects in the first place.</p>
     */
    public boolean acceptsFireball(UUID actorId, Entity candidate) {
        if (finished || ending || actorId == null || candidate == null) return false;
        EnemyState shooter = enemies.get(actorId);
        if (shooter == null || shooter.mob == null || shooter.mob.isRemoved() || !shooter.mob.isAlive()) return false;
        if (candidate == shooter.mob) return false;
        if (!(candidate instanceof LivingEntity victim)) return false;
        if (victim.level() != level || !victim.isAlive() || victim.isRemoved()) return false;
        if (!opposed(shooter.mob, victim)) return false;
        if (victim instanceof ServerPlayer player) {
            PlayerMember member = members.get(player.getUUID());
            return member != null && member.entity == player;
        }
        EnemyState state = enemies.get(victim.getUUID());
        return state != null && state.mob == victim;
    }

    // ------------------------------------------------------------------
    // witch: one self-drink, one thrown bottle
    // ------------------------------------------------------------------

    /** Plan §2.3 defaults; the config exposes all four so a pack can retune without touching the rules. */
    static final int WITCH_DRINK_COST = 25;
    static final int WITCH_THROW_COST = 15;
    /** Vanilla instant health at amplifier 0 restores exactly this much; a smaller gap would waste a dose. */
    static final float HEAL_DOSE = 4.0F;

    /**
     * Everything the potion tactics need, read once from the state the action is really charged from.
     *
     * <p>The cooldown is the witch's own own-turn counter, never wall-clock time: only her own turn start
     * advances it, so other units acting, a paused turn or a reopened menu can neither reduce nor re-arm it.
     * "Fully effective" means at least one whole own-turn cycle of the effect is left, which is the same 200
     * ticks {@link BattleEffects} settles per cycle; anything shorter is treated as worth replacing.</p>
     */
    private WitchState witchState(EnemyState state) {
        if (!(state.mob instanceof Witch witch)) return WitchState.NONE;
        LivingEntity opponent = actionOpponent(state);
        EnumSet<WitchBrew> activeSelf = EnumSet.noneOf(WitchBrew.class);
        for (WitchBrew brew : WitchBrew.values()) {
            if (!brew.selfDrink()) continue;
            net.minecraft.core.Holder<MobEffect> effect = WitchBrews.periodicEffect(brew);
            if (effect != null && witch.hasEffect(effect)) activeSelf.add(brew);
        }
        EnumSet<WitchBrew> activeTarget = EnumSet.noneOf(WitchBrew.class);
        EnumSet<WitchBrew> receptive = EnumSet.noneOf(WitchBrew.class);
        if (opponent != null) {
            for (WitchBrew brew : WitchBrew.values()) {
                if (!brew.thrown()) continue;
                net.minecraft.core.Holder<MobEffect> effect = WitchBrews.periodicEffect(brew);
                if (effect == null) {
                    // Instant harming would heal an inverted target instead of hurting it, so it is not
                    // receptive there. Nothing else can make an instant brew fail.
                    if (!opponent.isInvertedHealAndHarm()) receptive.add(brew);
                    continue;
                }
                MobEffectInstance current = opponent.getEffect(effect);
                if (current != null && current.getDuration() >= BattleEffects.TICKS_PER_OWN_CYCLE) {
                    activeTarget.add(brew);
                }
                if (opponent.canBeAffected(new MobEffectInstance(effect))) receptive.add(brew);
            }
        }
        float maxHealth = witch.getMaxHealth();
        boolean burning = witch.isOnFire() || witch.getLastDamageSource() != null
                && witch.getLastDamageSource().is(net.minecraft.tags.DamageTypeTags.IS_FIRE);
        boolean ready = isActorTurn(witch) && !isBusy() && state.drink.ready(state.ownTurns);
        return new WitchState(ready, opponent != null, mobDrinkCost(state), mobThrowCost(state),
                maxHealth <= 0.0F ? 1.0F : witch.getHealth() / maxHealth,
                Math.max(0.0F, maxHealth - witch.getHealth()), HEAL_DOSE, (float) witchHealThreshold(),
                witch.isEyeInFluid(net.minecraft.tags.FluidTags.WATER), burning,
                opponent == null ? 0.0F : opponent.getHealth(),
                activeSelf, activeTarget, receptive, state.pendingDrink);
    }

    private int mobDrinkCost(EnemyState enemy) {
        return quote(intConfig(TacticalConfig.INSTANCE.witchDrinkApCost, WITCH_DRINK_COST), enemy.paidActions);
    }

    private int mobThrowCost(EnemyState enemy) {
        return quote(intConfig(TacticalConfig.INSTANCE.witchThrowApCost, WITCH_THROW_COST), enemy.paidActions);
    }

    private int witchDrinkCooldownTurns() {
        return Math.max(1, intConfig(TacticalConfig.INSTANCE.witchDrinkCooldownTurns, 3));
    }

    private double witchHealThreshold() {
        try {
            return TacticalConfig.INSTANCE.witchHealThreshold.get();
        } catch (RuntimeException unreadable) {
            Tactical.LOGGER.warn("[Tactical] witch heal threshold unreadable; using the ruled default", unreadable);
            return WitchTacticalAi.DEFAULT_HEAL_THRESHOLD;
        }
    }

    /** Authoritative legality of one thrown bottle from a hypothetical feet cell. */
    private boolean canThrowFrom(EnemyState state, LivingEntity opponent, GridPos from) {
        if (opponent == null || from == null || !(state.mob instanceof Witch)) return false;
        GridPos target = participantCell(opponent);
        if (target == null) return false;
        if (Math.hypot(from.x() - target.x(), from.z() - target.z()) > WitchTacticalAi.MAX_THROW_DISTANCE + 0.001D) {
            return false;
        }
        // A potion is not an arrow: it is planned with its own speed, gravity and water drag, and it does not
        // pierce. A blocked arc is a real refusal, not a shot that flies through cover.
        return BallisticShot.plan(level, state.mob, from, opponent, ThrowProfile.SPLASH_POTION, 0) != null;
    }

    /**
     * Start this own turn's self-drink as a bounded, vanilla-styled presentation.
     *
     * <p>The bottle really goes into her hand, vanilla's own drinking flag and countdown are set and her own
     * drink sound plays, for as many ticks as the potion's own use duration. While that runs the turn stays
     * busy and nobody else acts. The dose itself is settled exactly once, in {@link #settleWitchDrink()}, from
     * vanilla's own outlets: an instant brew through {@code applyInstantenousEffect}, a periodic one through
     * {@code addEffect}. AP, the paid-action count and the shared cooldown move only if that settlement really
     * changed something.</p>
     *
     * @return true when the action was accepted and the presentation started
     */
    private boolean drinkPotion(EnemyState state, WitchBrew brew) {
        if (!(state.mob instanceof Witch witch) || brew == null || !brew.selfDrink()) return false;
        if (!isActorTurn(witch) || isBusy() || !state.drink.ready(state.ownTurns)) return false;
        if (mobDrinkCost(state) <= 0 || state.ap < mobDrinkCost(state)) return false;
        if (!witchState(state).beneficial(brew)) {
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_DRINK_REFUSED,
                    BattleText.name(witch)));
            sendState();
            return false;
        }
        ItemStack bottle = WitchBrews.drink(brew);
        if (bottle.get(DataComponents.POTION_CONTENTS) == null) return false;
        // Borrow the hand for the presentation. What was there is kept and given back when the pose ends, so
        // an unrelated item is never destroyed by a drink.
        pendingWitchBorrowed = witch.getMainHandItem().copy();
        pendingWitchBottle = bottle.copy();
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        witch.setUsingItem(true);
        witchAccess(witch).tactical$restoreDrink(new TacticalWitchAccess.WitchDrink(bottle,
                Math.max(1, bottle.getUseDuration(witch)), true, true));
        if (!witch.isSilent()) {
            level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), net.minecraft.sounds.SoundEvents.WITCH_DRINK,
                    witch.getSoundSource(), 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
        }
        pendingWitchDrink = witch.getUUID();
        pendingWitchBrew = brew;
        pendingWitchDrinkTicks = Math.max(1, bottle.getUseDuration(witch));
        sendState();
        return true;
    }

    /** True while this encounter is presenting a witch's drink: the turn belongs to that pose, not to anyone else. */
    private boolean witchDrinking() { return pendingWitchDrink != null; }

    /**
     * Advance the presented drink by one tick and settle it once at the end of its window.
     *
     * <p>Called from the encounter tick before turn flow. It never settles twice: the pose is cleared before the
     * outlets run, so a callback that re-enters cannot find a half-open drink.</p>
     */
    private void tickWitchDrink() {
        if (pendingWitchDrink == null) return;
        if (--pendingWitchDrinkTicks > 0) return;
        settleWitchDrink();
    }

    /** The single settlement point of one self-drink: dose, price, cooldown and cleanup, in that order. */
    private void settleWitchDrink() {
        EnemyState state = enemies.get(pendingWitchDrink);
        WitchBrew brew = pendingWitchBrew;
        ItemStack borrowed = pendingWitchBorrowed;
        ItemStack owned = pendingWitchBottle;
        Mob actor = state == null ? null : state.mob;
        pendingWitchDrink = null;
        pendingWitchBrew = null;
        pendingWitchBorrowed = ItemStack.EMPTY;
        pendingWitchBottle = ItemStack.EMPTY;
        pendingWitchDrinkTicks = 0;
        if (!(actor instanceof Witch witch) || brew == null) {
            if (actor != null) sendState();
            return;
        }
        // End the presentation first and give the borrowed hand back: the dose below must not look like a drink
        // still in progress, and an unrelated item must never be left out of her hand by the pose.
        witchAccess(witch).tactical$clearDrink();
        giveBackBorrowedHand(witch, borrowed, owned, actor.isAlive() && !actor.isRemoved());
        boolean took = applyDrink(witch, brew);
        if (!took) {
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_DRINK_REFUSED,
                    BattleText.name(witch)));
        } else {
            int cost = mobDrinkCost(state);
            state.ap = Math.max(0, state.ap - cost);
            state.paidActions++;
            state.drink.settle(state.ownTurns);
            // The drink carried over from admission is now paid for: it can never come back as a second dose.
            if (state.pendingDrink != null) {
                if (state.pendingDrink == brew) state.witchDrinkResolved = true;
                state.pendingDrink = null;
            }
            journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_DRINK,
                    BattleText.name(witch), brewName(brew)));
        }
        sendState();
        if (finished) return;
        if (actor.getUUID().toString().equals(queue.current())) advanceTurn();
    }

    /**
     * Apply one brew through vanilla's own outlets and report whether it really did something.
     *
     * <p>This is the distinction {@code PotionItem#finishUsingItem} makes: an instant effect goes through
     * {@code applyInstantenousEffect} and is only paid for when real health actually moved, a periodic one
     * through {@code addEffect} and only when the entity really carries it afterwards. A cancelled heal, a
     * refused effect or a brew with nothing left to give therefore settles as no drink at all.</p>
     */
    private boolean applyDrink(Witch witch, WitchBrew brew) {
        ItemStack bottle = WitchBrews.drink(brew);
        PotionContents contents = bottle.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        float before = witch.getHealth();
        boolean[] periodic = new boolean[1];
        contents.forEachEffect(instance -> {
            if (instance.getEffect().value().isInstantenous()) {
                instance.getEffect().value().applyInstantenousEffect(null, null, witch,
                        instance.getAmplifier(), 1.0);
            } else {
                periodic[0] |= witch.addEffect(instance);
            }
        });
        boolean healed = witch.getHealth() > before + 0.01F;
        witch.gameEvent(net.minecraft.world.level.gameevent.GameEvent.DRINK);
        if (brew == WitchBrew.HEALING) return healed;
        net.minecraft.core.Holder<MobEffect> effect = WitchBrews.periodicEffect(brew);
        return periodic[0] && effect != null && witch.hasEffect(effect);
    }

    /**
     * One real vanilla splash potion, thrown at the opponent this own turn locked.
     *
     * <p>The bottle is a genuine {@link ThrownPotion} carrying the same registry potion vanilla would have
     * used, marked as this encounter's bottle so its splash can be filtered, and given the planned flight of a
     * potion rather than an arrow's. Creating it is the paid event: a bottle that never joins the world costs
     * nothing and leaves no rule behind, while a bottle that exists is final even if it misses.</p>
     */
    private boolean throwPotion(EnemyState state, WitchBrew brew) {
        if (!(state.mob instanceof Witch witch) || brew == null || !brew.thrown()) return false;
        if (!isActorTurn(witch) || isBusy()) return false;
        LivingEntity target = actionOpponent(state);
        if (target == null || !opposed(witch, target) || target.level() != level) return false;
        int cost = mobThrowCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        if (!canThrowFrom(state, target, state.cell)) {
            refuseShot(state, BattleText.KEY_ENEMY_NO_THROW);
            return false;
        }
        BallisticShot.Plan shot = BallisticShot.plan(level, witch, state.cell, target,
                ThrowProfile.SPLASH_POTION, 0);
        if (shot == null) {
            refuseShot(state, BattleText.KEY_ENEMY_NO_THROW);
            return false;
        }
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(witch, targetCell);
        witch.setYRot(shot.yaw());
        witch.setYHeadRot(shot.yaw());
        witch.setYBodyRot(shot.yaw());
        witch.setXRot(shot.pitch());
        ThrownPotion bottle = new ThrownPotion(level, witch);
        bottle.setItem(WitchBrews.splash(brew));
        BattlePotionSplash.mark(bottle, this, witch);
        // Catch inside the capture scope, exactly as the ranged path does: a throwing join callback must not
        // escape, and capture still has to finish its authoritative spawned-entity count before any refund.
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int fired = projectiles.capture(witch, shot, () -> {
            try {
                level.addFreshEntity(bottle);
            } catch (RuntimeException failure) {
                nativeFailure[0] = failure;
            }
        });
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native potion throw failed for {} after {} bottle(s)",
                    witch.getUUID(), Math.max(0, fired), nativeFailure[0]);
        }
        if (fired <= 0) {
            BattlePotionSplash.forget(bottle);
            refuseShot(state, BattleText.KEY_ENEMY_NO_THROW);
            return false;
        }
        if (!witch.isSilent()) {
            level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), net.minecraft.sounds.SoundEvents.WITCH_THROW,
                    witch.getSoundSource(), 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
        }
        state.ap -= cost;
        state.paidActions++;
        pendingEnemyShot = witch.getUUID();
        awaitingShotResolution = true;
        shotRecoveryTicks = -1;
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_THROW,
                BattleText.name(witch), BattleText.name(target), brewName(brew)));
        sendState();
        return true;
    }

    /** One brew name as structure, so every client reads it in its own language. */
    private static Component brewName(WitchBrew brew) {
        return BattleText.translatable(BattleText.BREW_PREFIX + brew.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Whether one of this encounter's own hostile bottles may affect a candidate at the moment it lands.
     *
     * <p>Only this encounter's living, opposed participants count: the throwing witch herself, a same-side
     * raider, a bystander, an uninvited pet and another encounter's unit are all refused. That single rule
     * covers instant damage and every periodic effect, so poison and slowness cannot leak where damage cannot.
     * </p>
     */
    public boolean acceptsSplash(LivingEntity thrower, LivingEntity candidate) {
        if (finished || thrower == null || candidate == null || candidate == thrower) return false;
        if (candidate.level() != level || !candidate.isAlive() || candidate.isRemoved()) return false;
        if (!opposed(thrower, candidate)) return false;
        if (candidate instanceof ServerPlayer player) return members.containsKey(player.getUUID());
        EnemyState state = enemies.get(candidate.getUUID());
        return state != null && state.mob == candidate;
    }

    private static TacticalWitchAccess witchAccess(Mob mob) {
        if (mob instanceof TacticalWitchAccess access) return access;
        throw new IllegalStateException("Witch tactical mixin missing: register tactical.witch.mixins.json");
    }

    /**
     * Give back a witch's unfinished vanilla drink; a drink this encounter already paid for is never restored.
     *
     * <p>Also ends any presentation this encounter still owes her: an unfinished pose is never left holding a
     * borrowed bottle, and it never settles a dose on the way out.</p>
     */
    private void releaseWitchState(EnemyState state) {
        if (state == null) return;
        abortWitchDrink(state.mob.getUUID());
        if (state.witchDrink != null && !state.witchDrinkResolved
                && state.mob instanceof Witch witch && witch.isAlive() && !witch.isRemoved()) {
            witchAccess(witch).tactical$restoreDrink(state.witchDrink);
        }
        state.witchDrink = null;
        state.pendingDrink = null;
    }

    /**
     * End a presented drink without settling it and give the borrowed stack back.
     *
     * <p>Death, departure, a refused encounter end and a finished battle all land here, so a pose can never
     * outlive its owner and an unrelated hand item is never lost to it.</p>
     */
    private void abortWitchDrink(UUID actorId) {
        if (pendingWitchDrink == null || (actorId != null && !pendingWitchDrink.equals(actorId))) return;
        EnemyState state = enemies.get(pendingWitchDrink);
        ItemStack borrowed = pendingWitchBorrowed;
        ItemStack owned = pendingWitchBottle;
        pendingWitchDrink = null;
        pendingWitchBrew = null;
        pendingWitchBorrowed = ItemStack.EMPTY;
        pendingWitchBottle = ItemStack.EMPTY;
        pendingWitchDrinkTicks = 0;
        if (state == null || !(state.mob instanceof Witch witch)) return;
        // Vanilla's flag and slowdown are cleared even for a unit that is no longer alive: leaving a pose on a
        // corpse is exactly the temporary state this encounter promised not to leave behind.
        witchAccess(witch).tactical$clearDrink();
        giveBackBorrowedHand(witch, borrowed, owned, witch.isAlive() && !witch.isRemoved());
    }

    /**
     * Put the borrowed item back into the hand this presentation borrowed it from - and only if that slot still
     * holds the bottle this presentation put there.
     *
     * <p>An external callback that legitimately replaced the main hand during the pose keeps its new item: it is
     * neither swallowed nor re-created, and the borrowed stack is never inserted anywhere else, so nothing is
     * duplicated. A unit that is dead or removed gets no item back - its own death drop has already read the
     * hand, or will read it after {@link #restoreWitchDrinkBeforeDeath(LivingEntity)} ran.</p>
     */
    private void giveBackBorrowedHand(Witch witch, ItemStack borrowed, ItemStack owned, boolean writable) {
        if (!writable || owned.isEmpty()) return;
        if (!ItemStack.matches(witch.getMainHandItem(), owned)) return;
        witch.setItemSlot(EquipmentSlot.MAINHAND, borrowed);
    }

    /**
     * Give the borrowed item back before vanilla's own death processing reads the hand for drops.
     *
     * <p>Called from the death hook at the lowest priority, so the real item is what drops instead of the
     * temporary bottle. It settles no dose, heals nothing and moves nothing: only the equip slot and the drink
     * presentation are touched.</p>
     *
     * @return true when this death really ended a drink this encounter was presenting
     */
    public boolean restoreWitchDrinkBeforeDeath(LivingEntity actor) {
        if (actor == null || pendingWitchDrink == null || !pendingWitchDrink.equals(actor.getUUID())) return false;
        EnemyState state = enemies.get(pendingWitchDrink);
        ItemStack borrowed = pendingWitchBorrowed;
        ItemStack owned = pendingWitchBottle;
        pendingWitchDrink = null;
        pendingWitchBrew = null;
        pendingWitchBorrowed = ItemStack.EMPTY;
        pendingWitchBottle = ItemStack.EMPTY;
        pendingWitchDrinkTicks = 0;
        if (state == null || !(state.mob instanceof Witch witch)) return false;
        witchAccess(witch).tactical$clearDrink();
        // The unit is dying, so a writable slot is exactly what the drop pass is about to read.
        giveBackBorrowedHand(witch, borrowed, owned, true);
        return true;
    }

    /**
     * The ammunition the native shot will really draw.
     *
     * <p>Read through the same vanilla accessors the firing code uses, so the planned piercing, the planned
     * flight and the projectile that really leaves cannot describe two different things.</p>
     */
    private static ItemStack ammoFor(Mob mob, MobRangedLoadout loadout, ItemStack weapon) {
        if (loadout.weapon() == RangedWeapon.CROSSBOW) {
            ChargedProjectiles charged = weapon.get(DataComponents.CHARGED_PROJECTILES);
            if (charged != null && !charged.isEmpty()) return charged.getItems().get(0);
        }
        return mob.getProjectile(weapon);
    }

    /** The piercing the real arrow will carry: the same vanilla count the projectile applies to itself. */
    private int piercingOf(ItemStack weapon, ItemStack ammo) {
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getPiercingCount(level, weapon, ammo);
    }

    /**
     * One ranged main action as a bounded transaction over what it can really change.
     *
     * <p>Scope is exactly the two hand stacks (the weapon with its real charged projectiles and durability, and
     * the ammunition stack the shot draws from), the crossbow charging pose and the actor's own aim. A world
     * change made by some unrelated callback is not this transaction's business and is never replayed.</p>
     */
    private record RangedTransaction(Mob mob, MobRangedLoadout loadout, ItemStack weapon, ItemStack other,
                                     boolean charging, float yRot, float xRot, float yHeadRot, float yBodyRot) {
        static RangedTransaction begin(Mob mob, MobRangedLoadout loadout) {
            boolean main = loadout.hand() == InteractionHand.MAIN_HAND;
            return new RangedTransaction(mob, loadout,
                    (main ? mob.getMainHandItem() : mob.getOffhandItem()).copy(),
                    (main ? mob.getOffhandItem() : mob.getMainHandItem()).copy(),
                    mob instanceof net.minecraft.world.entity.monster.Pillager pillager && pillager.isChargingCrossbow(),
                    mob.getYRot(), mob.getXRot(), mob.getYHeadRot(), mob.yBodyRot);
        }

        boolean stillHoldsWeapon() {
            return loadout.equals(MobRangedLoadout.of(loadout.weapon(),
                    MobRangedLoadout.carried(mob.getMainHandItem()),
                    MobRangedLoadout.carried(mob.getOffhandItem())));
        }

        void restore() {
            boolean main = loadout.hand() == InteractionHand.MAIN_HAND;
            mob.setItemSlot(main ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND, weapon);
            mob.setItemSlot(main ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND, other);
            if (mob instanceof net.minecraft.world.entity.monster.Pillager pillager) {
                pillager.setChargingCrossbow(charging);
            }
            mob.setYRot(yRot);
            mob.setYHeadRot(yHeadRot);
            mob.setYBodyRot(yBodyRot);
            mob.setXRot(xRot);
        }
    }

    /**
     * Load an empty crossbow through the vanilla item path.
     *
     * <p>{@code releaseUsing} at full charge is the exact vanilla load: it draws this actor's own
     * {@code getProjectile} ammunition (a monster's native default arrow, never a player inventory), applies
     * real multishot/piercing enchants and stores the real charged projectiles. Nothing is charged here.</p>
     */
    private static boolean loadCrossbow(Mob mob, MobRangedLoadout loadout) {
        ItemStack stack = loadout.stack(mob);
        if (!(stack.getItem() instanceof CrossbowItem crossbow)) return false;
        if (CrossbowItem.isCharged(stack)) return true;
        // getUseDuration - 0 is the full charge duration, so no wall-clock using time is invented.
        crossbow.releaseUsing(stack, mob.level(), mob, 0);
        return CrossbowItem.isCharged(stack);
    }

    private static boolean loadedFirework(ItemStack weapon) {
        ChargedProjectiles charged = weapon.get(DataComponents.CHARGED_PROJECTILES);
        return charged != null && !charged.isEmpty() && charged.contains(Items.FIREWORK_ROCKET);
    }

    private AiContext aiContext(EnemyState state) {
        LivingEntity opponent = actionOpponent(state);
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
            public List<GridPos> standableNeighbors(GridPos cell) {
                if (state.flying) return airNeighbors(cell);
                // A door-capable actor asks the door-aware view, so the cells it was planned through - an
                // already open doorway included - are exactly the cells its movement is validated against.
                DoorRouteTerrain terrain = doorTerrain(state);
                return terrain == null ? navigator.neighbours(cell) : terrain.enterableNeighbours(cell);
            }
            public boolean occupied(GridPos cell) {
                for (PlayerMember member : members.values())
                    if (member.state.cell().equals(cell)) return true;
                for (EnemyState other : enemies.values())
                    if (other != state && other.mob.isAlive() && !other.mob.isRemoved() && other.cell.equals(cell)) return true;
                return false;
            }
            public boolean inMeleeRange(GridPos from, GridPos to) { return state.flying ? Math.abs(from.x()-to.x())<=1 && Math.abs(from.y()-to.y())<=1 && Math.abs(from.z()-to.z())<=1 : ServerBattle.inMeleeRange(from,to); }
            public boolean hasLineOfSight(GridPos from, GridPos to) { return ServerBattle.this.hasLineOfSight(from,to,state.mob); }
            public int meleeCost() { return mobMeleeCost(state); }
            public int rangedCost() { return mobRangedCost(state); }
            public boolean canRangedAttack(GridPos from) { return canShootFrom(state, opponent, from); }
            public void rangedAttackPlayer() { ServerBattle.this.shootOpponent(state); }
            public int fireballCost() { return blazeVolleyCost(state); }
            public boolean canFireballFrom(GridPos from) {
                return ServerBattle.this.canFireballFrom(state, opponent, from);
            }
            public boolean inNativeMeleeReach(GridPos from) {
                return ServerBattle.this.inNativeMeleeReach(state, opponent, from);
            }
            public boolean fireballVolley() { return ServerBattle.this.startBlazeVolley(state); }
            public void attackPlayer() { ServerBattle.this.attackPlayer(state); }
            public WitchState witch() { return witchState(state); }
            public boolean canThrowFrom(GridPos from) { return ServerBattle.this.canThrowFrom(state, opponent, from); }
            public boolean drinkPotion(WitchBrew brew) { return ServerBattle.this.drinkPotion(state, brew); }
            public boolean throwPotion(WitchBrew brew) { return ServerBattle.this.throwPotion(state, brew); }
            public void clearPendingDrink() { state.pendingDrink = null; }
            public VindicatorRoute vindicator() { return vindicatorRoute(state); }
            public boolean breakDoor(DoorRoutePlanner.Door door) { return ServerBattle.this.breakDoor(state, door); }
            public void log(String text) { journal.record("enemy",text); }

            /**
             * The structured outlet: the row is stored as structure and resolved per client.
             *
             * <p>Preferring this over {@link #log(String)} is what keeps an AI warning out of one language:
             * the producer never turns it into text, so there is nothing to translate back later.</p>
             */
            @Override public boolean logLocalized(Component text) { journal.record("enemy", text); return true; }
        };
    }

    private void resolveAiAction(EnemyState enemy,AiAction action) {
        if(action.kind()==AiAction.Kind.MOVE||action.kind()==AiAction.Kind.DIVE) {
            List<GridPos> steps=action.path();
            if(steps.isEmpty()) {advanceTurn();return;}
            int attackCost=action.kind()==AiAction.Kind.DIVE?mobMeleeCost(enemy):0;
            long total=(long)steps.size()*enemy.moveCost+attackCost;
            if(total>enemy.ap){advanceTurn();return;}
            int cost=(int)total;
            enemy.ap-=cost;
            enemyDiveReservedCost=attackCost;
            enemyDiveAttackIndex=action.kind()==AiAction.Kind.DIVE?action.attackIndex():-1;enemyDiveAttacked=false;
            enemyPath=List.copyOf(steps);enemyPathIndex=0;enemyStepping=false;enemyMoving=true;
            if(enemy.flying)journal.record("enemy",BattleText.translatable(BattleText.KEY_ENEMY_FLIGHT_MOVE,
                    BattleText.name(enemy.mob), cost));
            journal.record("move",BattleText.translatable(BattleText.KEY_ENEMY_MOVE,
                    BattleText.name(enemy.mob), BattleText.literal(steps.get(steps.size()-1).toString())));
            sendState();return;
        }
        if (targetedAction(action.kind()) && targetLost(enemy)) {
            // The opponent this action was planned against is gone. One bounded replan is allowed; anything
            // more would be an endless search for a new victim inside one turn.
            if (finished || !enemy.mob.getUUID().toString().equals(queue.current())) return;
            if (replanAfterLostTarget(enemy)) return;
            advanceTurn();
            return;
        }
        AiResolution result=ai.resolveInstant(aiContext(enemy),action);
        if(result==AiResolution.ROSTER_CHANGED) {
            removeDeadEnemies();
            pruneMembers();
            return;
        }
        if (finished || !enemy.mob.getUUID().toString().equals(queue.current())) return;
        if(pendingEnemyShot!=null && rangedBusy()) return;
        // The salvo owns the turn until it hands back itself; advancing here would cut its own charge short.
        if (blazeVolley != null) return;
        // The presented drink settles itself and only then hands the turn over.
        if (witchDrinking()) return;
        advanceTurn();
    }

    private void planEnemyTurn() {
        String id=queue.current();
        if(id==null) {finish(BattleText.endReason(BattleText.End.EMPTY_QUEUE));return;}
        EnemyState enemy=enemies.get(UUID.fromString(id));
        // A commanded companion is never played by the AI: its slot waits for its controller's order.
        if(enemy==null || enemy.companionAdapter != null) {if(enemy==null)advanceTurn();return;}
        if(!lockOpponent(enemy)) {advanceTurn();return;}
        resolveAiAction(enemy,ai.planTurn(aiContext(enemy)));
    }

    // ------------------------------------------------------------------
    // hand-commanded companions
    // ------------------------------------------------------------------

    /** True when this exact entity is a hand-commanded companion of this encounter. */
    public boolean isCompanion(LivingEntity actor) {
        if (actor == null || actor instanceof ServerPlayer || finished) return false;
        EnemyState state = enemies.get(actor.getUUID());
        return state != null && state.mob == actor && state.companionAdapter != null;
    }

    /** The controller of that companion, or null when the actor is not one. */
    public UUID companionOwner(LivingEntity actor) {
        if (actor == null) return null;
        EnemyState state = enemies.get(actor.getUUID());
        return state != null && state.mob == actor ? state.companionOwner : null;
    }

    /** Living companions of one controller, in recruitment order. */
    public List<Mob> companionsOf(UUID ownerId) {
        if (ownerId == null || finished) return List.of();
        List<Mob> result = new ArrayList<>();
        for (EnemyState state : enemies.values()) {
            if (state.companionAdapter == null || !ownerId.equals(state.companionOwner)) continue;
            if (state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level) result.add(state.mob);
        }
        return List.copyOf(result);
    }

    public int companionCount() {
        if (finished) return 0;
        int count = 0;
        for (EnemyState state : enemies.values()) {
            if (state.companionAdapter == null) continue;
            if (state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level) count++;
        }
        return count;
    }

    /**
     * Whether this companion's adapter declares one order right now.
     *
     * <p>Read live on every intent from the adapter, never from a client payload and never from the snapshot
     * the client was shown: a companion that offers fewer orders cannot be talked into one it did not declare,
     * and an adapter that changes its mind takes effect on the next order.</p>
     */
    private boolean companionAllows(EnemyState state, CompanionAbility ability) {
        if (state == null || state.companionAdapter == null || ability == null) return false;
        int abilities;
        try {
            abilities = state.companionAdapter.abilities(state.mob);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion ability query failed for {}; refusing the order",
                    state.mob.getUUID(), failure);
            return false;
        }
        return (abilities & ability.mask()) != 0;
    }

    /** One refusal line per undeclared order, so a player learns why instead of seeing nothing happen. */
    private void rejectUndeclared(EnemyState state, CompanionAbility ability) {
        Tactical.LOGGER.info("[Tactical] companion {} has no {} order", state.mob.getUUID(), ability);
        reject(BattleText.translatable(BattleText.KEY_REJECT_COMPANION_ORDER));
    }

    /** The orders this companion offers, as a {@link CompanionAbility} mask; 0 for anything else. */
    public int companionAbilities(LivingEntity actor) {
        EnemyState state = actor == null ? null : enemies.get(actor.getUUID());
        if (state == null || state.mob != actor || state.companionAdapter == null) return 0;
        return state.companionAdapter.abilities(state.mob);
    }

    /** The companion acting right now, if it is this member's: the only actor that member may order. */
    public LivingEntity commandCompanionOf(ServerPlayer player) {
        if (player == null || finished) return null;
        EnemyState state = actingCompanion();
        return state != null && player.getUUID().equals(state.companionOwner) ? state.mob : null;
    }

    /**
     * True while this player may issue an order at all: their own turn, or the turn of the companion that
     * resolves to them right now. Inventory, growth, blocks and fleeing keep using their own stricter gates.
     */
    public boolean isCommandTurn(ServerPlayer player) {
        if (player == null || finished) return false;
        EnemyState state = actingCompanion();
        if (state != null) return player.getUUID().equals(state.companionOwner);
        return isPlayerTurnNow(player);
    }

    /** The feet cell of whatever actor this player currently commands (their body, or their companion). */
    public GridPos commandCell(ServerPlayer player) {
        LivingEntity companion = commandCompanionOf(player);
        return companion == null ? playerCell(player) : currentActorCell(companion);
    }

    /** Reach of the active command; companion ATTACK is melee-only in this version. */
    public int commandWeaponRange(ServerPlayer player, GridPos target) {
        return commandCompanionOf(player) != null ? 1 : weaponRangeFor(player, target);
    }

    /** Preview exactly the native melee that a companion command can execute, not an unimplemented shot. */
    public boolean commandAttackAllowed(ServerPlayer player, LivingEntity target) {
        LivingEntity companion = commandCompanionOf(player);
        if (companion == null) return weaponAttackAllowed(player, target);
        EnemyState state = enemies.get(companion.getUUID());
        EnemyState victim = target == null ? null : enemies.get(target.getUUID());
        if (state == null || state.mob != companion || victim == null || victim.mob != target
                || victim.side != BattleSide.HOSTILE || !target.isAlive() || target.isRemoved()
                || target.level() != level || !opposed(companion, target)
                || companion.getAttribute(Attributes.ATTACK_DAMAGE) == null
                || !companionAllows(state, CompanionAbility.ATTACK)) return false;
        return inMeleeRange(state.cell, victim.cell) && hasLineOfSight(state.cell, victim.cell, companion);
    }

    /** Companion ATTACK is native melee even when an adapter's mob happens to hold a ranged item. */
    public int commandWeaponKind(ServerPlayer player) {
        return commandCompanionOf(player) != null ? 0 : weaponKind(player);
    }

    /** Friendly roster budget: the older automatic friends and the controlled companions share it. */
    int friendlyCount() {
        int count = 0;
        for (EnemyState state : enemies.values()) {
            if (state.side != BattleSide.PLAYER) continue;
            if (state.mob.isAlive() && !state.mob.isRemoved() && state.mob.level() == level) count++;
        }
        return count;
    }

    private boolean friendlySlotsFull() {
        return friendlyCount() >= BattleParticipation.MAX_FRIENDLIES;
    }

    /**
     * Admit one hand-commanded companion. Explicit entry only: the older {@link #tryAddFriendly(Mob)} keeps
     * its automatic behaviour and is never converted into a controlled unit.
     *
     * <p>The controller comes from the adapter, and must already be a member of this encounter, so a
     * companion can never pull its owner into somebody else's battle. Everything the admission commits is
     * rolled back together when a later step throws.</p>
     */
    public boolean tryAddCompanion(Mob mob, CompanionAdapter adapter) {
        if (mob == null || adapter == null || finished || ending || queue.current() == null || members.isEmpty()
                || mob.level() != level || !mob.isAlive() || mob.isRemoved()
                || enemies.containsKey(mob.getUUID()) || departedCompanions.contains(mob.getUUID())
                || friendlySlotsFull()) return false;
        if (!adapter.supports(mob)) return false;
        // Every formal entry point proves the candidate is already inside this encounter's zone: a wolf
        // standing outside the battlefield is never pulled in merely by being admitted.
        if (!zone.contains(cellOf(mob))) return false;
        UUID ownerId = adapter.controllerId(mob);
        if (ownerId == null || !members.containsKey(ownerId) || !adapter.canJoin(level, mob, ownerId)) return false;
        if (BattleManager.ownerOf(mob.getUUID()) != null) return false;
        // The capture happens before anything is mutated, so a throwing adapter cannot leave a half-admitted
        // unit behind: there is nothing to roll back yet.
        Object pose;
        try {
            pose = adapter.capturePose(mob);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion pose capture failed for {}; nothing was admitted",
                    mob.getUUID(), failure);
            return false;
        }
        var safe = deploymentPosition(mob,
                pos -> occupied(new GridPos(pos.getX(), pos.getY(), pos.getZ())),
                pos -> zone.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
        if (safe.isEmpty()) return false;
        Vec3 position = safe.get();
        GridPos cell = new GridPos(net.minecraft.util.Mth.floor(position.x), net.minecraft.util.Mth.floor(position.y), net.minecraft.util.Mth.floor(position.z));
        EnemyState state = admit(mob, cell, position, BattleSide.PLAYER);
        if (state == null) return false;
        // Recorded after the admission transaction succeeded, so a rolled-back admission leaves no record.
        state.companionAdapter = adapter;
        state.companionOwner = ownerId;
        state.companionPose = pose;
        // Durable restore record: written here, after the admission transaction committed and before control
        // changes the entity further, so a save or unload during the fight cannot keep a battle's AI lock.
        CompanionRestoreMarker.write(mob, state.originalNoAi, state.originalPersistenceRequired,
                session.sessionId(), ownerId);
        // END_TURN is part of the contract: without it the companion could never hand its own slot over, and
        // every further order would be refused by the same mask. Reported once, at admission.
        if (!companionAllows(state, CompanionAbility.END_TURN)) {
            Tactical.LOGGER.warn("[Tactical] companion adapter {} declares no end-turn order for {}; that kind "
                    + "cannot leave its own turn", adapter.id(), mob.getUUID());
        }
        try {
            adapter.beginControl(mob);
            queue.add(mob.getUUID().toString(), TacticalAttributes.chargeRate(mob, state.initiative));
            journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_COMPANION,
                    BattleText.name(mob), BattleText.name(nameOf(ownerId.toString()))));
            sendState();
            return true;
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion admission failed for {}; rolling it back",
                    mob.getUUID(), failure);
            queue.remove(mob.getUUID().toString());
            enemies.remove(mob.getUUID());
            try {
                adapter.restorePose(mob, state.companionPose);
            } catch (RuntimeException ignored) {
                // The admit rollback below still has to run.
            }
            state.companionAdapter = null;
            state.companionOwner = null;
            CompanionRestoreMarker.clear(mob);
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
     * Bring in every eligible companion of the current members that stands inside the zone right now.
     *
     * <p>Runs on the encounter's own bounded cadence (battle start, a member joining, and the existing
     * reinforcement pulse), never on a global scan, and always resolves the candidate against the exact
     * battle its controller belongs to - which is this one by construction.</p>
     */
    private void recruitCompanions() {
        if (finished || ending || members.isEmpty() || queue.current() == null) return;
        if (friendlySlotsFull()) return;
        List<Mob> candidates;
        try {
            candidates = level.getEntitiesOfClass(Mob.class, zoneBounds(),
                    mob -> mob.isAlive() && !mob.isRemoved() && mob.level() == level
                            && zone.contains(cellOf(mob))
                            && !enemies.containsKey(mob.getUUID())
                            && !departedCompanions.contains(mob.getUUID())
                            && CompanionRegistry.find(mob) != null);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion scan failed; keeping the current roster", failure);
            return;
        }
        if (candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingDouble((Mob mob) -> mob.distanceToSqr(centerOf(center)))
                .thenComparing(mob -> mob.getUUID().toString()));
        for (Mob mob : candidates) {
            if (friendlySlotsFull()) return;
            CompanionAdapter adapter = CompanionRegistry.find(mob);
            if (adapter == null) continue;
            UUID ownerId = adapter.controllerId(mob);
            if (ownerId == null || !members.containsKey(ownerId)) continue;
            if (BattleManager.ownerOf(mob.getUUID()) != null) continue;
            try {
                tryAddCompanion(mob, adapter);
            } catch (RuntimeException failure) {
                // One broken candidate or adapter never stops the rest of the scan.
                Tactical.LOGGER.error("[Tactical] companion recruitment failed for {}; continuing",
                        mob.getUUID(), failure);
            }
        }
    }

    /**
     * Give a companion back only what control took over: the AI lock, persistence, its target and its pose.
     *
     * <p>Deliberately not the admission rollback: a normal exit keeps the real final cell, the real damage,
     * the equipment and the fall distance of that moment. Position, target and fall distance are given back
     * only by a transaction that never committed.</p>
     */
    private void restoreControlFlags(EnemyState state) {
        Mob mob = state.mob;
        BattleEffects.forget(mob);
        mob.getNavigation().stop();
        // The salvo and the vanilla charge bit belong to this encounter's control, so releasing the unit gives
        // both back before anything else is restored.
        dropBlazeVolleyFor(mob.getUUID(), state);
        setBlazeCharged(state, false);
        mob.setNoAi(state.originalNoAi);
        // Vanilla has no "set false"; the accessor restores the exact original flag in both directions.
        ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) mob)
                .tactical$persistenceRequired(state.originalPersistenceRequired);
        // The control relationship ends with the control, so no stale target is left behind.
        mob.setTarget(null);
        mob.setDeltaMovement(Vec3.ZERO);
        // And the durable record of that control goes with it: a unit that is released now needs no restore
        // after its next save.
        CompanionRestoreMarker.clear(mob);
    }

    /** Give one companion back: its control flags and pose, its slot, its claim. Idempotent. */
    private void releaseCompanion(EnemyState state, boolean battleEnd) {
        if (state == null || state.mob == null) return;
        Mob mob = state.mob;
        UUID id = mob.getUUID();
        restoreControlFlags(state);
        if (state.companionAdapter != null) {
            try {
                state.companionAdapter.restorePose(mob, state.companionPose);
            } catch (RuntimeException failure) {
                Tactical.LOGGER.error("[Tactical] companion pose restore failed for {}", id, failure);
            }
        }
        state.companionAdapter = null;
        state.companionOwner = null;
        state.companionPose = null;
        state.defending = false;
        departedCompanions.add(id);
        terrainFall.end(id);
        queue.remove(id.toString());
        BattleEffects.forget(mob);
        ai.remove(mob);
        BattleManager.releaseActor(this, id);
        Tactical.LOGGER.info("[Tactical] companion released {} (battleEnd={})", id, battleEnd);
    }

    /**
     * Release every companion of one controller, because that controller can no longer give orders.
     *
     * @return true when the released companion was the acting unit, so the caller advances the turn once.
     */
    private boolean releaseCompanionsOf(UUID ownerId, boolean battleEnd) {
        if (ownerId == null) return false;
        boolean removedActor = false;
        for (EnemyState state : List.copyOf(enemies.values())) {
            if (state.companionAdapter == null || !ownerId.equals(state.companionOwner)) continue;
            if (state.mob.getUUID().toString().equals(queue.current())) removedActor = true;
            enemies.remove(state.mob.getUUID());
            releaseCompanion(state, battleEnd);
        }
        return removedActor;
    }

    /** Path cells this companion may walk to right now, using its own AP and its own move price. */
    public List<GridPos> companionReachable(LivingEntity companion) {
        EnemyState state = companion == null ? null : enemies.get(companion.getUUID());
        if (state == null || state.mob != companion || finished) return List.of();
        int maxSteps = state.moveCost <= 0 ? 0 : state.ap / state.moveCost;
        if (maxSteps <= 0) return List.of();
        try {
            return new ArrayList<>(navigator.reachable(state.cell, maxSteps, this::passable, (from, to) -> true).keySet());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion reachable scan failed for {}", companion.getUUID(), failure);
            return List.of();
        }
    }

    /**
     * Move one companion along the existing ground grid path.
     *
     * <p>This is the AI's own path and its own per-cell animation, without the drop/leap shortcut the player
     * body offers, and it charges this companion's AP for the real number of cells. Completion keeps the turn
     * open: the controller decides whether to attack, move again or hand the turn over.</p>
     */
    private boolean companionMove(EnemyState companion, GridPos target) {
        if (!companionAllows(companion, CompanionAbility.MOVE)) {
            rejectUndeclared(companion, CompanionAbility.MOVE);
            return false;
        }
        if (target == null) return false;
        if (!zone.contains(target) || occupied(target)
                || !LevelGridWorld.isStandable(level, target.x(), target.y(), target.z())) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_MOVE_TARGET));
            return false;
        }
        if (companion.moveCost <= 0) {
            reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return false;
        }
        List<GridPos> path;
        try {
            path = navigator.path(companion.cell, target, this::passable, 8192, true);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] companion path failed for {}", companion.mob.getUUID(), failure);
            reject(BattleText.translatable(BattleText.KEY_REJECT_NO_ROUTE));
            return false;
        }
        if (path.isEmpty()) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_NO_ROUTE));
            return false;
        }
        // Two ints multiplied could overflow into a refund; the price is compared as a long and only then
        // narrowed, and the path never exceeds what the companion can pay.
        long total = (long) path.size() * (long) companion.moveCost;
        if (total > companion.ap || total > Integer.MAX_VALUE) {
            reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return false;
        }
        int cost = (int) total;
        companion.ap -= cost;
        actionCount++;
        enemyDiveAttackIndex = -1;
        enemyDiveAttacked = false;
        enemyDiveReservedCost = 0;
        enemyPath = List.copyOf(path);
        enemyPathIndex = 0;
        enemyStepping = false;
        enemyMoving = true;
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_WALK,
                BattleText.name(companion.mob),
                BattleText.literal(companion.cell.toString()),
                BattleText.literal(path.get(path.size() - 1).toString()), cost));
        sendState();
        return true;
    }

    /** Attack one explicitly chosen legal hostile participant with the companion's own native attack. */
    private void companionAttack(EnemyState companion, UUID targetId) {
        if (!companionAllows(companion, CompanionAbility.ATTACK)) {
            rejectUndeclared(companion, CompanionAbility.ATTACK);
            return;
        }
        if (finished || rangedBusy()) return;
        EnemyState target = targetId == null ? null : enemies.get(targetId);
        if (target == null || target == companion || target.side == BattleSide.PLAYER
                || !target.mob.isAlive() || target.mob.isRemoved()
                || !opposed(companion.mob, target.mob)) return;
        if (!inMeleeRange(companion.cell, target.cell)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_MELEE_RANGE));
            return;
        }
        int cost = mobMeleeCost(companion);
        if (companion.ap < cost) {
            reject(BattleText.translatable(BattleText.KEY_AP_NOT_ENOUGH));
            return;
        }
        if (!hasLineOfSight(companion.cell, target.cell, companion.mob)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_BLOCKED));
            return;
        }
        // The single melee exit below already records the blow and swings the arm, so nothing is
        // written or swung twice here; only the order's own cost and bookkeeping follow it.
        faceDirection(companion.mob, target.cell);
        if (!performMelee(companion, target.mob)) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_MELEE_NO_EFFECT));
            return;
        }
        companion.ap -= cost;
        companion.paidActions++;
        actionCount++;
        removeDeadEnemies();
        if (!finished) sendState();
    }

    /** The shared guard: 25 AP, one of four facings, then the turn goes over. */
    private void companionDefend(EnemyState companion, int yaw) {
        if (!companionAllows(companion, CompanionAbility.DEFEND)) {
            rejectUndeclared(companion, CompanionAbility.DEFEND);
            return;
        }
        if (companion.ap < DEFEND_COST) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_DEFEND_AP, DEFEND_COST));
            sendState();
            return;
        }
        if (yaw != 0 && yaw != 90 && yaw != 180 && yaw != 270) {
            reject(BattleText.translatable(BattleText.KEY_REJECT_DEFEND_FACING));
            return;
        }
        setActorFacing(companion.mob, yaw);
        companion.ap -= DEFEND_COST;
        companion.defending = true;
        actionCount++;
        journal.record("block", BattleText.translatable(BattleText.KEY_BLOCK,
                BattleText.translatable(BattleText.KEY_BLOCK_STANCE),
                DEFEND_COST, BattleText.literal(Integer.toString(yaw))));
        advanceTurn();
    }

    /** Hand the companion's turn over, optionally after facing a chosen direction. Costs nothing. */
    private void companionEndTurn(EnemyState companion, Integer yaw) {
        if (!companionAllows(companion, CompanionAbility.END_TURN)) {
            // The order set is a rule, so an undeclared end-turn is refused like any other order and the turn
            // does not move. Declaring it is part of the adapter contract, which the admission warns about.
            rejectUndeclared(companion, CompanionAbility.END_TURN);
            return;
        }
        if (yaw != null) setActorFacing(companion.mob, yaw);
        advanceTurn();
    }

    // ------------------------------------------------------------------
    // turn flow
    // ------------------------------------------------------------------

    private void beginTurn(String actorId) {
        if (actorId == null) {
            finish(BattleText.endReason(BattleText.End.EMPTY_QUEUE));
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
                removeMember(member, BattleText.exitReason(BattleText.Exit.DEFEATED));
                return;
            }
            phase = BattlePhase.PLAYER_TURN;
            TacticalSkills.onTurnStart(this, member.entity);
            BattleEffects.onTurnStart(member.entity);
            if(!member.entity.isAlive()) { removeMember(member, BattleText.exitReason(BattleText.Exit.DEFEATED)); return; }
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
            boolean companion = enemy.companionAdapter != null;
            phase = companion ? BattlePhase.COMPANION_TURN : BattlePhase.ENEMY_TURN;
            // A new own turn starts with no target lock and no replan allowance from the previous turn.
            enemy.lockedTarget = null;
            enemy.targetReplans = 0;
            if (enemy.mob.isAlive() && !enemy.mob.isRemoved() && enemy.mob.level() == level) {
                // One own turn really began: this is the only thing that advances the witch drink cooldown.
                enemy.ownTurns++;
                TacticalSkills.onTurnStart(this, enemy.mob);
                BattleEffects.onTurnStart(enemy.mob);
                if(!enemy.mob.isAlive()) {removeDeadEnemies();return;}
                enemy.maxAp=TacticalAttributes.maxAp(enemy.mob);
                enemy.moveCost=ApCalculator.moveCost(attribute(enemy.mob,Attributes.MOVEMENT_SPEED));
                enemy.ap=ApCalculator.beginOwnTurnAp(enemy.ap,enemy.maxAp,TacticalAttributes.apRecovery(enemy.mob),!enemy.hasTakenTurn);
                enemy.hasTakenTurn=true;
                enemy.paidActions=0;
                enemy.skillUsed=false;
                // A guard lasts until this actor's own next turn begins, for every actor that can raise one.
                enemy.defending=false;
            }
            aiDelay = companion ? 0 : AI_TURN_DELAY;
            enemyMoving = false;
            enemyStepping = false;
            enemyPath = List.of();
        }
        // The turn row names the actor as structure, so a vanilla mob keeps its registry key here too.
        journal.record("turn", BattleText.translatable(BattleText.KEY_TURN, nameComponent(actorId)));
        sendState();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new com.tactical.neoforge.skill.SkillStateChangedEvent(this, true));
    }

    /**
     * Advance the turn queue, unless units are still in the air.
     *
     * <p>A forced fall is a barrier for turn flow, not only for commands: nobody may start a new turn and
     * collect its recovery while a combatant is mid-fall. A death or an exit that happens during the fall
     * still takes effect immediately - the member is removed - but the new turn waits until the last fall
     * has settled, which is what keeps the queue and the resources in one order.</p>
     */
    private void advanceTurn() {
        if (finished) return;
        if (terrainFall.busy()) {
            pendingTurnAdvance = true;
            return;
        }
        pendingTurnAdvance = false;
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
        // The same bounded cadence picks up a companion that walked into the zone or was tamed mid-battle.
        recruitCompanions();
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
                EnemyState departed = entry.getValue();
                iterator.remove();
                dropBlazeVolleyFor(entry.getKey(), departed);
                if (departed.companionAdapter != null) {
                    // Death, removal and an unloaded chunk all end this companion's control. The release path
                    // is idempotent and gives the unit's own flags back without teleporting or healing it.
                    releaseCompanion(departed, false);
                } else {
                    terrainFall.end(entry.getKey());
                    queue.remove(entry.getKey().toString());
                    BattleEffects.forget(departed.mob);
                    ai.remove(departed.mob);
                    // A departed unit gives up its ownership claim, but only this encounter's: another
                    // encounter's identical UUID or a same-UUID respawn must keep its own routing.
                    BattleManager.releaseActor(this, entry.getKey());
                    departed.mob.setNoAi(departed.originalNoAi);
                    departed.mob.setTarget(null);
                    // Death and removal only drop this mod's transient potion state; a dead unit is given no
                    // potion back, so leaving a battle never invents an item that vanilla would not drop.
                    releaseWitchState(departed);
                }
                changed = true;
            }
        }
        // Reuse newly freed capacity before victory/turn selection, even when the old
        // roster was emptied in this tick. Do not force a player into a second battle.
        if(changed)recruitNearbyAggro();
        if (hostileCount() == 0) {
            finish(BattleText.endReason(BattleText.End.ENEMIES_DEFEATED));
        } else if (removedCurrent && !finished
                && (phase == BattlePhase.ENEMY_TURN || phase == BattlePhase.COMPANION_TURN)) {
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
        journal.record("action",BattleText.translatable(BattleText.KEY_ADMIN_AP, before, state.ap()));
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

    private int mobMeleeCost(EnemyState enemy) { return quote(intConfig(TacticalConfig.INSTANCE.meleeApCost,ApCalculator.MELEE_COST),enemy.paidActions); }

    private int mobBowCost(EnemyState enemy) { return quote(intConfig(TacticalConfig.INSTANCE.bowApCost,BattleRules.BOW_COST),enemy.paidActions); }

    private int mobCrossbowCost(EnemyState enemy) { return quote(intConfig(TacticalConfig.INSTANCE.crossbowApCost,BattleRules.CROSSBOW_COST),enemy.paidActions); }

    /**
     * Quoted price of this actor's next ranged main action, for the weapon it really holds.
     *
     * <p>0 means it has no ranged weapon at all, so a shot is never affordable; the actor falls back to its
     * original melee instead of being given a weapon by entity type.</p>
     */
    private int mobRangedCost(EnemyState enemy) {
        MobRangedLoadout loadout = mobLoadout(enemy.mob);
        if (loadout == null) return 0;
        return switch (loadout.weapon()) {
            case BOW -> mobBowCost(enemy);
            case CROSSBOW -> mobCrossbowCost(enemy);
        };
    }

    /** Read-only quotes; previewing an intent never reserves AP or advances the counter. */
    private int currentMeleeCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.meleeApCost,ApCalculator.MELEE_COST),member.state.paidActions()); }
    private int currentBowCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.bowApCost,BattleRules.BOW_COST),member.state.paidActions()); }
    private int currentCrossbowCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.crossbowApCost,BattleRules.CROSSBOW_COST),member.state.paidActions()); }
    private int currentItemCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.itemApCost,ApCalculator.ITEM_COST),member.state.paidActions()); }
    private int currentPlaceCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.placeApCost,ApCalculator.PLACE_COST),member.state.paidActions()); }
    private int currentBreakCost(PlayerMember member) { return quote(intConfig(TacticalConfig.INSTANCE.breakApCost,ApCalculator.BREAK_COST),member.state.paidActions()); }

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
    public int currentBreakCost() { PlayerMember m=actingMember(); return m==null?0:currentBreakCost(m); }
    public int currentAttackCost() { PlayerMember m=actingMember(); return m==null?0:currentAttackCost(m.entity); }
    public int successfulPaidActions() { PlayerMember m=actingMember(); return m==null?0:m.state.paidActions(); }
    public int successfulPaidActions(ServerPlayer player) { return paidActions(player); }
    public int quoteAttackCost() { return currentAttackCost(); }
    public int quoteItemCost() { return currentItemCost(); }
    public int quotePlaceCost() { return currentPlaceCost(); }
    public int quoteBreakCost() { return currentBreakCost(); }
    public int paidActionCount() { return successfulPaidActions(); }

    private boolean rangedBusy() {
        // A running blaze salvo owns the turn exactly like a tracked projectile does: charge, shots and the
        // hand-back are one bounded action, so nothing else may act or advance while it lasts.
        return projectiles.pending() || awaitingShotResolution || blazeVolley != null;
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

    /**
     * The native legality of one weapon attack, as display structure.
     *
     * <p>Every check below is the same decision as before; only the wording became structure, so a
     * rejection that reaches a player (through the skill reason chain or the attack-range list) is
     * resolved on that player's client. An empty component means "permitted".</p>
     */
    public net.minecraft.network.chat.Component previewWeaponAttack(LivingEntity caster, LivingEntity target) {
        BattleSide casterSide = sideOf(caster), targetSide = sideOf(target);
        if (casterSide == null || targetSide == null || !caster.isAlive() || !target.isAlive()
                || caster.isRemoved() || target.isRemoved() || caster.level() != level || target.level() != level)
            return refusal(SkillText.KEY_REJECT_NOT_IN_BATTLE);
        // Identity-bearing opposition: never side-only, so a faction policy applies here too.
        if (!opposed(caster, target)) return refusal(SkillText.KEY_REJECT_FRIENDLY_FIRE);
        GridPos from = participantCell(caster), to = participantCell(target);
        if (caster instanceof ServerPlayer player) {
            PlayerMember member = member(player);
            if (member == null || from == null) return refusal(SkillText.KEY_REJECT_NOT_IN_BATTLE);
            int kind = weaponKind(player);
            if (kind == 0) return inMeleeRange(from,to) && hasLineOfSight(from,to,caster)
                    ? allowedAttack() : refusal(SkillText.KEY_REJECT_MELEE_BLOCKED);
            if (Math.hypot(from.x()-to.x(),from.z()-to.z()) > weaponRange(from, to, kind)+.001)
                return refusal(SkillText.KEY_REJECT_OUT_OF_RANGE);
            ItemStack weapon = player.getMainHandItem();
            boolean loaded = kind == 2 && CrossbowItem.isCharged(weapon);
            ChargedProjectiles charged = weapon.getOrDefault(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.EMPTY);
            boolean rocket = kind == 2 && (loaded ? charged.contains(Items.FIREWORK_ROCKET) : player.getProjectile(weapon).is(Items.FIREWORK_ROCKET));
            int pierce = kind == 2 ? net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(
                    level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PIERCING), weapon) : 0;
            return BallisticShot.plan(level,player,from,target,kind==2?(rocket?1.6F:3.15F):3F,!rocket,pierce) != null
                    ? allowedAttack() : refusal(SkillText.KEY_REJECT_BALLISTIC_BLOCKED);
        }
        Mob mob = (Mob) caster;
        EnemyState state = enemies.get(mob.getUUID());
        MobRangedLoadout loadout = mobLoadout(mob);
        if (loadout != null && state != null) {
            // Same lock and the same weapon parameters the real shot will use.
            return canShootFrom(state, target, from) ? allowedAttack()
                    : refusal(SkillText.KEY_REJECT_OUT_OF_RANGE_OR_BLOCKED);
        }
        if (loadout != null) {
            // Not a participant of this encounter: there is no target lock and no battle cost, so only the
            // world checks of its own weapon apply.
            ItemStack held = loadout.stack(mob);
            return Math.hypot(from.x()-to.x(),from.z()-to.z()) <= 16.001
                    && BallisticShot.plan(level, mob, from, target, loadout.weapon(),
                            piercingOf(held, ammoFor(mob, loadout, held))) != null
                    ? allowedAttack() : refusal(SkillText.KEY_REJECT_OUT_OF_RANGE_OR_BLOCKED);
        }
        return aiContext(state).inMeleeRange(from,to) && hasLineOfSight(from,to,caster)
                ? allowedAttack() : refusal(SkillText.KEY_REJECT_MELEE_BLOCKED);
    }

    /** Whether the native weapon checks permit this attack; never decided by resolved text. */
    public boolean weaponAttackAllowed(LivingEntity caster, LivingEntity target) {
        return SkillText.isEmpty(previewWeaponAttack(caster, target));
    }

    private static net.minecraft.network.chat.Component allowedAttack() {return SkillText.none();}
    private static net.minecraft.network.chat.Component refusal(String key) {return SkillText.translatable(key);}

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

    // ------------------------------------------------------------------
    // forced fall: the block under a unit is gone
    // ------------------------------------------------------------------

    /**
     * One encounter's own falling units.
     *
     * <p>A member is pinned to its grid cell every tick, so a removed support would otherwise leave it
     * hovering in the air forever. The fall is its own state, never a paid move: it charges no AP, counts
     * no successful action and restores nothing. While any unit of this encounter is falling the
     * encounter is busy, so no new command is accepted and the turn queue does not advance.</p>
     */
    private final com.tactical.neoforge.battle.terrain.TerrainFall terrainFall =
            new com.tactical.neoforge.battle.terrain.TerrainFall();

    /** True while some unit of this encounter is still falling. */
    public boolean terrainFallActive() { return terrainFall.busy(); }

    /** A turn advance that arrived while a unit was falling; it runs once the last fall settles. */
    private boolean pendingTurnAdvance;

    /**
     * Start a fall for every unit of this encounter that has really lost its support.
     *
     * <p>Only this encounter's own units are inspected - never the whole world - and a unit that holds
     * itself up (it flies, swims, climbs, rides or glides) is left alone: its own movement rules already
     * answer what happens next.</p>
     */
    private void beginTerrainFallIfNeeded() {
        for (PlayerMember member : List.copyOf(members.values())) {
            if (member.state.moving()) continue;
            startFallIfUnsupported(member.id(), true, member.state.cell(), member.entity);
        }
        for (EnemyState enemy : List.copyOf(enemies.values())) {
            if (enemyMoving && enemy.mob.getUUID().toString().equals(queue.current())) continue;
            startFallIfUnsupported(enemy.mob.getUUID(), false, enemy.cell, enemy.mob);
        }
    }

    private void startFallIfUnsupported(UUID id, boolean player, GridPos cell, LivingEntity unit) {
        if (id == null || unit == null || cell == null) return;
        if (!unit.isAlive() || unit.isRemoved() || unit.level() != level) return;
        if (terrainFall.unit(id) != null) return;
        if (!zone.contains(cell)) return;
        if (holdsItselfUp(unit)) return;
        if (hasSupportBelow(cell)) return;
        TerrainFall.Unit falling = terrainFall.begin(id, player, cell);
        // The landing is a reservation that keeps two falling units apart, and it is recomputed from the
        // live world for as long as the descent lasts.
        refreshFallLanding(falling);
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL_BEGIN,
                BattleText.name(unit), BattleText.literal(cell.toString())));
        sendState();
    }

    /** A unit that carries itself - flight, water, a ladder, a glider, a mount - is not affected. */
    private static boolean holdsItselfUp(LivingEntity unit) {
        if (unit == null) return true;
        if (AerialMovement.supports(unit)) return true;
        if (unit.isPassenger() || unit.isFallFlying()) return true;
        if (unit.isInWater() || unit.isInLava() || unit.onClimbable()) return true;
        return unit instanceof ServerPlayer player && player.getAbilities().flying;
    }

    private boolean hasSupportBelow(GridPos cell) {
        BlockPos ground = new BlockPos(cell.x(), cell.y() - 1, cell.z());
        return !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty();
    }

    private boolean standableCell(GridPos cell) {
        return standableIgnoring(cell, null);
    }

    /**
     * {@link LevelGridWorld#isStandable} with one position treated as already removed.
     *
     * <p>The pre-check of a break has to answer "what will be standable once this block is gone" while the
     * block is still there, and the descent has to answer the same question against the real world. One
     * implementation for both, so the promise made before the break is the promise the fall keeps.</p>
     */
    private boolean standableIgnoring(GridPos cell, BlockPos removed) {
        return standableIgnoring(cell, removed, null);
    }

    /**
     * The same question for an action that removes <em>two</em> blocks at once.
     *
     * <p>A door pair is one object, so the fall question has to be asked of the world in which both halves are
     * already gone. Asking it about one half would let the other half still count as somebody's landing
     * support, which is exactly what the removal then takes away.</p>
     */
    private boolean standableIgnoring(GridPos cell, BlockPos removed, BlockPos alsoRemoved) {
        if (cell.y() <= level.getMinBuildHeight() || cell.y() >= level.getMaxBuildHeight() - 1) return false;
        BlockPos feet = new BlockPos(cell.x(), cell.y(), cell.z());
        BlockPos head = feet.above();
        if (!collides(feet.below(), removed, alsoRemoved)) return false;
        if (collides(feet, removed, alsoRemoved)) return false;
        if (collides(head, removed, alsoRemoved)) return false;
        return level.getFluidState(feet).isEmpty() && level.getFluidState(head).isEmpty();
    }

    private boolean collides(BlockPos pos, BlockPos removed) {
        return collides(pos, removed, null);
    }

    private boolean collides(BlockPos pos, BlockPos removed, BlockPos alsoRemoved) {
        if (removed != null && removed.equals(pos)) return false;
        if (alsoRemoved != null && alsoRemoved.equals(pos)) return false;
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private boolean enterableCell(GridPos cell) {
        BlockPos feet = new BlockPos(cell.x(), cell.y(), cell.z());
        BlockPos head = feet.above();
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(head).getCollisionShape(level, head).isEmpty();
    }

    private boolean occupiedByOther(GridPos cell, UUID except) {
        for (PlayerMember member : members.values()) {
            if (member.id().equals(except)) continue;
            if (member.state.cell().equals(cell)) return true;
        }
        for (EnemyState enemy : enemies.values()) {
            if (enemy.mob.getUUID().equals(except)) continue;
            if (enemy.mob.isAlive() && !enemy.mob.isRemoved() && enemy.cell.equals(cell)) return true;
        }
        return false;
    }

    /** The units standing on exactly this block; a grid cell holds one unit, so this is at most one. */
    private List<UUID> unitsStandingOn(BlockPos broken) {
        return unitsStandingOn(broken, null);
    }

    /** The units standing on either block, for an action that removes both of them. */
    private List<UUID> unitsStandingOn(BlockPos broken, BlockPos alsoBroken) {
        List<UUID> ids = new ArrayList<>(2);
        for (BlockPos block : alsoBroken == null ? List.of(broken) : List.of(broken, alsoBroken)) {
            GridPos above = new GridPos(block.getX(), block.getY() + 1, block.getZ());
            for (PlayerMember member : members.values()) {
                if (member.state.cell().equals(above) && !ids.contains(member.id())) ids.add(member.id());
            }
            for (EnemyState enemy : enemies.values()) {
                if (enemy.mob.isAlive() && !enemy.mob.isRemoved() && enemy.cell.equals(above)
                        && !ids.contains(enemy.mob.getUUID())) {
                    ids.add(enemy.mob.getUUID());
                }
            }
        }
        return ids;
    }

    /**
     * Before a support goes away: every unit that would lose it must have a legal landing in this zone.
     *
     * <p>It refuses instead of acting when a column has no landing at all, ends outside the zone or ends
     * on a cell another unit already holds - a break may not drop somebody out of the battle, and two
     * falling units may not arrive in the same cell.</p>
     */
    private boolean precheckLosingSupport(BlockPos broken) {
        return precheckLosingSupport(broken, null);
    }

    /**
     * Before one action removes <em>both</em> blocks: every unit that would lose its footing must have a legal
     * landing in this zone, in the world where both are already gone.
     *
     * <p>A door pair is one object here as well: simulating only the upper half disappear would leave the
     * lower half standing in the simulated world and let it count as a landing spot that this action then
     * takes away too.</p>
     */
    private boolean precheckLosingSupport(BlockPos broken, BlockPos alsoBroken) {
        for (UUID id : unitsStandingOn(broken, alsoBroken)) {
            LivingEntity unit = participantEntity(id);
            if (unit == null || holdsItselfUp(unit)) continue;
            GridPos from = participantCellOf(id);
            if (from == null) continue;
            boolean found = com.tactical.neoforge.battle.terrain.FallPlanner.resolve(from,
                    cell -> standableIgnoring(cell, broken, alsoBroken), zone::contains,
                    cell -> occupiedByOther(cell, id) || terrainFall.claimedByOther(cell, id)).isPresent();
            if (!found) return false;
        }
        return true;
    }

    private LivingEntity participantEntity(UUID id) {
        PlayerMember member = members.get(id);
        if (member != null) return member.entity;
        EnemyState enemy = enemies.get(id);
        return enemy == null ? null : enemy.mob;
    }

    private GridPos participantCellOf(UUID id) {
        PlayerMember member = members.get(id);
        if (member != null) return member.state.cell();
        EnemyState enemy = enemies.get(id);
        return enemy == null ? null : enemy.cell;
    }

    /** One tick of every fall in flight; true while the encounter must stay busy. */
    private boolean stepTerrainFall() {
        if (!terrainFall.busy()) return false;
        for (TerrainFall.Unit unit : terrainFall.units()) {
            unit.advance();
            if (unit.expired()) {
                abandonTerrainFall(unit);
                continue;
            }
            if (unit.player()) stepFallingPlayer(unit);
            else stepFallingEnemy(unit);
        }
        if (!terrainFall.busy()) {
            if (pendingTurnAdvance) {
                // The barrier lifts: a turn advance that was refused while units were in the air runs now,
                // in the same tick the last one landed, so the queue never stalls on a removed actor.
                // beginTurn broadcasts the cleared flag itself.
                pendingTurnAdvance = false;
                if (!finished) advanceTurn();
                else sendState();
                return true;
            }
            // The last unit landed: the snapshot carries the busy flag, so one broadcast clears "waiting for
            // the units to land" for every client in this same tick. The tick then continues normally, which
            // is what re-pins the landed body at the cell it actually reached.
            sendState();
            return false;
        }
        return true;
    }

    private void stepFallingPlayer(TerrainFall.Unit unit) {
        PlayerMember member = members.get(unit.id());
        if (member == null) { terrainFall.end(unit.id()); return; }
        ServerPlayer player = member.entity;
        if (!player.isAlive() || player.isRemoved() || player.level() != level) { terrainFall.end(unit.id()); return; }
        if (unit.stepTo() == null) {
            // Terrain and the roster can both change while a unit is in the air, so the landing is asked
            // again before every decision instead of trusting the one the pre-check promised.
            refreshFallLanding(unit);
            com.tactical.neoforge.battle.terrain.FallPlanner.Step step =
                    com.tactical.neoforge.battle.terrain.FallPlanner.next(unit.cell(), this::hasSupportBelow,
                            cell -> enterableForFall(cell, unit.id()));
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.LAND) {
                settlePlayerFall(unit, member);
                return;
            }
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.STUCK) {
                // No floor under the unit and the cell below refuses it (a block or another combatant):
                // waiting is not landing. The deadline keeps running, so a column that stays blocked ends
                // in the explicit exception path instead of a fake landing that re-pins the body and
                // restarts the timer.
                return;
            }
            GridPos next = below(unit.cell());
            if (!zone.contains(next)) { abandonTerrainFall(unit); return; }
            unit.beginStep(unit.cell(), next, fallStepTicks());
            // The owning client follows this one-cell descent through the fall's own trajectory packet:
            // never the paid move's state, which a player who has not moved this turn has not filled in.
            sendFallMotion(member, unit.stepFrom(), unit.stepTo(), fallStepTicks());
        }
        double sliceFrom = fallFraction(unit, unit.progress());
        double sliceTo = fallFraction(unit, unit.progress() + 1);
        if (!clearFallSlice(unit, player, sliceFrom, sliceTo)) {
            // Blocked mid-step: terrain or a combatant entered the segment the body was already moving
            // through. The body stays at its last verified sample, the client stops interpolating a target
            // that no longer holds, and the next tick re-plans from the integer cell it still occupies -
            // the fall keeps its own deadline instead of being restarted.
            unit.endStep();
            cancelFallMotion(member);
            return;
        }
        unit.progress(unit.progress() + 1);
        setPlayerPosition(member, DropClearance.sample(unit.stepFrom(), unit.stepTo(), unit.fraction()));
        if (unit.fraction() >= 1.0D) {
            GridPos arrived = unit.stepTo();
            unit.stepLanded(arrived);
            member.state.cell(arrived);
        }
    }

    private void stepFallingEnemy(TerrainFall.Unit unit) {
        EnemyState enemy = enemies.get(unit.id());
        if (enemy == null || !enemy.mob.isAlive() || enemy.mob.isRemoved()) { terrainFall.end(unit.id()); return; }
        if (unit.stepTo() == null) {
            refreshFallLanding(unit);
            com.tactical.neoforge.battle.terrain.FallPlanner.Step step =
                    com.tactical.neoforge.battle.terrain.FallPlanner.next(unit.cell(), this::hasSupportBelow,
                            cell -> enterableForFall(cell, unit.id()));
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.LAND) {
                settleEnemyFall(unit, enemy);
                return;
            }
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.STUCK) return;
            GridPos next = below(unit.cell());
            if (!zone.contains(next)) { abandonTerrainFall(unit); return; }
            unit.beginStep(unit.cell(), next, fallStepTicks());
        }
        double sliceFrom = fallFraction(unit, unit.progress());
        double sliceTo = fallFraction(unit, unit.progress() + 1);
        if (!clearFallSlice(unit, enemy.mob, sliceFrom, sliceTo)) {
            // The same sweep the player path runs: a mob may not be pushed through a block or another
            // unit that entered its segment, and its fall keeps the same deadline while it re-plans.
            unit.endStep();
            return;
        }
        unit.progress(unit.progress() + 1);
        Vec3 position = DropClearance.sample(unit.stepFrom(), unit.stepTo(), unit.fraction());
        enemy.mob.setPos(position.x, position.y, position.z);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        if (unit.fraction() >= 1.0D) {
            GridPos arrived = unit.stepTo();
            unit.stepLanded(arrived);
            enemy.cell = arrived;
        }
    }

    private void settlePlayerFall(TerrainFall.Unit unit, PlayerMember member) {
        GridPos landing = unit.cell();
        GridPos origin = unit.origin();
        int cells = unit.cells();
        terrainFall.end(unit.id());
        member.state.cell(landing);
        snapPlayer(member, landing);
        settleFallDamage(member.entity, landing, cells);
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL,
                BattleText.name(member.entity), BattleText.literal(origin.toString()),
                BattleText.literal(landing.toString()), cells));
        // A landing can kill: the existing death path decides the turn, and no fall state may outlive it.
        removeDeadEnemies();
        if (!finished) sendState();
    }

    private void settleEnemyFall(TerrainFall.Unit unit, EnemyState enemy) {
        GridPos landing = unit.cell();
        GridPos origin = unit.origin();
        int cells = unit.cells();
        terrainFall.end(unit.id());
        enemy.cell = landing;
        moveEntity(enemy.mob, landing);
        settleFallDamage(enemy.mob, landing, cells);
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL,
                BattleText.name(enemy.mob), BattleText.literal(origin.toString()),
                BattleText.literal(landing.toString()), cells));
        removeDeadEnemies();
        if (!finished) sendState();
    }

    /**
     * Settle a scripted landing through the vanilla chain.
     *
     * <p>Nothing here decides a threshold. The block that receives the unit gets its own {@code fallOn} -
     * where hay, slime, beds and every other landing rule live - and that call reaches
     * {@code causeFallDamage}, which applies the fall event, the safe-fall-distance and fall-multiplier
     * attributes, and armour/enchantment reduction inside {@code hurt}. Only what vanilla decides outside
     * that call is decided here: slow falling and levitation, which in vanilla reset the fall distance
     * while travelling, and a player's own flight.</p>
     */
    private void settleFallDamage(LivingEntity unit, GridPos landing, int cells) {
        boolean immune = com.tactical.neoforge.battle.terrain.FallPlanner.immuneToFallDamage(
                unit.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING),
                unit.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),
                unit instanceof ServerPlayer player && (player.getAbilities().flying || player.getAbilities().mayfly),
                unit.isFallFlying());
        double distance = com.tactical.neoforge.battle.terrain.FallPlanner.settlementDistance(cells, immune);
        // The descent was scripted, so the vanilla accumulator is not what says how far the unit fell.
        unit.fallDistance = 0.0F;
        if (distance <= 0.0D) return;
        BlockPos ground = new BlockPos(landing.x(), landing.y() - 1, landing.z());
        BlockState landedOn = level.getBlockState(ground);
        landedOn.getBlock().fallOn(level, landedOn, ground, unit, (float) distance);
    }

    /**
     * A fall that outlived its deadline is an exception, not a landing.
     *
     * <p>It is logged, broadcast to the encounter, and the affected unit is taken out of this encounter -
     * roster, queue and occupancy - so vanilla physics owns its position again. The break that started it
     * stays paid: nothing is refunded and no item is duplicated.</p>
     */
    private void abandonTerrainFall(TerrainFall.Unit unit) {
        terrainFall.end(unit.id());
        Tactical.LOGGER.error("[Tactical] forced fall did not settle within {} ticks: {} {} from {} at {} ({} cells); "
                        + "releasing it to vanilla physics", TerrainFall.TIMEOUT_TICKS,
                unit.player() ? "player" : "mob", unit.id(), unit.origin(), unit.cell(), unit.cells());
        Component reason = BattleText.translatable(BattleText.KEY_FALL_ABANDONED);
        journal.record("reject", reason);
        if (unit.player()) {
            PlayerMember member = members.get(unit.id());
            if (member != null) removeMember(member, reason);
        } else {
            EnemyState enemy = enemies.get(unit.id());
            if (enemy != null) {
                String id = unit.id().toString();
                boolean acting = id.equals(queue.current());
                dropBlazeVolleyFor(unit.id(), enemy);
                enemies.remove(unit.id());
                queue.remove(id);
                BattleEffects.forget(enemy.mob);
                ai.remove(enemy.mob);
                BattleManager.releaseActor(this, unit.id());
                enemy.mob.setNoAi(enemy.originalNoAi);
                enemy.mob.setTarget(null);
                if (acting && !finished) advanceTurn();
            }
        }
        if (!finished) sendState();
    }

    /**
     * Re-resolve a fall's landing against the live world and the live roster.
     *
     * <p>Only a reservation: it keeps two falling units from choosing one cell, and a landing that has
     * become impossible is simply dropped rather than remembered.</p>
     */
    private void refreshFallLanding(TerrainFall.Unit unit) {
        unit.landing(com.tactical.neoforge.battle.terrain.FallPlanner
                .resolve(unit.cell(), this::standableCell, zone::contains,
                        cell -> occupiedByOther(cell, unit.id()) || terrainFall.claimedByOther(cell, unit.id()))
                .map(com.tactical.neoforge.battle.terrain.FallPlanner.Landing::cell).orElse(null));
    }

    /**
     * Whether a falling body may move into this cell.
     *
     * <p>Blocks alone are not enough: another combatant, a member already animating into that cell, and
     * another fall's reservation all make it unusable, which is what stops a fall from passing through or
     * stacking onto somebody.</p>
     */
    private boolean enterableForFall(GridPos cell, UUID unitId) {
        return enterableCell(cell) && !reserved(cell) && !terrainFall.claimedByOther(cell, unitId);
    }

    /**
     * The forced fall's own trajectory packet.
     *
     * <p>It is built from the fall state, never from the paid-move fields: a member that has not moved
     * this turn has {@code motionTicks == 0}, which {@code PlayerStepPacket} refuses outright, and the fall
     * is a drop with its own per-cell duration. Start cell, single entered cell and duration all come from
     * the same numbers the server samples, so the client animates exactly the server's trajectory.</p>
     */
    private void sendFallMotion(PlayerMember member, GridPos from, GridPos to, int ticks) {
        if (member == null || from == null || to == null) return;
        ServerPlayer player = member.entity;
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                new PlayerStepPacket(from, List.of(to), Math.max(1, Math.min(200, ticks)), player.getYRot(), true));
    }

    /**
     * Sweep this tick's slice of the fall's real trajectory.
     *
     * <p>The integer plan is not the whole truth: a block or another combatant can enter the segment a
     * body is already moving through, and a cell-by-cell plan would keep setting the position straight
     * through it. This is the same collision sweep - real bounding boxes, living entities excluded except
     * the mover - that the client's drop animation mirrors, so the checked curve and the drawn curve are
     * one curve.</p>
     */
    private boolean clearFallSlice(TerrainFall.Unit unit, LivingEntity body, double from, double to) {
        if (unit.stepFrom() == null || unit.stepTo() == null) return true;
        return DropClearance.clearTrajectorySegment(level, body, unit.stepFrom(), unit.stepTo(), from, to, true);
    }

    /** How far through the current one-cell descent that tick count is, clamped to the segment. */
    private static double fallFraction(TerrainFall.Unit unit, int progress) {
        int motion = Math.max(1, unit.motionTicks());
        return Math.min(1.0D, progress / (double) motion);
    }

    /**
     * Cancel what the owning client is still interpolating for this member.
     *
     * <p>Used when the segment it was told to animate stops being legal: the client must not keep flying
     * on to a target the server has already refused.</p>
     */
    private void cancelFallMotion(PlayerMember member) {
        if (member == null) return;
        ServerPlayer player = member.entity;
        GridPos cell = member.state.cell();
        if (cell == null) return;
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                new PlayerStepPacket(cell, List.of(), Math.max(1, Math.min(200, stepTicks)), player.getYRot(), false));
    }

    private static GridPos below(GridPos cell) {
        return new GridPos(cell.x(), cell.y() - 1, cell.z());
    }

    /** Two ticks per cell keeps a zone-deep fall inside the deadline while the descent stays visible. */
    private int fallStepTicks() {
        return Math.max(2, stepTicks - 1);
    }

    private static double attribute(LivingEntity entity,
                                    net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var instance = entity.getAttribute(attribute);
        return instance == null ? (attribute == Attributes.MOVEMENT_SPEED ? 0.1D : 1.0D) : instance.getValue();
    }

    private void reject(Component message) {
        journal.record("reject",message);
    }

    private void message(Component text) { journal.record("info",text); }

    public BattleJournal journal() { return journal; }
    public BattleZone zone() { return zone; }
    public com.tactical.neoforge.network.CreeperTelegraphPacket creeperSnapshot() { return ai.snapshot(); }
    private static GridPos cellOf(Entity entity) { return new GridPos(entity.getBlockX(),entity.getBlockY(),entity.getBlockZ()); }
    AABB zoneBounds() {
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
                0, 0, 0, new int[0], Component.empty(), List.of(), 0, Component.empty(), 0, 0,
                center.x(), center.y(), center.z(), zoneRadius, false, -1, new int[0], new int[0], 0, false,
                new int[0], 0, 0, 0, 0, 0, 0, false, session.sessionId(), 0L, 0L, 0L);
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

    /**
     * The same actor identity as {@link #nameOf}, but as the structure the client will read.
     *
     * <p>A player's profile name is the text somebody chose, so it stays a literal. A mob keeps its native
     * name component - its registry key when nobody renamed it, its own component (including nested
     * structure) when somebody did - so nothing is resolved here and a client that never had the entity in
     * tracking range still shows a readable name.</p>
     */
    private Component nameComponent(String id) {
        if (id == null) {
            return Component.empty();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException invalid) {
            return Component.empty();
        }
        PlayerMember member = members.get(uuid);
        if (member != null) return BattleText.name(member.name());
        EnemyState state = enemies.get(uuid);
        return state == null ? Component.literal("?") : BattleText.entityName(state.mob);
    }

    private int entityIdOf(UUID uuid) {
        PlayerMember member = members.get(uuid);
        if (member != null) return member.entity.getId();
        EnemyState state = enemies.get(uuid);
        return state == null ? -1 : state.mob.getId();
    }

    /** The parts of a snapshot every member shares; the per-member parts are added per recipient. */
    private record SharedSnapshot(List<Component> order, int[] gauges, int[] turnEntityIds, Component actorName,
                                  int actorEntityId, int[] participants, boolean anyMoving,
                                  UUID sessionId, long epoch) {
    }

    private SharedSnapshot sharedSnapshot() {
        List<Component> order = new ArrayList<>();
        int[] gauges = new int[queue.order().size()];
        int[] turnEntityIds = new int[queue.order().size()];
        int gaugeIndex = 0;
        for (String id : queue.order()) {
            UUID uuid = UUID.fromString(id);
            order.add(nameComponent(id));
            turnEntityIds[gaugeIndex] = entityIdOf(uuid);
            gauges[gaugeIndex++] = (int) Math.round(queue.gaugeOf(id) * 100.0D);
        }
        String actorId = queue.current();
        Component actorName = actorId == null ? Component.empty() : nameComponent(actorId);
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
                anyPlayerMoving() || enemyMoving || rangedBusy() || terrainFall.busy(),
                session.sessionId(), session.turnEpoch());
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
        // The command projection is per recipient: only the controller of the acting companion gets one, and
        // nobody else is ever told which companion they could order.
        EnemyState commandState = actingCompanion();
        boolean commandsCompanion = commandState != null && member.id().equals(commandState.companionOwner);
        // Own resources, own reachable set, own equipment and own quote counters. While commanding, the one
        // reachable set on the wire is the companion's - never a second array.
        List<GridPos> reachable = commandsCompanion ? companionReachable(commandState.mob) : reachableCells(player);
        int[] flattened = new int[reachable.size() * 3];
        int index = 0;
        for (GridPos cell : reachable) {
            flattened[index++] = cell.x();
            flattened[index++] = cell.y();
            flattened[index++] = cell.z();
        }
        int kind = weaponKind(player);
        // The held item travels as its own name component; an empty hand is a key, not a frozen word.
        Component weapon = BattleText.itemName(player.getMainHandItem());
        GridPos ownCell = state.cell();
        BattleStatePacket packet = new BattleStatePacket(
                true, phase.ordinal(), queue.turnCount(), state.ap(), state.maxAp(), state.moveCost(),
                ownCell.x(), ownCell.y(), ownCell.z(), flattened, shared.actorName(), shared.order(),
                queue.turnCount(), weapon, kind, kind == 0 ? 0 : weaponRangeForDisplay(kind),
                center.x(), center.y(), center.z(), zoneRadius,
                state.moving() || shared.anyMoving(), shared.actorEntityId(), shared.participants(),
                shared.gauges(), actionCount, state.defending(), shared.turnEntityIds(),
                currentAttackCost(player), currentItemCost(member), currentPlaceCost(member),
                currentBreakCost(member), TacticalAttributes.apRecovery(player), state.paidActions(),
                terrainFall.busy(), shared.sessionId(), shared.epoch(),
                // Stamped by ServerPackets for this recipient at delivery time.
                0L, 0L,
                commandsCompanion ? commandProjection(commandState) : BattleStatePacket.Command.NONE);
        // Through the single publication choke point, so this recipient's membership generation is
        // stamped at this very moment and a fixture sees exactly what was attempted.
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player, packet);
    }

    /** The bounded command projection of one companion: its own numbers, never its controller's. */
    private BattleStatePacket.Command commandProjection(EnemyState state) {
        Mob mob = state.mob;
        ItemStack held = mob.getMainHandItem();
        // All hand-commanded companion attacks are melee in this release; a held bow is not a ranged order.
        int kind = 0;
        Component weapon = held.isEmpty()
                ? BattleText.translatable(BattleText.KEY_NATURAL_WEAPON)
                : BattleText.itemName(held);
        int abilities = state.companionAdapter == null ? 0 : state.companionAdapter.abilities(mob);
        return new BattleStatePacket.Command(true, mob.getUUID(), mob.getId(), BattleText.name(mob),
                (int) Math.ceil(mob.getHealth()), (int) Math.ceil(mob.getMaxHealth()),
                state.ap, state.maxAp, state.moveCost, mobMeleeCost(state),
                TacticalAttributes.apRecovery(mob), state.paidActions, state.defending,
                enemyMoving && mob.getUUID().toString().equals(queue.current()),
                kind, kind == 0 ? 1 : weaponRangeForDisplay(kind), weapon, abilities,
                state.cell.x(), state.cell.y(), state.cell.z());
    }

    /**
     * Publish the authoritative entry of one player.
     *
     * <p>The order is the contract: the membership transition (carried by the full snapshot) comes
     * first, then the complete journal history and the complete warning snapshot for that member.
     * Nothing here is allowed to fail the join: a failed publication is corrected by a newer exit
     * generation instead of silently rolling back a membership the server already committed.</p>
     */
    private void publishActivation(ServerPlayer player, Component journalLine) {
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
    private void publishBootstrap(ServerPlayer player, Component journalLine) {
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
        /** The blaze charge bit vanilla had at admission, kept only so a failed admission can give it back. */
        final boolean originalBlazeCharged;
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
        /**
         * Set only for a hand-commanded companion: the adapter that claimed it and the controller it answers
         * to right now. Automatic hostiles and the older automatic friendlies keep both fields null, which is
         * what preserves their untouched AI behaviour.
         */
        CompanionAdapter companionAdapter;
        UUID companionOwner;
        /** Kind-specific presentation state control changed and has to give back (the wolf's sitting pose). */
        Object companionPose;
        /** Guard raised by the shared defend order; cleared at this actor's next own turn. */
        boolean defending;
        /**
         * The opponent this own turn was planned against, plus how many times target loss has already been
         * replanned inside this same turn. Both reset when the actor's own turn begins.
         */
        UUID lockedTarget;
        int targetReplans;
        /**
         * How many own turns this actor has had, counted only at its own turn start.
         *
         * <p>The witch drink cooldown is expressed against this number instead of wall-clock ticks, so waiting
         * in real time, another unit acting or reopening a menu can never make a drink available again.</p>
         */
        int ownTurns;
        /** This witch's own shared drink cooldown; one instance per unit, never shared between witches. */
        WitchDrinkCooldown drink = new WitchDrinkCooldown(3);
        /** A drink this witch had already started when the encounter admitted her, still to be paid for. */
        com.tactical.neoforge.battle.ai.WitchBrew pendingDrink;
        /** The raw vanilla drink that was frozen at admission, kept to give back if it never gets paid for. */
        TacticalWitchAccess.WitchDrink witchDrink;
        /** True once this encounter really settled that carried-over drink, so it is never recreated. */
        boolean witchDrinkResolved;

        EnemyState(Mob mob, GridPos cell, int ap, int maxAp, int moveCost, int initiative, BattleSide side,
                   boolean originalNoAi, boolean originalPersistenceRequired, Vec3 originalPosition,
                   LivingEntity originalTarget, float originalFallDistance, boolean originalBlazeCharged) {
            this.mob = mob;
            this.originalBlazeCharged = originalBlazeCharged;
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
