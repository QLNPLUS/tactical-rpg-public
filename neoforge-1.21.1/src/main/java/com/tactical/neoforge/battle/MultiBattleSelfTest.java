package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.battle.multi.BattleRelationPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Headless multiplayer self test driven by {@code -Dtactical.selftest=multi_battle}.
 *
 * <p>Server-side fixture only: it proves roster, invite, authority rejection, the safe join
 * boundary, relation routing, public recruitment and per-member lifecycle without a socket.
 * Real two-client input acceptance is still required separately and is not claimed here.</p>
 *
 * <p>A failure must be visible: the JVM exits non-zero so the harness cannot read a green
 * Gradle task as a passing test.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class MultiBattleSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static ServerPlayer owner;
    private static ServerPlayer guest;
    private static ServerPlayer third;
    private static ServerPlayer fourth;
    private static ServerPlayer outsider;
    private static Zombie zombie;
    private static String stage = "setup";
    /** Multi-tick bootstrap-retry probe: the member whose entry publication is owed. */
    private static UUID bootstrapProbe;
    private static boolean bootstrapProbeDone;
    private static ServerBattle bootstrapBattle;
    private static ServerPlayer bootstrapOther;
    private static long bootstrapScheduledTick;
    private static long bootstrapSkillsBefore;

    private MultiBattleSelfTest() { }

    /** Cells already handed out, so two fixtures never start on the same grid cell. */
    private static final java.util.Set<GridPos> CLAIMED = new java.util.HashSet<>();

    private static ServerPlayer player(ServerLevel level, BlockPos spawn, int dx, String name) {
        ServerPlayer player = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setPos(spawn.getX() + dx + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        placeOnStandableGround(level, player, spawn);
        return player;
    }

    /**
     * The spawn platform can be only a couple of blocks wide, so a fixture must be placed on real
     * standable ground or the join is legitimately refused as OUTSIDE_ZONE. Each fixture claims a
     * distinct cell so no test starts on top of another combatant.
     */
    private static void placeOnStandableGround(ServerLevel level, ServerPlayer player, BlockPos spawn) {
        LevelGridWorld world = new LevelGridWorld(level);
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    GridPos cell = world.surfaceCell(spawn.getX() + dx, spawn.getZ() + dz, spawn.getY());
                    if (cell == null || !CLAIMED.add(cell)) continue;
                    player.setPos(cell.x() + 0.5D, cell.y(), cell.z() + 0.5D);
                    return;
                }
            }
        }
        Tactical.LOGGER.warn("[Tactical] fixture could not find standable ground near {}", spawn);
    }

    /** Flat stone floor with clear air above, so paths exist without touching any production world. */
    private static void buildPlatform(ServerLevel level, BlockPos center, int radius) {
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(center.getX() + dx, center.getY() - 1, center.getZ() + dz);
                level.setBlock(floor, stone, 3);
                level.setBlock(floor.above(), air, 3);
                level.setBlock(floor.above(2), air, 3);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!"multi_battle".equals(System.getProperty("tactical.selftest", ""))) return;
        enabled = true;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        // Isolated fixture world only: a flat walkable platform so enemy navigation is reachable.
        buildPlatform(level, spawn, 8);
        owner = player(level, spawn, 0, "MultiOwner");
        guest = player(level, spawn, 1, "MultiGuest");
        third = player(level, spawn, 2, "MultiThird");
        fourth = player(level, spawn, 3, "MultiFourth");
        outsider = player(level, spawn, -2, "MultiOutsider");
        zombie = EntityType.ZOMBIE.create(level);
        if (zombie != null) {
            zombie.setPos(spawn.getX() + 5.5D, spawn.getY(), spawn.getZ() + 0.5D);
            level.addFreshEntity(zombie);
        }
        Tactical.LOGGER.info("[Tactical] multi selftest prepared at {}", spawn);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        if (ticks < 20) return;
        if (ticks == 20) {
            // Synchronous roster / authority / lifecycle assertions first.
            try {
                run(event.getServer());
                beginBootstrapProbe(event.getServer());
            } catch (Throwable failure) {
                fail(event.getServer(), failure);
            }
            return;
        }
        if (bootstrapProbe != null && !bootstrapProbeDone) {
            // A real multi-tick stage: the server tick is what retries an owed bootstrap.
            try {
                pollBootstrapProbe(event.getServer());
            } catch (Throwable failure) {
                fail(event.getServer(), failure);
            }
            return;
        }
        if (navBattle == null) {
            try {
                beginNavigation(event.getServer());
            } catch (Throwable failure) {
                fail(event.getServer(), failure);
            }
            return;
        }
        try {
            tickNavigation(event.getServer());
        } catch (Throwable failure) {
            fail(event.getServer(), failure);
        }
    }

    // ------------------------------------------------------------- moving-member join boundary

    /**
     * A real {@code tryMove} puts the acting member into a running path; the invitation that was
     * already pending must then be refused as {@code NOT_A_SAFE_BOUNDARY} without consuming it, and
     * must still work once the path has actually finished. Drives real battle ticks, so a boolean
     * helper cannot fake the busy state.
     */
    private static void movingJoinBoundary(MinecraftServer server) {
        ServerLevel level = server.overworld();
        enter("join-boundary-moving-member");
        BattleManager.reset();
        hostileFor(level);
        owner.getInventory().clearContent();
        guest.getInventory().clearContent();
        check(BattleManager.start(owner, true), "moving-boundary encounter starts");
        ServerBattle battle = BattleManager.current();
        check(battle != null, "moving-boundary battle registered");
        check(BattleManager.invite(owner, guest) == BattleEligibility.Denial.NONE,
                "the invitation exists before the owner moves");

        GridPos from = battle.playerCell(owner);
        check(from != null, "the owner has an authoritative cell");
        GridPos target = null;
        List<GridPos> walk = List.of();
        GridPos[] candidates = {
                from.offset(2, 0, 0), from.offset(-2, 0, 0), from.offset(0, 0, 2), from.offset(0, 0, -2),
                from.offset(1, 0, 0), from.offset(-1, 0, 0), from.offset(0, 0, 1), from.offset(0, 0, -1)};
        for (GridPos candidate : candidates) {
            if (!LevelGridWorld.isStandable(level, candidate.x(), candidate.y(), candidate.z())) continue;
            var options = battle.movementOptions(candidate);
            if (options == null || options.choice() || options.walk().size() < 2) continue;
            target = candidate;
            walk = options.walk();
            break;
        }
        check(target != null, "the platform offers a multi-cell walk route for the moving fixture");
        check(battle.tryMove(owner, battle.stamp(), target), "the owner starts a real walk route");
        check(battle.isBusy(), "a started route makes the encounter busy before any tick runs");
        int membersBefore = battle.memberCount();
        int revisionBefore = battle.stateRevision();
        UUID actorBefore = battle.currentActorId();

        BattleEligibility.Denial blocked = battle.joinHeadless(guest);
        check(blocked == BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY,
                "accepting into a running move is refused as NOT_A_SAFE_BOUNDARY: " + blocked);
        check(battle.memberCount() == membersBefore, "the refused acceptance adds no member");
        check(battle.stateRevision() == revisionBefore, "the refused acceptance changes no authoritative state");
        check(owner.getUUID().equals(actorBefore), "the move is driven by the owner");
        check(owner.getUUID().equals(battle.currentActorId()),
                "the current actor is unchanged after the refusal: " + battle.currentActorId());
        check(battle.pendingInvite(guest.getUUID()) == BattleEligibility.Denial.NONE,
                "the invitation survives the boundary refusal for a retry");
        check(BattleManager.findFor(guest.getUUID()) == null, "the refused guest is not routed yet");

        for (int tick = 0; tick < 400 && battle.isBusy() && !battle.finished(); tick++) battle.tick();
        check(!battle.finished(), "the moving-boundary encounter survives the route");
        check(!battle.isBusy(), "the real route finishes after battle ticks");
        check(battle.joinHeadless(guest) == BattleEligibility.Denial.NONE,
                "the same invitation joins once the path has finished");
        check(battle.memberCount() == membersBefore + 1, "the retried acceptance adds exactly one member");
        check(BattleManager.findFor(guest.getUUID()) == battle, "the joined guest is routed to the encounter");
        Tactical.LOGGER.info("[Tactical] join boundary stage route={} steps={} (tick budget 400)", walk, walk.size());
        BattleManager.reset();
    }

    // ------------------------------------------------------------------ two-encounter isolation

    /** Effect whose pulses are counted, so one own-turn cycle is directly observable. */
    private static final class CountingClockEffect extends MobEffect {
        int pulses;

        CountingClockEffect() {
            super(MobEffectCategory.NEUTRAL, 0);
        }

        @Override public boolean shouldApplyEffectTickThisTick(int time, int amplifier) {
            return true;
        }

        @Override public boolean applyEffectTick(LivingEntity actor, int amplifier) {
            pulses++;
            return true;
        }
    }

    /**
     * Hand exactly one real turn over for this encounter and return the turn epoch afterwards.
     *
     * <p>Drives the actual acting unit: the member's own end-turn when it is that unit, otherwise
     * bounded battle ticks until the AI turn ends. Comparing "not lower" could not prove progress at
     * all, so the caller asserts a strict increase.</p>
     */
    private static long driveOneTurn(ServerBattle battle, ServerPlayer member) {
        if (battle.finished()) return battle.turnEpoch();
        if (battle.isPlayerTurnNow(member) && !battle.isBusy()) {
            battle.endPlayerTurn(member, battle.stamp());
            return battle.turnEpoch();
        }
        long before = battle.turnEpoch();
        for (int tick = 0; tick < 600 && !battle.finished() && battle.turnEpoch() == before; tick++) {
            battle.tick();
        }
        return battle.turnEpoch();
    }

    /**
     * The last battle-state snapshot the server attempted to publish to this recipient.
     *
     * <p>Uses the fixture-only publication capture in {@code ServerPackets}: a rollback has to be
     * judged by what the client was last told, not only by server-side state.</p>
     */
    private static com.tactical.neoforge.network.BattleStatePacket lastStateFor(UUID playerId) {
        var packets = com.tactical.neoforge.network.ServerPackets.capturedFor(playerId);
        for (int index = packets.size() - 1; index >= 0; index--) {
            if (packets.get(index) instanceof com.tactical.neoforge.network.BattleStatePacket state) {
                return state;
            }
        }
        return null;
    }

    /**
     * Arrange a state that progresses by itself: hand the turn to the AI side using direct calls, so
     * that a following manager-only tick loop is what actually advances the encounter.
     */
    private static void makeEnemyActing(ServerBattle battle, ServerPlayer member) {
        for (int guard = 0; guard < 4 && !battle.finished() && battle.isPlayerTurnNow(member)
                && !battle.isBusy(); guard++) {
            battle.endPlayerTurn(member, battle.stamp());
        }
    }

    /**
     * Two encounters alive in one server at the same time.
     *
     * <p>Proves the MS01/MS02 skeleton on real entities: exclusive ownership for players and NPCs,
     * both encounters advancing through the same tick loop, a refused creation that leaves an
     * existing encounter and its candidate enemy untouched, and an exit that releases only its own
     * roster, ownership and state. Effects/MP/queue isolation is asserted through the second
     * encounter's authoritative counters and its surviving ownership claims.</p>
     */
    private static void twoEncounterIsolation(MinecraftServer server) {
        ServerLevel level = server.overworld();
        enter("two-encounter-setup");
        BattleManager.reset();
        LevelGridWorld world = new LevelGridWorld(level);
        GridPos origin = world.surfaceCell(owner.getBlockX(), owner.getBlockZ(), owner.getBlockY());
        check(origin != null, "two-encounter origin is standable");
        // Deterministic: only the two enemies this stage creates may exist around both battlefields.
        for (Mob leftover : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(
                origin.x() - 96, origin.y() - 24, origin.z() - 96,
                origin.x() + 97, origin.y() + 25, origin.z() + 97))) {
            if (leftover instanceof net.minecraft.world.entity.monster.Enemy) leftover.discard();
        }
        // The second battlefield has to be clear of the first by more than two zone radii
        // (configured radius 24), otherwise the deliberate same-dimension overlap rule refuses it.
        BlockPos far = new BlockPos(origin.x() + 72, origin.y(), origin.z());
        buildPlatform(level, far, 8);
        ServerPlayer distant = player(level, far, 0, "MultiDistant");
        // A standable spot outside the first zone (radius 24) but close enough to be absorbed: the
        // absorb/refusal checks below must reach the real deployment step, not fail on bare terrain.
        BlockPos ridge = new BlockPos(origin.x() + 30, origin.y(), origin.z());
        buildPlatform(level, ridge, 3);
        Zombie firstEnemy = EntityType.ZOMBIE.create(level);
        Zombie secondEnemy = EntityType.ZOMBIE.create(level);
        check(firstEnemy != null && secondEnemy != null, "two-encounter enemies created");
        firstEnemy.setPos(owner.getX() + 2.5D, owner.getY(), owner.getZ() + 2.5D);
        level.addFreshEntity(firstEnemy);
        secondEnemy.setPos(distant.getX() + 2.5D, distant.getY(), distant.getZ() + 2.5D);
        level.addFreshEntity(secondEnemy);

        enter("two-encounter-start");
        com.tactical.neoforge.network.ServerPackets.startCapture();
        check(BattleManager.start(owner, true), "the first encounter starts");
        // Read before stopCapture: stopping forgets what was captured.
        var founderSequence = com.tactical.neoforge.network.ServerPackets.capturedFor(owner.getUUID());
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        ServerBattle first = BattleManager.findFor(owner.getUUID());
        check(first != null, "the first founder is routed to its own encounter");
        // The founder is admitted through the same publish path as any other member.
        long founderGeneration = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(owner);
        int founderTransition = -1;
        int founderJournal = -1;
        for (int index = 0; index < founderSequence.size(); index++) {
            var packet = founderSequence.get(index);
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot
                    && snapshot.active() && first.sessionId().equals(snapshot.sessionId())
                    && snapshot.membershipGeneration() == founderGeneration && founderTransition < 0) {
                founderTransition = index;
            } else if (packet instanceof com.tactical.neoforge.network.BattleLogPacket log
                    && first.sessionId().equals(log.battleId()) && founderJournal < 0) {
                founderJournal = index;
            }
        }
        StringBuilder founderDump = new StringBuilder();
        for (var packet : founderSequence) {
            founderDump.append(packet.getClass().getSimpleName());
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot) {
                founderDump.append("(active=").append(snapshot.active())
                        .append(",gen=").append(snapshot.membershipGeneration())
                        .append(",session=").append(snapshot.sessionId()).append(')');
            }
            founderDump.append(' ');
        }
        check(founderTransition >= 0, "the founder receives an ACTIVE transition at its own generation; captured="
                + founderSequence.size() + " gen=" + founderGeneration + " -> " + founderDump);
        check(founderJournal > founderTransition, "and the journal follows that transition");
        check(BattleManager.start(distant, true), "a second encounter starts while the first is running");
        ServerBattle second = BattleManager.findFor(distant.getUUID());
        check(second != null && second != first, "the two founders are routed to different encounters");
        check(BattleManager.active().size() == 2, "the registry holds exactly two live encounters");
        check(BattleManager.current() == null,
                "the single-encounter compatibility view refuses to guess between two");
        check(BattleManager.findById(first.sessionId()) == first
                && BattleManager.findById(second.sessionId()) == second, "each session id resolves to its own encounter");

        enter("two-encounter-ownership");
        check(BattleManager.findFor(firstEnemy.getUUID()) == first, "the first enemy belongs to the first encounter");
        check(BattleManager.findFor(secondEnemy.getUUID()) == second, "the second enemy belongs to the second encounter");
        check(first.isParticipant(firstEnemy) && !first.isParticipant(secondEnemy),
                "the first encounter holds only its own enemy");
        check(second.isParticipant(secondEnemy) && !second.isParticipant(firstEnemy),
                "the second encounter holds only its own enemy");
        check(BattleManager.containing(firstEnemy) == first && BattleManager.containing(secondEnemy) == second,
                "NPC routing resolves through the ownership index, not through a position guess");
        check(BattleManager.findFor(outsider.getUUID()) == null, "a bystander belongs to no encounter");

        enter("two-encounter-independent-tick");
        for (int tick = 0; tick < 4; tick++)
            BattleManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
        check(BattleManager.active().size() == 2, "both encounters survive the shared tick loop");
        check(BattleManager.findFor(owner.getUUID()) == first
                && BattleManager.findFor(distant.getUUID()) == second, "ticking never re-routes a member");
        check(!first.finished() && !second.finished(), "one shared tick loop keeps both encounters running");

        enter("two-encounter-strict-turn-progress");
        // "not lower" proves nothing: each encounter must really hand a turn over, and doing so in
        // one encounter must not move the other.
        long firstEpochBefore = first.turnEpoch();
        long secondEpochBefore = second.turnEpoch();
        long firstEpochAfter = driveOneTurn(first, owner);
        check(firstEpochAfter > firstEpochBefore,
                "the first encounter really advances a turn: " + firstEpochBefore + " -> " + firstEpochAfter);
        check(second.turnEpoch() == secondEpochBefore,
                "a turn in the first encounter never advances the second");
        long secondEpochAfter = driveOneTurn(second, distant);
        check(secondEpochAfter > secondEpochBefore,
                "the second encounter really advances a turn: " + secondEpochBefore + " -> " + secondEpochAfter);
        check(!first.finished() && !second.finished(), "both encounters survive their own turn hand-over");

        enter("two-encounter-manager-tick-progress");
        // The manager's own per-tick loop must advance BOTH encounters, not just the first one.
        // The arrangement uses direct calls; the measured progress below comes only from
        // BattleManager.onServerTick, because an AI turn is what ends by itself.
        makeEnemyActing(first, owner);
        makeEnemyActing(second, distant);
        owner.setHealth(owner.getMaxHealth());
        distant.setHealth(distant.getMaxHealth());
        long managerFirstBefore = first.turnEpoch();
        long managerSecondBefore = second.turnEpoch();
        for (int tick = 0; tick < 60; tick++)
            BattleManager.onServerTick(new ServerTickEvent.Post(() -> true, server));
        check(first.turnEpoch() > managerFirstBefore,
                "the manager's per-tick loop advances the first encounter: "
                        + managerFirstBefore + " -> " + first.turnEpoch());
        check(second.turnEpoch() > managerSecondBefore,
                "the manager's per-tick loop advances the second encounter: "
                        + managerSecondBefore + " -> " + second.turnEpoch());
        check(BattleManager.active().size() == 2, "both encounters stay registered through the loop");

        enter("two-encounter-member-reaccept-keeps-binding");
        // Rollback trap: an existing member re-enters the public join path while its entity is
        // outside the zone, so the boundary check is skipped and only the session's own re-check
        // refuses. An ordinary same-cell duplicate call is stopped earlier by the occupancy check,
        // so this is the reproducible shape of the bug, not a claim about every duplicate call.
        Vec3 ownerHome = owner.position();
        owner.setPos(origin.x() + 600.5D, origin.y(), origin.z() + 0.5D);
        int membersBeforeRefusal = first.memberCount();
        long epochBeforeRefusal = first.turnEpoch();
        BattleEligibility.Denial refused = first.join(owner, level.getGameTime());
        check(refused != BattleEligibility.Denial.NONE,
                "an out-of-zone member's re-accept is refused: " + refused);
        check(BattleManager.findFor(owner.getUUID()) == first,
                "a refused re-accept never releases an existing member's ownership");
        check(first.isMember(owner), "the existing member keeps its roster slot");
        check(first.memberCount() == membersBeforeRefusal, "the refused re-accept adds no member");
        check(first.turnEpoch() == epochBeforeRefusal, "the refused re-accept changes no turn");
        owner.setPos(ownerHome.x, ownerHome.y, ownerHome.z);

        enter("two-encounter-foreign-unit-refusal");
        // A unit owned by the other encounter, standing outside this battlefield: absorbing it WOULD
        // grow this zone, so a refusal must leave zone, reach, ownership, roster and queue untouched.
        Zombie foreign = EntityType.ZOMBIE.create(level);
        check(foreign != null, "foreign candidate created");
        foreign.setPos(owner.getX() + 30.5D, owner.getY(), owner.getZ() + 0.5D);
        level.addFreshEntity(foreign);
        check(BattleManager.sessions().bind(second.sessionId(), foreign.getUUID()),
                "the other encounter owns this unit in the ownership index");
        int foreignQueue = first.turnQueueSize();
        int foreignEnemies = first.enemyCount();
        var foreignZone = first.zone().bounds();
        double reachBefore = owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        boolean foreignNoAi = foreign.isNoAi();
        Vec3 foreignPosition = foreign.position();
        check(!first.tryAbsorb(foreign), "absorbing a unit owned by the other encounter is refused");
        check(BattleManager.findFor(foreign.getUUID()) == second,
                "the refused unit keeps the other encounter's ownership");
        check(first.turnQueueSize() == foreignQueue && first.enemyCount() == foreignEnemies,
                "the refused absorb adds no queue entry and no roster entry");
        check(first.zone().bounds().equals(foreignZone),
                "the refused absorb does not grow this battlefield");
        check(owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == reachBefore,
                "the refused absorb does not extend this encounter's reach");
        check(foreign.isNoAi() == foreignNoAi && foreign.position().equals(foreignPosition),
                "the refused unit is never moved and never loses its own AI");
        check(second.isParticipant(secondEnemy) && !second.finished(),
                "the other encounter is unaffected by the refused absorb");
        foreign.discard();

        enter("two-encounter-admission-fault-rollback");
        // Case 1: a unit that was NOT persistence-required must come back not persistent.
        Zombie faulty = EntityType.ZOMBIE.create(level);
        check(faulty != null, "fault candidate created");
        faulty.setPos(owner.getX() - 2.5D, owner.getY(), owner.getZ() + 0.5D);
        level.addFreshEntity(faulty);
        boolean faultyNoAi = faulty.isNoAi();
        boolean faultyPersistent = faulty.isPersistenceRequired();
        check(!faultyPersistent, "the fixture unit starts without the persistence flag");
        Vec3 faultyPosition = faulty.position();
        LivingEntity faultyTarget = faulty.getTarget();
        int faultQueue = first.turnQueueSize();
        int faultEnemies = first.enemyCount();
        var faultZone = first.zone().bounds();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        check(!first.tryAbsorb(faulty), "an injected admission fault refuses the absorb");
        check(ServerBattle.pendingFaults() == 0,
                "the injected fault was really consumed by an admission, so the rollback ran");
        check(faulty.isNoAi() == faultyNoAi, "the failed admission restores the unit's own AI flag");
        check(faulty.isPersistenceRequired() == faultyPersistent,
                "a failed admission restores persistence exactly: false stays false");
        check(faulty.position().equals(faultyPosition), "the failed admission restores its position");
        check(faulty.getTarget() == faultyTarget, "the failed admission restores its target");
        check(BattleManager.findFor(faulty.getUUID()) == null,
                "the failed admission releases its ownership claim");
        check(first.turnQueueSize() == faultQueue && first.enemyCount() == faultEnemies,
                "the failed admission leaves no queue entry and no roster entry");
        check(first.zone().bounds().equals(faultZone),
                "the failed admission does not grow this battlefield");
        check(BattleManager.findFor(distant.getUUID()) == second && !second.finished(),
                "the failed admission leaves the other encounter alone");
        faulty.discard();

        // Case 2: the same rollback must keep an already-persistent unit persistent.
        Zombie persistentUnit = EntityType.ZOMBIE.create(level);
        check(persistentUnit != null, "persistent fixture created");
        persistentUnit.setPersistenceRequired();
        check(persistentUnit.isPersistenceRequired(), "the fixture unit starts persistence-required");
        persistentUnit.setPos(owner.getX() - 2.5D, owner.getY(), owner.getZ() - 0.5D);
        level.addFreshEntity(persistentUnit);
        int persistentQueue = first.turnQueueSize();
        int persistentEnemies = first.enemyCount();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        check(!first.tryAbsorb(persistentUnit), "the injected fault refuses the second absorb too");
        check(ServerBattle.pendingFaults() == 0, "the second injected fault was consumed as well");
        check(persistentUnit.isPersistenceRequired(),
                "a failed admission restores persistence exactly: true stays true");
        check(BattleManager.findFor(persistentUnit.getUUID()) == null,
                "the second failed admission releases its ownership claim");
        check(first.turnQueueSize() == persistentQueue && first.enemyCount() == persistentEnemies,
                "the second failed admission leaves queue and roster unchanged");
        check(BattleManager.findFor(distant.getUUID()) == second && !second.finished(),
                "the second failed admission leaves the other encounter alone");
        persistentUnit.discard();

        enter("two-encounter-absorb-fault-rollback");
        // A failure AFTER a successful admission must give back the zone growth, the reach
        // extension, the queue entry and the unit itself.
        Zombie lateFault = EntityType.ZOMBIE.create(level);
        check(lateFault != null, "late-fault candidate created");
        lateFault.setPos(owner.getX() + 30.5D, owner.getY(), owner.getZ() - 0.5D);
        level.addFreshEntity(lateFault);
        var lateZone = first.zone().bounds();
        double lateReach = owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        int lateQueue = first.turnQueueSize();
        int lateEnemies = first.enemyCount();
        boolean lateNoAi = lateFault.isNoAi();
        boolean latePersistent = lateFault.isPersistenceRequired();
        Vec3 latePosition = lateFault.position();
        int secondEnemiesDuringFault = second.enemyCount();
        int lateZoneRadius = first.zone().radius();
        // sendState publishes three ints per combatant (id, health, max health).
        int lateParticipants = (first.memberCount() + first.enemyCount()) * 3;
        com.tactical.neoforge.network.ServerPackets.startCapture();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ABSORB_AFTER_ADMIT);
        check(!first.tryAbsorb(lateFault), "a fault after admission refuses the absorb");
        check(ServerBattle.pendingFaults() == 0, "the post-admission fault was really consumed");
        // The clients already received the grown battlefield; the rollback must correct them.
        var lateCorrective = lastStateFor(owner.getUUID());
        check(lateCorrective != null && lateCorrective.active()
                        && lateCorrective.zoneRadius() == lateZoneRadius
                        && lateCorrective.participants().length == lateParticipants,
                "the client is corrected back to the pre-absorb zone and roster: last="
                        + (lateCorrective == null ? "none"
                        : "active=" + lateCorrective.active() + " radius=" + lateCorrective.zoneRadius()
                                + " participants=" + lateCorrective.participants().length)
                        + " expected radius=" + lateZoneRadius + " participants=" + lateParticipants);
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(first.zone().bounds().equals(lateZone), "the grown battlefield is given back");
        check(owner.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == lateReach,
                "the extended reach is given back");
        check(first.turnQueueSize() == lateQueue, "the queue entry is given back");
        check(first.enemyCount() == lateEnemies, "the roster entry is given back");
        check(!first.isParticipant(lateFault), "the rolled-back unit is not a participant");
        check(BattleManager.findFor(lateFault.getUUID()) == null,
                "the rolled-back unit's ownership claim is released");
        check(lateFault.isNoAi() == lateNoAi && lateFault.isPersistenceRequired() == latePersistent
                        && lateFault.position().equals(latePosition),
                "the rolled-back unit keeps its own AI, persistence and position");
        check(second.enemyCount() == secondEnemiesDuringFault
                        && BattleManager.findFor(distant.getUUID()) == second,
                "the other encounter is untouched by the post-admission rollback");
        lateFault.discard();

        enter("two-encounter-join-fault-rollback");
        // A failure after the member was added and moved must give back the member, the queue entry,
        // the reach, the ownership claim, the consumed invitation and the player's position, while
        // keeping the container items conserved.
        check(BattleManager.invite(owner, guest) == BattleEligibility.Denial.NONE, "the join fixture is invited");
        guest.getInventory().clearContent();
        int guestStoneBefore = countInInventory(guest, Items.STONE);
        guest.inventoryMenu.setCarried(new ItemStack(Items.STONE, 16));
        int joinQueue = first.turnQueueSize();
        int joinMembers = first.memberCount();
        Vec3 guestHome = guest.position();
        double guestReach = guest.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        int secondMembersDuringJoin = second.memberCount();
        ServerBattle.injectFault(ServerBattle.FaultPoint.JOIN_AFTER_MEMBER);
        BattleEligibility.Denial joinDenial = BattleManager.acceptHeadless(guest);
        check(joinDenial == BattleEligibility.Denial.UNRESOLVED,
                "a fault after the member was added is reported as unresolved: " + joinDenial);
        check(ServerBattle.pendingFaults() == 0, "the join fault was really consumed");
        check(!first.isMember(guest), "the rolled-back member is not in the roster");
        check(first.memberCount() == joinMembers, "the roster count is given back");
        check(first.turnQueueSize() == joinQueue, "the queue entry is given back");
        check(BattleManager.findFor(guest.getUUID()) == null, "the new ownership claim is released");
        check(guest.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == guestReach,
                "the extended reach is given back");
        check(guest.position().equals(guestHome), "the joining player is put back where it started");
        check(countInInventory(guest, Items.STONE) + countDroppedAround(level, guest, Items.STONE)
                        == guestStoneBefore + 16,
                "container settlement conserves the cursor stack through a failed join: inventory="
                        + countInInventory(guest, Items.STONE) + " dropped="
                        + countDroppedAround(level, guest, Items.STONE));
        check(first.session().hasInvitation(guest.getUUID()),
                "the consumed invitation is restored so the player can retry");
        check(second.memberCount() == secondMembersDuringJoin
                        && BattleManager.findFor(distant.getUUID()) == second,
                "the other encounter is untouched by the failed join");
        // The restored invitation really is usable.
        check(BattleManager.acceptHeadless(guest) == BattleEligibility.Denial.NONE,
                "the retry after the rollback joins normally");
        check(BattleManager.leave(guest, "selftest join fixture done"), "the retried member leaves again");
        check(first.memberCount() == joinMembers, "the encounter is back to its original roster");
        guest.getInventory().clearContent();

        enter("two-encounter-join-publish-fault");
        // The hard case: the enlarged roster was already published to every recipient before the
        // failure. The new member must be cleaned up and the remaining members must see the
        // rolled-back roster, while the joining client is told it is not in a battle.
        check(BattleManager.invite(owner, guest) == BattleEligibility.Denial.NONE,
                "the publish-fault fixture is invited");
        int publishQueue = first.turnQueueSize();
        int publishMembers = first.memberCount();
        int publishParticipants = (first.memberCount() + first.enemyCount()) * 3;
        com.tactical.neoforge.network.ServerPackets.startCapture();
        ServerBattle.injectFault(ServerBattle.FaultPoint.JOIN_AFTER_PUBLISH);
        BattleEligibility.Denial publishDenial = BattleManager.acceptHeadless(guest);
        check(publishDenial == BattleEligibility.Denial.UNRESOLVED,
                "a fault after publication is reported as unresolved: " + publishDenial);
        check(ServerBattle.pendingFaults() == 0, "the publish fault was really consumed");
        check(!first.isMember(guest), "the published member is removed again");
        check(first.memberCount() == publishMembers && first.turnQueueSize() == publishQueue,
                "roster and queue are back to their pre-join state");
        check(BattleManager.findFor(guest.getUUID()) == null, "the published claim is released");
        var guestPublished = lastStateFor(guest.getUUID());
        check(guestPublished != null && !guestPublished.active(),
                "the joining client is told it is not in a battle after the failed publish");
        var ownerPublished = lastStateFor(owner.getUUID());
        check(ownerPublished != null && ownerPublished.active()
                        && ownerPublished.participants().length == publishParticipants,
                "the remaining member sees the rolled-back roster");
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(first.session().hasInvitation(guest.getUUID()),
                "the invitation survives the published-then-rolled-back join");
        check(BattleManager.decline(guest) == BattleEligibility.Denial.NONE,
                "the restored invitation can be declined to clean the fixture up");

        enter("two-encounter-start-fault-rollback");
        // A failure at the very end of deployment must give back the founder, the admitted units,
        // the queue, the reach and the registry entry, and leave both running encounters alone.
        BlockPos lonely = new BlockPos(origin.x() - 72, origin.y(), origin.z());
        buildPlatform(level, lonely, 6);
        ServerPlayer fresh = player(level, lonely, 0, "MultiFresh");
        Zombie freshEnemy = EntityType.ZOMBIE.create(level);
        check(freshEnemy != null, "start-fault enemy created");
        freshEnemy.setPos(fresh.getX() + 2.5D, fresh.getY(), fresh.getZ() + 0.5D);
        level.addFreshEntity(freshEnemy);
        Vec3 freshHome = fresh.position();
        boolean freshEnemyNoAi = freshEnemy.isNoAi();
        boolean freshEnemyPersistent = freshEnemy.isPersistenceRequired();
        double freshReach = fresh.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
        int firstEpochDuringStartFault = (int) first.turnEpoch();
        int secondEpochDuringStartFault = (int) second.turnEpoch();
        int activeBeforeStartFault = BattleManager.active().size();
        ServerBattle.injectFault(ServerBattle.FaultPoint.START_AFTER_ADMISSION);
        com.tactical.neoforge.network.ServerPackets.startCapture();
        check(!BattleManager.start(fresh, true),
                "a fault at the end of deployment refuses the encounter");
        check(ServerBattle.pendingFaults() == 0, "the start fault was really consumed");
        // Published state must be corrected, not merely dropped server-side: the journal that already
        // started is cleared for the same session, and the last state this client was told is that it
        // is not in a battle. (These are server-side send attempts; a real client is not involved.)
        var freshPackets = com.tactical.neoforge.network.ServerPackets.capturedFor(fresh.getUUID());
        com.tactical.neoforge.network.BattleLogPacket logged = null;
        com.tactical.neoforge.network.BattleLogPacket lastLog = null;
        for (var packet : freshPackets) {
            if (!(packet instanceof com.tactical.neoforge.network.BattleLogPacket log)) continue;
            lastLog = log;
            if (logged == null
                    && (log.lifecycle() == com.tactical.neoforge.network.BattleLogPacket.START
                    || log.lifecycle() == com.tactical.neoforge.network.BattleLogPacket.UPDATE)) {
                logged = log;
            }
        }
        check(logged != null, "the failed start had already attempted a journal START/UPDATE");
        check(lastLog != null && lastLog.lifecycle() == com.tactical.neoforge.network.BattleLogPacket.CLEAR,
                "the last journal attempt after a failed start is a CLEAR");
        check(lastLog.battleId().equals(logged.battleId()),
                "the CLEAR belongs to the journal session that was already published");
        var freshLast = lastStateFor(fresh.getUUID());
        check(freshLast != null && !freshLast.active(),
                "the last published state after a failed start is an inactive snapshot");
        check(BattleManager.findFor(fresh.getUUID()) == null, "the failed founder keeps no route");
        check(BattleManager.findFor(freshEnemy.getUUID()) == null,
                "the admitted unit's ownership claim is released");
        check(!freshEnemy.isNoAi() && freshEnemy.isPersistenceRequired() == freshEnemyPersistent
                        && !freshEnemy.isRemoved(),
                "the admitted unit gets its own AI and persistence back");
        check(fresh.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) == freshReach,
                "the failed founder's reach is given back");
        check(fresh.position().equals(freshHome), "the failed founder is put back at its position");
        check(BattleManager.active().size() == activeBeforeStartFault,
                "a failed start registers no encounter");
        check(first.turnEpoch() == firstEpochDuringStartFault
                        && second.turnEpoch() == secondEpochDuringStartFault,
                "the running encounters are untouched by the failed start");
        check(!first.finished() && !second.finished(), "both running encounters stay alive");
        freshEnemy.discard();
        com.tactical.neoforge.network.ServerPackets.stopCapture();

        enter("two-encounter-npc-release");
        Zombie ownNpc = EntityType.ZOMBIE.create(level);
        check(ownNpc != null, "own reinforcement created");
        ownNpc.setPos(owner.getX() + 30.5D, owner.getY(), owner.getZ() + 0.5D);
        level.addFreshEntity(ownNpc);
        check(first.tryAbsorb(ownNpc),
                "an unowned hostile at the same spot is absorbed, so that deployment path is reachable");
        check(BattleManager.findFor(ownNpc.getUUID()) == first, "the absorbed unit belongs to this encounter");
        check(first.isParticipant(ownNpc), "the absorbed unit is a participant");
        check(!first.zone().bounds().equals(faultZone), "a successful absorb is what grows the battlefield");
        int releaseEnemies = first.enemyCount();
        List<UUID> secondActorsBeforeRelease = BattleManager.sessions().actorsOf(second.sessionId());
        ownNpc.kill();
        first.tick();
        check(!first.isParticipant(ownNpc), "a dead unit leaves the roster on the next tick");
        check(BattleManager.findFor(ownNpc.getUUID()) == null,
                "a dead unit's ownership claim is released with its own session");
        check(first.enemyCount() == releaseEnemies - 1, "the dead unit is the only roster loss");
        check(BattleManager.findFor(distant.getUUID()) == second
                        && BattleManager.findFor(secondEnemy.getUUID()) == second,
                "the other encounter keeps both of its ownership claims");
        check(BattleManager.sessions().actorsOf(second.sessionId()).equals(secondActorsBeforeRelease),
                "the other encounter's ownership set is unchanged by this release");

        enter("two-encounter-same-zone-conflict");
        Zombie candidate = EntityType.ZOMBIE.create(level);
        check(candidate != null, "conflict candidate created");
        // Inside the running first encounter's zone: a new battlefield there must be refused.
        candidate.setPos(owner.getX() - 1.5D, owner.getY(), owner.getZ() + 1.5D);
        level.addFreshEntity(candidate);
        outsider.setPos(owner.getX() + 0.5D, owner.getY(), owner.getZ() - 2.5D);
        int firstMembers = first.memberCount();
        int firstRevision = first.stateRevision();
        long firstEpoch = first.turnEpoch();
        UUID firstActor = first.currentActorId();
        check(!BattleManager.start(outsider, true),
                "a new battlefield inside an existing zone is refused");
        check(BattleManager.findFor(outsider.getUUID()) == null, "the refused founder keeps no route");
        check(BattleManager.active().size() == 2, "a refused start creates no third encounter");
        check(first.memberCount() == firstMembers && first.stateRevision() == firstRevision
                        && first.turnEpoch() == firstEpoch
                        && java.util.Objects.equals(first.currentActorId(), firstActor),
                "the refused start changes nothing in the existing encounter");
        check(!candidate.isNoAi() && !candidate.isRemoved(),
                "the refused start never touched the candidate enemy");
        candidate.discard();

        enter("two-encounter-effect-clock");
        // A real own-turn effect clock on the other encounter's member: exactly one pulse per
        // complete cycle. The pulse count is the differential observation; a global clear would
        // silently re-baseline this member and swallow the next pulse.
        CountingClockEffect clock = new CountingClockEffect();
        Holder<MobEffect> clockHolder = Holder.direct(clock);
        distant.removeAllEffects();
        distant.addEffect(new MobEffectInstance(clockHolder, 5000, 0, false, false));
        BattleEffects.onTurnStart(distant);
        int pulsesAtBaseline = clock.pulses;
        BattleEffects.onTurnStart(distant);
        int pulsesWhileBothRun = clock.pulses;
        check(pulsesWhileBothRun > pulsesAtBaseline,
                "the second encounter's own effect clock advances while both encounters run");

        enter("two-encounter-exit-isolation");
        int secondMembers = second.memberCount();
        int secondQueue = second.turnQueueSize();
        long secondEpoch = second.turnEpoch();
        int secondRevision = second.stateRevision();
        int secondAp = second.currentAp(distant);
        List<UUID> secondActors = BattleManager.sessions().actorsOf(second.sessionId());
        check(BattleManager.leave(owner, "selftest two-encounter leave"), "the first founder leaves");
        check(first.finished(), "the emptied encounter finishes");
        BattleEffects.onTurnStart(distant);
        check(clock.pulses > pulsesWhileBothRun,
                "finishing the first encounter does not clear the second encounter's effect clock");
        check(distant.hasEffect(clockHolder), "the second member keeps its own effect instances");
        check(!second.finished(), "finishing the first encounter never finishes the second");
        check(BattleManager.findFor(distant.getUUID()) == second, "the second founder keeps its route");
        check(BattleManager.findFor(secondEnemy.getUUID()) == second, "the second encounter keeps its unit");
        check(second.memberCount() == secondMembers && second.turnQueueSize() == secondQueue
                        && second.turnEpoch() == secondEpoch && second.stateRevision() == secondRevision
                        && second.currentAp(distant) == secondAp,
                "finishing the first encounter changes nothing authoritative in the second");
        check(BattleManager.sessions().actorsOf(second.sessionId()).equals(secondActors),
                "the second encounter keeps every ownership claim");
        check(BattleManager.findFor(owner.getUUID()) == null, "the finished encounter's player is unrouted");
        check(BattleManager.findFor(firstEnemy.getUUID()) == null, "the finished encounter's unit is unrouted");
        check(BattleManager.active().size() == 1 && BattleManager.current() == second,
                "one encounter remains and is the compatibility view again");
        Tactical.LOGGER.info("[Tactical] two encounters isolated: first={} second={} secondMembers={} queue={}",
                first.sessionId(), second.sessionId(), secondMembers, secondQueue);

        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        Tactical.LOGGER.info("[Tactical] two-encounter isolation stage complete");
    }

    // ------------------------------------------------------------------ nearby discovery / direct join

    /** Last nearby-battles summary the server attempted to send to this recipient. */
    private static com.tactical.neoforge.network.NearbyBattlesPacket lastNearbyFor(UUID playerId) {
        var packets = com.tactical.neoforge.network.ServerPackets.capturedFor(playerId);
        for (int index = packets.size() - 1; index >= 0; index--) {
            if (packets.get(index) instanceof com.tactical.neoforge.network.NearbyBattlesPacket packet) {
                return packet;
            }
        }
        return null;
    }

    /**
     * Nearby summary and direct opt-in joining on the real server.
     *
     * <p>Proves the summary only contains the caller's own dimension and reach, that a repeat request
     * inside the interval is dropped, that an unknown or foreign session is refused without routing
     * the player, that a confirmed row really joins with zero charge and no stolen turn, and that the
     * leaving member's clear names its own session. Real client buttons are MS05.</p>
     */
    private static void nearbyDiscoveryAndJoin(MinecraftServer server) {
        ServerLevel level = server.overworld();
        enter("nearby-discovery-setup");
        BattleManager.reset();
        LevelGridWorld world = new LevelGridWorld(level);
        GridPos origin = world.surfaceCell(owner.getBlockX(), owner.getBlockZ(), owner.getBlockY());
        check(origin != null, "discovery stage origin is standable");
        for (Mob leftover : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(
                origin.x() - 96, origin.y() - 24, origin.z() - 96,
                origin.x() + 97, origin.y() + 25, origin.z() + 97))) {
            if (leftover instanceof net.minecraft.world.entity.monster.Enemy) leftover.discard();
        }
        BlockPos far = new BlockPos(origin.x() + 72, origin.y(), origin.z());
        buildPlatform(level, far, 8);
        ServerPlayer host = player(level, far, 0, "MultiHost");
        Zombie nearOwner = EntityType.ZOMBIE.create(level);
        Zombie nearHost = EntityType.ZOMBIE.create(level);
        check(nearOwner != null && nearHost != null, "discovery enemies created");
        nearOwner.setPos(owner.getX() + 2.5D, owner.getY(), owner.getZ() + 2.5D);
        level.addFreshEntity(nearOwner);
        nearHost.setPos(host.getX() + 2.5D, host.getY(), host.getZ() + 2.5D);
        level.addFreshEntity(nearHost);

        check(BattleManager.start(owner, true), "the nearby encounter starts");
        ServerBattle nearby = BattleManager.findFor(owner.getUUID());
        check(nearby != null, "the nearby encounter is registered");
        check(BattleManager.start(host, true), "the distant encounter starts too");
        ServerBattle distantBattle = BattleManager.findFor(host.getUUID());
        check(distantBattle != null && distantBattle != nearby, "the two encounters are distinct");

        // The observer is not in any battle and stands inside the first encounter's zone.
        fourth.setPos(owner.getX() - 1.5D, owner.getY(), owner.getZ() - 1.5D);
        check(BattleManager.findFor(fourth.getUUID()) == null, "the observer joins nothing yet");

        enter("nearby-discovery-list");
        com.tactical.neoforge.network.ServerPackets.startCapture();
        check(BattleDiscovery.request(fourth, 11L), "the first discovery request is served");
        var summary = lastNearbyFor(fourth.getUUID());
        check(summary != null, "a nearby summary was attempted for the observer");
        check(summary.requestId() == 11L, "the summary echoes the request token");
        check(summary.entries().size() == 1,
                "only the encounter inside reach is listed, got " + summary.entries().size());
        var row = summary.entries().get(0);
        check(row.sessionId().equals(nearby.sessionId()), "the listed row is the nearby encounter");
        check("MultiOwner".equals(row.manager()), "the row names the real manager: " + row.manager());
        check(row.members() == nearby.memberCount() && row.capacity() == nearby.playerCapacity(),
                "the row reports the live roster and capacity");
        check(row.joinable(), "an eligible observer sees an joinable row");
        check(summary.entries().stream().noneMatch(entry -> entry.sessionId().equals(distantBattle.sessionId())),
                "an encounter beyond the discovery distance is not listed");
        com.tactical.neoforge.network.ServerPackets.stopCapture();

        enter("nearby-discovery-throttle");
        check(!BattleDiscovery.request(fourth, 12L),
                "a second request inside the refresh interval is dropped, not answered");
        check(BattleDiscovery.REFRESH_INTERVAL_TICKS == 20, "the documented rate limit is 20 server ticks");

        enter("nearby-discovery-direct-join");
        UUID actorBeforeJoin = nearby.currentActorId();
        int membersBeforeJoin = nearby.memberCount();
        long epochBeforeJoin = nearby.turnEpoch();
        UUID unknownSession = UUID.randomUUID();
        com.tactical.neoforge.network.ServerPackets.startCapture();
        var unknown = BattleDiscovery.join(fourth, unknownSession);
        var unknownSequence = com.tactical.neoforge.network.ServerPackets.capturedFor(fourth.getUUID());
        var missingSession = BattleDiscovery.join(fourth, null);
        var missingSequence = com.tactical.neoforge.network.ServerPackets.capturedFor(fourth.getUUID());
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(unknown.denial() == BattleEligibility.Denial.SESSION_CLOSED,
                "an unknown session is refused as closed: " + unknown.denial());
        check(unknown.sessionId().equals(unknownSession), "the refusal names the requested session");
        check(BattleManager.findFor(fourth.getUUID()) == null, "a refused join never routes the player");
        // Every early return must still answer the requesting page; otherwise it waits in "joining".
        check(unknownSequence.size() == 1,
                "an early refusal answers the page exactly once, saw " + unknownSequence.size());
        boolean carriedRefusal = false;
        if (unknownSequence.size() == 1
                && unknownSequence.get(0) instanceof com.tactical.neoforge.network.JoinBattleResultPacket
                        refused) {
            carriedRefusal = !refused.joined() && refused.sessionId().equals(unknownSession);
        }
        check(carriedRefusal, "that single answer is the refusal for the requested encounter");
        check(missingSession.denial() == BattleEligibility.Denial.UNRESOLVED,
                "a join without a session id is refused: " + missingSession.denial());
        check(missingSession.sessionId().equals(new UUID(0L, 0L)),
                "an unusable request is answered with an explicit placeholder, not routed anywhere");
        check(missingSequence.size() == unknownSequence.size() + 1,
                "the unusable request is answered as well, saw " + missingSequence.size());

        com.tactical.neoforge.network.ServerPackets.startCapture();
        long generationBeforeJoin = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(fourth);
        var joined = BattleDiscovery.join(fourth, nearby.sessionId());
        check(joined.joined(), "a confirmed row joins directly: " + joined.denial());
        long generationAfterJoin = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(fourth);
        check(generationAfterJoin > generationBeforeJoin,
                "the committed join publishes a new membership generation");
        // The published order the client relies on: the ACTIVE transition (carried by the full
        // snapshot), then the complete journal, then the complete warning snapshot, then the UI answer.
        var joinSequence = com.tactical.neoforge.network.ServerPackets.capturedFor(fourth.getUUID());
        int transitionIndex = -1;
        int historyIndex = -1;
        int warningIndex = -1;
        int resultIndex = -1;
        for (int index = 0; index < joinSequence.size(); index++) {
            var packet = joinSequence.get(index);
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot
                    && snapshot.active() && nearby.sessionId().equals(snapshot.sessionId())
                    && snapshot.membershipGeneration() == generationAfterJoin && transitionIndex < 0) {
                transitionIndex = index;
            } else if (packet instanceof com.tactical.neoforge.network.BattleLogPacket log
                    && nearby.sessionId().equals(log.battleId()) && historyIndex < 0) {
                historyIndex = index;
            } else if (packet instanceof com.tactical.neoforge.network.CreeperTelegraphPacket telegraph
                    && nearby.sessionId().equals(telegraph.battleId()) && warningIndex < 0) {
                warningIndex = index;
            } else if (packet instanceof com.tactical.neoforge.network.JoinBattleResultPacket result
                    && result.sessionId().equals(nearby.sessionId()) && result.joined()) {
                resultIndex = index;
            }
        }
        check(transitionIndex >= 0, "the joiner is told its membership at the committed generation");
        check(historyIndex > transitionIndex, "the complete journal follows the transition");
        check(warningIndex > historyIndex, "the complete warning snapshot follows the journal");
        check(resultIndex > warningIndex, "the UI answer is the last thing that member receives");
        check(BattleManager.findFor(fourth.getUUID()) == nearby, "the joiner is routed to the chosen encounter");
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(nearby.memberCount() == membersBeforeJoin + 1, "exactly one member was added");
        check(java.util.Objects.equals(nearby.currentActorId(), actorBeforeJoin),
                "the new member never steals the current turn");
        check(nearby.turnEpoch() == epochBeforeJoin, "joining does not advance the turn");
        check(nearby.currentAp(fourth) == TacticalAttributes.maxAp(fourth) || nearby.currentAp(fourth) > 0,
                "the new member starts with its own initial AP, not a charged turn");

        // A refusal answers the page and changes nothing else: no transition, no generation bump.
        var duplicate = BattleDiscovery.join(fourth, nearby.sessionId());
        check(duplicate.denial() == BattleEligibility.Denial.ALREADY_MEMBER,
                "a duplicate join is refused instead of adding twice: " + duplicate.denial());
        check(nearby.memberCount() == membersBeforeJoin + 1, "the duplicate added nothing");
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(fourth)
                        == generationAfterJoin,
                "a refused join does not touch the member's membership generation");

        int distantMembers = distantBattle.memberCount();
        long hostGenerationBefore = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(host);
        var crossBattle = BattleDiscovery.join(host, nearby.sessionId());
        check(crossBattle.denial() == BattleEligibility.Denial.ALREADY_MEMBER,
                "a player already fighting elsewhere cannot be moved into this encounter");
        check(distantBattle.memberCount() == distantMembers
                        && BattleManager.findFor(host.getUUID()) == distantBattle,
                "the other encounter is untouched by the refusal");
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(host)
                        == hostGenerationBefore,
                "and its own membership generation is untouched as well");



        enter("nearby-discovery-invitation-ambiguity");
        // Two live encounters invite the same player. The parameter-less accept must refuse instead of
        // joining whichever the registry lists first; the session-carrying form joins exactly that one.
        third.setPos(owner.getX() + 1.5D, owner.getY(), owner.getZ() + 1.5D);
        check(BattleManager.invite(owner, third) == BattleEligibility.Denial.NONE,
                "the nearby encounter invites the third player");
        third.setPos(host.getX() + 1.5D, host.getY(), host.getZ() - 1.5D);
        check(BattleManager.invite(host, third) == BattleEligibility.Denial.NONE,
                "the distant encounter invites the same player");
        long ambiguousGeneration = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(third);
        check(BattleManager.accept(third) == BattleEligibility.Denial.UNRESOLVED,
                "an ambiguous accept refuses instead of picking one invitation");
        check(BattleManager.findFor(third.getUUID()) == null, "the ambiguous player joined nothing");
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(third)
                        == ambiguousGeneration,
                "and a refusal never publishes a membership transition");
        com.tactical.neoforge.network.ServerPackets.startCapture();
        check(BattleManager.accept(third, distantBattle.sessionId()) == BattleEligibility.Denial.NONE,
                "the session-carrying accept joins exactly the named encounter");
        var invitationSequence = com.tactical.neoforge.network.ServerPackets
                .capturedFor(third.getUUID());
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(BattleManager.findFor(third.getUUID()) == distantBattle,
                "and routes the player to that encounter");
        long invitedGeneration = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(third);
        check(invitedGeneration > ambiguousGeneration,
                "the accepted invitation publishes a new membership generation");
        int invitationTransition = -1;
        int invitationResult = -1;
        for (int index = 0; index < invitationSequence.size(); index++) {
            var packet = invitationSequence.get(index);
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot
                    && snapshot.active() && distantBattle.sessionId().equals(snapshot.sessionId())
                    && snapshot.membershipGeneration() == invitedGeneration
                    && invitationTransition < 0) {
                invitationTransition = index;
            } else if (packet instanceof com.tactical.neoforge.network.JoinBattleResultPacket result
                    && result.sessionId().equals(distantBattle.sessionId()) && result.joined()) {
                invitationResult = index;
            }
        }
        check(invitationTransition >= 0, "the invited member receives its ACTIVE transition first");
        check(invitationResult > invitationTransition, "the UI answer follows that transition");
        check(BattleManager.leave(third, "selftest invitation fixture done"), "the invited member leaves again");

        enter("nearby-discovery-invitation-no-argument");
        // Exactly one invitation: the parameter-less accept must take that one and publish the same way.
        long rejoinGeneration = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(third);
        third.setPos(owner.getX() + 1.5D, owner.getY(), owner.getZ() + 1.5D);
        check(BattleManager.invite(owner, third) == BattleEligibility.Denial.NONE,
                "the only remaining invitation is offered again");
        check(BattleManager.accept(third) == BattleEligibility.Denial.NONE,
                "the unambiguous parameter-less accept joins");
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(third)
                        > rejoinGeneration,
                "and it publishes a generation above the one it left with");
        check(BattleManager.findFor(third.getUUID()) == nearby,
                "the re-entry routes to the encounter that invited it");
        check(BattleManager.leave(third, "selftest no-arg invitation done"), "and leaves again");

        enter("nearby-discovery-session-scoped-clear");
        com.tactical.neoforge.network.ServerPackets.startCapture();
        check(BattleManager.leave(fourth, "selftest direct-join leave"), "the joined member leaves again");
        var leavingState = lastStateFor(fourth.getUUID());
        check(leavingState != null && !leavingState.active(), "the leaving member is told the encounter ended");
        check(nearby.sessionId().equals(leavingState.sessionId()),
                "the clear names its own session, so a newer encounter is never erased");
        // The journal must speak with the same canonical id, otherwise the client's session gate would
        // treat every legitimate log packet as another encounter's traffic.
        check(nearby.journal().sessionId().equals(nearby.sessionId()),
                "the journal uses the encounter's canonical session id");
        var journalPackets = new ArrayList<com.tactical.neoforge.network.BattleLogPacket>();
        for (var recipient : java.util.List.of(owner.getUUID(), fourth.getUUID())) {
            for (var packet : com.tactical.neoforge.network.ServerPackets.capturedFor(recipient)) {
                if (packet instanceof com.tactical.neoforge.network.BattleLogPacket log) journalPackets.add(log);
            }
        }
        check(!journalPackets.isEmpty(), "a member was sent journal packets to check");
        for (var log : journalPackets) {
            check(nearby.sessionId().equals(log.battleId()),
                    "every journal packet names the canonical session id, saw " + log.battleId());
        }
        var telegraph = nearby.creeperSnapshot();
        check(telegraph == null || nearby.sessionId().equals(telegraph.battleId()),
                "AI warnings use the same canonical session id");
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(nearby.memberCount() == membersBeforeJoin, "the encounter is back to its previous roster");

        enter("nearby-discovery-bootstrap-fault");
        // A publication failure after the committed entry must not withdraw the membership while the
        // roster stays: the join is reported, the member stays, and the repair is an authoritative
        // resync of the same generation rather than a compensating exit.
        // The member must be outside the encounter before the faulted entry is attempted.
        if (BattleManager.findFor(fourth.getUUID()) != null) {
            check(BattleManager.leave(fourth, "selftest bootstrap-fault setup"), "the member leaves first");
        }
        check(BattleManager.findFor(fourth.getUUID()) == null, "the faulted entry starts from outside");
        long generationBeforeBootstrap = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(fourth);
        com.tactical.neoforge.network.ServerPackets.startCapture();
        ServerBattle.injectFault(ServerBattle.FaultPoint.BOOTSTRAP);
        var faultedJoin = BattleDiscovery.join(fourth, nearby.sessionId());
        var faultSequence = com.tactical.neoforge.network.ServerPackets.capturedFor(fourth.getUUID());
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        check(faultedJoin.joined(), "the committed join still answers success: " + faultedJoin.denial());
        check(ServerBattle.pendingFaults() == 0, "the injected bootstrap fault was consumed");
        check(BattleManager.findFor(fourth.getUUID()) == nearby, "the roster and the index keep the member");
        check(nearby.isMember(fourth), "and the encounter still owns it");
        var faultedMembership = com.tactical.neoforge.battle.multi.MembershipGenerations.current(fourth);
        check(faultedMembership.active() && nearby.sessionId().equals(faultedMembership.sessionId()),
                "the membership stays ACTIVE on that encounter");
        check(faultedMembership.generation() > generationBeforeBootstrap,
                "at the generation the transition published");
        int resyncStates = 0;
        boolean compensatingExit = false;
        for (var packet : faultSequence) {
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot
                    && nearby.sessionId().equals(snapshot.sessionId())) {
                if (snapshot.active()
                        && snapshot.membershipGeneration() == faultedMembership.generation()) resyncStates++;
                if (!snapshot.active()) compensatingExit = true;
            }
        }
        check(resyncStates >= 2, "the repair republishes the snapshot instead of leaving, states=" + resyncStates);
        check(!compensatingExit, "and no exit was published for the committed membership");
        check(BattleManager.leave(fourth, "selftest bootstrap-fault done"), "the member leaves afterwards");
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.generationOf(fourth)
                        > faultedMembership.generation(),
                "and that exit is a higher generation than the entry it ends");

        enter("membership-generation-across-reset");
        // A battle reset is not the end of this server process: the counters must keep increasing, or
        // the client that applied its exit would refuse the next entry of the same connection.
        long generationBeforeReset = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(owner);
        BattleManager.reset();
        check(BattleManager.active().isEmpty(), "the reset ends every encounter");
        check(BattleManager.start(owner, true), "the same connection starts a new encounter after the reset");
        ServerBattle afterReset = BattleManager.findFor(owner.getUUID());
        check(afterReset != null, "the founder is routed again");
        long generationAfterReset = com.tactical.neoforge.battle.multi.MembershipGenerations
                .generationOf(owner);
        check(generationAfterReset > generationBeforeReset,
                "the reset keeps the per-player counter increasing: " + generationBeforeReset
                        + " -> " + generationAfterReset);
        check(com.tactical.neoforge.battle.multi.MembershipGenerations.epoch() > 0,
                "the server epoch is unchanged only while the process lives");
        check(BattleManager.leave(owner, "selftest reset generation done"), "and the founder leaves again");

        BattleManager.reset();
        Tactical.LOGGER.info("[Tactical] nearby discovery stage complete");
    }

    // ------------------------------------------------------------------ container settlement stage

    private static int countInInventory(ServerPlayer player, Item item) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int countDroppedAround(ServerLevel level, ServerPlayer player, Item item) {
        int total = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(6.0D))) {
            if (entity.getItem().is(item)) total += entity.getItem().getCount();
        }
        return total;
    }

    private static void hostileFor(ServerLevel level) {
        Zombie enemy = EntityType.ZOMBIE.create(level);
        check(enemy != null, "container fixture enemy created");
        enemy.setPos(owner.getX() + 2.5D, owner.getY(), owner.getZ() + 2.5D);
        level.addFreshEntity(enemy);
    }

    private static void freshEncounter(ServerLevel level, String what) {
        BattleManager.reset();
        hostileFor(level);
        check(BattleManager.start(owner, true), what + " encounter starts");
        check(BattleManager.current() != null, what + " battle registered");
    }

    private static void joinGuest(String what) {
        check(BattleManager.invite(owner, guest) == BattleEligibility.Denial.NONE, what + " invite is offered");
        check(BattleManager.acceptHeadless(guest) == BattleEligibility.Denial.NONE, what + " guest joins");
    }

    /**
     * Container settlement on admission, headless but with the same vanilla menu objects a real
     * connection owns: own inventory (menu id 0), an external menu, an empty cursor, a full
     * inventory and the starter path. Nothing here sockets a client; real-client screenshots stay
     * with the QA matrix.
     */
    private static void containerSettlement(MinecraftServer server) {
        ServerLevel level = server.overworld();

        enter("container-starter-own-inventory");
        BattleManager.reset();
        owner.getInventory().clearContent();
        owner.inventoryMenu.setCarried(new ItemStack(Items.STONE, 48));
        check(!owner.inventoryMenu.getCarried().isEmpty(), "the starter fixture holds a cursor stack");
        hostileFor(level);
        check(BattleManager.start(owner, true), "the starter is admitted with a cursor stack held");
        check(owner.inventoryMenu.getCarried().isEmpty(), "starter admission clears its own cursor stack");
        check(countInInventory(owner, Items.STONE) == 48, "starter admission returns the stack intact");
        BattleManager.reset();

        enter("container-join-own-inventory");
        freshEncounter(level, "own inventory");
        guest.getInventory().clearContent();
        guest.inventoryMenu.setCarried(new ItemStack(Items.STONE, 48));
        joinGuest("own inventory");
        check(guest.containerMenu == guest.inventoryMenu, "the own inventory stays the active menu");
        check(guest.inventoryMenu.getCarried().isEmpty(), "joining clears a cursor stack held over the own inventory");
        check(countInInventory(guest, Items.STONE) == 48, "the own-inventory cursor stack returns to the inventory");

        enter("container-join-external-menu");
        freshEncounter(level, "external menu");
        guest.getInventory().clearContent();
        AbstractContainerMenu external = new ChestMenu(MenuType.GENERIC_9x1, 42,
                guest.getInventory(), new SimpleContainer(9), 1);
        guest.containerMenu = external;
        external.setCarried(new ItemStack(Items.STONE, 48));
        joinGuest("external menu");
        check(guest.containerMenu == guest.inventoryMenu, "joining closes the external menu");
        check(external.getCarried().isEmpty(), "the retired external menu no longer owns the cursor stack");
        check(guest.inventoryMenu.getCarried().isEmpty(), "no cursor stack survives on the inventory menu");
        check(countInInventory(guest, Items.STONE) == 48, "the external cursor stack returns to the inventory");

        enter("container-join-empty-cursor");
        freshEncounter(level, "empty cursor");
        guest.getInventory().clearContent();
        guest.inventoryMenu.setCarried(ItemStack.EMPTY);
        joinGuest("empty cursor");
        check(guest.inventoryMenu.getCarried().isEmpty(), "an empty cursor stays empty");
        check(countInInventory(guest, Items.STONE) == 0, "an empty cursor invents no items");
        check(countDroppedAround(level, guest, Items.STONE) == 0, "an empty cursor drops nothing");

        enter("container-join-full-inventory");
        freshEncounter(level, "full inventory");
        guest.getInventory().clearContent();
        for (int slot = 0; slot < 36; slot++) guest.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        guest.inventoryMenu.setCarried(new ItemStack(Items.DIRT, 5));
        int stoneBefore = countInInventory(guest, Items.STONE);
        joinGuest("full inventory");
        check(guest.inventoryMenu.getCarried().isEmpty(), "a full inventory still clears the cursor");
        check(countInInventory(guest, Items.STONE) == stoneBefore, "a full inventory keeps its existing stacks untouched");
        check(countInInventory(guest, Items.DIRT) == 0, "the overflow is never duplicated into the inventory");
        check(countDroppedAround(level, guest, Items.DIRT) == 5, "the overflow is dropped as a real item entity");

        owner.getInventory().clearContent();
        guest.getInventory().clearContent();
        BattleManager.reset();
        Tactical.LOGGER.info("[Tactical] container settlement stage complete");
    }

    private static void fail(MinecraftServer server, Throwable failure) {
        enabled = false;
        Tactical.LOGGER.error("[Tactical] MULTIPLAYER SELFTEST FAILED at stage {}", stage, failure);
        try {
            BattleManager.reset();
        } catch (Throwable ignored) {
            // A broken teardown must not hide the original failure.
        }
        halt(server, 1);
    }

    private static void pass(MinecraftServer server) {
        enabled = false;
        Tactical.LOGGER.info("[Tactical] MULTIPLAYER SELFTEST PASSED");
        halt(server, 0);
    }

    /** Explicit exit: the harness must never treat a green Gradle task as a passing test. */
    private static void halt(MinecraftServer server, int code) {
        try {
            server.halt(false);
        } catch (Throwable ignored) {
            // Fall through to the explicit exit below.
        }
        Runtime.getRuntime().halt(code);
    }

    // ------------------------------------------------------------------ AI navigation stage

    private static final int NAV_TIMEOUT_TICKS = 400;

    private static ServerBattle navBattle;
    private static ServerPlayer navOwner;
    private static int navTicks;
    private static final java.util.Map<UUID, GridPos> NAV_START_CELL = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Mob> NAV_MOBS = new java.util.HashMap<>();

    // --------------------------------------------- owed bootstrap retried by real server ticks

    /**
     * A member whose entry bootstrap fails twice must stay admitted and be repaired later.
     *
     * <p>The entry transaction cannot publish the journal history, the warning snapshot or the skill
     * state, so the server keeps the membership and the roster and registers a debt. The server tick
     * retries it with a bounded backoff; this stage injects three more failures so the backoff and the
     * bounded attempt count are real instead of simulated.</p>
     */
    private static void beginBootstrapProbe(MinecraftServer server) {
        enter("membership-bootstrap-retry");
        ServerLevel level = server.overworld();
        BattleManager.reset();
        LevelGridWorld world = new LevelGridWorld(level);
        GridPos origin = world.surfaceCell(owner.getBlockX(), owner.getBlockZ(), owner.getBlockY());
        check(origin != null, "bootstrap-retry origin is standable");
        for (Mob leftover : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(
                origin.x() - 96, origin.y() - 24, origin.z() - 96,
                origin.x() + 97, origin.y() + 25, origin.z() + 97))) {
            if (leftover instanceof net.minecraft.world.entity.monster.Enemy) leftover.discard();
        }
        Zombie enemy = EntityType.ZOMBIE.create(level);
        check(enemy != null, "bootstrap-retry enemy created");
        enemy.setPos(owner.getX() + 2.5D, owner.getY(), owner.getZ() + 2.5D);
        level.addFreshEntity(enemy);
        check(BattleManager.start(owner, true), "the encounter the retry probe enters starts");
        bootstrapBattle = BattleManager.findFor(owner.getUUID());
        check(bootstrapBattle != null, "the probe encounter is routed");

        // An unrelated encounter, far enough away that it must never notice the retries.
        BlockPos far = new BlockPos(origin.x() + 72, origin.y(), origin.z());
        buildPlatform(level, far, 8);
        bootstrapOther = player(level, far, 0, "MultiRetryFar");
        Zombie farEnemy = EntityType.ZOMBIE.create(level);
        check(farEnemy != null, "the unrelated encounter has an enemy");
        farEnemy.setPos(bootstrapOther.getX() + 2.5D, bootstrapOther.getY(), bootstrapOther.getZ() + 2.5D);
        level.addFreshEntity(farEnemy);
        check(BattleManager.start(bootstrapOther, true), "the unrelated encounter starts");
        check(BattleManager.findFor(bootstrapOther.getUUID()) != null, "and is routed separately");

        // Two inline failures inside the entry transaction, then three failing retries.
        ServerBattle.injectFault(ServerBattle.FaultPoint.BOOTSTRAP, 2);
        com.tactical.neoforge.network.ServerPackets.startCapture();
        var joined = BattleDiscovery.join(fourth, bootstrapBattle.sessionId());
        check(joined.joined(), "the committed join still answers success: " + joined.denial());
        check(ServerBattle.pendingFaults() == 0, "both inline bootstrap attempts failed as injected");
        var debt = com.tactical.neoforge.battle.multi.PendingBootstraps.debt(fourth.getUUID());
        check(debt != null, "the owed bootstrap is registered instead of being dropped");
        check(debt.sessionId().equals(bootstrapBattle.sessionId()),
                "the debt names the encounter the client is showing");
        var membership = com.tactical.neoforge.battle.multi.MembershipGenerations.current(fourth);
        check(membership.active() && bootstrapBattle.sessionId().equals(membership.sessionId()),
                "while the membership stays ACTIVE on that encounter");
        check(membership.generation() == debt.generation(),
                "and the debt is bound to that generation");
        check(BattleManager.findFor(fourth.getUUID()) == bootstrapBattle, "the roster keeps the member");
        check(bootstrapBattle.isMember(fourth), "and the encounter still owns it");

        ServerBattle.injectFault(ServerBattle.FaultPoint.BOOTSTRAP, 3);
        bootstrapProbe = fourth.getUUID();
        bootstrapProbeDone = false;
        bootstrapScheduledTick = server.getTickCount();
        bootstrapSkillsBefore = com.tactical.neoforge.network.SkillNetwork
                .forcedPublications(bootstrapProbe);
    }

    /** Wait for the retries, then verify consistency, the resend and the untouched neighbour. */
    private static void pollBootstrapProbe(MinecraftServer server) {
        boolean owed = com.tactical.neoforge.battle.multi.PendingBootstraps.owed(bootstrapProbe);
        long elapsed = server.getTickCount() - bootstrapScheduledTick;
        if (owed && elapsed < 400) return;
        check(!owed, "the owed bootstrap is cleared within the retry budget (" + elapsed + " ticks)");
        long attempts = com.tactical.neoforge.battle.multi.PendingBootstraps.attemptsMade();
        check(attempts == 4, "four bounded attempts were made: " + attempts);
        // Backoff, not per-tick hammering: the gap before each attempt grows.
        List<Long> attemptTicks = com.tactical.neoforge.battle.multi.PendingBootstraps.attemptTicks();
        check(attemptTicks.size() == 4, "each attempt is recorded, got " + attemptTicks.size());
        long previous = bootstrapScheduledTick;
        for (int index = 0; index < attemptTicks.size(); index++) {
            long gap = attemptTicks.get(index) - previous;
            long expected = (long) com.tactical.neoforge.battle.multi.PendingBootstraps.FIRST_DELAY_TICKS
                    << Math.min(6, index);
            check(gap >= expected, "attempt " + (index + 1) + " waited " + gap
                    + " ticks, expected at least " + expected);
            previous = attemptTicks.get(index);
        }

        // The membership and the roster never moved during the whole episode.
        var membership = com.tactical.neoforge.battle.multi.MembershipGenerations.current(bootstrapProbe);
        check(membership.active() && bootstrapBattle.sessionId().equals(membership.sessionId()),
                "the membership stayed ACTIVE on the same encounter throughout");
        check(BattleManager.findFor(bootstrapProbe) == bootstrapBattle, "the roster still holds the member");
        check(bootstrapBattle.isMember(fourth), "and the encounter still owns it");

        // The parts that were owed really were re-attempted for this member.
        var captured = com.tactical.neoforge.network.ServerPackets.capturedFor(bootstrapProbe);
        int history = 0;
        int warnings = 0;
        for (var packet : captured) {
            if (packet instanceof com.tactical.neoforge.network.BattleLogPacket log
                    && bootstrapBattle.sessionId().equals(log.battleId())) history++;
            if (packet instanceof com.tactical.neoforge.network.CreeperTelegraphPacket telegraph
                    && bootstrapBattle.sessionId().equals(telegraph.battleId())) warnings++;
        }
        check(history > 0, "the journal history was re-published, saw " + history);
        check(warnings > 0, "the warning snapshot was re-published, saw " + warnings);
        long forcedSkills = com.tactical.neoforge.network.SkillNetwork.forcedPublications(bootstrapProbe);
        check(forcedSkills > bootstrapSkillsBefore, "the skill state was re-published, saw " + forcedSkills);

        // The unrelated encounter saw nothing of it.
        for (var packet : com.tactical.neoforge.network.ServerPackets.capturedFor(bootstrapOther.getUUID())) {
            if (packet instanceof com.tactical.neoforge.network.BattleLogPacket log) {
                check(!bootstrapBattle.sessionId().equals(log.battleId()),
                        "the unrelated encounter never receives this encounter's journal");
            }
            if (packet instanceof com.tactical.neoforge.network.BattleStatePacket snapshot) {
                check(!bootstrapBattle.sessionId().equals(snapshot.sessionId()),
                        "nor this encounter's snapshots");
            }
        }
        // Its own lifecycle is its own business (its enemy may well have finished it in the meantime);
        // what must never happen is that this encounter's repair leaks into it.
        var otherMembership = com.tactical.neoforge.battle.multi.MembershipGenerations
                .current(bootstrapOther.getUUID());
        check(!otherMembership.active()
                        || !bootstrapBattle.sessionId().equals(otherMembership.sessionId()),
                "the unrelated encounter never becomes this one");

        com.tactical.neoforge.network.ServerPackets.stopCapture();
        Tactical.LOGGER.info("[Tactical] bootstrap retry probe completed after {} ticks, attempts={}",
                elapsed, attempts);
        bootstrapProbeDone = true;
    }

    private static void beginNavigation(MinecraftServer server) {
        enter("ai-navigation-setup");
        ServerLevel level = server.overworld();
        BattleManager.reset();
        LevelGridWorld world = new LevelGridWorld(level);
        GridPos center = world.surfaceCell(owner.getBlockX(), owner.getBlockZ(), owner.getBlockY());
        check(center != null, "navigation platform centre is standable");
        owner.setPos(center.x() + 0.5D, center.y(), center.z() + 0.5D);
        // Deterministic stage: remove every leftover hostile from the earlier stages so exactly one
        // enemy starts exactly six cells away. Only this isolated fixture world is touched.
        for (Mob leftover : level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(
                center.x() - 24, center.y() - 8, center.z() - 24,
                center.x() + 25, center.y() + 9, center.z() + 25))) {
            if (leftover instanceof net.minecraft.world.entity.monster.Enemy) leftover.discard();
        }
        GridPos target = center.offset(6, 0, 0);
        check(LevelGridWorld.isStandable(level, target.x(), target.y(), target.z()),
                "navigation enemy cell is standable at " + target);
        Zombie enemy = EntityType.ZOMBIE.create(level);
        check(enemy != null, "navigation enemy created");
        enemy.setPos(target.x() + 0.5D, target.y(), target.z() + 0.5D);
        level.addFreshEntity(enemy);
        check(BattleManager.start(owner, true), "navigation encounter starts");
        navBattle = BattleManager.current();
        check(navBattle != null && !navBattle.finished(), "navigation encounter registered");
        navOwner = owner;
        navTicks = 0;
        NAV_START_CELL.clear();
        NAV_MOBS.clear();
        for (LivingEntity participant : navBattle.participants()) {
            if (!(participant instanceof Mob mob)) continue;
            GridPos cell = navBattle.currentActorCell(mob);
            if (cell == null) continue;
            NAV_START_CELL.put(mob.getUUID(), cell);
            NAV_MOBS.put(mob.getUUID(), mob);
        }
        check(!NAV_START_CELL.isEmpty(), "at least one hostile participates in the navigation encounter");
        double nearest = Double.MAX_VALUE;
        for (GridPos cell : NAV_START_CELL.values()) nearest = Math.min(nearest, cell.manhattanDistance(center));
        check(nearest >= 5, "a hostile starts several cells away so a real navigator path is required: " + nearest);
        Tactical.LOGGER.info("[Tactical] ai navigation stage enemies={} nearest={}",
                NAV_START_CELL.size(), nearest);
    }

    /**
     * Drives real server ticks until an enemy actually walks a navigator path toward the player.
     * This is the stage that would have caught a self-recursive navigator delegate: the previous
     * fixture never entered enemy pathfinding at all, so a green run could not prove it worked.
     */
    private static void tickNavigation(MinecraftServer server) {
        if (navBattle.finished()) {
            throw new AssertionError("navigation encounter finished before any enemy moved");
        }
        GridPos ownerCell = navBattle.playerCell(navOwner);
        if (ownerCell == null) throw new AssertionError("owner lost its authoritative cell");
        if (navBattle.isPlayerTurnNow(navOwner)) navBattle.endPlayerTurn(navOwner, navBattle.stamp());
        for (java.util.Map.Entry<UUID, GridPos> entry : NAV_START_CELL.entrySet()) {
            Mob mob = NAV_MOBS.get(entry.getKey());
            GridPos now = navBattle.currentActorCell(mob);
            if (now == null || now.equals(entry.getValue())) continue;
            int before = entry.getValue().manhattanDistance(ownerCell);
            int after = now.manhattanDistance(ownerCell);
            check(after < before,
                    "the enemy's navigator path must approach the player: " + entry.getValue() + " -> " + now);
            Tactical.LOGGER.info(
                    "[Tactical] ai navigation moved {} -> {} (manhattan {} -> {}) after {} ticks, epoch={}",
                    entry.getValue(), now, before, after, navTicks, navBattle.turnEpoch());
            pass(server);
            return;
        }
        if (++navTicks > NAV_TIMEOUT_TICKS) {
            throw new AssertionError("no enemy moved within " + NAV_TIMEOUT_TICKS + " ticks: phase="
                    + navBattle.phaseName() + " epoch=" + navBattle.turnEpoch()
                    + " enemies=" + navBattle.debugEnemies() + " owner=" + ownerCell);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void enter(String name) { stage = name; }

    private static int revision(ServerBattle battle) { return battle == null ? -1 : battle.stateRevision(); }

    private static BattleEligibility.Denial accept(ServerPlayer player) {
        return BattleManager.acceptHeadless(player);
    }

    private static void run(MinecraftServer server) {
        check(zombie != null, "selftest zombie missing");
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);

        enter("owner-start");
        check(BattleManager.start(owner, true), "force start with a nearby zombie");
        ServerBattle battle = BattleManager.current();
        check(battle != null && !battle.finished(), "encounter registered");
        check(battle.memberCount() == 1, "only the starter joins automatically");
        check(owner.getUUID().equals(battle.managerId()), "starter manages invitations");
        check(BattleManager.findFor(owner.getUUID()) == battle, "route by player uuid");
        check(BattleManager.findById(battle.sessionId()) == battle, "route by session uuid");
        check(BattleManager.findFor(guest.getUUID()) == null, "guest is not routed before joining");

        enter("outsider-isolation");
        check(!battle.isParticipant(outsider), "a bystander is not a participant");
        check(BattleManager.containing(outsider) == null, "no encounter contains a bystander");
        check(BattleManager.containing(owner) == battle, "the attacker side resolves its own encounter");
        LivingEntity bogusTarget = outsider;
        check(BattleManager.containing(bogusTarget) == null,
                "an out-of-encounter victim resolves nothing, so the attacker side must be consulted first");
        int before = revision(battle);
        GridPos ownerCell = battle.playerCell(owner);
        check(ownerCell != null, "owner has an authoritative cell");
        battle.tryMove(guest, battle.stamp(), ownerCell.offset(1, 0, 0));
        battle.tryAttack(guest, battle.stamp(), zombie.getUUID());
        battle.endPlayerTurn(guest, battle.stamp());
        BattleManager.attack(outsider, battle.stamp(), zombie.getUUID());
        check(revision(battle) == before, "non-member intents change no authoritative state");
        check(battle.memberCount() == 1, "non-member intents never join or remove anybody");

        enter("invite-decline-reinvite");
        check(battle.invite(outsider, guest) == BattleEligibility.Denial.NOT_MANAGER,
                "a player outside the encounter is told NOT_MANAGER, not an unresolved request");
        check(battle.invite(guest, outsider) == BattleEligibility.Denial.NOT_MANAGER,
                "a joined non-manager member cannot invite either");
        check(battle.invite(owner, guest) == BattleEligibility.Denial.NONE, "manager invites guest");
        check(battle.invite(null, guest) == BattleEligibility.Denial.UNRESOLVED,
                "a null requester stays UNRESOLVED");
        check(battle.session().hasInvitation(guest.getUUID()), "invitation is session bound");
        check(battle.decline(guest) == BattleEligibility.Denial.NONE, "guest declines explicitly");
        check(battle.decline(guest) == BattleEligibility.Denial.NO_INVITE, "declining twice is harmless");
        check(battle.memberCount() == 1, "declining never adds the guest");
        check(battle.invite(owner, guest) == BattleEligibility.Denial.NONE, "manager may re-invite");

        enter("safe-boundary-occupied-cell");
        // Guest accepts while standing on the owner's own cell: never inserted onto a taken cell.
        GridPos parked = new GridPos(guest.getBlockX(), guest.getBlockY(), guest.getBlockZ());
        guest.setPos(ownerCell.x() + 0.5D, ownerCell.y(), ownerCell.z() + 0.5D);
        check(accept(guest) == BattleEligibility.Denial.OCCUPIED,
                "an occupied destination cell refuses the join");
        check(battle.memberCount() == 1, "a refused boundary join never adds a member");
        check(battle.session().hasInvitation(guest.getUUID()), "the invitation stays pending for a retry");
        guest.setPos(parked.x() + 0.5D, parked.y(), parked.z() + 0.5D);

        enter("accept-joins");
        check(accept(guest) == BattleEligibility.Denial.NONE, "guest accepts from a free cell");
        check(battle.memberCount() == 2, "both players are members of one encounter");
        check(owner.getUUID().equals(battle.managerId()), "accepting never steals the manager role");
        check(!battle.session().hasInvitation(guest.getUUID()), "an accepted invitation is consumed");

        enter("stale-stamp");
        int stable = revision(battle);
        ActionStamp staleTurn = new ActionStamp(battle.sessionId(), Math.max(0, battle.turnEpoch() - 1));
        ActionStamp staleSession = new ActionStamp(UUID.randomUUID(), battle.turnEpoch());
        battle.tryMove(owner, staleTurn, ownerCell.offset(0, 0, 1));
        battle.tryMove(owner, staleSession, ownerCell.offset(0, 0, 1));
        battle.tryDefend(owner, staleTurn);
        battle.tryFlee(owner, staleSession);
        check(revision(battle) == stable, "old session or old turn generation cannot mutate state");

        enter("waiting-member");
        List<ServerPlayer> waiting = new ArrayList<>();
        if (!battle.isActorTurn(owner)) waiting.add(owner);
        if (!battle.isActorTurn(guest)) waiting.add(guest);
        check(!waiting.isEmpty(), "at most one member holds the current turn");
        int waitingBefore = revision(battle);
        for (ServerPlayer idle : waiting) {
            check(!battle.isPlayerTurnNow(idle), "a waiting member is not the actor");
            check(VanillaInventoryGuard.denied(idle), "a waiting member cannot mutate vanilla items");
            GridPos idleCell = battle.playerCell(idle);
            check(idleCell != null, "waiting member still owns an authoritative cell");
            battle.tryMove(idle, battle.stamp(), idleCell.offset(0, 0, 1));
            battle.tryAttack(idle, battle.stamp(), zombie.getUUID());
            battle.swapSlots(idle, battle.stamp(), 0, 1);
            battle.dropSlot(idle, battle.stamp(), 0);
            battle.useItem(idle, battle.stamp(), idle.getInventory().selected);
            battle.endPlayerTurn(idle, battle.stamp());
        }
        check(revision(battle) == waitingBefore,
                "a waiting member cannot move, attack, edit inventory or end somebody else's turn");

        enter("identity-sensitive-policy");
        // Same side, different identity: a fixture policy must be able to separate them, and the
        // real target selection/attack legality must follow the policy instead of the side.
        UUID ownerId = owner.getUUID();
        UUID guestId = guest.getUUID();
        BattleRelationPolicy pve = BattleRelationPolicy.pve();
        BattleRelationPolicy faction = (self, other) -> {
            if (self == null || other == null) return BattleRelationPolicy.Relation.UNRELATED;
            if (self.entityId().equals(ownerId) && other.entityId().equals(guestId)) return BattleRelationPolicy.Relation.OPPOSED;
            if (self.entityId().equals(guestId) && other.entityId().equals(ownerId)) return BattleRelationPolicy.Relation.OPPOSED;
            return pve.relationOf(self, other);
        };
        battle.relationPolicy(faction);
        check(battle.opposed(owner, guest), "identity-sensitive policy overrides the default same-side ally");
        check(!"不能攻击友方".equals(battle.previewWeaponAttack(owner, guest)),
                "attack legality preview follows the identity policy, not sideOnly");
        check(!BattleParticipation.allied(owner, guest),
                "friendly-fire protection follows the injected identity policy");
        battle.relationPolicy(pve);
        check(BattleParticipation.allied(owner, guest), "under PvE two members are allies again");
        check(!battle.opposed(owner, guest), "default PvE relations are restored");
        check("不能攻击友方".equals(battle.previewWeaponAttack(owner, guest)),
                "under PvE two members are allies for attack legality again");
        check(battle.opposed(zombie, owner), "a hostile remains opposed to a player under PvE");
        var chosen = battle.primaryOpponent(zombie);
        check(chosen != null && battle.opposed(zombie, chosen),
                "enemy target selection resolves a legal opposed target through the policy");

        enter("four-member-fixture");
        GridPos thirdCell = new GridPos(third.getBlockX(), third.getBlockY(), third.getBlockZ());
        Tactical.LOGGER.info("[Tactical] fixture precheck third at {} inZone={} standable={} surface={}",
                thirdCell, battle.zone().contains(thirdCell),
                LevelGridWorld.isStandable(third.level(), thirdCell.x(), thirdCell.y(), thirdCell.z()),
                new LevelGridWorld(third.level()).surfaceCell(thirdCell.x(), thirdCell.z(), thirdCell.y()));
        BattleEligibility.Denial inviteThird = battle.invite(owner, third);
        check(inviteThird == BattleEligibility.Denial.NONE, "invite third: " + inviteThird);
        BattleEligibility.Denial joinThird = accept(third);
        check(joinThird == BattleEligibility.Denial.NONE, "third joins: " + joinThird);
        // Capacity edge: the manager may still invite a fifth player while one seat is open; that
        // invitation must be refused at accept time once the roster is full. Server-side roster
        // rule for one encounter, not a claim about four real clients.
        check(battle.invite(owner, outsider) == BattleEligibility.Denial.NONE,
                "a fifth player can be invited while a seat is still open");
        BattleEligibility.Denial inviteFourth = battle.invite(owner, fourth);
        check(inviteFourth == BattleEligibility.Denial.NONE, "invite fourth: " + inviteFourth);
        BattleEligibility.Denial joinFourth = accept(fourth);
        check(joinFourth == BattleEligibility.Denial.NONE, "fourth joins: " + joinFourth);
        check(battle.memberCount() == 4, "four real members share one encounter");
        check(battle.invite(owner, outsider) == BattleEligibility.Denial.BATTLE_FULL,
                "a full roster refuses further invitations");
        int fullOwnerAp = battle.currentAp(owner);
        int fullGuestAp = battle.currentAp(guest);
        int fullThirdAp = battle.currentAp(third);
        int fullFourthAp = battle.currentAp(fourth);
        int fullRevision = battle.stateRevision();
        BattleEligibility.Denial fifthAccept = accept(outsider);
        check(fifthAccept == BattleEligibility.Denial.BATTLE_FULL,
                "accepting a stale fifth-seat invitation into a full roster is refused: " + fifthAccept);
        check(battle.memberCount() == 4, "a refused fifth acceptance keeps the roster at four");
        check(BattleManager.findFor(outsider.getUUID()) == null, "a refused fifth player is never routed");
        check(battle.stateRevision() == fullRevision, "a refused fifth acceptance changes no authoritative state");
        check(battle.currentAp(owner) == fullOwnerAp && battle.currentAp(guest) == fullGuestAp
                        && battle.currentAp(third) == fullThirdAp && battle.currentAp(fourth) == fullFourthAp,
                "the four members keep their own resources after a refused fifth acceptance");
        Tactical.LOGGER.info("[Tactical] fixture 4 members={} epoch={} revision={} queue={} enemies={} ap=[{}, {}, {}, {}]",
                battle.memberCount(), battle.turnEpoch(), battle.stateRevision(), battle.turnQueueSize(),
                battle.enemyCount(), battle.currentAp(owner), battle.currentAp(guest),
                battle.currentAp(third), battle.currentAp(fourth));
        int guestApBefore = battle.currentAp(guest);
        int thirdApBefore = battle.currentAp(third);
        int ownerApBefore = battle.currentAp(owner);
        battle.adjustAp(owner, 10);
        int expectedOwnerAp = Math.min(ownerApBefore + 10, TacticalAttributes.maxAp(owner));
        check(battle.currentAp(owner) == expectedOwnerAp,
                "the owner's own AP is adjusted and clamped to its own cap: " + ownerApBefore + " -> "
                        + battle.currentAp(owner) + " expected " + expectedOwnerAp);
        check(battle.currentAp(guest) == guestApBefore,
                "an AP adjustment for one member never touches another member's AP");
        check(battle.currentAp(third) == thirdApBefore, "and never touches a third member's AP");

        enter("public-friendly-recruitment");
        Zombie ally = EntityType.ZOMBIE.create(server.overworld());
        check(ally != null, "friendly candidate created");
        ally.setPos(owner.getX() + 2.5D, owner.getY(), owner.getZ() + 2.5D);
        server.overworld().addFreshEntity(ally);
        check(BattleParticipation.tryAddFriendly(ally),
                "public API recruits a brand-new friendly NPC (candidate, not member, semantics)");
        check(!BattleParticipation.tryAddFriendly(ally), "duplicate recruitment is refused");

        Zombie faraway = EntityType.ZOMBIE.create(server.overworld());
        check(faraway != null, "far candidate created");
        faraway.setPos(owner.getX() + 600.0D, owner.getY(), owner.getZ() + 600.0D);
        server.overworld().addFreshEntity(faraway);
        check(!BattleParticipation.tryAddFriendly(faraway), "an NPC outside the zone never joins");

        ServerLevel other = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level != server.overworld()) { other = level; break; }
        }
        if (other != null) {
            Zombie elsewhere = EntityType.ZOMBIE.create(other);
            if (elsewhere != null) {
                elsewhere.setPos(owner.getX() + 0.5D, owner.getY(), owner.getZ() + 0.5D);
                other.addFreshEntity(elsewhere);
                check(!BattleParticipation.tryAddFriendly(elsewhere), "a mob in another dimension never joins");
            }
        } else {
            Tactical.LOGGER.info("[Tactical] single loaded dimension; cross-dimension recruitment covered statically");
        }

        enter("lifecycle");
        Mob recruitedNpc = ally;
        check(BattleManager.leave(owner, "selftest leave"), "owner leaves explicitly");
        check(!battle.finished(), "the encounter survives one member's exit");
        check(battle.memberCount() == 3, "only the owner left");
        check(guest.getUUID().equals(battle.managerId()), "management moves to the earliest surviving member");
        check(BattleManager.findFor(owner.getUUID()) == null, "a departed player is unrouted");
        check(BattleManager.findFor(guest.getUUID()) == battle, "the survivor keeps routing");
        check(BattleParticipation.participants().contains(guest), "survivors remain battle participants");
        check(!BattleParticipation.participants().contains(owner), "the departed player left the roster");
        check(BattleParticipation.participants().contains(recruitedNpc), "a recruited NPC is unaffected by a player leaving");

        check(BattleManager.leave(guest, "selftest leave"), "guest leaves");
        check(BattleManager.leave(third, "selftest leave"), "third leaves");
        check(!battle.finished(), "two survivors still keep the encounter alive");
        check(BattleManager.leave(fourth, "selftest leave"), "the last member leaves");
        check(battle.finished(), "the last member's exit finishes the encounter");
        check(BattleManager.current() == null, "no encounter is registered after the last exit");
        check(BattleManager.findFor(fourth.getUUID()) == null, "no route remains for a departed player");

        containerSettlement(server);
        movingJoinBoundary(server);
        twoEncounterIsolation(server);
        nearbyDiscoveryAndJoin(server);

        BattleManager.reset();
        Tactical.LOGGER.info("[Tactical] multi selftest synchronous stages complete");
    }
}
