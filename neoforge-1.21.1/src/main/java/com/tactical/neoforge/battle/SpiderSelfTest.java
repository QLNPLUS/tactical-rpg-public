package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.AiAction;
import com.tactical.neoforge.battle.ai.AiContext;
import com.tactical.neoforge.battle.ai.GenericMeleeAi;
import com.tactical.neoforge.battle.ai.SpiderTacticalAi;
import com.tactical.neoforge.battle.ai.TacticalAiRegistry;
import com.tactical.neoforge.network.BattleLogPacket;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.Difficulty;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.CaveSpider;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real loaded-server fixture for the spider and the cave spider: {@code -Dtactical.selftest=spider}.
 *
 * <p>Every reading comes from a booted dedicated server: a real {@link ServerLevel}, real vanilla entities and
 * the encounter's own admission, surface movement, leap, fall and melee paths. The stages follow the frozen
 * groups - the two exact types and their real bodies (S1), the wall surface and its cost (S2), the wide and
 * narrow body (S3), a surface that disappears under a route (S4), the real attack box (S7), one bounded short
 * leap with its own price and every lifecycle exit (S5/S6), and the native cave-spider poison with its
 * own-turn clock (S8).</p>
 *
 * <p>Two kinds of waiting are used on purpose. A synchronous run of {@code battle.tick()} proves the
 * encounter's own decisions; a stage that ends in {@link #await} lets <em>real server ticks</em> pass without
 * advancing the encounter, so a held body really is asked what vanilla would do to it between the two turns.
 * The support-loss stage uses both: the fall is only believed when the body really descends over real ticks.</p>
 *
 * <p>No client is launched and no user world is touched: the run directory is its own (see
 * {@code serverSpiderTest} in {@code build.gradle}). A failure halts the server with a non-zero code, so a green
 * Gradle task can never look like a passing fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SpiderSelfTest {

    /** The first stage that measures damage, so the spawn-invulnerability window is waited out before it. */
    private static final int FIRST_BATTLE_STAGE = 7;

    private static boolean enabled;
    private static int ticks;
    private static int checks;
    private static int stage;
    private static final List<Runnable> STAGES = List.of(
            SpiderSelfTest::exactTypesAndBodies,
            SpiderSelfTest::wallSurfaceGeometry,
            SpiderSelfTest::wideAndNarrowBodies,
            SpiderSelfTest::ledgeAndMantle,
            SpiderSelfTest::occupancyAndBounds,
            SpiderSelfTest::attackBoxRefusesOneLevelUp,
            SpiderSelfTest::boundedApproachPolicy,
            SpiderSelfTest::wideSpiderClimbsAndMantles,
            SpiderSelfTest::wideSpiderFallsWhenSupportBreaks,
            SpiderSelfTest::wideSpiderFallThatCannotSettle,
            SpiderSelfTest::wideSpiderFallOntoItsOwnFeetRefused,
            SpiderSelfTest::caveSpiderClimbsAndBites,
            SpiderSelfTest::routeRefundAndRelease,
            SpiderSelfTest::leapArcGates,
            SpiderSelfTest::leapCurveAndRangeGates,
            SpiderSelfTest::spiderLeapsOverAPlatformEdgeAndBites,
            SpiderSelfTest::spiderLeapRefusedFreeBeforeLift,
            SpiderSelfTest::spiderLeapRefusedWhenSupportIsGone,
            SpiderSelfTest::spiderLeapRefusedWhenTheRestOfArcIsSealed,
            SpiderSelfTest::spiderLeapRefusedWhenPoolCannotPay,
            SpiderSelfTest::spiderLeapUnpayableIsFree,
            SpiderSelfTest::spiderLeapStopsWhenTheArcIsBlocked,
            SpiderSelfTest::spiderLeapLandsWithoutABite,
            SpiderSelfTest::spiderLeapKeepsFlyingAfterTargetLoss,
            SpiderSelfTest::spiderLeapFinishedMidAir,
            SpiderSelfTest::caveSpiderPoisonsOnNormal,
            SpiderSelfTest::caveSpiderPoisonEasyAndHard,
            SpiderSelfTest::caveSpiderPoisonCancelAndImmunity,
            SpiderSelfTest::poisonClockAndIsolation,
            SpiderSelfTest::caveSpiderWalksALowCeilingCorridor,
            SpiderSelfTest::wideSpiderWalksALowCeilingCorridor);
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Spider spider;
    private static CaveSpider caveSpider;
    private static Zombie zombie;
    private static Skeleton skeleton;
    private static Cow bystander;
    private static Cow squatter;
    /** A body placed at an exact fractional position, to make a specific real feet position illegal. */
    private static Entity blocker;
    /** Tracked, never per-tick asserted: how far a refused landing ever slid, and how low it ever got. */
    private static double refusedDrift;
    private static double refusedLowestY;
    private static ServerBattle battle;
    private static Wire wire;
    private static int defaultZoneHeight = -1;
    /** The first tick at which the fixture's own player can really be damaged (spawn invulnerability over). */
    private static int damageReadyTick;
    private static final List<Entity> spawned = new ArrayList<>();

    /** Real server ticks still to let pass before the next continuation runs. */
    private static int waitTicks;
    /** The continuation that runs after those ticks, so an observation can span real entity ticks. */
    private static Runnable afterWait;
    /** Fixture lever: refuse the native damage of a cave spider's own bite, to prove no poison is added. */
    private static boolean cancelNativeBite;
    /** The authoritative damage of the last real cave-spider bite, after every modifier. */
    private static float biteDamage = -1.0F;
    /** How many real native bites of the cave spider landed on the fixture's own player. */
    private static int biteCount;
    /** Poison pulses that really landed on the fixture's own player (unattributed magic damage). */
    private static int poisonPulseCount;
    private static float poisonPulseDamage;
    /** What the last real cave-spider bite put on the fixture's own player: duration, amplifier and source. */
    private static int poisonTicks = -1;
    private static int poisonAmplifier = -1;
    private static Entity poisonSource;
    /** The leap price this run really ships with, restored by the one case that raises it. */
    private static int defaultLeapCost = -1;
    /** A second real member, used only by the case that loses its target while the body is in the air. */
    private static net.neoforged.neoforge.common.util.FakePlayer guest;

    private SpiderSelfTest() { }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        enabled = "spider".equals(System.getProperty("tactical.selftest"));
        ticks = 0;
        checks = 0;
        stage = 0;
        waitTicks = 0;
        afterWait = null;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        try {
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }
            if (afterWait != null) {
                Runnable next = afterWait;
                afterWait = null;
                next.run();
                return;
            }
            if (++ticks == 20) {
                setup(event.getServer());
                return;
            }
            if (ticks < 40) return;
            // The stages that measure native damage wait quietly - without a check per tick - until vanilla's
            // spawn-invulnerability window for the fixture's own player has passed.
            if (stage >= FIRST_BATTLE_STAGE && ticks < damageReadyTick) return;
            // One stage per server tick: a body that was moved or spawned in a stage is really registered in
            // the level's entity sections before the next stage asks the world about it.
            if (stage < STAGES.size()) {
                check(ticks < 3000, "fixture deadline");
                STAGES.get(stage++).run();
                return;
            }
            Tactical.LOGGER.info("[Tactical] SPIDER SELFTEST PASSED: {} checks on the two exact types, the wall "
                    + "surface, the wide/narrow body, the bounded approach, a broken surface with a real fall, the "
                    + "real attack box, one bounded short leap with its own price and lifecycle, and the native "
                    + "cave-spider poison with its own-turn clock", checks);
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SPIDER SELFTEST FAILED after {} checks", checks, failure);
            finish(event.getServer());
            Runtime.getRuntime().halt(1);
            throw new IllegalStateException(failure);
        }
    }

    /** Let {@code count} real server ticks pass, then run {@code then} on a later tick. */
    private static void await(int count, Runnable then) {
        waitTicks = Math.max(0, count);
        afterWait = then;
    }

    /**
     * One real native bite refused before it lands.
     *
     * <p>This is the same cancellation any mod or datapack may ask for, and the fixture uses it to prove the
     * thing the ruleset claims: a cave spider's poison is added by its own successful {@code doHurtTarget} and
     * by nothing else, so a cancelled hit adds no poison and is not paid for either.</p>
     */
    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!enabled || !cancelNativeBite || owner == null || caveSpider == null) return;
        if (event.getEntity() != owner) return;
        if (event.getSource().getEntity() == caveSpider) event.setCanceled(true);
    }

    /**
     * The authoritative damage of one real cave-spider bite, after the encounter's own modifiers.
     *
     * <p>Read here on purpose: the native attack attribute is the input, the applied damage is what really
     * happened, and the health the target lost is the third, different number.</p>
     */
    @SubscribeEvent
    public static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!enabled || owner == null || caveSpider == null || event.getEntity() != owner) return;
        if (event.getSource().getEntity() == null) {
            // No attacker: this is the effect clock's own pulse, the only such damage in this fixture.
            poisonPulseCount++;
            poisonPulseDamage += event.getNewDamage();
            return;
        }
        if (event.getSource().getEntity() != caveSpider) return;
        biteDamage = event.getNewDamage();
        biteCount++;
        Tactical.LOGGER.info("[Tactical] SPIDER bite damage: attribute={} applied={} original={}",
                caveSpider.getAttributeValue(Attributes.ATTACK_DAMAGE), event.getNewDamage(),
                event.getOriginalDamage());
    }

    /** The real poison instance a native bite added, with the native source that event names. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEffectAdded(MobEffectEvent.Added event) {
        if (!enabled || owner == null || event.getEntity() != owner) return;
        MobEffectInstance instance = event.getEffectInstance();
        if (instance == null || instance.getEffect().value() != MobEffects.POISON.value()) return;
        poisonTicks = instance.getDuration();
        poisonAmplifier = instance.getAmplifier();
        poisonSource = event.getEffectSource();
        Tactical.LOGGER.info("[Tactical] SPIDER poison added: duration={} amplifier={} source={}",
                poisonTicks, poisonAmplifier, poisonSource);
    }

    // ------------------------------------------------------------------ stages

    /** S1/A2: exactly two types, their real bodies, native attributes, and who may be taken at all. */
    private static void exactTypesAndBodies() {
        check(TacticalAiRegistry.create(spider) instanceof SpiderTacticalAi, "the spider gets the surface adapter");
        check(TacticalAiRegistry.create(caveSpider) instanceof SpiderTacticalAi,
                "the cave spider gets the surface adapter");
        check(TacticalAiRegistry.create(zombie) instanceof GenericMeleeAi,
                "an unrelated monster keeps the generic melee adapter");
        check(Math.abs(spider.getBbWidth() - 1.4D) < 1.0E-4D && Math.abs(spider.getBbHeight() - 0.9D) < 1.0E-4D,
                "the spider's real body is 1.4 x 0.9");
        check(Math.abs(caveSpider.getBbWidth() - 0.7D) < 1.0E-4D
                        && Math.abs(caveSpider.getBbHeight() - 0.5D) < 1.0E-4D,
                "the cave spider's real body is 0.7 x 0.5");
        check(spider.getMaxHealth() == 1000.0F && caveSpider.getMaxHealth() == 1000.0F,
                "the fixture's own health override is in place (the encounter reads attributes, not guesses)");
        check(spider.getAttributeValue(Attributes.ATTACK_DAMAGE) == 2.0D
                        && caveSpider.getAttributeValue(Attributes.ATTACK_DAMAGE) == 2.0D,
                "both keep vanilla's own attack damage");
        check(!spider.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100))
                        && !caveSpider.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100)),
                "both spiders are natively immune to poison");
        check(SpiderSurface.supports(spider) && SpiderSurface.supports(caveSpider)
                        && !SpiderSurface.supports(zombie),
                "the surface policy is exact-type only");
        // A spider walking on the floor is not an aerial actor and never becomes one.
        check(!AerialMovement.supports(spider) && !AerialMovement.supports(caveSpider),
                "a spider is never marked as a flying unit");
        // A2: the control entry is exact type, not riding and carrying no rider - all three are real questions
        // about the real entity, not about the class it shares with other mobs.
        check(SpiderSurface.controllable(spider), "an ordinary spider is a body this encounter may take");
        check(!SpiderSurface.controllable(zombie), "another exact type is never taken by the surface policy");
        check(skeleton.startRiding(spider, true) && spider.getPassengers().size() == 1,
                "a rider really sits on the spider");
        check(!SpiderSurface.controllable(spider), "a spider that carries a rider is not taken");
        skeleton.stopRiding();
        check(SpiderSurface.controllable(spider), "the same spider is takeable again once the rider is off");
        check(spider.startRiding(bystander, true) && spider.isPassenger(),
                "the spider really rides a body of its own");
        check(!SpiderSurface.controllable(spider), "a riding spider is not taken");
        spider.stopRiding();
        check(SpiderSurface.controllable(spider), "and is takeable again once it is back on its own feet");
    }

    /** S2/A1: a wall node is real, and re-reading a position never fabricates one. */
    private static void wallSurfaceGeometry() {
        for (int y = 0; y <= 3; y++) block(0, y, 2, Blocks.STONE);
        // The wall column is at relative x=0, so the cell that really holds its west face is relative x=-1 and
        // a body that wide has to reach it from the cell beyond (relative x=-2).
        GridPos wideCell = cell(-1, 0, 2);
        GridPos approach = cell(-2, 0, 2);
        SpiderSurface.Node wide = SpiderSurface.wallNode(level, spider, wideCell, Direction.EAST).orElse(null);
        check(wide != null, "a wide spider can hold the west face of a real wall from the cell beside it");
        if (wide != null) {
            check(wide.onWall() && wide.wall() == Direction.EAST, "the node keeps the side the wall is on");
            double expected = wideCell.x() + 1.0D - 0.7D - 1.0E-4D;
            check(Math.abs(wide.feet().x - expected) < 1.0E-3D,
                    "the feet come from the real half-width, not from the neighbour's centre: " + wide.feet());
            // A1: re-reading the position of a body that really is there answers the same node at its own feet.
            spider.setPos(wide.feet());
            check(SpiderSurface.holdsWall(level, spider, wide), "the body really touches the wall face");
            SpiderSurface.Node read = SpiderSurface.nodeAt(level, spider, wideCell, spider.position());
            check(read != null && read.onWall() && read.cell().equals(wideCell) && read.wall() == Direction.EAST,
                    "the real position is read back as the same wall node");
            check(read != null && Math.abs(read.feet().x - spider.position().x) < 1.0E-9D
                            && Math.abs(read.feet().y - spider.position().y) < 1.0E-9D,
                    "the node keeps the real feet instead of an idealised anchor: " + (read == null ? null : read.feet()));
            // A body three tenths of a block off the wall is beside the wall, not held by it.
            Vec3 apart = new Vec3(wide.feet().x + 0.3D, wide.feet().y, wide.feet().z);
            spider.setPos(apart);
            SpiderSurface.Node loose = SpiderSurface.nodeAt(level, spider, wideCell, spider.position());
            check(loose != null && !loose.onWall(), "a body that does not touch the wall is never called anchored");
            check(loose != null && Math.abs(loose.feet().x - apart.x) < 1.0E-9D
                            && Math.abs(loose.feet().z - apart.z) < 1.0E-9D,
                    "the real horizontal position is preserved, never snapped to the cell centre");
        }
        // The wide body steps off the wall cell, so the narrow body can be asked about the same wall.
        spider.setPos(new Vec3(approach.x() + 0.5D, approach.y(), approach.z() + 0.5D));
        SpiderSurface.Node narrow = SpiderSurface.wallNode(level, caveSpider, wideCell, Direction.EAST)
                .orElse(null);
        check(narrow != null, "a narrow cave spider can hold the wall from the adjacent cell");
        if (narrow != null) {
            double expected = wideCell.x() + 1.0D - 0.35D - 1.0E-4D;
            check(Math.abs(narrow.feet().x - expected) < 1.0E-3D,
                    "the narrow body is placed from its own half-width: " + narrow.feet());
        }
        // The wide body cannot stand at the centre of the cell beside the wall: its own box would be inside it.
        // The deployment rule - which only ever tries centres - is therefore still refused that position.
        check(!BattleDeployment.isStandable(level, spider,
                        new Vec3(wideCell.x() + 0.5D, wideCell.y(), wideCell.z() + 0.5D)),
                "the encounter's own centred rule refuses a wide body in the cell beside a wall");
        // The surface policy is allowed to shift inside the cell, so that same cell does carry the wide body at
        // a position that really fits - and the wall of that cell is one edge away from it.
        SpiderSurface.Node shifted = SpiderSurface.groundNode(level, spider, wideCell).orElse(null);
        check(shifted != null && shifted.shiftX() < 0,
                "the shared cell carries the wide body at a shifted position that really fits: " + shifted);
        check(shifted != null && wide != null && SpiderSurface.adjacent(shifted, wide),
                "the wall of that very cell is one edge away from that position");
        if (wide != null) {
            check(SpiderSurface.neighbours(level, spider, SpiderSurface.Node.ground(approach), c -> false,
                            c -> true).contains(wide),
                    "the wide body may enter that wall position from the cell beside it");
        }
        check(SpiderSurface.groundNode(level, caveSpider, wideCell).isPresent(),
                "the narrow body keeps the ordinary floor cell next to the same wall");
        // The wall continues, so the vertical edges exist.
        SpiderSurface.Node up = SpiderSurface.wallNode(level, caveSpider, wideCell.offset(0, 1, 0),
                Direction.EAST).orElse(null);
        check(up != null, "the wall node one block up exists while the wall continues");
        check(narrow != null && up != null && SpiderSurface.canStep(level, caveSpider, narrow, up),
                "the vertical wall edge is legal while the wall is continuous");
        check(narrow != null && up != null && SpiderSurface.canStep(level, caveSpider, up, narrow),
                "the same edge is legal downwards");
        // A cell with no wall beside it offers no wall node at all.
        check(SpiderSurface.wallNode(level, spider, cell(-2, 0, -3), Direction.EAST).isEmpty(),
                "a cell with no wall beside it has no wall node");
        // A1: the shift inside a cell is part of a floor node's identity, and it is quantised - the two
        // positions a wide body can really tell apart are two nodes, and float noise cannot create more.
        GridPos probe = cell(0, 0, -6);
        check(!SpiderSurface.Node.ground(probe).equals(SpiderSurface.Node.ground(probe, 0.25D, 0.0D)),
                "a shifted floor position is a different node from the cell centre");
        check(SpiderSurface.Node.ground(probe, 0.25D, 0.0D)
                        .equals(SpiderSurface.Node.groundAt(probe,
                                new Vec3(probe.x() + 0.76D, probe.y(), probe.z() + 0.5D))),
                "a real position snaps onto the same lattice node instead of multiplying nodes");
    }

    /** S3/A1: the real body decides, and a wide body that needs a shifted floor position gets one. */
    private static void wideAndNarrowBodies() {
        for (int y = 0; y <= 1; y++) {
            block(4, y, -3, Blocks.STONE);
            block(6, y, -3, Blocks.STONE);
        }
        GridPos slot = cell(5, 0, -3);
        check(SpiderSurface.groundNode(level, caveSpider, slot).isPresent(),
                "a cave spider's 0.7 wide body fits the one-block slot");
        check(SpiderSurface.groundNode(level, spider, slot).isEmpty(),
                "a spider's 1.4 wide body is refused by the same slot");
        check(BattleDeployment.isStandable(level, caveSpider, new Vec3(slot.x() + 0.5D, slot.y(),
                        slot.z() + 0.5D)),
                "the encounter's own deployment rule agrees the narrow body fits");
        // A wide body is never pushed through a wall to keep its cell centre: the hugging node is refused.
        check(SpiderSurface.wallNode(level, spider, cell(5, 0, -3), Direction.WEST).isEmpty()
                        && SpiderSurface.wallNode(level, spider, cell(5, 0, -3), Direction.EAST).isEmpty(),
                "a wide spider is refused both wall sides its body would clip in a one-block slot");
        for (int y = 0; y <= 1; y++) {
            block(4, y, -3, Blocks.AIR);
            block(6, y, -3, Blocks.AIR);
        }
        // A1: a raised platform really carries the wide body - but its body is 1.4 wide on both axes, so the
        // pad has to be two by two and the position it can hold is a shifted one.
        for (int dx = 10; dx <= 11; dx++) {
            for (int dz = -6; dz <= -5; dz++) block(dx, 0, dz, Blocks.STONE);
        }
        GridPos twoWide = cell(10, 1, -6);
        SpiderSurface.Node wideStand = SpiderSurface.groundNode(level, spider, twoWide).orElse(null);
        check(wideStand != null, "the wide body may stand on a platform two columns wide: " + wideStand);
        if (wideStand != null) {
            check(wideStand.shiftX() != 0 && wideStand.shiftZ() != 0 && wideStand.cell().equals(twoWide),
                    "the position it really needs is a shifted one inside the same cell: " + wideStand);
            check(BattleDeployment.isStandable(level, spider, wideStand.feet()),
                    "the whole body has support at exactly that shifted position");
        }
        SpiderSurface.Node narrowStand = SpiderSurface.groundNode(level, caveSpider, twoWide).orElse(null);
        check(narrowStand != null && narrowStand.shiftX() == 0 && narrowStand.shiftZ() == 0,
                "the narrow body keeps the plain centred position on the same platform");
        block(14, 0, -6, Blocks.STONE);
        check(SpiderSurface.groundNode(level, spider, cell(14, 1, -6)).isEmpty(),
                "the same wide body is still refused a one-column ledge, whatever shift it tries");
        check(SpiderSurface.groundNode(level, caveSpider, cell(14, 1, -6)).isPresent(),
                "and the narrow body still takes that one-column ledge");
        for (int dx = 10; dx <= 11; dx++) {
            for (int dz = -6; dz <= -5; dz++) block(dx, 0, dz, Blocks.AIR);
        }
        block(14, 0, -6, Blocks.AIR);
    }

    /** S2/S3/S4/A1/A4: the ledge, the bounded landing, the declared topologies and the mid-sweep refusals. */
    private static void ledgeAndMantle() {
        // A two high wall column: its top is a legal landing for the narrow body and an overhang for the wide one.
        for (int y = 0; y <= 1; y++) block(-6, y, 6, Blocks.STONE);
        SpiderSurface.Node narrowTop = SpiderSurface.wallNode(level, caveSpider, cell(-7, 1, 6),
                Direction.EAST).orElse(null);
        check(narrowTop != null, "the narrow body holds the wall just under the ledge");
        GridPos ledge = cell(-6, 2, 6);
        SpiderSurface.Node onto = SpiderSurface.groundNode(level, caveSpider, ledge).orElse(null);
        check(onto != null, "the top of the wall is a legal landing for the narrow body");
        check(narrowTop != null && onto != null && SpiderSurface.canStep(level, caveSpider, narrowTop, onto),
                "the mantle onto the ledge is one legal edge for the narrow body");
        // The wide body's own 1.4 box hangs 0.2 over a one-column ledge, so the whole-footprint rule refuses
        // it there - and no shift can help, because the ledge is only one column wide.
        check(SpiderSurface.groundNode(level, spider, ledge).isEmpty(),
                "the wide body is refused the one-block-wide ledge it would overhang");
        // A wide platform beside the same wall is different: the landing is reachable at a shifted position.
        // The wide body overhangs both axes, so the platform has to be two by two for it to stand on at all.
        for (int dx = -6; dx <= -5; dx++) {
            for (int dz = 9; dz <= 10; dz++) {
                for (int y = 0; y <= 1; y++) block(dx, y, dz, Blocks.STONE);
            }
        }
        SpiderSurface.Node wideTop = SpiderSurface.wallNode(level, spider, cell(-7, 1, 9),
                Direction.EAST).orElse(null);
        check(wideTop != null, "the wide body holds the same wall where it is not in a one-column slot");
        SpiderSurface.Node wideLanding = SpiderSurface.groundNode(level, spider, cell(-6, 2, 9)).orElse(null);
        check(wideLanding != null, "the two-by-two platform top carries the wide body");
        check(wideLanding != null && wideLanding.shiftX() > 0,
                "the wide landing is a shifted position that keeps the whole body on the platform");
        check(wideTop != null && wideLanding != null
                        && SpiderSurface.canStep(level, spider, wideTop, wideLanding),
                "the wide body's mantle onto that platform is one legal edge");
        // A4: the swept body has to stay inside the battlefield, including the transient point of the mantle.
        check(wideTop != null && wideLanding != null
                        && !SpiderSurface.canStep(level, spider, wideTop, wideLanding, z -> z.x() <= wideTop.cell().x()),
                "a battlefield that stops at the wall refuses the mantle, transition included");
        check(wideTop != null && wideLanding != null
                        && SpiderSurface.canStep(level, spider, wideTop, wideLanding, z -> true),
                "the same mantle is legal inside a battlefield that covers it");
        // A4: a liquid in the middle of the sweep is a refusal even though both endpoints are dry.
        block(-8, 2, 9, Blocks.WATER);
        check(wideTop != null && wideLanding != null
                        && !SpiderSurface.canStep(level, spider, wideTop, wideLanding, z -> true),
                "liquid in the swept body refuses the edge, endpoints aside");
        block(-8, 2, 9, Blocks.AIR);
        check(wideTop != null && wideLanding != null
                        && SpiderSurface.canStep(level, spider, wideTop, wideLanding, z -> true),
                "the same edge is legal once the liquid is gone");
        // A4: two legal positions are not automatically one edge.
        SpiderSurface.Node near = SpiderSurface.groundNode(level, caveSpider, cell(0, 0, -9)).orElse(null);
        SpiderSurface.Node far = SpiderSurface.groundNode(level, caveSpider, cell(2, 0, -9)).orElse(null);
        check(near != null && far != null, "both distant floor positions are legal on their own");
        check(near != null && far != null && !SpiderSurface.adjacent(near, far)
                        && !SpiderSurface.canStep(level, caveSpider, near, far),
                "a legal position two cells away is never joined by one free edge");
        // A wall edge never crosses from one wall side to another: two faces of one cell are two nodes but not
        // one step, which is what keeps a climb from cutting a corner through the block.
        GridPos corner = cell(-8, 1, 9);
        SpiderSurface.Node eastFace = SpiderSurface.Node.wall(corner,
                new Vec3(corner.x() + 0.3D, corner.y(), corner.z() + 0.5D), Direction.EAST);
        SpiderSurface.Node westFace = SpiderSurface.Node.wall(corner.offset(1, 0, 0),
                new Vec3(corner.x() + 1.3D, corner.y(), corner.z() + 0.5D), Direction.WEST);
        check(!SpiderSurface.adjacent(eastFace, westFace),
                "changing the wall side is not one step, even between touching faces");
        // A block that appears in the landing cell itself makes the whole mantle illegal - the sweep is never
        // narrowed to the half of the body that happens to fit.
        block(-6, 2, 6, Blocks.STONE);
        check(SpiderSurface.groundNode(level, caveSpider, ledge).isEmpty(),
                "a block that fills the landing cell refuses it");
        check(onto != null && !SpiderSurface.canStep(level, caveSpider, narrowTop, onto),
                "the mantle edge is refused once the landing cell is filled");
        block(-6, 2, 6, Blocks.AIR);
        check(SpiderSurface.groundNode(level, caveSpider, ledge).isPresent(), "the ledge is legal again");
        // A squatter is placed on the ledge now and asked about in the next stage, so the level has really
        // registered it in that entity section before the occupancy rule is consulted.
        squatter = spawn(EntityType.COW, -6, 2, 6);
    }

    /** S3: an occupied candidate, an unloaded chunk, the zone and the pure grid rule. */
    private static void occupancyAndBounds() {
        GridPos ledge = cell(-6, 2, 6);
        check(squatter != null && squatter.isAlive() && !squatter.isRemoved(),
                "the squatter really stands on the ledge");
        check(SpiderSurface.groundNode(level, caveSpider, ledge).isEmpty(),
                "another living body in the cell refuses it");
        check(squatter.isAlive() && !squatter.isRemoved(), "the bystander is only refused, never touched");
        squatter.discard();
        check(SpiderSurface.groundNode(level, caveSpider, ledge).isPresent(), "the free cell is legal again");
        // A candidate in a chunk that was never loaded is refused without loading it.
        GridPos far = new GridPos(origin.getX() + 900, origin.getY(), origin.getZ() + 900);
        BlockPos farBlock = new BlockPos(far.x(), far.y(), far.z());
        check(!level.hasChunkAt(farBlock), "the probe cell starts unloaded");
        check(SpiderSurface.groundNode(level, caveSpider, far).isEmpty(), "an unloaded cell is refused");
        check(!level.hasChunkAt(farBlock), "asking about it did not force-load the chunk");
        // The zone test sees the whole body, not only its feet cell.
        SpiderSurface.Node wideWall = SpiderSurface.wallNode(level, spider, cell(-1, 0, 2), Direction.EAST)
                .orElse(null);
        check(wideWall != null && !SpiderSurface.bodyInsideZone(spider, wideWall,
                        z -> z.z() == wideWall.cell().z() && z.x() <= wideWall.cell().x()),
                "the zone test sees the body's second column, not only its feet cell");
    }

    /** S7: the real attack box refuses an integer one-level step even though the grid allows it. */
    private static void attackBoxRefusesOneLevelUp() {
        block(-9, 0, -6, Blocks.STONE);
        owner.setPos(origin.getX() - 9.5D, origin.getY() + 1, origin.getZ() - 5.5D);
        owner.setHealth(owner.getMaxHealth());
        spider.setPos(origin.getX() - 10.5D, origin.getY(), origin.getZ() - 5.5D);
        double damageBefore = owner.getHealth();
        Vec3 feet = spider.position();
        check(!SpiderSurface.attackBoxAt(spider, feet).intersects(owner.getHitbox()),
                "the real attack box does not reach a whole block up");
        check(com.tactical.core.grid.GridMovementRules.canStep(cell(-10, 0, -6), cell(-9, 1, -6)),
                "the grid alone would have allowed that attack");
        // The same two bodies at one level make the box intersect, so the refusal is about height and not
        // about distance: a clear column two cells away is used so no block can be blamed.
        spider.setPos(origin.getX() - 10.5D, origin.getY(), origin.getZ() - 9.5D);
        owner.setPos(origin.getX() - 9.5D, origin.getY(), origin.getZ() - 9.5D);
        feet = spider.position();
        check(SpiderSurface.attackBoxAt(spider, feet).intersects(owner.getHitbox()),
                "the same box reaches a target at the same level");
        check(owner.getHealth() == damageBefore, "none of this damaged the owner");
        block(-9, 0, -6, Blocks.AIR);
    }

    /**
     * A5: the bounded search really approaches.
     *
     * <p>A synthetic surface graph is the only honest way to put the opponent beyond the search front: it is
     * the same adapter, the same node type and the same budget the encounter gives it, with the world reads
     * replaced by an explicit graph. Three cases: a bite inside the budget is taken first, an opponent beyond
     * it is approached by the deepest legal prefix, and a turn with no strict progress ends where it stands.</p>
     */
    private static void boundedApproachPolicy() {
        SpiderTacticalAi adapter = new SpiderTacticalAi();
        GridPos start = new GridPos(0, 64, 0);
        // Case one: the opponent is twelve cells away and three edges are affordable - a legal prefix.
        PolicyContext prefix = new PolicyContext(start, new GridPos(12, 64, 0), 3, Set.of());
        AiAction plan = adapter.planTurn(prefix);
        check(plan.kind() == AiAction.Kind.SURFACE_MOVE, "a far opponent is still approached, not ignored");
        List<SpiderSurface.Node> route = plan.surface();
        check(route.size() == 3, "the prefix is exactly what the budget allows: " + route.size());
        check(route.get(route.size() - 1).cell().x() > start.x(),
                "the prefix strictly closes in on the opponent: " + route.get(route.size() - 1).cell());
        check(prefix.asked <= SpiderSurface.MAX_SEARCH_NODES + 64,
                "the search stays bounded in what it asks the world: " + prefix.asked);
        // Case two: a bite is already affordable - the frozen order bites before it walks further.
        PolicyContext bite = new PolicyContext(start, new GridPos(12, 64, 0), 5, Set.of(new GridPos(2, 64, 0)));
        AiAction bitePlan = adapter.planTurn(bite);
        check(bitePlan.kind() == AiAction.Kind.SURFACE_MOVE && bitePlan.surface().size() == 2,
                "the shortest route that can bite is taken, no further");
        // Case three: no direction closes in - nothing is walked for free.
        PolicyContext nowhere = new PolicyContext(start, new GridPos(0, 64, 30), 2, Set.of());
        check(adapter.planTurn(nowhere).kind() == AiAction.Kind.END_TURN,
                "a turn with no strict progress ends where it stands");
        // Case four: no surface state at all keeps the plain four-way rule of every other monster.
        PolicyContext empty = new PolicyContext(null, new GridPos(12, 64, 0), 3, Set.of());
        check(adapter.planTurn(empty).kind() == AiAction.Kind.END_TURN,
                "a context without a surface never invents a surface route");
        // Case five: a branching surface whose opponent is far outside it. The 512 node budget is really
        // reached, and the search must return the best prefix it already knows instead of stopping empty-handed
        // or crawling the whole graph.
        PolicyContext branching = PolicyContext.lattice(new GridPos(0, 64, 0), new GridPos(100, 64, 100), 64);
        AiAction prefixPlan = adapter.planTurn(branching);
        check(prefixPlan.kind() == AiAction.Kind.SURFACE_MOVE,
                "a budget-limited search still returns the approach it found: " + branching.offered + " offered");
        check(prefixPlan.surface().size() > 0 && prefixPlan.surface().size() <= 64,
                "the prefix stays inside the affordable edge count: " + prefixPlan.surface().size());
        SpiderSurface.Node reached = prefixPlan.surface().get(prefixPlan.surface().size() - 1);
        int startDistance = Math.abs(100) + Math.abs(100);
        check(Math.abs(reached.cell().x()) + Math.abs(reached.cell().z()) < startDistance,
                "the prefix strictly closes in on the opponent: " + reached.cell());
        check(branching.asked <= SpiderSurface.MAX_SEARCH_NODES + 16,
                "the search expanded no more nodes than its budget: " + branching.asked);
        check(branching.offered <= 4 * SpiderSurface.MAX_SEARCH_NODES + 64,
                "and offered no more candidates than that budget can read: " + branching.offered);
        int askedOnce = branching.asked;
        AiAction again = adapter.planTurn(branching);
        check(again.kind() == prefixPlan.kind() && again.surface().equals(prefixPlan.surface()),
                "the same bounded question answers the same route");
        check(branching.asked - askedOnce == askedOnce,
                "the second plan asked exactly as much as the first: " + (branching.asked - askedOnce));
    }

    /** S1/S2/S3/S7/A1/A2: a real admission, a real climb, a real mantle and a held body across real ticks. */
    private static void wideSpiderClimbsAndMantles() {
        discardAll();
        // A three-by-two platform three blocks up: the wide body needs all four support columns and can only
        // reach it at a shifted position, which is exactly the landing the surface policy has to offer it. The
        // opponent stands on the far column so the platform really carries it and no body overlaps the landing.
        for (int y = 0; y <= 1; y++) block(0, y, 0, Blocks.STONE);
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 1; dz++) block(dx, 2, dz, Blocks.STONE);
        }
        owner.setPos(origin.getX() + 2.5D, origin.getY() + 3, origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        spider = spawn(EntityType.SPIDER, -2, 0, 0);
        bystander = spawn(EntityType.COW, 16, 0, 12);
        // Precondition, not a product claim: this body really can be damaged right now. Vanilla's spawn
        // invulnerability would otherwise turn every melee assertion below into a lie.
        check(owner.hurt(spider.damageSources().mobAttack(spider), 1.0F),
                "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        float ownerHealth = owner.getHealth();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the wide spider's encounter starts");
        battle = BattleManager.current();
        UUID id = spider.getUUID();
        // The chain the route needs, asked with the encounter's own battlefield: the wall beside the body, the
        // climb up it, the mantle onto the platform and the step that reaches the opponent.
        SpiderSurface.Node foot = SpiderSurface.Node.ground(cell(-2, 0, 0));
        SpiderSurface.Node wall0 = SpiderSurface.wallNode(level, spider, cell(-1, 0, 0), Direction.EAST)
                .orElse(null);
        check(wall0 != null, "the wide body can hold the wall of the cell beside it");
        check(wall0 != null && SpiderSurface.neighbours(level, spider, foot, c -> false,
                        battle.zone()::contains).contains(wall0),
                "that wall is one of the edges the body is really offered");
        SpiderSurface.Node wall1 = SpiderSurface.wallNode(level, spider, cell(-1, 1, 0), Direction.EAST)
                .orElse(null);
        SpiderSurface.Node wall2 = SpiderSurface.wallNode(level, spider, cell(-1, 2, 0), Direction.EAST)
                .orElse(null);
        check(wall1 != null && wall2 != null, "the wall continues two blocks up");
        check(wall0 != null && wall1 != null && SpiderSurface.canStep(level, spider, wall0, wall1,
                        battle.zone()::contains),
                "the climb is one legal edge inside the battlefield");
        check(wall1 != null && wall2 != null && SpiderSurface.canStep(level, spider, wall1, wall2,
                        battle.zone()::contains),
                "the second climb is one legal edge inside the battlefield");
        SpiderSurface.Node top = SpiderSurface.groundNode(level, spider, cell(0, 3, 0)).orElse(null);
        check(wall2 != null && top != null && SpiderSurface.canStep(level, spider, wall2, top,
                        battle.zone()::contains),
                "the mantle onto the platform is one legal edge: " + top);
        SpiderSurface.Node reach = SpiderSurface.groundNode(level, spider, cell(1, 3, 0)).orElse(null);
        check(top != null && reach != null && SpiderSurface.canStep(level, spider, top, reach,
                        battle.zone()::contains),
                "the platform carries the step that reaches the opponent: " + reach
                        + " zone=" + battle.zone().bounds());
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null, "the wide spider was really deployed into the encounter");
        check(admitted != null && admitted.node() != null, "the encounter resolved a real surface node");
        check(admitted != null && admitted.feet().distanceToSqr(spider.position()) < 1.0E-9D,
                "admission keeps the real feet: no uncharged snap onto a planned node");
        int startY = admitted == null ? 0 : admitted.cell().y();
        int apBefore = admitted == null ? 0 : admitted.ap();

        // The encounter advances on its own real server ticks; this stage only hands the player's turn on and
        // watches, so nothing is decided twice in one tick.
        await(1, () -> watchWideClimb(id, 0, startY, apBefore, ownerHealth));
    }

    /** One real server tick of the wide spider's own turn, watched until it has acted and the turn is back. */
    private static void watchWideClimb(UUID id, int guard, int startY, int apBefore, float ownerHealth) {
        if (guard > 800) {
            check(false, "the wide spider really acted inside its own turn");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the wide spider stayed on the roster");
            return;
        }
        if (!(view.paidActions() > 0 && battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchWideClimb(id, guard + 1, startY, apBefore, ownerHealth));
            return;
        }
        check(view.cell().y() == startY + 3,
                "the wide body really climbed and mantled: y=" + startY + " -> " + view.cell().y());
        check(view.node() != null && !view.node().onWall(),
                "it stands on the platform instead of hanging on the wall");
        check(view.node() != null && (view.node().shiftX() != 0 || view.node().shiftZ() != 0),
                "the landing it holds is a shifted position its real width needs: " + view.node());
        check(view.ap() < apBefore, "the route was really paid for out of its own pool");
        check(view.paidActions() == 1, "exactly one paid action was counted, the melee");
        check(owner.getHealth() < ownerHealth, "the wide spider bit from the platform");
        check(spider.position().y > origin.getY() + 2.5D, "the body really stands up there: " + spider.position());
        check(battle.isPlayerTurnNow() && !battle.isBusy(), "the encounter handed the turn over cleanly");
        // A2: between its own turns the controlled body keeps exactly the position the encounter proved, across
        // real server ticks - not merely across a synchronous run of encounter ticks.
        Vec3 held = spider.position();
        GridPos heldCell = view.cell();
        int heldAp = view.ap();
        boolean heldClimbing = spider.isClimbing();
        await(12, () -> {
            check(spider.position().distanceToSqr(held) < 1.0E-9D,
                    "a held body does not drift across real server ticks: " + spider.position() + " vs " + held);
            ServerBattle.EnemyView during = battle.fixtureEnemy(id);
            check(during != null && during.cell().equals(heldCell), "the authoritative cell did not change");
            check(during != null && during.ap() == heldAp, "no AP was spent or stolen while waiting");
            check(spider.isClimbing() == heldClimbing, "the borrowed climbing bit did not change");
            check(!battle.isBusy(), "nothing is left in flight");
            battle.finish("fixture wait done");
            check(((TacticalSpiderAccess) spider).tactical$poseHandler() == null,
                    "ending the encounter gives the body back to its own tick");
        });
    }

    /**
     * S4/A3: the support under an edge really disappears, and the body really falls.
     *
     * <p>The wall is broken while an upward edge is in flight: the encounter must stop the body where it
     * really is - not finish the segment, not pull it back to the node it already left - keep the price of the
     * edge that really moved it, and hand it to the existing fall rule. The fall is only believed once the
     * body has really descended over real server ticks.</p>
     */
    private static void wideSpiderFallsWhenSupportBreaks() {
        discardAll();
        resetFallBarrier();
        // A five high wall in a corner of the arena nothing else uses, with one block beside its top so the
        // opponent really stands up there: the route has several edges, so one can be interrupted high enough
        // above the floor to make the fall an observable descent.
        for (int y = 0; y <= 4; y++) block(-12, y, 12, Blocks.STONE);
        block(-11, 4, 12, Blocks.STONE);
        owner.setPos(origin.getX() - 10.5D, origin.getY() + 5, origin.getZ() + 12.5D);
        owner.setHealth(owner.getMaxHealth());
        spider = spawn(EntityType.SPIDER, -14, 0, 12);
        bystander = spawn(EntityType.COW, 16, 0, 12);
        check(owner.hurt(spider.damageSources().mobAttack(spider), 1.0F),
                "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the fall encounter starts");
        battle = BattleManager.current();
        UUID id = spider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the falling spider resolved its own surface node");
        int apBeforeTurn = admitted == null ? 0 : admitted.ap();
        int startY = admitted == null ? 0 : admitted.cell().y();

        // Walk until it is really climbing - two blocks up with an edge in flight - and then break the wall
        // that carries the edge it is on.
        // The encounter advances on its own real server ticks. This stage hands the player's turn on and
        // watches until an edge is really in flight two blocks up, then takes the wall away under it.
        await(1, () -> watchClimbThenBreak(id, 0, startY, 0));
    }

    /** Watch the climb with real server ticks, and break the wall that carries the edge in flight. */
    private static void watchClimbThenBreak(UUID id, int guard, int startY, int mode) {
        if (guard > 800) {
            check(false, "the wide spider really climbed two blocks with an edge in flight");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the falling spider stayed on the roster");
            return;
        }
        boolean midEdge = view.moving() && view.node() != null && view.node().onWall()
                && view.node().cell().y() >= startY + 2
                && Math.abs(spider.position().y - view.node().feet().y()) > 1.0E-6D;
        if (!midEdge) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchClimbThenBreak(id, guard + 1, startY, mode));
            return;
        }
        GridPos peakCell = view.node().cell();
        int apAtCommit = view.ap();
        double breakY = spider.position().y;
        check(breakY > origin.getY() + 1.5D, "the interrupted edge is high above the floor: " + breakY);
        // The wall the body is really holding, the one the edge in flight would need, and the wall just below:
        // the whole face it is clinging to goes, so what happens next can only be a real fall.
        BlockPos wall = SpiderSurface.wallBlock(peakCell, view.node().wall());
        for (int dy = -1; dy <= 2; dy++) {
            block(wall.getX() - origin.getX(), wall.getY() - origin.getY() + dy,
                    wall.getZ() - origin.getZ(), Blocks.AIR);
        }
        BlockPos column = spider.blockPosition();
        Vec3 realFeet = spider.position();
        refusedDrift = 0.0D;
        refusedLowestY = realFeet.y;
        if (mode == 1) {
            // The only cell this column could land in is taken, so no landing can be resolved at all.
            bystander = spawn(EntityType.COW, column.getX() - origin.getX(), 0, column.getZ() - origin.getZ());
        }
        if (mode == 2) {
            // The centre of the landing cell is free and standable; only the body that really stands there
            // would clip something. That is the case where a landing must be refused rather than faked.
            block(wall.getX() - origin.getX(), 0, wall.getZ() - origin.getZ(), Blocks.AIR);
            blocker = spawnPrecise(EntityType.CHICKEN, realFeet.x, origin.getY(), column.getZ() - 0.5D);
            GridPos landing = new GridPos(column.getX(), origin.getY(), column.getZ());
            check(BattleDeployment.isStandable(level, spider,
                            new Vec3(landing.x() + 0.5D, landing.y(), landing.z() + 0.5D)),
                    "premise: the centre of the landing cell is really standable");
            check(!SpiderSurface.isLegal(level, spider,
                            SpiderSurface.Node.groundAt(landing,
                                    new Vec3(realFeet.x, landing.y(), realFeet.z))),
                    "premise: the body that really stands there is not standable");
        }
        await(3, () -> {
            if (mode == 1) watchBlockedFall(id, 0);
            else if (mode == 2) watchRefusedLanding(id, 0, realFeet, column);
            else watchFall(id, 0, breakY, apAtCommit);
        });
    }

    /**
     * The interrupted edge and the forced fall, watched with real server ticks until the body rests on the
     * floor.
     *
     * <p>Two things are being observed here, and they are different: the encounter keeps its own bounded control
     * while the fall is its own, and once that control is given back the body is the world's - so the descent is
     * followed for as long as either one is still moving it, and the body may never go back up.</p>
     */
    private static void watchFall(UUID id, int guard, double breakY, int apAtCommit) {
        if (guard > 400) {
            check(false, "the lost support really moving the body down: " + spider.position());
            return;
        }
        double y = spider.position().y;
        if (y > breakY + 1.0E-6D) {
            check(false, "a body that lost its support never climbs: " + breakY + " -> " + y);
            return;
        }
        checkFallBarrierIsExclusive("the interrupted wall");
        if (battle.terrainFallActive() || battle.isBusy()) {
            await(1, () -> watchFall(id, guard + 1, breakY, apAtCommit));
            return;
        }
        // The encounter is quiet again: the descent it owned really finished, on the floor it really reached.
        checkFallHandedOffOnce("the interrupted wall");
        ServerBattle.EnemyView landed = battle.fixtureEnemy(id);
        double rest = spider.position().y;
        check(rest < breakY - 0.5D,
                "the body really descended after losing its support: " + breakY + " -> " + rest);
        check(!battle.isBusy(), "no permanent busy state is left behind the fall");
        check(!battle.terrainFallActive(), "no fall is left running");
        check(Math.abs(rest - origin.getY()) < 0.02D,
                "it really landed on the floor instead of stopping in the air: " + spider.position());
        check(spider.onGround() || BattleDeployment.isStandable(level, spider, spider.position()),
                "the position it settled on is a real standable position: " + spider.position());
        check(!spider.isClimbing() || SpiderSurface.holdsWall(level, spider,
                        SpiderSurface.nodeAt(level, spider, new GridPos(spider.getBlockX(), spider.getBlockY(),
                                spider.getBlockZ()), spider.position())),
                "a landed spider only claims a wall it really touches: " + spider.position());
        check(landed != null, "the landed spider is still on the roster");
        if (landed != null) {
            check(!landed.moving(), "the route stopped instead of being finished through the hole");
            check(landed.paidActions() == 0, "a stopped route never counts a success");
            GridPos bodyCell = new GridPos(spider.getBlockX(), spider.getBlockY(), spider.getBlockZ());
            check(landed.cell().equals(bodyCell),
                    "the authoritative cell is where the body really landed: " + landed.cell() + " vs " + bodyCell);
            check(landed.node() != null && SpiderSurface.isLegal(level, spider, landed.node()),
                    "the landed body holds a legal surface node: " + landed.node());
            check((landed.ap() - apAtCommit) % Math.max(1, landed.moveCost()) == 0,
                    "the unwalked edges were given back as whole edges: " + apAtCommit + " -> " + landed.ap());
            check(landed.ap() >= apAtCommit, "no edge in front of the body was taken away from it");
            check(landed.ap() >= 0 && landed.ap() <= landed.maxAp(), "the pool stayed inside its own bounds");
        }
        check(bystander.isAlive() && !bystander.isRemoved(), "the unrelated member was never touched");
        battle.finish("fixture fall done");
    }

    /**
     * S4/A3: a descent the world really blocks is released instead of pinning the body.
     *
     * <p>The same climb is interrupted the same way, but the floor cell under the fall is occupied, so no landing
     * can be resolved. The encounter's own deadline is the only thing that may end this: the unit is released,
     * nothing of this encounter is left pinning it, and it is not teleported onto the floor it never reached.</p>
     */
    private static void wideSpiderFallThatCannotSettle() {
        discardAll();
        for (int y = 0; y <= 4; y++) block(-12, y, 12, Blocks.STONE);
        block(-11, 4, 12, Blocks.STONE);
        owner.setPos(origin.getX() - 10.5D, origin.getY() + 5, origin.getZ() + 12.5D);
        owner.setHealth(owner.getMaxHealth());
        spider = spawn(EntityType.SPIDER, -14, 0, 12);
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the blocked-fall encounter starts");
        battle = BattleManager.current();
        UUID id = spider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the blocked spider resolved its own surface node");
        int startY = admitted == null ? 0 : admitted.cell().y();
        await(1, () -> watchClimbThenBreak(id, 0, startY, 1));
    }

    /** Watch the release of a fall that really cannot settle, with real server ticks. */
    private static void watchBlockedFall(UUID id, int guard) {
        if (guard > 400) {
            check(false, "a fall that cannot settle really ends");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view != null) {
            await(1, () -> watchBlockedFall(id, guard + 1));
            return;
        }
        // The encounter's own deadline ended it: this is an exception path, and it must leave nothing behind.
        check(!battle.isBusy(), "the encounter is quiet again");
        check(!battle.terrainFallActive(), "no fall is left running");
        check(((TacticalSpiderAccess) spider).tactical$poseHandler() == null,
                "a released unit keeps no pose seam: " + spider.position());
        check(!spider.isClimbing(), "and no borrowed climbing bit: " + spider.position());
        check(spider.isAlive() && !spider.isRemoved(), "the released body itself is untouched");
        check(spider.position().y > origin.getY() + 0.5D,
                "and it was not teleported onto a floor it never reached: " + spider.position());
        check(bystander.isAlive() && !bystander.isRemoved(), "the body that blocked the landing was not moved");
        battle.finish("fixture blocked fall done");
    }

    /**
     * S4/A3: a landing whose cell centre is standable but whose real feet are not is refused, not faked.
     *
     * <p>The same interruption, with one body standing where only the wall-hugging feet would be. The cell centre
     * is free, so a cell-based landing would "succeed" by moving the body onto it. The encounter must not do
     * that: nothing may be claimed as landed, the body may not be moved sideways, and the existing fall deadline
     * ends it.</p>
     */
    private static void wideSpiderFallOntoItsOwnFeetRefused() {
        discardAll();
        for (int y = 0; y <= 4; y++) block(-12, y, 12, Blocks.STONE);
        block(-11, 4, 12, Blocks.STONE);
        owner.setPos(origin.getX() - 10.5D, origin.getY() + 5, origin.getZ() + 12.5D);
        owner.setHealth(owner.getMaxHealth());
        spider = spawn(EntityType.SPIDER, -14, 0, 12);
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the refused-landing encounter starts");
        battle = BattleManager.current();
        UUID id = spider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the refused-landing spider resolved its surface node");
        int startY = admitted == null ? 0 : admitted.cell().y();
        await(1, () -> watchClimbThenBreak(id, 0, startY, 2));
    }

    /** Watch a fall whose real landing is refused, with real server ticks, until the deadline releases it. */
    private static void watchRefusedLanding(UUID id, int guard, Vec3 realFeet, BlockPos column) {
        if (guard > 400) {
            check(false, "a refused landing really ends");
            return;
        }
        if (battle.fixtureEnemy(id) != null) {
            // Watched, not asserted, on every tick: the count of a fixture must not depend on timing.
            refusedDrift = Math.max(refusedDrift,
                    Math.max(Math.abs(spider.getX() - realFeet.x), Math.abs(spider.getZ() - realFeet.z)));
            refusedLowestY = Math.min(refusedLowestY, spider.getY());
            await(1, () -> watchRefusedLanding(id, guard + 1, realFeet, column));
            return;
        }
        check(!battle.isBusy() && !battle.terrainFallActive(), "the encounter is quiet again");
        check(refusedDrift < 0.02D,
                "a refused landing never slides the body onto the cell centre, not even for one tick: "
                        + refusedDrift);
        check(refusedLowestY > origin.getY() + 0.5D,
                "and never claims the floor it could not land on: " + refusedLowestY);
        check(spider.getY() > origin.getY() + 0.5D,
                "it was not teleported onto a landing it never legally reached: " + spider.position());
        check(((TacticalSpiderAccess) spider).tactical$poseHandler() == null, "and keeps no pose seam");
        check(!spider.isClimbing(), "and no borrowed climbing bit");
        check(blocker != null && blocker.isAlive() && !blocker.isRemoved(),
                "the body that made the real landing illegal was not moved");
        battle.finish("fixture refused landing done");
    }

    /** S1/S2/S7/A6: a real admission, a real climb onto the ledge and one real native bite. */
    private static void caveSpiderClimbsAndBites() {
        discardAll();
        for (int y = 0; y <= 2; y++) block(0, y, 0, Blocks.STONE);
        block(1, 2, 0, Blocks.STONE);
        owner.setPos(origin.getX() + 1.5D, origin.getY() + 3, origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        caveSpider = spawn(EntityType.CAVE_SPIDER, -1, 0, 0);
        bystander = spawn(EntityType.COW, 16, 0, 12);
        check(owner.hurt(caveSpider.damageSources().mobAttack(caveSpider), 1.0F),
                "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        float ownerHealth = owner.getHealth();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the encounter starts");
        battle = BattleManager.current();
        check(battle != null && battle.isPlayerTurnNow(), "the owner acts first");
        check(battle.isParticipant(caveSpider), "the cave spider was really deployed into the encounter");
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null, "the cave spider is on the roster");
        check(caveSpider.isNoAi() && caveSpider.isPersistenceRequired(),
                "admission takes the native AI and keeps it");
        check(admitted != null && admitted.node() != null, "the encounter resolved a real surface node");
        check(admitted != null && admitted.feet().distanceToSqr(caveSpider.position()) < 1.0E-9D,
                "admission keeps the real feet: no uncharged snap onto a planned node");
        int startY = admitted == null ? 0 : admitted.cell().y();
        int apBefore = admitted == null ? 0 : admitted.ap();

        // The encounter advances on its own real server ticks: this stage only hands the player's turn on.
        await(1, () -> watchCaveClimb(id, 0, startY, apBefore, ownerHealth));
    }

    /** One real server tick of the cave spider's own turn, watched until it has acted and the turn is back. */
    private static void watchCaveClimb(UUID id, int guard, int startY, int apBefore, float ownerHealth) {
        if (guard > 800) {
            check(false, "the cave spider really acted inside its own turn");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the cave spider stayed on the roster");
            return;
        }
        if (!(view.paidActions() > 0 && battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchCaveClimb(id, guard + 1, startY, apBefore, ownerHealth));
            return;
        }
        check(view.cell().y() > startY,
                "the cave spider really climbed: from y=" + startY + " to y=" + view.cell().y());
        check(view.node() != null && view.node().cell().equals(view.cell()),
                "the surface node and the authoritative cell name the same position");
        check(view.ap() >= 0 && view.ap() <= view.maxAp(), "the pool stays inside its own bounds");
        check(view.ap() < apBefore, "the route was really paid for out of its own pool");
        check(view.paidActions() == 1, "exactly one paid action was counted, the melee");
        check(owner.getHealth() < ownerHealth,
                "the native bite landed from the ledge: " + owner.getHealth() + "/" + ownerHealth);
        // A6: the native attribute, the real difficulty and the HP after they were applied are three separate
        // readings. Only the direction and the ceiling are asserted; the two numbers are never forced equal.
        float delta = ownerHealth - owner.getHealth();
        double nativeAttack = caveSpider.getAttributeValue(Attributes.ATTACK_DAMAGE);
        int armour = owner.getArmorValue();
        Tactical.LOGGER.info("[Tactical] SPIDER native bite: attribute={} difficulty={} armour={} "
                        + "hpBefore={} hpAfter={} delta={}",
                nativeAttack, level.getDifficulty(), armour, ownerHealth, owner.getHealth(), delta);
        check(delta > 0.0F, "the native bite really removed health");
        check(delta <= nativeAttack + 1.0E-4D,
                "the native attack attribute is the ceiling of the real delta, never an invented value: "
                        + delta + " <= " + nativeAttack);
        check(armour == 0, "the fixture's own player wears no armour, so armour is not the reason for the delta");
        check(bystander.isAlive() && !bystander.isRemoved(), "the unrelated body took no damage");
        Vec3 held = caveSpider.position();
        check(held.y > origin.getY() + 2.0D, "the body really stands on the ledge: " + held);
        check(!battle.isBusy(), "no trajectory, fall or surface step is left running");
    }

    /** S4/S1: a surface that disappears under the route is given back, and an exit returns the control. */
    private static void routeRefundAndRelease() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        // The same wall and ledge the climb stage used: the owner is three blocks up, so a surface route is the
        // only way to reach him and it really has several edges to give back.
        owner.setPos(origin.getX() + 1.5D, origin.getY() + 3, origin.getZ() + 0.5D);
        // The previous stage's own native bite left its poison behind; it is that bite's subject, not this
        // stop's, so it is cleared here instead of being counted against the refund.
        owner.removeEffect(MobEffects.POISON);
        owner.setHealth(owner.getMaxHealth());
        float ownerHealth = owner.getHealth();
        caveSpider = spawn(EntityType.CAVE_SPIDER, -1, 0, 0);
        bystander = spawn(EntityType.COW, 16, 0, -10);
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "a second encounter starts");
        battle = BattleManager.current();
        check(battle != null && battle.isParticipant(caveSpider), "the cave spider was really deployed into it");
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider resolved its own surface node");
        int apBeforeTurn = admitted == null ? 0 : admitted.ap();

        // The encounter advances on its own real server ticks. This stage watches until a surface route is
        // really committed, then takes the wall of the next edge away under it.
        await(1, () -> watchRouteThenBreak(id, 0, apBeforeTurn, ownerHealth));
    }

    /** Watch the cave spider's own turn with real server ticks, and break the wall of an unwalked edge. */
    private static void watchRouteThenBreak(UUID id, int guard, int apBeforeTurn, float ownerHealth) {
        if (guard > 800) {
            check(false, "the encounter committed a surface route");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        if (!view.moving()) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchRouteThenBreak(id, guard + 1, apBeforeTurn, ownerHealth));
            return;
        }
        // The route is in flight and already paid: take the wall the second edge needs away.
        GridPos cellAtCommit = view.cell();
        int apAtCommit = view.ap();
        check(apAtCommit < apBeforeTurn,
                "the whole route was really paid out of the pool: " + apBeforeTurn + " -> " + apAtCommit);
        block(0, 1, 0, Blocks.AIR);
        await(2, () -> watchRefund(id, 0, apBeforeTurn, apAtCommit, cellAtCommit, ownerHealth));
    }

    /** Watch the stop and its refund with real server ticks until the encounter is quiet again. */
    private static void watchRefund(UUID id, int guard, int apBeforeTurn, int apAtCommit,
                                   GridPos cellAtCommit, float ownerHealth) {
        if (guard > 400) {
            check(false, "the broken route really settles");
            return;
        }
        if (battle.isBusy() || battle.terrainFallActive()) {
            await(1, () -> watchRefund(id, guard + 1, apBeforeTurn, apAtCommit, cellAtCommit, ownerHealth));
            return;
        }
        ServerBattle.EnemyView refunded = battle.fixtureEnemy(id);
        check(refunded != null, "the spider survived the broken surface");
        if (refunded == null) return;
        check(!refunded.moving(), "the route stopped instead of walking through the hole");
        int walked = Math.abs(refunded.cell().x() - cellAtCommit.x())
                + Math.abs(refunded.cell().y() - cellAtCommit.y())
                + Math.abs(refunded.cell().z() - cellAtCommit.z());
        check(walked <= 1, "nothing beyond the edge already in flight was walked: " + cellAtCommit + " -> "
                + refunded.cell());
        check(refunded.paidActions() == 0, "a movement refund never counts a success");
        check(refunded.ap() > apAtCommit, "the unwalked edges were given back: " + apAtCommit + " -> "
                + refunded.ap());
        check((refunded.ap() - apAtCommit) % Math.max(1, refunded.moveCost()) == 0,
                "the refund is whole edges at the actor's own price");
        check(refunded.ap() <= refunded.maxAp(), "the pool was not over-refunded");
        int spent = (apBeforeTurn - refunded.ap()) / Math.max(1, refunded.moveCost());
        check((apBeforeTurn - refunded.ap()) % Math.max(1, refunded.moveCost()) == 0,
                "the charge is whole edges only: " + apBeforeTurn + " -> " + refunded.ap());
        check(spent >= walked, "every edge the body really walked stays charged: walked=" + walked
                + " charged=" + spent);
        check(spent <= walked + 1, "at most the edge in flight is charged as well: walked=" + walked
                + " charged=" + spent);
        if (refunded.node() != null) {
            check(!refunded.node().onWall() || SpiderSurface.isLegal(level, caveSpider, refunded.node()),
                    "a wall the body still claims is really there: " + refunded.node());
        }
        check(!battle.isBusy(), "a broken surface never leaves the encounter busy");
        check(owner.getHealth() == ownerHealth, "the other member's health was never touched by the stop");
        check(bystander.isAlive() && !bystander.isRemoved(), "a body outside this unit was never touched");
        check(!caveSpider.isClimbing() || SpiderSurface.holdsWall(level, caveSpider,
                        SpiderSurface.nodeAt(level, caveSpider,
                                new GridPos(caveSpider.getBlockX(), caveSpider.getBlockY(),
                                        caveSpider.getBlockZ()), caveSpider.position())),
                "the climbing bit is only set while the body really holds a wall: " + caveSpider.position());
        // The wall is restored, and the encounter can still be left cleanly.
        block(0, 1, 0, Blocks.STONE);
        Vec3 realPosition = caveSpider.position();
        battle.finish("fixture release");
        check(((TacticalSpiderAccess) caveSpider).tactical$poseHandler() == null,
                "ending the encounter clears the pose seam");
        check(caveSpider.position().distanceToSqr(realPosition) < 1.0E-4D,
                "a normal exit keeps the real final position, it never teleports the unit back");
        check(battle.fixtureEnemy(id) == null, "no roster entry is left behind");
        check(!caveSpider.isClimbing() || caveSpider.position().y > origin.getY(),
                "the borrowed climbing bit is not left set on a body back on the floor");
        // Only the two spider types ever carry a pose seam; an unrelated body never does.
        check(caveSpider instanceof TacticalSpiderAccess && !(bystander instanceof TacticalSpiderAccess),
                "only the two spider types carry the pose seam");
    }

    // ------------------------------------------------------------------ B: the short leap

    /**
     * The fixture's own quiet corner for the leap and poison stages.
     *
     * <p>A separate corner of the arena on purpose: every earlier stage built its own walls and platforms, and
     * a stage that measures one arc must not inherit them. The corner is cleared, its floor is restored and only
     * what that stage needs is built on top.</p>
     */
    private static final int B_DX = -5;
    private static final int B_DZ = -9;

    private static GridPos bCell(int dx, int dy, int dz) { return cell(B_DX + dx, dy, B_DZ + dz); }

    private static void bBlock(int dx, int dy, int dz, Block block) {
        block(B_DX + dx, dy, B_DZ + dz, block);
    }

    /** Put a block exactly at one authoritative cell, whatever the leap proposed. */
    private static void at(GridPos cell, Block block) {
        level.setBlock(new BlockPos(cell.x(), cell.y(), cell.z()), block.defaultBlockState(), 2);
    }

    private static void bClear() {
        for (int dx = -4; dx <= 5; dx++) {
            for (int dz = -4; dz <= 5; dz++) {
                for (int dy = 0; dy <= 8; dy++) bBlock(dx, dy, dz, Blocks.AIR);
                bBlock(dx, -1, dz, Blocks.STONE);
            }
        }
    }

    /**
     * The one arena the leap cases use.
     *
     * <p>The opponent stands two cells away on the same floor, with the two orthogonal cells between them taken
     * out of the world: the diagonal landing beside him is a real floor that no four-way route reaches cheaply,
     * so the leap really is the cheaper way in and is the only thing these cases measure. The arc itself flies
     * over nothing but air.</p>
     */
    private static void leapArena() {
        bClear();
        bBlock(1, -1, 0, Blocks.AIR);
        bBlock(0, -1, 1, Blocks.AIR);
        owner.setPos(origin.getX() + B_DX + 1.5D, origin.getY(), origin.getZ() + B_DZ + 2.5D);
        owner.setHealth(owner.getMaxHealth());
        biteDamage = -1.0F;
        biteCount = 0;
        poisonPulseCount = 0;
        poisonPulseDamage = 0.0F;
        caveSpider = spawn(EntityType.CAVE_SPIDER, B_DX, 0, B_DZ);
        bystander = spawn(EntityType.COW, 16, 0, 12);
    }

    /**
     * S6: the arc's own gates, asked of the live world with the same sweep the executor uses.
     *
     * <p>Water, a ceiling, another body, a battlefield edge and an unloaded chunk are five different reasons an
     * arc may not be flown. None of them is faked: a real source block, a real solid block, a real second
     * entity, a real zone predicate and a real unloaded chunk are put in the way of one real proposal.</p>
     */
    private static void leapArcGates() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        SpiderSurface.Node from = SpiderSurface.nodeAt(level, caveSpider,
                new GridPos(caveSpider.getBlockX(), caveSpider.getBlockY(), caveSpider.getBlockZ()),
                caveSpider.position());
        check(from != null && !from.onWall(), "the leaping body stands on a real floor");
        java.util.Optional<SpiderLeap.Proposal> found = SpiderLeap.propose(level, caveSpider, from, owner,
                cell -> false, cell -> true, node -> true);
        check(found.isPresent(), "a real leap proposal exists over the platform edge");
        SpiderLeap.Proposal proposal = found.orElse(null);
        Vec3 start = caveSpider.position();
        check(proposal != null && SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "the untouched arc is really clear: " + proposal);
        if (proposal == null) return;
        check(Math.abs(proposal.landing().cell().y() - from.cell().y()) <= 1,
                "the landing is at most one block above or below the take-off");

        // A liquid of any kind refuses the whole arc, exactly as it refuses a standing position.
        bBlock(0, 1, 1, Blocks.WATER);
        check(!SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "a liquid in the arc refuses the leap");
        bBlock(0, 1, 1, Blocks.AIR);
        check(SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "removing the liquid makes the same arc legal again");

        // A low ceiling: a solid block where the arc's own top has to pass.
        bBlock(0, 1, 1, Blocks.STONE);
        check(!SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "a ceiling at the arc's top refuses the leap");
        bBlock(0, 1, 1, Blocks.AIR);

        // Another real body in the arc.
        blocker = spawnPrecise(EntityType.COW, origin.getX() + B_DX + 0.5D, origin.getY() + 1.0D,
                origin.getZ() + B_DZ + 1.5D);
        check(!SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "another body in the arc refuses the leap");
        blocker.discard();
        blocker = null;

        // A battlefield the arc would leave: the landing column is outside it.
        check(!SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false,
                        cell -> cell.z() < origin.getZ() + B_DZ + 1),
                "an arc whose body leaves the battlefield is refused");
        check(SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> false, cell -> true),
                "the same arc still holds inside the real battlefield");

        // The roster is asked about the cells the real foot point passes through - never about every cell the
        // whole wide body touches, which would refuse a legal landing beside the opponent.
        GridPos apex = proposal.footCell(start, 6);
        check(!SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> cell.equals(apex), cell -> true),
                "a participant's authoritative cell on the foot path refuses the leap: " + apex);
        GridPos ownerCell = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
        check(!apex.equals(ownerCell), "the opponent's own cell is not on the foot path: " + ownerCell);
        check(SpiderLeap.arcHolds(level, caveSpider, start, proposal, cell -> cell.equals(ownerCell),
                        cell -> true),
                "the opponent's neighbouring cell never refuses a legal landing");
        check(!SpiderLeap.footPathClear(proposal.point(start, 5), proposal.point(start, 6),
                        cell -> cell.equals(apex))
                        && !SpiderLeap.footPathClear(proposal.point(start, 6), proposal.point(start, 7),
                        cell -> cell.equals(apex)),
                "the foot-path gate really reads every cell the feet pass through, leg by leg");

        // A corner the foot point only crosses briefly: a fixed-distance sampler can step over the cell the
        // path really enters, so the gate is asked with the exact segment from the review.
        Vec3 cornerFrom = new Vec3(origin.getX() + 0.95D, origin.getY(), origin.getZ() + 0.94D);
        Vec3 cornerTo = new Vec3(origin.getX() + 1.07D, origin.getY(), origin.getZ() + 1.065D);
        GridPos cornerCell = new GridPos(origin.getX() + 1, origin.getY(), origin.getZ());
        GridPos untouchedNeighbour = new GridPos(origin.getX(), origin.getY(), origin.getZ() + 1);
        Tactical.LOGGER.info("[Tactical] SPIDER foot path corner: from={} to={} crossed={} neighbour={}",
                cornerFrom, cornerTo, cornerCell, untouchedNeighbour);
        check(!SpiderLeap.footPathClear(cornerFrom, cornerTo, cell -> cell.equals(cornerCell)),
                "a cell the feet only clip at a corner is still read: " + cornerCell);
        check(!SpiderLeap.footPathClear(cornerTo, cornerFrom, cell -> cell.equals(cornerCell)),
                "and the same corner is refused in the other direction too: " + cornerCell);
        check(SpiderLeap.footPathClear(cornerFrom, cornerTo, cell -> cell.equals(untouchedNeighbour)),
                "a neighbouring cell the segment never enters is not falsely refused: " + untouchedNeighbour);
        check(!SpiderLeap.footPathClear(new Vec3(Double.NaN, origin.getY(), origin.getZ()),
                        cornerTo, cell -> cell.equals(cornerCell)),
                "an unbounded or non-finite leg is refused instead of sampled");
        check(!SpiderLeap.footPathClear(cornerFrom,
                        new Vec3(origin.getX() + 64.95D, origin.getY(), origin.getZ() + 0.94D),
                        cell -> cell.equals(cornerCell)),
                "a leg far longer than one animation step is refused");

        // An unloaded chunk is refused before any state in it is read, and asking does not load it.
        Vec3 far = new Vec3(origin.getX() + 4096.5D, origin.getY(), origin.getZ() + 4096.5D);
        check(!level.hasChunkAt(BlockPos.containing(far)), "the probe column starts unloaded");
        check(!SpiderLeap.sliceClear(level, caveSpider, far, far.add(1.0D, 0.0D, 0.0D), cell -> true),
                "an arc through an unloaded chunk is refused");
        check(!level.hasChunkAt(BlockPos.containing(far)), "asking about it did not force-load the chunk");
        check(caveSpider.isAlive() && !caveSpider.isRemoved(), "none of this touched the body itself");
    }

    /**
     * S6/B2: the arc's frozen numbers, and the real distance band it is quoted on.
     *
     * <p>The curve keeps the endpoints exactly, reaches the declared apex exactly at the midpoint and never
     * rises above it - for a flat leap and for both one-block cases. The band itself is the native leap goal's
     * real 3D distance, so the same horizontal offset five blocks up is refused while the same bodies on one
     * floor are a legal leap again.</p>
     */
    private static void leapCurveAndRangeGates() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        SpiderSurface.Node from = SpiderSurface.Node.ground(cell(0, 0, 0));
        Vec3 start = new Vec3(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        for (SpiderSurface.Node landing : List.of(
                SpiderSurface.Node.ground(cell(2, 0, 2)),
                SpiderSurface.Node.ground(cell(2, 1, 2)),
                SpiderSurface.Node.ground(cell(2, -1, 2)))) {
            SpiderLeap.Proposal proposal = new SpiderLeap.Proposal(from, landing, SpiderLeap.TICKS);
            check(proposal.point(start, 0).distanceToSqr(start) < 1.0E-12D,
                    "frame 0 is exactly the take-off feet: " + landing);
            check(proposal.point(start, proposal.ticks()).distanceToSqr(landing.feet()) < 1.0E-12D,
                    "the last frame is exactly the landing feet: " + landing);
            double apex = proposal.apexY(start);
            check(Math.abs(apex - (Math.max(start.y, landing.feet().y) + 1.0D)) < 1.0E-12D,
                    "the frozen apex is exactly one block above the higher end: " + landing);
            check(Math.abs(proposal.point(start, 6).y - apex) < 1.0E-12D,
                    "the midpoint of the armed arc is exactly that apex: " + proposal.point(start, 6).y
                            + " vs " + apex);
            double highest = Double.NEGATIVE_INFINITY;
            for (int frame = 0; frame <= proposal.ticks(); frame++) {
                highest = Math.max(highest, proposal.point(start, frame).y);
            }
            check(Math.abs(highest - apex) < 1.0E-12D,
                    "no frame rises above the frozen apex: " + highest + " vs " + apex);
            check(proposal.point(start, 6).y >= proposal.point(start, 5).y
                            && proposal.point(start, 6).y >= proposal.point(start, 7).y,
                    "the apex is the arc's own turning point: " + landing);
        }

        // The band is a real 3D distance: the same horizontal offset, five blocks higher, is not a leap.
        bClear();
        for (int dy = 0; dy <= 4; dy++) bBlock(1, dy, 2, Blocks.STONE);
        owner.setPos(origin.getX() + B_DX + 1.5D, origin.getY() + 5, origin.getZ() + B_DZ + 2.5D);
        caveSpider = spawn(EntityType.CAVE_SPIDER, B_DX, 0, B_DZ);
        SpiderSurface.Node here = SpiderSurface.nodeAt(level, caveSpider, bCell(0, 0, 0),
                caveSpider.position());
        check(here != null && !here.onWall(), "the spider stands on a real floor under the pillar");
        double realRange = SpiderLeap.rangeTo(caveSpider, owner);
        double horizontal = Math.hypot(owner.getX() - caveSpider.getX(), owner.getZ() - caveSpider.getZ());
        Tactical.LOGGER.info("[Tactical] SPIDER leap range: real={} horizontal={} band={}..{}",
                realRange, horizontal, SpiderLeap.MIN_RANGE, SpiderLeap.MAX_RANGE);
        check(horizontal <= SpiderLeap.MAX_RANGE,
                "the horizontal offset alone would still look inside the band: " + horizontal);
        check(realRange > SpiderLeap.MAX_RANGE,
                "while the real 3D distance really is above it: " + realRange);
        check(SpiderLeap.propose(level, caveSpider, here, owner, cell -> false, cell -> true,
                node -> true).isEmpty(), "a target vertically out of the band is refused");
        check(!SpiderLeap.inRange(caveSpider, owner), "the shared distance gate says so too");

        // Control: the same offset on the same floor is a legal leap again. The pillar spider is discarded
        // first, or two bodies would occupy the same take-off and the world would rightly refuse both.
        discardAll();
        leapArena();
        SpiderSurface.Node again = SpiderSurface.nodeAt(level, caveSpider, bCell(0, 0, 0),
                caveSpider.position());
        check(again != null, "the control spider holds a real node");
        check(SpiderLeap.inRange(caveSpider, owner), "the control distance really is inside the band: "
                + SpiderLeap.rangeTo(caveSpider, owner));
        check(SpiderLeap.propose(level, caveSpider, again, owner, cell -> false, cell -> true,
                node -> true).isPresent(), "and the same offset on the floor is a proposal again");
    }

    /** S5: the real leap - committed unpaid, paid once at the first displacement, then one ordinary bite. */
    private static void spiderLeapsOverAPlatformEdgeAndBites() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the leap encounter starts");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the leaping spider holds a real surface node");
        check(admitted != null && !admitted.node().onWall(), "it takes off from a real floor");
        check(admitted != null && admitted.leapsUsed() == 0, "it has not leaped before its own turn");
        int apBefore = admitted == null ? 0 : admitted.ap();
        int leapPrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.BattleRules.SPIDER_LEAP_COST, 0);
        int meleePrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.ApCalculator.MELEE_COST, 1);
        Tactical.LOGGER.info("[Tactical] SPIDER leap prices: leap={} meleeAfterLeap={} ap={}",
                leapPrice, meleePrice, apBefore);
        await(0, () -> watchLeap(id, 0, apBefore, leapPrice, meleePrice, owner.getHealth(), 0));
    }

    /** One real server tick of the leap, watched phase by phase. */
    private static void watchLeap(UUID id, int guard, int apBefore, int leapPrice, int meleePrice,
                                  float ownerHealth, int phase) {
        if (guard > 900) {
            check(false, "the spider really leaped and bit inside its own turn (phase " + phase + ")");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the leaping spider stayed on the roster");
            return;
        }
        if (phase == 0) {
            if (!view.leaping() || view.leapFrames() != 0 || view.leapPaid()) {
                if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
                await(0, () -> watchLeap(id, guard + 1, apBefore, leapPrice, meleePrice, ownerHealth, 0));
                return;
            }
            check(view.ap() == apBefore && view.paidActions() == 0,
                    "a committed leap that has not moved the body has cost nothing: ap=" + view.ap()
                            + " paid=" + view.paidActions());
            check(view.leapsUsed() == 0, "and has not spent the one leap of this own turn yet");
            check(view.leapLanding() != null, "the committed leap names one real landing: " + view.leapLanding());
            GridPos landing = view.leapLanding();
            await(0, () -> watchLeapLanded(id, guard + 1, apBefore, leapPrice, meleePrice, ownerHealth, landing));
            return;
        }
    }

    /** The same leap, watched from its first bought frame to the end of its own turn. */
    private static void watchLeapLanded(UUID id, int guard, int apBefore, int leapPrice, int meleePrice,
                                       float ownerHealth, GridPos landing) {
        if (guard > 900) {
            check(false, "the spider really leaped and bit inside its own turn");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the leaping spider stayed on the roster");
            return;
        }
        if (view.leaping() && view.leapPaid() && view.leapFrames() > 0) {
            check(view.ap() == apBefore - leapPrice,
                    "the frozen leap price was taken exactly once: " + apBefore + " -> " + view.ap()
                            + " (price " + leapPrice + ")");
            check(view.paidActions() == 1, "and the leap counted as one paid action");
            check(view.leapsUsed() == 1, "the one leap of this own turn is spent");
            check(view.cell().equals(bCell(0, 0, 0)),
                    "the authoritative cell is still the take-off cell while the body is in the air: " + view.cell());
            check(caveSpider.position().distanceToSqr(origin.getX() + B_DX + 0.5D, origin.getY(),
                    origin.getZ() + B_DZ + 0.5D) > 0.01D, "the body really left the floor: " + caveSpider.position());
            await(0, () -> watchLeapQuiet(id, guard + 1, apBefore, leapPrice, meleePrice, ownerHealth, landing));
            return;
        }
        await(0, () -> watchLeapLanded(id, guard + 1, apBefore, leapPrice, meleePrice, ownerHealth, landing));
    }

    /** The quiet end of the leap's own turn: the landing, the one bite and nothing else. */
    private static void watchLeapQuiet(UUID id, int guard, int apBefore, int leapPrice, int meleePrice,
                                       float ownerHealth, GridPos landing) {
        if (guard > 900) {
            check(false, "the leaped turn really ended");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the leaping spider stayed on the roster");
            return;
        }
        if (battle.isBusy() || !battle.isPlayerTurnNow()) {
            await(0, () -> watchLeapQuiet(id, guard + 1, apBefore, leapPrice, meleePrice, ownerHealth, landing));
            return;
        }
        check(!view.leaping(), "the leap is over instead of leaving a permanent animation");
        check(view.leapsUsed() == 1, "exactly one leap was taken in that own turn");
        check(view.paidActions() == 2, "the leap and the bite are exactly two paid actions: " + view.paidActions());
        check(view.ap() == apBefore - leapPrice - meleePrice,
                "the two prices are the whole spend: " + apBefore + " -> " + view.ap()
                        + " (leap " + leapPrice + " + melee " + meleePrice + ")");
        check(landing != null && view.cell().equals(landing),
                "it really came down on the proposed landing: " + view.cell() + " vs " + landing);
        check(Math.abs(view.cell().x() - bCell(0, 0, 0).x()) + Math.abs(view.cell().y() - bCell(0, 0, 0).y())
                        + Math.abs(view.cell().z() - bCell(0, 0, 0).z()) == 2,
                "the leap really covered two cells instead of one step: " + view.cell());
        check(view.node() != null && !view.node().onWall(), "it came down on a real floor");
        GridPos ownerCell = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
        check(com.tactical.core.grid.GridMovementRules.canStep(view.cell(), ownerCell),
                "the landing is a cell from which the opponent is really adjacent: " + view.cell()
                        + " vs " + ownerCell);
        float delta = ownerHealth - owner.getHealth();
        double nativeAttack = caveSpider.getAttributeValue(Attributes.ATTACK_DAMAGE);
        Tactical.LOGGER.info("[Tactical] SPIDER leap bite: native={} applied={} difficulty={} hpBefore={} "
                        + "hpAfter={} delta={} bites={}", nativeAttack, biteDamage, level.getDifficulty(),
                ownerHealth, owner.getHealth(), delta, biteCount);
        check(biteCount == 1, "the leap and its landing caused exactly one native hit: " + biteCount);
        check(delta > 0.0F, "the landing bite really removed health");
        check(biteDamage > 0.0F && delta <= biteDamage + 1.0E-4D,
                "the health lost is at most the authoritative hit: " + delta + " <= " + biteDamage);
        check(delta <= nativeAttack * 3.0D + 1.0E-4D,
                "and the encounter's own modifiers never invent a large hit: " + delta + " on "
                        + nativeAttack);
        check(!battle.isBusy(), "nothing is left in flight after the landing");
        float afterBite = owner.getHealth();
        await(10, () -> {
            check(owner.getHealth() == afterBite,
                    "the leap itself and its landing hurt nothing else: " + afterBite + " -> " + owner.getHealth());
            check(!battle.isBusy(), "and the encounter is still quiet");
            battle.finish("fixture leap done");
        });
    }

    /**
     * S5/B1: the launch conditions are re-proved at the first frame, so a change between the commit and the
     * first real displacement costs nothing.
     *
     * <p>Four different things can happen in that window and none of them may buy a leap: the landing is taken,
     * the floor under the take-off is taken, the rest of the arc is sealed, or the pool no longer covers the
     * frozen price. All four are refused before the body moves, so nothing is charged, no success is counted and
     * the one leap of that own turn stays available.</p>
     */
    private static void spiderLeapRefusedFreeBeforeLift() {
        launchRefusalStage(0);
    }

    /** S5/B1: the floor under the take-off is taken away before the first frame. */
    private static void spiderLeapRefusedWhenSupportIsGone() {
        launchRefusalStage(1);
    }

    /** S5/B1: the second half of the arc is sealed before the first frame. */
    private static void spiderLeapRefusedWhenTheRestOfArcIsSealed() {
        launchRefusalStage(2);
    }

    /** S5/B1: the pool no longer covers the frozen price at the first frame. */
    private static void spiderLeapRefusedWhenPoolCannotPay() {
        launchRefusalStage(3);
    }

    private static void launchRefusalStage(int stimulus) {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the refused-launch encounter starts (stimulus " + stimulus + ")");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider holds a real node");
        int apBefore = admitted == null ? 0 : admitted.ap();
        GridPos takeoff = admitted == null ? null : admitted.cell();
        Vec3 feet = caveSpider.position();
        await(0, () -> watchLaunchStimulus(id, 0, apBefore, takeoff, feet, stimulus));
    }

    /** Wait for the committed leap to reach its first frame, then change what the launch relied on. */
    private static void watchLaunchStimulus(UUID id, int guard, int apBefore, GridPos takeoff, Vec3 feet,
                                            int stimulus) {
        if (guard > 600) {
            check(false, "the leap was committed before its first frame (stimulus " + stimulus + ")");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        if (view.leaping() && view.leapFrames() == 0 && !view.leapPaid()) {
            GridPos landing = view.leapLanding();
            check(landing != null, "the committed leap names one real landing: " + landing);
            switch (stimulus) {
                case 0 -> {
                    if (landing != null) at(landing, Blocks.STONE);
                }
                case 1 -> {
                    // The floor it stands on is gone; a floor one cell lower catches the fall it then deserves.
                    bBlock(0, -1, 0, Blocks.AIR);
                    bBlock(0, -2, 0, Blocks.STONE);
                }
                case 2 -> bBlock(0, 1, 2, Blocks.STONE);
                default -> battle.fixtureEnemyAp(id, 4);
            }
            await(3, () -> watchLaunchRefused(id, 0, apBefore, takeoff, feet, landing, stimulus));
            return;
        }
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
        await(0, () -> watchLaunchStimulus(id, guard + 1, apBefore, takeoff, feet, stimulus));
    }

    /** The quiet end of a launch that was refused for free. */
    private static void watchLaunchRefused(UUID id, int guard, int apBefore, GridPos takeoff, Vec3 feet,
                                           GridPos landing, int stimulus) {
        if (guard > 400) {
            check(false, "a launch refused before its first frame really settles (stimulus " + stimulus + ")");
            return;
        }
        if (battle.isBusy() || !battle.isPlayerTurnNow()) {
            await(0, () -> watchLaunchRefused(id, guard + 1, apBefore, takeoff, feet, landing, stimulus));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        check(view != null, "the spider is still on the roster");
        if (view == null) return;
        check(!view.leaping(), "the refused leap left no animation behind");
        int apNow = stimulus == 3 ? 4 : apBefore;
        check(view.ap() == apNow, "a leap that never moved the body cost nothing: " + apNow + " -> " + view.ap());
        check(view.paidActions() == 0, "and counted no paid action");
        check(view.leapsUsed() == 0, "and did not spend the one leap of this own turn");
        check(Math.abs(caveSpider.position().x - feet.x) < 1.0E-6D
                        && Math.abs(caveSpider.position().z - feet.z) < 1.0E-6D,
                "the body never advanced toward its landing: " + caveSpider.position());
        if (stimulus == 1) {
            check(caveSpider.position().y <= feet.y + 1.0E-6D,
                    "and only went down with the floor it lost: " + caveSpider.position());
            check(takeoff != null && view.cell().x() == takeoff.x() && view.cell().z() == takeoff.z()
                            && view.cell().y() <= takeoff.y(),
                    "the authoritative cell moved down, never sideways: " + view.cell() + " vs " + takeoff);
        } else {
            check(caveSpider.position().distanceToSqr(feet) < 1.0E-6D,
                    "the body never left its node: " + caveSpider.position() + " vs " + feet);
            check(takeoff != null && view.cell().equals(takeoff),
                    "the authoritative cell did not move: " + view.cell());
        }
        check(((TacticalSpiderAccess) caveSpider).tactical$poseHandler() != null,
                "the encounter still holds the body it never moved");
        check(!battle.isBusy() && !battle.terrainFallActive(),
                "a refused launch leaves no busy state and no running fall");
        if (landing != null && stimulus == 0) at(landing, Blocks.AIR);
        bBlock(0, 1, 2, Blocks.AIR);
        battle.finish("fixture launch refused (" + stimulus + ")");
    }

    /** S5: a leap the pool cannot pay for is never taken, and nothing about it is charged. */
    private static void spiderLeapUnpayableIsFree() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        TacticalConfigSplit.leapCost(200);
        check(BattleManager.start(owner, true), "the unpayable-leap encounter starts");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider holds a real node");
        check(admitted != null && admitted.ap() < TacticalConfigSplit.leapCost(),
                "the quoted leap price really is above this unit's pool: " + (admitted == null ? -1 : admitted.ap()));
        int apBefore = admitted == null ? 0 : admitted.ap();
        int moveCost = admitted == null ? 1 : admitted.moveCost();
        float ownerHealth = owner.getHealth();
        await(0, () -> watchUnpayableLeap(id, 0, apBefore, moveCost, ownerHealth, 0));
    }

    private static void watchUnpayableLeap(UUID id, int guard, int apBefore, int moveCost, float ownerHealth,
                                           int leapsSeen) {
        if (guard > 500) {
            check(false, "the unpayable spider still finished its own turn");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        int seen = leapsSeen + (view.leaping() ? 1 : 0);
        if (!(battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchUnpayableLeap(id, guard + 1, apBefore, moveCost, ownerHealth, seen));
            return;
        }
        check(seen == 0 && view.leapsUsed() == 0, "a leap above the pool is never taken");
        boolean bit = owner.getHealth() < ownerHealth;
        int meleePrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.ApCalculator.MELEE_COST, 0);
        int spent = apBefore - view.ap();
        check(view.paidActions() == (bit ? 1 : 0),
                "only a real bite counts a paid action: paid=" + view.paidActions() + " bit=" + bit);
        check(spent - (bit ? meleePrice : 0) >= 0, "no leap price was taken: spent=" + spent);
        check((spent - (bit ? meleePrice : 0)) % Math.max(1, moveCost) == 0,
                "the whole spend is walk edges plus at most one melee: spent=" + spent + " move=" + moveCost
                        + " melee=" + meleePrice);
        TacticalConfigSplit.leapCost(defaultLeapCost > 0 ? defaultLeapCost : 0);
        battle.finish("fixture unpayable leap done");
    }

    /**
     * S6: the arc is interrupted mid-flight, so the leap stops where the body really is.
     *
     * <p>No refund, no second action, no pull-back to the take-off point and no permanent busy - the existing
     * fall rule owns whatever the body does next.</p>
     */
    private static void spiderLeapStopsWhenTheArcIsBlocked() {
        resetFallBarrier();
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the blocked-arc encounter starts");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider holds a real node");
        int apBefore = admitted == null ? 0 : admitted.ap();
        int leapPrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.BattleRules.SPIDER_LEAP_COST, 0);
        float ownerHealth = owner.getHealth();
        await(0, () -> watchLeapThenBlockArc(id, 0, apBefore, leapPrice, ownerHealth));
    }

    private static void watchLeapThenBlockArc(UUID id, int guard, int apBefore, int leapPrice, float ownerHealth) {
        if (guard > 400) {
            check(false, "the leap really reached the middle of its arc");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        if (view.leaping() && view.leapPaid() && view.leapFrames() >= 1 && view.leapFrames() <= 5) {
            // The landing is taken while the body is really in the air: the leap is already bought, so it can
            // only be stopped where the body is - never refunded and never finished onto a place that is gone.
            GridPos landing = view.leapLanding();
            check(landing != null, "the flying leap names one real landing: " + landing);
            if (landing != null) at(landing, Blocks.STONE);
            await(3, () -> watchLeapAbort(id, 0, apBefore, leapPrice, ownerHealth, caveSpider.position(), landing));
            return;
        }
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
        await(0, () -> watchLeapThenBlockArc(id, guard + 1, apBefore, leapPrice, ownerHealth));
    }

    private static void watchLeapAbort(UUID id, int guard, int apBefore, int leapPrice, float ownerHealth,
                                       Vec3 atAbort, GridPos landing) {
        if (guard > 400) {
            check(false, "an interrupted leap really settles");
            return;
        }
        checkFallBarrierIsExclusive("the interrupted leap");
        if (battle.isBusy() || battle.terrainFallActive() || !battle.isPlayerTurnNow()) {
            await(0, () -> watchLeapAbort(id, guard + 1, apBefore, leapPrice, ownerHealth, atAbort, landing));
            return;
        }
        if (fallBarrierEpoch >= 0) checkFallHandedOffOnce("the interrupted leap");
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        check(view != null, "the spider survived the interrupted leap");
        if (view == null) return;
        check(!view.leaping(), "an interrupted leap leaves no animation behind");
        check(view.leapsUsed() == 1, "the leap was really taken and stays spent");
        check(view.paidActions() == 1, "no second action was invented after the interruption");
        check(view.ap() == apBefore - leapPrice,
                "an interrupted leap is never refunded: " + apBefore + " -> " + view.ap());
        check(owner.getHealth() == ownerHealth, "the interruption hurt nobody");
        check(landing == null || !view.cell().equals(landing),
                "the body was never teleported onto the refused landing");
        check(Math.hypot(caveSpider.position().x - (origin.getX() + B_DX + 0.5D),
                        caveSpider.position().z - (origin.getZ() + B_DZ + 0.5D)) > 0.05D,
                "the body was never pulled back to its take-off point: " + caveSpider.position());
        check(atAbort != null && Math.abs(caveSpider.position().z - atAbort.z) < 1.5D,
                "the body stayed near where it really was when the arc closed: " + caveSpider.position());
        check(!battle.isBusy(), "an interrupted leap leaves no permanent busy state");
        check(caveSpider.isAlive() && !caveSpider.isRemoved(), "the body itself is untouched");
        if (landing != null) at(landing, Blocks.AIR);
        battle.finish("fixture blocked arc done");
    }

    /**
     * S6: the leap lands, but the follow-up bite is refused because the real eye line is blocked.
     *
     * <p>The landing is untouched, the leap is not refunded, and the turn is simply handed over: the spider may
     * neither walk nor leap again inside the same own turn.</p>
     */
    private static void spiderLeapLandsWithoutABite() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the no-bite encounter starts");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider holds a real node");
        int apBefore = admitted == null ? 0 : admitted.ap();
        int leapPrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.BattleRules.SPIDER_LEAP_COST, 0);
        float ownerHealth = owner.getHealth();
        await(0, () -> watchLeapThenBlockSight(id, 0, apBefore, leapPrice, ownerHealth));
    }

    private static void watchLeapThenBlockSight(UUID id, int guard, int apBefore, int leapPrice,
                                                float ownerHealth) {
        if (guard > 400) {
            check(false, "the leap really reached the middle of its arc");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        if (view.leaping() && view.leapPaid() && view.leapFrames() >= 3) {
            // The opponent's own cell is filled, so the real eye line from the landing is blocked; the landing
            // itself stays legal, which is what separates this case from an interrupted arc.
            // Both cells of the opponent's body: the eye line runs from the short body's own eyes up to his, so
            // a single block would only graze it.
            GridPos ownerCell = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
            at(ownerCell, Blocks.STONE);
            at(new GridPos(ownerCell.x(), ownerCell.y() + 1, ownerCell.z()), Blocks.STONE);
            await(3, () -> watchLeapNoBite(id, 0, apBefore, leapPrice, ownerHealth, view.leapLanding(),
                    ownerCell));
            return;
        }
        if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
        await(0, () -> watchLeapThenBlockSight(id, guard + 1, apBefore, leapPrice, ownerHealth));
    }

    private static void watchLeapNoBite(UUID id, int guard, int apBefore, int leapPrice, float ownerHealth,
                                        GridPos landing, GridPos ownerCell) {
        if (guard > 400) {
            check(false, "a leap that cannot bite hands the turn over");
            return;
        }
        if (battle.isBusy() || !battle.isPlayerTurnNow()) {
            await(0, () -> watchLeapNoBite(id, guard + 1, apBefore, leapPrice, ownerHealth, landing, ownerCell));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        check(view != null, "the spider is still on the roster");
        if (view == null) return;
        check(!view.leaping(), "the leap finished");
        check(landing != null && view.cell().equals(landing),
                "it really came down on the proposed landing: " + view.cell() + " vs " + landing);
        check(view.leapsUsed() == 1, "the leap stays spent");
        check(view.paidActions() == 1, "a refused bite is never charged: paid=" + view.paidActions());
        check(view.ap() == apBefore - leapPrice,
                "and the leap is not refunded: " + apBefore + " -> " + view.ap());
        check(owner.getHealth() == ownerHealth, "the refused bite hurt nobody");
        check(!view.moving(), "the spider neither walked nor leaped again inside that own turn");
        at(ownerCell, Blocks.AIR);
        at(new GridPos(ownerCell.x(), ownerCell.y() + 1, ownerCell.z()), Blocks.AIR);
        battle.finish("fixture leap without a bite done");
    }

    /**
     * S6/B1: the body is already in the air when the opponent it locked dies.
     *
     * <p>A second real member keeps the encounter alive, which is the only way a leap that has already left the
     * floor can really finish. It must land where it was aimed, keep its price, and neither be refunded nor pick
     * the remaining member as a fresh victim.</p>
     */
    private static void spiderLeapKeepsFlyingAfterTargetLoss() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        guest = new net.neoforged.neoforge.common.util.FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("tactical-spider-guest".getBytes(StandardCharsets.UTF_8)),
                "SpiderGuest"));
        guest.setPos(origin.getX() + B_DX + 4.5D, origin.getY(), origin.getZ() + B_DZ + 4.5D);
        guest.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        guest.setHealth(1000);
        guest.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(guest);
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the two-member encounter starts");
        battle = BattleManager.current();
        check(BattleManager.invite(owner, guest) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the second member is invited");
        var joined = BattleManager.acceptHeadless(guest);
        check(joined == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the second member really joins: " + joined);
        check(battle.memberCount() == 2, "the encounter really has two members: " + battle.memberCount());
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the leaping spider holds a real node");
        int apBefore = admitted == null ? 0 : admitted.ap();
        int leapPrice = com.tactical.core.combat.ApCalculator.paidActionCost(
                com.tactical.core.combat.BattleRules.SPIDER_LEAP_COST, 0);
        float guestHealth = guest.getHealth();
        await(0, () -> watchLeapThenLoseTarget(id, 0, apBefore, leapPrice, guestHealth));
    }

    private static void watchLeapThenLoseTarget(UUID id, int guard, int apBefore, int leapPrice,
                                                float guestHealth) {
        if (guard > 700) {
            check(false, "the leap really started before the locked opponent died");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        GridPos ownerCell = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
        if (!(view.leaping() && view.leapPaid() && view.leapFrames() >= 2)) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchLeapThenLoseTarget(id, guard + 1, apBefore, leapPrice, guestHealth));
            return;
        }
        GridPos landing = view.leapLanding();
        check(landing != null && com.tactical.core.grid.GridMovementRules.canStep(landing, ownerCell),
                "the leap was aimed at the member it locked: " + landing + " vs " + ownerCell);
        check(owner.isAlive(), "the locked member is still alive when the body leaves the floor");
        owner.kill();
        check(!owner.isAlive(), "the locked member really died while the body was in the air");
        await(3, () -> watchLeapAfterTargetLoss(id, 0, apBefore, leapPrice, guestHealth, landing));
    }

    private static void watchLeapAfterTargetLoss(UUID id, int guard, int apBefore, int leapPrice,
                                                 float guestHealth, GridPos landing) {
        if (guard > 500) {
            check(false, "a leap whose target died still finishes");
            return;
        }
        if (battle.isBusy()) {
            await(0, () -> watchLeapAfterTargetLoss(id, guard + 1, apBefore, leapPrice, guestHealth, landing));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        check(view != null, "the spider is still on the roster");
        if (view == null) return;
        check(!view.leaping(), "the leap finished instead of hanging in the air");
        check(landing != null && view.cell().equals(landing),
                "it landed exactly where it was aimed: " + view.cell() + " vs " + landing);
        check(view.leapsUsed() == 1, "the leap stays spent");
        check(view.paidActions() == 1,
                "no bite was invented for a target that is gone: paid=" + view.paidActions());
        check(view.ap() == apBefore - leapPrice,
                "and the leap is not refunded: " + apBefore + " -> " + view.ap());
        check(guest.getHealth() == guestHealth,
                "no other member was picked as a fresh victim: " + guest.getHealth());
        check(!view.moving(), "the turn was handed over instead of starting a second action");
        battle.finish("fixture leap after target loss done");
    }

    /** S6: the encounter ends while the body is in the air; control is given back in one bounded place. */
    private static void spiderLeapFinishedMidAir() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        leapArena();
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the finish-mid-air encounter starts");
        battle = BattleManager.current();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.node() != null, "the spider holds a real node");
        await(0, () -> watchLeapThenFinish(id, 0));
    }

    private static void watchLeapThenFinish(UUID id, int guard) {
        if (guard > 400) {
            check(false, "the leap really started before the encounter ended");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the spider stayed on the roster");
            return;
        }
        if (!(view.leaping() && view.leapPaid() && view.leapFrames() >= 2)) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchLeapThenFinish(id, guard + 1));
            return;
        }
        Vec3 midAir = caveSpider.position();
        battle.finish("fixture leap finish");
        await(3, () -> {
            check(!battle.isBusy(), "ending the encounter mid-leap leaves nothing busy");
            check(!battle.terrainFallActive(), "and starts no fall from an encounter that is over");
            check(((TacticalSpiderAccess) caveSpider).tactical$poseHandler() == null,
                    "the pose seam is cleared on the single exit path");
            check(!caveSpider.isClimbing(), "no borrowed climbing bit is left behind");
            check(caveSpider.isAlive() && !caveSpider.isRemoved(), "the body itself is untouched");
            check(Math.abs(caveSpider.position().x - midAir.x) < 0.5D
                            && Math.abs(caveSpider.position().z - midAir.z) < 0.5D,
                    "the body stays where it really was, not on the aborted landing: " + caveSpider.position());
        });
    }

    // ------------------------------------------------------------------ B: the native poison

    /**
     * The one bite arena: a cave spider standing next to the fixture's own player, so its own turn is a real
     * native bite - the only place a cave spider's poison can come from.
     */
    private static void biteArena() {
        bClear();
        owner.removeEffect(MobEffects.POISON);
        owner.setPos(origin.getX() + B_DX + 1.5D, origin.getY(), origin.getZ() + B_DZ + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        poisonTicks = -1;
        poisonAmplifier = -1;
        poisonSource = null;
        biteDamage = -1.0F;
        biteCount = 0;
        poisonPulseCount = 0;
        poisonPulseDamage = 0.0F;
        caveSpider = spawn(EntityType.CAVE_SPIDER, B_DX, 0, B_DZ);
        bystander = spawn(EntityType.COW, 16, 0, 12);
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the poison encounter starts");
        battle = BattleManager.current();
    }

    /** S8: one real NORMAL bite, its native duration, its source, and no invented damage. */
    private static void caveSpiderPoisonsOnNormal() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        biteArena();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null, "the cave spider was deployed into the encounter");
        int apBefore = admitted == null ? 0 : admitted.ap();
        float ownerHealth = owner.getHealth();
        await(0, () -> watchBite(id, 0, apBefore, ownerHealth, Difficulty.NORMAL));
    }

    /** One real native bite at that difficulty, and what it really put on the target. */
    private static void watchBite(UUID id, int guard, int apBefore, float ownerHealth, Difficulty difficulty) {
        if (guard > 600) {
            check(false, "the cave spider really bit at " + difficulty);
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the cave spider stayed on the roster");
            return;
        }
        if (!(view.paidActions() > 0 && battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchBite(id, guard + 1, apBefore, ownerHealth, difficulty));
            return;
        }
        check(level.getDifficulty() == difficulty, "the fixture really runs at " + difficulty);
        check(owner.getHealth() < ownerHealth, "the native bite removed health at " + difficulty);
        check(view.paidActions() == 1, "the successful bite counted exactly one paid action");
        double nativeAttack = caveSpider.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float delta = ownerHealth - owner.getHealth();
        Tactical.LOGGER.info("[Tactical] SPIDER bite at {}: attribute={} hpBefore={} hpAfter={} delta={} "
                        + "poisonTicks={} amplifier={} source={}",
                difficulty, nativeAttack, ownerHealth, owner.getHealth(), delta, poisonTicks, poisonAmplifier,
                poisonSource);
        check(biteCount == 1, "exactly one native hit landed at " + difficulty + ": " + biteCount);
        check(biteDamage > 0.0F && delta <= biteDamage + 1.0E-4D,
                "the health lost is at most the authoritative hit at " + difficulty + ": " + delta + " <= "
                        + biteDamage);
        check(delta <= nativeAttack * 3.0D + 1.0E-4D,
                "the encounter's own modifiers never invent a large hit: " + delta + " on " + nativeAttack);
        MobEffectInstance poison = owner.getEffect(MobEffects.POISON);
        int expected = difficulty == Difficulty.EASY ? 0 : difficulty == Difficulty.HARD ? 300 : 140;
        if (expected == 0) {
            check(poison == null && poisonTicks < 0, "EASY applies no poison at all");
        } else {
            check(poison != null, "the native bite put poison on the target at " + difficulty);
            check(poisonTicks == expected,
                    "the native duration really is " + expected + " ticks: " + poisonTicks);
            check(poisonAmplifier == 0, "the native poison uses amplifier 0: " + poisonAmplifier);
            check(poison != null && poison.getDuration() == expected && poison.getAmplifier() == 0,
                    "the instance on the target carries that duration and amplifier");
            check(poisonSource == caveSpider, "the poison names the biting cave spider as its source");
        }
        check(bystander.isAlive() && !bystander.isRemoved(), "the unrelated body was never touched");
        battle.finish("fixture poison bite done at " + difficulty);
    }

    /** S8: EASY adds none and HARD adds the 300 tick one, both from the same native bite. */
    private static void caveSpiderPoisonEasyAndHard() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        level.getServer().setDifficulty(Difficulty.EASY, true);
        biteArena();
        UUID easyId = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(easyId);
        check(admitted != null && admitted.ap() > 0, "the cave spider has its own pool at EASY");
        float ownerHealth = owner.getHealth();
        await(0, () -> watchBiteThen(easyId, 0, admitted == null ? 0 : admitted.ap(), ownerHealth,
                Difficulty.EASY, SpiderSelfTest::hardBiteStage));
    }

    private static void hardBiteStage() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        level.getServer().setDifficulty(Difficulty.HARD, true);
        biteArena();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.ap() > 0, "the cave spider has its own pool at HARD");
        float ownerHealth = owner.getHealth();
        await(0, () -> watchBiteThen(id, 0, admitted == null ? 0 : admitted.ap(), ownerHealth, Difficulty.HARD,
                SpiderSelfTest::restoreDifficulty));
    }

    private static void restoreDifficulty() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        discardAll();
    }

    /** The same bite watcher, with a continuation so the three difficulties share one implementation. */
    private static void watchBiteThen(UUID id, int guard, int apBefore, float ownerHealth, Difficulty difficulty,
                                      Runnable then) {
        if (guard > 600) {
            check(false, "the cave spider really bit at " + difficulty);
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the cave spider stayed on the roster");
            return;
        }
        if (!(view.paidActions() > 0 && battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchBiteThen(id, guard + 1, apBefore, ownerHealth, difficulty, then));
            return;
        }
        check(level.getDifficulty() == difficulty, "the fixture really runs at " + difficulty);
        check(owner.getHealth() < ownerHealth, "the native bite removed health at " + difficulty);
        check(view.paidActions() == 1, "the successful bite counted exactly one paid action at " + difficulty);
        MobEffectInstance poison = owner.getEffect(MobEffects.POISON);
        int expected = difficulty == Difficulty.EASY ? 0 : difficulty == Difficulty.HARD ? 300 : 140;
        Tactical.LOGGER.info("[Tactical] SPIDER bite at {}: poisonTicks={} amplifier={} source={} instance={}",
                difficulty, poisonTicks, poisonAmplifier, poisonSource, poison);
        if (expected == 0) {
            check(poison == null && poisonTicks < 0, "EASY applies no poison at all");
        } else {
            check(poison != null, "the native bite put poison on the target at " + difficulty);
            check(poisonTicks == expected, "the native duration really is " + expected + " ticks: " + poisonTicks);
            check(poisonAmplifier == 0, "the native poison uses amplifier 0: " + poisonAmplifier);
            check(poisonSource == caveSpider, "the poison names the biting cave spider as its source");
        }
        then.run();
    }

    /** S8: a cancelled native hit adds no poison and is not paid for; both spider types are immune. */
    private static void caveSpiderPoisonCancelAndImmunity() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        // Both exact types carry vanilla poison immunity, and a real attempt changes nothing. The ordinary
        // spider is spawned far outside the battlefield so it can never join the encounter under test.
        spider = spawn(EntityType.SPIDER, 18, 0, 13);
        check(!spider.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100)),
                "the ordinary spider cannot be affected by poison");
        check(!spider.addEffect(new MobEffectInstance(MobEffects.POISON, 140)),
                "a real poison attempt on an ordinary spider is refused");
        check(spider.getEffect(MobEffects.POISON) == null, "and leaves no instance behind");
        biteArena();
        check(!caveSpider.canBeAffected(new MobEffectInstance(MobEffects.POISON, 100)),
                "the cave spider cannot be affected by poison either");
        check(!caveSpider.addEffect(new MobEffectInstance(MobEffects.POISON, 140)),
                "a real poison attempt on a cave spider is refused too");
        check(caveSpider.getEffect(MobEffects.POISON) == null, "and leaves no instance behind either");
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.ap() > 0, "the cave spider has its own pool");
        int apBefore = admitted == null ? 0 : admitted.ap();
        float ownerHealth = owner.getHealth();
        cancelNativeBite = true;
        await(0, () -> watchCancelledBite(id, 0, apBefore, ownerHealth, false));
    }

    private static void watchCancelledBite(UUID id, int guard, int apBefore, float ownerHealth,
                                           boolean handedOver) {
        if (guard > 600) {
            check(false, "the cancelled native bite really resolved");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the cave spider stayed on the roster");
            return;
        }
        if (battle.isPlayerTurnNow() && !battle.isBusy()) {
            if (!handedOver) {
                battle.endPlayerTurn();
                await(0, () -> watchCancelledBite(id, guard + 1, apBefore, ownerHealth, true));
                return;
            }
        } else {
            await(0, () -> watchCancelledBite(id, guard + 1, apBefore, ownerHealth, handedOver));
            return;
        }
        check(owner.getHealth() == ownerHealth, "the cancelled native hit removed no health");
        check(owner.getEffect(MobEffects.POISON) == null && poisonTicks < 0,
                "a cancelled native hit adds no poison");
        check(view.ap() == apBefore, "and a refused hit is never charged: " + apBefore + " -> " + view.ap());
        check(view.paidActions() == 0, "and counts no paid action");
        cancelNativeBite = false;
        battle.finish("fixture cancelled bite done");
    }

    /**
     * S8: the effect clock around a real cave-spider poison, and the isolation of one unit's release.
     *
     * <p>The poison was added by the spider's own bite. Here it is only observed: the first own start of the
     * target baselines it without charging a whole cycle, another unit's own turns do not advance it at all,
     * real wall-clock ticks between turns do not advance it either, and the next complete own cycle pulses it
     * and lets it expire - never below one health point. A second unit's effect instance is never touched by
     * this unit's release.</p>
     */
    private static void poisonClockAndIsolation() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
        biteArena();
        UUID id = caveSpider.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        int apBefore = admitted == null ? 0 : admitted.ap();
        await(0, () -> watchPoisonClock(id, 0, apBefore, owner.getHealth(), 0));
    }

    private static void watchPoisonClock(UUID id, int guard, int apBefore, float ownerHealth, int phase) {
        if (guard > 900) {
            check(false, "the poison clock really ran (phase " + phase + ")");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the cave spider stayed on the roster");
            return;
        }
        MobEffectInstance poison = owner.getEffect(MobEffects.POISON);
        if (phase == 0) {
            // Wait for the real native bite and its poison.
            if (!(poison != null && battle.isPlayerTurnNow() && !battle.isBusy())) {
                if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
                await(0, () -> watchPoisonClock(id, guard + 1, apBefore, ownerHealth, 0));
                return;
            }
            check(poison != null && poisonTicks == 140,
                    "the real bite left the native 140 tick poison: " + poisonTicks);
            check(owner.getHealth() < ownerHealth, "and it really came with a native hit");
            // The target's first own start after the bite only baselines the clock: no whole cycle is charged,
            // and nothing ticks while the other unit waits in real time.
            int duration = poison == null ? -1 : poison.getDuration();
            int pulsesBeforeWait = poisonPulseCount;
            await(30, () -> {
                MobEffectInstance still = owner.getEffect(MobEffects.POISON);
                check(still != null && still.getDuration() == duration,
                        "the poison does not tick on wall-clock time while it waits: "
                                + (still == null ? "gone" : still.getDuration()) + " vs " + duration);
                check(poisonPulseCount == pulsesBeforeWait,
                        "and no pulse happened outside the target's own turn: " + poisonPulseCount);
                await(0, () -> watchPoisonClock(id, 0, apBefore, ownerHealth, 1));
            });
            return;
        }
        if (phase == 1) {
            // The target's own start happened; the other unit may now take its turn. Its bite is refused by the
            // same cancellation a mod may ask for, so nothing it does can refresh this poison.
            check(poison != null && poison.getDuration() == 140,
                    "the first own start of the target did not charge a whole cycle: "
                            + (poison == null ? "gone" : poison.getDuration()));
            poisonTicks = poison == null ? -1 : poison.getDuration();
            cancelNativeBite = true;
            // One hit short of death: vanilla poison stops at one health point.
            owner.setHealth(2.0F);
            battle.endPlayerTurn();
            await(0, () -> watchPoisonClock(id, 0, apBefore, ownerHealth, 2));
            return;
        }
        if (phase == 2) {
            if (!battle.isPlayerTurnNow() || battle.isBusy()) {
                // The other unit's own turn is running: the target's clock must not move because of it.
                MobEffectInstance still = owner.getEffect(MobEffects.POISON);
                check(still != null && still.getDuration() == poisonTicks,
                        "another unit's own turn did not advance this poison: "
                                + (still == null ? "gone" : still.getDuration()) + " vs " + poisonTicks);
                check(owner.getHealth() >= 2.0F, "and the refused bite changed no health: " + owner.getHealth());
                await(0, () -> watchPoisonClock(id, guard + 1, apBefore, ownerHealth, 2));
                return;
            }
            // The target's next complete own cycle has settled the poison: pulses, expiry, and never death.
            check(owner.getEffect(MobEffects.POISON) == null,
                    "the poison really expired through its own-turn cycle");
            check(owner.isAlive(), "poison never kills: the target is alive");
            // How much health is left after that cycle is a reading, not a rule: the same own start also
            // settles the target's native food tick, and this fixture does not claim to decompose the two.
            Tactical.LOGGER.info("[Tactical] SPIDER poison cycle: pulses={} pulseDamage={} health={} of max={}",
                    poisonPulseCount, poisonPulseDamage, owner.getHealth(), owner.getMaxHealth());
            check(poisonPulseCount > 0, "the poison really pulsed inside that own cycle: " + poisonPulseCount);
            check(owner.getHealth() > 0.0F, "and the target is still above zero health: " + owner.getHealth());
            // One unit's release must not delete another unit's real instance or this one's.
            MobEffectInstance real = new MobEffectInstance(MobEffects.POISON, 100);
            MobEffectInstance cow = new MobEffectInstance(MobEffects.POISON, 120);
            check(bystander.addEffect(cow), "the unrelated body can carry its own poison");
            check(owner.addEffect(real), "the member can carry a fresh poison of its own");
            BattleEffects.forget(bystander);
            check(bystander.getEffect(MobEffects.POISON) == cow,
                    "releasing one unit's clock never deletes that unit's real effect instance");
            check(owner.getEffect(MobEffects.POISON) == real,
                    "and never touches another unit's instance either");
            check(caveSpider.isAlive() && !battle.isBusy(), "the encounter is still quiet");
            battle.finish("fixture poison clock done");
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Remove every unit this fixture spawned and the one admitted by the previous stage. */
    private static void discardAll() {
        for (Entity entity : List.copyOf(spawned)) {
            if (entity instanceof TacticalSpiderAccess access) access.tactical$setPoseHandler(null);
            entity.discard();
        }
        spawned.clear();
        if (guest != null) {
            if (!guest.isRemoved()) {
                level.removePlayerImmediately(guest, Entity.RemovalReason.DISCARDED);
            }
            guest = null;
        }
        spider = null;
        caveSpider = null;
        zombie = null;
        skeleton = null;
        bystander = null;
        squatter = null;
        blocker = null;
    }

    /**
     * Nothing but this stage's own unit may be inside the battlefield: a forced start deploys every hostile the
     * zone contains, and vanilla's own natural spawns near the arena are not this fixture's subject.
     */
    private static void purgeForeignMonsters() {
        net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(origin).inflate(48.0D);
        for (net.minecraft.world.entity.monster.Monster monster
                : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, area)) {
            if (monster == spider || monster == caveSpider || monster == zombie) continue;
            monster.discard();
        }
    }

    private static GridPos cell(int dx, int dy, int dz) {
        return new GridPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
    }

    private static void block(int dx, int dy, int dz, Block block) {
        level.setBlock(new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz),
                block.defaultBlockState(), 2);
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            // The journal names the reason a refused action gives, which is what a failure usually needs.
            String detail = wire == null || wire.log.isEmpty() ? "" : " (journal: " + wire.log + ")";
            Tactical.LOGGER.error("[Tactical] SPIDER [{}] FAIL {}{}", checks + 1, label, detail);
            throw new AssertionError(label + detail);
        }
        checks++;
        Tactical.LOGGER.info("[Tactical] SPIDER [{}] PASS {}", checks, label);
    }

    // ------------------------------------------------------------------ harness

    // ------------------------------------------------------------------ C2: the fall barrier

    /** The epoch and the other side's AP the moment a real fall took the body, or -1 when no fall was seen. */
    private static long fallBarrierEpoch = -1L;
    private static int fallBarrierOwnerAp = Integer.MIN_VALUE;

    private static void resetFallBarrier() {
        fallBarrierEpoch = -1;
        fallBarrierOwnerAp = Integer.MIN_VALUE;
    }

    /**
     * C2: while the existing fall rule really carries a body, the encounter may not hand the turn on.
     *
     * <p>This is the gap a correct busy reading exposed: the stop path cleared its own motion and advanced the
     * turn, and only the next tick began the fall, which left one tick in which commands were live and the
     * other side could recover AP while the body was still in the air. The assertion is therefore taken on
     * every tick that the fall rule reports itself busy.</p>
     */
    private static void checkFallBarrierIsExclusive(String where) {
        if (!battle.terrainFallActive()) return;
        if (fallBarrierEpoch < 0) {
            fallBarrierEpoch = battle.turnEpoch();
            fallBarrierOwnerAp = battle.currentAp(owner);
        }
        check(!battle.isPlayerTurnNow(),
                where + ": the turn is not handed back while the fall rule carries the body");
        check(battle.currentAp(owner) == fallBarrierOwnerAp,
                where + ": the other side gets no AP back inside the fall window: " + fallBarrierOwnerAp
                        + " -> " + battle.currentAp(owner));
        check(battle.turnEpoch() == fallBarrierEpoch,
                where + ": the epoch does not move inside the fall window: " + fallBarrierEpoch
                        + " -> " + battle.turnEpoch());
    }

    /** C2: the fall is over - the turn came back exactly once, after the landing. */
    private static void checkFallHandedOffOnce(String where) {
        check(battle.isPlayerTurnNow(), where + ": the turn really came back after the landing");
        check(fallBarrierEpoch >= 0,
                where + ": the fall barrier was really observed before this hand-off");
        check(battle.turnEpoch() == fallBarrierEpoch + 1,
                where + ": exactly one hand-off for the whole fall: " + fallBarrierEpoch + " -> "
                        + battle.turnEpoch());
        // A pool that was already full cannot visibly grow: what this proves is that it never moved while the
        // body was in the air, and that any recovery happened only after the landing.
        check(battle.currentAp(owner) >= fallBarrierOwnerAp,
                where + ": the other side's AP never dropped across the fall: " + fallBarrierOwnerAp
                        + " -> " + battle.currentAp(owner));
    }

    // ------------------------------------------------------------------ S3/C1: a real one-block ceiling

    private static final int C_DX = 12;
    private static final int C_DZ = 10;

    private static GridPos cCell(int dx, int dy, int dz) { return cell(C_DX + dx, dy, C_DZ + dz); }

    private static void cBlock(int dx, int dy, int dz, Block block) {
        block(C_DX + dx, dy, C_DZ + dz, block);
    }

    /** A clean corner for the low-ceiling stages: real stone floor, open air above, nothing inherited. */
    private static void cArena() {
        for (int dx = -3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 4; dz++) {
                for (int dy = 0; dy <= 5; dy++) cBlock(dx, dy, dz, Blocks.AIR);
                cBlock(dx, -1, dz, Blocks.STONE);
            }
        }
        // The only thing over the corridor is a roof one block up: the sides stay open air, so this route can
        // never be explained by hugging a wall.
        for (int dx = 0; dx <= 5; dx++) {
            for (int dz = -1; dz <= 1; dz++) cBlock(dx, 1, dz, Blocks.STONE);
        }
    }

    /** The cells the body was really seen standing in during its own turn, and whether it ever held a wall. */
    private static final java.util.Set<GridPos> corridorCells = new java.util.LinkedHashSet<>();
    private static boolean corridorSawWall;

    /**
     * The headroom gate itself, asked of the live world.
     *
     * <p>The shared grid helper asks for a free cell above the feet, so it never answers for a real floor with a
     * roof one block up; that is the old gate this stage exists for. The body's own box is the real question,
     * and it is what {@link SpiderSurface#groundNode} and {@link SpiderSurface#isLegal} ask, so the corridor must
     * be a node the body can really stand on and one of the edges it is really offered.</p>
     */
    private static void lowCeilingGates(Mob body, String label) {
        GridPos corridor = cCell(2, 0, 0);
        GridPos start = cCell(1, 0, 0);
        BlockPos above = new BlockPos(corridor.x(), corridor.y() + 1, corridor.z());
        check(level.getBlockState(above).is(Blocks.STONE),
                label + ": the corridor really has exactly one block of headroom");
        check(level.getBlockState(new BlockPos(corridor.x(), corridor.y(), corridor.z())).isAir(),
                label + ": the corridor cell itself is air");
        check(LevelGridWorld.standCellAt(level, corridor.x(), corridor.z(), corridor.y()) == null,
                label + ": the shared fixed two-block headroom rule refuses this real floor cell");
        SpiderSurface.Node underCeiling = SpiderSurface.groundNode(level, body, corridor).orElse(null);
        check(underCeiling != null,
                label + ": the real body can stand under the one block ceiling (height " + body.getBbHeight() + ")");
        SpiderSurface.Node before = SpiderSurface.groundNode(level, body, start).orElse(null);
        check(before != null, label + ": the cell in front of the corridor is a real floor node");
        check(before != null && !before.onWall(), label + ": that node is a floor node, not a wall");
        check(before != null && underCeiling != null
                        && SpiderSurface.neighbours(level, body, before, c -> false,
                                battle.zone()::contains).contains(underCeiling),
                label + ": the corridor is one of the edges the body is really offered");
    }

    /** One real server tick of a corridor walk, watched until the enemy acted and the turn came back. */
    private static void watchLowCorridor(UUID id, int guard, String label, float ownerHealth) {
        if (guard > 900) {
            check(false, label + ": really walked the low corridor inside its own turn");
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, label + ": stayed on the roster");
            return;
        }
        corridorCells.add(view.cell());
        corridorSawWall |= view.node() != null && view.node().onWall();
        if (!(view.paidActions() > 0 && battle.isPlayerTurnNow() && !battle.isBusy())) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchLowCorridor(id, guard + 1, label, ownerHealth));
            return;
        }
        check(!corridorSawWall, label + ": every held node on the way was a floor node, never a wall");
        GridPos ownerCell = cCell(6, 0, 0);
        int beside = Math.abs(view.cell().x() - ownerCell.x()) + Math.abs(view.cell().z() - ownerCell.z());
        check(beside == 1 && view.cell().y() == ownerCell.y(),
                label + ": the body really stands beside the owner on the same level: " + view.cell()
                        + " owner " + ownerCell);
        for (GridPos seen : corridorCells) {
            check(seen.y() == cCell(0, 0, 0).y(),
                    label + ": the whole route stayed on the corridor level: " + seen);
        }
        for (int dx = 1; dx <= 5; dx++) {
            check(corridorCells.contains(cCell(dx, 0, 0)), label + ": really stood in corridor cell x="
                    + (C_DX + dx) + " of the low corridor (observed " + corridorCells + ")");
        }
        check(view.paidActions() == 1, label + ": exactly one paid action, the melee");
        check(owner.getHealth() < ownerHealth,
                label + ": the native bite landed from the low corridor: " + owner.getHealth() + "/" + ownerHealth);
        check(!battle.isBusy(), label + ": nothing is left in flight");
    }

    /**
     * S3/C1: a cave spider really walks a corridor whose only ceiling is one block up.
     *
     * <p>Nothing here has a side wall: the roof is the only block in the way, so a body that really fits passes
     * and a body that does not is refused by its own box rather than by a fixed headroom rule.</p>
     */
    private static void caveSpiderWalksALowCeilingCorridor() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        // The poison-clock stage left its own bite cancellation armed; this stage asks real damage questions.
        cancelNativeBite = false;
        cArena();
        owner.setPos(origin.getX() + C_DX + 6.5D, origin.getY(), origin.getZ() + C_DZ + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        caveSpider = spawn(EntityType.CAVE_SPIDER, C_DX, 0, C_DZ);
        // The encounter that just ended still holds this player for a moment; let the release run on its own
        // server ticks before asking whether the player can really be damaged again.
        await(2, () -> startLowCorridor(caveSpider, "cave spider"));
    }

    /**
     * S3/C1: the same low corridor for the ordinary wide spider.
     *
     * <p>A 0.9 tall body fits under a one block ceiling just as well as a 0.5 tall one; only its width needs a
     * second column, and that column is still roofed by the same single ceiling block height. Treating this as a
     * cave-spider-only ability would be wrong, so the wide body is asked the same question.</p>
     */
    private static void wideSpiderWalksALowCeilingCorridor() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        discardAll();
        cancelNativeBite = false;
        cArena();
        owner.setPos(origin.getX() + C_DX + 6.5D, origin.getY(), origin.getZ() + C_DZ + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        spider = spawn(EntityType.SPIDER, C_DX, 0, C_DZ);
        check(spider.getBbHeight() < 1.0D && spider.getBbWidth() > 1.0D,
                "the wide body is taller than the cave spider but still shorter than one block: height="
                        + spider.getBbHeight() + " width=" + spider.getBbWidth());
        await(2, () -> startLowCorridor(spider, "wide spider"));
    }

    /** Admit one body into the low corridor encounter and watch its own turn on real server ticks. */
    private static void startLowCorridor(Mob body, String label) {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        boolean hit = owner.hurt(body.damageSources().mobAttack(body), 1.0F);
        Tactical.LOGGER.info("[Tactical] SPIDER corridor precondition: hurt={} hp={} maxHp={} invulnerableTime={} "
                        + "hurtTime={} gameMode={} alive={} removed={} poison={} bodyAlive={} bodyRemoved={} "
                        + "difficulty={}", hit, owner.getHealth(), owner.getMaxHealth(), owner.invulnerableTime,
                owner.hurtTime, owner.gameMode.getGameModeForPlayer(), owner.isAlive(), owner.isRemoved(),
                owner.getEffect(MobEffects.POISON) != null, body.isAlive(), body.isRemoved(), level.getDifficulty());
        check(hit, "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the low corridor encounter starts");
        battle = BattleManager.current();
        lowCeilingGates(body, label);
        corridorCells.clear();
        corridorSawWall = false;
        float ownerHealth = owner.getHealth();
        UUID id = body.getUUID();
        await(1, () -> watchLowCorridor(id, 0, label, ownerHealth));
    }

    private static void setup(MinecraftServer server) {
        BattleManager.setAutoTrigger(false);
        defaultZoneHeight = TacticalConfigSplit.zoneHeight();
        defaultLeapCost = TacticalConfigSplit.leapCost();
        // Native mob damage to a player is scaled by difficulty; a peaceful or unset difficulty would zero it
        // and make a real bite look like a refusal. The fixture asks for the ordinary one.
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        level = server.overworld();
        // The arena is a large dark platform, so vanilla would spawn its own hostiles into an encounter that is
        // measuring this mod's own units. The fixture turns natural spawning off instead of filtering them out.
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX() + 260, 80, spawn.getZ() + 260);
        for (int x = (origin.getX() - 16) >> 4; x <= (origin.getX() + 16) >> 4; x++) {
            for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        for (int x = -20; x <= 20; x++) {
            for (int z = -12; z <= 14; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 10; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-spider-fixture".getBytes(StandardCharsets.UTF_8)), "SpiderOwner");
        owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        wire = new Wire();
        wire.channel = new io.netty.channel.embedded.EmbeddedChannel(wire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer")) {
            net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(wire)
                    .add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(server, wire, owner,
                CommonListenerCookie.createInitial(profile, false));
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 2.5D);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        owner.setHealth(1000);
        owner.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(owner);
        // Vanilla gives a joining player three seconds of spawn invulnerability. A fixture that measures a real
        // native bite has to wait that out instead of reporting a refused hit as a ruleset defect.
        damageReadyTick = ticks + 200;

        spider = spawn(EntityType.SPIDER, -4, 0, 4);
        caveSpider = spawn(EntityType.CAVE_SPIDER, -6, 0, 4);
        zombie = spawn(EntityType.ZOMBIE, 3, 0, 5);
        skeleton = spawn(EntityType.SKELETON, 5, 0, 5);
        bystander = spawn(EntityType.COW, 7, 0, 7);
    }

    private static <T extends Entity> T spawn(EntityType<T> type, int dx, int dy, int dz) {
        T entity = type.create(level);
        check(entity != null, "the native " + type.toShortString() + " was created");
        entity.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            mob.setHealth(1000);
        }
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    /** Spawn one body at an exact position: used where a fraction of a block is the point of the case. */
    private static <T extends Entity> T spawnPrecise(EntityType<T> type, double x, double y, double z) {
        T entity = type.create(level);
        check(entity != null, "the native " + type.toShortString() + " was created");
        entity.setPos(x, y, z);
        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        for (Entity entity : spawned) {
            if (entity instanceof TacticalSpiderAccess access) access.tactical$setPoseHandler(null);
            entity.discard();
        }
        spawned.clear();
        if (owner != null && !owner.isRemoved()) {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        }
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        BattleEffects.clear();
        if (defaultZoneHeight > 0) {
            TacticalConfigSplit.zoneHeight(defaultZoneHeight);
            defaultZoneHeight = -1;
        }
        if (defaultLeapCost > 0) {
            TacticalConfigSplit.leapCost(defaultLeapCost);
            defaultLeapCost = -1;
        }
        cancelNativeBite = false;
        server.halt(false);
    }

    /** The one config value this fixture changes, read and restored in one place. */
    private static final class TacticalConfigSplit {
        static int zoneHeight() { return com.tactical.neoforge.TacticalConfig.INSTANCE.zoneHeight.get(); }
        static void zoneHeight(int value) {
            com.tactical.neoforge.TacticalConfig.INSTANCE.zoneHeight.set(value);
        }
        static int leapCost() { return com.tactical.neoforge.TacticalConfig.INSTANCE.spiderLeapApCost.get(); }
        static void leapCost(int value) {
            com.tactical.neoforge.TacticalConfig.INSTANCE.spiderLeapApCost.set(value);
        }
    }

    /**
     * A synthetic surface graph for the bounded-approach policy.
     *
     * <p>No world read at all: the adapter is asked exactly the questions the encounter would ask, over a
     * corridor of floor nodes and an opponent placed wherever the case needs it. That is the only way to put
     * the target beyond the search front deterministically.</p>
     */
    private static final class PolicyContext implements AiContext {
        private final SpiderSurface.Node here;
        private final GridPos goal;
        private final int budget;
        private final Set<GridPos> reachable;
        /** A branching graph instead of a corridor: every cell offers its four orthogonal neighbours. */
        private final boolean lattice;
        int asked;
        int offered;

        private PolicyContext(GridPos start, GridPos goal, int budget, Set<GridPos> reachable) {
            this(start, goal, budget, reachable, false);
        }

        private PolicyContext(GridPos start, GridPos goal, int budget, Set<GridPos> reachable, boolean lattice) {
            this.here = start == null ? null : SpiderSurface.Node.ground(start);
            this.goal = goal;
            this.budget = budget;
            this.reachable = reachable;
            this.lattice = lattice;
        }

        static PolicyContext lattice(GridPos start, GridPos goal, int budget) {
            return new PolicyContext(start, goal, budget, Set.of(), true);
        }

        @Override public Mob actor() { return spider; }
        @Override public GridPos actorCell() { return here == null ? new GridPos(0, 64, 0) : here.cell(); }
        @Override public GridPos playerCell() { return goal; }
        @Override public int ap() { return 100; }
        @Override public int moveCost() { return 2; }
        @Override public List<GridPos> pathToPlayer() { return List.of(); }
        @Override public List<GridPos> standableNeighbors(GridPos from) { return List.of(); }
        @Override public boolean occupied(GridPos cell) { return false; }
        @Override public boolean inMeleeRange(GridPos from, GridPos to) { return reachable.contains(to); }
        @Override public boolean hasLineOfSight(GridPos from, GridPos to) { return true; }
        @Override public void attackPlayer() { }
        @Override public void log(String message) { }
        @Override public SpiderSurface.Node surfaceHere() { return here; }
        @Override public boolean surfaceInsideZone(SpiderSurface.Node node) { return true; }
        @Override public boolean surfaceMeleeReach(SpiderSurface.Node node) { return reachable.contains(node.cell()); }
        @Override public int surfaceEdgeBudget() { return budget; }

        @Override
        public List<SpiderSurface.Node> surfaceNeighbours(SpiderSurface.Node from) {
            asked++;
            if (lattice) {
                // A two-dimensional surface with four exits per cell: enough branching that the node budget is
                // reached before the opponent is, which is the case this assertion exists for.
                List<SpiderSurface.Node> grid = new ArrayList<>(4);
                for (GridPos next : List.of(from.cell().offset(1, 0, 0), from.cell().offset(-1, 0, 0),
                        from.cell().offset(0, 0, 1), from.cell().offset(0, 0, -1))) {
                    if (next.x() < -8 || next.x() > 40 || next.z() < -8 || next.z() > 40) continue;
                    grid.add(SpiderSurface.Node.ground(next));
                }
                offered += grid.size();
                return grid;
            }
            // A corridor along +x, one node per cell, so depth and distance are the same number.
            List<SpiderSurface.Node> out = new ArrayList<>(2);
            out.add(SpiderSurface.Node.ground(from.cell().offset(1, 0, 0)));
            if (here != null && from.cell().offset(-1, 0, 0).equals(here.cell())) out.add(here);
            offered += out.size();
            return out;
        }
    }

    private static final class Wire extends Connection {
        io.netty.channel.embedded.EmbeddedChannel channel;
        /** The journal tail, so a refused action names its own reason in a failure message. */
        final List<String> log = new ArrayList<>();

        Wire() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof BattleLogPacket next) {
                log.clear();
                for (BattleLogPacket.Entry entry : next.history()) {
                    log.add(entry.category() + "=" + entry.text().getString());
                }
            }
        }
    }
}
