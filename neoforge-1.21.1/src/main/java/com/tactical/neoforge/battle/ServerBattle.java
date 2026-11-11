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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Spider;
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
    /**
     * The native split this encounter is still owed children by.
     *
     * <p>A dead size-4 body leaves the roster on the tick it dies, but vanilla only produces its children about
     * twenty death ticks later, outside this encounter's tick. Until that has really happened the encounter
     * owes a split: no victory, no deferred turn and no new order may be decided as if the battlefield were
     * empty.</p>
     */
    private final SlimeSplitLifecycle splits = new SlimeSplitLifecycle(this);
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
    /** The coded reason this encounter ended with, when it ended through one of the enumerated paths. */
    private BattleText.End endCode;
    /**
     * A victory both barriers had to hold back.
     *
     * <p>An emptied roster is only an end once the owed split and the forced fall are both clear; until then the
     * decision is remembered instead of being re-derived from counters that a later tick may have changed.</p>
     */
    private boolean victoryDeferred;
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

    /**
     * The real feet each falling body of the slime family keeps, keyed by unit id.
     *
     * <p>The same idea as the spider slot: the cells a fall passes through are only half of the fact, and a
     * body dragged onto the centre of every cell would be put back inside the wall or the ceiling it just lost.
     * </p>
     */
    private final java.util.Map<UUID, Vec3> slimeFallFeet = new java.util.LinkedHashMap<>();

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
            // This family's body is not a cell: the candidate is proved as a whole loaded body inside the
            // battlefield before it is read or admitted, so a wide body is never taken and then found not to fit.
            var safe=deploymentPosition(mob,
                    pos->occupied(new GridPos(pos.getX(),pos.getY(),pos.getZ())),
                    pos->zone.contains(new GridPos(pos.getX(),pos.getY(),pos.getZ())),
                    feet->slimeCandidateFits(mob, feet, zone::contains));
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
        // A split this encounter is owed is settled here, on its own tick: the children the world really
        // registered are confirmed and handed over, and the wait is closed once nothing is outstanding. The
        // barrier itself is part of isBusy and of the victory test, so nothing acts on an empty-looking
        // battlefield while a dead parent is still going to split.
        boolean wasWaiting = splits.pending();
        splits.tick();
        if (finished) return;
        if (wasWaiting && !splits.pending()) settleSplitBarrier();
        // A wall removed under a held spider must not stay a phantom hold: the pose is dropped here, before the
        // fall rule asks whether the unit carries itself.
        maintainSpiderSurfaces();
        // A body of this family that is really above the floor it holds is not standing: the descent is a sweep,
        // and it is established here - on the encounter's own tick - instead of being pinned down by the pose
        // seam on the next entity tick.
        maintainSlimeHold();
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
            // A drowned in water has no floor to stand on: fluid physics would sink or push it away from the
            // cell its turn is priced on. It is pinned at its real position for exactly as long as that
            // position is still legal - a drained lake or a closed shore falls through to the ordinary
            // unsupported-unit rule below instead of being held in mid air.
            if(air.amphibious&&!(enemyMoving&&entry.getKey().toString().equals(queue.current()))
                    &&terrainFall.unit(entry.getKey())==null) {
                // The position this cell really holds: fractional in water, the exact support height on land.
                Vec3 held=AmphibiousMovement.holdFeet(level,air.cell,air.airOffset);
                if(AmphibiousMovement.isHoldable(level,air.mob,held)) {
                    air.mob.setPos(held);
                    air.mob.setDeltaMovement(Vec3.ZERO);
                    air.mob.fallDistance=0;
                }
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
                // A settled flight is the only moment a marked trident may be reclaimed: the real hit or the
                // real block impact has already happened, so removing the entity now cannot hide anything.
                if (shooter != null) {
                    DrownedTridents.settle(this, shooter);
                    releaseTridentPose(shooter);
                }
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
        // Both barriers are clear: a victory one of them was holding is settled here, once.
        settleDeferredVictory();
        if (finished) return;
        if (rangedBusy()) return;

        // A mob's own trajectory advances in both mob phases: an automatic enemy turn and a companion turn
        // that its controller started. Only the automatic phase goes on to plan anything by itself.
        if (enemyMoving && (phase == BattlePhase.ENEMY_TURN || phase == BattlePhase.COMPANION_TURN)) {
            stepEnemy();
            return;
        }
        // A split this encounter is still owed blocks new plans exactly as a fall does: an order already in
        // flight keeps its own tick, but nobody starts anything on a battlefield whose dead parent has not
        // produced its children yet.
        if (splits.pending()) return;
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
        // No marked trident and no SPEAR pose may outlive the encounter that owns them.
        DrownedTridents.clearBattle(this);
        for (EnemyState state : enemies.values()) releaseTridentPose(state.mob.getUUID());
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
        spiderFallFeet.clear();
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
            // A unit that was not persistent before admission is not left persistently saved by this encounter:
            // vanilla has no "set false", so the same bidirectional accessor every rollback uses restores the
            // exact original flag in both directions.
            ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) state.mob)
                    .tactical$persistenceRequired(state.originalPersistenceRequired);
            state.mob.setTarget(null);
            releaseWitchState(state);
            releaseEndermanState(state);
            releaseSpiderState(state);
            releaseSlimeState(state);
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
        // No owed split and no separated child may outlive the encounter: a child this encounter was still
        // holding gets its own flags back and is left to the world, and a late join can never revive this
        // session's wait.
        splits.clear();
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
        return addFriendly(mob, true);
    }

    /**
     * The one friendly admission path, also used by a native split child of a friendly parent.
     *
     * <p>The rules are unchanged: one safe deployment of the whole real body inside this battlefield, one
     * admission transaction, one queue entry, one rollback. {@code announce} only decides whether the journal
     * says a friendly unit joined, because a split's own line already names the birth.</p>
     */
    private boolean addFriendly(Mob mob, boolean announce) {
        if (mob == null || finished || ending || queue.current() == null || members.isEmpty()
                || mob.level() != level || !mob.isAlive() || mob.isRemoved()
                || enemies.containsKey(mob.getUUID()) || enemies.values().stream()
                        .filter(state -> state.side == BattleSide.PLAYER).count() >= BattleParticipation.MAX_FRIENDLIES) return false;
        var safe = deploymentPosition(mob,
                pos -> occupied(new GridPos(pos.getX(), pos.getY(), pos.getZ())),
                pos -> zone.contains(new GridPos(pos.getX(), pos.getY(), pos.getZ())),
                feet -> slimeCandidateFits(mob, feet, zone::contains));
        if (safe.isEmpty()) return false;
        Vec3 position = safe.get();
        GridPos cell = new GridPos(net.minecraft.util.Mth.floor(position.x), net.minecraft.util.Mth.floor(position.y), net.minecraft.util.Mth.floor(position.z));
        EnemyState state = admit(mob, cell, position, BattleSide.PLAYER);
        if (state == null) return false;
        try {
            queue.add(mob.getUUID().toString(), TacticalAttributes.chargeRate(mob, state.initiative));
            if (announce) {
                journal.record("battle", BattleText.translatable(BattleText.KEY_BATTLE_FRIENDLY, BattleText.name(mob)));
            }
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
        // A child a native split is still separating belongs to exactly one controlled hand-over: no other path
        // may claim it or sample the temporary flags it is being held with. The birth removes that separation
        // immediately before it calls this method, so its own admission is never refused here.
        if (SlimeSplitLifecycle.isSeparated(mob)) return null;
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
            // The one routing seam this unit's native teleports and its water clock answer to. Installed here,
            // after the transaction succeeded, and released by every exit path below.
            installEndermanHandler(state);
            // The same single commit point for a spider's surface: the node it was really admitted on, plus
            // the pose seam its own tick answers. From here until release, this encounter owns that surface.
            installSpiderState(state);
            // The same single commit point for this family: the node the body was really admitted on, plus the
            // pose seam its own tick answers. From here until release, this encounter owns that body.
            installSlimeState(state);
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
        releaseEndermanState(state);
        releaseSpiderState(state);
        releaseSlimeState(state);
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

    /** The split lifecycle this encounter owns; the native hooks find it through this. */
    SlimeSplitLifecycle splits() {
        return splits;
    }

    /** The roster entry that really holds this exact body, or null. Never a same-UUID stranger. */
    EnemyState stateOf(Mob mob) {
        if (mob == null) return null;
        EnemyState state = enemies.get(mob.getUUID());
        return state != null && state.mob == mob ? state : null;
    }

    /** True when this encounter already holds this exact body as a participant. */
    boolean ownsChild(Mob mob) {
        return stateOf(mob) != null;
    }

    /** True while a native split this encounter is owed has not been settled yet. */
    boolean splitPending() {
        return splits.pending();
    }

    /** True while this encounter is closing; no admission, no split hand-over and no wait may start. */
    boolean ending() {
        return ending;
    }

    /**
     * True when this encounter must keep {@code mob} off its members by physical contact.
     *
     * <p>Hostility is deliberately <em>not</em> part of this test. A refused attacker is usually an
     * {@code Enemy}, but a neutral or plain mob can displace a participant just as well by collision,
     * so the collision rule covers every unowned mob while the roster is full. Callers
     * ({@link BattleContactGuard#isolationFor}) already exclude participants, players and any unit
     * another encounter owns.</p>
     *
     * <p>A split child this encounter refused is the one other case: there the roster may well have room and
     * still no legal place for that body, so the exclusion itself - not the fullness - is what keeps it away
     * from the members until it can be taken.</p>
     */
    boolean protectsFromContact(Mob mob) {
        if (finished || ending || mob == null) return false;
        if (isolation.holdsSplitChild(mob)) return true;
        // Fullness is the only other switch. A stale exclusion entry must never outlive the fullness
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
        return anyPlayerMoving() || enemyMoving || rangedBusy() || witchDrinking() || terrainFall.busy()
                || splits.pending();
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
        return absorbHostile(mob, true);
    }

    /**
     * Take one native split child into this encounter as a reinforcement.
     *
     * <p>Nothing about the admission rules changes - the body is proved on a legal deployment, the battlefield
     * grows over its whole body and never over another encounter, the transaction rolls back cleanly and the
     * queue gets exactly one entry. Only two things are different from an outside attacker: the faction comes
     * from the parent (a friendly parent's child is never turned into an enemy), and the child does not have to
     * be hunting a member, because it was born into this fight rather than walking into it.</p>
     *
     * <p>{@code false} is a refusal, not a failure: the caller keeps the child in the world and hands it to the
     * exclusion, which is where the existing "no legal place" answer already lives. Nothing here inherits the
     * parent's AP, initiative, skill, equipment or controller - a newborn unit is a new unit.</p>
     */
    boolean tryAdmitSplitChild(Mob child, BattleSide side) {
        if (child == null || finished || ending || members.isEmpty()) return false;
        if (child.level() != level || !child.isAlive() || child.isRemoved()) return false;
        if (enemies.containsKey(child.getUUID()) || BattleManager.ownerOf(child.getUUID()) != null) return false;
        if (side == BattleSide.PLAYER) return addFriendly(child, false);
        if (!isHostile(child)) return false;
        return absorbHostile(child, false);
    }

    /**
     * Keep a split child this encounter cannot take out of its members' way.
     *
     * <p>The child itself is never deleted, never moved to an illegal cell, never re-timed and never left with
     * a borrowed flag: the exclusion only clears the relationship with this encounter's members and, when the
     * terrain allows it, places the body outside every battlefield. A free roster slot with no legal foot
     * surface is exactly the case the old cap-only hand-off could not express, which is why the child is
     * handed over with its own reason instead of pretending the roster is full.</p>
     */
    void handOffSplitChild(Mob child, BattleSide side) {
        if (child == null || finished || ending) return;
        isolation.holdSplitChild(this, child, side);
    }

    /**
     * Retry an excluded split child once it legally fits the battlefield it was born into.
     *
     * <p>Deliberately the same transaction without a growing battlefield: a child the exclusion had to place
     * outside is not allowed to pull the players' battlefield after it. If it cannot fit where the encounter
     * already is, it stays held and is released when the encounter ends.</p>
     */
    boolean retrySplitChild(Mob child, BattleSide side) {
        if (child == null || finished || ending || members.isEmpty()) return false;
        if (child.level() != level || !child.isAlive() || child.isRemoved()) return false;
        if (enemies.containsKey(child.getUUID()) || BattleManager.ownerOf(child.getUUID()) != null) return false;
        boolean taken = side == BattleSide.PLAYER ? addFriendly(child, false)
                : admitHostile(child, false, false);
        if (taken) splits.noteRetake(child);
        return taken;
    }

    private boolean absorbHostile(Mob mob, boolean requireHostile) {
        return admitHostile(mob, requireHostile, true);
    }

    /**
     * The one hostile admission transaction, with or without a battlefield that grows to cover the body.
     *
     * <p>An outside attacker is judged against the battlefield it would really get: the zone grows over its own
     * whole body and never over another live encounter's space. A split child's second attempt is the same
     * transaction without that growth, because a body the exclusion had to place outside the battlefield must
     * not drag the battlefield thirty blocks after it - it joins when it legally fits the one it was born into,
     * and otherwise stays held until this encounter ends.</p>
     */
    private boolean admitHostile(Mob mob, boolean requireHostile, boolean growZone) {
        if (mob == null || finished || ending || members.isEmpty() || !hasUnprotectedMember()
                || mob.level()!=level
                || !mob.isAlive() || mob.isRemoved() || hostileCount()>=participantCap
                || enemies.containsKey(mob.getUUID()) || (requireHostile && !isHostile(mob))) {
            return false;
        }
        // The candidate is judged against the battlefield it would really get: a wide body is only taken when
        // its whole self fits the grown zone, and the zone is never grown beyond the body's own feet cell just
        // to swallow whatever stands next to it.
        var safe = growZone
                ? deploymentPosition(mob,
                        pos->occupied(new GridPos(pos.getX(),pos.getY(),pos.getZ())),pos->true,
                        feet->slimeCandidateFits(mob, feet, grownZone(mob, feet)))
                : deploymentPosition(mob,
                        pos->occupied(new GridPos(pos.getX(),pos.getY(),pos.getZ())),
                        pos->zone.contains(new GridPos(pos.getX(),pos.getY(),pos.getZ())),
                        feet->slimeCandidateFits(mob, feet, zone::contains));
        if(safe.isEmpty()) return false;
        Vec3 position=safe.get();
        GridPos cell=new GridPos(net.minecraft.util.Mth.floor(position.x),net.minecraft.util.Mth.floor(position.y),net.minecraft.util.Mth.floor(position.z));
        // Growing this battlefield must cover the whole body it is about to grant - a wide body is not its feet
        // cell - and it must never swallow another live encounter's space. The whole check runs on the would-be
        // zone, before anything changes.
        BattleZone expanded = !growZone ? zone
                : SlimeGround.supports(mob) ? grownToBody(mob, position, zone.including(cell))
                : zone.including(cell);
        if (!slimeCandidateFits(mob, position, expanded::contains)) return false;
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
        return deploymentPosition(mob, occupied, allowed, null);
    }

    /**
     * The same bounded candidate search, plus the one narrow gate this family needs.
     *
     * <p>Only the exact slime family is gated here, and the gate is asked about a candidate feet position
     * before anything is read or changed: the whole real body has to be in already-loaded columns and inside
     * the battlefield that would be committed. A caller that knows a different would-be battlefield (an
     * absorbing expansion) passes its own gate; every other caller gets this encounter's live zone. Nothing
     * about another family, another encounter or another body's rule changes.</p>
     */
    private java.util.Optional<Vec3> deploymentPosition(Mob mob,java.util.function.Predicate<BlockPos> occupied,
                                                        java.util.function.Predicate<BlockPos> allowed,
                                                        java.util.function.Predicate<Vec3> ready) {
        boolean family = SlimeGround.supports(mob);
        java.util.function.Predicate<Vec3> gate = family
                ? (ready != null ? ready : feet -> slimeCandidateFits(mob, feet, zone::contains))
                : ready;
        // A drowned is the one unit that may be placed in water; every other actor keeps the ground/aerial rule.
        if (AmphibiousMovement.supports(mob)) return AmphibiousMovement.findNearestHold(level, mob, occupied, allowed);
        if (AerialMovement.supports(mob)) return AerialMovement.findNearestClearAir(level, mob, occupied, allowed);
        return BattleDeployment.findNearestStandable(level, mob, occupied, allowed, gate);
    }

    /**
     * The shared narrow gate of the slime family: does the whole real body fit there, in a world already read?
     *
     * <p>Three facts, asked together and before any block is read: every column the real body would occupy is
     * loaded (nothing is force-loaded to make a candidate work), and the whole body - not its feet cell - is
     * inside the battlefield that would be committed. Anything else keeps its own rule and answers true here.
     * </p>
     */
    private boolean slimeCandidateFits(LivingEntity mob, Vec3 feet, java.util.function.Predicate<GridPos> wouldBeZone) {
        if (mob == null) return false;
        if (!SlimeGround.supports(mob)) return true;
        if (feet == null) return false;
        AABB box = BodyGeometry.bodyAt(mob, feet);
        if (box == null || !BodyGeometry.loaded(level, box)) return false;
        return BodyGeometry.boxInsideZone(box, wouldBeZone);
    }

    /** The battlefield that would be committed if a body at those feet were taken: the zone grown to its body. */
    private java.util.function.Predicate<GridPos> grownZone(LivingEntity mob, Vec3 feet) {
        return grownToBody(mob, feet, zone)::contains;
    }

    /**
     * The same battlefield, folded over every cell the real body of this unit would occupy.
     *
     * <p>A unit is not its feet cell: a wide body reaches into the neighbouring cells, so a zone grown only to
     * where it stands would grant it cells it does not cover. Folding over the body's own cells is what makes
     * "the whole body is inside the battlefield it was granted" true by construction; the caller still checks
     * the conflicts of that larger zone with every other encounter before anything is committed.</p>
     */
    private BattleZone grownToBody(LivingEntity mob, Vec3 feet, BattleZone baseline) {
        BattleZone grown = baseline == null ? zone : baseline;
        AABB box = BodyGeometry.bodyAt(mob, feet);
        if (box == null) return grown;
        for (int x = BodyGeometry.floor(box.minX); x <= BodyGeometry.floor(box.maxX); x++) {
            for (int y = BodyGeometry.floor(box.minY); y <= BodyGeometry.floor(box.maxY); y++) {
                for (int z = BodyGeometry.floor(box.minZ); z <= BodyGeometry.floor(box.maxZ); z++) {
                    grown = grown.including(new GridPos(x, y, z));
                }
            }
        }
        return grown;
    }

    private void stepEnemy() {
        EnemyState enemy = enemies.get(UUID.fromString(queue.current()));
        if (enemy == null) {
            enemyMoving = false;
            advanceTurn();
            return;
        }
        // A hop edge in flight owns the turn its own arc, exactly like a walked edge owns its segment. It is
        // asked first because a hop is ordinary movement while a leap is one main action.
        if (enemy.hop != null) {
            stepSlimeHop(enemy);
            return;
        }
        // A leap in flight owns the turn its own arc, exactly like a walked edge owns its segment.
        if (enemy.leap != null) {
            stepSpiderLeap(enemy);
            return;
        }
        // A spider walks surfaces, not cells: its own bounded control is the only thing that moves that body.
        if (enemy.spider != null) {
            stepSpider(enemy);
            return;
        }
        if (!enemyStepping) {
            if (enemyPathIndex >= enemyPath.size()) {
                enemyMoving = false;
                enemyPath = List.of();
                enemyStepping = false;
                if(enemy.flying){enemy.mob.setPos(AerialMovement.feet(enemy.cell,enemy.airOffset));enemy.mob.setDeltaMovement(Vec3.ZERO);}
                else if(enemy.amphibious){enemy.mob.setPos(AmphibiousMovement.holdFeet(level,enemy.cell,enemy.airOffset));enemy.mob.setDeltaMovement(Vec3.ZERO);}
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
        Vec3 target=enemy.flying?AerialMovement.feet(enemyStepTarget,enemy.airOffset)
                :enemy.amphibious?AmphibiousMovement.holdFeet(level,enemyStepTarget,enemy.airOffset)
                :centerOf(enemyStepTarget);
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
        // The water it planned through may be gone: a drained cell, a closed shore or a body that stepped into
        // the route stops it here. The whole remaining route is re-checked against the live world before a single
        // tick of it is walked, so the actor never crosses a stale water route through dry air; the steps it will
        // not walk are given back and the turn ends.
        if(enemy.amphibious&&!enemyAmphibiousRouteHolds(enemy)) {
            int unused=enemyPath.size()-enemyPathIndex+1;
            enemy.ap=Math.min(enemy.maxAp,enemy.ap+unused*enemy.moveCost+enemyDiveReservedCost);
            enemyDiveReservedCost=0;
            enemyMoving=false;enemyStepping=false;enemyPath=List.of();enemyDiveAttackIndex=-1;
            journal.record("enemy",BattleText.translatable(BattleText.KEY_ENEMY_SWIM_BLOCKED,
                    BattleText.name(enemy.mob)));
            sendState();
            advanceTurn();return;
        }
        // The step itself is the real route: a step up rises and then moves across, a step down moves across and
        // then drops, and the position is the point of that path at this progress - exactly the legs the rule
        // above proved clear.
        if(enemy.amphibious) next=amphibiousStepPoint(enemyStepFrom,target,progress);
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

    /**
     * One surface step of a spider: the same animation budget, the same re-check before every edge and the same
     * bounded hand-back as an ordinary move, only along real surface nodes instead of plain cells.
     *
     * <p>Each edge is re-proved against the live world before a single tick of it is walked - a wall broken in
     * the meantime stops the body where it really is, gives back the edges it will not walk and ends the turn
     * instead of walking through the hole. The route's end and the follow-up melee are the same single
     * {@code afterMovement} call every adapter gets, so a spider can never plan a second route in one turn.</p>
     */
    private void stepSpider(EnemyState enemy) {
        SpiderTraversal walk = enemy.spider;
        if (!walk.walking()) {
            enemyMoving = false;
            enemyStepping = false;
            if (enemy.companionAdapter != null) {
                enemyDiveAttackIndex = -1;
                enemyDiveAttacked = false;
                enemyDiveReservedCost = 0;
                sendState();
                return;
            }
            if (!targetLost(enemy)) resolveAiAction(enemy, ai.afterMovement(aiContext(enemy)));
            else if (!replanAfterLostTarget(enemy)) advanceTurn();
            return;
        }
        if (!enemyStepping) {
            SpiderSurface.Node from = walk.node();
            SpiderSurface.Node to = walk.stepTarget();
            boolean hold = from != null && spiderHoldStillHolds(enemy, from);
            boolean edge = hold && to != null && spiderEdgeStillHolds(enemy, from, to);
            if (!hold || !edge) {
                if (DeveloperTrace.enabled()) {
                    Tactical.LOGGER.info("[Tactical] dev-trace spider edge refused session={} tick={} uuid={} "
                                    + "hold={} edge={} from={} to={}",
                            session.sessionId(), ticks, enemy.mob.getUUID(), hold, edge, from, to);
                }
                stopSpiderSurface(enemy);
                return;
            }
            enemyStepFrom = enemy.mob.position();
            faceDirection(enemy.mob, to.cell());
            enemyStepProgress = 0;
            enemyStepping = true;
            walk.motion(enemyStepFrom);
        }
        SpiderSurface.Node target = walk.stepTarget();
        if (target == null) {
            stopSpiderSurface(enemy);
            return;
        }
        // The leg the body is really on is re-proved on every animation tick: a wall taken out mid-edge, a
        // block placed in front of it, a body stepping into the sweep or the battlefield shrinking stop the
        // body where it really is instead of letting it finish a segment that no longer exists.
        int frames = SpiderSurface.stepFrames(stepTicks, enemyStepFrom, target.feet());
        double progress = Math.min(1.0D, (enemyStepProgress + 1) / (double) frames);
        Vec3 point = SpiderSurface.stepPoint(enemyStepFrom, target.feet(), progress);
        if (!spiderLegStillHolds(enemy, enemy.mob.position(), point, target)) {
            stopSpiderSurface(enemy);
            return;
        }
        enemyStepProgress++;
        enemy.mob.setPos(point);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        walk.motion(point);
        if (progress >= 1.0D) {
            walk.landed();
            SpiderSurface.Node arrived = walk.node();
            enemy.cell = arrived.cell();
            enemy.mob.setPos(arrived.feet());
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemyStepping = false;
        }
    }

    /**
     * One frame of a short leap: the same bounded, re-proved animation every other movement here uses, along the
     * one arc that was proposed for this own turn.
     *
     * <p>Before the body is displaced at all, the leg it is about to fly is re-proved as a full-body sweep - a
     * block placed in the arc, a pool, another body, an unloaded chunk or a shrunken battlefield stops it - and
     * so is the landing. If nothing has moved yet the whole leap is refused for free and the body keeps the node
     * it stands on. Once the body really left the floor the leap is not refunded: it is stopped exactly where it
     * really is, the hold is dropped and the existing fall rule owns the rest of the descent. The body is never
     * pulled back to its take-off point and the encounter is never left busy.</p>
     */
    private void stepSpiderLeap(EnemyState enemy) {
        LeapRun run = enemy.leap;
        if (run == null) {
            enemyMoving = false;
            advanceTurn();
            return;
        }
        if (finished || ending) {
            // The encounter is over: no turn is advanced and no fall is started from here.
            enemy.leap = null;
            enemyMoving = false;
            enemyStepping = false;
            return;
        }
        if (enemy.spider == null || run.epoch != turnEpoch() || !isActorTurn(enemy.mob)) {
            abandonLeap(enemy, !run.paid);
            return;
        }
        Vec3 real = enemy.mob.position();
        Vec3 point = run.point(run.frames + 1);
        boolean untouched = run.frames == 0 && !run.paid;
        if (untouched && !spiderLeapLaunchHolds(enemy, run)) {
            // Nothing has moved and nothing is owed: the launch conditions themselves are gone.
            abandonLeap(enemy, true);
            return;
        }
        if (!SpiderLeap.sliceClear(level, enemy.mob, real, point, zone::contains)
                || !SpiderLeap.footPathClear(real, point, cell -> occupiedByOthers(enemy, cell))
                || !spiderLeapLandingHolds(enemy, run)) {
            abandonLeap(enemy, untouched);
            return;
        }
        enemy.mob.setPos(point);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        enemy.spider.motion(point);
        run.frames++;
        if (!run.paid) {
            // The first real displacement is the moment the leap is bought: the frozen quote, one paid action
            // and the single leap this own turn may take, all at this one point. The untouched gate above
            // already proved this pool really covers that exact frozen price, so it is subtracted exactly and
            // never clamped to hide a shortfall.
            run.paid = true;
            enemy.ap -= run.cost;
            enemy.paidActions++;
            enemy.spiderLeapsUsed++;
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace spider leap paid session={} tick={} uuid={} from={} "
                                + "to={} cost={} ap={} frames={}",
                        session.sessionId(), ticks, enemy.mob.getUUID(), run.start, point, run.cost, enemy.ap,
                        run.frames);
            }
            sendState();
        }
        if (run.frames >= run.ticks()) landLeap(enemy, run);
    }

    /**
     * Stop a leap that can no longer be flown and give the body back cleanly.
     *
     * <p>{@code free} is the untouched case: the body never left its node, so nothing was charged, no success
     * was counted and the hold it stands on is kept. Otherwise the leap really happened, its price stays with
     * the actor, and the body stops at the position it really reached - the hold is dropped so the existing
     * fall rule takes over, exactly as any other unsupported unit.</p>
     */
    private void abandonLeap(EnemyState enemy, boolean free) {
        LeapRun run = enemy.leap;
        enemy.leap = null;
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        enemyPathIndex = 0;
        enemyDiveAttackIndex = -1;
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        SpiderTraversal walk = enemy.spider;
        if (walk != null) walk.motion(null);
        if (free && run != null && !run.paid) {
            // Nothing moved, so nothing is owed: the body keeps the node it stands on, but only while the live
            // world still carries that node - otherwise the fall rule must own it, never a phantom hold.
            if (walk != null) walk.heldValid(SpiderSurface.isLegal(level, enemy.mob, walk.node()));
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace spider leap refused free session={} tick={} uuid={} "
                                + "at={} proposal={}", session.sessionId(), ticks, enemy.mob.getUUID(),
                        enemy.mob.position(), run.proposal);
            }
            endSpiderMotionTurn(enemy);
            return;
        }
        stopLeapAtRealPosition(enemy);
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_ROUTE_BLOCKED,
                BattleText.name(enemy.mob)));
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace spider leap stopped session={} tick={} uuid={} real={} "
                            + "paid={} ap={} cell={}", session.sessionId(), ticks, enemy.mob.getUUID(),
                    enemy.mob.position(), run != null && run.paid, enemy.ap, enemy.cell);
        }
        endSpiderMotionTurn(enemy);
    }

    /**
     * Stop an interrupted leap exactly where the body really is and give the pose back.
     *
     * <p>Shared by the executor's own stop and by a turn that moved for any other reason while a body was in the
     * air: the hold is re-read from the real position, a hold the world no longer carries is dropped so the
     * existing fall rule owns the descent, and the body is never moved back to its take-off point.</p>
     */
    private void stopLeapAtRealPosition(EnemyState enemy) {
        SpiderTraversal walk = enemy.spider;
        if (walk != null) walk.motion(null);
        Vec3 real = enemy.mob.position();
        GridPos realCell = new GridPos(Mth.floor(real.x), Mth.floor(real.y + 1.0E-7D), Mth.floor(real.z));
        SpiderSurface.Node node = walk == null ? null : SpiderSurface.nodeAt(level, enemy.mob, realCell, real);
        boolean holds = node != null && SpiderSurface.isLegal(level, enemy.mob, node);
        if (walk != null && node != null) walk.settle(node);
        if (walk != null) walk.heldValid(holds);
        enemy.cell = node == null ? realCell : node.cell();
        if (!holds && enemy.mob instanceof Spider spider) spider.setClimbing(false);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
    }

    /**
     * Set the body down on the landing it really reached and hand the turn over.
     *
     * <p>The landing is re-proved from the real body, then the context is asked exactly once for a follow-up,
     * and only a melee is accepted: no second walk, no second leap and no fresh plan inside this own turn. A
     * landing that finds its opponent gone simply ends the turn - the leap is never refunded and nothing is
     * invented to hit.</p>
     */
    private void landLeap(EnemyState enemy, LeapRun run) {
        SpiderSurface.Node landing = run.proposal.landing();
        enemy.leap = null;
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        enemyPathIndex = 0;
        enemyDiveAttackIndex = -1;
        enemy.mob.setPos(landing.feet());
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        SpiderTraversal walk = enemy.spider;
        boolean holds = SpiderSurface.isLegal(level, enemy.mob, landing);
        if (walk != null) {
            walk.motion(null);
            walk.settle(landing);
            walk.heldValid(holds);
        }
        enemy.cell = landing.cell();
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace spider leap landed session={} tick={} uuid={} at={} "
                            + "holds={} ap={} paid={}", session.sessionId(), ticks, enemy.mob.getUUID(),
                    landing.feet(), holds, enemy.ap, enemy.paidActions);
        }
        if (targetLost(enemy)) {
            sendState();
            advanceTurn();
            return;
        }
        AiAction followUp = ai.afterMovement(aiContext(enemy));
        if (followUp.kind() == AiAction.Kind.MELEE) {
            resolveAiAction(enemy, followUp);
            return;
        }
        sendState();
        advanceTurn();
    }

    /** One edge, re-proved: both nodes legal now, the leg swept, nobody standing on it, inside the zone. */
    private boolean spiderEdgeStillHolds(EnemyState enemy, SpiderSurface.Node from, SpiderSurface.Node to) {
        if (to == null) return false;
        if (occupiedByOthers(enemy, to.cell()) || !zone.contains(to.cell())) return false;
        if (!SpiderSurface.bodyInsideZone(enemy.mob, to, zone::contains)) return false;
        return SpiderSurface.canStep(level, enemy.mob, from, to, zone::contains);
    }

    /** The node the body is standing on still carries it: the wall is really there, or the floor is. */
    private boolean spiderHoldStillHolds(EnemyState enemy, SpiderSurface.Node node) {
        if (node == null) return false;
        if (!zone.contains(node.cell()) || occupiedByOthers(enemy, node.cell())) return false;
        if (!SpiderSurface.bodyInsideZone(enemy.mob, node, zone::contains)) return false;
        return SpiderSurface.isLegal(level, enemy.mob, node);
    }

    /** The part of the edge the body is really about to walk, re-proved before it is displaced any further. */
    private boolean spiderLegStillHolds(EnemyState enemy, Vec3 from, Vec3 to, SpiderSurface.Node target) {
        if (!SpiderSurface.sweptInsideZone(enemy.mob, from, to, zone::contains)) return false;
        if (!spiderLegClear(enemy, from, to)) return false;
        if (occupiedByOthers(enemy, target.cell())) return false;
        return SpiderSurface.isLegal(level, enemy.mob, target);
    }

    /** No solid block and no liquid at all in the swept body of one leg, exactly as a planned edge required. */
    private boolean spiderLegClear(EnemyState enemy, Vec3 from, Vec3 to) {
        AABB swept = SpiderSurface.swept(enemy.mob, from, to);
        return level.noCollision(enemy.mob, swept)
                && !AmphibiousMovement.overlapsBody(level, enemy.mob, swept)
                && !AmphibiousMovement.hazardous(level, swept)
                && !level.containsAnyLiquid(swept);
    }

    /**
     * Stop a spider where it really is and give back exactly the edges it will not walk.
     *
     * <p>No success is counted and nothing is charged again. The body is never pulled back to a node it already
     * left: the encounter re-reads the surface from the real position, keeps the price of every edge that
     * really moved it, and gives back only the edges in front of it. When no legal hold exists where the body
     * really is, the hold is dropped so the existing fall rule - which no longer sees a climbing body - owns it
     * from there.</p>
     */
    private void stopSpiderSurface(EnemyState enemy) {
        SpiderTraversal walk = enemy.spider;
        if (walk == null) {
            advanceTurn();
            return;
        }
        int unused = walk.refundable().size();
        int spent = walk.spentEdges();
        walk.abandonRoute();
        enemy.ap = Math.min(enemy.maxAp, enemy.ap + unused * enemy.moveCost + enemyDiveReservedCost);
        enemyDiveReservedCost = 0;
        enemyPath = List.of();
        enemyPathIndex = 0;
        enemyMoving = false;
        enemyStepping = false;
        enemyDiveAttackIndex = -1;
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        Vec3 real = enemy.mob.position();
        GridPos realCell = new GridPos(net.minecraft.util.Mth.floor(real.x), net.minecraft.util.Mth.floor(real.y),
                net.minecraft.util.Mth.floor(real.z));
        SpiderSurface.Node node = SpiderSurface.nodeAt(level, enemy.mob, realCell, real);
        boolean holds = node != null && SpiderSurface.isLegal(level, enemy.mob, node);
        if (node != null) walk.settle(node);
        walk.heldValid(holds);
        enemy.cell = node == null ? realCell : node.cell();
        if (!holds && enemy.mob instanceof Spider spider) spider.setClimbing(false);
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_ROUTE_BLOCKED,
                BattleText.name(enemy.mob)));
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace spider stop session={} tick={} uuid={} real={} holds={} "
                            + "edgesSpent={} edgesRefunded={} ap={}",
                    session.sessionId(), ticks, enemy.mob.getUUID(), real, holds, spent, unused, enemy.ap);
        }
        endSpiderMotionTurn(enemy);
    }

    /** Publish the fall barrier before handing off an interrupted spider's own turn. */
    private void endSpiderMotionTurn(EnemyState enemy) {
        // Starting the fall next tick leaves a command/recovery window while this unsupported body is in air.
        // Establish it now; advanceTurn then defers through the existing pendingTurnAdvance barrier.
        startFallIfUnsupported(enemy.mob.getUUID(), false, enemy.cell, enemy.mob);
        sendState();
        advanceTurn();
    }

    /** The real point of one amphibious step at this progress, on the leg that step really takes. */
    private static Vec3 amphibiousStepPoint(Vec3 from, Vec3 to, double progress) {
        Vec3 middle = AmphibiousMovement.via(from, to);
        if (middle == null) return from.lerp(to, progress);
        double half = 0.5D;
        return progress <= half
                ? from.lerp(middle, progress / half)
                : middle.lerp(to, (progress - half) / half);
    }

    /**
     * Whether every remaining step of this route is still legal against the live world.
     *
     * <p>Each edge is re-proved from the position the previous edge ends at, including the destination cell
     * itself: a pool that was drained, a shore that was closed or a body that stepped into the route refuses the
     * edge instead of letting the actor finish a route that no longer exists.</p>
     */
    private boolean enemyAmphibiousRouteHolds(EnemyState enemy) {
        if (enemyPath.isEmpty()) return true;
        Vec3 from = enemyStepping ? enemyStepFrom : AmphibiousMovement.holdFeet(level, enemy.cell, enemy.airOffset);
        int start = enemyStepping ? enemyPathIndex - 1 : enemyPathIndex;
        for (int index = Math.max(0, start); index < enemyPath.size(); index++) {
            GridPos step = enemyPath.get(index);
            if (!zone.contains(step) || occupiedByOtherOnly(step)) return false;
            Vec3 to = AmphibiousMovement.holdFeet(level, step, enemy.airOffset);
            if (!AmphibiousMovement.canStep(level, enemy.mob, from, to)) return false;
            from = to;
        }
        return true;
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
                || kind == AiAction.Kind.THROW_POTION || kind == AiAction.Kind.FIREBALL_VOLLEY
                || kind == AiAction.Kind.THROW_TRIDENT;
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
        // The charge decision asked the context; the real hit asks the body. A drowned whose held position cannot
        // touch the target must not damage it, and must therefore not pay either.
        if ((enemy.amphibious || isEndermanActor(enemy.mob))
                && !amphibiousMeleeReach(enemy, enemy.cell, target)) return false;
        // A spider is short: its own box decides, never the grid's one-block tolerance.
        if (enemy.spider != null && !spiderMeleeReach(enemy, enemy.spider.node(), target)) return false;
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(enemy.mob, targetCell);
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_MELEE,
                BattleText.name(enemy.mob), BattleText.name(target)));
        enemy.mob.swing(InteractionHand.MAIN_HAND);
        enemy.mob.setTarget(target);
        return enemy.mob.doHurtTarget(target);
    }

    /**
     * The shared real-body melee gate for the drowned and enderman, keeping tactical grid adjacency.
     *
     * <p>Vanilla decides a melee hit by intersecting {@code Mob#getAttackBoundingBox} - the body box widened by the
     * attack reach - with the target's own hitbox. Grid adjacency says nothing about that, and for a swimmer the
     * vertical part matters: two cells can be adjacent while the bodies are a whole block apart. The box is
     * therefore moved to the position this cell really holds before the intersection is asked for.</p>
     */
    private boolean amphibiousMeleeReach(EnemyState state, GridPos from, LivingEntity target) {
        if (target == null || from == null) return false;
        Vec3 feet = feetAtCell(state, from);
        AABB attackBox = state.mob.getBoundingBox().move(feet.subtract(state.mob.position()))
                .inflate(MELEE_ATTACK_REACH, 0.0D, MELEE_ATTACK_REACH);
        if (!attackBox.intersects(target.getHitbox())) return false;
        return inMeleeRange(from, participantCell(target));
    }

    /** Vanilla's own melee reach widening ({@code Mob.DEFAULT_ATTACK_REACH}); never a per-mob guess. */
    private static final double MELEE_ATTACK_REACH = Math.sqrt(2.04F) - 0.6F;

    // ------------------------------------------------------------------
    // spiders: the surface policy, its price and its real attack box
    // ------------------------------------------------------------------

    /**
     * The feet position one surface node really holds.
     *
     * <p>For the node the body occupies right now the real position is the answer - a body mid-animation or a
     * body displaced by a knockback must be judged where it really is, not where a plan wished it. Every other
     * node answers with the geometry it was resolved from.</p>
     */
    private Vec3 surfaceFeet(EnemyState state, SpiderSurface.Node node) {
        if (node == null) return state.mob.position();
        if (state.spider != null && node.equals(state.spider.node())) return state.mob.position();
        return node.feet();
    }

    /**
     * The shared melee gate for the two vanilla spider types: the real attack box, the encounter's own
     * four-way adjacency, and the real eye position.
     *
     * <p>The box is vanilla's - the body widened horizontally only - so an integer-foot short body does not
     * reach a whole block up merely because the cell is adjacent, while a fractional height is decided by the
     * real intersection instead of a rule about integers. The line of sight starts at the actor's own eye height
     * rather than a fixed one, so a 0.45 tall cave spider is not credited with a 1.5 tall view.</p>
     */
    private boolean spiderMeleeReach(EnemyState state, SpiderSurface.Node node, LivingEntity target) {
        if (target == null || node == null) return false;
        GridPos targetCell = participantCell(target);
        if (targetCell == null || !inMeleeRange(node.cell(), targetCell)) return false;
        Vec3 feet = surfaceFeet(state, node);
        if (!SpiderSurface.attackBoxAt(state.mob, feet).intersects(target.getHitbox())) return false;
        return realEyeLineOfSight(feet, state.mob, target);
    }

    /** A line of sight asked from the real eye, not from a fixed height that flatters a short body. */
    private boolean realEyeLineOfSight(Vec3 feet, LivingEntity viewer, LivingEntity target) {
        Vec3 start = new Vec3(feet.x, feet.y + viewer.getEyeHeight(), feet.z);
        Vec3 end = target.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        if (hit.getType() == HitResult.Type.MISS) return true;
        return hit.getLocation().distanceToSqr(start) >= end.distanceToSqr(start) - 0.05D;
    }

    /** How many surface edges this actor can still pay for while keeping one melee affordable. */
    private int spiderEdgeBudget(EnemyState state) {
        if (state == null || state.spider == null) return 0;
        int move = Math.max(1, state.moveCost);
        int reserve = mobMeleeCost(state);
        return Math.max(0, Math.min(SpiderSurface.MAX_SEARCH_NODES, (state.ap - reserve) / move));
    }

    /**
     * Every gate a surface route has to pass, asked once against the live world.
     *
     * <p>Legality of every node, the swept legs between them, the roster and the whole body inside the zone. A
     * refused route charges nothing and is never walked halfway.</p>
     */
    private boolean spiderRouteHolds(EnemyState state, SpiderSurface.Node start, List<SpiderSurface.Node> route) {
        if (state == null || state.spider == null || start == null || route == null || route.isEmpty()) return false;
        if (route.size() > SpiderSurface.MAX_SEARCH_NODES) return false;
        if (!SpiderSurface.routeHolds(level, state.mob, route, start, zone::contains)) return false;
        SpiderSurface.Node previous = start;
        for (SpiderSurface.Node node : route) {
            if (occupiedByOthers(state, node.cell()) || !zone.contains(node.cell())) return false;
            if (!SpiderSurface.bodyInsideZone(state.mob, node, zone::contains)) return false;
            if (!SpiderSurface.canStep(level, state.mob, previous, node, zone::contains)) return false;
            previous = node;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // spiders: the short leap - one bounded proposal, one fixed arc, one price
    // ------------------------------------------------------------------

    /** Quoted price of one short leap this own turn; zero for an actor that cannot leap at all. */
    private int spiderLeapCost(EnemyState state) {
        if (state == null || state.spider == null) return 0;
        return quote(intConfig(TacticalConfig.INSTANCE.spiderLeapApCost, BattleRules.SPIDER_LEAP_COST),
                state.paidActions);
    }

    /**
     * The one bounded leap proposal of this own turn, resolved once and then only re-checked.
     *
     * <p>The cache is keyed on the authoritative turn epoch and this actor's own turn count, so the answer is
     * stable for as long as planning, validation and the commit of that same turn can ask it, and a new own
     * turn - or a new turn generation - resolves a fresh one. The body itself is only asked about the cached
     * landing again, which is what keeps "is a leap available" from turning into a world scan on every getter.</p>
     */
    private SpiderLeap.Proposal spiderLeapProposal(EnemyState state) {
        if (state == null || state.spider == null || finished) return null;
        long epoch = turnEpoch();
        if (state.leapProposalDone && state.leapProposalEpoch == epoch
                && state.leapProposalTurn == state.ownTurns) return state.leapProposal;
        state.leapProposalDone = true;
        state.leapProposalEpoch = epoch;
        state.leapProposalTurn = state.ownTurns;
        state.leapProposal = resolveSpiderLeap(state);
        return state.leapProposal;
    }

    /**
     * Resolve that one proposal against the live world.
     *
     * <p>A leap is only worth having when it really forms an approach: the locked opponent is alive and at a
     * real distance of two to four blocks, the body is not already able to bite it, this own turn has not
     * leaped yet, and one of at most sixteen real landing positions is a legal floor from which the shared
     * native attack gate reaches that opponent with a clear arc on top.</p>
     */
    private SpiderLeap.Proposal resolveSpiderLeap(EnemyState state) {
        SpiderTraversal walk = state.spider;
        if (walk == null || walk.walking() || state.leap != null || state.spiderLeapsUsed > 0) return null;
        if (!SpiderSurface.controllable(state.mob)) return null;
        LivingEntity target = actionOpponent(state);
        if (target == null || !opposed(state.mob, target)) return null;
        SpiderSurface.Node here = walk.node();
        if (here == null || here.onWall()) return null;
        if (spiderMeleeReach(state, here, target)) return null;
        if (spiderLeapCost(state) <= 0) return null;
        java.util.Optional<SpiderLeap.Proposal> found = SpiderLeap.propose(level, state.mob, here, target,
                cell -> occupiedByOthers(state, cell), zone::contains,
                node -> spiderMeleeReach(state, node, target));
        if (DeveloperTrace.enabled() && found.isEmpty()) {
            Tactical.LOGGER.info("[Tactical] dev-trace spider leap declined session={} tick={} uuid={} here={} "
                            + "target={} feet={} targetFeet={} range={}", session.sessionId(), ticks,
                    state.mob.getUUID(), here, participantCell(target), state.mob.position(), target.position(),
                    SpiderLeap.rangeTo(state.mob, target));
        }
        return found.orElse(null);
    }

    /**
     * Whether this actor may spend this own turn on the leap right now, from the same state the commit uses.
     *
     * <p>The price, the unused quota and the cached proposal are the whole answer: nothing here moves, charges
     * or searches for a second landing.</p>
     */
    private boolean spiderCanLeap(EnemyState state) {
        if (state == null || state.spider == null || finished || ending) return false;
        if (state.leap != null || state.spiderLeapsUsed > 0 || state.spider.walking()) return false;
        if (!isActorTurn(state.mob) || isBusy()) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace spider leap not now session={} tick={} uuid={} "
                                + "actorTurn={} busy={} fall={}", session.sessionId(), ticks, state.mob.getUUID(),
                        isActorTurn(state.mob), isBusy(), terrainFall.busy());
            }
            return false;
        }
        int cost = spiderLeapCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        return spiderLeapProposal(state) != null;
    }

    /**
     * Whether the landing this leap is already flying towards is still a real place to come down.
     *
     * <p>Only terrain, the roster and the battlefield are re-proved here. The opponent is deliberately not
     * part of it: once the body has left the floor a vanished target changes what happens after the landing,
     * never whether the leap itself may finish - a leap is not refunded and never becomes a fall because the
     * victim stepped away.</p>
     */
    private boolean spiderLeapLandingHolds(EnemyState state, LeapRun run) {
        if (state == null || run == null) return false;
        SpiderSurface.Node landing = run.proposal.landing();
        if (!zone.contains(landing.cell()) || occupiedByOthers(state, landing.cell())) return false;
        if (!SpiderSurface.bodyInsideZone(state.mob, landing, zone::contains)) return false;
        return SpiderSurface.isLegal(level, state.mob, landing);
    }

    /** Every gate the commit needs, asked once against the cached proposal and the live world. */
    private boolean spiderLeapStillHolds(EnemyState state, SpiderLeap.Proposal proposal) {
        if (state == null || state.spider == null || proposal == null) return false;
        if (state.spiderLeapsUsed > 0 || state.spider.walking()) return false;
        LivingEntity target = actionOpponent(state);
        if (target == null || !opposed(state.mob, target)) return false;
        if (!proposal.from().equals(state.spider.node())) return false;
        if (!SpiderSurface.isLegal(level, state.mob, proposal.from())) return false;
        if (spiderMeleeReach(state, proposal.from(), target)) return false;
        Vec3 start = state.mob.position();
        if (!SpiderLeap.inRange(state.mob, target)) return false;
        if (!zone.contains(proposal.landing().cell())
                || occupiedByOthers(state, proposal.landing().cell())) return false;
        if (!SpiderSurface.bodyInsideZone(state.mob, proposal.landing(), zone::contains)) return false;
        if (!spiderMeleeReach(state, proposal.landing(), target)) return false;
        return SpiderLeap.arcHolds(level, state.mob, start, proposal,
                cell -> occupiedByOthers(state, cell), zone::contains);
    }

    /**
     * Every gate the launch itself has to pass again at the first frame of the arc.
     *
     * <p>The commit and the first real displacement are different ticks, and anything may happen between them:
     * the floor under the take-off can be taken away, the rest of the arc can be sealed, the opponent can leave
     * the terms the leap was priced under, or the pool can be spent elsewhere. Until the body has really moved
     * nothing has been charged, so a failure here is refused for free - and it is proved against the same cached
     * proposal: no second search, no refreshed price, no widened budget.</p>
     */
    private boolean spiderLeapLaunchHolds(EnemyState state, LeapRun run) {
        if (state == null || run == null || state.spider == null) return false;
        if (finished || ending) return false;
        if (run.epoch != turnEpoch() || !isActorTurn(state.mob)) return false;
        if (!state.mob.isAlive() || state.mob.isRemoved() || state.mob.level() != level) return false;
        // The frozen price has to still fit the pool exactly; a shortfall is never masked by a clamp.
        if (state.ap < run.cost) return false;
        if (state.spiderLeapsUsed > 0) return false;
        if (!run.proposal.from().equals(state.spider.node())) return false;
        // The floor it stands on is still a real floor, and the opponent is still one it cannot already bite.
        if (!SpiderSurface.isLegal(level, state.mob, run.proposal.from())) return false;
        LivingEntity target = lockedOpponent(state);
        if (target == null) return false;
        if (spiderMeleeReach(state, run.proposal.from(), target)) return false;
        if (!SpiderLeap.inRange(state.mob, target)) return false;
        // The whole arc is proved again, on the same proposal, with the same roster and zone gates.
        return SpiderLeap.arcHolds(level, state.mob, state.mob.position(), run.proposal,
                cell -> occupiedByOthers(state, cell), zone::contains)
                && spiderLeapLandingHolds(state, run);
    }

    /** Resolve one spider's held surface again from where its body really is, dropping any stale route. */
    private void resyncSpiderSurface(EnemyState state, GridPos cell) {
        if (state == null || state.spider == null || cell == null) return;
        if (!(state.mob instanceof Spider spider)) return;
        state.spider.abandonRoute();
        SpiderSurface.Node node = SpiderSurface.nodeAt(level, spider, cell, spider.position());
        if (node != null) state.spider.settle(node);
        // The hold is only as good as the live world says it is; the native movement gate answers from this.
        state.spider.heldValid(node != null && SpiderSurface.isLegal(level, spider, node));
        enemyStepping = false;
    }

    /**
     * Whether the surface state of every spider still holds, once per encounter tick.
     *
     * <p>A wall that is gone must not remain a phantom hold: the native climbing bit is cleared first and the
     * hold is dropped, so the existing fall rule then sees an unsupported body instead of a unit carrying
     * itself, and the encounter's own descent settles the landing. Nothing about the surface is fabricated to
     * keep a body in place - a node the world no longer carries is reported as such, and the outcome is the
     * same one every other unsupported unit gets.</p>
     */
    /**
     * Start the real descent for any controlled body of this family that is really in the air.
     *
     * <p>This is the sub-block case: a body interrupted part-way through an edge has a solid block under its
     * own cell and is still not standing. Nothing else would notice it, because the ordinary support check only
     * asks what is under the cell.</p>
     */
    private void maintainSlimeHold() {
        if (finished || ending) return;
        for (EnemyState state : List.copyOf(enemies.values())) {
            if (state.slime == null || state.hop != null) continue;
            if (!(state.mob instanceof Slime slime)) continue;
            if (!slime.isAlive() || slime.isRemoved() || slime.level() != level) continue;
            if (terrainFall.unit(slime.getUUID()) != null) continue;
            // A held node is only true while the whole real body still stands on it. A height above it, a
            // different X/Z, or one lost support column under the real footprint are all the same answer: this
            // node is no longer where the body is, and continuing to hold it would keep an illegal foot
            // surface - a body standing over a hole it never swept through.
            if (SlimeGround.holds(level, slime, state.slime)) continue;
            // The real feet are the truth: a legal real stand is adopted exactly where the body is, with no
            // cell-centre rebuild and no lateral move, and the pose seam keeps pinning that real sample.
            SlimeGround.Node real = SlimeGround.heldNode(level, slime).orElse(null);
            if (real != null) {
                state.slime = real;
                state.cell = real.cell();
                slime.setOnGround(true);
                continue;
            }
            // Anything else is not standing: the descent owns the body, and it is established here, on the
            // encounter's own tick, before the seam can pin a position nothing supports.
            startSlimeFall(state);
        }
    }

    private void maintainSpiderSurfaces() {
        for (EnemyState state : List.copyOf(enemies.values())) {
            SpiderTraversal walk = state.spider;
            if (walk == null) continue;
            if (state.mob.isRemoved() || !state.mob.isAlive()) continue;
            // An edge in flight is re-proved on every animation tick by the executor itself.
            if (walk.walking() || state.leap != null) {
                walk.heldValid(true);
                continue;
            }
            SpiderSurface.Node node = walk.node();
            if (SpiderSurface.isLegal(level, state.mob, node)) {
                walk.heldValid(true);
                continue;
            }
            // The support is gone. Drop the claim, clear the borrowed climbing bit and let the fall rule ask.
            walk.heldValid(false);
            if (!node.onWall()) continue;
            if (state.mob instanceof Spider spider) spider.setClimbing(false);
            state.mob.setDeltaMovement(Vec3.ZERO);
            state.mob.fallDistance = 0.0F;
            walk.settle(SpiderSurface.Node.groundAt(node.cell(), state.mob.position()));
        }
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

    /** The water-and-land edges of one drowned: the same list its own steps are revalidated against. */
    private List<GridPos> amphibiousNeighbours(EnemyState state, GridPos cell) {
        if (state == null || cell == null) return List.of();
        return AmphibiousMovement.neighbors(level, state.mob, cell, state.airOffset,
                this::occupiedByOtherOnly, zone::contains);
    }

    /** Occupancy as the AI sees it: every other participant, never this exact unit itself. */
    private boolean occupiedByOtherOnly(GridPos cell) {
        for (PlayerMember member : members.values()) if (member.state.cell().equals(cell)) return true;
        for (EnemyState other : enemies.values()) {
            if (other.mob.isAlive() && !other.mob.isRemoved() && other.cell.equals(cell)) return true;
        }
        return false;
    }

    /** Record one ranged refusal as structure; the refusal itself changed nothing and costs nothing. */
    private void refuseShot(EnemyState enemy, String key) {
        journal.record("enemy", BattleText.translatable(key, BattleText.name(enemy.mob)));
        sendState();
    }

    // ------------------------------------------------------------------
    // drowned: one native trident throw per own turn, real water included
    // ------------------------------------------------------------------

    /**
     * A drowned really holds a trident in its main hand.
     *
     * <p>Exact entity type and exact main-hand item, which is the same pair of checks vanilla applies before
     * its own throw goal runs: a trident in the off hand, a fishing rod or an empty hand never gains a ranged
     * action, and no other zombie-like type inherits this one.</p>
     */
    private static boolean isDrownedTridentActor(Mob mob) {
        return mob != null && mob.getType() == EntityType.DROWNED && mob.getMainHandItem().is(Items.TRIDENT);
    }

    /**
     * Quoted price of one trident throw, or 0 when this actor cannot throw at all.
     *
     * <p>20 AP is the ruleset's own value for one throw and is configurable; the +25% per prior paid action of
     * this own turn comes from the same quote every other main action uses.</p>
     */
    private int mobTridentCost(EnemyState enemy) {
        if (enemy == null || !isDrownedTridentActor(enemy.mob)) return 0;
        return quote(intConfig(TacticalConfig.INSTANCE.drownedTridentApCost,
                com.tactical.core.combat.BattleRules.DROWNED_TRIDENT_COST), enemy.paidActions);
    }

    /** The feet position this actor would really stand at, for one hypothetical grid cell. */
    private Vec3 feetAtCell(EnemyState state, GridPos cell) {
        Vec3 real = state.mob.position();
        if (cell == null || cell.equals(state.cell)) return real;
        // A drowned's feet depend on the medium of the cell it would stand in, so a hypothetical cell is
        // resolved the same way its real position is - not by shifting a fractional water altitude onto land.
        if (state.amphibious) return AmphibiousMovement.holdFeet(level, cell, state.airOffset);
        return real.add(centerOf(cell).subtract(centerOf(state.cell)));
    }

    /**
     * Authoritative legality of one trident throw from a hypothetical feet cell.
     *
     * <p>Two independent gates, both read from the entity's real numbers: the vanilla 10 block radius against
     * the target's own position, and the native nominal arc - vanilla's aim with the {@code horizontal * 0.2}
     * lift, launched at 1.6 with the air and water inertia of a thrown trident - actually reaching the target
     * without cover or another body in the way. Dispersion is vanilla's and only widens the arc, so a blocked
     * arc is a real refusal rather than a shot that would fly through a wall.</p>
     */
    private boolean canThrowTridentFrom(EnemyState state, LivingEntity opponent, GridPos from) {
        if (opponent == null || from == null || !isDrownedTridentActor(state.mob)) return false;
        if (opponent.level() != level || !opponent.isAlive() || opponent.isRemoved()) return false;
        Vec3 feet = feetAtCell(state, from);
        double dx = feet.x - opponent.getX();
        double dy = feet.y - opponent.getY();
        double dz = feet.z - opponent.getZ();
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > com.tactical.core.combat.TridentThrow.MAX_RANGE + 0.001D) {
            return false;
        }
        Vec3 origin = DrownedTridents.spawnPoint(state.mob, feet);
        return DrownedTridents.nominalReaches(level, state.mob, origin, opponent);
    }

    /**
     * One real vanilla trident throw at the opponent this own turn locked.
     *
     * <p>The entity's own {@code performRangedAttack} creates the trident, so the spawn point, the aim with its
     * 0.2 lift, the 1.6 launch speed, the difficulty inaccuracy, the water inertia, the 8.0 base damage and the
     * native sound are all vanilla's. This method adds only the encounter's boundary: the real trident must
     * exist before anything is paid, the throw never consumes or copies the held trident, and the projectile is
     * marked from creation until it really leaves the level.</p>
     *
     * <p>The price is frozen before the native call. A cancelled join, an exception or a refused/non-native
     * projectile charges nothing and leaves nothing behind; a trident that really joined is final even if it
     * misses.</p>
     */
    private boolean throwTrident(EnemyState state) {
        if (state == null || !(state.mob instanceof net.minecraft.world.entity.monster.Drowned drowned)) return false;
        if (!isActorTurn(drowned) || isBusy()) return false;
        LivingEntity target = actionOpponent(state);
        if (target == null || !opposed(drowned, target) || target.level() != level) return false;
        // Re-read the hand now: an action never pays for a throw the entity could not really perform.
        if (!isDrownedTridentActor(drowned)) {
            refuseShot(state, BattleText.KEY_ENEMY_NO_SHOT);
            return false;
        }
        int cost = mobTridentCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        if (!canThrowTridentFrom(state, target, state.cell)) {
            refuseShot(state, BattleText.KEY_ENEMY_NO_SHOT);
            return false;
        }
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(drowned, targetCell);
        drowned.setTarget(target);
        // Vanilla's own throw pose while this action runs. The renderer picks THROW_SPEAR from
        // "main hand holds a trident && isAggressive", and the entity's own ranged goal sets both, so both are set
        // here and both are cleared when the projectile settles.
        drowned.setAggressive(true);
        drowned.startUsingItem(InteractionHand.MAIN_HAND);
        RuntimeException[] nativeFailure = new RuntimeException[1];
        int marked;
        try {
            marked = DrownedTridents.throwNative(this, projectiles, drowned, target, () -> {
                try {
                    DrownedTridents.performNative(drowned, target);
                } catch (RuntimeException failure) {
                    nativeFailure[0] = failure;
                }
            });
        } catch (RuntimeException failure) {
            nativeFailure[0] = failure;
            marked = 0;
        }
        if (nativeFailure[0] != null) {
            Tactical.LOGGER.error("[Tactical] native trident throw failed for {} after {} trident(s)",
                    drowned.getUUID(), Math.max(0, marked), nativeFailure[0]);
        }
        if (marked <= 0) {
            releaseTridentPose(state.mob.getUUID());
            refuseShot(state, BattleText.KEY_ENEMY_NO_SHOT);
            return false;
        }
        state.ap -= cost;
        state.paidActions++;
        pendingEnemyShot = drowned.getUUID();
        awaitingShotResolution = true;
        shotRecoveryTicks = -1;
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_TRIDENT,
                BattleText.name(drowned), BattleText.name(target)));
        sendState();
        return true;
    }

    /**
     * The encounter's rule for a marked trident, mirroring the marked fireball's.
     *
     * <p>Only this encounter's living, opposed participants count: the thrower, a same-side unit, a bystander,
     * an uninvited pet and another encounter's unit are refused at the collision entry, and a non-living body
     * is refused because it is not a participant at all.</p>
     */
    public boolean acceptsTrident(UUID actorId, Entity candidate) {
        return acceptsMarkedProjectile(actorId, candidate);
    }

    /**
     * Fixture-only: fire this actor's own native trident throw at one body and answer whether it really joined.
     *
     * <p>It exists because the encounter's collision rule can only be exercised by a real projectile reaching a
     * body the plan would never aim at: the AI refuses such a shot on purpose, so a fixture could otherwise only
     * assert the refusal, never the collision itself. This is the same kind of fixture entry as the injected
     * admission faults - it changes nothing unless a fixture calls it, and what it creates is the vanilla
     * entity through the vanilla method.</p>
     */
    public boolean fixtureNativeTrident(LivingEntity actor, LivingEntity target) {
        if (!(actor instanceof net.minecraft.world.entity.monster.Drowned drowned) || target == null) return false;
        int joined = DrownedTridents.throwNative(this, projectiles, drowned, target,
                () -> DrownedTridents.performNative(drowned, target));
        return joined > 0;
    }

    /**
     * Read-only view of the trident legality gate for one actor, from the live state.
     *
     * <p>Fixtures use it to separate "the rule refused this position" from "the AI never tried", so a silent
     * no-throw can never be mistaken for a passing scenario. It changes nothing and prices nothing.</p>
     */
    public boolean tridentThrowLegal(LivingEntity actor) {
        if (actor == null) return false;
        EnemyState state = enemies.get(actor.getUUID());
        return state != null && state.mob == actor && canThrowTridentFrom(state, actionOpponent(state), state.cell);
    }

    /** End the vanilla SPEAR pose of one thrower once its trident is settled or its turn is over. */
    private void releaseTridentPose(UUID actorId) {
        if (actorId == null) return;
        EnemyState state = enemies.get(actorId);
        if (state == null || !(state.mob instanceof net.minecraft.world.entity.monster.Drowned drowned)) return;
        if (drowned.isUsingItem()) drowned.stopUsingItem();
        // The aggressive bit is part of the pose condition, so it is cleared with the pose - never left set, which
        // would keep the native throw stance on a unit that is not throwing.
        if (drowned.isAggressive()) drowned.setAggressive(false);
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
        return acceptsMarkedProjectile(actorId, candidate);
    }

    /**
     * The one predicate both marked-projectile rules share: a real participant of this encounter that is
     * opposed to the shooter, and nothing else. Extracted rather than copied, so a fireball and a trident can
     * never disagree about what "legal target" means.
     */
    private boolean acceptsMarkedProjectile(UUID actorId, Entity candidate) {
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

    // ------------------------------------------------------------------
    // enderman: one native relocation, one bounded reaction, one water clock
    // ------------------------------------------------------------------

    /** An enderman is the one actor this ruleset relocates through the native entry; never a superclass check. */
    private static boolean isEndermanActor(Mob mob) {
        return mob != null && mob.getType() == EntityType.ENDERMAN;
    }

    private static TacticalEndermanAccess endermanAccess(Mob mob) {
        if (mob instanceof TacticalEndermanAccess access) return access;
        throw new IllegalStateException("Enderman tactical mixin missing: register tactical.enderman.mixins.json");
    }

    /**
     * Quoted price of this actor's next own-turn relocation, or 0 for anything that is not an enderman.
     *
     * <p>The 20 AP base is this ruleset's own value and is configurable; the +25% per prior paid action of the
     * own turn is applied by the same quote every other main action uses, which is why a melee after a teleport
     * really costs its own increased price.</p>
     */
    private int endermanTeleportCost(EnemyState enemy) {
        if (enemy == null || !isEndermanActor(enemy.mob)) return 0;
        return quote(intConfig(TacticalConfig.INSTANCE.endermanTeleportApCost,
                BattleRules.ENDERMAN_TELEPORT_COST), enemy.paidActions);
    }

    /**
     * The fixed base price of one reaction.
     *
     * <p>A reaction is not a main action: it neither reads nor advances the paid-action surcharge, so it always
     * costs the same configured base AP of the unit it protects.</p>
     */
    private int endermanReactionCost(EnemyState enemy) {
        if (enemy == null || !isEndermanActor(enemy.mob)) return 0;
        return intConfig(TacticalConfig.INSTANCE.endermanTeleportApCost, BattleRules.ENDERMAN_TELEPORT_COST);
    }

    /** This encounter's own members are what a landing point may not already contain. */
    private boolean occupiedByOthers(EnemyState actor, GridPos cell) {
        for (PlayerMember member : members.values()) {
            if (member.state.cell().equals(cell)) return true;
        }
        for (EnemyState other : enemies.values()) {
            if (other == actor) continue;
            if (other.mob.isAlive() && !other.mob.isRemoved() && other.cell.equals(cell)) return true;
        }
        return false;
    }

    /** True while that state is still this encounter's live unit, and this encounter is still running. */
    private boolean liveEndermanState(EnemyState state) {
        if (state == null || finished || ending) return false;
        if (enemies.get(state.mob.getUUID()) != state) return false;
        Mob mob = state.mob;
        return mob.isAlive() && !mob.isRemoved() && mob.level() == level && isEndermanActor(mob);
    }

    /**
     * One relocation proposal for this own turn, resolved at most once and then only re-validated.
     *
     * <p>The bounded scan is the expensive part, so planning, validation and the commit all read the same
     * proposal instead of each opening a new search. A proposal that stopped being legal is refused - it is
     * never replaced by an endless hunt for another point inside one turn.</p>
     */
    private EndermanLanding.Landing endermanProposal(EnemyState state) {
        if (!liveEndermanState(state)) return null;
        if (state.endermanScanned) return state.endermanProposal;
        state.endermanScanned = true;
        state.endermanProposal = nearestLanding(state, state.endermanEscapeThisTurn,
                BattleRules.ENDERMAN_TELEPORT_CANDIDATES);
        return state.endermanProposal;
    }

    /**
     * The best landing this action may use, examining at most {@code examined} cells.
     *
     * <p>An escape only accepts a dry, sheltered point: relocating out of the rain into more rain would be a
     * paid move that fixes nothing, which is why "no dry point" stays a refusal instead of a cheaper answer.</p>
     */
    private EndermanLanding.Landing nearestLanding(EnemyState state, boolean escape, int examined) {
        if (!(state.mob instanceof EnderMan enderman)) return null;
        GridPos targetCell = participantCell(actionOpponent(state));
        List<GridPos> cells = EndermanLanding.candidates(state.cell, targetCell, escape,
                BattleRules.ENDERMAN_TELEPORT_RANGE, examined);
        for (GridPos cell : cells) {
            EndermanLanding.Landing landing = new EndermanLanding.Landing(cell, centerOf(cell));
            if (!EndermanLanding.legal(level, enderman, landing, other -> occupiedByOthers(state, other), zone)) {
                continue;
            }
            if (escape && EndermanLanding.exposed(level, enderman, landing.feet())) continue;
            return landing;
        }
        return null;
    }

    /** Whether one landing this turn would really pass every gate, from the same proposal the commit uses. */
    private boolean endermanCanTeleport(EnemyState state) {
        if (!liveEndermanState(state) || state.endermanTeleportsLeft <= 0) return false;
        return endermanProposal(state) != null;
    }

    /** What the planner is allowed to know, read from the same state that prices and commits the action. */
    private EndermanState endermanState(EnemyState state) {
        if (state == null || !isEndermanActor(state.mob) || !liveEndermanState(state)) return EndermanState.NONE;
        boolean falling = terrainFall.unit(state.mob.getUUID()) != null;
        boolean busy = isBusy() || !isActorTurn(state.mob);
        boolean landing = endermanCanTeleport(state);
        return new EndermanState(state.endermanTeleportsLeft, state.endermanExposed, state.endermanEscapeThisTurn,
                falling, busy, landing);
    }

    /**
     * One authorized native relocation request.
     *
     * <p>Valid only while the encounter is driving the native entry: it carries where the body really was when the
     * request started, what the relocation is for, and how many native attempts are still allowed. The landing
     * gate answers inside this scope and nowhere else, so a native entry can never relocate this unit on its own
     * authority - and the scope is released in a {@code finally}, including when the native call throws.</p>
     */
    private static final class EndermanRequest {
        private final GridPos originCell;
        private final Vec3 originFeet;
        private final boolean escape;
        private final int attemptsAllowed;
        private int attempts;
        private EndermanLanding.Landing allowed;

        private EndermanRequest(GridPos originCell, Vec3 originFeet, boolean escape, int attemptsAllowed) {
            this.originCell = originCell;
            this.originFeet = originFeet;
            this.escape = escape;
            this.attemptsAllowed = Math.max(0, attemptsAllowed);
        }
    }

    /**
     * One own-turn relocation through vanilla's own teleport entry.
     *
     * <p>The one commit point: quota, price, and the cached proposal are re-validated against the live world,
     * then vanilla's private {@code teleport(DDD)} runs inside a scoped request. That method had already let the
     * {@code EnderEntity} event re-point the destination before the landing gate sees it, so what this commits is
     * the point the gate really allowed - never a position the entity was moved to first and pulled back from.</p>
     */
    private boolean teleport(EnemyState state) {
        if (!liveEndermanState(state)) return false;
        if (!isActorTurn(state.mob) || isBusy()) return false;
        if (state.endermanTeleportsLeft <= 0) return false;
        if (!(state.mob instanceof EnderMan enderman)) return false;
        int cost = endermanTeleportCost(state);
        if (cost <= 0 || state.ap < cost) return false;
        EndermanLanding.Landing proposal = endermanProposal(state);
        if (proposal == null) {
            refuseTeleport(state);
            return false;
        }
        if (!EndermanLanding.legal(level, enderman, proposal, other -> occupiedByOthers(state, other), zone)) {
            // The world changed under the proposal. It is dropped for this turn instead of scanning again.
            state.endermanProposal = null;
            refuseTeleport(state);
            return false;
        }
        if (!commitEndermanTeleport(state, enderman, proposal, state.endermanEscapeThisTurn)) {
            refuseTeleport(state);
            return false;
        }
        state.ap -= cost;
        state.paidActions++;
        state.endermanTeleportsLeft = Math.max(0, state.endermanTeleportsLeft - 1);
        state.endermanProposal = null;
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_TELEPORT,
                BattleText.name(state.mob), BattleText.literal(state.cell.toString())));
        sendState();
        return true;
    }

    /**
     * Drive the native entry once inside a scoped request and commit the position it really produced.
     *
     * <p>The landing gate already validated the final point (range from the real origin, purpose, full support)
     * before vanilla moved the body; this only reads the result and makes the encounter's state agree with it. A
     * point that is somehow not the allowed one is refused without charging and without dragging the body back.
     * A refused or vetoed attempt costs nothing: the caller's own quotas stay untouched.</p>
     */
    private boolean commitEndermanTeleport(EnemyState state, EnderMan enderman, EndermanLanding.Landing proposal,
                                           boolean escape) {
        EndermanRequest request = new EndermanRequest(state.cell, enderman.position(), escape, 1);
        boolean returned;
        state.endermanRequest = request;
        try {
            returned = EndermanNative.teleportTo(enderman, proposal.feet().x, proposal.feet().y, proposal.feet().z);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] native enderman teleport failed for {}; nothing was charged",
                    enderman.getUUID(), failure);
            returned = false;
        } finally {
            state.endermanRequest = null;
        }
        EndermanLanding.Landing allowed = request.allowed;
        if (!returned || allowed == null) return false;
        Vec3 after = enderman.position();
        GridPos real = new GridPos(Mth.floor(after.x), Mth.floor(after.y + 1.0E-7D), Mth.floor(after.z));
        // The gate allowed exactly one point; anything else means the world moved under it.
        if (!real.equals(allowed.cell())) {
            Tactical.LOGGER.warn("[Tactical] enderman {} landed on {} instead of the allowed {}", enderman.getUUID(),
                    real, allowed.cell());
            return false;
        }
        state.cell = real;
        // The feet the body really holds now are the feet every later range check must use.
        state.airOffset = after.subtract(centerOf(real));
        state.endermanProposal = null;
        state.endermanScanned = false;
        enderman.setDeltaMovement(Vec3.ZERO);
        enderman.fallDistance = 0;
        return true;
    }

    private void refuseTeleport(EnemyState state) {
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_TELEPORT_REFUSED,
                BattleText.name(state.mob)));
        sendState();
    }

    /**
     * The one successful reaction of this cycle.
     *
     * <p>Only a real attack on the native projectile/potion branch, from this encounter's other side, is
     * considered at all: a bystander, a same-side unit or another encounter's member cannot make this unit pay AP
     * or move, and a plain melee or a blast is never a dodge. The search is bounded by
     * {@link BattleRules#ENDERMAN_HURT_CANDIDATES} cells and one native attempt, and the success quota is spent
     * only when the body really moved - a refused attempt stays free.</p>
     */
    private boolean endermanReaction(EnemyState state, EnderMan enderman, DamageSource source) {
        if (!liveEndermanState(state) || state.endermanReactionSuccess) return false;
        if (!endermanAttackIsOpposed(state, source)) return false;
        // That unit's own exclusive flows only: a global busy check would also refuse a real shot resolution.
        if (endermanSelfBusy(state, enderman)) return false;
        int cost = endermanReactionCost(state);
        // No price the unit can pay: no search at all, nothing charged.
        if (cost <= 0 || state.ap < cost) return false;
        EndermanLanding.Landing landing = nearestLanding(state, false, BattleRules.ENDERMAN_HURT_CANDIDATES);
        if (landing == null) return false;
        if (!commitEndermanTeleport(state, enderman, landing, false)) return false;
        state.ap -= cost;
        state.endermanReactionSuccess = true;
        // The body really left the spot it was hit on, so nothing is left to escape from on its own turn.
        state.endermanEscapeThisTurn = false;
        state.endermanPendingEscape = false;
        journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_TELEPORT_REACTION,
                BattleText.name(state.mob)));
        sendState();
        return true;
    }

    /**
     * Whether that unit itself is inside a flow a relocation may not interrupt.
     *
     * <p>Deliberately per unit and not {@link #isBusy()}: another unit's projectile still in the air is exactly
     * the window this reaction exists for, so a global "something is happening" check would refuse the very case
     * the rules name.</p>
     */
    private boolean endermanSelfBusy(EnemyState state, EnderMan enderman) {
        if (terrainFall.unit(enderman.getUUID()) != null) return true;
        if (state.endermanRequest != null) return true;
        if (enemyMoving && enderman.getUUID().toString().equals(queue.current())) return true;
        return enderman.fallDistance > 1.0F;
    }

    /** A projectile or a cast is only this unit's business when its source belongs to the other side. */
    private boolean endermanAttackIsOpposed(EnemyState state, DamageSource source) {
        if (source == null) return false;
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living && living != state.mob && opposed(state.mob, living)) return true;
        Entity direct = source.getDirectEntity();
        return direct instanceof LivingEntity living && living != state.mob && opposed(state.mob, living);
    }

    /**
     * Start one own turn of a controlled enderman.
     *
     * <p>Fresh quotas for this turn, the exposure fact read from the entity itself, and - when it is really
     * standing in water or rain - one real native hit of the same drown source vanilla would have applied per
     * tick. This is the encounter's whole water clock: vanilla's per-tick call is suspended while this handler
     * is installed, so nothing is lost on wall-clock ticks and nothing is settled twice.</p>
     */
    private void beginEndermanTurn(EnemyState state) {
        if (!isEndermanActor(state.mob) || !(state.mob instanceof EnderMan enderman)) return;
        state.endermanTeleportsLeft = 1;
        state.endermanReactionSuccess = false;
        state.endermanScanned = false;
        state.endermanProposal = null;
        state.endermanRequest = null;
        state.endermanEscapeThisTurn = state.endermanPendingEscape;
        state.endermanPendingEscape = false;
        state.endermanExposed = enderman.isInWaterRainOrBubble();
        if (!state.endermanExposed) return;
        state.endermanEscapeThisTurn = true;
        if (enderman.isAlive() && !enderman.isRemoved()) {
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_WATER_CLOCK, BattleText.name(enderman)));
            enderman.hurt(level.damageSources().drown(), 1.0F);
        }
    }

    /** Install the single routing handler this unit's native teleports answer to. */
    private void installEndermanHandler(EnemyState state) {
        if (!isEndermanActor(state.mob)) return;
        state.endermanHandler = new EndermanHandler(state);
        endermanAccess(state.mob).tactical$setLandingHandler(state.endermanHandler);
    }

    /**
     * Give the unit back to its own vanilla behaviour and drop every transient of this encounter.
     *
     * <p>Idempotent and safe on a dead, removed or already-released unit: the carried block, the equipment and
     * the final position are the entity's own and are never restored from an admission snapshot.</p>
     */
    private void releaseEndermanState(EnemyState state) {
        if (state == null || state.endermanHandler == null) return;
        state.endermanHandler = null;
        state.endermanTeleportsLeft = 0;
        state.endermanReactionSuccess = false;
        state.endermanScanned = false;
        state.endermanProposal = null;
        state.endermanRequest = null;
        state.endermanPendingEscape = false;
        state.endermanEscapeThisTurn = false;
        state.endermanHurtDepth = 0;
        state.endermanHurtBranch = false;
        if (state.mob instanceof TacticalEndermanAccess access) access.tactical$setLandingHandler(null);
    }

    /**
     * Take this exact spider's real surface into this encounter's own state.
     *
     * <p>Installed after the admission transaction committed, like the enderman handler: from here the pose the
     * vanilla tick asks for is the encounter's, so a wall position is neither overridden back to "not climbing"
     * nor allowed to start climbing on somebody else's turn. The node is resolved from where the body really
     * stands after deployment - never from a guess - and a failed admission clears it again.</p>
     */
    private void installSpiderState(EnemyState state) {
        if (state == null || state.mob == null) return;
        // Exact type, not riding anything and carrying no rider: those individuals keep vanilla behaviour, and
        // this encounter neither detaches a rider nor shares another owner's mount to make its own turn easier.
        if (!SpiderSurface.controllable(state.mob)) return;
        if (!(state.mob instanceof Spider spider)) return;
        SpiderSurface.Node start = SpiderSurface.nodeAt(level, spider, state.cell, spider.position());
        if (start == null) return;
        SpiderTraversal walk = new SpiderTraversal(start);
        walk.heldValid(SpiderSurface.isLegal(level, spider, start));
        state.spider = walk;
        ((TacticalSpiderAccess) spider).tactical$setPoseHandler(new SpiderHandler(state));
    }

    /**
     * Give a spider back to its own vanilla tick and drop every surface transient.
     *
     * <p>Idempotent, and it never moves, heals or re-positions the unit: a normal exit keeps the real final
     * position, and only the borrowed pose seam is given back. A unit this encounter never took has nothing to
     * give back, so another encounter's seam is never cleared by this path. The climbing bit is left false so a
     * released spider starts from vanilla's own answer at its next tick instead of this encounter's last one.</p>
     */
    private void releaseSpiderState(EnemyState state) {
        if (state == null) return;
        SpiderTraversal walk = state.spider;
        state.spider = null;
        // A leap and its proposal belong to the controlled body: releasing the surface drops both, so a released
        // spider is a plain vanilla one with no arc, no quota and no cached landing left behind.
        state.leap = null;
        state.spiderLeapsUsed = 0;
        state.leapProposal = null;
        state.leapProposalDone = false;
        if (walk == null) return;
        if (state.mob instanceof Spider spider) {
            ((TacticalSpiderAccess) spider).tactical$setPoseHandler(null);
            spider.setClimbing(false);
        }
    }

    /**
     * The mixin-facing view of one controlled spider.
     *
     * <p>Everything it answers is read from this encounter's live state: the exact entity, the roster entry, a
     * live encounter and a surface it can still prove. A stale handler can therefore neither pin a released
     * unit, nor hold a body the fall rule has taken, nor declare a wall pose for a body standing on a floor.</p>
     */
    private final class SpiderHandler implements SpiderPoseHandler {
        private final EnemyState state;

        private SpiderHandler(EnemyState state) {
            this.state = state;
        }

        /** The live traversal for that exact body, or null when this encounter does not own it any more. */
        private SpiderTraversal live(Spider spider) {
            if (spider == null || finished || state.mob != spider) return null;
            if (state.spider == null || enemies.get(spider.getUUID()) != state) return null;
            if (spider.isRemoved() || !spider.isAlive() || spider.level() != level) return null;
            // A forced fall owns the body: the encounter must not pin it back into the air.
            if (terrainFall.unit(spider.getUUID()) != null) return null;
            return state.spider;
        }

        @Override
        public boolean tactical$controls(Spider spider) {
            SpiderTraversal walk = live(spider);
            return walk != null && walk.controls();
        }

        @Override
        public Vec3 tactical$pinFeet(Spider spider) {
            SpiderTraversal walk = live(spider);
            return walk == null ? null : walk.pinFeet();
        }

        @Override
        public boolean tactical$wallPose(Spider spider) {
            SpiderTraversal walk = live(spider);
            return walk != null && walk.onWall() && walk.heldValid();
        }
    }

    // ------------------------------------------------------------------
    // slimes and magma cubes: real body, one hop per edge, native contact
    // ------------------------------------------------------------------

    /**
     * Install this family's movement seam on admission, from where the body really stands.
     *
     * <p>Exact types, not riding anything and carrying no rider: those individuals keep vanilla behaviour, and
     * this encounter neither detaches a rider nor shares another owner's mount to make its own turn easier. A
     * body whose own cell does not hold a legal node is not taken at all, so a mis-placed unit simply keeps the
     * plain rules instead of being animated onto a position it could never stand on.</p>
     */
    private void installSlimeState(EnemyState state) {
        if (state == null || state.mob == null) return;
        if (!SlimeGround.controllable(state.mob)) return;
        if (!(state.mob instanceof Slime slime)) return;
        // The position admission really gave this body, not the centre of its cell: a wide body may only fit
        // off centre, and adopting the centre would be an unswept move no frame of this encounter proved.
        SlimeGround.Node start = SlimeGround.heldNode(level, slime).orElse(null);
        if (start == null) return;
        state.slime = start;
        state.cell = start.cell();
        // A body this encounter installs on a real, legal ground node is standing on the ground at that very
        // moment: the adoption does not move it, so the native contact flag has to say so instead of waiting for
        // the next hop to correct it. Only the flag is written here - size, health, position, AP and the native
        // tick are untouched, no AI is opened and no frame is invented.
        slime.setOnGround(true);
        ((TacticalSlimeAccess) slime).tactical$setPoseHandler(new SlimeHandler(state));
    }

    /**
     * Give a slime back to its own vanilla tick and drop every transient of this family.
     *
     * <p>Idempotent, and it never moves, heals or re-positions the unit: a normal exit keeps the real final
     * position and only the borrowed pose seam is given back. A unit this encounter never took has nothing to
     * give back, so another encounter's seam is never cleared by this path.</p>
     */
    private void releaseSlimeState(EnemyState state) {
        if (state == null) return;
        SlimeGround.Node node = state.slime;
        state.slime = null;
        state.hop = null;
        state.slimeRoute = null;
        state.slimeRouteDone = false;
        if (node == null) return;
        if (state.mob instanceof Slime slime) ((TacticalSlimeAccess) slime).tactical$setPoseHandler(null);
    }

    /**
     * The mixin-facing view of one controlled slime.
     *
     * <p>Everything it answers is read from this encounter's live state: the exact entity, the roster entry, a
     * live encounter and a node it can still prove. A stale handler can therefore neither pin a released unit
     * nor hold a body the fall rule has taken.</p>
     */
    private final class SlimeHandler implements SlimePoseHandler {
        private final EnemyState state;

        private SlimeHandler(EnemyState state) {
            this.state = state;
        }

        /**
         * Whether this encounter really owns that exact body's displacement right now.
         *
         * <p>Owning the body and holding a proven ground node are two different facts. A body this encounter
         * holds stays owned while it stands on a node, while one of its edges animates, and while a forced
         * descent carries it - the node is what the controller can prove about it, not a condition for keeping
         * control. Only a real hand-back ends ownership: the unit left this encounter, died, was removed, or
         * moved to another dimension.</p>
         */
        private boolean owns(Slime slime) {
            if (slime == null || finished || state.mob != slime) return false;
            if (enemies.get(slime.getUUID()) != state) return false;
            if (slime.isRemoved() || !slime.isAlive() || slime.level() != level) return false;
            return true;
        }

        @Override
        public boolean tactical$controls(Slime slime) {
            return owns(slime);
        }

        @Override
        public Vec3 tactical$pinFeet(Slime slime) {
            if (!owns(slime)) return null;
            // An edge in flight and a forced descent both own the position themselves: the pin is the real
            // sample the controller last applied, never the node it took off from, and never a cell centre.
            // Letting native gravity integrate beside either of them would be a second motion on one tick.
            if (state.hop != null) return slime.position();
            if (terrainFall.unit(slime.getUUID()) != null) return slime.position();
            SlimeGround.Node node = state.slime;
            if (node == null) return slime.position();
            // The held node is the pin only while the body really is on it. A different X/Z, or any real height
            // above it, is not that node: pinning the stale point would be an unswept move in front of native
            // travel, so the real sample is pinned and the fall rule takes the body from there.
            if (slime.position().distanceToSqr(node.feet()) > 1.0E-8D) return slime.position();
            return node.feet();
        }
    }

    /** Quoted price of one native contact this own turn; zero for a body that cannot damage anything. */
    private int slimeContactCost(EnemyState state) {
        if (state == null || !SlimeGround.supports(state.mob) || !SlimeContact.sizeCanHurt(state.mob)) return 0;
        return quote(intConfig(TacticalConfig.INSTANCE.meleeApCost, ApCalculator.MELEE_COST), state.paidActions);
    }

    /**
     * How many hop edges this body can still pay for while keeping one native contact affordable.
     *
     * <p>A size-1 slime reserves nothing because it can never hurt anything; a magma cube of any size reserves
     * one contact, because that is exactly what vanilla lets it do.</p>
     */
    private int slimeEdgeBudget(EnemyState state) {
        if (state == null || state.slime == null) return 0;
        int move = Math.max(1, state.moveCost);
        int reserve = slimeContactCost(state);
        return Math.max(0, Math.min(SlimeGround.MAX_SEARCH_NODES, (state.ap - reserve) / move));
    }

    /**
     * Whether this body's native attack box at that node really reaches the locked opponent.
     *
     * <p>Three separate facts, asked together because a paid contact needs all of them: the exact type and size
     * may damage at all, the native box (the body widened by vanilla's own reach) intersects the target's
     * hitbox, and the line of sight from this body's real eye is clear. None of them is grid adjacency.</p>
     */
    private boolean slimeContactReaches(EnemyState state, SlimeGround.Node node) {
        if (state == null || node == null) return false;
        LivingEntity target = actionOpponent(state);
        if (target == null || !opposed(state.mob, target)) return false;
        if (target.level() != level || !target.isAlive() || target.isRemoved()) return false;
        if (!SlimeContact.sizeCanHurt(state.mob)) return false;
        if (!SlimeContact.attackBoxReaches(state.mob, node.feet(), target)) return false;
        return SlimeContact.eyeLineOfSight(level, state.mob, node.feet(), target);
    }

    /**
     * Every gate a hop route has to pass, asked once against the live world.
     *
     * <p>A real stand for every node, a real provable arc for every edge, the whole body inside the battlefield
     * and no cell another participant holds. A refused route charges nothing and is never walked halfway.</p>
     */
    private boolean slimeRouteHolds(EnemyState state, List<SlimeGround.Node> route) {
        if (state == null || state.slime == null || route == null || route.isEmpty()) return false;
        if (route.size() > SlimeGround.MAX_SEARCH_NODES) return false;
        if (!SlimeGround.legal(level, state.mob, state.slime)) return false;
        SlimeGround.Node previous = state.slime;
        for (SlimeGround.Node node : route) {
            if (occupiedByOthers(state, node.cell()) || !zone.contains(node.cell())) return false;
            if (!SlimeGround.legal(level, state.mob, node)) return false;
            if (!SlimeGround.bodyInsideZone(state.mob, node, zone::contains)) return false;
            if (!SlimeGround.adjacent(previous.cell(), node.cell())) return false;
            if (SlimeGround.leg(level, state.mob, previous, node, zone::contains).isEmpty()) return false;
            previous = node;
        }
        return true;
    }

    /**
     * The one bounded approach proposal of this own turn, resolved once and then only re-checked.
     *
     * <p>The bounded search is the expensive part, so planning, validation and the commit all read the same
     * immutable answer; a new own turn - or a new turn generation - resolves a fresh one.</p>
     */
    private List<SlimeGround.Node> slimeApproach(EnemyState state) {
        if (state == null || state.slime == null || finished || ending) return List.of();
        if (!isActorTurn(state.mob) || isBusy()) return List.of();
        long epoch = turnEpoch();
        if (state.slimeRouteDone && state.slimeRouteEpoch == epoch
                && state.slimeRouteTurn == state.ownTurns) {
            return state.slimeRoute == null ? List.of() : state.slimeRoute;
        }
        state.slimeRouteDone = true;
        state.slimeRouteEpoch = epoch;
        state.slimeRouteTurn = state.ownTurns;
        LivingEntity target = actionOpponent(state);
        GridPos targetCell = participantCell(target);
        int budget = slimeEdgeBudget(state);
        SlimeGround.Route route = targetCell == null || budget <= 0 ? null
                : SlimeGround.approach(level, state.mob, state.slime, targetCell, budget,
                        cell -> occupiedByOthers(state, cell), zone::contains,
                        node -> slimeContactReaches(state, node)).orElse(null);
        state.slimeRoute = route == null ? null : route.nodes();
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace slime approach session={} tick={} uuid={} here={} "
                            + "budget={} route={}", session.sessionId(), ticks, state.mob.getUUID(),
                    state.slime.cell(), budget, state.slimeRoute);
        }
        return state.slimeRoute == null ? List.of() : state.slimeRoute;
    }

    /**
     * One native contact, at the frozen price, with the real result of vanilla's own {@code hurt}.
     *
     * <p>Nothing is charged unless that exact call reported a hit: an absorbed hit whose health does not drop is
     * still a hit, and a cancelled one is still not. The damage number, the difficulty scaling of the damage
     * type, armour, the enchantment post-effects and the sound are all vanilla's, because the entry that runs
     * them is vanilla's. {@code NoAI} stays exactly as it is: the gate vanilla's own {@code isDealsDamage} uses
     * is never asked here, which is the whole reason a controlled body can still touch anything.</p>
     */
    private boolean slimeContact(EnemyState state) {
        int cost = slimeContactCost(state);
        LivingEntity target = actionOpponent(state);
        SlimeGround.Node here = state.slime;
        if (DeveloperTrace.enabled()) {
            // Development trace: which gate refused a native contact. Needed because a silent refusal and a
            // silent "no plan at all" look identical from the journal.
            boolean legal = here != null && SlimeGround.legal(level, state.mob, here);
            boolean standable = here != null && BattleDeployment.isStandable(level, state.mob, here.feet());
            Tactical.LOGGER.info("[Tactical] dev-trace slime contact gates session={} tick={} uuid={} cost={} ap={} "
                            + "target={} here={} legal={} standable={} loaded={} bodies={} reaches={} actorTurn={}",
                    session.sessionId(), ticks, state.mob.getUUID(), cost, state.ap,
                    target == null ? "none" : target.getUUID(), here == null ? "none" : here.cell(), legal,
                    standable,
                    here != null && BodyGeometry.loaded(level, BodyGeometry.bodyAt(state.mob, here.feet())),
                    here == null ? "-" : level.getEntities(state.mob, BodyGeometry.bodyAt(state.mob, here.feet()),
                            o -> !o.isSpectator() && o.isAlive()
                                    && (o instanceof LivingEntity || o.canBeCollidedWith())).toString(),
                    here != null && slimeContactReaches(state, here), isActorTurn(state.mob));
        }
        if (cost <= 0 || state.ap < cost || target == null || here == null) return false;
        if (!isActorTurn(state.mob)) return false;
        // The standing half only: a wide body that really stands next to the opponent it touches would fail a
        // placement rule ("nobody else in the box") that has nothing to say about a native contact.
        if (!SlimeGround.holds(level, state.mob, here)) return false;
        if (!slimeContactReaches(state, here)) return false;
        GridPos targetCell = participantCell(target);
        if (targetCell != null) faceDirection(state.mob, targetCell);
        journal.record("attack", BattleText.translatable(BattleText.KEY_ATTACK_ENEMY_MELEE,
                BattleText.name(state.mob), BattleText.name(target)));
        state.mob.setTarget(target);
        boolean hurt = SlimeContact.strike((Slime) state.mob, target);
        if (!hurt) {
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace slime contact refused session={} tick={} uuid={} "
                                + "target={} at={}", session.sessionId(), ticks, state.mob.getUUID(),
                        target.getUUID(), here.cell());
            }
            return false;
        }
        state.ap -= cost;
        state.paidActions++;
        if (DeveloperTrace.enabled()) {
            Tactical.LOGGER.info("[Tactical] dev-trace slime contact paid session={} tick={} uuid={} target={} "
                            + "cost={} ap={} paid={}", session.sessionId(), ticks, state.mob.getUUID(),
                    target.getUUID(), cost, state.ap, state.paidActions);
        }
        sendState();
        return true;
    }

    /**
     * One frame of a hop: the same bounded, re-proved animation every other movement here uses.
     *
     * <p>Before the body is displaced at all, the frame it is about to fly is re-proved as a full body sweep -
     * a block placed in the arc, a pool, another body, an unloaded column or a shrunken battlefield stops it -
     * and so is the landing. If the edge has not started, the whole remaining route is refused and its unwalked
     * edges are given back; once the body really moved, the edge stays paid and the body stops exactly where it
     * is. The body is never pulled back to a cell centre or to its take-off point, and a body left in the air is
     * handed to the existing fall rule - establishing that fall <em>before</em> the turn is handed on.</p>
     */
    private void stepSlimeHop(EnemyState enemy) {
        HopRun run = enemy.hop;
        if (run == null) {
            enemyMoving = false;
            advanceTurn();
            return;
        }
        if (finished || ending) {
            // The encounter is over: no turn is advanced and no fall is started from here.
            enemy.hop = null;
            enemyMoving = false;
            enemyStepping = false;
            return;
        }
        if (enemy.slime == null || run.epoch != turnEpoch() || !isActorTurn(enemy.mob)) {
            abandonSlimeHop(enemy);
            return;
        }
        if (run.edge == null) {
            if (run.index >= run.route.size()) {
                finishSlimeHop(enemy);
                return;
            }
            SlimeGround.Node from = enemy.slime;
            SlimeGround.Node to = run.route.get(run.index);
            java.util.Optional<SlimeMotion.Leg> leg = SlimeGround.legal(level, enemy.mob, from)
                    && SlimeGround.legal(level, enemy.mob, to)
                    && !occupiedByOthers(enemy, to.cell())
                    ? SlimeGround.leg(level, enemy.mob, from, to, zone::contains)
                    : java.util.Optional.empty();
            if (leg.isEmpty() || !SlimeGround.bodyInsideZone(enemy.mob, to, zone::contains)) {
                abandonSlimeHop(enemy);
                return;
            }
            // The body has to really be standing on the node this edge starts from. Adopting that node's feet
            // here would be an unswept jump no frame of this route ever proved; a body that is not on its node
            // belongs to the same interruption every other lost support uses.
            if (!SlimeGround.holds(level, enemy.mob, from)) {
                abandonSlimeHop(enemy);
                return;
            }
            run.edge = leg.get();
            run.to = to;
            run.frames = 0;
            run.edgeStarted = false;
            // The body really leaves the floor on this edge; saying so keeps the native landing presentation
            // (squish, particles, sound) reading the truth while the movement step stays cancelled.
            enemy.mob.setOnGround(false);
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            faceDirection(enemy.mob, to.cell());
        }
        int next = run.frames + 1;
        // Before the body is displaced at all, the frame it is about to fly and the landing it is flying to are
        // re-proved against the live world. A sweep that only gets emptier - a floor dug out of the landing cell
        // mid-edge - would otherwise be flown into an empty box and called a landing on the last frame.
        if (!SlimeMotion.nextFrameHolds(level, enemy.mob, run.edge, next, zone::contains)
                || !BodyGeometry.bodyInsideZone(enemy.mob, run.edge.point(next), zone::contains)
                || run.to == null
                || !SlimeGround.legal(level, enemy.mob, run.to)
                || occupiedByOthers(enemy, run.to.cell())
                || !SlimeGround.bodyInsideZone(enemy.mob, run.to, zone::contains)) {
            abandonSlimeHop(enemy);
            return;
        }
        Vec3 point = run.edge.point(next);
        enemy.mob.setPos(point);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        run.frames = next;
        run.edgeStarted = true;
        if (next >= run.edge.frames()) {
            // The edge landed on its own node, with the exact feet that node holds.
            enemy.slime = run.to;
            enemy.cell = run.to.cell();
            enemy.mob.setPos(run.to.feet());
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemy.mob.setOnGround(true);
            run.edge = null;
            run.to = null;
            run.frames = 0;
            run.edgeStarted = false;
            run.index++;
            if (run.index >= run.route.size()) finishSlimeHop(enemy);
        }
    }

    /** The route really arrived: the same hand-back every other movement uses. */
    private void finishSlimeHop(EnemyState enemy) {
        enemy.hop = null;
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        enemyPathIndex = 0;
        if (enemy.companionAdapter != null) {
            sendState();
            return;
        }
        if (!targetLost(enemy)) resolveAiAction(enemy, ai.afterMovement(aiContext(enemy)));
        else if (!replanAfterLostTarget(enemy)) advanceTurn();
    }

    /**
     * Stop a hop that can no longer be flown and give the body back cleanly.
     *
     * <p>Every normal interruption settles at the per-edge quote frozen when the route was committed: the edges
     * the body really completed and the one it really began moving on stay paid, and every edge it never began
     * is given back exactly once. A route refused on its first frame therefore costs nothing at all. The quote
     * is never re-read from a {@code moveCost} that changed after the commit, and the same run can never refund
     * twice because the run is dropped before anything is returned. The body keeps the position it really
     * reached: a real, legal stand at its own feet is re-adopted where it stands, and anything else starts a
     * real fall - established first, so the encounter is already busy when the turn is handed on.</p>
     */
    private void abandonSlimeHop(EnemyState enemy) {
        HopRun run = enemy.hop;
        enemy.hop = null;
        enemyMoving = false;
        enemyStepping = false;
        enemyPath = List.of();
        enemyPathIndex = 0;
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        if (run != null && !run.route.isEmpty()) {
            // The frozen route was quoted as one price per edge, so its own per-edge share is exact and does not
            // depend on anything that may have changed since.
            int perEdge = run.cost / run.route.size();
            int charged = run.index + (run.edgeStarted ? 1 : 0);
            int unused = Math.max(0, run.route.size() - charged);
            int refund = unused * perEdge;
            if (refund > 0) enemy.ap = Math.min(enemy.maxAp, enemy.ap + refund);
        }
        Vec3 real = enemy.mob.position();
        GridPos cell = new GridPos(Mth.floor(real.x), Mth.floor(real.y), Mth.floor(real.z));
        // The held point is the real sample, and the cell it really occupies is derived from it: rebuilding a
        // node on the cell centre and moving the body there would be a lateral snap nothing swept.
        SlimeGround.Node held = new SlimeGround.Node(cell, real);
        if (SlimeGround.legal(level, enemy.mob, held)) {
            // The body really stands where it is: adopt that exact point, with the feet it really has.
            enemy.slime = held;
            enemy.cell = cell;
            enemy.mob.setPos(real);
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemy.mob.fallDistance = 0.0F;
            enemy.mob.setOnGround(true);
            sendState();
            advanceTurn();
            return;
        }
        // Anything else - a fractional height, a lost support, a body inside a cell whose floor is refused -
        // is a fall, and it is established before the turn is handed on.
        startSlimeFall(enemy);
        advanceTurn();
    }

    /**
     * Start the real descent for a body this encounter had to let go of.
     *
     * <p>The existing fall owns the cells, the landing, the deadline and the damage; this only establishes it and
     * remembers the real feet, exactly like the spider slot. It is deliberately unconditional about the support
     * below: a body interrupted half a block above its own floor has a floor under it and still is not standing.
     * </p>
     */
    private void startSlimeFall(EnemyState enemy) {
        if (enemy == null || enemy.mob == null || finished || ending) return;
        if (!enemy.mob.isAlive() || enemy.mob.isRemoved() || enemy.mob.level() != level) return;
        if (terrainFall.unit(enemy.mob.getUUID()) != null) return;
        // The fall starts from the cell the body really occupies, not from the node it took off from: an
        // interrupted hop can be several tenths of a block past its own cell boundary.
        GridPos cell = new GridPos(Mth.floor(enemy.mob.getX()), Mth.floor(enemy.mob.getY()),
                Mth.floor(enemy.mob.getZ()));
        enemy.cell = cell;
        TerrainFall.Unit unit = terrainFall.begin(enemy.mob.getUUID(), false, cell);
        slimeFallFeet.put(enemy.mob.getUUID(), enemy.mob.position());
        // The descent really starts in the air, and the body says so from this moment.
        enemy.mob.setOnGround(false);
        refreshFallLanding(unit);
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL_BEGIN,
                BattleText.name(enemy.mob), BattleText.literal(cell.toString())));
        try {
            sendState();
        } catch (RuntimeException ignored) {
            // The fall is authoritative even when one broadcast fails.
        }
    }

    /**
     * One tick of this family's descent: the real fractional height first, then the ordinary cells.
     *
     * <p>The sub-block part is the whole point. A body interrupted in the air above its own floor already has a
     * solid block under its cell, so the ordinary rule would call that a landing and snap it down a fraction of
     * a block - a move nothing swept and nothing proved. Here the real Y is descended in bounded slices, sweeping
     * the whole body between the two positions it really occupies, and only when it reaches the real foot surface
     * does the ordinary column rule get to decide.</p>
     */
    private void stepFallingSlime(TerrainFall.Unit unit, EnemyState enemy, Vec3 held) {
        if (enemy == null || !enemy.mob.isAlive() || enemy.mob.isRemoved()) {
            terrainFall.end(unit.id());
            slimeFallFeet.remove(unit.id());
            return;
        }
        double x = held.x;
        double z = held.z;
        double surfaceY = unit.cell().y();
        double realY = enemy.mob.getY();
        if (realY > surfaceY + 1.0E-4D) {
            double target = Math.max(surfaceY, realY - subCellDescent());
            if (!slimeFallSliceClear(enemy.mob, x, realY, target, z)) {
                // Something is in the way of the fraction it is about to fall through: keep the real position
                // and re-plan next tick, exactly as every other falling unit does.
                return;
            }
            enemy.mob.setPos(x, target, z);
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemy.mob.fallDistance = 0.0F;
            if (target > surfaceY + 1.0E-4D) return;
        }
        if (unit.stepTo() == null) {
            refreshFallLanding(unit);
            com.tactical.neoforge.battle.terrain.FallPlanner.Step step =
                    com.tactical.neoforge.battle.terrain.FallPlanner.next(unit.cell(), this::hasSupportBelow,
                            cell -> enterableForFall(cell, unit.id()));
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.LAND) {
                settleSlimeFall(unit, enemy, x, z);
                return;
            }
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.STUCK) return;
            GridPos next = below(unit.cell());
            if (!zone.contains(next)) { abandonTerrainFall(unit); return; }
            unit.beginStep(unit.cell(), next, fallStepTicks());
        }
        double from = enemy.mob.getY();
        double target = unit.stepFrom().y() + (unit.stepTo().y() - unit.stepFrom().y())
                * fallFraction(unit, unit.progress() + 1);
        if (!slimeFallSliceClear(enemy.mob, x, from, target, z)) {
            unit.endStep();
            return;
        }
        unit.progress(unit.progress() + 1);
        enemy.mob.setPos(x, target, z);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        if (unit.fraction() >= 1.0D) {
            GridPos arrived = unit.stepTo();
            unit.stepLanded(arrived);
            enemy.cell = arrived;
        }
    }

    /** One bounded slice of the sub-block descent, at the same speed the cell ladder uses. */
    private double subCellDescent() {
        return 1.0D / Math.max(1, fallStepTicks());
    }

    /** The whole body between the two real heights it occupies, cleared as one box. */
    private boolean slimeFallSliceClear(LivingEntity body, double x, double fromY, double toY, double z) {
        AABB swept = BodyGeometry.swept(body, new Vec3(x, fromY, z), new Vec3(x, toY, z));
        if (swept == null) return false;
        if (!BodyGeometry.loaded(level, swept)) return false;
        if (!level.noCollision(body, swept)) return false;
        return !AmphibiousMovement.overlapsBody(level, body, swept);
    }

    /**
     * Land this body where it really is.
     *
     * <p>The ordinary landing snaps the body onto the centre of its cell; only the real, legal feet can settle
     * this family's fall. An invalid stand keeps the deadline running instead of being repaired by an unswept
     * snap.</p>
     */
    private void settleSlimeFall(TerrainFall.Unit unit, EnemyState enemy, double x, double z) {
        GridPos landing = unit.cell();
        Vec3 feet = new Vec3(x, landing.y(), z);
        SlimeGround.Node node = new SlimeGround.Node(landing, feet);
        if (!SlimeGround.legal(level, enemy.mob, node)) return;
        terrainFall.end(unit.id());
        slimeFallFeet.remove(unit.id());
        enemy.cell = landing;
        enemy.slime = node;
        enemy.mob.setPos(feet);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        enemy.mob.setOnGround(true);
        settleFallDamage(enemy.mob, landing, unit.cells());
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL,
                BattleText.name(enemy.mob), BattleText.literal(unit.origin().toString()),
                BattleText.literal(landing.toString()), unit.cells()));
        if (!finished) sendState();
    }

    /**
     * The mixin-facing view of one controlled enderman.
     *
     * <p>Everything it answers is read from the encounter's live state and re-validated against the live world,
     * so a stale handler can never move a released unit or charge a finished encounter.</p>
     */
    private final class EndermanHandler implements EndermanLandingHandler {
        private final EnemyState state;

        private EndermanHandler(EnemyState state) {
            this.state = state;
        }

        /**
         * The landing gate: the point vanilla is about to use, after every event listener re-pointed it.
         *
         * <p>Answers only inside a request the encounter itself opened. The point must still be within this
         * ruleset's range of where the body really stood when the request began, must not be the cell it already
         * occupies, must be a real supported stand, and - when the request exists to leave water or rain - must
         * really be dry. Anything else is refused before the body moves.</p>
         */
        @Override
        public boolean tactical$mayUseLanding(EnderMan enderman, double x, double y, double z) {
            if (!liveEndermanState(state) || state.mob != enderman) return false;
            EndermanRequest request = state.endermanRequest;
            if (request == null || request.attempts >= request.attemptsAllowed) return false;
            request.attempts++;
            EndermanLanding.Landing landing = EndermanLanding.resolve(level, enderman, x, y, z);
            if (landing == null) return false;
            // The upper bound is about the point that will really be used, not about the proposal.
            if (request.originFeet.distanceTo(landing.feet()) > BattleRules.ENDERMAN_TELEPORT_RANGE) return false;
            // A "relocation" onto the cell it already occupies is a paid no-op, and its real feet prove it.
            if (landing.cell().equals(request.originCell)
                    && request.originFeet.distanceTo(landing.feet()) < 0.5D) return false;
            // The purpose the request was opened for has to survive the event's own answer as well.
            if (request.escape && EndermanLanding.exposed(level, enderman, landing.feet())) return false;
            if (!EndermanLanding.legal(level, enderman, landing,
                    cell -> occupiedByOthers(state, cell), zone)) return false;
            request.allowed = landing;
            return true;
        }

        /**
         * One real damage call: vanilla's own path first, then at most the one reaction the native
         * projectile/potion branch is allowed.
         *
         * <p>Vanilla answers the branch with {@code true} exactly when one of its own free teleports succeeded;
         * this ruleset keeps that immunity but replaces the free dodge with one bounded, paid one, so a
         * legitimate projectile or water-bottle hit stays absorbed while the relocation itself may be refused.
         * Everything else - a plain melee, a blast, an unowned environment hit, an invulnerable source - keeps
         * vanilla's own answer and never buys a dodge.</p>
         */
        @Override
        public boolean tactical$hurt(EnderMan enderman, DamageSource source, float amount,
                                     java.util.function.BooleanSupplier vanillaHurt) {
            if (!liveEndermanState(state) || state.mob != enderman) return vanillaHurt.getAsBoolean();
            // One hurt, one reaction: a nested damage call inside this one reuses the outer decision.
            if (state.endermanHurtDepth > 0) return vanillaHurt.getAsBoolean();
            boolean branch = endermanDamageBranch(source);
            boolean invulnerable = enderman.isInvulnerableTo(source);
            state.endermanHurtDepth++;
            state.endermanHurtBranch = branch;
            try {
                // Vanilla's own damage path first: the clean-water hit, the real melee damage and the death
                // handling are vanilla's, and vanilla's own order settles the bottle before any dodge.
                boolean vanilla = vanillaHurt.getAsBoolean();
                if (!branch || invulnerable || !enderman.isAlive() || enderman.isRemoved()) return vanilla;
                boolean moved = endermanReaction(state, enderman, source);
                // A successful paid dodge replaces vanilla's successful teleport answer; a rejected dodge
                // keeps the native hurt result (including false for an immune arrow/trident hit).
                return vanilla || moved;
            } finally {
                state.endermanHurtDepth--;
                state.endermanHurtBranch = false;
            }
        }

        @Override
        public boolean tactical$ownsWaterClock(EnderMan enderman) {
            return liveEndermanState(state) && state.mob == enderman;
        }

        /**
         * A vanilla free relocation was attempted for this unit.
         *
         * <p>The dodge loop of one projectile/potion hit is not an escape request: that hit is answered by the
         * one bounded reaction, and turning every refused attempt into a paid escape next turn would let a
         * bystander's arrow cost this unit AP. Only a genuinely unowned environmental escape survives.</p>
         */
        @Override
        public void tactical$onFreeTeleportRefused(EnderMan enderman) {
            if (!liveEndermanState(state) || state.mob != enderman) return;
            if (state.endermanHurtDepth > 0 && state.endermanHurtBranch) return;
            state.endermanPendingEscape = true;
        }
    }

    /**
     * Vanilla's own projectile/potion branch of {@code EnderMan.hurt}, read from its own condition.
     *
     * <p>That branch is the only place vanilla dodges a hit for free, so it is the only place this ruleset may
     * replace the dodge with its bounded, paid one. A plain melee, a blast or an unowned environment hit never
     * teleports a vanilla enderman and therefore never buys a reaction here.</p>
     */
    private static boolean endermanDamageBranch(DamageSource source) {
        return source != null && (source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
                || source.getDirectEntity() instanceof ThrownPotion);
    }

    /** The cells this enderman's real 2.9 high body could really stand in, from the same gate as its landings. */
    private List<GridPos> endermanEdges(EnemyState state, List<GridPos> edges) {
        if (!(state.mob instanceof EnderMan enderman)) return edges;
        List<GridPos> legal = new ArrayList<>(edges.size());
        for (GridPos edge : edges) {
            EndermanLanding.Landing landing = new EndermanLanding.Landing(edge, feetAtCell(state, edge));
            if (EndermanLanding.legal(level, enderman, landing, cell -> false, zone)) legal.add(edge);
        }
        return legal;
    }

    /**
     * A short affordable walk out of water or rain, or an empty path when there is none.
     *
     * <p>The route is the same terrain the movement code validates against, and it is limited by what this own
     * turn can really pay for. Walking toward the opponent is never a substitute for it: the whole point is
     * leaving the exposed cell, so a route that ends anywhere else is not this answer.</p>
     */
    private List<GridPos> dryEscapePath(EnemyState state) {
        if (state == null || !isEndermanActor(state.mob)) return List.of();
        if (!liveEndermanState(state)) return List.of();
        if (!(state.mob instanceof EnderMan enderman) || !isActorTurn(state.mob)) return List.of();
        int moveCost = Math.max(1, state.moveCost);
        int budget = Math.min(state.ap / moveCost, 8);
        if (budget <= 0) return List.of();
        if (!EndermanLanding.exposed(level, enderman, enderman.position())) return List.of();
        java.util.Map<GridPos, GridPos> parent = new LinkedHashMap<>();
        java.util.Map<GridPos, Integer> depth = new LinkedHashMap<>();
        java.util.ArrayDeque<GridPos> frontier = new java.util.ArrayDeque<>();
        parent.put(state.cell, null);
        depth.put(state.cell, 0);
        frontier.add(state.cell);
        int examined = 0;
        GridPos dry = null;
        while (!frontier.isEmpty() && dry == null) {
            GridPos from = frontier.poll();
            int step = depth.get(from);
            if (step >= budget) continue;
            for (GridPos next : endermanEdges(state, navigator.neighbours(from))) {
                if (parent.containsKey(next) || occupiedByOthers(state, next) || !zone.contains(next)) continue;
                parent.put(next, from);
                depth.put(next, step + 1);
                if (++examined > 512) return List.of();
                if (!EndermanLanding.exposed(level, enderman, feetAtCell(state, next))) {
                    dry = next;
                    break;
                }
                frontier.add(next);
            }
        }
        if (dry == null) return List.of();
        java.util.LinkedList<GridPos> path = new java.util.LinkedList<>();
        for (GridPos at = dry; at != null && !at.equals(state.cell); at = parent.get(at)) path.addFirst(at);
        return List.copyOf(path);
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
                if (state.flying) return AerialMovement.approach(state.cell, state.ap / Math.max(1, state.moveCost), this::airNeighbors,
                        c -> inMeleeRange(c, selectedCell), c -> c.manhattanDistance(selectedCell));
                // A drowned crosses water: its route comes from the same amphibious edges its steps are
                // validated against, and the opponent's own cell stays a legal final edge so the approach can
                // end in melee exactly as the ground route does.
                if (state.amphibious) return AerialMovement.approach(state.cell,
                        state.ap / Math.max(1, state.moveCost),
                        cell -> {
                            List<GridPos> edges = new java.util.ArrayList<>(
                                    ServerBattle.this.amphibiousNeighbours(state, cell));
                            if (AerialMovement.adjacent(cell, selectedCell)) edges.add(selectedCell);
                            return edges;
                        },
                        c -> inMeleeRange(c, selectedCell), c -> c.manhattanDistance(selectedCell));
                return navigator.path(state.cell, selectedCell, ServerBattle.this::passable, 4096, true);
            }
            public List<GridPos> standableNeighbors(GridPos cell) {
                if (state.flying) return airNeighbors(cell);
                if (state.amphibious) return ServerBattle.this.amphibiousNeighbours(state, cell);
                // A door-capable actor asks the door-aware view, so the cells it was planned through - an
                // already open doorway included - are exactly the cells its movement is validated against.
                DoorRouteTerrain terrain = doorTerrain(state);
                List<GridPos> edges = terrain == null ? navigator.neighbours(cell) : terrain.enterableNeighbours(cell);
                // An enderman is 2.9 blocks tall: the two-block grid view would walk it through a hole its real
                // body cannot pass. Its own walk view is therefore the same full-body gate its landings use.
                if (!isEndermanActor(state.mob)) return edges;
                return endermanEdges(state, edges);
            }
            public boolean occupied(GridPos cell) {
                for (PlayerMember member : members.values())
                    if (member.state.cell().equals(cell)) return true;
                for (EnemyState other : enemies.values())
                    if (other != state && other.mob.isAlive() && !other.mob.isRemoved() && other.cell.equals(cell)) return true;
                return false;
            }
            public boolean inMeleeRange(GridPos from, GridPos to) {
                if (state.flying) return Math.abs(from.x()-to.x())<=1 && Math.abs(from.y()-to.y())<=1 && Math.abs(from.z()-to.z())<=1;
                // A drowned's cell adjacency is not reach: its feet depend on the medium, so the real attack box is
                // evaluated at the position it would really hold on that cell, exactly as the native attack does.
                if (state.amphibious || isEndermanActor(state.mob))
                    return ServerBattle.this.amphibiousMeleeReach(state, from, opponent);
                return ServerBattle.inMeleeRange(from,to);
            }
            public boolean hasLineOfSight(GridPos from, GridPos to) { return ServerBattle.this.hasLineOfSight(from,to,state.mob); }
            // ---- surface policy: only the exact two vanilla spider types answer these ----
            public SpiderSurface.Node surfaceHere() { return state.spider == null ? null : state.spider.node(); }
            public List<SpiderSurface.Node> surfaceNeighbours(SpiderSurface.Node from) {
                if (state.spider == null || from == null) return List.of();
                return SpiderSurface.neighbours(level, state.mob, from, this::occupied, zone::contains);
            }
            public boolean surfaceLegal(SpiderSurface.Node node) {
                return state.spider != null && node != null && SpiderSurface.isLegal(level, state.mob, node);
            }
            public boolean surfaceMeleeReach(SpiderSurface.Node node) {
                return state.spider != null && node != null && spiderMeleeReach(state, node, opponent);
            }
            public boolean surfaceInsideZone(SpiderSurface.Node node) {
                return state.spider != null && node != null && SpiderSurface.bodyInsideZone(state.mob, node, zone::contains);
            }
            public boolean surfaceRouteHolds(List<SpiderSurface.Node> route) {
                return state.spider != null && spiderRouteHolds(state, state.spider.node(), route);
            }
            public int surfaceEdgeBudget() { return spiderEdgeBudget(state); }
            // ---- slime family: real body, real hop edges, native contact ----
            public boolean slimeContext() { return SlimeGround.supports(state.mob) && state.slime != null; }
            public SlimeGround.Node slimeHere() { return state.slime; }
            public List<SlimeGround.Node> slimeNeighbours(SlimeGround.Node from) {
                if (state.slime == null || from == null) return List.of();
                return SlimeGround.neighbours(level, state.mob, from, opponentCell == null ? state.cell : opponentCell,
                        this::occupied, zone::contains);
            }
            public boolean slimeRouteHolds(List<SlimeGround.Node> route) {
                return state.slime != null && ServerBattle.this.slimeRouteHolds(state, route);
            }
            public int slimeEdgeBudget() { return ServerBattle.this.slimeEdgeBudget(state); }
            public List<SlimeGround.Node> slimeApproach() { return ServerBattle.this.slimeApproach(state); }
            public boolean slimeContactReaches(SlimeGround.Node node) {
                return ServerBattle.this.slimeContactReaches(state, node);
            }
            public int slimeContactCost() { return ServerBattle.this.slimeContactCost(state); }
            public boolean slimeContact() { return ServerBattle.this.slimeContact(state); }
            public boolean canLeap() { return spiderCanLeap(state); }
            public int leapCost() { return spiderLeapCost(state); }
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
            public int tridentCost() { return ServerBattle.this.mobTridentCost(state); }
            public boolean canThrowTridentFrom(GridPos from) {
                return ServerBattle.this.canThrowTridentFrom(state, opponent, from);
            }
            public boolean throwTrident() { return ServerBattle.this.throwTrident(state); }
            public EndermanState enderman() { return endermanState(state); }
            public int teleportCost() { return ServerBattle.this.endermanTeleportCost(state); }
            public boolean canTeleport() { return endermanCanTeleport(state); }
            public boolean teleport() { return ServerBattle.this.teleport(state); }
            public List<GridPos> dryEscapePath() { return ServerBattle.this.dryEscapePath(state); }
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
        if(action.kind()==AiAction.Kind.SURFACE_MOVE) {
            /* One surface route for a spider. The plan is re-proved node by node against the live world and the
               price is charged once, per edge, exactly like an ordinary move: the unwalked edges are given back
               when the world changes under it, and movement never touches the success counter. */
            List<SpiderSurface.Node> nodes=action.surface();
            if(enemy.spider==null||nodes.isEmpty()||!spiderRouteHolds(enemy,enemy.spider.node(),nodes)) {advanceTurn();return;}
            long total=(long)nodes.size()*enemy.moveCost;
            if(total>enemy.ap){advanceTurn();return;}
            enemy.ap-=(int)total;
            if (DeveloperTrace.enabled()) {
                StringBuilder route = new StringBuilder();
                for (SpiderSurface.Node node : nodes) route.append(' ').append(node);
                Tactical.LOGGER.info("[Tactical] dev-trace spider route session={} tick={} uuid={} from={} "
                                + "budget={} cost={} route={}",
                        session.sessionId(), ticks, enemy.mob.getUUID(), enemy.spider.node(),
                        spiderEdgeBudget(enemy), total, route);
            }
            enemy.spider.beginRoute(nodes);
            enemyPath=List.of();enemyPathIndex=0;enemyStepping=false;enemyMoving=true;
            journal.record("move",BattleText.translatable(BattleText.KEY_ENEMY_MOVE,
                    BattleText.name(enemy.mob), BattleText.literal(nodes.get(nodes.size()-1).cell().toString())));
            sendState();return;
        }
        if (action.kind() == AiAction.Kind.SLIME_HOP) {
            /* One hop route for this family. The plan is re-proved edge by edge against the live world before the
               body leaves its node, and the price is charged once, per edge, exactly like an ordinary move: the
               edges it will not walk are given back, and movement never touches the success counter. */
            List<SlimeGround.Node> nodes = action.hop();
            if (enemy.slime == null || nodes.isEmpty() || enemy.hop != null
                    || nodes.size() > slimeEdgeBudget(enemy) || !slimeRouteHolds(enemy, nodes)) {
                advanceTurn();
                return;
            }
            long total = (long) nodes.size() * enemy.moveCost;
            if (total > enemy.ap) { advanceTurn(); return; }
            enemy.ap -= (int) total;
            enemy.hop = new HopRun(nodes, (int) total, turnEpoch());
            enemyPath = List.of();
            enemyPathIndex = 0;
            enemyStepping = false;
            enemyMoving = true;
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemy.mob.fallDistance = 0.0F;
            journal.record("move", BattleText.translatable(BattleText.KEY_ENEMY_MOVE,
                    BattleText.name(enemy.mob), BattleText.literal(nodes.get(nodes.size() - 1).cell().toString())));
            if (DeveloperTrace.enabled()) {
                StringBuilder route = new StringBuilder();
                for (SlimeGround.Node node : nodes) route.append(' ').append(node.cell());
                Tactical.LOGGER.info("[Tactical] dev-trace slime hop commit session={} tick={} uuid={} from={} "
                                + "budget={} cost={} route={}", session.sessionId(), ticks, enemy.mob.getUUID(),
                        enemy.slime.cell(), slimeEdgeBudget(enemy), total, route);
            }
            sendState();
            return;
        }
        if (action.kind() == AiAction.Kind.SLIME_CONTACT) {
            /* One native contact: the server re-proves the locked opponent, the real attack box at those feet, the
               real eye line, the size's own damage ability and the frozen price, and charges only when the hurt
               call inside vanilla's dealDamage really reported a hit. Nothing here writes a damage number. */
            // One contact per own turn, whether it landed or not: this turn's main action is spent either way,
            // exactly like a refused melee. Without this a body whose box no longer reaches would retry the
            // same call on every tick of its own turn.
            slimeContact(enemy);
            advanceTurn();
            return;
        }
        if (action.kind() == AiAction.Kind.SPIDER_LEAP) {
            /* One short leap. The one proposal of this own turn is the only thing it may fly, and it is re-proved
               here against the live world before the body leaves the floor: the take-off node it really holds, the
               landing, the price, the unused quota and every frame of the arc. Nothing is charged here - the price
               is frozen and taken out of the pool on the first frame that really displaces the body. */
            SpiderLeap.Proposal proposal = spiderLeapProposal(enemy);
            int cost = spiderLeapCost(enemy);
            if (enemy.spider == null || enemy.leap != null || cost <= 0 || enemy.ap < cost
                    || enemy.spiderLeapsUsed > 0 || !spiderLeapStillHolds(enemy, proposal)) {
                advanceTurn();
                return;
            }
            enemy.leap = new LeapRun(proposal, enemy.mob.position(), cost, turnEpoch());
            enemyPath = List.of();
            enemyPathIndex = 0;
            enemyStepping = false;
            enemyMoving = true;
            enemy.mob.setDeltaMovement(Vec3.ZERO);
            enemy.mob.fallDistance = 0.0F;
            journal.record("enemy", BattleText.translatable(BattleText.KEY_ENEMY_LEAP,
                    BattleText.name(enemy.mob), BattleText.literal(proposal.landing().cell().toString())));
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace spider leap commit session={} tick={} uuid={} "
                                + "from={} landing={} cost={} ap={}", session.sessionId(), ticks,
                        enemy.mob.getUUID(), proposal.from(), proposal.landing(), cost, enemy.ap);
            }
            sendState();
            return;
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
        if (result == AiResolution.RELOCATED) {
            // The same own turn continues after a controlled relocation, with exactly one follow-up and only
            // towards a melee or its end: no second relocation, no new walk and no fresh plan inside this turn.
            if (finished || !enemy.mob.getUUID().toString().equals(queue.current())) return;
            AiAction followUp = ai.afterMovement(aiContext(enemy));
            if (followUp.kind() != AiAction.Kind.MELEE && followUp.kind() != AiAction.Kind.END_TURN) {
                followUp = AiAction.endTurn();
            }
            resolveAiAction(enemy, followUp);
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
        // A leap belongs to exactly one own turn. If the turn moved for any other reason (a member leaving, a
        // queue rebuild) while a body was still in the air, that arc is over: it stops where it really is and
        // the fall rule owns what happens next, so no arc can outlive its turn as a phantom hold.
        for (EnemyState flying : enemies.values()) {
            if (flying.leap == null) continue;
            if (DeveloperTrace.enabled()) {
                Tactical.LOGGER.info("[Tactical] dev-trace spider leap abandoned with its turn session={} "
                                + "tick={} uuid={} real={} paid={}", session.sessionId(), ticks,
                        flying.mob.getUUID(), flying.mob.position(), flying.leap.paid);
            }
            flying.leap = null;
            stopLeapAtRealPosition(flying);
        }
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
                // A controlled enderman gets its own-turn quotas here, and settles its water clock once:
                // vanilla's per-tick water call is suspended for exactly as long as this encounter holds it.
                beginEndermanTurn(enemy);
                if (finished || !enemy.mob.isAlive()) return;
                // A new own turn may leap once, and it starts with no leap from the previous turn still in
                // flight and no cached proposal to reuse.
                enemy.leap = null;
                enemy.spiderLeapsUsed = 0;
                enemy.leapProposal = null;
                enemy.leapProposalDone = false;
                // A spider's surface is re-read from where the body really is now: a landing, a knockback or a
                // fall since the last own turn must never leave this turn planning on a stale node.
                resyncSpiderSurface(enemy, enemy.cell);
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
     * Advance the turn queue, unless units are still in the air or a native split is still owed.
     *
     * <p>A forced fall is a barrier for turn flow, not only for commands: nobody may start a new turn and
     * collect its recovery while a combatant is mid-fall. A death or an exit that happens during the fall
     * still takes effect immediately - the member is removed - but the new turn waits until the last fall
     * has settled, which is what keeps the queue and the resources in one order. A split this encounter is
     * owed children by is the same kind of wait, and the two coexist: the next turn only begins once both
     * are closed, and then exactly once.</p>
     */
    private void advanceTurn() {
        if (finished) return;
        if (terrainFall.busy() || splits.pending()) {
            pendingTurnAdvance = true;
            return;
        }
        pendingTurnAdvance = false;
        refreshTacticalAttributes();
        String next = queue.advance();
        beginTurn(next);
    }

    /**
     * Settle the wait once no native split is outstanding.
     *
     * <p>Victory is only declared when nothing can still be born: a dead size-4 parent whose children are
     * being produced is not "all enemies defeated". A turn the barrier deferred is advanced exactly once, so a
     * member that killed a parent keeps its own turn and its own resources instead of being handed a fresh
     * one. An unconfirmed split ends the encounter with its own reason rather than a victory nobody proved.</p>
     */
    private void settleSplitBarrier() {
        if (finished || ending) return;
        if (splits.pending()) return;
        if (hostileCount() == 0) {
            // A fall still owns the encounter: victory waits for it, exactly like a new turn does. The outcome is
            // remembered, never dropped, so a split released unconfirmed cannot later be reported as a victory.
            if (terrainFall.busy()) { victoryDeferred = true; return; }
            finishWith(splits.hasUnreleasedAnomaly()
                    ? BattleText.End.SPLIT_UNRESOLVED : BattleText.End.ENEMIES_DEFEATED);
            return;
        }
        if (pendingTurnAdvance) advanceTurn();
        else sendState();
    }

    /**
     * Settle a victory both barriers had to wait for.
     *
     * <p>Called from the encounter's tick once the forced fall is over: with no hostile left, no owed split and no
     * fall running, the encounter ends on its own recorded reason.</p>
     */
    private void settleDeferredVictory() {
        if (!victoryDeferred || finished || ending) return;
        if (splits.pending() || terrainFall.busy() || hostileCount() > 0) return;
        victoryDeferred = false;
        finishWith(splits.hasUnreleasedAnomaly()
                ? BattleText.End.SPLIT_UNRESOLVED : BattleText.End.ENEMIES_DEFEATED);
    }

    /** End this encounter through one enumerated reason, keeping the code available to diagnostics. */
    private void finishWith(BattleText.End end) {
        endCode = end;
        finish(BattleText.endReason(end));
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
                // A dead parent still owes vanilla's own split to this encounter. It is remembered before the
                // roster entry goes away - together with the flags this encounter borrowed - so the coming
                // native removal can neither happen unseen nor hand the borrowed flags to the whole family.
                captureSplitParent(departed);
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
                    // A departed thrower's trident and pose end with its control, not later in the world.
                    DrownedTridents.clearOwner(this, entry.getKey());
                    releaseTridentPose(entry.getKey());
                    releaseEndermanState(departed);
                    releaseSpiderState(departed);
                    releaseSlimeState(departed);
                    ai.remove(departed.mob);
                    // A departed unit gives up its ownership claim, but only this encounter's: another
                    // encounter's identical UUID or a same-UUID respawn must keep its own routing.
                    BattleManager.releaseActor(this, entry.getKey());
                    departed.mob.setNoAi(departed.originalNoAi);
                    ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) departed.mob)
                            .tactical$persistenceRequired(departed.originalPersistenceRequired);
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
        // An emptied roster is not a victory while this encounter is still owed a native split: vanilla
        // produces those children about twenty death ticks later, and the wait is settled on its own tick. A
        // forced fall is the other barrier: while one runs, the end is only remembered.
        if (hostileCount() == 0 && !splits.pending()) {
            if (terrainFall.busy()) victoryDeferred = true;
            else finishWith(splits.hasUnreleasedAnomaly()
                    ? BattleText.End.SPLIT_UNRESOLVED : BattleText.End.ENEMIES_DEFEATED);
        } else if (removedCurrent && !finished
                && (phase == BattlePhase.ENEMY_TURN || phase == BattlePhase.COMPANION_TURN)) {
            advanceTurn();
        } else if (changed && !finished) {
            // Occupancy, queue, HP and reachable overlay change in the same
            // snapshot, even if death came from a projectile or outside command.
            sendState();
        }
    }

    /**
     * Remember a dead parent before its roster entry is dropped, so vanilla's own split cannot happen unseen.
     *
     * <p>Only this encounter's exact supported bodies with {@code size > 1} in its own level: a size-1 body has
     * no split, a body that left the dimension is not this encounter's removal any more, and every other mob
     * family keeps its own vanilla behaviour.</p>
     */
    private void captureSplitParent(EnemyState departed) {
        if (departed == null || departed.mob == null) return;
        Mob body = departed.mob;
        if (!body.isDeadOrDying() || body.level() != level) return;
        if (!SlimeGround.supports(body)) return;
        if (!(body instanceof net.minecraft.world.entity.monster.Slime slime) || slime.getSize() <= 1) return;
        splits.captureFromDeath(body, departed.side, departed.originalNoAi,
                departed.originalPersistenceRequired);
    }

    /**
     * Capture a dead parent straight from this encounter's roster entry.
     *
     * <p>The native removal can arrive before the death scan; asking the roster is what keeps the borrowed
     * originals exact in that window instead of sampling flags this encounter itself changed.</p>
     */
    SlimeSplitLifecycle.Pending captureSplitFromRoster(Mob parent) {
        EnemyState state = stateOf(parent);
        if (state == null) return null;
        // A death event arrives before vanilla marks the body dead; the roster scan sees it after. Both are
        // the same death, so neither the dead flag nor the health alone may be the gate.
        if (!parent.isDeadOrDying() && parent.getHealth() > 0.0F) return null;
        return splits.captureFromDeath(parent, state.side, state.originalNoAi,
                state.originalPersistenceRequired);
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
            // A spider knocked off its surface is re-read where it really ended up.
            resyncSpiderSurface(state, state.cell);
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
    /**
     * The real feet each falling spider keeps, keyed by the falling unit's id.
     *
     * <p>The encounter's forced fall is cell based - every unit of every type descends one <em>cell</em> at a
     * time - and that is right for a body the size of a cell. A spider is 1.4 wide and holds a lateral offset
     * against the wall it was on, so animating it through cell centres would push its body into the wall it just
     * left. Only the exact two spider types are recorded here; every other unit keeps the untouched path.</p>
     */
    private final java.util.Map<UUID, Vec3> spiderFallFeet = new java.util.HashMap<>();

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
        // A wide spider holds its own lateral position against the wall it lost; the cells it falls through are
        // only half of that fact. Remembering the real feet here is what lets the descent keep them instead of
        // sliding the whole body onto the centre of every cell, which would put it back inside the wall.
        if (!player && SpiderSurface.supports(unit)) spiderFallFeet.put(id, unit.position());
        // This family keeps its real X/Z and its real fractional Y the same way; only the bridge that steps it
        // differs, because a body can be interrupted less than a whole block above its own floor.
        if (!player && SlimeGround.supports(unit)) slimeFallFeet.put(id, unit.position());
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
        if (enemy == null || !enemy.mob.isAlive() || enemy.mob.isRemoved()) {
            terrainFall.end(unit.id());
            spiderFallFeet.remove(unit.id());
            return;
        }
        Vec3 slimeHeld = slimeFallFeet.get(unit.id());
        if (slimeHeld != null) {
            stepFallingSlime(unit, enemy, slimeHeld);
            return;
        }
        Vec3 held = spiderFallFeet.get(unit.id());
        if (held != null) {
            stepFallingSpider(unit, enemy, held.x, held.z);
            return;
        }
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

    /**
     * One slice of a wide spider's forced descent, at the lateral position it really held.
     *
     * <p>The existing fall decides <em>where</em> and <em>how far</em>: the same cells, the same landing, the same
     * deadline, the same damage settlement. Only the geometry of the body is bridged here, and only for the exact
     * two spider types: the body keeps its real X and Z, the first slice starts from its real fractional feet
     * instead of the cell centre, and every slice sweeps the whole body between the two positions it really
     * occupies. That is what stops a wide body from being dragged back into the wall it just lost, which is what
     * made a normal landing unprovable.</p>
     */
    private void stepFallingSpider(TerrainFall.Unit unit, EnemyState enemy, double x, double z) {
        if (unit.stepTo() == null) {
            refreshFallLanding(unit);
            com.tactical.neoforge.battle.terrain.FallPlanner.Step step =
                    com.tactical.neoforge.battle.terrain.FallPlanner.next(unit.cell(), this::hasSupportBelow,
                            cell -> enterableForFall(cell, unit.id()));
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.LAND) {
                settleSpiderFall(unit, enemy, x, z);
                return;
            }
            if (step == com.tactical.neoforge.battle.terrain.FallPlanner.Step.STUCK) return;
            GridPos next = below(unit.cell());
            if (!zone.contains(next)) { abandonTerrainFall(unit); return; }
            unit.beginStep(unit.cell(), next, fallStepTicks());
        }
        double from = enemy.mob.getY();
        double target = unit.stepFrom().y() + (unit.stepTo().y() - unit.stepFrom().y())
                * fallFraction(unit, unit.progress() + 1);
        if (!spiderFallSliceClear(enemy.mob, x, from, target, z)) {
            // Something really is in the way of the body it would sweep: keep its own cell and re-plan next
            // tick, exactly as every other falling unit does.
            unit.endStep();
            return;
        }
        unit.progress(unit.progress() + 1);
        enemy.mob.setPos(x, target, z);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        if (unit.fraction() >= 1.0D) {
            GridPos arrived = unit.stepTo();
            unit.stepLanded(arrived);
            enemy.cell = arrived;
        }
    }

    /** The whole body between the position it holds and the one it is about to take, cleared as one box. */
    private boolean spiderFallSliceClear(LivingEntity body, double x, double fromY, double toY, double z) {
        AABB swept = SpiderSurface.bodyAt(body, new Vec3(x, fromY, z))
                .minmax(SpiderSurface.bodyAt(body, new Vec3(x, toY, z)));
        if (!AmphibiousMovement.loaded(level, swept)) return false;
        if (!level.noCollision(body, swept)) return false;
        return !AmphibiousMovement.overlapsBody(level, body, swept);
    }

    /**
     * Land a spider where it really is.
     *
     * <p>The ordinary landing snaps the body onto the centre of its cell, which for a wide body that held a wall
     * is a position it may not be able to stand in. Only the real, legal feet can settle the fall; otherwise
     * its existing deadline keeps running. An invalid stand must never be repaired by an unswept snap.</p>
     */
    private void settleSpiderFall(TerrainFall.Unit unit, EnemyState enemy, double x, double z) {
        GridPos landing = unit.cell();
        GridPos origin = unit.origin();
        int cells = unit.cells();
        Vec3 feet = new Vec3(x, landing.y(), z);
        if (!SpiderSurface.isLegal(level, enemy.mob, SpiderSurface.Node.groundAt(landing, feet))) return;
        terrainFall.end(unit.id());
        spiderFallFeet.remove(unit.id());
        enemy.cell = landing;
        enemy.mob.setPos(feet);
        enemy.mob.setDeltaMovement(Vec3.ZERO);
        enemy.mob.fallDistance = 0.0F;
        // A landed spider holds the floor it really reached, not the wall it left.
        resyncSpiderSurface(enemy, landing);
        settleFallDamage(enemy.mob, landing, cells);
        journal.record("move", BattleText.translatable(BattleText.KEY_MOVE_FALL,
                BattleText.name(enemy.mob), BattleText.literal(origin.toString()),
                BattleText.literal(landing.toString()), cells));
        if (!finished) sendState();
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
        // A landed spider holds the floor it really reached, not the wall it left.
        resyncSpiderSurface(enemy, landing);
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
        spiderFallFeet.remove(unit.id());
        slimeFallFeet.remove(unit.id());
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
                // A unit that disappears mid-fall also stops owning its trident and its throw pose.
                DrownedTridents.clearOwner(this, unit.id());
                releaseTridentPose(unit.id());
                enemies.remove(unit.id());
                queue.remove(id);
                BattleEffects.forget(enemy.mob);
                ai.remove(enemy.mob);
                BattleManager.releaseActor(this, unit.id());
                // A unit that leaves the battle this way must not keep this encounter's own pose seam or
                // surface state: nothing of this encounter may still be pinning the body it gave back.
                releaseEndermanState(enemy);
                releaseSpiderState(enemy);
                releaseSlimeState(enemy);
                releaseWitchState(enemy);
                enemy.mob.setNoAi(enemy.originalNoAi);
                // A unit released this way is given back to vanilla physics with the same flags it arrived with:
                // the borrowed persistence is not left on a body this encounter no longer owns.
                ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) enemy.mob)
                        .tactical$persistenceRequired(enemy.originalPersistenceRequired);
                enemy.mob.setTarget(null);
                enemy.mob.setDeltaMovement(Vec3.ZERO);
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

    /**
     * Read-only fixture view of one roster unit's authoritative state.
     *
     * <p>No setter and no mutation: a fixture may observe what the encounter really decided (its cell, its AP,
     * its surface node, whether a trajectory is still advancing) instead of inferring it from a client mirror.</p>
     */
    record EnemyView(GridPos cell, int ap, int maxAp, int moveCost, int paidActions,
                     SpiderSurface.Node node, boolean moving, boolean alive, Vec3 feet,
                     boolean leaping, int leapFrames, boolean leapPaid, int leapsUsed,
                     GridPos leapLanding, SlimeGround.Node slime, boolean hopping,
                     int hopEdges, int hopIndex, boolean hopEdgeStarted, int hopPrice) { }

    /**
     * Fixture-only: set one enemy's own AP, so a deterministic case can prove a transaction whose pool changes
     * between the commit and the first real displacement, or hand a unit exactly the pool a case needs.
     *
     * <p>This is a setter, not a readback: it writes the authoritative pool of that unit (clamped into its own
     * cap) and changes nothing unless a fixture calls it. The readback of the same value is
     * {@link EnemyView#ap()}; {@link #fixtureEnemyPool(UUID, int)} is the same write with a "was that unit there"
     * answer, kept because two fixtures ask by different names.</p>
     */
    void fixtureEnemyAp(UUID id, int ap) {
        fixtureEnemyPool(id, ap);
    }

    /**
     * Fixture-only: change one enemy's frozen per-edge move price after its route was committed.
     *
     * <p>Needed to prove that a refund settles at the price frozen at the commit instead of a price that changed
     * afterwards; it writes the same field a property refresh would, and nothing happens unless a fixture asks.
     * </p>
     */
    boolean fixtureEnemyMoveCost(UUID id, int moveCost) {
        EnemyState state = id == null ? null : enemies.get(id);
        if (state == null) return false;
        state.moveCost = Math.max(1, moveCost);
        return true;
    }

    /** Fixture-only readback: the bounded descent one tick of this family's fall may take. */
    double fixtureSubCellDescent() {
        return subCellDescent();
    }

    /** Fixture-only: set one enemy's pool and report whether that exact unit was there to set it. */
    boolean fixtureEnemyPool(UUID id, int ap) {
        EnemyState state = id == null ? null : enemies.get(id);
        if (state == null) return false;
        state.ap = Math.max(0, Math.min(state.maxAp, ap));
        return true;
    }

    /**
     * Fixture-only: open a native-split wait for this body without a native removal.
     *
     * <p>The one thing a server fixture cannot produce is a split that is never confirmed (an unloaded chunk or
     * a third-party caller holding a dead body back). This opens the same wait the death scan opens, so the
     * abnormal cutoff can be observed over real ticks; the release, the barrier and the end reason are the
     * production ones.</p>
     */
    boolean fixtureSplitWait(Mob parent, BattleSide side) {
        if (parent == null) return false;
        return splits.capture(parent, side, parent.isNoAi(), parent.isPersistenceRequired()) != null;
    }

    /** Fixture-only: how many native splits this encounter is still owed. */
    int fixtureSplitPending() {
        return splits.pendingCount();
    }

    /** Fixture-only: how many children a native split really produced for this encounter. */
    int fixtureSplitBorn() {
        return splits.bornTotal();
    }

    /** Fixture-only: how many of those were admitted by this encounter exactly once. */
    int fixtureSplitAdmitted() {
        return splits.admittedTotal();
    }

    /** Fixture-only: how many split waits ended at the abnormal cutoff. */
    int fixtureSplitAnomalies() {
        return splits.anomalies();
    }

    /** Fixture-only: the exact parents this encounter is waiting on. */
    List<UUID> fixtureSplitWaiting() {
        return splits.waitingParents();
    }

    /** Fixture-only: whether this child is held out because its split could not be taken. */
    boolean fixtureHoldsSplitChild(Mob mob) {
        return isolation.holdsSplitChild(mob);
    }

    /** Fixture-only: the faction of one roster entry, so a child's inherited side can be read. */
    BattleSide fixtureSide(UUID id) {
        EnemyState state = id == null ? null : enemies.get(id);
        return state == null ? null : state.side;
    }

    /** Fixture-only: the coded reason this encounter ended with, or null. */
    BattleText.End fixtureEndCode() {
        return endCode;
    }

    /** Fixture-only: whether a victory is waiting for the fall and split barriers to clear. */
    boolean fixtureVictoryDeferred() {
        return victoryDeferred;
    }

    /** Fixture-only: the forced-fall barrier on its own, so the two barriers can be told apart. */
    boolean fixtureFallBusy() {
        return terrainFall.busy();
    }

    /** Fixture-only: the owed-split barrier on its own. */
    boolean fixtureSplitBusy() {
        return splits.pending();
    }

    /** Fixture-only: the live session id, so a fixture can build a real intent stamp. */
    UUID fixtureSessionId() {
        return session.sessionId();
    }

    /** Fixture-only: the AI flag this roster entry captured before the encounter borrowed one. */
    Boolean fixtureOriginalNoAi(UUID id) {
        EnemyState state = id == null ? null : enemies.get(id);
        return state == null ? null : state.originalNoAi;
    }

    /** Fixture-only: the persistence flag this roster entry captured, which no borrowed value may become. */
    Boolean fixtureOriginalPersistence(UUID id) {
        EnemyState state = id == null ? null : enemies.get(id);
        return state == null ? null : state.originalPersistenceRequired;
    }

    /** Fixture-only: how many paid actions this member has made this turn. */
    int fixtureMemberPaid(ServerPlayer player) {
        PlayerParticipant state = memberState(player);
        return state == null ? -1 : state.paidActions();
    }

    EnemyView fixtureEnemy(UUID id) {
        EnemyState state = id == null ? null : enemies.get(id);
        if (state == null || state.mob == null) return null;
        SpiderLeap.Proposal proposal = state.leap != null ? state.leap.proposal : state.leapProposal;
        HopRun hop = state.hop;
        return new EnemyView(state.cell, state.ap, state.maxAp, state.moveCost, state.paidActions,
                state.spider == null ? null : state.spider.node(), enemyMoving,
                state.mob.isAlive() && !state.mob.isRemoved(), state.mob.position(),
                state.leap != null, state.leap == null ? 0 : state.leap.frames,
                state.leap != null && state.leap.paid, state.spiderLeapsUsed,
                proposal == null ? null : proposal.landing().cell(), state.slime, hop != null,
                hop == null ? 0 : hop.route.size(), hop == null ? 0 : hop.index,
                hop != null && hop.edgeStarted, hop == null ? 0 : hop.cost);
    }

    /**
     * One hop route in flight: the nodes it may still walk, the edge it is animating and what it already spent.
     *
     * <p>The price of the whole route was frozen and paid when it was committed, so a stop only has to give back
     * the edges the body never started - never a price re-read from a changed {@code moveCost}.</p>
     */
    private static final class HopRun {
        final List<SlimeGround.Node> route;
        final int cost;
        final long epoch;
        int index;
        SlimeMotion.Leg edge;
        SlimeGround.Node to;
        int frames;
        boolean edgeStarted;

        HopRun(List<SlimeGround.Node> route, int cost, long epoch) {
            this.route = route;
            this.cost = cost;
            this.epoch = epoch;
        }
    }

    /**
     * One short leap really in flight: the proposal being walked, where it started, its frozen price and how
     * many of its fixed frames have already happened.
     *
     * <p>The price is frozen when the leap is committed, exactly like every other action, and it is only taken
     * out of the pool once the body has really left the floor - so a leap the world refuses at its first frame
     * costs nothing and leaves no success behind.</p>
     */
    private static final class LeapRun {
        final SpiderLeap.Proposal proposal;
        final Vec3 start;
        final int cost;
        final long epoch;
        int frames;
        boolean paid;

        LeapRun(SpiderLeap.Proposal proposal, Vec3 start, int cost, long epoch) {
            this.proposal = proposal;
            this.start = start;
            this.cost = cost;
            this.epoch = epoch;
        }

        int ticks() { return proposal.ticks(); }

        Vec3 point(int frame) { return proposal.point(start, frame); }
    }

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
        /**
         * True for the one vanilla type that really swims: the drowned keeps water cells as cells, is pinned
         * where it really is instead of being snapped onto a floor, and steps with the 26-way water rule.
         * It is deliberately not {@link #flying}: a drowned in clear air is not a supported position.
         */
        final boolean amphibious;
        /**
         * The surface this exact unit really holds, for the two vanilla spider types only.
         *
         * <p>A spider keeps its node (cell, real feet, floor or wall side) instead of a bare cell, because a
         * cell alone cannot say whether the body is standing on a floor or holding a wall - and a stale "it was
         * on a wall" is exactly what would leave it hovering after the wall is gone.</p>
         */
        SpiderTraversal spider;
        /**
         * The real ground node this exact body holds, for the two vanilla slime types only.
         *
         * <p>A node carries the feet the body really has, because a body 2.08 blocks wide and tall cannot be
         * described by a cell alone - and a cell-only view would walk it through a hole its body cannot pass.</p>
         */
        SlimeGround.Node slime;
        /** The hop route this own turn is really flying, or null; it owns the turn until it lands or stops. */
        HopRun hop;
        /**
         * The one bounded route proposal of this own turn, cached with the turn and epoch it was resolved in.
         * A getter re-asks the live world about this same proposal instead of scanning again, so planning,
         * validation and the commit all name one immutable answer.
         */
        List<SlimeGround.Node> slimeRoute;
        boolean slimeRouteDone;
        long slimeRouteEpoch;
        int slimeRouteTurn;
        /**
         * The one bounded short-leap proposal of this own turn, cached with the turn and epoch it was resolved
         * in. A getter re-asks the live world about this same proposal instead of scanning for another one, so
         * planning, validation and the commit all name one immutable landing.
         */
        SpiderLeap.Proposal leapProposal;
        boolean leapProposalDone;
        long leapProposalEpoch;
        int leapProposalTurn;
        /** How many short leaps really left the ground this own turn: never more than one. */
        int spiderLeapsUsed;
        /** The leap in flight, or null; it owns the turn and the busy flag until it lands or is stopped. */
        LeapRun leap;
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
        /**
         * Enderman transients. The handler is the routing seam the native mixins ask while this encounter holds
         * that unit; every other field is this encounter's own decision state and is dropped on release, so a
         * released or dead enderman is a plain vanilla enderman with no leftover quota.
         */
        EndermanLandingHandler endermanHandler;
        /** Own-turn relocation quota: one attempt per own turn, reset when that turn begins. */
        int endermanTeleportsLeft;
        /**
         * The one successful reaction of this cycle. Only a reaction that really moved the body spends it, so a
         * refused attempt (no price, no point, a vetoing event) stays free and a later real hit may still react.
         */
        boolean endermanReactionSuccess;
        /** The raw water/rain exposure this own turn started with, read from the entity itself. */
        boolean endermanExposed;
        /** A free relocation vanilla would have performed became a request for this unit's own turn. */
        boolean endermanPendingEscape;
        /** That request, handed to exactly one own turn's plan. */
        boolean endermanEscapeThisTurn;
        /** The bounded proposal scan of this own turn already ran; it never runs twice. */
        boolean endermanScanned;
        /** The single landing proposal this own turn plans, validates and commits against. */
        EndermanLanding.Landing endermanProposal;
        /** The one native relocation request in flight; the landing gate answers only inside it. */
        EndermanRequest endermanRequest;
        /** Depth guard: one hurt call owns one reaction, and a nested damage reuses that decision. */
        int endermanHurtDepth;
        /** Whether that hurt is the native projectile/potion branch, so its refused dodge is not an escape. */
        boolean endermanHurtBranch;

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
            this.amphibious=AmphibiousMovement.supports(mob);
            this.airOffset=mob.position().subtract(new Vec3(cell.x()+.5,cell.y(),cell.z()+.5));
            this.cell = cell;
            this.ap = ap;
            this.maxAp = maxAp;
            this.moveCost = moveCost;
            this.initiative = initiative;
        }
    }
}
