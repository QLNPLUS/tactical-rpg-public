package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.network.BattleStatePacket;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.Cod;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opt-in real dedicated-server fixture for cap-refused isolation: {@code -Dtactical.selftest=isolation}.
 *
 * <p>Covers the whole exclusion contract on real server ticks: a refused mob leaves the battlefield
 * and cannot lock back on while the roster is full, hostility is irrelevant for physical contact
 * (a passive mob may not displace a member either), the body box - not the feet cell - must clear
 * every battlefield, Brain memories are re-cleaned continuously, a freed slot releases the
 * suppression and restores ordinary refill, a second encounter and an unrelated player keep their
 * state, and a member leaving or changing dimension is handled.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class IsolationSelfTest {

    private static final List<Entity> entities = new ArrayList<>();
    private static final List<RecordingConnection> wires = new ArrayList<>();
    private static final List<BlockPos> chunks = new ArrayList<>();

    private static boolean enabled, configured;
    private static int ticks, stage, since, oldCap;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner, other, guest;
    private static Zombie e1, e2, intruder, oE1, oE2, oIntruder, unrelated, contact, extra;
    private static ZombifiedPiglin angered;
    private static Piglin brain;
    private static Phantom phantom;
    private static Ghast ghast;
    private static Cow cow;
    private static ServerBattle encounter, second;

    private static String phase;
    private static int ap;
    private static double ownerX, ownerZ, ownerHealth, phantomY;
    private static double hostilePushMax, passivePushMax, releasedPushMax;
    private static int hostilePushSamples, passivePushSamples, releasedPushSamples;
    private static int contactWindow, step, window;
    private static final Map<UUID, Vec3> secondPositions = new LinkedHashMap<>();
    private static final Map<UUID, Entity> secondMobs = new LinkedHashMap<>();
    private static final Map<UUID, Entity> secondTargets = new LinkedHashMap<>();

    private IsolationSelfTest() {}

    @SubscribeEvent public static void onStarted(ServerStartedEvent event) {
        enabled = "isolation".equals(System.getProperty("tactical.selftest"));
        ticks = stage = since = 0;
    }

    /**
     * Physical measurement point: immediately after a contact mob's own tick, i.e. after
     * {@code LivingEntity.pushEntities} has run for it and before the encounter pins the player.
     */
    @SubscribeEvent
    public static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!enabled || owner == null || contactWindow <= 0) return;
        if (event.getEntity() == contact && contact.isAlive() && !contact.isRemoved()) {
            hostilePushSamples++;
            hostilePushMax = Math.max(hostilePushMax, owner.getDeltaMovement().lengthSqr());
        } else if (event.getEntity() == cow && cow != null && !cow.isRemoved()) {
            if (window == 2) { passivePushSamples++; passivePushMax = Math.max(passivePushMax, owner.getDeltaMovement().lengthSqr()); }
            else { releasedPushSamples++; releasedPushMax = Math.max(releasedPushMax, owner.getDeltaMovement().lengthSqr()); }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            check(ticks < 900, "fixture completes before real-tick deadline");
            if (ticks == 20) setup(event.getServer());
            if (ticks < 40) return;
            AABB loaded = new AABB(origin).inflate(140);
            if (stage == 0 && !level.getEntitiesOfClass(Mob.class, loaded).containsAll(
                    List.of(e1, e2, intruder, angered, brain, phantom, ghast, oE1, oE2, oIntruder, unrelated))) return;
            run();
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] ISOLATION SELFTEST FAILED at tick {} stage {} window {}", ticks, stage, window, failure);
            finish(event.getServer());
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    private static void setup(MinecraftServer server) {
        BattleManager.reset();
        BattleManager.setAutoTrigger(false);
        oldCap = TacticalConfig.INSTANCE.participantCap.get();
        configured = true;
        TacticalConfig.INSTANCE.participantCap.set(2);
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos((spawn.getX() >> 4) * 16 + 15,
                Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 20), spawn.getZ());
        for (int cx = (origin.getX() - 120) >> 4; cx <= (origin.getX() + 120) >> 4; cx++) {
            for (int cz = (origin.getZ() - 40) >> 4; cz <= (origin.getZ() + 40) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) chunks.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -120; x <= 120; x++) for (int z = -36; z <= 36; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 10; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "IsolationOwner", 0);
        other = player(server, "IsolationOther", -62);
        guest = player(server, "IsolationGuest", 3);
        e1 = spawn(EntityType.ZOMBIE, 5, 0);
        e2 = spawn(EntityType.ZOMBIE, 6, 0);
        intruder = spawn(EntityType.ZOMBIE, 11, 0);
        angered = spawn(EntityType.ZOMBIFIED_PIGLIN, -9, 0);
        brain = spawn(EntityType.PIGLIN, -12, 0);
        phantom = spawn(EntityType.PHANTOM, 7, 4);
        phantom.setNoGravity(true);
        // Wide flying body: its feet cell is outside the zone long before its 4-block box is.
        ghast = spawn(EntityType.GHAST, -16, 4);
        ghast.setNoGravity(true);
        unrelated = spawn(EntityType.ZOMBIE, 20, 0);
        oE1 = spawn(EntityType.ZOMBIE, -62 - 5, 0);
        oE2 = spawn(EntityType.ZOMBIE, -62 - 8, 0);
        oIntruder = spawn(EntityType.ZOMBIE, -62 - 11, 0);
        unrelated.setNoAi(true); // freeze its own AI so the assertion is about isolation only
    }

    private static void run() {
        if (stage == 0) {
            e1.setTarget(owner); e2.setTarget(owner); intruder.setTarget(owner);
            angered.setTarget(owner);
            angered.setPersistentAngerTarget(owner.getUUID());
            angered.startPersistentAngerTimer();
            brain.setTarget(owner);
            brain.getBrain().setMemory(MemoryModuleType.ANGRY_AT, owner.getUUID());
            brain.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, owner);
            phantom.setTarget(owner);
            ghast.setTarget(owner);
            unrelated.setTarget(other);
            oE1.setTarget(other); oE2.setTarget(other); oIntruder.setTarget(other);

            check(BattleManager.start(owner), "first encounter starts from a real aggro target");
            check(BattleManager.start(other), "second encounter starts in the same dimension");
            encounter = BattleManager.findFor(owner.getUUID());
            second = BattleManager.findFor(other.getUUID());
            check(encounter != null && second != null && encounter != second, "two independent encounters registered");
            check(encounter.enemyCount() == 2 && second.enemyCount() == 2, "both encounters fill their cap of two");
            check(encounter.isParticipant(e1) && encounter.isParticipant(e2),
                    "the two nearest hostiles are the ones admitted");
            check(!encounter.isParticipant(intruder) && !encounter.isParticipant(angered)
                    && !encounter.isParticipant(brain) && !encounter.isParticipant(phantom)
                    && !encounter.isParticipant(ghast), "every other mob is refused by the cap at deployment");
            check(encounter.zone().bounds().maxX() < second.zone().bounds().minX()
                            || second.zone().bounds().maxX() < encounter.zone().bounds().minX(),
                    "the two battlefields do not overlap");

            phase = encounter.phaseName();
            ap = encounter.currentAp();
            ownerX = owner.getX(); ownerZ = owner.getZ();
            ownerHealth = owner.getHealth();
            phantomY = phantom.getY();
            secondPositions.put(oE1.getUUID(), oE1.position());
            secondPositions.put(oE2.getUUID(), oE2.position());
            secondMobs.put(oE1.getUUID(), oE1);
            secondMobs.put(oE2.getUUID(), oE2);
            secondTargets.put(oE1.getUUID(), oE1.getTarget());
            secondTargets.put(oE2.getUUID(), oE2.getTarget());
            next();
        } else if (stage == 1) {
            if (ticks - since < 25) return;
            check(encounter.hostileAtCap(), "first encounter stays full during the observation window");
            // 1) the refused attacker left and cannot keep its relationship with this encounter
            check(!encounter.isParticipant(intruder), "cap-refused attacker never joins the roster");
            check(!intruder.isNoAi(), "isolation never freezes the refused mob's AI");
            check(intruder.getTarget() == null, "refused attacker lost its target");
            check(encounter.holdsOutside(intruder), "refused attacker is held outside by this encounter");
            GridPos intruderCell = cellOf(intruder);
            check(!encounter.zone().contains(intruderCell), "refused attacker left the battlefield");
            check(bodyOutsideEveryZone(intruder), "evicted body overlaps no active battlefield");
            // 2) a neutral-anger and a Brain representative lose their relationship too
            check(!angered.isNoAi() && angered.getTarget() == null, "anger representative lost its target");
            check(angered.getPersistentAngerTarget() == null, "neutral-mob persistent anger cleared");
            check(brain.getTarget() == null, "Brain representative lost its target");
            check(brain.getBrain().getMemoryInternal(MemoryModuleType.ANGRY_AT).isEmpty(), "Brain ANGRY_AT memory erased");
            check(brain.getBrain().getMemoryInternal(MemoryModuleType.ATTACK_TARGET).isEmpty(),
                    "Brain ATTACK_TARGET memory erased");
            check(!encounter.zone().contains(cellOf(brain)), "Brain representative left the battlefield");
            // 3) flying representatives leave without falling or losing altitude bounds
            check(!encounter.zone().contains(cellOf(phantom)), "flying representative left the battlefield");
            check(phantom.isAlive() && Math.abs(phantom.getY() - phantomY) <= 8.0D,
                    "flying eviction preserves a bounded altitude");
            check(AerialMovement.isClearAir(level, phantom, phantom.position()),
                    "flying destination is clear air, not a wall or liquid");
            // 4) the wide body, not only the feet cell, must clear every battlefield
            check(!ghast.isRemoved() && ghast.isAlive(), "wide representative survived eviction");
            if (encounter.zone().contains(cellOf(ghast)) || !bodyOutsideEveryZone(ghast)) {
                // A failed placement is suppressed and retried (bounded), so a wide body may need
                // one retry cycle before it is actually out. Assert the bound, not the first tick.
                check(ticks - since <= 70, "wide representative leaves within the bounded retry window (pos="
                        + ghast.position() + " held=" + encounter.holdsOutside(ghast)
                        + " stance=" + BattleDeployment.stanceOf(ghast) + ")");
                return;
            }
            check(bodyOutsideEveryZone(ghast), "wide body box overlaps no active battlefield");
            wideBodyRegression();
            aquaticPlacementRegression();
            // 5) an unrelated player's aggro survives this encounter's cleanup
            check(unrelated.getTarget() == other, "unrelated player's aggro is not cleared");
            check(!encounter.zone().contains(cellOf(unrelated)), "unrelated aggro carrier was evicted from the battlefield");
            // 6) participants never moved and took no damage
            check(owner.getHealth() == ownerHealth, "participant HP unchanged");
            check(Math.abs(owner.getX() - ownerX) < 0.05D && Math.abs(owner.getZ() - ownerZ) < 0.05D,
                    "participant position unchanged while full");
            check(encounter.phaseName().equals(phase) && encounter.currentAp() == ap, "isolation never resets turn/AP");
            // 7) the other encounter is untouched by this one
            check(second.enemyCount() == 2, "second encounter keeps its roster");
            check(e1.isAlive() && encounter.isParticipant(e1), "first participant still admitted");
            check(!encounter.holdsOutside(oE1) && !encounter.holdsOutside(oE2) && !encounter.holdsOutside(oIntruder),
                    "first encounter never holds the other encounter's units");
            check(second.holdsOutside(oIntruder), "the second encounter isolates its own intruder");
            check(!intruder.isRemoved() && !oIntruder.isRemoved(), "no evicted unit was removed");
            contact = spawn(EntityType.ZOMBIE, 2, 0);
            keepContactAdjacent();
            hostilePushMax = 0;
            hostilePushSamples = 0;
            contactWindow = 8;
            next();
            window = 1;
        } else if (stage == 2) {
            if (window == 1) {
                // Hostile contact window: the mob is kept overlapping the player so its own
                // pushEntities runs every tick; the sample is taken right after that tick.
                if (contactWindow > 0) { keepContactAdjacent(); contactWindow--; return; }
                check(hostilePushSamples >= 2, "hostile contact produced real-tick samples");
                check(hostilePushMax == 0.0D, "hostile contact never displaced the participant");
                intruder.setTarget(owner);
                window = 3;
                since = ticks;
                return;
            }
            if (window == 3) {
                if (ticks - since < 3) return;
                check(intruder.getTarget() == null, "refused attacker cannot lock back on while the roster is full");
                check(encounter.holdsOutside(intruder), "suppression persists while the roster stays full");
                // Passive contact window: a mob that is not an Enemy must not displace a member
                // either. Hostility is not what the collision rule keys on.
                cow = spawn(EntityType.COW, 2, 0);
                keepCowAdjacent();
                passivePushMax = 0;
                passivePushSamples = 0;
                contactWindow = 8;
                window = 2;
                return;
            }
            if (window == 2) {
                if (contactWindow > 0) { keepCowAdjacent(); contactWindow--; return; }
                check(passivePushSamples >= 2, "passive contact produced real-tick samples");
                check(passivePushMax == 0.0D, "passive contact never displaced the participant while full");
                // Deterministic pair checks through the vanilla entry point.
                contact.teleportTo(owner.getX() + 0.5D, owner.getY(), owner.getZ());
                owner.setDeltaMovement(Vec3.ZERO);
                owner.push(contact);
                check(owner.getDeltaMovement().lengthSqr() == 0.0D, "participant is not pushed by a held-out mob");
                contact.setDeltaMovement(Vec3.ZERO);
                contact.push(owner);
                check(contact.getDeltaMovement().lengthSqr() == 0.0D, "held-out mob is not pushed by a participant");
                owner.setDeltaMovement(Vec3.ZERO);
                owner.push(cow);
                check(owner.getDeltaMovement().lengthSqr() == 0.0D,
                        "participant is not pushed by a passive non-participant either");
                owner.setDeltaMovement(Vec3.ZERO);
                other.setDeltaMovement(Vec3.ZERO);
                owner.push(other);
                check(owner.getDeltaMovement().lengthSqr() > 0.0D,
                        "collision between two players is untouched: the guard is narrow");
                owner.setDeltaMovement(Vec3.ZERO);
                other.setDeltaMovement(Vec3.ZERO);
                cow.discard();
                contact.discard();
                // A removed held unit must not stay suppressed.
                angered.discard();
                window = 4;
                since = ticks;
                return;
            }
            if (ticks - since < 2) return;
            check(!encounter.holdsOutside(angered),
                    "a removed held unit is dropped from the isolation state (held=" + encounter.heldCount()
                            + " removed=" + angered.isRemoved() + " alive=" + angered.isAlive()
                            + " owner=" + BattleManager.ownerOf(angered.getUUID()) + ")");
            // Continuous maintenance: a Brain mob that writes a memory again, without ever touching
            // Mob.setTarget, must be cleaned while the roster is still full.
            brain.getBrain().setMemory(MemoryModuleType.ANGRY_AT, owner.getUUID());
            brain.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, owner);
            next();
        } else if (stage == 3) {
            if (ticks - since < 12) return;
            check(encounter.hostileAtCap(), "roster stayed full while the Brain memory was rewritten");
            check(brain.getTarget() == null, "rewritten Brain memory did not restore a target");
            check(brain.getBrain().getMemoryInternal(MemoryModuleType.ANGRY_AT).isEmpty(),
                    "Brain ANGRY_AT rewritten without setTarget is cleaned again");
            check(brain.getBrain().getMemoryInternal(MemoryModuleType.ATTACK_TARGET).isEmpty(),
                    "Brain ATTACK_TARGET rewritten without setTarget is cleaned again");
            e1.setHealth(0);
            e1.discard();
            next();
        } else if (stage == 4) {
            if (ticks - since < 2) return;
            check(encounter.hostileCount() < encounter.hostileCapacity(), "a dead participant frees a hostile slot");
            check(!encounter.holdsOutside(intruder), "suppression is released as soon as a slot frees");
            // Released relationship: the same passive mob that could not push while full must push
            // again now that the encounter is not full. This is the control for "not globally off".
            cow = spawn(EntityType.COW, 2, 0);
            keepCowAdjacent();
            releasedPushMax = 0;
            releasedPushSamples = 0;
            contactWindow = 8;
            next();
            window = 6;
        } else if (stage == 5) {
            if (window == 6) {
                if (contactWindow > 0) { keepCowAdjacent(); contactWindow--; return; }
                check(releasedPushSamples >= 2, "released contact produced real-tick samples");
                check(releasedPushMax > 0.0D, "with a free slot a contact mob pushes again: the guard is cap-scoped");
                cow.discard();
                intruder.setTarget(owner);
                window = 7;
                since = ticks;
                return;
            }
            if (window == 7) {
                check(intruder.getTarget() == owner || encounter.isParticipant(intruder),
                        "released mob may re-acquire the participant");
                if (!encounter.isParticipant(intruder)) {
                    check(ticks - since <= 25, "ordinary refill absorbs the released mob within 25 ticks");
                    return;
                }
                check(encounter.enemyCount() == 2, "released mob refills the freed slot");
                check(encounter.phaseName().equals(phase) && encounter.currentAp() == ap,
                        "refill does not reset turn/AP");
                check(!encounter.holdsOutside(intruder), "an admitted unit is no longer held outside");
                check(second.enemyCount() == 2 && !second.finished(), "second encounter is still running");
                for (Map.Entry<UUID, Vec3> entry : secondPositions.entrySet()) {
                    Entity mob = secondMobs.get(entry.getKey());
                    check(mob != null && !mob.isRemoved(), "second encounter unit still exists");
                    check(mob.position().distanceToSqr(entry.getValue()) < 0.01D, "second encounter unit did not move");
                    check(mob instanceof Mob unit && unit.getTarget() == secondTargets.get(entry.getKey()),
                            "second encounter target unchanged");
                }
                // A second real member leaves through the membership boundary (leave, not disconnect).
                BattleEligibility.Denial denial = encounter.joinDirect(guest);
                check(denial == BattleEligibility.Denial.NONE && encounter.memberCount() == 2,
                        "a second real member joins (denial " + denial + " guestPos=" + guest.position()
                                + " cell=" + cellOf(guest) + " zoneContains=" + encounter.zone().contains(cellOf(guest))
                                + " sameLevel=" + (guest.level() == level) + " center=" + encounter.zone().center()
                                + " radius=" + encounter.zone().radius() + ")");
                window = 8;
                since = ticks;
                return;
            }
            if (window == 8) {
                // A membership boundary must not disturb the exclusion state, so put a fresh
                // suppressed unit in place first: after the refill the roster is full again and a
                // newly arriving intruder is held out by the ordinary scan.
                if (extra == null) {
                    extra = spawn(EntityType.ZOMBIE, 8, 0);
                    extra.setTarget(owner);
                    since = ticks;
                }
                if (!encounter.holdsOutside(extra)) {
                    check(ticks - since <= 25, "a fresh intruder is isolated before the boundary test");
                    return;
                }
                String before = heldSummary();
                check(BattleManager.leave(guest, "selftest leave"), "the second member leaves through the real API");
                check(encounter.memberCount() == 1 && !encounter.finished(), "leaving one member keeps the encounter");
                check(heldSummary().equals(before), "a member leaving does not drop the remaining member's isolation");
                // Real dimension change of the last member: the encounter ends and its state is dropped.
                ServerLevel nether = level.getServer().getLevel(Level.NETHER);
                check(nether != null, "the nether level is available for a real dimension change");
                owner.teleportTo(nether, 0.5D, 70.0D, 0.5D, 0.0F, 0.0F);
                window = 9;
                since = ticks;
                return;
            }
            if (window == 9) {
                if (ticks - since < 3) return;
                check(owner.level() != level, "the member really changed dimension");
                check(encounter.finished() || encounter.memberCount() == 0,
                        "a dimension change of the last member ends the encounter");
                check(!encounter.holdsOutside(extra) && !encounter.holdsOutside(brain)
                                && !encounter.holdsOutside(phantom) && !encounter.holdsOutside(ghast),
                        "ending the encounter drops every held unit");
                check(!second.finished() && second.enemyCount() == 2, "the other encounter survives the first one's end");
                Tactical.LOGGER.info("[Tactical] ISOLATION SELFTEST PASSED: cap-refused mobs leave the battlefield by body box, lose target/anger/Brain memories and keep losing them while full, cannot re-lock, are not frozen or removed, hostile AND passive contact produce zero participant velocity on real ticks, a free slot releases suppression and restores pushing plus ordinary refill without AP/phase reset, two players still collide, a second encounter and an unrelated player keep their aggro and positions, wide flying bodies clear every battlefield, a member leaving keeps the encounter's state and a real dimension change ends it");
                finish(level.getServer());
            }
        }
    }

    /** A candidate body must never be tested as a bare cell. */
    private static void wideBodyRegression() {
        // 1) the predicate really receives the moved body, not the feet cell
        final AABB[] seen = new AABB[1];
        BattleDeployment.findOutsideStandable(level, ghast, encounter.zone().center(),
                encounter.zone().radius(), box -> { if (seen[0] == null) seen[0] = box; return false; });
        check(seen[0] != null, "the forbidden predicate is evaluated at all");
        check(Math.abs(seen[0].getXsize() - ghast.getBoundingBox().getXsize()) < 0.001D,
                "the forbidden predicate receives the body box, not a cell");
        // 2) a feet cell just outside the bounds, whose body still reaches in, IS a conflict -
        //    exactly the case a cell-only test used to miss.
        double justOutside = encounter.zone().bounds().maxX() + 1.0D;
        Vec3 feet = new Vec3(justOutside, ghast.getY(), encounter.zone().center().z() + 0.5D);
        GridPos cell = new GridPos(Mth.floor(feet.x), Mth.floor(feet.y), Mth.floor(feet.z));
        check(!encounter.zone().contains(cell), "the regression cell itself is outside the zone");
        check(BattleDeployment.overlapsZone(BattleDeployment.boundsAt(ghast, feet), encounter.zone().bounds()),
                "a wide body at that cell still overlaps the battlefield");
    }

    /**
     * The aquatic rule of the outside-placement helper, on a real level: no safe water means no
     * destination (never dry land), and once real water exists the destination is water at feet and
     * head. The search radius is kept small so the whole ring lies on this fixture's stone pad,
     * which makes "no water in range" a property of the fixture rather than of the world.
     */
    private static void aquaticPlacementRegression() {
        Cod fish = spawn(EntityType.COD, 60, 0);
        fish.setNoAi(true);
        int radius = 6;
        java.util.function.Predicate<AABB> otherZonesOnly =
                box -> BattleManager.insideAnyZone(level, box, encounter);
        check(BattleDeployment.findOutsideStandable(level, fish, encounter.zone().center(), radius, otherZonesOnly).isEmpty(),
                "a swimmer gets no destination while no safe water exists");
        int ox = encounter.zone().center().x() + 9;
        int oz = encounter.zone().center().z();
        int oy = origin.getY();
        // A walled basin, not a loose water cube: open water spreads several blocks and would turn
        // unrelated cells into fluid, which would invalidate every later standability assertion.
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    boolean wall = dx == -1 || dx == 5 || dz == -3 || dz == 3;
                    BlockPos pos = new BlockPos(ox + dx, oy + dy, oz + dz);
                    level.setBlock(pos, wall ? Blocks.STONE.defaultBlockState()
                            : (dy < 2 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState()), 3);
                }
            }
        }
        var wet = BattleDeployment.findOutsideStandable(level, fish, encounter.zone().center(), radius, otherZonesOnly);
        check(wet.isPresent(), "a swimmer finds a destination once safe water exists");
        Vec3 feet = wet.get();
        check(level.getFluidState(BlockPos.containing(feet)).is(net.minecraft.tags.FluidTags.WATER)
                        && level.getFluidState(BlockPos.containing(feet.add(0, 1, 0))).is(net.minecraft.tags.FluidTags.WATER),
                "the swimmer's destination is water at feet and head, never dry land");
        Tactical.LOGGER.info("[Tactical] aquatic placement check: dry search empty, wet destination {} in water", feet);
        // Put the arena back exactly as it was, so the remaining assertions see one flat pad.
        for (int dx = -1; dx <= 5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(new BlockPos(ox + dx, oy + dy, oz + dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        fish.discard();
    }

    private static String heldSummary() {
        return encounter.holdsOutside(extra) + "/" + encounter.holdsOutside(brain) + "/"
                + encounter.holdsOutside(phantom) + "/" + encounter.holdsOutside(intruder)
                + "/" + encounter.holdsOutside(ghast);
    }

    private static boolean bodyOutsideEveryZone(Entity entity) {
        AABB box = entity.getBoundingBox();
        for (ServerBattle battle : BattleManager.active()) {
            if (BattleDeployment.overlapsZone(box, battle.zone().bounds())) return false;
        }
        return true;
    }

    private static void keepContactAdjacent() {
        // Overlapping the player's own bounding box, otherwise vanilla pushEntities finds nothing
        // to push and the measurement would be vacuous.
        contact.teleportTo(owner.getX() + 0.5D, owner.getY(), owner.getZ());
        contact.setDeltaMovement(Vec3.ZERO);
    }

    private static void keepCowAdjacent() {
        cow.teleportTo(owner.getX() + 0.5D, owner.getY(), owner.getZ());
        cow.setDeltaMovement(Vec3.ZERO);
    }

    private static GridPos cellOf(Entity entity) {
        return new GridPos(Mth.floor(entity.getX()), Mth.floor(entity.getY()), Mth.floor(entity.getZ()));
    }

    private static void next() { stage++; since = ticks; step = 0; window = 0; }

    private static <T extends Mob> T spawn(EntityType<T> type, int dx, int dy) {
        T mob = type.create(level);
        check(mob != null, "actual mob created");
        mob.setPos(origin.getX() + dx + .5, origin.getY() + dy, origin.getZ() + .5);
        mob.setPersistenceRequired();
        mob.setNoAi(false);
        if (mob.getAttribute(Attributes.MOVEMENT_SPEED) != null) mob.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        mob.setInvulnerable(true);
        check(level.addFreshEntity(mob), "actual mob registered");
        entities.add(mob);
        return mob;
    }

    private static ServerPlayer player(MinecraftServer server, String name, int dx) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        RecordingConnection wire = new RecordingConnection();
        wire.channel = new EmbeddedChannel(wire);
        wire.player = player;
        wires.add(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph", "move_choice_offer")) {
            ChannelAttributes.getOrCreateAdHocChannels(wire).add(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + dx + .5, origin.getY(), origin.getZ() + .5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.setInvulnerable(true);
        level.addNewPlayer(player);
        entities.add(player);
        return player;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        BattleManager.setAutoTrigger(true);
        if (configured) TacticalConfig.INSTANCE.participantCap.set(oldCap);
        configured = false;
        if (owner != null) FleeProtection.remove(owner);
        if (other != null) FleeProtection.remove(other);
        if (guest != null) FleeProtection.remove(guest);
        for (Entity entity : entities) {
            if (entity instanceof ServerPlayer player && !player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            else entity.discard();
        }
        for (RecordingConnection wire : wires) if (wire.channel != null) wire.channel.finishAndReleaseAll();
        if (level != null) for (BlockPos chunk : chunks) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        entities.clear(); wires.clear(); chunks.clear();
        server.halt(false);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class RecordingConnection extends Connection {
        private EmbeddedChannel channel;
        private ServerPlayer player;
        private final List<BattleStatePacket> states = new ArrayList<>();
        private RecordingConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof BattleStatePacket state) states.add(state);
        }
    }
}
