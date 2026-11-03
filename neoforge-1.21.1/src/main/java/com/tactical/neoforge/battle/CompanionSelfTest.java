package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.combat.BattlePhase;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.api.companion.CompanionAbility;
import com.tactical.neoforge.api.companion.CompanionAdapter;
import com.tactical.neoforge.battle.multi.ActionStamp;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.network.BattleStatePacket;
import com.tactical.neoforge.registry.companion.CompanionRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real loaded-server fixture for hand-commanded companions: {@code -Dtactical.selftest=companion}.
 *
 * <p>Every check drives the production entry points on real entities in a real {@link ServerBattle}: the
 * explicit recruitment path, the stamped intents a real connection sends, the shared guard through the real
 * damage event, and the lifecycle releases. No mocked mob, no interface-only assertion, and no fixture-only
 * business path - the fixture only uses reflection to hand the turn to one actor, which is what the ATB
 * scheduler would do on its own.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class CompanionSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer guest;
    /** A real connection that never joins the encounter, for the bystander interaction check. */
    private static ServerPlayer bystander;
    private static Zombie hostile;
    private static ServerBattle battle;
    private static final List<Mob> mobs = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();
    private static final List<ServerPlayer> players = new ArrayList<>();
    private static int oldCap;
    private static boolean configured;
    private static CompanionAdapter testAdapter;
    private static int stage;

    private CompanionSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        enabled = "companion".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        stage = 0;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            check(++ticks < 900, "companion fixture deadline");
            if (ticks == 20) setup(event.getServer());
            if (ticks < 50) return;
            if (stage == 0) {
                if (!level.getEntitiesOfClass(Zombie.class, owner.getBoundingBox().inflate(24)).contains(hostile)) return;
                recruitment();
                stage = 1;
            } else if (stage == 1) {
                if (battle == null || battle.isBusy()) return;
                authority();
                stage = 2;
            } else if (stage == 2) {
                if (battle.isBusy()) return;
                actions();
                stage = 3;
            } else if (stage == 3) {
                if (battle.isBusy()) return;
                guard();
                stage = 4;
            } else if (stage == 4) {
                if (battle.isBusy()) return;
                lifecycle();
                stage = 5;
            } else if (stage == 5) {
                if (battle == null || battle.isBusy()) return;
                releaseEvidence();
                stage = 6;
            } else if (stage == 6) {
                if (battle == null || battle.isBusy()) return;
                guards();
                stage = 7;
            } else if (stage == 7) {
                if (battle == null || battle.isBusy()) return;
                abilities();
                stage = 8;
            } else if (stage == 8) {
                if (battle == null || battle.isBusy()) return;
                savedWhileControlled();
                pass(event.getServer());
            }
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] COMPANION SELFTEST FAILED stage={} tick={}", stage, ticks, failure);
            try {
                finish(event.getServer());
            } catch (Throwable ignored) {
                // A broken teardown must not hide the original failure.
            }
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        oldCap = TacticalConfig.INSTANCE.participantCap.get();
        configured = true;
        TacticalConfig.INSTANCE.participantCap.set(4);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX(), Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 20), spawn.getZ());
        for (int x = (origin.getX() - 24) >> 4; x <= (origin.getX() + 24) >> 4; x++) {
            for (int z = (origin.getZ() - 24) >> 4; z <= (origin.getZ() + 24) >> 4; z++) {
                if (level.setChunkForced(x, z, true)) chunks.add(new BlockPos(x, 0, z));
            }
        }
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y < 9; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        owner = player(server, "CompanionOwner", 0, 0);
        guest = player(server, "CompanionGuest", 6, 0);
        bystander = player(server, "CompanionBystander", 12, 0);
        hostile = mob(EntityType.ZOMBIE, 5, 0, 5);
        // A non-wolf companion kind proves the control skeleton does not depend on the wolf adapter.
        testAdapter = new TestCowCompanionAdapter(owner.getUUID());
        CompanionRegistry.doRegister(testAdapter);
    }

    // ------------------------------------------------------------------ stages

    private static void recruitment() throws Exception {
        Wolf standing = wolf(owner, 2, 0, 0, false);
        Wolf sitting = wolf(owner, -3, 0, 0, true);
        Wolf wild = wolf(null, -2, 0, 3, false);
        Wolf foreign = wolf(guest, 4, 0, -3, false);
        check(BattleManager.start(owner, true), "real encounter starts");
        battle = BattleManager.current();
        check(battle != null && battle.sideOf(hostile) == BattleSide.HOSTILE, "the hostile side is authoritative");
        // The publication capture is what a recipient was actually told; it is the only honest way to judge a
        // per-recipient projection without a client.
        com.tactical.neoforge.network.ServerPackets.startCapture();

        check(CompanionRegistry.find(standing) instanceof com.tactical.neoforge.companion.WolfCompanionAdapter,
                "the built-in wolf adapter claims a wolf");
        // The production start path has to bring the owner's own standing wolf along by itself; this is the
        // discovery requirement, not a fixture-only call.
        check(battle.isCompanion(standing),
                "the owner's standing wolf is recruited by the battle start, on the real path");
        check(owner.getUUID().equals(battle.companionOwner(standing)),
                "the companion records its live controller");
        check(!standing.isOrderedToSit(), "a commanded wolf is not left sitting");
        check(!battle.isCompanion(sitting), "a wolf ordered to sit is never recruited");
        check(!battle.isCompanion(wild), "a wild wolf is never recruited");
        check(!battle.isCompanion(foreign), "another player's wolf is never recruited for this owner");
        check(!BattleParticipation.tryAddCompanion(sitting), "and the public entry refuses it as well");
        check(!BattleParticipation.tryAddCompanion(wild), "the public entry refuses a wild wolf");
        check(!BattleParticipation.tryAddCompanion(foreign), "the public entry refuses another owner's wolf");
        check(!BattleParticipation.tryAddCompanion(standing), "duplicate recruitment is refused");

        // A wolf that arrives after the encounter started is still picked up by the explicit entry.
        Wolf late = wolf(owner, -5, 0, 5, false);
        check(BattleParticipation.tryAddCompanion(late), "a companion that turns up mid-battle can be recruited");
        check(battle.companionCount() == 2, "the owner now commands two companions");

        BattleEligibility.Denial joined = battle.joinDirect(guest);
        check(joined == BattleEligibility.Denial.NONE, "a second real connection joins the same encounter: " + joined);
        check(battle.isCompanion(foreign), "the second owner's own wolf joins under that owner's join");
        check(guest.getUUID().equals(battle.companionOwner(foreign)), "each wolf answers to its own controller");
        check(battle.companionCount() == 3, "two owners, three companions in total");

        Cow cow = cow(owner, -1, 0, -4);
        check(BattleParticipation.tryAddCompanion(cow), "a non-wolf adapter recruits through the same path");
        check(battle.companionCount() == 4, "the non-wolf companion is on the roster");
        check(battle.companionAbilities(cow) == CompanionAbility.all(), "the adapter declares every order");
    }

    private static void authority() throws Exception {
        Wolf mine = companionOf(owner);
        check(mine != null, "the owner has a companion on the roster");
        begin(mine);
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()),
                "a companion turn is its own phase: " + battle.phaseName());
        ActionStamp stamp = battle.stamp();
        // Recipient isolation: only the controller is told about the companion it may order.
        BattleStatePacket.Command toOwner = lastCaptured(owner).command();
        BattleStatePacket.Command toGuest = lastCaptured(guest).command();
        // (kept as one object: the guest's projection must be the shared empty one)
        check(toOwner.commanding(), "the controller receives its companion projection");
        check(mine.getUUID().equals(toOwner.companionId()), "the projection names that companion");
        check(toOwner.cellX() == battle.currentActorCell(mine).x()
                && toOwner.cellY() == battle.currentActorCell(mine).y()
                && toOwner.cellZ() == battle.currentActorCell(mine).z(),
                "the projection carries the companion's own authoritative cell");
        check(toOwner.ap() == battle.actorAp(mine), "and the companion's own AP, not the owner's");
        check(!toGuest.commanding(), "another player receives no command projection at all");
        check(toGuest.companionId() == null, "and never learns which companion it could be");
        GridPos own = battle.currentActorCell(mine);
        GridPos target = new GridPos(own.x() + 2, own.y(), own.z());

        // Not this player's companion: another real connection, the same intent, nothing may change.
        int guestAp = battle.actorAp(mine);
        battle.tryMove(guest, stamp, target);
        check(battle.currentActorCell(mine).equals(own) && battle.actorAp(mine) == guestAp,
                "another connection cannot move somebody else's companion");
        battle.tryAttack(guest, stamp, hostile.getUUID());
        check(hostile.getHealth() == hostile.getMaxHealth(), "and cannot make it attack");

        // Stale generation: a stamp from before this turn is refused.
        ActionStamp stale = new ActionStamp(stamp.sessionId(), stamp.turnEpoch() - 1);
        battle.tryMove(owner, stale, target);
        check(battle.currentActorCell(mine).equals(own), "a stale turn generation is refused");
        // Wrong session.
        battle.tryMove(owner, new ActionStamp(UUID.randomUUID(), stamp.turnEpoch()), target);
        check(battle.currentActorCell(mine).equals(own), "a foreign session id is refused");

        // Ownership change: the adapter's live answer is what counts, not the record taken at recruitment.
        com.tactical.neoforge.companion.WolfCompanionAdapter wolfAdapter =
                (com.tactical.neoforge.companion.WolfCompanionAdapter) CompanionRegistry.find(mine);
        check(wolfAdapter != null, "the wolf adapter is resolvable");
        UUID original = mine.getOwnerUUID();
        check(original != null && original.equals(owner.getUUID()), "the wolf records its tamer");
        check(battle.isCompanion(mine), "the companion is still on the roster after the negative intents");
    }

    private static void actions() throws Exception {
        Wolf mine = companionOf(owner);
        begin(mine);
        ActionStamp stamp = battle.stamp();
        GridPos start = battle.currentActorCell(mine);
        int startAp = battle.actorAp(mine);
        int ownerAp = battle.currentAp(owner);

        // Real movement: the cell changes, the AP is charged once, and the turn stays open.
        long epochBefore = battle.turnEpoch();
        GridPos step = new GridPos(start.x() - 1, start.y(), start.z());
        check(battle.tryMove(owner, stamp, step), "the owner moves its own companion");
        check(battle.actorAp(mine) < startAp, "the move charged the companion's own AP");
        for (int tick = 0; tick < 200 && battle.isBusy(); tick++) battle.tick();
        check(battle.currentActorCell(mine).equals(step), "the companion really walked to the target cell");
        check(battle.turnEpoch() == epochBefore, "movement alone never ends the companion's turn");
        check(hostile.getHealth() == hostile.getMaxHealth(), "and never adds an automatic attack");
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()), "the turn still belongs to the companion");

        // Nowhere near the hostile: melee must be refused without a charge.
        int apBeforeRefusal = battle.actorAp(mine);
        battle.tryAttack(owner, battle.stamp(), hostile.getUUID());
        check(battle.actorAp(mine) == apBeforeRefusal, "a refused melee charges nothing");

        // Explicit-target melee from a legal cell: real damage, one price, and the ramp applies to the second.
        moveNextTo(mine, hostile);
        begin(mine);
        long epochAtAttack = battle.turnEpoch();
        int apBeforeHit = battle.actorAp(mine);
        hostile.setInvulnerable(false);
        hostile.invulnerableTime = 0;
        hostile.setHealth(hostile.getMaxHealth());
        float before = hostile.getHealth();
        battle.tryAttack(owner, battle.stamp(), hostile.getUUID());
        int firstCost = apBeforeHit - battle.actorAp(mine);
        check(hostile.getHealth() < before, "the companion's melee hurts the chosen hostile");
        check(firstCost > 0, "and charges its own AP once: " + firstCost);

        int apBeforeSecond = battle.actorAp(mine);
        hostile.invulnerableTime = 0;
        hostile.setHealth(hostile.getMaxHealth());
        battle.tryAttack(owner, battle.stamp(), hostile.getUUID());
        int secondCost = apBeforeSecond - battle.actorAp(mine);
        check(secondCost > firstCost, "the repeated-action ramp charged the second order more: "
                + firstCost + " -> " + secondCost);
        check(battle.turnEpoch() == epochAtAttack, "an accepted attack also leaves the turn open");
        check(battle.currentAp(owner) == ownerAp, "the owner's own AP is untouched by its companion's orders");
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()), "and the turn is still the companion's");

        // End turn: the only order that hands the slot over.
        long beforeEnd = battle.turnEpoch();
        check(beforeEnd == epochAtAttack, "nothing before the end order moved the turn generation");
        battle.endPlayerTurn(owner, battle.stamp());
        check(battle.turnEpoch() > beforeEnd, "the owner ends its companion's turn");
    }

    private static void guard() throws Exception {
        Wolf mine = companionOf(owner);
        begin(mine);
        ActionStamp stamp = battle.stamp();
        // Unguarded reference hit.
        mine.setInvulnerable(false);
        mine.invulnerableTime = 0;
        float hp = mine.getHealth();
        hostile.setTarget(mine);
        hostile.doHurtTarget(mine);
        float unguarded = hp - mine.getHealth();
        check(unguarded > 0.0F, "a hostile can actually hit a controlled companion");

        // Guard: 25 AP, then the same hit lands for half.
        begin(mine);
        int ap = battle.actorAp(mine);
        check(ap >= 25, "the companion has the 25 AP the guard costs: " + ap);
        check(!battle.isDefending(mine), "no guard before the order");
        battle.tryDefendFacing(owner, battle.stamp(), 90);
        check(battle.isDefending(mine), "the defend order raises a real guard");
        check(battle.actorAp(mine) == ap - 25, "the guard costs exactly 25 of the companion's AP");
        mine.setInvulnerable(false);
        mine.invulnerableTime = 0;
        mine.hurtTime = 0;
        mine.hurtDuration = 0;
        hp = mine.getHealth();
        hostile.setTarget(mine);
        hostile.doHurtTarget(mine);
        float guarded = hp - mine.getHealth();
        check(guarded > 0.0F, "the guarded hit still lands");
        check(guarded < unguarded, "the guard really reduces the damage: " + unguarded + " -> " + guarded);
        check(Math.abs(guarded - unguarded * 0.5D) <= Math.max(0.51D, unguarded * 0.1D),
                "the no-shield guard halves the hit: " + unguarded + " -> " + guarded);

        // The guard lasts until this actor's own next turn, then clears.
        begin(mine);
        check(!battle.isDefending(mine), "the guard clears at the companion's next own turn");
    }

    private static void lifecycle() throws Exception {
        Wolf mine = companionOf(owner);
        Wolf other = companionOf(guest);
        check(mine != null && other != null, "both companions are still on the roster");
        boolean originalNoAi = mine.isNoAi();
        UUID mineId = mine.getUUID();
        // The controller leaves: its companion leaves with it, releases its claim and never waits for orders.
        check(BattleManager.leave(guest, "companion fixture leave"), "the second owner leaves");
        check(!battle.isParticipant(other), "the departed owner's companion left the roster");
        check(BattleManager.ownerOf(other.getUUID()) == null, "and gave its ownership claim back");
        check(!battle.finished(), "the encounter survives for the remaining member");

        // A dead companion is released exactly once and never breaks the encounter.
        check(battle.isParticipant(mine), "the remaining companion is still a participant");
        mine.setHealth(0);
        mine.discard();
        battle.tick();
        check(!battle.isParticipant(mine), "a dead companion leaves the roster");
        check(mine.isNoAi() == originalNoAi, "the released companion keeps its own AI flag");
        check(!battle.finished(), "losing a companion is not a victory");

        // Ending the encounter gives back every unit's own flags and never resurrects it.
        battle.finish("companion fixture end");
        check(battle.finished(), "the encounter ends");
        check(BattleManager.ownerOf(mineId) == null, "no ownership claim survives the encounter");
        check(battle.companionCount() == 0, "no companion record survives the encounter");
    }

    /**
     * A normal exit is not a rollback: the final cell, the damage, the equipment and the AI flag are the
     * truth, and the persisted form must carry no accidental lock.
     *
     * <p>The wolf here is created AI-live and non-persistent on purpose, so the fixture can prove a real
     * transition {@code false -> (battle) true -> false} instead of starting from the locked state.</p>
     */
    private static void releaseEvidence() throws Exception {
        BattleManager.reset();
        check(BattleManager.start(owner, true), "a second real encounter starts");
        battle = BattleManager.current();
        Wolf live = liveWolf(owner, 3, 0, 3);
        check(!live.isNoAi(), "the fixture wolf is AI-live before it is recruited");
        check(!live.isPersistenceRequired(), "and not persistent before it is recruited");
        check(BattleParticipation.tryAddCompanion(live), "an AI-live wolf is recruited");
        check(live.isNoAi(), "control takes the AI lock for the duration of the battle");
        check(live.isPersistenceRequired(), "and makes it persistent for the duration");

        begin(live);
        GridPos start = battle.currentActorCell(live);
        GridPos step = new GridPos(start.x() + 1, start.y(), start.z());
        check(battle.tryMove(owner, battle.stamp(), step), "the companion really walks");
        for (int tick = 0; tick < 200 && battle.isBusy(); tick++) battle.tick();
        GridPos end = battle.currentActorCell(live);
        check(end.equals(step), "and stands on the walked-to cell: " + end);
        live.setInvulnerable(false);
        float woundedAt = live.getHealth() - 3.0F;
        live.setHealth(woundedAt);

        battle.finish("companion release fixture");
        check(!live.isNoAi(), "a normal exit gives the original AI flag back");
        check(!live.isPersistenceRequired(), "and the original persistence flag");
        check(live.getHealth() == woundedAt, "the real damage is kept, never healed");
        check(battle.currentActorCell(live) == null, "the unit is no longer a participant");
        // The final cell is the truth now: a normal exit never moves the unit back to the battle start.
        GridPos after = new GridPos(live.getBlockX(), live.getBlockY(), live.getBlockZ());
        check(after.equals(end), "the final legal cell is kept, not the entry cell: " + after);
        // Persisted form: no accidental permanent lock travels with the save.
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        live.saveWithoutId(saved);
        check(!saved.getBoolean("NoAI"), "the saved form carries no battle AI lock");
        check(!saved.getBoolean("PersistenceRequired"), "and no battle persistence flag");
    }

    /**
     * The edges that must refuse or release instead of waiting forever: a dead unit before the tick that
     * cleans it, an owner that changed while the unit owns the acting slot, an off-zone candidate, a throwing
     * adapter, and the vanilla-interaction guard.
     */
    private static void guards() throws Exception {
        // The previous stage ended its encounter, so the guard edges run on a fresh real one.
        BattleManager.reset();
        check(BattleManager.start(owner, true), "a third real encounter starts");
        battle = BattleManager.current();
        // --- a candidate outside the zone is never pulled in
        Wolf faraway = wolf(owner, 200, 0, 200, false);
        check(!BattleParticipation.tryAddCompanion(faraway), "a wolf outside the zone is never admitted");
        check(BattleManager.ownerOf(faraway.getUUID()) == null, "and it gains no ownership claim");
        // --- a throwing pose capture leaves nothing behind
        // A sheep, so no earlier adapter claims it: the throwing one is the kind that answers.
        Mob poison = animal(EntityType.SHEEP, owner, -2, 0, -2);
        CompanionRegistry.doRegister(new ThrowingAdapter(poison.getUUID()));
        check(!BattleParticipation.tryAddCompanion(poison), "a throwing adapter admits nothing");
        check(BattleManager.ownerOf(poison.getUUID()) == null, "no claim is left behind");
        check(!battle.isParticipant(poison), "no roster entry is left behind");
        // --- the vanilla interaction guard
        Wolf controlled = companionOf(owner);
        check(controlled != null, "a companion is on the roster for the interaction check");
        check(interactionCancelled(owner, controlled), "a participant's raw right click on it is refused");
        check(interactionCancelled(bystander, controlled), "a bystander's raw right click on it is refused");
        // Far outside the zone, so it is never recruited; the bystander is not a participant either, which is
        // the only case where the answer can be "not cancelled".
        Wolf freeWolf = wolf(owner, 120, 0, 120, false);
        check(!battle.isParticipant(freeWolf), "the free wolf is not part of the encounter");
        check(!interactionCancelled(bystander, freeWolf), "a wolf outside the encounter is untouched");
        check(interactionCancelled(owner, controlled), "and the participant lock still holds on the roster");

        // --- R1-3: the attack legality and reach are resolved for the commanded actor, not the owner body
        begin(controlled);
        Mob dummy = mob(EntityType.ZOMBIE, 8, 0, 8);
        battle.tryAbsorb(dummy);
        check(battle.isParticipant(dummy), "a second hostile is on the roster for the range check");
        // The companion stands next to it, the owner is far away.
        moveNextTo(controlled, dummy);
        check(battle.commandWeaponKind(owner) == 0, "the commanded wolf fights with its natural melee");
        check(battle.commandAttackAllowed(owner, dummy), "the wolf's own legality allows the attack beside it");
        check(!battle.weaponAttackAllowed(owner, dummy),
                "while the owner's body, far away, could not: the two actors are told apart");
        // The wolf is put back out of reach again: the command preview follows it, while the owner's own
        // preview - taken from the owner's own authoritative cell - does not change with it at all.
        GridPos away = new GridPos(battle.currentActorCell(controlled).x() - 6,
                battle.currentActorCell(controlled).y(), battle.currentActorCell(controlled).z());
        controlled.teleportTo(away.x() + 0.5D, away.y(), away.z() + 0.5D);
        Object controlledState = ((java.util.Map<?, ?>) field(battle, "enemies").get(battle))
                .get(controlled.getUUID());
        Field controlledCell = controlledState.getClass().getDeclaredField("cell");
        controlledCell.setAccessible(true);
        controlledCell.set(controlledState, away);
        check(battle.currentActorCell(controlled).equals(away), "the fixture move is reflected in the battle cell");
        check(!battle.commandAttackAllowed(owner, dummy),
                "out of reach again, the command preview refuses: it answers for the wolf");
        check(!battle.weaponAttackAllowed(owner, dummy),
                "and the owner's own body is still far away, so its preview refuses for its own reason");
        begin(controlled);

        // --- a dead unit refuses an order before the cleanup tick
        begin(controlled);
        int apBefore = battle.actorAp(controlled);
        controlled.setHealth(0);
        battle.tryMove(owner, battle.stamp(), new GridPos(controlled.getBlockX() + 1, controlled.getBlockY(),
                controlled.getBlockZ()));
        check(!battle.isParticipant(controlled) || battle.actorAp(controlled) == apBefore,
                "a dead companion refuses the order without a charge");
        battle.tick();
        check(!battle.isParticipant(controlled), "and the cleanup tick releases it");

        // --- an owner change while the unit owns the slot must not deadlock the queue
        Wolf switched = wolf(owner, 5, 0, -5, false);
        check(BattleParticipation.tryAddCompanion(switched), "a second companion is recruited for the switch");
        begin(switched);
        long epoch = battle.turnEpoch();
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()), "it owns the acting slot");
        // Somebody else now says they own it: the control record is no longer valid.
        switched.setOwnerUUID(bystander.getUUID());
        battle.tryMove(owner, battle.stamp(), new GridPos(switched.getBlockX() + 1, switched.getBlockY(),
                switched.getBlockZ()));
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()),
                "the old controller's order is refused while the record is still being cleaned");
        battle.tick();
        check(!battle.isParticipant(switched), "the handover releases the control record");
        check(battle.turnEpoch() > epoch, "and the queue moves on instead of waiting for a controller");
        check(!BattleParticipation.tryAddCompanion(switched),
                "a unit that left this way is not recruited again by the same encounter");

        // --- B-R3: a companion that happens to hold a bow is still a melee companion
        Mob archer = mob(EntityType.SKELETON, -10, 0, 10);
        archer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        CompanionRegistry.doRegister(new BowCompanionAdapter(archer.getUUID()));
        check(BattleParticipation.tryAddCompanion(archer) || battle.isCompanion(archer),
                "a bow-holding companion is admitted through the same path");
        begin(archer);
        // The order set, the reach and the projection all describe melee, whatever the hand holds.
        check(battle.commandWeaponKind(owner) == 0,
                "a held bow does not turn the companion's ATTACK order into a shot");
        GridPos archerCell = battle.currentActorCell(archer);
        check(battle.commandWeaponRange(owner, new GridPos(archerCell.x() + 5, archerCell.y(), archerCell.z())) == 1,
                "and its reach is the one-cell melee reach, not a bow range");
        BattleStatePacket.Command toOwner = lastCaptured(owner).command();
        check(archer.getUUID().equals(toOwner.companionId()), "the owner's projection names the archer");
        check(toOwner.weaponKind() == 0 && toOwner.weaponRange() == 1,
                "and reports melee kind/reach: " + toOwner.weaponKind() + "/" + toOwner.weaponRange());
        check(!toOwner.weapon().getString().isEmpty(), "while the held item still has a name");

        // A target five cells away: the preview refuses and the formal order charges nothing at all.
        Mob farTarget = mob(EntityType.ZOMBIE, -14, 0, 14);
        battle.tryAbsorb(farTarget);
        check(battle.isParticipant(farTarget), "the distant hostile is on the roster");
        // A hostile that cannot be hurt would make "hurts nobody" meaningless, so it is made hittable here.
        farTarget.setInvulnerable(false);
        farTarget.invulnerableTime = 0;
        check(!battle.commandAttackAllowed(owner, farTarget), "out of melee reach the preview refuses");
        int archerAp = battle.actorAp(archer);
        int ownerAp = battle.currentAp(owner);
        float farHealth = farTarget.getHealth();
        int accepted = actionCount();
        battle.tryAttack(owner, battle.stamp(), farTarget.getUUID());
        check(battle.actorAp(archer) == archerAp, "a refused order charges the companion nothing");
        check(battle.currentAp(owner) == ownerAp, "and never the owner");
        check(farTarget.getHealth() == farHealth, "and hurts nobody");
        check(actionCount() == accepted, "and counts no action");

        // Adjacent: the preview allows, the formal order hits once and charges the companion once.
        moveNextTo(archer, farTarget);
        begin(archer);
        farTarget.invulnerableTime = 0;
        Wolf witness = companionOf(owner);
        check(witness != null, "another companion is still on the roster as the untouched witness");
        int witnessAp = battle.actorAp(witness);
        ownerAp = battle.currentAp(owner);
        archerAp = battle.actorAp(archer);
        farHealth = farTarget.getHealth();
        check(battle.commandAttackAllowed(owner, farTarget), "adjacent, the preview allows the melee");
        battle.tryAttack(owner, battle.stamp(), farTarget.getUUID());
        check(farTarget.getHealth() < farHealth, "the formal order really connects");
        check(battle.actorAp(archer) < archerAp, "and charges the companion once");
        check(battle.currentAp(owner) == ownerAp, "the owner stays untouched");
        check(battle.actorAp(witness) == witnessAp, "and so does the other companion");
    }

    /** Fire the production handler with a real event object, the way a raw right click would. */
    private static boolean interactionCancelled(ServerPlayer player, Mob target) {
        net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event =
                new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract(player,
                        net.minecraft.world.InteractionHand.MAIN_HAND, target);
        BattleItemLock.onEntityInteract(event);
        return event.isCanceled();
    }

    /**
     * C1: the adapter's order set is a server rule.
     *
     * <p>A companion kind that declares only "end turn" must refuse move, attack and defend through the real
     * command entries - no cell, no AP, no guard and no turn may change - while its declared end-turn order
     * still works. Nothing here reads a client-side capability; the formal entry points are the ones a
     * connection reaches.</p>
     */
    private static void abilities() throws Exception {
        BattleManager.reset();
        check(BattleManager.start(owner, true), "the order-set encounter starts");
        battle = BattleManager.current();
        Mob limited = animal(EntityType.PIG, owner, 1, 0, 4);
        CompanionRegistry.doRegister(new OnlyEndTurnAdapter(limited.getUUID()));
        check(logicAdd(limited), "a companion that declares only end-turn is admitted");
        check(battle.companionAbilities(limited) == CompanionAbility.END_TURN.mask(),
                "the projection carries exactly the declared order");

        begin(limited);
        check(battle.phaseName().equals(BattlePhase.COMPANION_TURN.name()), "it owns its own turn");
        GridPos cell = battle.currentActorCell(limited);
        int ap = battle.actorAp(limited);
        long epoch = battle.turnEpoch();
        int attacks = actionCount();

        battle.tryMove(owner, battle.stamp(), new GridPos(cell.x() + 1, cell.y(), cell.z()));
        check(battle.currentActorCell(limited).equals(cell), "an undeclared move changes no cell");
        check(battle.actorAp(limited) == ap, "and costs nothing");
        float healthBefore = hostile.getHealth();
        battle.tryAttack(owner, battle.stamp(), hostile.getUUID());
        check(hostile.getHealth() == healthBefore, "an undeclared attack hurts nobody");
        check(battle.actorAp(limited) == ap, "and costs nothing either");
        battle.tryDefendFacing(owner, battle.stamp(), 90);
        check(!battle.isDefending(limited), "an undeclared guard is not raised");
        check(battle.actorAp(limited) == ap, "and costs nothing");
        check(battle.turnEpoch() == epoch, "none of the refused orders moved the turn");
        check(actionCount() == attacks, "and none of them counted as a paid action");

        battle.endPlayerTurn(owner, battle.stamp());
        check(battle.turnEpoch() > epoch, "the declared end-turn order still hands the slot over");
    }

    /**
     * C2: a unit saved while under control must not come back permanently locked.
     *
     * <p>The save happens mid-control (the NBT really carries the battle AI lock and the restore record), the
     * old instance leaves the world the way an unload would, and the production load handler is the only thing
     * that touches the reloaded copy.</p>
     */
    private static void savedWhileControlled() throws Exception {
        BattleManager.reset();
        check(BattleManager.start(owner, true), "the save/reload encounter starts");
        battle = BattleManager.current();
        Wolf live = liveWolf(owner, 2, 0, -2);
        check(BattleParticipation.tryAddCompanion(live), "an AI-live wolf is recruited for the save test");
        begin(live);
        GridPos start = battle.currentActorCell(live);
        check(battle.tryMove(owner, battle.stamp(), new GridPos(start.x() + 1, start.y(), start.z())),
                "it walks before the save");
        for (int tick = 0; tick < 200 && battle.isBusy(); tick++) battle.tick();
        GridPos walkedTo = battle.currentActorCell(live);
        live.setInvulnerable(false);
        live.setHealth(live.getHealth() - 5.0F);
        float wounded = live.getHealth();
        UUID id = live.getUUID();

        // The save really happens while the unit is under control.
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        live.save(saved);
        check(saved.getBoolean("NoAI"), "the saved form carries the battle AI lock: this is the risk case");
        check(saved.getBoolean("PersistenceRequired"), "and the battle persistence flag");
        net.minecraft.nbt.CompoundTag data = saved.getCompound(NEOFORGE_DATA);
        check(CompanionRestoreMarker.present(live, data),
                "and the versioned restore record travels with the save");

        // The old instance leaves the world the way an unload would, then the saved form is loaded again.
        live.discard();
        Wolf reloaded = EntityType.WOLF.create(level);
        check(reloaded != null, "the saved wolf is loaded as a new instance");
        reloaded.load(saved);
        check(level.addFreshEntity(reloaded), "and really joins the level, which fires the production handler");
        mobs.add(reloaded);

        check(!reloaded.isNoAi(), "the reloaded wolf has its original AI flag back");
        check(!reloaded.isPersistenceRequired(), "and its original persistence flag");
        check(!CompanionRestoreMarker.present(reloaded, reloaded.getPersistentData()),
                "the restore record is consumed exactly once");
        check(Math.abs(reloaded.getHealth() - wounded) < 0.001F, "its real damage is kept, never healed");
        GridPos afterReload = new GridPos(reloaded.getBlockX(), reloaded.getBlockY(), reloaded.getBlockZ());
        check(afterReload.equals(walkedTo), "its final cell is kept: " + afterReload);
        check(id.equals(reloaded.getUUID()), "the same unit identity comes back");
        check(!battle.isParticipant(reloaded), "the load path never re-admits it into the encounter");

        // The stale record of the old instance is pruned, and the same encounter must not silently re-admit
        // the reloaded unit with a fresh AP pool.
        battle.tick();
        for (int tick = 0; tick < 12; tick++) battle.tick();
        check(BattleManager.ownerOf(id) == null, "no ownership claim survives for that unit");
        check(!battle.isParticipant(reloaded), "and the encounter does not re-recruit it in the same fight");
        check(battle.companionsOf(owner.getUUID()).stream().noneMatch(mob -> mob.getUUID().equals(id)),
                "it is not listed as a companion again");
    }

    /** NeoForge stores an entity's persistent data under this compound key. */
    private static final String NEOFORGE_DATA = "NeoForgeData";

    /** A bow-wielding companion kind: the hand may be ranged, the order is still melee. */
    private static final class BowCompanionAdapter implements CompanionAdapter {
        private final UUID target;

        private BowCompanionAdapter(UUID target) {
            this.target = target;
        }

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("tactical", "fixture_bow_companion");
        }

        @Override
        public boolean supports(Mob mob) {
            return mob.getUUID().equals(target);
        }

        @Override
        public UUID controllerId(Mob mob) {
            return owner.getUUID();
        }

        @Override
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            return mob.level() == level;
        }

        @Override
        public int abilities(Mob mob) {
            return CompanionAbility.all();
        }
    }

    /** An adapter for one mob that declares the end-turn order only. */
    private static final class OnlyEndTurnAdapter implements CompanionAdapter {
        private final UUID target;

        private OnlyEndTurnAdapter(UUID target) {
            this.target = target;
        }

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("tactical", "fixture_end_turn_only");
        }

        @Override
        public boolean supports(Mob mob) {
            return mob.getUUID().equals(target);
        }

        @Override
        public UUID controllerId(Mob mob) {
            return owner.getUUID();
        }

        @Override
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            return mob.level() == level;
        }

        @Override
        public int abilities(Mob mob) {
            return CompanionAbility.END_TURN.mask();
        }
    }

    /** The public participation entry, so the fixture never uses a private path. */
    private static boolean logicAdd(Mob mob) {
        return BattleParticipation.tryAddCompanion(mob);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A test-only companion kind: any cow whose adapter was registered for one controller.
     *
     * <p>It exists to prove the control skeleton is adapter-driven and not wolf-specific. It is registered by
     * the fixture and never by the mod.</p>
     */
    private static final class TestCowCompanionAdapter implements CompanionAdapter {
        private final UUID controller;

        private TestCowCompanionAdapter(UUID controller) {
            this.controller = controller;
        }

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("tactical", "fixture_cow");
        }

        @Override
        public boolean supports(Mob mob) {
            return mob instanceof Cow;
        }

        @Override
        public UUID controllerId(Mob mob) {
            return controller;
        }

        @Override
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            return controllerId != null && controllerId.equals(controller) && mob.level() == level;
        }
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx, int dz) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Wire wire = new Wire();
        wire.channel = new EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer", "skill_state")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + dx + 0.5D, origin.getY(), origin.getZ() + dz + 0.5D);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.setInvulnerable(true);
        level.addNewPlayer(player);
        players.add(player);
        return player;
    }

    private static Wolf wolf(ServerPlayer controller, int dx, int dy, int dz, boolean sitting) {
        Wolf wolf = EntityType.WOLF.create(level);
        check(wolf != null, "a real wolf is created");
        wolf.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        wolf.setNoAi(true);
        wolf.setPersistenceRequired();
        wolf.setInvulnerable(true);
        wolf.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        wolf.setHealth(1000);
        if (controller != null) {
            wolf.setTame(true, false);
            wolf.setOwnerUUID(controller.getUUID());
        }
        if (sitting) wolf.setOrderedToSit(true);
        check(level.addFreshEntity(wolf), "the wolf is registered in the level");
        mobs.add(wolf);
        return wolf;
    }

    /** A wolf created AI-live and non-persistent, so a real flag transition can be observed. */
    private static Wolf liveWolf(ServerPlayer controller, int dx, int dy, int dz) {
        Wolf wolf = EntityType.WOLF.create(level);
        check(wolf != null, "a real wolf is created");
        wolf.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        wolf.setNoAi(false);
        wolf.setInvulnerable(true);
        wolf.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        wolf.setHealth(1000);
        wolf.setTame(true, false);
        wolf.setOwnerUUID(controller.getUUID());
        check(level.addFreshEntity(wolf), "the wolf is registered in the level");
        mobs.add(wolf);
        return wolf;
    }

    /** An adapter whose pose capture throws, for the "nothing is admitted" edge. */
    private static final class ThrowingAdapter implements CompanionAdapter {
        private final UUID target;

        private ThrowingAdapter(UUID target) {
            this.target = target;
        }

        @Override
        public ResourceLocation id() {
            return ResourceLocation.fromNamespaceAndPath("tactical", "fixture_throwing");
        }

        @Override
        public boolean supports(Mob mob) {
            return mob.getUUID().equals(target);
        }

        @Override
        public UUID controllerId(Mob mob) {
            return owner.getUUID();
        }

        @Override
        public boolean canJoin(ServerLevel level, Mob mob, UUID controllerId) {
            return mob.level() == level;
        }

        @Override
        public Object capturePose(Mob mob) {
            throw new IllegalStateException("fixture: pose capture refused");
        }
    }

    /** Any real animal, used where the mob type itself must not be claimed by an earlier adapter. */
    private static <T extends net.minecraft.world.entity.animal.Animal> T animal(EntityType<T> type,
                                                                                ServerPlayer controller,
                                                                                int dx, int dy, int dz) {
        T mob = type.create(level);
        check(mob != null, "a real animal is created");
        mob.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        mob.setHealth(1000);
        check(level.addFreshEntity(mob), "the animal is registered in the level");
        mobs.add(mob);
        return mob;
    }

    private static Cow cow(ServerPlayer controller, int dx, int dy, int dz) {
        Cow cow = EntityType.COW.create(level);
        check(cow != null, "a real cow is created");
        cow.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        cow.setNoAi(true);
        cow.setPersistenceRequired();
        cow.setInvulnerable(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        cow.setHealth(1000);
        check(level.addFreshEntity(cow), "the cow is registered in the level");
        mobs.add(cow);
        return cow;
    }

    private static <T extends Mob> T mob(EntityType<T> type, int dx, int dy, int dz) {
        T mob = type.create(level);
        check(mob != null, "a real hostile is created");
        mob.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        mob.setHealth(1000);
        check(level.addFreshEntity(mob), "the hostile is registered in the level");
        mobs.add(mob);
        return mob;
    }

    /** The last logical snapshot the server attempted to publish to one recipient. */
    private static BattleStatePacket lastCaptured(ServerPlayer player) {
        var packets = com.tactical.neoforge.network.ServerPackets.capturedFor(player.getUUID());
        for (int index = packets.size() - 1; index >= 0; index--) {
            if (packets.get(index) instanceof BattleStatePacket state) return state;
        }
        throw new AssertionError("no snapshot was published to " + player.getGameProfile().getName());
    }

    /** The encounter's accepted-action counter, read the way the snapshot reads it. */
    private static int actionCount() throws Exception {
        Field field = ServerBattle.class.getDeclaredField("actionCount");
        field.setAccessible(true);
        return field.getInt(battle);
    }

    /** The queue's acting id, read the same way the fixture hands a turn over. */
    private static String currentSlot() throws Exception {
        com.tactical.core.combat.TurnScheduler queue =
                (com.tactical.core.combat.TurnScheduler) field(battle, "queue").get(battle);
        return queue.current();
    }

    private static Wolf companionOf(ServerPlayer controller) {
        for (Mob mob : battle.companionsOf(controller.getUUID())) {
            if (mob instanceof Wolf wolf) return wolf;
        }
        return null;
    }

    /** Put the companion on a cell next to the hostile and keep the battle's own cell record honest. */
    private static void moveNextTo(Mob companion, Mob target) throws Exception {
        GridPos targetCell = battle.currentActorCell(target);
        GridPos from = battle.currentActorCell(companion);
        check(targetCell != null && from != null, "both actors have authoritative cells");
        GridPos landing = null;
        for (GridPos candidate : List.of(
                new GridPos(targetCell.x() + 1, targetCell.y(), targetCell.z()),
                new GridPos(targetCell.x() - 1, targetCell.y(), targetCell.z()),
                new GridPos(targetCell.x(), targetCell.y(), targetCell.z() + 1),
                new GridPos(targetCell.x(), targetCell.y(), targetCell.z() - 1))) {
            if (LevelGridWorld.isStandable(level, candidate.x(), candidate.y(), candidate.z())) {
                landing = candidate;
                break;
            }
        }
        check(landing != null, "a standable cell beside the hostile exists");
        companion.teleportTo(landing.x() + 0.5D, landing.y(), landing.z() + 0.5D);
        // The fixture places the body directly, so the authoritative cell is set through the same field the
        // production movement completion writes.
        Object state = ((java.util.Map<?, ?>) field(battle, "enemies").get(battle)).get(companion.getUUID());
        check(state != null, "the companion has a battle record");
        Field cell = state.getClass().getDeclaredField("cell");
        cell.setAccessible(true);
        cell.set(state, landing);
        check(battle.currentActorCell(companion).equals(landing), "the fixture move is reflected in the battle cell");
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Hand the turn to one actor exactly as the ATB scheduler would. */
    private static void begin(net.minecraft.world.entity.LivingEntity actor) throws Exception {
        com.tactical.core.combat.TurnScheduler queue =
                (com.tactical.core.combat.TurnScheduler) field(battle, "queue").get(battle);
        field(queue, "current").set(queue, actor.getUUID().toString());
        var method = ServerBattle.class.getDeclaredMethod("beginTurn", String.class);
        method.setAccessible(true);
        method.invoke(battle, actor.getUUID().toString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void pass(MinecraftServer server) {
        Tactical.LOGGER.info("[Tactical] COMPANION SELFTEST PASSED: adapter-driven recruitment (standing tame "
                + "wolf only, never sitting/wild/foreign), two owners with exclusive companions, a non-wolf "
                + "adapter on the same skeleton, foreign/stale/wrong-session orders refused with no charge, real "
                + "companion movement and explicit-target melee that never auto-attacks or ends the turn, the "
                + "shared 25 AP guard halving real damage and clearing at the next own turn, idempotent "
                + "release on owner exit, death and encounter end, an adapter that declares only end-turn "
                + "enforcing its own order set on every formal entry, and a wolf saved while under control "
                + "coming back with its own AI/persistence flags and no fresh admission");
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        BattleManager.setAutoTrigger(true);
        if (configured) {
            TacticalConfig.INSTANCE.participantCap.set(oldCap);
            configured = false;
        }
        com.tactical.neoforge.network.ServerPackets.stopCapture();
        CompanionRegistry.clearForTests();
        com.tactical.neoforge.registry.companion.CompanionRegistry.registerDefaults();
        for (Mob mob : mobs) mob.discard();
        mobs.clear();
        for (ServerPlayer player : players) {
            if (!player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        players.clear();
        for (BlockPos chunk : chunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        chunks.clear();
        server.halt(false);
    }

    private static final class Wire extends Connection {
        private EmbeddedChannel channel;

        private Wire() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            // Only the transport is observed; nothing in this fixture reads the payload bodies.
            if (packet instanceof ClientboundCustomPayloadPacket) channel.flush();
        }
    }
}
