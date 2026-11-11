package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.battle.ai.GenericMeleeAi;
import com.tactical.neoforge.battle.ai.SlimeTacticalAi;
import com.tactical.neoforge.battle.ai.TacticalAiRegistry;
import com.tactical.neoforge.network.BattleLogPacket;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real loaded-server fixture for the slime and the magma cube: {@code -Dtactical.selftest=slime}.
 *
 * <p>Every reading comes from a booted dedicated server: a real {@link ServerLevel}, real vanilla entities with
 * their real sizes, and the encounter's own admission, hop, contact and fall paths. The stages follow the frozen
 * groups - the two exact types and their native bodies (S1), the real ground geometry and the bounded approach
 * (S2), the native contact including the tiny-slime refusal and the two-cell reach of a large body (S3), and the
 * hop arc, its price, a refused edge and the sub-block fall (S4).</p>
 *
 * <p>Two kinds of waiting are used on purpose. A synchronous read proves a geometry or a transaction answer; a
 * stage that ends in {@link #await} lets <em>real server ticks</em> pass, so an animated hop, a native damage call
 * and a descent are only believed when the world really did them.</p>
 *
 * <p>No client is launched and no user world is touched: the run directory is its own (see
 * {@code serverSlimeTest} in {@code build.gradle}). A failure halts the server with a non-zero code, so a green
 * Gradle task can never look like a passing fixture.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SlimeSelfTest {

    /** The first stage that measures native damage, so the spawn-invulnerability window is waited out first. */
    private static final int FIRST_CONTACT_STAGE = 5;

    /**
     * How long the fixture may wait for its own world to become real before it reports what is still missing.
     *
     * <p>Ten seconds of real server ticks. A world directory reused from an earlier run is already ticking when
     * the fixture's bodies are added, while a brand-new world has to generate and promote the fixture's own
     * chunks first; without this wait a stage would ask "does a live body refuse this cell" about a body the
     * level's entity index cannot see yet, and the answer would be a property of the world's age, not of the
     * rule under test.</p>
     */
    private static final int WORLD_READY_LIMIT = 200;

    private static boolean enabled;
    private static int ticks;
    private static int checks;
    private static int stage;
    /** Whether this run has already proved that its own world really holds the fixture's bodies. */
    private static boolean worldReady;
    private static int worldReadyWaits;
    private static final List<Runnable> STAGES = List.of(
            SlimeSelfTest::exactTypesAndBodies,
            SlimeSelfTest::nativeSizesAndAttributes,
            SlimeSelfTest::groundGeometry,
            SlimeSelfTest::boundedApproach,
            SlimeSelfTest::contactCapability,
            SlimeSelfTest::nativeContactUnderNoAi,
            SlimeSelfTest::cancelledContactIsFreeAndHonest,
            SlimeSelfTest::contactTransactionThroughTheEncounter,
            SlimeSelfTest::cancelledContactThroughTheEncounter,
            SlimeSelfTest::hopArcGeometry,
            SlimeSelfTest::hopRefusedEdgesAndLowTier,
            SlimeSelfTest::hopPriceThroughTheEncounter,
            SlimeSelfTest::fractionalInterruptionFallsBySweep,
            SlimeSelfTest::arcApexInterruptionFallsBySlice,
            SlimeSelfTest::firstFrameRefusalIsFree,
            SlimeSelfTest::startedEdgeStaysPaidAtFrozenPrice,
            SlimeSelfTest::dugLandingAbortsMidHop,
            SlimeSelfTest::idleBodyLosingOuterSupportIsReleased,
            SlimeSelfTest::fractionalDeploymentKeepsRealFeet,
            SlimeSelfTest::integerApexInterruptionKeepsHeldPoint,
            SlimeSelfTest::deploymentAndAdmissionGates,
            SlimeSelfTest::reinforcementGrowsOverTheBody,
            SlimeSelfTest::chunkBoundaryCandidateIsProved,
            SlimeSelfTest::originalFlagsReturnOnExit,
            SlimeSelfTest::sizeOneMagmaContactsNatively,
            SlimeSelfTest::sizeOneSlimeCannotContact,
            SlimeSelfTest::nativeDamageAcrossDifficulties,
            // B: the native split lifecycle - the barrier, the children, the refusals and the cleanup.
            SlimeSelfTest::nativeSplitChain,
            SlimeSelfTest::splitEventCancelled,
            SlimeSelfTest::splitJoinRefused,
            SlimeSelfTest::splitRosterFullRefusal,
            SlimeSelfTest::splitFriendlySide,
            SlimeSelfTest::splitCleanupOnFinish,
            SlimeSelfTest::splitAbnormalDeadline,
            // B1-B5 review items: scope/finally, hand-over ownership, compound barrier, retake, real payment.
            SlimeSelfTest::splitScopeIsReal,
            SlimeSelfTest::splitHandoverOwnership,
            SlimeSelfTest::splitDuplicateChildUuid,
            SlimeSelfTest::splitAndFallBarriers,
            SlimeSelfTest::splitAnomalySurvives,
            SlimeSelfTest::splitRetakenThroughRetry,
            SlimeSelfTest::splitLethalAttackKeepsPayment,
            SlimeSelfTest::splitActingParentDiesOnce,
            SlimeSelfTest::magmaCubeNativeSplit,
            SlimeSelfTest::splitChildFirstOwnTurn,
            SlimeSelfTest::splitParentSubstituteRefused);
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Slime bigSlime;
    private static Slime midSlime;
    private static Slime tinySlime;
    private static MagmaCube smallMagma;
    private static MagmaCube tinyMagma;
    private static MagmaCube bigMagma;
    private static Zombie zombie;
    private static Cow bystander;
    private static ServerBattle battle;
    private static Wire wire;
    private static int damageReadyTick;
    private static final List<Entity> spawned = new ArrayList<>();

    private static int waitTicks;
    /** Real server ticks since startup; unlike {@link #ticks} this counts waiting callbacks too. */
    private static int worldTicks;
    private static Runnable afterWait;

    /** Fixture lever: refuse the native damage of a slime contact, to prove a cancelled hit is free. */
    private static boolean cancelNativeContact;
    /** The amount the last real native contact passed into hurt, and what was left of it after reductions. */
    private static float incomingAmount = -1.0F;
    private static float incomingAfterReductions = -1.0F;
    /** The authoritative damage of the last real native contact, after every modifier. */
    private static float contactDamage = -1.0F;
    private static int contactCount;
    /** The damage the fixture's own lever cancelled, so an untouched success is not confused with a refusal. */
    private static int cancelledContactCount;
    /** How low the interrupted body really got, and whether it ever slid sideways while it fell. */
    /** The pool a hop was seen holding at its first animated frame; the price must not change per frame. */
    private static int hopFrozenAp = Integer.MIN_VALUE;
    private static Slime descentBody;
    private static String descentLabel = "";
    private static double descentStartX;
    private static double descentStartZ;
    private static double descentStartY;
    private static double descentFloorY;
    private static double descentSlice;
    private static long descentEpoch;
    private static int descentOwnerAp;
    private static double descentLastY;
    private static double descentLowestY = Double.MAX_VALUE;
    private static double descentMaxDrift;
    /** How many real ticks were observed with the body strictly between its floor and where it started. */
    private static int descentSamples;
    private static Vec3 descentStart;

    private SlimeSelfTest() { }

    /**
     * Registered at server start rather than by the annotation scan.
     *
     * <p>Both this probe and the lifecycle listen at the last priority, so the one registered later runs later.
     * A scanned subscriber class would be registered before the battle classes and would therefore see the child
     * one step too early to be inside its hand-over window.</p>
     */
    private static final SplitProbe PROBE = new SplitProbe();

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        enabled = "slime".equals(System.getProperty("tactical.selftest"));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(PROBE);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(PROBE);
        ticks = 0;
        checks = 0;
        stage = 0;
        worldReady = false;
        worldReadyWaits = 0;
        waitTicks = 0;
        afterWait = null;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (!enabled) return;
        // Every real server tick, including the ones a waiting callback chain consumes: the stage counter
        // above only advances when a stage is dispatched, which is not a clock.
        worldTicks++;
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
            // The fixture's own world has to be real before any stage asks it a question: the chunks have to
            // participate in entity queries and the level has to actually hold the bodies it was given. On a
            // reused world this is true on the first look; on a brand-new one it takes real server ticks, and a
            // stage that ran before that would measure the world's age instead of the rule under test.
            if (!worldReady) {
                String missing = worldNotReady();
                if (missing == null) {
                    worldReady = true;
                } else {
                    worldReadyWaits++;
                    if (worldReadyWaits > WORLD_READY_LIMIT) {
                        check(false, "the fixture world never became real within " + WORLD_READY_LIMIT
                                + " ticks: " + missing);
                    } else if (worldReadyWaits % 20 == 0) {
                        Tactical.LOGGER.info("[Tactical] SLIME waiting for its own world: {} ({} of {} ticks)",
                                missing, worldReadyWaits, WORLD_READY_LIMIT);
                    }
                    return;
                }
            }
            // The stages that measure native damage wait quietly until vanilla's spawn invulnerability for the
            // fixture's own player has passed.
            if (stage >= FIRST_CONTACT_STAGE && ticks < damageReadyTick) return;
            if (stage < STAGES.size()) {
                check(ticks < 8000, "fixture deadline");
                STAGES.get(stage++).run();
                return;
            }
            Tactical.LOGGER.info("[Tactical] SLIME SELFTEST PASSED: {} checks on the two exact types, their native "
                    + "sizes and attributes, the real ground geometry, the bounded approach, the native contact "
                    + "under NoAI with the tiny-slime refusal and the large body's two-cell reach, the hop arc and "
                    + "its price, the sub-block fall, and the native split lifecycle (barrier, birth hand-over, "
                    + "refusals and cleanup)", checks);
            finish(event.getServer());
        } catch (Exception | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] SLIME SELFTEST FAILED after {} checks", checks, failure);
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
     * One native contact refused before it lands, by the same event any mod may cancel.
     *
     * <p>The fixture uses it to prove the thing the ruleset claims: the action is paid for only when the very
     * {@code hurt} call inside vanilla's own {@code dealDamage} reported a hit.</p>
     */
    @SubscribeEvent
    public static void onIncoming(LivingIncomingDamageEvent event) {
        if (!enabled || !cancelNativeContact || owner == null) return;
        if (event.getEntity() != owner) return;
        if (event.getSource().getEntity() instanceof Slime) {
            event.setCanceled(true);
            cancelledContactCount++;
        }
    }

    /**
     * The authoritative damage of one real native contact, read after the encounter's own modifiers.
     *
     * <p>The native attack attribute is the input, the applied damage is what really happened, and the health the
     * target lost is the third, different number; the fixture keeps them apart instead of asserting they agree.
     * </p>
     */
    /**
     * The original and current amounts exposed by NeoForge's incoming-damage event.
     *
     * <p>Player difficulty scaling precedes this event. Neither field is the argument of the outer native
     * {@code Slime.dealDamage -> Player.hurt} call; {@code getAmount()} is the event's current amount at this
     * listener, not an observation of armour reduction. Applied damage and actual health loss are separate.</p>
     */
    @SubscribeEvent
    public static void onIncomingAmount(LivingIncomingDamageEvent event) {
        if (!enabled || owner == null || event.getEntity() != owner) return;
        if (event.getSource().getEntity() instanceof Slime) {
            incomingAmount = event.getOriginalAmount();
            incomingAfterReductions = event.getAmount();
        }
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || owner == null || event.getEntity() != owner) return;
        Entity source = event.getSource().getEntity();
        // Any body of this family: the fixture records whichever exact unit it drove, instead of naming two.
        if (!(source instanceof Slime)) return;
        contactDamage = event.getNewDamage();
        contactCount++;
        Tactical.LOGGER.info("[Tactical] SLIME contact damage: source={} size={} applied={} original={}",
                source.getType().toShortString(),
                source instanceof Slime slime ? slime.getSize() : -1,
                event.getNewDamage(), event.getOriginalDamage());
    }

    // ------------------------------------------------------------------ stages

    /** S1: exactly two types, and who may be taken at all. */
    private static void exactTypesAndBodies() {
        check(TacticalAiRegistry.create(bigSlime) instanceof SlimeTacticalAi, "the slime gets the family adapter");
        check(TacticalAiRegistry.create(bigMagma) instanceof SlimeTacticalAi,
                "the magma cube gets the family adapter");
        check(TacticalAiRegistry.create(zombie) instanceof GenericMeleeAi,
                "an unrelated monster keeps the generic melee adapter");
        check(SlimeGround.supports(bigSlime) && SlimeGround.supports(smallMagma)
                        && !SlimeGround.supports(zombie),
                "the family policy is exact-type only");
        check(!AerialMovement.supports(bigSlime) && !AerialMovement.supports(smallMagma),
                "a slime is never marked as a flying unit");
        check(!SpiderSurface.supports(bigSlime) && !SpiderSurface.supports(smallMagma),
                "a slime is never handed the spider surface policy");
        check(SlimeGround.controllable(bigSlime), "an ordinary slime is a body this encounter may take");
        check(!bigSlime.isEffectiveAi(),
                "the fixture's bodies really are NoAI, so nothing below is vanilla's own contact gate");
        check(!bigSlime.isPassenger() && bigSlime.getPassengers().isEmpty(),
                "the taken body is neither riding nor carrying a rider");
    }

    /** S1: the native size ladder, health, speed, attack, armour and fire immunity. */
    private static void nativeSizesAndAttributes() {
        // The natural 1 / 2 / 4 ladder of both exact types, read from the real bodies before any encounter: size,
        // box, health, movement speed, attack and armour are the native table, not a value this mod re-set.
        Map<Integer, Slime> slimes = new LinkedHashMap<>();
        slimes.put(1, tinySlime);
        slimes.put(2, midSlime);
        slimes.put(4, bigSlime);
        Map<Integer, MagmaCube> magmas = new LinkedHashMap<>();
        magmas.put(1, tinyMagma);
        magmas.put(2, smallMagma);
        magmas.put(4, bigMagma);
        for (Map.Entry<Integer, Slime> entry : slimes.entrySet()) {
            int size = entry.getKey();
            Slime body = entry.getValue();
            String label = "size " + size + " slime";
            check(body.getSize() == size, label + ": the fixture body really carries its own size");
            check(Math.abs(body.getBbWidth() - 0.52D * size) < 1.0E-4D
                            && Math.abs(body.getBbHeight() - 0.52D * size) < 1.0E-4D,
                    label + ": its real box is 0.52 * size in both directions: " + body.getBbWidth()
                            + " x " + body.getBbHeight());
            check(Math.abs(body.getMaxHealth() - (float) (size * size)) < 1.0E-4F,
                    label + ": health is the size squared: " + body.getMaxHealth());
            // The native value is computed in float and only then widened, so the comparison carries that.
            check(Math.abs(body.getAttributeValue(Attributes.MOVEMENT_SPEED) - (0.2D + 0.1D * size)) < 1.0E-6D,
                    label + ": the native speed is 0.2 + 0.1 * size: "
                            + body.getAttributeValue(Attributes.MOVEMENT_SPEED));
            check(Math.abs(body.getAttributeValue(Attributes.ATTACK_DAMAGE) - size) < 1.0E-9D,
                    label + ": the native attack attribute equals the size: "
                            + body.getAttributeValue(Attributes.ATTACK_DAMAGE));
            check(body.getArmorValue() == 0 || Math.abs(body.getArmorValue()) < 1.0E-9D,
                    label + ": a slime carries no native armour: " + body.getArmorValue());
        }
        for (Map.Entry<Integer, MagmaCube> entry : magmas.entrySet()) {
            int size = entry.getKey();
            MagmaCube body = entry.getValue();
            String label = "size " + size + " magma cube";
            check(body.getSize() == size, label + ": the fixture body really carries its own size");
            check(Math.abs(body.getBbWidth() - 0.52D * size) < 1.0E-4D
                            && Math.abs(body.getBbHeight() - 0.52D * size) < 1.0E-4D,
                    label + ": its real box is 0.52 * size in both directions");
            check(Math.abs(body.getMaxHealth() - (float) (size * size)) < 1.0E-4F,
                    label + ": health is the size squared: " + body.getMaxHealth());
            // The native value is computed in float and only then widened, so the comparison carries that.
            check(Math.abs(body.getAttributeValue(Attributes.MOVEMENT_SPEED) - (0.2D + 0.1D * size)) < 1.0E-6D,
                    label + ": the native speed is 0.2 + 0.1 * size: "
                            + body.getAttributeValue(Attributes.MOVEMENT_SPEED));
            check(Math.abs(body.getAttributeValue(Attributes.ATTACK_DAMAGE) - size) < 1.0E-9D,
                    label + ": the native attack attribute equals the size: "
                            + body.getAttributeValue(Attributes.ATTACK_DAMAGE));
            check(Math.abs(body.getAttributeValue(Attributes.ARMOR) - 3.0D * size) < 1.0E-9D,
                    label + ": a magma cube carries 3 * size armour: " + body.getAttributeValue(Attributes.ARMOR));
            check(body.fireImmune(), label + ": a magma cube is fire immune");
        }
        check(!bigSlime.fireImmune(), "the slime is not fire immune");
        check(smallMagma.getType() == EntityType.MAGMA_CUBE && bigSlime.getType() == EntityType.SLIME,
                "the two types stay exact");
        float bigHealth = bigSlime.getMaxHealth();
        check(!SlimeContact.sizeCanHurt(tinySlime),
                "a size 1 slime may not damage anything, exactly as vanilla's own tiny rule says");
        check(SlimeContact.sizeCanHurt(tinyMagma) && SlimeContact.sizeCanHurt(smallMagma)
                        && SlimeContact.sizeCanHurt(bigSlime),
                "a magma cube of any size and a grown slime really may");
        // The deployment rule reads the real box, so the widest natural body and the smallest are both real
        // positions on this floor; nothing here reduces a body to a point.
        check(BattleDeployment.isStandable(level, bigSlime, feet(0, 0, 0)),
                "a size 4 body really stands on an open floor cell");
        check(BattleDeployment.isStandable(level, tinySlime, feet(0, 0, 0)),
                "a size 1 body really stands on the same cell");
        check(bigSlime.getMaxHealth() == bigHealth,
                "asking the world about a position never changed the body's own health");
    }

    /** S2: the real ground node, and the three negatives that are not about a cell alone. */
    private static void groundGeometry() {
        discardAll();
        SlimeGround.Node node = SlimeGround.groundNode(level, bigSlime, cell(0, 0, 0)).orElse(null);
        check(node != null, "a legal cell really resolves a ground node for a size 4 body");
        check(node != null && Math.abs(node.feet().y - (origin.getY())) < 1.0E-9D,
                "the node's feet are the cell's own floor, never a fractional guess");
        check(node != null && SlimeGround.legal(level, bigSlime, node), "the node is legal in the live world");

        // A two-block room: the grid's own head-clearance rule accepts it for both bodies, while the real 2.08
        // tall body cannot stand up in it. This is the "do not go through the two-cell head clearance" rule.
        block(0, 2, 2, Blocks.STONE);
        check(LevelGridWorld.isStandable(level, origin.getX(), origin.getY(), origin.getZ() + 2),
                "the plain grid rule accepts a two-block room for anybody (the rule this family must not use)");
        check(SlimeGround.groundNode(level, bigSlime, cell(0, 0, 2)).isEmpty(),
                "a 2.08 tall body is really refused by that room");
        check(SlimeGround.groundNode(level, tinySlime, cell(0, 0, 2)).isPresent(),
                "a 0.52 tall body really fits the same room");
        // A one-cell-wide slot: the wide body cannot fit, the small one can.
        block(-1, 0, 4, Blocks.STONE);
        block(1, 0, 4, Blocks.STONE);
        block(-1, 1, 4, Blocks.STONE);
        block(1, 1, 4, Blocks.STONE);
        block(-1, 2, 4, Blocks.STONE);
        block(1, 2, 4, Blocks.STONE);
        check(SlimeGround.groundNode(level, bigSlime, cell(0, 0, 4)).isEmpty(),
                "a 2.08 wide body is really refused by a one-block slot");
        check(SlimeGround.groundNode(level, tinySlime, cell(0, 0, 4)).isPresent(),
                "a 0.52 wide body really fits that slot");
        // Water and a hazard are refused for both sizes: this family never swims and never stands in fire.
        block(0, 0, 6, Blocks.WATER);
        check(SlimeGround.groundNode(level, tinySlime, cell(0, 0, 6)).isEmpty(),
                "a flooded cell is refused");
        block(0, 0, 6, Blocks.AIR);
        block(0, 0, 6, Blocks.MAGMA_BLOCK);
        check(SlimeGround.groundNode(level, smallMagma, cell(0, 0, 6)).isEmpty(),
                "a hazard cell is refused even for the fire-immune magma cube");
        block(0, 0, 6, Blocks.AIR);
        // An unloaded column is never read as "nothing in the way".
        GridPos far = new GridPos(origin.getX() + 40000, origin.getY(), origin.getZ());
        check(SlimeGround.groundNode(level, tinySlime, far).isEmpty(),
                "an unloaded column is refused instead of being read as free ground");
        // A real body already standing there refuses the node: the wide body is put on that very cell first.
        Vec3 held = bigSlime.position();
        bigSlime.setPos(feet(0, 0, 0));
        check(SlimeGround.groundNode(level, tinySlime, cell(0, 0, 0)).isEmpty(),
                "the cell a live body already occupies is refused for another body");
        check(bigSlime.position().distanceToSqr(feet(0, 0, 0)) < 1.0E-6D,
                "asking the geometry never moved the body that owns the cell");
        bigSlime.setPos(held);
    }

    /** S2: the bounded search, and the fact that a legal prefix beats standing still. */
    private static void boundedApproach() {
        discardAll();
        check(SlimeGround.MAX_SEARCH_NODES == 512, "the search budget is the frozen 512 nodes");
        // The owner is put out of the corridor first: a wide body really refuses to stand on a cell its
        // footprint would share with a live player, which is a fact this stage must not trip over.
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        bigSlime.setPos(feet(0, 0, 0));
        SlimeGround.Node here = SlimeGround.groundNode(level, bigSlime, cell(0, 0, 0)).orElse(null);
        check(here != null, "the approach starts from a real node");
        GridPos target = cell(6, 0, 0);
        SlimeGround.Route route = here == null ? null
                : SlimeGround.approach(level, bigSlime, here, target, 8, c -> false, c -> true,
                n -> n.cell().equals(cell(5, 0, 0))).orElse(null);
        check(route != null && route.nodes().size() == 5,
                "the straight approach is found, one edge per cell: " + (route == null ? "none" : route.nodes()));
        check(route != null && route.end().cell().equals(cell(5, 0, 0)),
                "the route ends on the node the contact needs, not on the opponent's own cell");
        check(route != null && route.nodes().size() <= 8, "the route never exceeds the edge budget it was given");
        // No contact anywhere: the answer is still a legal prefix that really closes in.
        SlimeGround.Route prefix = SlimeGround.approach(level, bigSlime, here, cell(6, 0, 0), 3,
                c -> false, c -> true, n -> false).orElse(null);
        check(prefix != null && prefix.nodes().size() == 3,
                "with no reachable contact the budgeted prefix is returned instead of nothing");
        check(prefix != null && prefix.end().cell().x() - here.cell().x() == 3,
                "that prefix really moved towards the opponent: " + (prefix == null ? "none" : prefix.end().cell()));
        // A cell another unit holds is never stepped on.
        GridPos blocked = cell(1, 0, 0);
        SlimeGround.Route detour = SlimeGround.approach(level, bigSlime, here, cell(6, 0, 0), 4,
                c -> c.equals(blocked), c -> true, n -> false).orElse(null);
        check(detour != null && !detour.nodes().contains(new SlimeGround.Node(blocked, feet(1, 0, 0))),
                "an occupied cell is never one of the nodes");
        // A battlefield that excludes the corridor leaves no edge at all.
        check(SlimeGround.approach(level, bigSlime, here, cell(6, 0, 0), 4,
                c -> false, c -> c.equals(here.cell()), n -> false).isEmpty(),
                "no cell outside the battlefield is ever offered");
        // The budget is a disclosure bound, not a magic constant: on an open floor with no reach anywhere the
        // walk really registers MAX_SEARCH_NODES states - the start included - and answers with the best legal
        // prefix. One registration more would ask the contact predicate 512 times instead of 511, which is
        // exactly the off-by-one this fixture has to be able to see.
        int[] contactAsked = {0};
        // The body that really stands on this node is the one whose own body is excluded from its own sweep.
        SlimeGround.Route bounded = SlimeGround.approach(level, bigSlime, here, cell(-40, 0, 0), 20,
                c -> false, c -> true, node -> {
                    contactAsked[0]++;
                    return false;
                }).orElse(null);
        check(contactAsked[0] == SlimeGround.MAX_SEARCH_NODES - 1,
                "the walk registers exactly 512 states, so a 513th discovery is never made: asked="
                        + contactAsked[0]);
        check(bounded != null && !bounded.nodes().isEmpty(),
                "and the bounded walk still answers with a legal prefix instead of nothing");
    }

    /** S3: who may hurt at all, and what the native attack box really reaches. */
    private static void contactCapability() {
        discardAll();
        // A size 4 slime and the owner two cells apart: grid adjacency says "no", the native box says "yes".
        bigSlime.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        SlimeGround.Node here = SlimeGround.groundNode(level, bigSlime, cell(0, 0, 0)).orElse(null);
        check(here != null, "the large body holds a real node next to the owner");
        GridPos ownerCell = new GridPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
        check(!com.tactical.core.grid.GridMovementRules.canStep(here.cell(), ownerCell),
                "the two cells are not grid-adjacent, so a one-cell melee rule would refuse this");
        check(SlimeContact.attackBoxReaches(bigSlime, here.feet(), owner),
                "the native attack box of a size 4 body really reaches two cells of centre distance");
        check(SlimeContact.eyeLineOfSight(level, bigSlime, here.feet(), owner),
                "the line of sight from its real eye is clear");
        // The same distance with a size 1 body: the box no longer reaches, so nothing is invented for it.
        tinySlime.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 4.5D);
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 4.5D);
        SlimeGround.Node tinyNode = SlimeGround.groundNode(level, tinySlime, cell(0, 0, 4)).orElse(null);
        check(tinyNode != null, "the small body holds its own node");
        check(!SlimeContact.attackBoxReaches(tinySlime, tinyNode.feet(), owner),
                "a size 1 body's box does not reach two cells, and its tiny rule refuses anyway");
        // Higher up: the box is widened horizontally only, exactly like vanilla - a 2.08 tall body already
        // overlaps one block of height, so the case that must be out of reach is three blocks up.
        owner.setPos(origin.getX() + 2.5D, origin.getY() + 3.0D, origin.getZ() + 0.5D);
        check(!SlimeContact.attackBoxReaches(bigSlime, here.feet(), owner),
                "a body three blocks up is really out of the native box (the reach is horizontal only)");
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        // A wall between the eye and the target refuses the contact even though the box still reaches. The
        // small body is used for this case: a wall cell inside the wide body's own footprint would push that
        // body out of the cell, which is a property of the world and not of the contact rule.
        tinySlime.setPos(tinyNode.feet());
        owner.setPos(tinyNode.feet().x + 1.2D, tinyNode.feet().y, tinyNode.feet().z);
        block(1, 0, 4, Blocks.STONE);
        block(1, 1, 4, Blocks.STONE);
        check(SlimeContact.attackBoxReaches(tinySlime, tinyNode.feet(), owner),
                "the small body's box still reaches through the wall");
        check(!SlimeContact.eyeLineOfSight(level, tinySlime, tinyNode.feet(), owner),
                "a wall between the real eyes really refuses the contact");
        block(1, 0, 4, Blocks.AIR);
        block(1, 1, 4, Blocks.AIR);
        // A size 1 slime still would damage natively - that is exactly why the encounter gates it by size.
        check(!SlimeContact.sizeCanHurt(tinySlime), "the encounter's own size gate refuses the tiny slime");
    }

    /** S3: the native contact really lands on a controlled body, and the numbers stay separate. */
    private static void nativeContactUnderNoAi() {
        tinySlime.setPos(feet(-6, 0, 2));
        bigSlime.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setPos(origin.getX() + 1.9D, origin.getY(), origin.getZ() + 0.5D);
        check(bigSlime.position().distanceToSqr(feet(0, 0, 0)) < 1.0E-6D,
                "the body really stands on the cell this case measures: " + bigSlime.position());
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        SlimeGround.Node here = SlimeGround.groundNode(level, bigSlime, cell(0, 0, 0)).orElse(null);
        check(here != null && SlimeContact.attackBoxReaches(bigSlime, here.feet(), owner),
                "fixture precondition: the large body's box reaches the owner: here=" + here
                        + " slime=" + bigSlime.position() + " owner=" + owner.position()
                        + " box=" + (here == null ? null : BodyGeometry.attackBoxAt(bigSlime, here.feet()))
                        + " hit=" + owner.getHitbox() + " alive=" + bigSlime.isAlive()
                        + " removed=" + bigSlime.isRemoved());
        check(bigSlime.isNoAi() && !bigSlime.isEffectiveAi(),
                "precondition: the body is NoAI, so vanilla's own contact gate would refuse this");
        float before = owner.getHealth();
        int contacts = contactCount;
        contactDamage = -1.0F;
        boolean hurt = SlimeContact.strike(bigSlime, owner);
        check(hurt, "the native dealDamage really reported a landed hit under NoAI");
        check(contactCount == contacts + 1, "exactly one real damage event was produced by that one call");
        check(Math.abs(bigSlime.getAttributeValue(Attributes.ATTACK_DAMAGE) - 4.0D) < 1.0E-9D,
                "the native attack value is the size, 4.0");
        check(contactDamage > 0.0F, "the event carries the applied damage: " + contactDamage);
        float lost = before - owner.getHealth();
        check(Math.abs(lost - contactDamage) < 1.0E-4F,
                "the health really lost equals the applied damage: " + lost + " vs " + contactDamage);
        check(!SlimeContact.inScope(), "the contact scope is cleared even after a successful call");
        // The magma cube's own override is kept: its native value is the size plus two.
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        smallMagma.setPos(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setPos(origin.getX() + 3.6D, origin.getY(), origin.getZ() + 0.5D);
        SlimeGround.Node magmaNode = SlimeGround.groundNode(level, smallMagma, cell(4, 0, 0)).orElse(null);
        check(magmaNode != null && SlimeContact.attackBoxReaches(smallMagma, magmaNode.feet(), owner),
                "fixture precondition: the size 2 magma cube's box reaches the owner");
        contactDamage = -1.0F;
        boolean magmaHurt = SlimeContact.strike(smallMagma, owner);
        check(magmaHurt, "the small magma cube really hurts, unlike a tiny slime");
        check(Math.abs(contactDamage - 4.0F) < 1.0E-4F
                        && contactDamage > (float) smallMagma.getAttributeValue(Attributes.ATTACK_DAMAGE),
                "its applied damage is vanilla's own size + 2 (" + contactDamage + "), above the raw attribute "
                        + smallMagma.getAttributeValue(Attributes.ATTACK_DAMAGE));
        check(Math.abs(smallMagma.getAttributeValue(Attributes.ATTACK_DAMAGE) - 2.0D) < 1.0E-9D,
                "the attribute itself stays the size; the +2 comes from the native getAttackDamage override");
    }

    /** S3: a cancelled hit is free and reported honestly - never guessed from health. */
    private static void cancelledContactIsFreeAndHonest() {
        owner.setPos(origin.getX() + 1.9D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        SlimeGround.Node here = SlimeGround.groundNode(level, bigSlime, cell(0, 0, 0)).orElse(null);
        check(here != null && SlimeContact.attackBoxReaches(bigSlime, here.feet(), owner),
                "fixture precondition: the box still reaches for the cancelled case");
        float before = owner.getHealth();
        int cancelled = cancelledContactCount;
        cancelNativeContact = true;
        boolean hurt;
        try {
            hurt = SlimeContact.strike(bigSlime, owner);
        } finally {
            cancelNativeContact = false;
        }
        check(cancelledContactCount == cancelled + 1, "the fixture's own cancellation really ran");
        check(!hurt, "a cancelled native contact reports false, not a health delta");
        check(Math.abs(owner.getHealth() - before) < 1.0E-4F, "and it really took no health");
        check(!SlimeContact.inScope(), "the scope is cleared on the cancelled path too");
    }

    /**
     * S3: one native contact driven by the encounter itself - charged once, and only when it really landed.
     */
    private static void contactTransactionThroughTheEncounter() {
        startContactTurn(EntityType.SLIME, 4, ContactCase.LANDED, 40, 1.4D);
    }

    /** S3: the same turn with the damage cancelled - nothing is charged and no success is counted. */
    private static void cancelledContactThroughTheEncounter() {
        startContactTurn(EntityType.SLIME, 4, ContactCase.CANCELLED, 40, 1.4D);
    }

    /**
     * R5: a size 1 magma cube really contacts natively under the encounter's own control.
     *
     * <p>The smallest magma cube is 0.52 wide, so its native box is a shorter reach: the owner stands 1.4 away
     * and the body's own box really reaches it. Its attribute is 1 and the damage it applies is vanilla's
     * {@code size + 2}, both reported separately from the health the player actually loses.</p>
     */
    private static void sizeOneMagmaContactsNatively() {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        tinyMagma = spawn(EntityType.MAGMA_CUBE, 0, 0, 0, 1);
        check(tinyMagma.getSize() == 1, "the size 1 magma cube really is the fixture body here");
        check(Math.abs(tinyMagma.getAttributeValue(Attributes.ATTACK_DAMAGE) - 1.0D) < 1.0E-9D,
                "its own attack attribute is 1, the size: " + tinyMagma.getAttributeValue(Attributes.ATTACK_DAMAGE));
        owner.setPos(origin.getX() + 1.4D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        SlimeGround.Node here = SlimeGround.groundNode(level, tinyMagma, cell(0, 0, 0)).orElse(null);
        check(here != null && SlimeContact.attackBoxReaches(tinyMagma, here.feet(), owner),
                "fixture precondition: the small magma cube's native box really reaches "
                        + (owner.position()) + " from " + (here == null ? "none" : here.feet()));
        float before = owner.getHealth();
        contactDamage = -1.0F;
        int contacts = contactCount;
        boolean hurt = SlimeContact.strike(tinyMagma, owner);
        check(hurt, "the size 1 magma cube really hurts natively");
        check(contactCount == contacts + 1, "exactly one real damage event came out of that one call");
        check(Math.abs(contactDamage - 3.0F) < 1.0E-4F,
                "its applied damage is vanilla's own size + 2: " + contactDamage);
        check(contactDamage > (float) tinyMagma.getAttributeValue(Attributes.ATTACK_DAMAGE),
                "which is really above the raw attribute it started from");
        float lost = before - owner.getHealth();
        check(Math.abs(lost - contactDamage) < 1.0E-4F,
                "the health the player really lost equals the applied damage on this difficulty: " + lost
                        + " vs " + contactDamage);
        // And the same body through the encounter's own transaction: the price is taken once, on a real hit.
        startContactTurn(EntityType.MAGMA_CUBE, 1, ContactCase.LANDED, 40, 0.9D);
    }

    /**
     * R5: the size 1 slime is the control - its native box reaches, and the encounter still spends and deals
     * nothing, because the family's own size gate may not use that attack at all.
     */
    private static void sizeOneSlimeCannotContact() {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        tinySlime = spawn(EntityType.SLIME, 0, 0, 0, 1);
        owner.setPos(origin.getX() + 1.4D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        SlimeGround.Node here = SlimeGround.groundNode(level, tinySlime, cell(0, 0, 0)).orElse(null);
        check(here != null && SlimeContact.attackBoxReaches(tinySlime, here.feet(), owner),
                "the size 1 slime's native box really reaches the owner at this distance");
        check(!SlimeContact.sizeCanHurt(tinySlime),
                "and the family's size gate still refuses it the attack, so distance is not the reason");
        // A pool of zero: this turn cannot even afford a hop, so its own turn really ends without doing anything.
        startContactTurn(EntityType.SLIME, 1, ContactCase.REFUSED, 0, 0.9D);
    }

    /** Which outcome one contact turn has to have. */
    private enum ContactCase { LANDED, CANCELLED, REFUSED }

    /**
     * Put one body in real contact range of the owner and let the encounter play its own turn.
     *
     * <p>The pool is set deliberately small and the body has not taken a turn yet, so its first own turn really
     * adds no recovery: what the pool holds afterwards is what the turn spent, and the success counter says
     * whether the native call really landed.</p>
     */
    private static void startContactTurn(EntityType<? extends Slime> type, int size, ContactCase kind,
                                         int pool, double away) {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        // Spawned after the purge: this stage owns exactly one body, and it is the one it measures.
        Slime body = spawn(type, 0, 0, 0, size);
        owner.setPos(origin.getX() + 0.5D + away, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        check(owner.hurt(body.damageSources().mobAttack(body), 1.0F),
                "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the contact encounter starts");
        battle = BattleManager.current();
        UUID id = body.getUUID();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(id);
        check(admitted != null && admitted.slime() != null, "the body was admitted with a real node");
        check(admitted != null && SlimeContact.attackBoxReaches(body, admitted.feet(), owner),
                "fixture precondition: its native box really reaches the owner");
        check(battle.fixtureEnemyPool(id, pool), "the fixture can set its pool for this case");
        contactBody = body;
        contactCellBefore = admitted == null ? null : admitted.cell();
        cancelNativeContact = kind == ContactCase.CANCELLED;
        contactWatch = new ContactWatch(id, pool, admitted == null ? 0 : admitted.paidActions(),
                owner.getHealth(), kind, false, 0);
        await(1, () -> watchContactTurn());
    }

    /** The body the current contact case drives, so the watcher asks that exact unit for its own turn. */
    private static Slime contactBody;
    /** The cell that body was admitted on, so a refused contact can prove it never moved either. */
    private static GridPos contactCellBefore;

    /** What one contact turn has to be judged against: the pool, the counter and the health before it. */
    private record ContactWatch(UUID id, int poolBefore, int paidBefore, float healthBefore,
                                ContactCase kind, boolean sawOwnTurn, int guard) {
        ContactWatch sawTurn() {
            return new ContactWatch(id, poolBefore, paidBefore, healthBefore, kind, true, guard);
        }

        ContactWatch next() {
            return new ContactWatch(id, poolBefore, paidBefore, healthBefore, kind, sawOwnTurn, guard + 1);
        }
    }

    private static ContactWatch contactWatch;

    /**
     * Wait on a fact, not on a tick count: the body has to have had its own turn, and the turn has to be back
     * with the player with nothing in flight. The cancelled case deliberately never counts a success, so the
     * success counter cannot be the signal there.
     */
    private static void watchContactTurn() {
        ContactWatch watch = contactWatch;
        if (watch == null) return;
        if (watch.guard() > 600) {
            cancelNativeContact = false;
            check(false, "the contact turn really resolved: paid=" + watch.paidBefore() + " case="
                    + watch.kind() + " sawOwnTurn=" + watch.sawOwnTurn());
            return;
        }
        if ((contactBody != null && battle.isActorTurn(contactBody)) || battle.isBusy()) {
            contactWatch = watch.sawTurn();
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(watch.id());
        boolean settled = battle.isPlayerTurnNow() && !battle.isBusy();
        if (!settled || !contactWatch.sawOwnTurn() || view == null) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            contactWatch = contactWatch.next();
            await(1, SlimeSelfTest::watchContactTurn);
            return;
        }
        cancelNativeContact = false;
        if (watch.kind() == ContactCase.CANCELLED) {
            check(view.paidActions() == watch.paidBefore(),
                    "a cancelled contact counts no paid action: " + watch.paidBefore() + " -> "
                            + view.paidActions());
            check(view.ap() == watch.poolBefore(),
                    "and it charges nothing from the pool: " + watch.poolBefore() + " -> " + view.ap());
            check(Math.abs(owner.getHealth() - watch.healthBefore()) < 1.0E-4F,
                    "the owner really took no health: " + watch.healthBefore() + " -> " + owner.getHealth());
        } else if (watch.kind() == ContactCase.REFUSED) {
            check(view.paidActions() == watch.paidBefore(),
                    "a contact the family's size gate refuses counts no paid action: " + watch.paidBefore()
                            + " -> " + view.paidActions());
            check(view.ap() == watch.poolBefore(),
                    "and it charges nothing from the pool: " + watch.poolBefore() + " -> " + view.ap());
            check(Math.abs(owner.getHealth() - watch.healthBefore()) < 1.0E-4F,
                    "the owner really took no health: " + watch.healthBefore() + " -> " + owner.getHealth());
            check(view.cell().equals(contactCellBefore),
                    "and the body never moved either, so nothing at all was spent: " + contactCellBefore
                            + " -> " + view.cell());
        } else {
            check(view.paidActions() == watch.paidBefore() + 1,
                    "a landed contact counts exactly one paid action: " + watch.paidBefore() + " -> "
                            + view.paidActions());
            check(view.ap() < watch.poolBefore(),
                    "and it is really paid for out of its own pool: " + watch.poolBefore() + " -> " + view.ap());
            check(owner.getHealth() < watch.healthBefore(),
                    "the owner really lost health to the native call: " + watch.healthBefore() + " -> "
                            + owner.getHealth());
        }
        battle.finish("fixture contact done");
    }

    /** S4: the hop arc itself - frames, exact endpoints and the exact apex. */
    private static void hopArcGeometry() {
        discardAll();
        Vec3 from = feet(0, 0, 0);
        Vec3 to = feet(1, 0, 0);
        check(SlimeMotion.framesFor(bigSlime) == 8 && SlimeMotion.framesFor(bigMagma) == 12,
                "a slime hops in 8 frames and a magma cube in 12");
        SlimeMotion.Leg leg = SlimeMotion.legFor(level, bigSlime, from, to, c -> true).orElse(null);
        check(leg != null, "one edge on an open floor really resolves a leg");
        check(leg != null && leg.frames() == 8, "the leg carries the family's own frame count");
        check(leg != null && leg.point(0).distanceToSqr(from) < 1.0E-12D, "frame 0 is the take-off exactly");
        check(leg != null && leg.point(leg.frames()).distanceToSqr(to) < 1.0E-12D,
                "the last frame is the landing exactly");
        check(leg != null && Math.abs(leg.height(0.5D) - (from.y + SlimeMotion.APEX_RISE)) < 1.0E-9D,
                "the apex is exactly 0.6 above the higher end, not a sampled guess");
        check(leg != null && Math.abs(leg.point(4).y - (from.y + SlimeMotion.APEX_RISE)) < 1.0E-9D,
                "the middle frame really is the apex for an 8-frame leg");
        check(leg != null && !leg.low(), "an open floor uses the default tier");
        SlimeMotion.Leg magmaLeg = SlimeMotion.legFor(level, bigMagma, from, to, c -> true).orElse(null);
        check(magmaLeg != null && magmaLeg.frames() == 12, "the magma cube flies the same edge in 12 frames");
        check(magmaLeg != null && Math.abs(magmaLeg.height(0.5D)
                        - (from.y + SlimeMotion.APEX_RISE + SlimeMotion.MAGMA_APEX_PER_SIZE * 4)) < 1.0E-9D,
                "and its apex carries its own real size");
        check(magmaLeg != null && Math.abs(magmaLeg.point(6).y - magmaLeg.height(0.5D)) < 1.0E-9D,
                "its middle frame is the apex as well");
        // The height never exceeds the declared apex anywhere between the endpoints.
        double apex = leg == null ? 0.0D : leg.apexY();
        boolean within = true;
        for (int frame = 0; leg != null && frame <= leg.frames(); frame++) {
            within &= leg.point(frame).y <= apex + 1.0E-9D;
        }
        check(within, "no frame of the arc rises above the declared apex");
    }

    /** S4: an edge the world refuses, and the low tier that only a taller body needs. */
    private static void hopRefusedEdgesAndLowTier() {
        discardAll();
        Vec3 from = feet(0, 0, 0);
        Vec3 to = feet(1, 0, 0);
        // A wall in the swept body refuses the edge for the wide body while the small one still flies it.
        block(0, 0, 1, Blocks.STONE);
        block(0, 1, 1, Blocks.STONE);
        block(0, 2, 1, Blocks.STONE);
        check(SlimeMotion.legFor(level, bigSlime, from, to, c -> true).isEmpty(),
                "a wall in the swept body refuses the wide body's edge");
        // The destination cell itself under a solid block has no node at all.
        check(SlimeGround.groundNode(level, bigSlime, cell(1, 0, 0)).isEmpty()
                        || SlimeMotion.legFor(level, bigSlime, from, to, c -> true).isEmpty(),
                "a destination the body cannot occupy is never landed on");
        block(0, 0, 1, Blocks.AIR);
        block(0, 1, 1, Blocks.AIR);
        block(0, 2, 1, Blocks.AIR);
        // A three-block room: a size 4 magma cube needs 1.4 of apex and only has 0.92 of clearance, so its
        // default tier is refused and the one low tier is used; a size 4 slime still clears it at 0.6.
        for (int dz = -1; dz <= 2; dz++) {
            for (int dx = -1; dx <= 2; dx++) {
                block(dx, 3, dz + 2, Blocks.STONE);
            }
        }
        Vec3 lowFrom = feet(0, 0, 2);
        Vec3 lowTo = feet(1, 0, 2);
        SlimeMotion.Leg low = SlimeMotion.legFor(level, bigMagma, lowFrom, lowTo, c -> true).orElse(null);
        check(low != null, "the tall magma cube still has an edge under that ceiling: size=" + bigMagma.getSize()
                + " gates[deploy=" + BattleDeployment.isStandable(level, bigMagma, lowFrom)
                + " loaded=" + BodyGeometry.loaded(level, BodyGeometry.bodyAt(bigMagma, lowFrom))
                + " noCollision=" + level.noCollision(bigMagma, BodyGeometry.bodyAt(bigMagma, lowFrom))
                + " liquid=" + level.containsAnyLiquid(BodyGeometry.bodyAt(bigMagma, lowFrom))
                + " bodies=" + level.getEntities(bigMagma, BodyGeometry.bodyAt(bigMagma, lowFrom),
                        o -> !o.isSpectator() && o.isAlive()
                                && (o instanceof net.minecraft.world.entity.LivingEntity || o.canBeCollidedWith()))
                + "]"
                + " h=" + bigMagma.getBbHeight() + " from=" + lowFrom + " to=" + lowTo
                + " startNode=" + SlimeGround.groundNode(level, bigMagma, cell(0, 0, 2))
                + " endNode=" + SlimeGround.groundNode(level, bigMagma, cell(1, 0, 2))
                + " pos=" + bigMagma.position()
                + " startBox=" + BodyGeometry.bodyAt(bigMagma, lowFrom));

        check(low != null && low.low() && Math.abs(low.apexRise() - SlimeMotion.LOW_APEX_RISE) < 1.0E-9D,
                "it is the single low tier, at 0.15: " + (low == null ? "none" : low.apexRise()));
        SlimeMotion.Leg normal = SlimeMotion.legFor(level, bigSlime, lowFrom, lowTo, c -> true).orElse(null);
        check(normal != null && !normal.low(),
                "a size 4 slime still uses the default tier in the same room");
        for (int dz = -1; dz <= 2; dz++) {
            for (int dx = -1; dx <= 2; dx++) {
                block(dx, 3, dz + 2, Blocks.AIR);
            }
        }
    }

    /**
     * S4: a hop really costs its own edges and nothing else, and it really moves the body.
     *
     * <p>Driven through the encounter on real server ticks: the body is admitted, its own turn plans the route,
     * the price is taken per edge and the success counter is untouched by movement.</p>
     */
    private static void hopPriceThroughTheEncounter() {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        // Far enough that the hop turn ends without a follow-up contact: this stage prices the movement, and a
        // contact after it would be a second action with its own (escalating) price.
        owner.setPos(origin.getX() + 16.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        bigSlime = spawn(EntityType.SLIME, -5, 0, 0, 4);
        check(owner.hurt(bigSlime.damageSources().mobAttack(bigSlime), 1.0F),
                "fixture precondition: the owner can really be damaged");
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the slime's encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(bigSlime.getUUID());
        check(admitted != null, "the slime was really deployed into the encounter");
        check(admitted != null && admitted.slime() != null,
                "the encounter resolved a real ground node for it");
        check(admitted != null && admitted.feet().distanceToSqr(bigSlime.position()) < 1.0E-6D,
                "admission keeps the real feet");
        check(bigSlime.isNoAi(), "the admitted body is NoAI, as every unit of this encounter is");
        UUID id = bigSlime.getUUID();
        hopFrozenAp = Integer.MIN_VALUE;
        int moveCost = admitted == null ? 1 : Math.max(1, admitted.moveCost());
        // A short pool on purpose: a few edges only, so the turn is priced and observed without also walking
        // into contact. The contact itself is measured by its own stages.
        check(battle.fixtureEnemyPool(id, 3 * moveCost), "the fixture can set the body's own pool for this case");
        ServerBattle.EnemyView start = battle.fixtureEnemy(id);
        check(start != null && start.ap() == 3 * moveCost, "the pool really holds three edges' worth");
        int apBefore = start == null ? 0 : start.ap();
        int paidBefore = start == null ? 0 : start.paidActions();
        GridPos cellBefore = start == null ? null : start.cell();
        await(1, () -> watchHop(id, 0, apBefore, paidBefore, cellBefore, moveCost));
    }

    /**
     * One real server tick of the slime's own turn, until it has really acted and the turn is back.
     *
     * <p>The wait is on a fact, not on a tick count: the body has to have spent AP, and the turn has to be back
     * with the player and nothing may be in flight. Handing the player's turn on every tick while waiting is
     * what actually lets the encounter play the enemy's turn.</p>
     */
    private static void watchHop(UUID id, int guard, int apBefore, int paidBefore, GridPos cellBefore, int moveCost) {
        if (guard > 900) {
            ServerBattle.EnemyView stuck = battle.fixtureEnemy(id);
            check(false, "the slime really acted inside its own turn: apBefore=" + apBefore + " now="
                    + (stuck == null ? "gone" : stuck.ap() + " cell=" + stuck.cell() + " hopping=" + stuck.hopping()
                    + " busy=" + battle.isBusy() + " playerTurn=" + battle.isPlayerTurnNow()));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, "the slime stayed on the roster");
            return;
        }
        if (view.hopping()) {
            if (hopFrozenAp == Integer.MIN_VALUE) {
                hopFrozenAp = view.ap();
                check(hopFrozenAp < apBefore + 64,
                        "the route was really taken out of this body's own pool: " + apBefore + " -> " + hopFrozenAp);
            } else {
                // The whole route is priced once when it is committed: an animated frame costs nothing, so a
                // pool that moved between two frames would mean the price was being re-read per tick.
                check(view.ap() == hopFrozenAp,
                        "the frozen route price never changes per frame: " + hopFrozenAp + " -> " + view.ap());
            }
            Vec3 airborne = bigSlime.position();
            check(airborne.y >= origin.getY(), "the body is really on its arc: " + airborne);
            await(1, () -> watchHop(id, guard + 1, apBefore, paidBefore, cellBefore, moveCost));
            return;
        }
        boolean settled = battle.isPlayerTurnNow() && !battle.isBusy();
        boolean acted = view.ap() < apBefore || !view.cell().equals(cellBefore);
        if (!settled || !acted) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchHop(id, guard + 1, apBefore, paidBefore, cellBefore, moveCost));
            return;
        }
        check(!view.cell().equals(cellBefore),
                "the body really changed cell by hopping: " + cellBefore + " -> " + view.cell());
        check(view.paidActions() == paidBefore,
                "movement never counts a paid action: " + paidBefore + " -> " + view.paidActions());
        check(hopFrozenAp != Integer.MIN_VALUE && view.ap() == hopFrozenAp,
                "the price charged at the commit is the price it holds after landing: frozen=" + hopFrozenAp
                        + " landed=" + view.ap());
        check(view.slime() != null, "the body still holds a real node after landing");
        check(bigSlime.position().distanceToSqr(view.feet()) < 1.0E-6D,
                "the landing is the node's own feet, not an interpolated leftover: " + bigSlime.position()
                        + " vs " + view.feet());
        check(!battle.isBusy(), "the encounter is not left busy after the hop");
        check(battle.isPlayerTurnNow(), "the turn really came back to the player");
        battle.finish("fixture hop done");
        check(((TacticalSlimeAccess) bigSlime).tactical$poseHandler() == null,
                "ending the encounter gives the body back to its own tick");
    }

    /**
     * S4: an interruption less than a whole block above the floor is a swept descent, not a snap.
     *
     * <p>Two cases, judged against the encounter's own declared slice: a drop smaller than one slice may be
     * swept to the foot surface in a single bounded step, while a drop larger than one slice has to show a real
     * intermediate sample. Both keep the real X/Z, end exactly on the real foot surface, and are watched with
     * the encounter's own busy, epoch and other-member state across real world ticks.</p>
     */
    private static void fractionalInterruptionFallsBySweep() {
        subBlockInterruption(2, "0.45");
    }

    /** S4, second case: the highest point of a hop, which needs more than one bounded slice. */
    private static void arcApexInterruptionFallsBySlice() {
        subBlockInterruption(4, "0.60");
    }

    /** Quiet state of one descent: the slice it may take, the sample it took and the barrier it must keep. */
    private static void subBlockInterruption(int frame, String label) {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 16.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        Slime body = spawn(EntityType.SLIME, -5, 0, 0, 2);
        bigSlime = body;
        // The previous case's damage would otherwise leave the owner inside its own invulnerability window.
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        check(owner.hurt(body.damageSources().mobAttack(body), 1.0F),
                label + ": fixture precondition: the owner can really be damaged");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), label + ": the interrupted body's encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView view = battle.fixtureEnemy(body.getUUID());
        check(view != null && view.slime() != null, label + ": the body was admitted with a real node");
        if (view == null || view.slime() == null) return;
        Vec3 start = view.feet();
        Vec3 end = start.add(1.0D, 0.0D, 0.0D);
        SlimeMotion.Leg leg = SlimeMotion.legFor(level, body, start, end, battle.zone()::contains).orElse(null);
        check(leg != null, label + ": the leg it is about to lose really existed a moment ago");
        if (leg == null) return;
        Vec3 sample = leg.point(frame);
        double floor = new GridPos((int) Math.floor(sample.x), (int) Math.floor(sample.y),
                (int) Math.floor(sample.z)).y();
        descentBody = body;
        descentLabel = label;
        descentStartX = sample.x;
        descentStartZ = sample.z;
        descentStartY = sample.y;
        descentFloorY = floor;
        descentSlice = battle.fixtureSubCellDescent();
        descentLastY = sample.y;
        descentSamples = 0;
        descentMaxDrift = 0.0D;
        descentEpoch = battle.turnEpoch();
        descentOwnerAp = battle.currentAp(owner);
        check(sample.y - floor > 0.0D, label + ": it really starts above its own floor: " + (sample.y - floor));
        body.setPos(sample);
        // Watch from the very next tick: a descent that needs two slices has to be seen between them, and
        // waiting an extra tick would only ever observe the settled body.
        await(0, () -> watchDescent(body.getUUID(), 0));
    }

    /** Real world ticks of the interrupted body's descent, with its own barrier readings. */
    private static void watchDescent(UUID id, int guard) {
        if (guard > 200) {
            check(false, descentLabel + ": the interrupted body really reached its own floor: y="
                    + descentBody.position().y + " floor=" + descentFloorY);
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(id);
        if (view == null) {
            check(false, descentLabel + ": the interrupted body stayed on the roster");
            return;
        }
        Vec3 now = descentBody.position();
        double drop = descentLastY - now.y;
        check(drop <= descentSlice + 1.0E-4D,
                descentLabel + ": one tick never drops more than the declared slice: " + drop
                        + " > slice " + descentSlice);
        check(drop >= -1.0E-9D, descentLabel + ": the descent never rises: " + (-drop));
        descentLastY = now.y;
        descentMaxDrift = Math.max(descentMaxDrift, Math.hypot(now.x - descentStartX, now.z - descentStartZ));
        if (Math.abs(now.y - descentFloorY) > 1.0E-4D) {
            descentSamples++;
            check(battle.terrainFallActive(),
                    descentLabel + ": the fall really owns the body while it is in the air");
            check(battle.isBusy(), descentLabel + ": the encounter is busy while the body descends");
            check(battle.turnEpoch() == descentEpoch,
                    descentLabel + ": no turn is handed over inside the fall window");
            check(battle.currentAp(owner) == descentOwnerAp,
                    descentLabel + ": the other side recovers nothing inside the fall window: "
                            + descentOwnerAp + " -> " + battle.currentAp(owner));
            check(descentBody.isNoAi(), descentLabel + ": the body stays NoAI through the descent");
            await(1, () -> watchDescent(id, guard + 1));
            return;
        }
        check(Math.abs(now.y - descentFloorY) < 1.0E-4D,
                descentLabel + ": the descent ends exactly on the cell's own floor: y=" + now.y
                        + " floor=" + descentFloorY);
        check(descentMaxDrift < 1.0E-4D,
                descentLabel + ": it keeps its own X/Z through the whole fall (no cell-centre snap): drift="
                        + descentMaxDrift);
        double total = descentStartY - descentFloorY;
        if (total > descentSlice + 1.0E-4D) {
            check(descentSamples >= 1,
                    descentLabel + ": a drop larger than one slice shows a real intermediate sample: samples="
                            + descentSamples + " total=" + total + " slice=" + descentSlice);
        } else {
            check(descentSamples == 0,
                    descentLabel + ": a drop inside one slice is one bounded sweep, not a staged animation: samples="
                            + descentSamples + " total=" + total + " slice=" + descentSlice);
        }
        check(view.slime() != null, descentLabel + ": a landed body holds a real node again");
        check(!battle.terrainFallActive(), descentLabel + ": the fall really settled");
        check(view.ap() >= 0, descentLabel + ": the fall charged nothing: ap=" + view.ap());
        check(descentBody.onGround(), descentLabel + ": a landed body reports itself on the ground again");
        battle.finish("fixture descent done");
    }

    // ------------------------------------------------------------------ R1: the frozen per-edge quote

    /** R1: a route the world refuses before the first frame is displaced costs nothing at all. */
    private static void firstFrameRefusalIsFree() {
        startRefundCase("R1a first-frame refusal", true);
    }

    /** R1: once the body really moved on an edge, only that edge stays paid - at the price frozen at the commit. */
    private static void startedEdgeStaysPaidAtFrozenPrice() {
        startRefundCase("R1b started edge", false);
    }

    /**
     * Commit one real multi-edge route and take the world out from under it.
     *
     * <p>The route comes from the encounter's own AI on real server ticks: the body is admitted, its pool holds
     * several edges, and the commit is the production one. The first case breaks the very first edge's swept
     * body before it is built, the second breaks the following edge once the body has really left the floor and
     * also changes the per-edge price after the commit - which is what separates a refund at the frozen quote
     * from a refund recomputed from a changed {@code moveCost}.</p>
     */
    private static void startRefundCase(String label, boolean firstFrame) {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        // Far enough that the turn is priced and observed without also walking into contact.
        owner.setPos(origin.getX() + 16.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        bigSlime = spawn(EntityType.SLIME, -5, 0, 0, 4);
        check(owner.hurt(bigSlime.damageSources().mobAttack(bigSlime), 1.0F),
                label + ": fixture precondition: the owner can really be damaged");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), label + ": the hop encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(bigSlime.getUUID());
        check(admitted != null && admitted.slime() != null, label + ": the body was admitted with a real node");
        int moveCost = admitted == null ? 1 : Math.max(1, admitted.moveCost());
        int pool = 6 * moveCost;
        check(battle.fixtureEnemyPool(bigSlime.getUUID(), pool), label + ": the pool holds room for a route");
        ServerBattle.EnemyView funded = battle.fixtureEnemy(bigSlime.getUUID());
        check(funded != null && funded.ap() == pool, label + ": the pool really holds the edges this case prices");
        refundLabel = label;
        refundId = bigSlime.getUUID();
        refundFirstFrame = firstFrame;
        refundPerEdge = moveCost;
        refundPoolBefore = funded == null ? pool : funded.ap();
        refundPrice = 0;
        refundEdges = 0;
        refundBroken = false;
        refundPaidBefore = admitted == null ? 0 : admitted.paidActions();
        refundOwnerAp = battle.currentAp(owner);
        refundOwnerMin = refundOwnerAp;
        refundStartFeet = bigSlime.position();
        refundStartCell = admitted == null ? null : admitted.cell();
        await(1, () -> watchRefund(0));
    }

    private static UUID refundId;
    private static String refundLabel = "";
    private static boolean refundFirstFrame;
    private static boolean refundBroken;
    private static int refundPerEdge;
    private static int refundPoolBefore;
    private static int refundPrice;
    private static int refundEdges;
    private static int refundPaidBefore;
    private static int refundOwnerAp;
    private static int refundOwnerMin;
    private static Vec3 refundStartFeet;
    private static GridPos refundStartCell;

    private static void watchRefund(int guard) {
        if (guard > 900) {
            ServerBattle.EnemyView stuck = battle.fixtureEnemy(refundId);
            check(false, refundLabel + ": the interrupted route really resolved: " + (stuck == null ? "gone"
                    : "hopping=" + stuck.hopping() + " ap=" + stuck.ap() + " cell=" + stuck.cell())
                    + " busy=" + battle.isBusy() + " playerTurn=" + battle.isPlayerTurnNow());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(refundId);
        if (view == null) {
            check(false, refundLabel + ": the body stayed on the roster");
            return;
        }
        // The other member is watched on every tick this move is in flight: its pool may recover at its own turn
        // start, but it may never be charged for somebody else's movement. The lowest reading is asserted once,
        // so the check count of this fixture does not depend on how many ticks a case happened to take.
        refundOwnerMin = Math.min(refundOwnerMin, battle.currentAp(owner));
        if (view.hopping()) {
            if (refundPrice == 0) {
                // The commit itself: one quote for the whole route, taken once out of this body's own pool.
                refundEdges = view.hopEdges();
                refundPrice = view.hopPrice();
                check(refundEdges >= 2, refundLabel + ": the committed route has more than one edge: " + refundEdges);
                check(refundPrice == refundEdges * refundPerEdge,
                        refundLabel + ": the committed route is quoted per edge and frozen: " + refundPrice
                                + " for " + refundEdges + " edges at " + refundPerEdge);
                check(view.ap() == refundPoolBefore - refundPrice,
                        refundLabel + ": the whole route is taken out of its own pool once: " + refundPoolBefore
                                + " -> " + view.ap());
                check(view.ap() < refundPoolBefore, refundLabel + ": the pool really moved");
                check(view.paidActions() == refundPaidBefore,
                        refundLabel + ": a hop never counts a paid action: " + refundPaidBefore + " -> "
                                + view.paidActions());
            }
            if (refundFirstFrame && !refundBroken) {
                // The route is committed and nothing has been displaced yet: break the first edge's swept body
                // two cells out, so the very first frame it would fly no longer exists.
                check(view.hopIndex() == 0 && !view.hopEdgeStarted(),
                        refundLabel + ": the route is still at its commit, with no edge begun: index="
                                + view.hopIndex() + " started=" + view.hopEdgeStarted());
                check(bigSlime.position().distanceToSqr(refundStartFeet) < 1.0E-6D,
                        refundLabel + ": and the commit itself moved nothing: " + bigSlime.position());
                for (int y = 0; y < 5; y++) block(-3, y, 0, Blocks.STONE);
                refundBroken = true;
            }
            if (!refundFirstFrame && !refundBroken && bigSlime.getY() > refundStartFeet.y + 1.0E-6D) {
                // The body has really left the floor on its first edge: break the next edge and change the price.
                check(view.hopIndex() == 0 && view.hopEdgeStarted(),
                        refundLabel + ": the first edge is really in flight: index=" + view.hopIndex()
                                + " started=" + view.hopEdgeStarted());
                for (int y = 0; y < 5; y++) block(-2, y, 0, Blocks.STONE);
                check(battle.fixtureEnemyMoveCost(refundId, 2 * refundPerEdge),
                        refundLabel + ": the fixture can change the per-edge price after the commit");
                refundBroken = true;
            }
            await(0, () -> watchRefund(guard + 1));
            return;
        }
        // A turn that is still the player's has not played the hop yet: hand it on and keep watching.
        boolean settled = battle.isPlayerTurnNow() && !battle.isBusy() && refundPrice != 0;
        if (!settled) {
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchRefund(guard + 1));
            return;
        }
        check(refundBroken, refundLabel + ": the route really met the world change this case needs");
        check(refundEdges >= 2, refundLabel + ": the frozen route really had several edges");
        // A route refused before its first frame is free; otherwise exactly the one edge the body really began
        // moving on stays paid.
        int chargedEdges = refundFirstFrame ? 0 : 1;
        int expected = refundPoolBefore - chargedEdges * refundPerEdge;
        check(view.ap() == expected,
                refundLabel + ": the refund settles at the frozen per-edge price, so only the edges the body "
                        + "really began stay paid: expected " + expected + " (pool " + refundPoolBefore + " minus "
                        + chargedEdges + " edge(s) at " + refundPerEdge + ", the price it later changed to was "
                        + (2 * refundPerEdge) + ") but the pool holds " + view.ap());
        check(view.paidActions() == refundPaidBefore,
                refundLabel + ": and a refund never counts a paid action: " + refundPaidBefore + " -> "
                        + view.paidActions());
        check(refundOwnerMin >= refundOwnerAp,
                refundLabel + ": the other side's pool never fell below where it started for this move: "
                        + refundOwnerAp + " -> lowest seen " + refundOwnerMin);
        check(!view.hopping(), refundLabel + ": the interrupted route is really over");
        check(view.slime() != null, refundLabel + ": the body holds a real node again");
        Vec3 held = view.slime() == null ? null : view.slime().feet();
        check(held != null && bigSlime.position().distanceToSqr(held) < 1.0E-6D,
                refundLabel + ": it keeps the real feet it ended on, with no cell-centre snap: "
                        + bigSlime.position() + " vs " + held);
        check(bigSlime.onGround(), refundLabel + ": a body that ended on a real stand reports itself on the ground");
        if (refundFirstFrame) {
            check(view.cell().equals(refundStartCell),
                    refundLabel + ": a route refused before its first frame leaves the body in its own cell: "
                            + view.cell());
            check(bigSlime.position().distanceToSqr(refundStartFeet) < 1.0E-6D,
                    refundLabel + ": and its real position is untouched, so nothing was displaced at all: "
                            + bigSlime.position());
            check(!battle.terrainFallActive(), refundLabel + ": and nothing had to fall either");
        } else {
            check(view.cell().x() - refundStartCell.x() == 1 && view.cell().y() == refundStartCell.y()
                            && view.cell().z() == refundStartCell.z(),
                    refundLabel + ": the body really walked its first edge and no more: " + refundStartCell
                            + " -> " + view.cell());
        }
        check(!battle.isBusy(), refundLabel + ": the encounter is not left busy");
        battle.finish("fixture refund done");
    }

    // ------------------------------------------------------------------ R2: the real landing and the real held point

    private static UUID hopWatchId;
    private static String hopWatchLabel = "";
    private static Slime hopWatchBody;
    private static double hopWatchStartY;
    private static boolean hopWatchBroken;
    private static GridPos hopWatchStartCell;

    /**
     * R2: an edge whose landing is dug out from under it must stop, not "land" on an empty sweep.
     *
     * <p>This is the production interruption: the route is committed and animated by the encounter itself, the
     * fixture only changes the world on a real server tick, and the body is never placed on an arc sample by
     * hand. The landing cell really loses its support while the body is in the air, so the only way the hop can
     * continue is by calling an empty box a landing - which is exactly what must not happen.</p>
     */
    private static void dugLandingAbortsMidHop() {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 16.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        // A size 1 body: its whole footprint stays inside one cell, so the hole really only removes the landing.
        bigSlime = spawn(EntityType.SLIME, -5, 0, 0, 1);
        check(owner.hurt(bigSlime.damageSources().mobAttack(bigSlime), 1.0F),
                "dug landing: fixture precondition: the owner can really be damaged");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "dug landing: the hop encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(bigSlime.getUUID());
        check(admitted != null && admitted.slime() != null, "dug landing: the body was admitted with a real node");
        check(admitted != null && admitted.cell().equals(cell(-5, 0, 0)),
                "dug landing: it starts on the cell this case breaks the next column of: "
                        + (admitted == null ? "none" : admitted.cell()));
        int moveCost = admitted == null ? 1 : Math.max(1, admitted.moveCost());
        check(battle.fixtureEnemyPool(bigSlime.getUUID(), 6 * moveCost),
                "dug landing: the pool holds room for a route");
        hopWatchLabel = "dug landing";
        hopWatchId = bigSlime.getUUID();
        hopWatchBody = bigSlime;
        hopWatchStartY = bigSlime.getY();
        hopWatchBroken = false;
        hopWatchStartCell = admitted == null ? null : admitted.cell();
        await(1, () -> watchDugLanding(0));
    }

    private static void watchDugLanding(int guard) {
        if (guard > 900) {
            check(false, hopWatchLabel + ": the hop really met the dug-out landing: y=" + hopWatchBody.getY()
                    + " busy=" + battle.isBusy() + " fall=" + battle.terrainFallActive());
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(hopWatchId);
        if (view == null) {
            check(false, hopWatchLabel + ": the body stayed on the roster");
            return;
        }
        if (view.hopping()) {
            if (!hopWatchBroken && hopWatchBody.getY() > hopWatchStartY + 1.0E-6D) {
                // The body is really in the air on this edge: take the support out of the cell it flies to.
                GridPos landing = new GridPos(hopWatchStartCell.x() + 1, hopWatchStartCell.y(),
                        hopWatchStartCell.z());
                block(landing.x() - origin.getX(), landing.y() - origin.getY() - 1, landing.z() - origin.getZ(),
                        Blocks.AIR);
                hopWatchBroken = true;
            }
            await(0, () -> watchDugLanding(guard + 1));
            return;
        }
        if (battle.isBusy()) {
            // The descent owns the encounter until the body really lands.
            await(0, () -> watchDugLanding(guard + 1));
            return;
        }
        if (!hopWatchBroken) {
            // The hop has not been played yet: this is still the player's turn.
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchDugLanding(guard + 1));
            return;
        }
        check(hopWatchBroken, hopWatchLabel + ": the world change really happened while the body was in the air");
        // The edge is over and the body is on the ground: it never reached the cell whose floor was removed.
        check(view.cell().equals(hopWatchStartCell),
                hopWatchLabel + ": the hop stopped short of the dug-out landing instead of finishing it: "
                        + hopWatchStartCell + " -> " + view.cell());
        check(view.slime() != null, hopWatchLabel + ": the interrupted body holds a real node again");
        Vec3 held = view.slime() == null ? null : view.slime().feet();
        check(held != null && bigSlime.position().distanceToSqr(held) < 1.0E-6D,
                hopWatchLabel + ": the node is the real sample, not a cell centre: " + bigSlime.position()
                        + " vs " + held);
        check(held != null && Math.abs(held.x - (hopWatchStartCell.x() + 0.5D)) > 1.0E-3D,
                hopWatchLabel + ": and that real sample is fractional, which is what proves no centre snap: "
                        + held);
        check(bigSlime.onGround(), hopWatchLabel + ": it reports itself on the ground again");
        check(!battle.terrainFallActive(), hopWatchLabel + ": the descent really settled");
        battle.finish("fixture dug landing done");
    }

    /**
     * R2: a real safe deployment may keep a fractional X/Z, and admission must adopt exactly that point.
     *
     * <p>A size 2 body is 1.04 wide, so at its cell centre it reaches 1.02 into the cell next to it: a block
     * standing there refuses the centre and leaves only the off-centre position legal. If admission rebuilt the
     * cell centre and moved the body there, this unit could not even be taken.</p>
     */
    private static void fractionalDeploymentKeepsRealFeet() {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        block(1, 0, 0, Blocks.STONE);
        owner.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        midSlime = spawn(EntityType.SLIME, 0, 0, 0, 2);
        // 0.40, not 0.48: the box then ends at 0.92 and really clears the block at 1.0 with a margin.
        Vec3 offCentre = new Vec3(origin.getX() + 0.40D, origin.getY(), origin.getZ() + 0.5D);
        midSlime.setPos(offCentre);
        check(SlimeGround.groundNode(level, midSlime, cell(0, 0, 0)).isEmpty(),
                "the cell centre is refused for this body: a block really stands in the way there");
        check(SlimeGround.heldNode(level, midSlime).isPresent(),
                "while its real off-centre position is a real stand: " + midSlime.position());
        check(owner.hurt(midSlime.damageSources().mobAttack(midSlime), 1.0F),
                "fractional deployment: fixture precondition: the owner can really be damaged");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "the fractionally placed body is really taken");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(midSlime.getUUID());
        check(admitted != null && admitted.slime() != null,
                "its node was resolved from the real position, not from the cell centre");
        check(admitted != null && Math.abs(admitted.feet().x - offCentre.x) < 1.0E-9D
                        && Math.abs(admitted.feet().z - offCentre.z) < 1.0E-9D,
                "and that node carries the real fractional feet: " + (admitted == null ? "none" : admitted.feet()));
        check(midSlime.position().distanceToSqr(offCentre) < 1.0E-9D,
                "admission did not recentre the body: " + midSlime.position());
        SlimePoseHandler handler = ((TacticalSlimeAccess) midSlime).tactical$poseHandler();
        check(handler != null && handler.tactical$controls(midSlime),
                "the pose seam really owns this body");
        Vec3 pinned = handler == null ? null : handler.tactical$pinFeet(midSlime);
        check(pinned != null && pinned.distanceToSqr(offCentre) < 1.0E-9D,
                "and it pins the real sample instead of a cell centre: " + pinned);
        battle.finish("fixture fractional deployment done");
    }

    /**
     * R2: an interruption at a whole-number height adopts the real held point, never the cell it lands in.
     *
     * <p>A size 2 magma cube's apex is exactly 1.0 above its take-off, and its middle frame is that apex: the
     * body leaves the floor, reaches an integer height above a cell whose west face is its own X, and the hop is
     * then stopped by a safe new foot surface under it and a landing that no longer holds. The integer-height
     * branch of the interruption must adopt where the body really is.</p>
     */
    private static void integerApexInterruptionKeepsHeldPoint() {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 16.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        bigMagma = spawn(EntityType.MAGMA_CUBE, -5, 0, 0, 2);
        check(owner.hurt(bigMagma.damageSources().mobAttack(bigMagma), 1.0F),
                "apex interruption: fixture precondition: the owner can really be damaged");
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        owner.setHealth(owner.getMaxHealth());
        purgeForeignMonsters();
        check(BattleManager.start(owner, true), "apex interruption: the hop encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(bigMagma.getUUID());
        check(admitted != null && admitted.slime() != null, "apex interruption: the body was admitted with a node");
        check(admitted != null && admitted.cell().equals(cell(-5, 0, 0)),
                "apex interruption: it starts on the cell this case measures: "
                        + (admitted == null ? "none" : admitted.cell()));
        int moveCost = admitted == null ? 1 : Math.max(1, admitted.moveCost());
        check(battle.fixtureEnemyPool(bigMagma.getUUID(), 6 * moveCost),
                "apex interruption: the pool holds room for a route");
        apexLabel = "apex interruption";
        apexStartCell = admitted == null ? null : admitted.cell();
        apexId = bigMagma.getUUID();
        apexBody = bigMagma;
        apexStartY = bigMagma.getY();
        apexBroken = false;
        await(1, () -> watchApexInterrupt(0));
    }

    private static UUID apexId;
    private static String apexLabel = "";
    private static MagmaCube apexBody;
    private static GridPos apexStartCell;
    private static double apexStartY;
    private static boolean apexBroken;

    private static void watchApexInterrupt(int guard) {
        if (guard > 900) {
            ServerBattle.EnemyView stuck = battle.fixtureEnemy(apexId);
            check(false, apexLabel + ": the apex was really reached and interrupted: y=" + apexBody.getY()
                    + " busy=" + battle.isBusy() + " playerTurn=" + battle.isPlayerTurnNow()
                    + " actor=" + (apexBody == null ? "gone" : battle.isActorTurn(apexBody))
                    + " body=" + (apexBody == null ? "gone" : apexBody.position())
                    + " owner=" + owner.position() + " view=" + stuck);
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(apexId);
        if (view == null) {
            check(false, apexLabel + ": the body stayed on the roster");
            return;
        }
        // The apex of a size 2 magma cube's first edge is exactly one block above its take-off.
        if (view.hopping()) {
            if (apexBroken) {
                await(0, () -> watchApexInterrupt(guard + 1));
                return;
            }
            if (apexBody.getY() < apexStartY + 1.0D - 1.0E-6D) {
                await(0, () -> watchApexInterrupt(guard + 1));
                return;
            }
            // A safe new foot surface under the body where it really is - the whole real footprint, so the
            // adopted-in-place branch is legal - and the landing cell's floor taken away.
            Vec3 apexFeet = apexBody.position();
            net.minecraft.world.phys.AABB apexBox = BodyGeometry.bodyAt(apexBody, apexFeet);
            int supportY = (int) Math.floor(apexFeet.y) - 1;
            for (int bx = BodyGeometry.floor(apexBox.minX); bx <= BodyGeometry.floor(apexBox.maxX); bx++) {
                for (int bz = BodyGeometry.floor(apexBox.minZ); bz <= BodyGeometry.floor(apexBox.maxZ); bz++) {
                    level.setBlock(new BlockPos(bx, supportY, bz), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            block(apexStartCell.x() + 1 - origin.getX(), -1, apexStartCell.z() - origin.getZ(), Blocks.AIR);
            apexBroken = true;
            check(Math.abs(apexBody.getY() - Math.rint(apexBody.getY())) < 1.0E-6D,
                    apexLabel + ": the interruption really happens at a whole-number height: " + apexBody.getY());
            await(0, () -> watchApexInterrupt(guard + 1));
            return;
        }
        if (battle.isBusy()) {
            await(0, () -> watchApexInterrupt(guard + 1));
            return;
        }
        if (!apexBroken) {
            // The hop has not reached its apex yet: this is still the player's turn.
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(0, () -> watchApexInterrupt(guard + 1));
            return;
        }
        check(apexBroken, apexLabel + ": the world change really happened at the apex");
        check(view.cell().equals(cell(-4, 1, 0)),
                apexLabel + ": the held point belongs to the cell it really occupies: " + view.cell());
        check(view.slime() != null, apexLabel + ": the interrupted body holds a real node again");
        Vec3 held = view.slime() == null ? null : view.slime().feet();
        check(held != null && Math.abs(held.x - (origin.getX() - 4.0D)) < 1.0E-6D
                        && Math.abs(held.y - (origin.getY() + 1.0D)) < 1.0E-6D,
                apexLabel + ": it keeps the real sample, on the cell face rather than at the centre: " + held);
        check(held != null && Math.abs(held.x - (view.cell().x() + 0.5D)) > 1.0E-3D,
                apexLabel + ": the held point is really off the cell centre, which no rebuild would produce");
        check(apexBody.position().distanceToSqr(held) < 1.0E-9D,
                apexLabel + ": the body was not moved to adopt it: " + apexBody.position() + " vs " + held);
        check(apexBody.onGround(), apexLabel + ": a body adopted in place reports itself on the ground");
        check(!battle.terrainFallActive(), apexLabel + ": nothing fell, because the real feet were legal");
        check(!battle.isBusy(), apexLabel + ": the encounter hands the turn back once");
        check(view.paidActions() == 0, apexLabel + ": a hop never counts a paid action");
        battle.finish("fixture apex interruption done");
    }

    // ------------------------------------------------------------------ R3: the shared admission gate

    /**
     * R3: entry is judged as a whole body, and a refused candidate leaves nothing behind.
     *
     * <p>The battlefield's own edge is 24 cells from the owner. A size 4 body whose feet cell is still inside
     * that edge reaches a cell outside it; the corridor this stage builds makes that the only position such a
     * body could take here, so both the friendly entry point and the initial deployment have to refuse it. The
     * same position is proven standable by the plain geometry rule, which is what leaves "the body does not fit
     * the battlefield" as the only reason.</p>
     */
    private static void deploymentAndAdmissionGates() {
        purgeAll();
        for (int x = -8; x <= 30; x++) {
            for (int z = -6; z <= 6; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        // One body well inside the battlefield starts the encounter this stage measures the edge of.
        bigSlime = spawn(EntityType.SLIME, 3, 0, 0, 4);
        check(BattleManager.start(owner, true), "the gate fixture encounter starts");
        battle = BattleManager.current();
        int radius = battle.zone().radius();
        com.tactical.core.zone.BattleZone.Bounds bounds = battle.zone().bounds();
        int centreX = (bounds.minX() + bounds.maxX()) / 2;
        check(radius >= 6, "the battlefield really has an edge to measure: radius=" + radius);
        int edgeX = centreX + radius - origin.getX();
        // A corridor one body wide at the edge: everything this body could be nudged towards is solid, so the
        // only position left to it is the one where its own body crosses the boundary.
        // Tall enough that the deployment search cannot simply put the body on top of it: the walk reads eight
        // cells up and down from where the body really is.
        for (int x = edgeX - 4; x <= edgeX - 2; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 0; y < 12; y++) block(x, y, z, Blocks.STONE);
            }
        }
        // (1) A friendly candidate whose feet cell is inside the edge but whose real body crosses it.
        Slime straddler = spawn(EntityType.SLIME, edgeX, 0, 0, 4);
        GridPos straddlerCell = cell(edgeX, 0, 0);
        check(battle.zone().contains(straddlerCell),
                "the straddling body's own cell is inside the battlefield: " + straddlerCell);
        check(BattleDeployment.isStandable(level, straddler, straddler.position()),
                "and the plain standability rule really accepts that very position");
        check(!SlimeGround.bodyInsideZone(straddler, new SlimeGround.Node(straddlerCell, straddler.position()),
                        battle.zone()::contains),
                "while its whole real body is not inside the battlefield: "
                        + BodyGeometry.bodyAt(straddler, straddler.position()));
        int hostilesBefore = battle.enemyCount();
        int queueBefore = battle.turnQueueSize();
        int radiusBefore = battle.zone().radius();
        int poolBefore = battle.currentAp(owner);
        boolean straddlerNoAi = straddler.isNoAi();
        boolean straddlerPersistent = straddler.isPersistenceRequired();
        Vec3 straddlerHome = straddler.position();
        check(!battle.tryAddFriendly(straddler),
                "a friendly candidate whose body crosses the edge is refused");
        check(battle.enemyCount() == hostilesBefore && battle.turnQueueSize() == queueBefore,
                "and it adds no roster or queue entry");
        check(battle.zone().radius() == radiusBefore, "and it does not grow the battlefield");
        check(straddler.isNoAi() == straddlerNoAi && straddler.isPersistenceRequired() == straddlerPersistent,
                "and it borrows none of the body's own flags");
        check(straddler.position().distanceToSqr(straddlerHome) < 1.0E-9D, "and it moves nothing");
        check(BattleManager.findFor(straddler.getUUID()) == null, "and it claims nothing");
        check(battle.currentAp(owner) == poolBefore, "and the other side spends nothing");
        straddler.discard();
        battle.finish("fixture gate done");
        // The control body leaves the world too: the next case needs the straddler to be the only candidate.
        bigSlime.discard();
        // (2) The same body, same corridor, on the initial deployment: it is the only candidate, so the start
        // has to refuse instead of taking a unit that does not fit the battlefield it would be given.
        Slime solo = spawn(EntityType.SLIME, edgeX, 0, 0, 4, false, false);
        Vec3 soloHome = solo.position();
        check(!BattleManager.start(owner, true),
                "an initial deployment whose only candidate's body crosses the edge is refused");
        check(BattleManager.findFor(solo.getUUID()) == null, "and it claims nothing");
        check(!solo.isNoAi() && !solo.isPersistenceRequired(), "and it borrows none of its own flags");
        check(solo.position().distanceToSqr(soloHome) < 1.0E-9D, "and it moves nothing");
        check(!solo.isRemoved(), "and it is not removed either");
        check(BattleManager.active().isEmpty(), "and no encounter was registered");
        solo.discard();
        for (int x = edgeX - 4; x <= edgeX - 2; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 0; y < 12; y++) block(x, y, z, Blocks.AIR);
            }
        }
    }

    /**
     * R3: a reinforcement from outside is granted a battlefield that really covers its whole body.
     */
    private static void reinforcementGrowsOverTheBody() {
        purgeAll();
        for (int x = -8; x <= 30; x++) {
            for (int z = -6; z <= 6; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        bigSlime = spawn(EntityType.SLIME, 3, 0, 0, 4);
        check(BattleManager.start(owner, true), "the reinforcement fixture encounter starts");
        battle = BattleManager.current();
        int grownBefore = battle.zone().radius();
        int rosterBefore = battle.enemyCount();
        int queueBefore = battle.turnQueueSize();
        com.tactical.core.zone.BattleZone.Bounds bounds = battle.zone().bounds();
        int centreX = (bounds.minX() + bounds.maxX()) / 2;
        int outsiderX = centreX + grownBefore + 1 - origin.getX();
        Slime outsider = spawn(EntityType.SLIME, outsiderX, 0, 0, 4);
        GridPos outsiderCell = cell(outsiderX, 0, 0);
        check(!battle.zone().contains(outsiderCell),
                "the reinforcement candidate really starts outside the battlefield: " + outsiderCell);
        check(!SlimeGround.bodyInsideZone(outsider, new SlimeGround.Node(outsiderCell, outsider.position()),
                battle.zone()::contains), "and its whole body is outside it as well");
        check(battle.tryAbsorb(outsider), "a body outside is absorbed instead of being pushed away");
        check(battle.enemyCount() == rosterBefore + 1,
                "and it really joins the roster once: " + rosterBefore + " -> " + battle.enemyCount());
        check(battle.turnQueueSize() == queueBefore + 1, "and it really enters the turn queue once");
        check(battle.zone().radius() > grownBefore,
                "and the battlefield is really grown: " + grownBefore + " -> " + battle.zone().radius());
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(outsider.getUUID());
        check(admitted != null && admitted.slime() != null, "and the absorbed body was admitted on a real node");
        check(admitted != null && SlimeGround.bodyInsideZone(outsider, admitted.slime(),
                        battle.zone()::contains),
                "and the committed battlefield really covers its whole body, not just its cell");
        battle.finish("fixture absorb gate done");
        outsider.discard();
    }

    /**
     * R3: a candidate whose whole body reaches an unloaded column is refused before that column is read.
     *
     * <p>The loader is proven first: the feet column's own chunk is loaded while the column the rest of the
     * 2.08 body reaches is not. What can be measured inside this fixture is exactly that - the ordinary
     * standability read is answered from a column nobody loaded - plus the fact that nothing force-loads it:
     * a chunk is only ever read here, and the reading never turns a candidate into a taken unit.</p>
     */
    private static void chunkBoundaryCandidateIsProved() {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        // The server keeps every column around the fixture loaded, so the boundary is searched for instead of
        // assumed: the first column along +x whose chunk is really not loaded.
        int loadedEdge = origin.getX() + 8;
        int probeZ = origin.getZ();
        while (loadedEdge < origin.getX() + 400 && level.hasChunk((loadedEdge + 1) >> 4, probeZ >> 4)) loadedEdge++;
        int chunkEdge = loadedEdge + 1;
        int farChunkX = chunkEdge >> 4;
        check(!level.hasChunk(farChunkX, probeZ >> 4),
                "precondition: the column past the boundary is really not loaded (" + chunkEdge + ")");
        Slime crossing = spawn(EntityType.SLIME, 3, 0, 0, 4, false, false);
        // Feet inside the last loaded column, the rest of its 2.08 body over the boundary.
        Vec3 crossingFeet = new Vec3(chunkEdge - 0.4D, origin.getY(), origin.getZ() + 0.5D);
        crossing.setPos(crossingFeet);
        int feetCellX = net.minecraft.util.Mth.floor(crossingFeet.x);
        check(level.hasChunk(feetCellX >> 4, probeZ >> 4),
                "the body's own feet column really is loaded: " + feetCellX);
        net.minecraft.world.phys.AABB striding = BodyGeometry.bodyAt(crossing, crossingFeet);
        check(!BodyGeometry.loaded(level, striding),
                "while the columns its whole body reaches are not: " + striding);
        check(BodyGeometry.loaded(level, BodyGeometry.bodyAt(crossing,
                        new Vec3(crossingFeet.x - 2.0D, crossingFeet.y, crossingFeet.z))),
                "and the same body two cells in is wholly inside loaded columns");
        check(SlimeGround.groundNode(level, crossing, new GridPos(feetCellX, origin.getY(), probeZ)).isEmpty(),
                "the encounter's own node rule refuses it: nothing calls a column nobody loaded a free stand");
        check(!level.hasChunk(farChunkX, probeZ >> 4),
                "and answering that question force-loaded nothing");
        crossing.discard();
    }

    /**
     * R5: the two borrowed control flags are given back exactly, on a normal exit and on a failed admission.
     *
     * <p>The fixture spawns bodies that really started with {@code false}/{@code false} as well as
     * {@code true}/{@code true}: a fixture that only ever starts {@code true} can never show that a false
     * original comes back, and that is exactly the state vanilla mobs normally have.</p>
     */
    private static void originalFlagsReturnOnExit() {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 3; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 3.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        // Case A: a body that started with its own AI and no persistence.
        Slime free = spawn(EntityType.SLIME, 2, 0, 0, 2, false, false);
        check(!free.isNoAi() && !free.isPersistenceRequired(),
                "precondition: this body really started with its own AI and no persistence");
        Vec3 freeHome = free.position();
        int freeSize = free.getSize();
        float freeHealth = free.getHealth();
        check(BattleManager.start(owner, true), "the flag encounter takes it");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(free.getUUID());
        check(admitted != null && admitted.slime() != null, "it is really on the roster");
        check(free.isNoAi() && free.isPersistenceRequired(), "admission borrows both flags");
        check(free.getSize() == freeSize && Math.abs(free.getHealth() - freeHealth) < 1.0E-4F,
                "and changes neither its size nor its health");
        check(((TacticalSlimeAccess) free).tactical$poseHandler() != null, "and installs the pose seam");
        battle.finish("fixture flag return");
        check(!free.isNoAi(), "a normal exit gives the original AI flag back");
        check(!free.isPersistenceRequired(), "and the original persistence flag");
        check(((TacticalSlimeAccess) free).tactical$poseHandler() == null, "and the pose seam");
        check(BattleManager.findFor(free.getUUID()) == null, "and the ownership claim");
        check(!free.isRemoved() && free.isAlive(), "the body itself is untouched");
        check(free.position().distanceToSqr(freeHome) < 1.0E-6D, "and a normal exit never moves it back");
        check(free.getSize() == freeSize && Math.abs(free.getHealth() - freeHealth) < 1.0E-4F,
                "and changes neither its size nor its health on the way out");
        // Case B: a body that already had both flags keeps them - a true original is not turned into false.
        free.discard();
        Slime kept = spawn(EntityType.SLIME, 2, 0, 0, 2, true, true);
        check(kept.isNoAi() && kept.isPersistenceRequired(),
                "precondition: this body really started with both flags already set");
        check(BattleManager.start(owner, true), "the second flag encounter takes it");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(kept.getUUID()) != null, "it is really on the roster");
        battle.finish("fixture true flag return");
        check(kept.isNoAi(), "an original true AI flag stays true");
        check(kept.isPersistenceRequired(), "and an original true persistence flag stays true");
        check(((TacticalSlimeAccess) kept).tactical$poseHandler() == null, "and the pose seam is given back");
        check(BattleManager.findFor(kept.getUUID()) == null, "and the ownership claim");
        // Case C: an admission that fails after the unit was mutated gives both flags back as well.
        kept.discard();
        Slime rolled = spawn(EntityType.SLIME, 2, 0, 0, 2, false, false);
        Vec3 rolledHome = rolled.position();
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        check(!BattleManager.start(owner, true), "an admission that fails really refuses the start");
        check(ServerBattle.pendingFaults() == 0, "the injected fault was really consumed");
        check(!rolled.isNoAi() && !rolled.isPersistenceRequired(),
                "the rolled-back unit gets both of its own flags back");
        check(((TacticalSlimeAccess) rolled).tactical$poseHandler() == null,
                "and no pose seam is left installed");
        check(BattleManager.findFor(rolled.getUUID()) == null, "and no ownership claim is kept");
        check(rolled.position().distanceToSqr(rolledHome) < 1.0E-6D, "and it is put back where it stood");
        check(!rolled.isRemoved() && rolled.isAlive(), "and it is left alive and in the world");
        check(BattleManager.active().isEmpty(), "and no encounter was left registered");
        rolled.discard();
        kept.discard();
    }

    // ------------------------------------------------------------------ R2: the idle hold itself

    private static UUID idleId;
    private static Slime idleBody;
    private static String idleLabel = "";
    private static GridPos idleCell;
    private static Vec3 idleHeld;
    private static boolean idleDug;
    private static boolean idleFalling;
    private static double idleMaxDrift;
    private static boolean idlePinnedHeld;

    /**
     * R2: an idle body that loses one support column under its real footprint is not left standing on it.
     *
     * <p>Its height never changes: the cell under its own centre keeps a solid floor and only the outer column
     * of its 2.08 footprint is taken away. A height-only hold check keeps such a body pinned over a hole
     * forever, so the hold has to be judged as the whole real foot surface. The descent that follows cannot
     * settle here - the missing column is under the very cell it would land on - so this case also reaches the
     * existing bounded release, which is the other half of the same contract: a unit this encounter can no
     * longer hold gets both borrowed flags, its pose seam and its ownership claim back.</p>
     */
    private static void idleBodyLosingOuterSupportIsReleased() {
        purgeAll();
        for (int x = -6; x <= 8; x++) {
            for (int z = -4; z <= 4; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 5; y++) block(x, y, z, Blocks.AIR);
            }
        }
        owner.setPos(origin.getX() + 12.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        // The two borrowed flags start false, so this case can prove a release really gives them back.
        idleBody = spawn(EntityType.SLIME, 0, 0, 0, 4, false, false);
        check(!idleBody.isNoAi() && !idleBody.isPersistenceRequired(),
                "idle hold: precondition: the body starts with its own AI and no persistence");
        check(BattleManager.start(owner, true), "idle hold: the encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(idleBody.getUUID());
        check(admitted != null && admitted.slime() != null, "idle hold: the body holds a real node");
        idleId = idleBody.getUUID();
        idleLabel = "idle hold";
        idleCell = admitted == null ? null : admitted.cell();
        idleHeld = admitted == null ? null : admitted.slime().feet();
        idleDug = false;
        idleFalling = false;
        idleMaxDrift = 0.0D;
        idlePinnedHeld = true;
        check(battle.fixtureEnemyPool(idleId, 0), "idle hold: the body is given no pool, so it cannot hop away");
        check(SlimeGround.holds(level, idleBody, admitted == null ? null : admitted.slime()),
                "idle hold: precondition: its whole real footprint is supported right now");
        await(1, () -> watchIdleHold(0));
    }

    private static void watchIdleHold(int guard) {
        if (guard > 600) {
            ServerBattle.EnemyView stuck = battle.fixtureEnemy(idleId);
            check(false, idleLabel + ": the unsupported hold really resolved: dug=" + idleDug + " falling="
                    + idleFalling + " fall=" + battle.terrainFallActive() + " busy=" + battle.isBusy()
                    + " view=" + stuck + " body=" + (idleBody == null ? "gone" : idleBody.position()));
            return;
        }
        if (!idleDug) {
            // Same height, same X/Z: only the outer column of the real footprint loses its support.
            check(SlimeGround.holds(level, idleBody, new SlimeGround.Node(idleCell, idleHeld)),
                    idleLabel + ": precondition: the body really holds that node before the column goes");
            block(idleCell.x() + 1 - origin.getX(), idleCell.y() - 1 - origin.getY(),
                    idleCell.z() - origin.getZ(), Blocks.AIR);
            check(!SlimeGround.holds(level, idleBody, new SlimeGround.Node(idleCell, idleHeld)),
                    idleLabel + ": one support column under the real footprint is really gone, and the hold "
                            + "is no longer true");
            check(SlimeGround.heldNode(level, idleBody).isEmpty(),
                    idleLabel + ": and the real feet are not a legal stand either");
            check(Math.abs(idleBody.getY() - idleHeld.y) < 1.0E-6D,
                    idleLabel + ": the body's own height never changed, so a height-only check would keep it: "
                            + idleBody.getY());
            idleDug = true;
            await(1, () -> watchIdleHold(guard + 1));
            return;
        }
        ServerBattle.EnemyView view = battle.fixtureEnemy(idleId);
        if (!idleFalling) {
            if (view != null) {
                // The encounter really took the body off that node instead of pinning it there.
                check(battle.terrainFallActive(),
                        idleLabel + ": the descent owns the body as soon as the footprint loses support");
                check(battle.isBusy(), idleLabel + ": the encounter is busy while it descends");
                idleFalling = true;
            }
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchIdleHold(guard + 1));
            return;
        }
        if (view != null) {
            // Still held by this encounter: it keeps the real sample and never slides sideways. The whole
            // window is folded into two readings that are asserted once, so the check count stays fixed.
            idleMaxDrift = Math.max(idleMaxDrift, Math.hypot(idleBody.getX() - idleHeld.x,
                    idleBody.getZ() - idleHeld.z));
            SlimePoseHandler handler = ((TacticalSlimeAccess) idleBody).tactical$poseHandler();
            Vec3 pinned = handler == null || !handler.tactical$controls(idleBody) ? null
                    : handler.tactical$pinFeet(idleBody);
            idlePinnedHeld &= pinned != null && pinned.distanceToSqr(idleHeld) < 1.0E-6D;
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            await(1, () -> watchIdleHold(guard + 1));
            return;
        }
        // The bounded release really ran: this encounter gave the body back.
        check(idleMaxDrift < 1.0E-6D,
                idleLabel + ": the body stayed exactly where it stood while the descent could not settle, "
                        + "with no lateral move: drift=" + idleMaxDrift);
        check(idlePinnedHeld, idleLabel + ": and the seam kept pinning that real sample for the whole window");
        check(BattleManager.findFor(idleId) == null, idleLabel + ": the released body claims nothing");
        check(((TacticalSlimeAccess) idleBody).tactical$poseHandler() == null,
                idleLabel + ": and keeps no pose seam of this encounter");
        check(!idleBody.isNoAi(), idleLabel + ": the original AI flag is given back");
        check(!idleBody.isPersistenceRequired(), idleLabel + ": and the borrowed persistence flag as well");
        check(!battle.terrainFallActive(), idleLabel + ": the fall really ended");
        check(!battle.isBusy(), idleLabel + ": the encounter is not left busy");
        check(!idleBody.isRemoved() && idleBody.isAlive(), idleLabel + ": the body itself is still in the world");
        battle.finish("fixture idle hold done");
        idleBody.discard();
    }

    // ------------------------------------------------------------------ R5: the dedicated-server difficulty table

    /** What one native contact produced, kept apart so no two of these numbers are ever asserted equal. */
    private record DamageSample(int size, double attribute, float incoming, float afterReductions,
                                float applied, float healthLost) { }

    /**
     * R5: the same native contact at Easy, Normal and Hard, with every number kept separate.
     *
     * <p>The plan leaves Easy/Normal/Hard damage accounting to the dedicated server, so this table records the
     * raw attribute, incoming-event original amount, applied event value and health actually lost by the player
     * - per difficulty, for a size 1 magma cube and for the slime the earlier stages already used. The incoming
     * event follows Player difficulty scaling; it does not capture the outer Slime-to-Player hurt argument.
     * The attack attribute stays the size, and the incoming-event amount matches the difficulty scaling of
     * vanilla's own attack value ({@code Player#hurt} with {@code IScalingFunction.DEFAULT}: easy narrows it to
     * {@code amount/2 + 1}, hard raises it by half, normal keeps it), the applied damage is the health really
     * lost, and the three difficulties are really ordered rather than flattened into one number.</p>
     *
     * <p>Every sample is one immediate call on the same native bridge, with the target's health and
     * invulnerability window restored between samples, so no cross-tick regeneration can blur the comparison.
     * The difficulty and the target state are restored in a {@code finally}.</p>
     */
    private static void nativeDamageAcrossDifficulties() {
        purgeAll();
        for (int x = -4; x <= 6; x++) {
            for (int z = -3; z <= 6; z++) {
                block(x, -1, z, Blocks.STONE);
                for (int y = 0; y < 4; y++) block(x, y, z, Blocks.AIR);
            }
        }
        bigSlime = spawn(EntityType.SLIME, 0, 0, 4, 4);
        tinyMagma = spawn(EntityType.MAGMA_CUBE, 0, 0, 0, 1);
        Vec3 magmaFeet = new Vec3(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        Vec3 magmaTarget = new Vec3(origin.getX() + 1.4D, origin.getY(), origin.getZ() + 0.5D);
        Vec3 slimeFeet = new Vec3(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 4.5D);
        Vec3 slimeTarget = new Vec3(origin.getX() + 1.9D, origin.getY(), origin.getZ() + 4.5D);
        net.minecraft.server.MinecraftServer server = level.getServer();
        net.minecraft.world.Difficulty original = level.getDifficulty();
        float originalHealth = owner.getHealth();
        int originalInvulnerable = owner.invulnerableTime;
        try {
            java.util.Map<net.minecraft.world.Difficulty, DamageSample[]> table = new LinkedHashMap<>();
            for (net.minecraft.world.Difficulty difficulty : new net.minecraft.world.Difficulty[]{
                    net.minecraft.world.Difficulty.EASY, net.minecraft.world.Difficulty.NORMAL,
                    net.minecraft.world.Difficulty.HARD}) {
                server.setDifficulty(difficulty, true);
                check(level.getDifficulty() == difficulty, "the dedicated server really runs on " + difficulty);
                DamageSample magma = sampleNativeDamage(tinyMagma, magmaFeet, magmaTarget);
                check(magma.attribute() == 1.0D,
                        difficulty + ": the size 1 magma cube's own attack attribute stays the size: "
                                + magma.attribute());
                check(magma.size() == 1, difficulty + ": and the body really still is size 1");
                DamageSample slime = sampleNativeDamage(bigSlime, slimeFeet, slimeTarget);
                check(slime.attribute() == 4.0D,
                        difficulty + ": the grown slime's own attack attribute stays the size: " + slime.attribute());
                table.put(difficulty, new DamageSample[]{magma, slime});
            }
            for (java.util.Map.Entry<net.minecraft.world.Difficulty, DamageSample[]> entry : table.entrySet()) {
                net.minecraft.world.Difficulty difficulty = entry.getKey();
                for (DamageSample sample : entry.getValue()) {
                    boolean magma = sample.size() == 1 && sample.attribute() == 1.0D;
                    String name = magma ? "size 1 magma" : "size 4 slime";
                    // Vanilla's own attack value for that body: a magma cube's override adds two, a slime's is
                    // its attribute.
                    float nativeValue = magma ? (float) sample.attribute() + 2.0F : (float) sample.attribute();
                    check(Math.abs(sample.incoming() - vanillaPlayerScaling(difficulty, nativeValue)) < 1.0E-4F,
                            name + " on " + difficulty + ": the amount reaching the native hurt call is vanilla's "
                                    + "own player-difficulty scaling of " + nativeValue + ": "
                                    + sample.incoming() + " vs " + vanillaPlayerScaling(difficulty, nativeValue));
                    check(sample.afterReductions() <= sample.incoming() + 1.0E-6F,
                            name + " on " + difficulty + ": no reduction invents damage: "
                                    + sample.afterReductions() + " <= " + sample.incoming());
                    check(Math.abs(sample.applied() - sample.healthLost()) < 1.0E-4F,
                            name + " on " + difficulty + ": the applied damage is the health the player really "
                                    + "lost: " + sample.applied() + " vs " + sample.healthLost());
                    Tactical.LOGGER.info("[Tactical] SLIME damage table difficulty={} body={} size={} "
                                    + "attribute={} incoming={} afterReductions={} applied={} healthLost={}",
                            difficulty, name, sample.size(), sample.attribute(), sample.incoming(),
                            sample.afterReductions(), sample.applied(), sample.healthLost());
                }
            }
            // The three difficulties are really different sentences about the same hit, not one flattened number.
            DamageSample[] easy = table.get(net.minecraft.world.Difficulty.EASY);
            DamageSample[] normal = table.get(net.minecraft.world.Difficulty.NORMAL);
            DamageSample[] hard = table.get(net.minecraft.world.Difficulty.HARD);
            for (int index = 0; index < 2; index++) {
                String name = index == 0 ? "size 1 magma" : "size 4 slime";
                check(easy[index].incoming() < normal[index].incoming()
                                && normal[index].incoming() < hard[index].incoming(),
                        name + ": easy < normal < hard really holds for the amount that reaches hurt: "
                                + easy[index].incoming() + " / " + normal[index].incoming() + " / "
                                + hard[index].incoming());
            }
        } finally {
            server.setDifficulty(original, true);
            owner.setHealth(originalHealth);
            owner.invulnerableTime = originalInvulnerable;
            owner.hurtTime = 0;
        }
        check(level.getDifficulty() == original, "the original difficulty is restored");
        check(Math.abs(owner.getHealth() - originalHealth) < 1.0E-4F, "and the target's own health with it");
    }

    /** One immediate native contact, with the target put back to a clean state first. */
    private static DamageSample sampleNativeDamage(Slime body, Vec3 feet, Vec3 target) {
        body.setPos(feet);
        body.setNoAi(true);
        owner.setPos(target);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        incomingAmount = -1.0F;
        incomingAfterReductions = -1.0F;
        contactDamage = -1.0F;
        float before = owner.getHealth();
        boolean hurt = SlimeContact.strike(body, owner);
        check(hurt, "the native contact really landed for this sample");
        check(incomingAmount >= 0.0F && contactDamage >= 0.0F,
                "both the arriving amount and the applied damage were really observed");
        float lost = before - owner.getHealth();
        return new DamageSample(body.getSize(), body.getAttributeValue(Attributes.ATTACK_DAMAGE),
                incomingAmount, incomingAfterReductions, contactDamage, lost);
    }

    /**
     * Vanilla's player-difficulty scaling of one incoming amount, read from its own definition.
     *
     * <p>{@code Player#hurt} scales by {@code DamageScaling} through {@code IScalingFunction.DEFAULT}:
     * {@code PEACEFUL -> 0}, {@code EASY -> min(amount / 2 + 1, amount)}, {@code NORMAL -> amount},
     * {@code HARD -> amount * 1.5}. This is the rule the encounter keeps, not a value it writes.</p>
     */
    private static float vanillaPlayerScaling(net.minecraft.world.Difficulty difficulty, float amount) {
        return switch (difficulty) {
            case PEACEFUL -> 0.0F;
            case EASY -> Math.min(amount / 2.0F + 1.0F, amount);
            case NORMAL -> amount;
            case HARD -> amount * 1.5F;
        };
    }

    // ------------------------------------------------------------------ B: the native split lifecycle

    /** The parent of the split case currently running, and where it died. */
    private static Slime splitParent;
    private static Vec3 splitDeathPos;
    private static int splitBornMark;
    private static int splitAdmittedMark;
    private static int splitPhase;
    private static int splitPendingTick;
    private static double splitOwnerHealth;
    private static int splitOwnerApMark;
    private static long splitEpochMark;
    private static int splitPaidMark;
    private static int splitOrbsMark;
    private static int splitMaxRoster;
    private static final List<UUID> splitSeen = new ArrayList<>();
    /** Fixture levers: refuse or trim exactly the next real split of one parent. */
    private static UUID splitCancelParent;
    private static UUID splitTrimParent;
    private static int splitCancelled;
    private static int splitTrimmed;
    /** Fixture lever: refuse this many joins that a real split is producing right now. */
    private static int refuseSplitJoins;
    private static int refusedSplitJoins;

    /**
     * S5/S6: one real 4 -> 2 -> 1 native chain, its timing, and what a newborn unit may not inherit.
     *
     * <p>Everything here is vanilla's: the body dies, vanilla waits its own twenty death ticks, vanilla builds
     * two to four children of half the size, and the world registers them. The encounter's own part is the
     * barrier (no victory, no new orders, no turn while a body is still going to split) and the hand-over
     * (one birth, one admission, one pool).</p>
     */
    private static void nativeSplitChain() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(!splitParent.isNoAi() && !splitParent.isPersistenceRequired(),
                "precondition: the size 4 parent started with its own AI and no persistence");
        check(BattleManager.start(owner, true), "the split encounter starts");
        battle = BattleManager.current();
        ServerBattle.EnemyView admitted = battle.fixtureEnemy(splitParent.getUUID());
        check(admitted != null && admitted.slime() != null, "the size 4 parent is admitted on a real node");
        check(battle.hostileCount() == 1, "and is the whole roster: " + battle.hostileCount());
        check(battle.fixtureSplitPending() == 0, "nothing is owed while it is alive");
        check(battle.fixtureEnemyPool(splitParent.getUUID(), 37),
                "the parent's own pool is set to a value no newborn may inherit");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        splitPendingTick = -1;
        splitPhase = 0;
        splitSeen.clear();
        splitMaxRoster = 1;
        splitOwnerHealth = owner.getHealth();
        splitOwnerApMark = battle.currentAp(owner);
        splitEpochMark = battle.turnEpoch();
        splitPaidMark = battle.fixtureMemberPaid(owner);
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F),
                "the parent really takes a lethal hit from outside its own actions");
        check(splitParent.isDeadOrDying(), "and really is dying");
        splitOrbsMark = countOrbs();
        await(0, () -> watchSplitChain(0));
    }

    private static void watchSplitChain(int guard) {
        if (guard > 400) {
            check(false, "the native split chain never completed: phase=" + splitPhase
                    + " pending=" + battle.fixtureSplitPending() + " born=" + battle.fixtureSplitBorn()
                    + " roster=" + battle.hostileCount() + " finished=" + battle.finished());
            return;
        }
        if (splitPhase == 0) {
            List<Slime> children = slimesNear(splitDeathPos, 4.0D, splitParent);
            if (children.isEmpty()) {
                check(!battle.finished(), "the encounter declares no victory while a native split is owed");
                check(battle.splitPending(), "and really is owed it from the tick the parent died");
                check(battle.isBusy(), "and reports itself busy, so no further order can be taken");
                check(battle.turnEpoch() == splitEpochMark,
                        "and no turn is handed over while the split is owed: epoch " + battle.turnEpoch()
                                + " vs " + splitEpochMark);
                splitMaxRoster = Math.max(splitMaxRoster, battle.hostileCount());
                if (splitPendingTick < 0) splitPendingTick = worldTicks;
                await(0, () -> watchSplitChain(guard + 1));
                return;
            }
            check(children.size() >= 2 && children.size() <= 4,
                    "vanilla's own 2..4 children were really added: " + children.size());
            for (Slime child : children) {
                check(child.getType() == EntityType.SLIME, "every child keeps the parent's exact type");
                check(child.getSize() == 2, "every child really carries half the parent's size: " + child.getSize());
                check(level.getEntity(child.getUUID()) == child, "and is really registered in this world");
                check(!splitSeen.contains(child.getUUID()), "and is a distinct body, never a duplicate");
                splitSeen.add(child.getUUID());
                // The one property that must hold from the first moment on: a body this encounter has not
                // taken yet is never running its own AI and can never touch a member this tick.
                check(child.isNoAi() || battle.fixtureEnemy(child.getUUID()) != null,
                        "a newborn body is separated or already taken, never a free AI mob");
            }
            splitPhase = 1;
            await(0, () -> watchSplitChain(guard + 1));
            return;
        }
        if (splitPhase == 1) {
            List<Slime> children = slimesNear(splitDeathPos, 4.0D, splitParent);
            check(Math.abs(owner.getHealth() - splitOwnerHealth) < 1.0E-4D,
                    "no member took a single hit from a newborn body: " + owner.getHealth() + " vs " + splitOwnerHealth);
            long taken = children.stream().filter(child -> battle.fixtureEnemy(child.getUUID()) != null).count();
            if (taken < children.size()) {
                await(0, () -> watchSplitChain(guard + 1));
                return;
            }
            splitMaxRoster = Math.max(splitMaxRoster, battle.hostileCount());
            check(battle.fixtureSplitBorn() - splitBornMark == children.size(),
                    "exactly one birth record per child: " + (battle.fixtureSplitBorn() - splitBornMark)
                            + " for " + children.size());
            check(battle.fixtureSplitAdmitted() - splitAdmittedMark == children.size(),
                    "and each child was admitted exactly once");
            check(battle.fixtureSplitPending() == 0, "the wait is closed once every child is taken");
            check(battle.turnEpoch() - splitEpochMark <= 1,
                    "the barrier defers the hand-over and advances it at most once: epoch "
                            + battle.turnEpoch() + " from " + splitEpochMark);
            int window = worldTicks - splitPendingTick;
            check(window >= 10 && window <= 30,
                    "the children came from vanilla's own death timer, not from this encounter: " + window
                            + " ticks after the death scan saw it");
            for (Slime child : children) {
                ServerBattle.EnemyView view = battle.fixtureEnemy(child.getUUID());
                check(view != null && view.ap() == 100,
                        "a newborn gets its own initial pool, never the parent's 37: " + (view == null ? -1 : view.ap()));
                check(view != null && view.paidActions() == 0, "and starts with no paid action");
                check(battle.fixtureSide(child.getUUID()) == BattleSide.HOSTILE,
                        "and inherits the parent's own hostile side");
                check(Boolean.FALSE.equals(battle.fixtureOriginalNoAi(child.getUUID())),
                        "admission captured the child's real AI flag, not the temporary separation");
                check(Boolean.FALSE.equals(battle.fixtureOriginalPersistence(child.getUUID())),
                        "and its real persistence flag, not the encounter's borrowed one");
                check(child.getSize() == 2 && Math.abs(child.getMaxHealth() - 4.0F) < 1.0E-4F,
                        "its native body and health are its own: " + child.getMaxHealth());
                check(Math.abs(child.getHealth() - child.getMaxHealth()) < 1.0E-4F,
                        "and it starts whole, never as a copy of the parent's health");
                check(child.getMainHandItem().isEmpty() && child.getOffhandItem().isEmpty(),
                        "and carries none of the parent's equipment");
                check(((TacticalSlimeAccess) child).tactical$poseHandler() != null,
                        "and is controlled through the family's own seam");
            }
            // One birth is one admission: asking again for the same object must change nothing at all.
            Slime again = children.get(0);
            ServerBattle.EnemyView before = battle.fixtureEnemy(again.getUUID());
            check(!battle.tryAdmitSplitChild(again, BattleSide.HOSTILE),
                    "the same child is refused a second time instead of being admitted twice");
            check(!battle.retrySplitChild(again, BattleSide.HOSTILE),
                    "and a retry of an already taken body is refused as well");
            ServerBattle.EnemyView after = battle.fixtureEnemy(again.getUUID());
            check(before != null && after != null && after.ap() == before.ap()
                            && battle.hostileCount() == children.size(),
                    "with no second slot, pool or queue entry: ap " + (after == null ? -1 : after.ap())
                            + " hostiles " + battle.hostileCount());
            check(!splitParent.isNoAi() && !splitParent.isPersistenceRequired(),
                    "the dead parent's borrowed flags were given back before vanilla copied them");
            check(countOrbs() == splitOrbsMark, "a birth produced no extra experience: "
                    + countOrbs() + " vs " + splitOrbsMark);
            splitPhase = 2;
            await(0, () -> watchSplitChain(guard + 1));
            return;
        }
        if (splitPhase == 2) {
            // A member really kills one size 2 child on its own turn; the turn, the epoch and the resources stay.
            if (!battle.isPlayerTurnNow(owner)) {
                await(0, () -> watchSplitChain(guard + 1));
                return;
            }
            List<Slime> midChildren = slimesNear(splitDeathPos, 6.0D, splitParent).stream()
                    .filter(child -> child.getSize() == 2 && battle.fixtureEnemy(child.getUUID()) != null).toList();
            if (midChildren.isEmpty()) {
                check(false, "no size 2 child of the chain was left to kill");
                return;
            }
            Slime victim = midChildren.get(0);
            splitOwnerApMark = battle.currentAp(owner);
            splitEpochMark = battle.turnEpoch();
            splitPaidMark = battle.fixtureMemberPaid(owner);
            splitDeathPos = victim.position();
            splitBornMark = battle.fixtureSplitBorn();
            splitAdmittedMark = battle.fixtureSplitAdmitted();
            check(victim.hurt(level.damageSources().playerAttack(owner), 400.0F),
                    "the member's own hit really kills a size 2 child");
            check(victim.isDeadOrDying(), "which really is dying");
            check(battle.turnEpoch() == splitEpochMark, "the member's turn epoch is unchanged by the kill");
            check(battle.currentAp(owner) == splitOwnerApMark, "its remaining pool is unchanged: "
                    + battle.currentAp(owner) + " vs " + splitOwnerApMark);
            check(battle.fixtureMemberPaid(owner) == splitPaidMark, "and its paid-action count is unchanged");
            check(battle.splitPending(), "the second generation is owed at once, on the same tick");
            check(battle.isBusy(), "and blocks further orders while it is owed");
            splitPhase = 3;
            await(0, () -> watchSplitChain(guard + 1));
            return;
        }
        if (splitPhase == 3) {
            List<Slime> tiny = slimesNear(splitDeathPos, 4.0D, null).stream()
                    .filter(child -> child.getSize() == 1).toList();
            long taken = tiny.stream().filter(child -> battle.fixtureEnemy(child.getUUID()) != null).count();
            if (battle.splitPending() || tiny.isEmpty() || taken < tiny.size()) {
                check(!battle.finished(), "the encounter stays open while the second generation is owed");
                check(Math.abs(owner.getHealth() - splitOwnerHealth) < 1.0E-4D,
                        "no member was hit while the second generation was pending");
                await(0, () -> watchSplitChain(guard + 1));
                return;
            }
            check(tiny.size() >= 1, "the second generation really is half the size: 1");
            for (Slime child : tiny) {
                check(child.getSize() == 1, "a size 1 child keeps size 1");
                check(battle.fixtureSide(child.getUUID()) == BattleSide.HOSTILE
                        || battle.fixtureEnemy(child.getUUID()) == null,
                        "and is either hostile or still owed a place");
                // A child this encounter installed on a real legal ground node stands on the ground at that very
                // moment, so its native contact flag has to say so: a vanilla newborn starts with the flag false and
                // an adoption that never wrote it would leave the squish/landing reading waiting for the first hop.
                if (battle.fixtureEnemy(child.getUUID()) != null) {
                    check(child.onGround(), "an installed child reports the real ground it stands on");
                }
            }
            check(battle.turnEpoch() == splitEpochMark,
                    "the member keeps its own turn across a whole split generation");
            check(battle.fixtureMemberPaid(owner) == splitPaidMark, "and its paid count");
            splitPhase = 4;
            await(0, () -> watchSplitChain(guard + 1));
            return;
        }
        // Phase 4: clear the rest of the chain one body per tick. A size 1 death owes nothing; the encounter
        // may only end once nothing is left and nothing is owed.
        if (battle.finished()) {
            check(battle.hostileCount() == 0, "the encounter ended on an empty roster");
            check(!battle.splitPending(), "and with nothing owed any more");
            check(battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                    "and ended as a real victory, not as an anomaly: " + battle.fixtureEndCode());
            check(battle.fixtureSplitBorn() > splitMaxRoster,
                    "the lineage's cumulative births are not the live roster: born="
                            + battle.fixtureSplitBorn() + " peakLive=" + splitMaxRoster);
            await(1, () -> finishSplitChain());
            return;
        }
        List<Slime> alive = slimesNear(new Vec3(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D), 40.0D, null)
                .stream().filter(child -> battle.fixtureEnemy(child.getUUID()) != null)
                .sorted(java.util.Comparator.comparingInt(Slime::getSize).reversed()).toList();
        if (alive.isEmpty()) {
            await(0, () -> watchSplitChain(guard + 1));
            return;
        }
        Slime next = alive.get(0);
        int bornBefore = battle.fixtureSplitBorn();
        int size = next.getSize();
        // At least one real server tick has passed since this body was installed on its node, so the flag is not
        // just the value written at adoption: it has to still describe the ground the body really stands on.
        check(next.onGround(), "a remaining size " + size + " body still reports the ground a tick later");
        check(next.hurt(level.damageSources().playerAttack(owner), 400.0F),
                "a remaining size " + size + " body really dies");
        if (size <= 1) {
            check(battle.fixtureSplitBorn() == bornBefore, "a size 1 body owes this encounter no split");
            // No owed split means this body simply leaves the roster: that release path must give back both
            // borrowed flags, not only the no-AI one.
            departedBody = next;
            int resumeGuard = guard;
            splitMaxRoster = Math.max(splitMaxRoster, battle.hostileCount());
            await(0, () -> watchDepartedBodyFlags(resumeGuard));
            return;
        }
        check(battle.splitPending(), "a size " + size + " body owes its split at once");
        splitMaxRoster = Math.max(splitMaxRoster, battle.hostileCount());
        await(0, () -> watchSplitChain(guard + 1));
    }

    private static Slime departedBody;

    /**
     * A body that left the roster without owing a split keeps neither borrowed control flag.
     *
     * <p>The same release path serves deaths, removals and a dimension change, so the persistence flag this
     * encounter set must come back with the no-AI flag - otherwise the world keeps a mob that can never despawn
     * again.</p>
     */
    private static void watchDepartedBodyFlags(int guard) {
        Slime body = departedBody;
        departedBody = null;
        check(body != null && !body.isNoAi(),
                "a departed body gets the member's borrowed no-AI back");
        check(body != null && !body.isPersistenceRequired(),
                "and the borrowed persistence with it, on the same release path");
        await(0, () -> watchSplitChain(guard + 1));
    }

    /** The chain is over; the next stage starts from a clean arena. */
    private static void finishSplitChain() {
        check(battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "the chain ends as a real victory once no body and no wait is left");
        purgeAll();
    }

    /**
     * S5: a listener cancels the split event, and one listener modifies the child list.
     *
     * <p>Both are the event's own contract: a cancelled event produces nothing at all, and a list is believed
     * exactly as it stands. Neither may leave a roster slot, an initial pool or a ghost record behind.</p>
     */
    private static void splitEventCancelled() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the cancelled-split encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitCancelled = 0;
        splitCancelParent = splitParent.getUUID();
        splitPhase = 0;
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent really dies");
        await(0, () -> watchCancelledSplit(0));
    }

    private static void watchCancelledSplit(int guard) {
        if (guard > 120) {
            check(false, "a cancelled split never settled: pending=" + battle.fixtureSplitPending()
                    + " finished=" + battle.finished() + " born=" + battle.fixtureSplitBorn());
            return;
        }
        if (battle.splitPending()) {
            check(!battle.finished(), "a cancelled split still keeps the encounter from declaring victory");
            await(0, () -> watchCancelledSplit(guard + 1));
            return;
        }
        splitCancelParent = null;
        check(splitCancelled == 1, "the event really fired once and was really cancelled: " + splitCancelled);
        check(slimesNear(splitDeathPos, 6.0D, splitParent).isEmpty(),
                "a cancelled event produced no child at all");
        check(battle.fixtureSplitBorn() == splitBornMark, "and left no birth record behind");
        check(battle.finished() && battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "so the encounter really ends as a victory: " + battle.fixtureEndCode());
        check(battle.hostileCount() == 0, "with no ghost roster slot");
        // Second case: a listener keeps exactly one child, and only that one may join.
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the trimmed-split encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        splitTrimmed = 0;
        splitTrimParent = splitParent.getUUID();
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the trimmed case's parent dies");
        await(0, () -> watchTrimmedSplit(0));
    }

    private static void watchTrimmedSplit(int guard) {
        if (guard > 120) {
            check(false, "a trimmed split never settled: pending=" + battle.fixtureSplitPending()
                    + " born=" + (battle.fixtureSplitBorn() - splitBornMark));
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchTrimmedSplit(guard + 1));
            return;
        }
        splitTrimParent = null;
        check(splitTrimmed >= 1, "the listener really modified the child list: " + splitTrimmed);
        List<Slime> children = slimesNear(splitDeathPos, 6.0D, splitParent);
        check(children.size() == 1, "exactly the one child the list kept is in the world: " + children.size());
        check(battle.fixtureSplitBorn() - splitBornMark == 1, "and exactly one birth was recorded");
        check(battle.fixtureSplitAdmitted() - splitAdmittedMark == 1, "and exactly one admission happened");
        check(battle.hostileCount() == 1, "so the roster holds one child, not a phantom sibling: "
                + battle.hostileCount());
        purgeAll();
    }

    /** S5: the world refuses the join itself, so no child exists and nothing may be recorded for it. */
    private static void splitJoinRefused() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the refused-join encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        refuseSplitJoins = 8;
        refusedSplitJoins = 0;
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the refused-join parent dies");
        await(0, () -> watchRefusedJoins(0));
    }

    private static void watchRefusedJoins(int guard) {
        if (guard > 120) {
            check(false, "a refused join never settled: pending=" + battle.fixtureSplitPending());
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchRefusedJoins(guard + 1));
            return;
        }
        refuseSplitJoins = 0;
        check(refusedSplitJoins >= 2, "the world really refused the split's own joins: " + refusedSplitJoins);
        check(slimesNear(splitDeathPos, 6.0D, splitParent).isEmpty(),
                "no refused child exists in the world");
        check(battle.fixtureSplitBorn() == splitBornMark, "and none was recorded as born");
        check(battle.fixtureSplitAdmitted() == splitAdmittedMark, "and none was admitted");
        check(battle.hostileCount() == 0, "and no slot was left behind");
        check(battle.finished() && battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "so the encounter ends honestly as a victory: " + battle.fixtureEndCode());
        purgeAll();
    }

    /** S7: the roster is full, so the children are kept in the world and re-admitted when a slot frees. */
    private static void splitRosterFullRefusal() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitPreviousCap = TacticalConfig.INSTANCE.participantCap.get();
        TacticalConfig.INSTANCE.participantCap.set(1);
        // The parent is the nearest hostile, so the one-slot cap really takes it and not the placeholder.
        splitParent = spawn(EntityType.SLIME, 4, 0, 0, 4, false, false);
        splitPlaceholder = spawn(EntityType.ZOMBIE, -12, 0, 6, 1);
        check(BattleManager.start(owner, true), "the full-roster encounter starts");
        battle = BattleManager.current();
        check(battle.hostileCount() == 1, "with exactly one hostile taken under the one-slot cap");
        check(battle.fixtureEnemy(splitParent.getUUID()) != null,
                "and that hostile is the size 4 parent, so its children cannot be taken");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        splitPhase = 0;
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent dies");
        await(0, () -> watchFullRefusal(0));
    }

    private static void watchFullRefusal(int guard) {
        if (guard > 200) {
            check(false, "a full-roster split refusal never settled: pending="
                    + battle.fixtureSplitPending() + " held=" + battle.heldCount());
            return;
        }
        if (splitPhase == 0) {
            // The dead parent's own slot frees at once, so the cap is refilled the ordinary way before the
            // children arrive: that is what makes the roster really full when they are born.
            if (battle.hostileCount() == 0) {
                check(splitPlaceholder.isAlive() && battle.tryAbsorb(splitPlaceholder),
                        "the freed slot is refilled by the placeholder through the ordinary admission");
                await(0, () -> watchFullRefusal(guard + 1));
                return;
            }
            List<Slime> children = allFamily(splitParent);
            if (battle.splitPending() || children.isEmpty()
                    || children.stream().noneMatch(battle::fixtureHoldsSplitChild)) {
                await(0, () -> watchFullRefusal(guard + 1));
                return;
            }
            check(battle.hostileCount() == 1,
                    "the roster is really full, so no child could be taken: " + battle.hostileCount());
            for (Slime child : children) {
                if (battle.fixtureEnemy(child.getUUID()) != null) continue;
                check(battle.fixtureHoldsSplitChild(child),
                        "a refused child is held out by the encounter's own exclusion");
                check(child.isAlive() && !child.isRemoved() && child.level() == level,
                        "and is kept in the world, not deleted");
                check(!child.isNoAi(), "and keeps its own AI flag, not a borrowed no-AI");
                check(!battle.isParticipant(child), "and is not a participant");
            }
            check(battle.fixtureSplitAdmitted() == splitAdmittedMark,
                    "nothing was admitted while the cap held");
            splitPhase = 1;
            await(0, () -> watchFullRefusal(guard + 1));
            return;
        }
        if (splitPhase == 1) {
            check(splitPlaceholder.isAlive() && !splitPlaceholder.isRemoved(),
                    "precondition: the placeholder really holds the only hostile slot");
            splitPlaceholder.hurt(splitPlaceholder.damageSources().generic(), 400.0F);
            check(splitPlaceholder.isDeadOrDying(), "and it really dies, freeing the slot");
            splitPhase = 2;
            await(0, () -> watchFullRefusal(guard + 1));
            return;
        }
        // With the only hostile dead the encounter is over, so the children are not re-admitted but released:
        // that is the documented end of a refusal, not a second attempt to take them.
        if (!battle.finished()) {
            check(!battle.splitPending(), "the encounter owns no split wait while the slot is refilled");
            await(0, () -> watchFullRefusal(guard + 1));
            return;
        }
        check(battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "the emptied encounter ends as a victory, not as an anomaly: " + battle.fixtureEndCode());
        check(battle.heldCount() == 0, "and its exclusion is released with it: " + battle.heldCount());
        List<Slime> children = allFamily(splitParent);
        check(!children.isEmpty(), "the refused children are still in the world, not deleted");
        for (Slime child : children) {
            check(child.isAlive() && !child.isRemoved() && child.level() == level,
                    "each is alive and still registered in this world");
            check(!battle.fixtureHoldsSplitChild(child), "and no longer held by the finished encounter");
            check(!battle.isParticipant(child), "and never became a participant of it");
            check(!child.isNoAi(), "and carries no borrowed no-AI");
            check(BattleDeployment.standable(level, child, child.position(), BattleDeployment.Stance.GROUND),
                    "and stands on its own real feet, where the legal zone-out search left it: "
                            + child.position());
        }
        TacticalConfig.INSTANCE.participantCap.set(splitPreviousCap);
        purgeAll();
    }

    private static Zombie splitPlaceholder;
    private static int splitPreviousCap = 16;

    /** S7: a free slot with no legal foot surface is a refusal of its own, and never a deletion or a freeze. */
    private static void splitNoFootingRefusal() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        MagmaCube witness = spawn(EntityType.MAGMA_CUBE, 4, 0, 4, 1);
        check(BattleManager.start(owner, true), "the no-footing encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        check(battle.fixtureEnemy(witness.getUUID()) != null, "and a tiny witness keeps the encounter alive");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        splitPhase = 0;
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent dies");
        await(0, () -> watchNoFooting(0));
    }

    private static void watchNoFooting(int guard) {
        if (guard > 200) {
            check(false, "a no-footing refusal never settled: pending=" + battle.fixtureSplitPending()
                    + " held=" + battle.heldCount() + " roster=" + battle.hostileCount());
            return;
        }
        if (splitPhase == 0) {
            // Seal the chamber the children will be born into: solid stone through the whole vertical range of
            // the bounded deployment search, so no cell in it is a legal foot surface for a size 2 body.
            for (int x = -10; x <= 2; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 0; y <= 8; y++) block(x, y, z, Blocks.STONE);
                }
            }
            splitPhase = 1;
            await(0, () -> watchNoFooting(guard + 1));
            return;
        }
        if (splitPhase == 1) {
            // The witness is a family body as well, so the generation under test is the size 2 one.
            List<Slime> children = allFamily(splitParent).stream()
                    .filter(child -> child.getSize() == 2).toList();
            if (battle.splitPending() || children.isEmpty()) {
                await(0, () -> watchNoFooting(guard + 1));
                return;
            }
            check(!battle.finished(), "the encounter stays alive while a refused child is handed over");
            check(!battle.splitPending(), "the wait really ended by handing the children over");
            check(battle.fixtureSplitAdmitted() == splitAdmittedMark,
                    "no child was taken in the tick its body had nowhere legal to stand");
            for (Slime child : children) {
                if (battle.fixtureEnemy(child.getUUID()) != null) continue;
                check(battle.fixtureHoldsSplitChild(child) || child.isNoAi(),
                        "a refused child is held out by the exclusion, or still separated for its hand-over");
                check(child.isAlive() && !child.isRemoved(), "and stays alive in the world");
                check(!battle.isParticipant(child), "and with no roster slot");
                check(child.getSize() == 2, "and its real native size");
            }
            check(battle.hostileCount() == 1, "the witness is still the whole roster: " + battle.hostileCount());
            // Give the world its foot surfaces back. A held child that the exclusion had to place outside the
            // battlefield still must not be dragged in from there: the retry only ever takes a body that fits
            // where the encounter already is.
            for (int x = -10; x <= 2; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 0; y <= 8; y++) block(x, y, z, Blocks.AIR);
                }
            }
            splitAdmittedMark = battle.fixtureSplitAdmitted();
            splitPhase = 2;
            await(0, () -> watchNoFooting(guard + 1));
            return;
        }
        // A bounded retry window: the exclusion tries again on its own cadence, and none of those attempts may
        // take a body that does not legally fit this battlefield.
        if (guard < 40 && !battle.finished()) {
            await(0, () -> watchNoFooting(guard + 1));
            return;
        }
        check(battle.fixtureSplitAdmitted() == splitAdmittedMark,
                "no retry admitted a body that does not fit the current battlefield");
        List<Slime> children = allFamily(splitParent).stream()
                .filter(child -> child.getSize() == 2).toList();
        for (Slime child : children) {
            check(child.isAlive() && !child.isRemoved(), "every refused child is still in the world");
            check(!battle.isParticipant(child), "and none was dragged into the roster from outside");
            check(!child.isNoAi(), "and none was left frozen with a borrowed no-AI");
            if (battle.fixtureHoldsSplitChild(child)) {
                check(BattleContactGuard.isolationFor(owner, child) != null,
                        "a held child is kept off the members by the same contact gate every refusal uses");
            }
        }
        // Ending the encounter is the documented release: nothing stays suppressed and nothing is deleted.
        battle.finish("fixture no-footing release");
        check(battle.heldCount() == 0, "the finished encounter holds nobody out: " + battle.heldCount());
        for (Slime child : children) {
            check(child.isAlive() && !child.isRemoved() && child.level() == level,
                    "and every refused child is left alive in the world");
            check(!battle.fixtureHoldsSplitChild(child), "with no suppression left from the old session");
            check(!child.isNoAi(), "and its own AI flag, never a permanent no-AI");
            check(!battle.isParticipant(child), "and no roster slot in a finished encounter");
        }
        purgeAll();
    }

    /** S7: a friendly parent's children stay friendly, and no newborn inherits a hand controller. */
    private static void splitFriendlySide() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        Zombie hostile = spawn(EntityType.ZOMBIE, 6, 0, 6, 1);
        check(BattleManager.start(owner, true), "an encounter starts with the zombie as its only hostile");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(hostile.getUUID()) != null, "the zombie is the hostile participant");
        // The friendly body is created after the encounter started, so it is never taken as a hostile first.
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(battle.tryAddFriendly(splitParent), "a slime is taken as a friendly unit");
        check(battle.fixtureSide(splitParent.getUUID()) == BattleSide.PLAYER, "on the player's own side");
        check(battle.fixtureEnemy(splitParent.getUUID()) != null && battle.fixtureEnemy(splitParent.getUUID()).slime() != null,
                "with the family's movement state installed");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        splitAdmittedMark = battle.fixtureSplitAdmitted();
        check(splitParent.hurt(level.damageSources().generic(), 400.0F), "the friendly parent dies");
        await(0, () -> watchFriendlySplit(0));
    }

    private static void watchFriendlySplit(int guard) {
        if (guard > 200) {
            check(false, "a friendly split never settled: pending=" + battle.fixtureSplitPending()
                    + " born=" + (battle.fixtureSplitBorn() - splitBornMark));
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchFriendlySplit(guard + 1));
            return;
        }
        List<Slime> children = slimesNear(splitDeathPos, 8.0D, splitParent);
        check(!children.isEmpty(), "the friendly parent really produced children");
        for (Slime child : children) {
            BattleSide side = battle.fixtureSide(child.getUUID());
            check(side != BattleSide.HOSTILE,
                    "a friendly parent's child is never turned into an enemy: " + side);
            if (side == BattleSide.PLAYER) {
                check(battle.companionOwner(child) == null,
                        "and gains no hand controller from its parent");
            }
        }
        check(battle.fixtureSplitBorn() - splitBornMark == children.size(),
                "each child is recorded once: " + (battle.fixtureSplitBorn() - splitBornMark));
        purgeAll();
    }

    /** S8: an encounter that ends mid-split clears the wait and never claims the late children. */
    private static void splitCleanupOnFinish() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the cleanup encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        splitDeathPos = splitParent.position();
        splitBornMark = battle.fixtureSplitBorn();
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent dies mid-encounter");
        await(0, () -> watchCleanup(0));
    }

    private static void watchCleanup(int guard) {
        if (guard > 200) {
            check(false, "a finished encounter's split never settled");
            return;
        }
        if (battle.splitPending()) {
            if (guard == 0) {
                check(!battle.finished(), "the encounter is still open while the split is owed");
                battle.finish("fixture cleanup during a split");
            }
            await(0, () -> watchCleanup(guard + 1));
            return;
        }
        // The wait is closed and the late children were never isolated, admitted or recorded by this session.
        check(battle.finished(), "the encounter stays finished");
        check(battle.fixtureSplitBorn() - splitBornMark == 0,
                "a late child after the end is never recorded by the old session: "
                        + (battle.fixtureSplitBorn() - splitBornMark));
        List<Slime> children = slimesNear(splitDeathPos, 8.0D, splitParent);
        for (Slime child : children) {
            check(!battle.isParticipant(child), "and is not admitted into a finished encounter");
            check(!battle.fixtureHoldsSplitChild(child), "and is not held by its exclusion");
            check(child.isAlive() && !child.isRemoved(), "and stays a normal world body");
        }
        check(!splitParent.isNoAi() && !splitParent.isPersistenceRequired(),
                "the dead parent's borrowed flags were given back even though the encounter ended mid-split");
        purgeAll();
    }

    /**
     * S8: the abnormal cutoff, over real ticks.
     *
     * <p>The one case a server fixture cannot build is a split that never arrives (an unloaded body or a third
     * party holding it). The wait is opened the way the death scan opens it, and everything after it - the
     * barrier, the sixty-tick release, the end reason when nothing else is left - is the production path.</p>
     */
    private static void splitAbnormalDeadline() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, 4, 0, 0, 4, false, false);
        splitPlaceholder = spawn(EntityType.ZOMBIE, -8, 0, 6, 1);
        check(BattleManager.start(owner, true), "the deadline encounter starts");
        battle = BattleManager.current();
        check(battle.hostileCount() == 2, "two hostiles are taken: " + battle.hostileCount());
        check(battle.fixtureSplitAnomalies() == 0, "no anomaly is recorded before the cutoff");
        check(battle.fixtureSplitWait(splitParent, BattleSide.HOSTILE),
                "a wait that no native removal will ever confirm is opened");
        check(battle.splitPending(), "and the encounter really waits on it");
        check(battle.isBusy(), "so no order may be taken");
        splitPendingTick = worldTicks;
        splitPhase = 0;
        await(0, () -> watchDeadline(0));
    }

    private static void watchDeadline(int guard) {
        if (guard > 300) {
            check(false, "the abnormal split cutoff never released: pending=" + battle.fixtureSplitPending()
                    + " finished=" + battle.finished());
            return;
        }
        if (splitPhase == 0) {
            if (battle.splitPending()) {
                check(!battle.finished(), "an unconfirmed split keeps the encounter from declaring anything");
                await(0, () -> watchDeadline(guard + 1));
                return;
            }
            int waited = worldTicks - splitPendingTick;
            check(waited >= SlimeSplitLifecycle.ABNORMAL_DEADLINE,
                    "an unconfirmed split is released only at the frozen deadline: " + waited + " ticks");
            check(waited <= SlimeSplitLifecycle.ABNORMAL_DEADLINE + 10,
                    "and not long after it: " + waited + " ticks");
            check(battle.fixtureSplitAnomalies() == 1, "and it is recorded as an anomaly");
            check(!battle.finished(), "with other enemies still on the roster the encounter goes on");
            check(battle.hostileCount() == 2, "and keeps both of them: " + battle.hostileCount());
            check(!battle.isBusy(), "so orders are accepted again");
            // Second case, fresh encounter: with nothing else left, the anomaly must end it as an anomaly.
            purgeAll();
            owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
            owner.setHealth(owner.getMaxHealth());
            splitPlaceholder = spawn(EntityType.ZOMBIE, 4, 0, 0, 1);
            check(BattleManager.start(owner, true), "the second deadline encounter starts");
            battle = BattleManager.current();
            check(battle.hostileCount() == 1 && battle.fixtureEnemy(splitPlaceholder.getUUID()) != null,
                    "with the zombie as its only hostile");
            // Created after the encounter started and held still, so it is never taken: it only carries the
            // wait whose native removal will never come.
            MagmaCube unconfirmed = spawn(EntityType.MAGMA_CUBE, 8, 0, 0, 4, true, true);
            check(battle.fixtureSplitWait(unconfirmed, BattleSide.HOSTILE),
                    "and one unconfirmed split owed for a body outside the roster");
            check(battle.splitPending(), "which the encounter really waits on");
            check(splitPlaceholder.hurt(splitPlaceholder.damageSources().generic(), 400.0F),
                    "the last hostile really dies, so nothing else is left");
            check(splitPlaceholder.isDeadOrDying(), "which really is dying");
            splitPendingTick = worldTicks;
            splitPhase = 1;
            await(0, () -> watchDeadline(guard + 1));
            return;
        }
        if (!battle.finished()) {
            await(0, () -> watchDeadline(guard + 1));
            return;
        }
        check(battle.fixtureEndCode() == BattleText.End.SPLIT_UNRESOLVED,
                "an unconfirmed split with nothing else left ends the encounter without claiming a victory: "
                        + battle.fixtureEndCode());
        check(battle.hostileCount() == 0, "and on an empty roster");
        check(battle.fixtureSplitAnomalies() >= 1, "with the anomaly recorded, not hidden");
        purgeAll();
    }

    // ------------------------------------------------------------------ B1: the scope is real and it cannot leak

    /** A slime one listener spawns itself during the event: not this split's child, so never touched. */
    private static Slime scopeForeign;
    /** A second owed parent whose native removal is performed from inside the first one's event. */
    private static Slime nestedParent;
    /** A parent whose split event throws, to prove the scope is closed by the mixin's finally. */
    private static Slime throwingParent;
    private static UUID scopeParentId;
    private static UUID nestedParentId;
    private static UUID throwingParentId;
    private static int scopeBornMark;
    private static int nestedRemovals;
    private static boolean throwOnSplit;

    /**
     * B1: the native-removal scope is per thread and closed by {@code try/finally}, and only the exact children the
     * event finally left are ever taken - not another same-typed add on the same thread, and not a listener's own
     * spawn.
     */
    private static void splitScopeIsReal() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        Slime first = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        Slime second = spawn(EntityType.SLIME, -6, 0, 4, 4, false, false);
        check(BattleManager.start(owner, true), "the scope encounter starts with two size 4 parents");
        battle = BattleManager.current();
        check(battle.hostileCount() == 2, "both parents are on the roster: " + battle.hostileCount());
        check(SlimeSplitLifecycle.scopeDepth() == 0, "no native-removal scope is open before anything happens");
        scopeParentId = first.getUUID();
        nestedParentId = second.getUUID();
        nestedParent = second;
        scopeForeign = null;
        nestedRemovals = 0;
        scopeBornMark = battle.fixtureSplitBorn();
        check(first.hurt(first.damageSources().generic(), 400.0F), "the first parent really dies");
        check(second.hurt(second.damageSources().generic(), 400.0F), "and so does the second, which stays owed");
        await(0, () -> watchScope(0));
    }

    private static void watchScope(int guard) {
        if (guard > 240) {
            check(false, "the nested-scope case never settled: pending=" + battle.fixtureSplitPending()
                    + " born=" + battle.fixtureSplitBorn() + " depth=" + SlimeSplitLifecycle.scopeDepth());
            return;
        }
        if (battle.splitPending()) {
            check(SlimeSplitLifecycle.scopeDepth() == 0,
                    "no native-removal scope is open between removals: " + SlimeSplitLifecycle.scopeDepth());
            await(0, () -> watchScope(guard + 1));
            return;
        }
        check(battle.fixtureSplitBorn() - scopeBornMark > 1, "both owed splits really produced children: "
                + (battle.fixtureSplitBorn() - scopeBornMark));
        check(nestedRemovals >= 1, "the nested removal of another owed parent really ran: " + nestedRemovals);
        check(scopeForeign != null, "the listener really spawned a same-typed body during the event");
        check(!battle.isParticipant(scopeForeign),
                "a body the listener spawned itself is never taken by this split");
        check(!scopeForeign.isNoAi(), "and is never frozen with the split's temporary flags");
        check(SlimeSplitLifecycle.scopeDepth() == 0, "the nested removal returned to the outer scope and closed it");
        // Second case: an exception thrown inside the native removal must not leak the scope or a birth record -
        // and leaving the window through an exception is not the same fact as vanilla finishing, so the unknown
        // outcome may never be settled as a completed split or as a victory.
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        throwingParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the exception encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(throwingParent.getUUID()) != null, "the throwing parent is on the roster");
        throwingParentId = throwingParent.getUUID();
        scopeBornMark = battle.fixtureSplitBorn();
        throwOnSplit = true;
        check(throwingParent.hurt(throwingParent.damageSources().generic(), 400.0F), "the throwing parent dies");
        boolean threw = false;
        try {
            throwingParent.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        } catch (RuntimeException expected) {
            threw = true;
        }
        throwOnSplit = false;
        check(threw, "the listener's exception really escaped the native removal");
        check(SlimeSplitLifecycle.scopeDepth() == 0,
                "and the guarded scope is closed anyway: " + SlimeSplitLifecycle.scopeDepth());
        check(battle.splitPending(),
                "leaving that window through an exception is not a completed removal: the wait is still owed");
        check(!battle.finished(), "so nothing at all was decided on an unknown outcome");
        // Far enough from the member that an ordinary mob does not aggro and reinforce this encounter: this body is
        // here to prove a later join is untouched, not to change the roster.
        Slime innocent = spawn(EntityType.SLIME, -14, 0, 6, 2, false, false);
        check(!innocent.isNoAi(), "an ordinary join after the failure is not touched: its own AI is kept");
        check(battle.fixtureSplitBorn() == scopeBornMark, "and the failed attempt left no birth record");
        faultedInnocent = innocent;
        scopeAdmittedMark = battle.fixtureSplitAdmitted();
        await(0, () -> watchFaultedSplit(0));
    }

    private static Slime faultedInnocent;
    private static int scopeAdmittedMark;

    /**
     * What the two exits really mean over real ticks.
     *
     * <p>The unknown outcome is never completed: while the wait is owed the encounter declares nothing, even
     * though its roster is already empty. Vanilla then retries its own removal when the death timer expires, that
     * second call returns normally, and only then may the wait close - on the children the world really registered,
     * each admitted once. A wait that was "completed" by the exception instead would have closed with no children
     * at all and reported a victory it never proved.</p>
     */
    private static void watchFaultedSplit(int guard) {
        if (guard > 160) {
            check(false, "the faulted split never settled: pending=" + battle.fixtureSplitPending()
                    + " born=" + (battle.fixtureSplitBorn() - scopeBornMark) + " finished=" + battle.finished()
                    + " code=" + battle.fixtureEndCode());
            return;
        }
        if (battle.splitPending()) {
            check(!battle.finished(), "an unknown native outcome is never reported as a finished encounter");
            check(battle.fixtureSplitBorn() == scopeBornMark,
                    "and the failed attempt invents no child: " + (battle.fixtureSplitBorn() - scopeBornMark));
            check(SlimeSplitLifecycle.scopeDepth() == 0,
                    "and no removal scope survives across real ticks: " + SlimeSplitLifecycle.scopeDepth());
            await(0, () -> watchFaultedSplit(guard + 1));
            return;
        }
        List<Slime> children = allFamily(throwingParent).stream()
                .filter(child -> child != faultedInnocent)
                .filter(child -> battle.fixtureEnemy(child.getUUID()) != null).toList();
        int born = battle.fixtureSplitBorn() - scopeBornMark;
        check(born >= 2 && born <= 4,
                "vanilla's own later removal produced its 2..4 children, not a completion with none: " + born);
        check(children.size() == born,
                "and every one of them is a world-registered body this encounter holds: " + children.size()
                        + " of " + born);
        check(battle.fixtureSplitAdmitted() - scopeAdmittedMark == born,
                "each admitted exactly once: " + (battle.fixtureSplitAdmitted() - scopeAdmittedMark));
        for (Slime child : children) {
            check(battle.isParticipant(child), "every child of that real removal is the roster's own body");
        }
        long strangers = battle.hostileCount() - children.size()
                - (battle.isParticipant(faultedInnocent) ? 1 : 0);
        check(strangers == 0, "and no ghost of the failed attempt sits in the roster: " + strangers + " extra in "
                + battle.hostileCount());
        check(battle.fixtureSplitAnomalies() == 0,
                "and the wait was never released abnormally: " + battle.fixtureSplitAnomalies());
        check(!battle.finished() && battle.fixtureEndCode() == null,
                "the encounter still runs on those children instead of ending on the unknown outcome: "
                        + battle.fixtureEndCode());
        check(!battle.isParticipant(faultedInnocent), "the unrelated body that joined after the failure was never "
                + "taken by the split either");
        check(SlimeSplitLifecycle.scopeDepth() == 0, "the scope stayed closed across real ticks");
        purgeAll();
    }

    /** A listener that spawns a same-typed body of its own and performs one nested removal inside the event. */
    @SubscribeEvent
    public static void onScopeSplit(net.neoforged.neoforge.event.entity.living.MobSplitEvent event) {
        if (!enabled) return;
        UUID parent = event.getParent() == null ? null : event.getParent().getUUID();
        if (throwOnSplit && parent != null && parent.equals(throwingParentId)) {
            throw new IllegalStateException("fixture split listener failure");
        }
        if (parent == null || !parent.equals(scopeParentId)) return;
        if (scopeForeign == null) {
            // A same-typed body added on this very thread and tick: not in the final list, so not this split's.
            scopeForeign = EntityType.SLIME.create(level);
            if (scopeForeign != null) {
                scopeForeign.setPos(origin.getX() + 8.5D, origin.getY(), origin.getZ() + 8.5D);
                scopeForeign.setNoAi(false);
                level.addFreshEntity(scopeForeign);
                spawned.add(scopeForeign);
            }
        }
        if (nestedParent != null && nestedParentId != null && !nestedParent.isRemoved()
                && nestedParent.isDeadOrDying()) {
            nestedRemovals++;
            // A nested native removal of another owed parent, on this thread, inside this event.
            nestedParent.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        }
    }

    // ------------------------------------------------------------------ B2: the hand-over owns the child

    private static UUID handoverChildId;
    private static boolean handoverFriendlyRefused;
    private static boolean handoverOtherRefused;
    private static boolean handoverOtherUnchanged;
    private static boolean handoverFlagsKept;
    private static int handoverBornMark;
    private static int handoverAdmittedMark;
    private static Slime cancelledDeath;
    private static UUID cancelSplitDeath;
    private static ServerBattle secondBattle;
    private static MinecraftServer serverRef;

    /**
     * B2: while a birth is separating its child, no admission path may claim it or sample its temporary flags; and a
     * death a later listener cancels leaves no wait and no unfrozen body behind.
     */
    private static void splitHandoverOwnership() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the hand-over encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        secondBattle = secondEncounter();
        check(secondBattle != null && !secondBattle.finished(), "a second encounter really runs beside it");
        handoverChildId = null;
        handoverFriendlyRefused = false;
        handoverOtherRefused = false;
        handoverOtherUnchanged = false;
        handoverFlagsKept = false;
        handoverBornMark = battle.fixtureSplitBorn();
        handoverAdmittedMark = battle.fixtureSplitAdmitted();
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent dies");
        await(0, () -> watchHandover(0));
    }

    private static void watchHandover(int guard) {
        if (guard > 240) {
            check(false, "the hand-over case never settled: pending=" + battle.fixtureSplitPending());
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchHandover(guard + 1));
            return;
        }
        check(handoverChildId != null, "a separated child was really observed inside its hand-over window");
        check(handoverFriendlyRefused, "this encounter's own friendly path cannot take a separated child");
        check(handoverOtherRefused, "and a second encounter cannot claim it either");
        check(handoverOtherUnchanged, "whose roster and battlefield stayed exactly as they were");
        check(handoverFlagsKept, "so the child keeps the temporary flags of the hand-over that owns it");
        check(battle.fixtureSplitBorn() - handoverBornMark > 0, "the birth was recorded exactly once");
        check(battle.fixtureSplitAdmitted() - handoverAdmittedMark > 0,
                "and the child was taken by the hand-over that owned it");
        for (Slime child : allFamily(splitParent)) {
            ServerBattle.EnemyView view = battle.fixtureEnemy(child.getUUID());
            if (view == null) continue;
            check(Boolean.FALSE.equals(battle.fixtureOriginalNoAi(child.getUUID())),
                    "whose roster entry captured its real AI flag, not the temporary separation");
            check(Boolean.FALSE.equals(battle.fixtureOriginalPersistence(child.getUUID())),
                    "and its real persistence flag");
        }
        endSecondEncounter();
        // Second case: a death cancelled and undone by a later listener leaves no wait behind.
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        cancelledDeath = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the cancelled-death encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(cancelledDeath.getUUID()) != null, "the body is on the roster");
        cancelSplitDeath = cancelledDeath.getUUID();
        check(cancelledDeath.hurt(cancelledDeath.damageSources().generic(), 400.0F),
                "the body takes what would be a lethal hit");
        check(!cancelledDeath.isDeadOrDying(), "a later listener cancelled the death and gave its life back");
        cancelSplitDeath = null;
        await(0, () -> watchCancelledDeath(0));
    }

    private static void watchCancelledDeath(int guard) {
        if (guard > 40) {
            check(false, "a cancelled death left a wait behind: pending=" + battle.fixtureSplitPending());
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchCancelledDeath(guard + 1));
            return;
        }
        check(battle.fixtureSplitPending() == 0, "a cancelled death leaves no owed split behind");
        check(battle.fixtureEnemy(cancelledDeath.getUUID()) != null, "and the body is still a participant");
        check(cancelledDeath.isNoAi(), "whose borrowed no-AI was never given back to a living body");
        check(cancelledDeath.isPersistenceRequired(), "nor its borrowed persistence");
        check(!battle.isBusy(), "and the encounter accepts orders again");
        purgeAll();
    }

    /** Fixture lever: cancel a lethal death at the last priority and give the body its life back. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeathUndone(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (cancelSplitDeath == null) return;
        net.minecraft.world.entity.LivingEntity body = event.getEntity();
        if (body == null || !cancelSplitDeath.equals(body.getUUID())) return;
        event.setCanceled(true);
        body.setHealth(body.getMaxHealth());
    }

    /** A listener that probes the hand-over gate from inside the join, after the lifecycle has taken the child. */
    private static final class SplitProbe {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
            if (!enabled || battle == null) return;
            if (!(event.getEntity() instanceof Slime child)) return;
            if (!SlimeSplitLifecycle.isSeparated(child)) return;
            if (handoverChildId != null) return;
            handoverChildId = child.getUUID();
            handoverFriendlyRefused = !battle.tryAddFriendly(child);
            if (secondBattle != null && !secondBattle.finished()) {
                int before = secondBattle.hostileCount();
                int radius = secondBattle.zone().radius();
                handoverOtherRefused = !secondBattle.tryAbsorb(child);
                handoverOtherUnchanged = secondBattle.hostileCount() == before
                        && secondBattle.zone().radius() == radius
                        && !secondBattle.isParticipant(child);
            } else {
                handoverOtherRefused = true;
                handoverOtherUnchanged = true;
            }
            handoverFlagsKept = child.isNoAi() && child.isPersistenceRequired();
        }
    }

    /** A second, independent encounter far from the measuring arena, with its own real player and hostile. */
    private static ServerBattle secondEncounter() {
        if (serverRef == null) return null;
        net.minecraft.core.BlockPos base = origin.offset(0, 0, 64);
        level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 4; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-slime-second".getBytes(StandardCharsets.UTF_8)), "SlimeSecond");
        ServerPlayer other = new ServerPlayer(serverRef, level, profile, ClientInformation.createDefault());
        Wire otherWire = new Wire();
        otherWire.channel = new io.netty.channel.embedded.EmbeddedChannel(otherWire);
        for (String id : List.of("battle_state", "battle_log", "player_step", "creeper_telegraph",
                "move_choice_offer")) {
            net.neoforged.neoforge.network.registration.ChannelAttributes.getOrCreateAdHocChannels(otherWire)
                    .add(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, id));
        }
        new ServerGamePacketListenerImpl(serverRef, otherWire, other,
                CommonListenerCookie.createInitial(profile, false));
        other.setPos(base.getX() + 2.5D, base.getY(), base.getZ() + 0.5D);
        other.setInvulnerable(true);
        level.addNewPlayer(other);
        spawned.add(other);
        Zombie bait = EntityType.ZOMBIE.create(level);
        check(bait != null, "the second encounter's bait really exists");
        bait.setPos(base.getX() + 0.5D, base.getY(), base.getZ() + 2.5D);
        level.addFreshEntity(bait);
        spawned.add(bait);
        bait.setTarget(other);
        if (!BattleManager.start(other, true)) return null;
        // current() is not per player: the second encounter is the one this player founded.
        return BattleManager.findFor(other.getUUID());
    }

    private static void endSecondEncounter() {
        if (secondBattle != null && !secondBattle.finished()) {
            secondBattle.finish("fixture second encounter done");
        }
        secondBattle = null;
    }

    // ------------------------------------------------------------------ B2 (review): one UUID, two bodies

    private static Slime duplicateParentBody;
    private static Slime duplicateFirstChild;
    private static Slime duplicateSecondChild;
    private static UUID duplicateParentId;
    private static int duplicateBornMark;
    private static int duplicateAdmittedMark;

    /**
     * B2 (review): the final child list may hold two different bodies a listener gave the same UUID.
     *
     * <p>Vanilla only registers the first of them, and the encounter must keep exactly that object's birth
     * record: writing the second into the same slot would drop the registered child out of the hand-over set,
     * leave its temporary flags on until the abnormal cutoff and let a body the world never held own the birth.
     * The duplicate is therefore refused before any flag or record exists for it - the registered child is still
     * admitted exactly once, and the encounter neither invents a slot for the refused body nor reports anything
     * it did not prove.</p>
     */
    private static void splitDuplicateChildUuid() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        duplicateParentBody = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the duplicate-UUID encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(duplicateParentBody.getUUID()) != null, "the parent is on the roster");
        duplicateParentId = duplicateParentBody.getUUID();
        duplicateFirstChild = null;
        duplicateSecondChild = null;
        duplicateBornMark = battle.fixtureSplitBorn();
        duplicateAdmittedMark = battle.fixtureSplitAdmitted();
        check(duplicateParentBody.hurt(duplicateParentBody.damageSources().generic(), 400.0F),
                "the parent really dies");
        await(0, () -> watchDuplicateUuid(0));
    }

    private static void watchDuplicateUuid(int guard) {
        if (guard > 240) {
            check(false, "the duplicate-UUID split never settled: pending=" + battle.fixtureSplitPending()
                    + " finished=" + battle.finished() + " code=" + battle.fixtureEndCode());
            return;
        }
        if (battle.splitPending()) {
            check(!battle.finished(), "the encounter reports no outcome while the births are still owed");
            await(0, () -> watchDuplicateUuid(guard + 1));
            return;
        }
        duplicateParentId = null;
        check(duplicateFirstChild != null && duplicateSecondChild != null,
                "the fixture really produced two distinct children sharing one UUID");
        check(duplicateFirstChild != duplicateSecondChild
                        && duplicateFirstChild.getUUID().equals(duplicateSecondChild.getUUID()),
                "which really are two different objects under the same UUID");
        // The world's own registration: the first join wins the UUID, the second is refused by the level itself.
        check(level.getEntity(duplicateFirstChild.getUUID()) == duplicateFirstChild,
                "the world registered exactly the first of the two bodies under that UUID");
        check(level.getEntity(duplicateSecondChild.getUUID()) != duplicateSecondChild,
                "and holds no such body as the second one");
        List<Slime> world = allFamily(duplicateParentBody);
        check(battle.fixtureSplitBorn() - duplicateBornMark == world.size(),
                "one birth record per body the world really holds, never one per list entry: born "
                        + (battle.fixtureSplitBorn() - duplicateBornMark) + " for " + world.size());
        check(battle.fixtureSplitAdmitted() - duplicateAdmittedMark == world.size(),
                "and each of those was admitted exactly once: "
                        + (battle.fixtureSplitAdmitted() - duplicateAdmittedMark));
        check(battle.hostileCount() == world.size(),
                "the roster holds exactly those bodies: " + battle.hostileCount());
        check(battle.isParticipant(duplicateFirstChild), "the registered child is the encounter's participant");
        check(!battle.isParticipant(duplicateSecondChild), "the refused duplicate never got a slot");
        check(!SlimeSplitLifecycle.isSeparated(duplicateSecondChild),
                "nor did it borrow the encounter's temporary separation");
        check(!duplicateSecondChild.isNoAi() && !duplicateSecondChild.isPersistenceRequired(),
                "its own flags are exactly the ones vanilla copied from the parent");
        check(!SlimeSplitLifecycle.isSeparated(duplicateFirstChild),
                "the registered child's hand-over really completed, so it is no longer held apart");
        ServerBattle.EnemyView taken = battle.fixtureEnemy(duplicateFirstChild.getUUID());
        check(taken != null && taken.ap() == 100 && taken.paidActions() == 0,
                "which owns its own initial pool and no paid action: "
                        + (taken == null ? -1 : taken.ap()) + " / " + (taken == null ? -1 : taken.paidActions()));
        check(Boolean.FALSE.equals(battle.fixtureOriginalNoAi(duplicateFirstChild.getUUID())),
                "and the roster entry captured its real AI flag, never the temporary one");
        check(Boolean.FALSE.equals(battle.fixtureOriginalPersistence(duplicateFirstChild.getUUID())),
                "and its real persistence flag, never the borrowed one");
        check(battle.fixtureSide(duplicateFirstChild.getUUID()) == BattleSide.HOSTILE,
                "with the parent's own side");
        check(battle.fixtureEndCode() == null,
                "nothing was declared on a resolution the duplicate could have confused: " + battle.fixtureEndCode());
        purgeAll();
    }

    // ------------------------------------------------------------------ B3: the compound barrier

    private static Slime barrierParent;
    private static Zombie barrierFaller;
    private static int barrierPhase;
    private static long barrierEpoch;
    private static int barrierStep;

    /**
     * B3: an emptied roster is not a victory while a forced fall still runs, and the waiting is remembered instead of
     * being re-derived; both orders of the two barriers are exercised.
     */
    private static void splitAndFallBarriers() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        barrierParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the compound-barrier encounter starts");
        battle = BattleManager.current();
        // A friendly body is the one that falls: it is not a hostile, so the roster can really empty while it falls.
        barrierFaller = spawn(EntityType.ZOMBIE, 2, 0, 0, 1);
        check(battle.tryAddFriendly(barrierFaller), "a friendly body is taken beside it");
        check(battle.hostileCount() == 1, "the size 4 parent is the whole hostile roster: " + battle.hostileCount());
        // This split keeps no child, so the roster really empties when its wait closes.
        trimToNothing = barrierParent.getUUID();
        // A real forced fall, long enough to outlive the split's own twenty-tick wait.
        for (int y = 0; y >= -9; y--) block(2, y, 0, Blocks.AIR);
        barrierEpoch = battle.turnEpoch();
        barrierPhase = 0;
        check(barrierParent.hurt(barrierParent.damageSources().generic(), 400.0F),
                "the size 4 parent dies while the friendly body is starting to fall");
        await(0, () -> watchFallBarrier(0));
    }

    private static void watchFallBarrier(int guard) {
        if (guard > 260) {
            check(false, "the compound barrier never settled: split=" + battle.fixtureSplitPending()
                    + " fall=" + battle.fixtureFallBusy() + " finished=" + battle.finished()
                    + " deferred=" + battle.fixtureVictoryDeferred());
            return;
        }
        if (barrierPhase == 0) {
            if (!battle.splitPending() && battle.fixtureFallBusy()) {
                check(!battle.finished(), "an emptied roster with a running fall is not a victory yet");
                check(battle.hostileCount() == 0, "the hostile roster really is empty: " + battle.hostileCount());
                check(battle.fixtureVictoryDeferred(), "the victory is remembered while the fall owns the unit");
                barrierPhase = 1;
            }
            await(0, () -> watchFallBarrier(guard + 1));
            return;
        }
        if (battle.fixtureFallBusy()) {
            check(!battle.finished(), "still not a victory while the fall runs");
            await(0, () -> watchFallBarrier(guard + 1));
            return;
        }
        check(battle.finished(), "the encounter ends as soon as the last barrier clears");
        check(battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "and as a real victory: " + battle.fixtureEndCode());
        check(battle.turnEpoch() - barrierEpoch <= 1,
                "the two barriers advanced the turn at most once: " + (battle.turnEpoch() - barrierEpoch));
        trimToNothing = null;
        // Second order: the fall is over long before the split resolves.
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        barrierParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the reversed-order encounter starts");
        battle = BattleManager.current();
        barrierFaller = spawn(EntityType.ZOMBIE, 2, 0, 0, 1);
        check(battle.tryAddFriendly(barrierFaller), "a friendly body is taken beside it again");
        check(battle.hostileCount() == 1, "one hostile on the roster: " + battle.hostileCount());
        trimToNothing = barrierParent.getUUID();
        // One cell only: the fall is finished long before the split resolves.
        block(2, 0, 0, Blocks.AIR);
        barrierEpoch = battle.turnEpoch();
        barrierPhase = 2;
        check(barrierParent.hurt(barrierParent.damageSources().generic(), 400.0F), "the parent dies");
        await(0, () -> watchReversedBarrier(0));
    }

    private static void watchReversedBarrier(int guard) {
        if (guard > 160) {
            check(false, "the reversed barrier order never settled: split=" + battle.fixtureSplitPending()
                    + " fall=" + battle.fixtureFallBusy() + " finished=" + battle.finished());
            return;
        }
        if (battle.splitPending()) {
            check(!battle.finished(), "no victory while the split is owed, whatever the fall did");
            await(0, () -> watchReversedBarrier(guard + 1));
            return;
        }
        if (!battle.finished()) {
            await(0, () -> watchReversedBarrier(guard + 1));
            return;
        }
        trimToNothing = null;
        check(battle.fixtureEndCode() == BattleText.End.ENEMIES_DEFEATED,
                "the fall ending first does not change the honest victory: " + battle.fixtureEndCode());
        check(battle.turnEpoch() - barrierEpoch <= 1,
                "and one hand-over at most: " + (battle.turnEpoch() - barrierEpoch));
        purgeAll();
    }

    private static Slime anomalyParentA;
    private static Slime anomalyParentB;
    private static UUID anomalyParentBId;
    private static int anomalyPhase;

    /**
     * B3: an early abnormal release must survive a later, perfectly normal one - an unconfirmed split never decays
     * into "all enemies defeated".
     */
    private static void splitAnomalySurvives() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        anomalyParentA = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        anomalyParentB = spawn(EntityType.SLIME, -6, 0, 5, 4, false, false);
        check(BattleManager.start(owner, true), "the anomaly encounter starts with two size 4 parents");
        battle = BattleManager.current();
        check(battle.hostileCount() == 2, "both parents are on the roster: " + battle.hostileCount());
        check(battle.fixtureSplitAnomalies() == 0, "no anomaly is recorded yet");
        check(battle.fixtureSplitWait(anomalyParentA, BattleSide.HOSTILE),
                "one split is opened that no native removal will confirm");
        anomalyParentBId = anomalyParentB.getUUID();
        anomalyPhase = 0;
        await(0, () -> watchAnomaly(0));
    }

    private static void watchAnomaly(int guard) {
        if (guard > 300) {
            check(false, "the anomaly case never settled: pending=" + battle.fixtureSplitPending()
                    + " finished=" + battle.finished() + " code=" + battle.fixtureEndCode()
                    + " roster=" + battle.hostileCount());
            return;
        }
        if (anomalyPhase == 0) {
            if (battle.splitPending()) {
                check(!battle.finished(), "the encounter waits for the unconfirmed split");
                await(0, () -> watchAnomaly(guard + 1));
                return;
            }
            check(battle.fixtureSplitAnomalies() == 1, "the unconfirmed split was released as an anomaly");
            check(!battle.finished(), "and the encounter goes on while another enemy is left");
            // The first parent now dies and its split keeps no child: a perfectly normal release.
            trimToNothing = anomalyParentA.getUUID();
            check(anomalyParentA != null && anomalyParentA.hurt(anomalyParentA.damageSources().generic(), 400.0F),
                    "the first parent dies and its split keeps no child");
            anomalyPhase = 1;
            await(0, () -> watchAnomaly(guard + 1));
            return;
        }
        if (anomalyPhase == 1) {
            if (battle.splitPending()) {
                await(0, () -> watchAnomaly(guard + 1));
                return;
            }
            check(!battle.finished(), "the encounter still goes on with one enemy left");
            // The second parent dies the same way: a second normal release, and the roster really empties.
            trimToNothing = anomalyParentBId;
            check(anomalyParentB != null && anomalyParentB.hurt(anomalyParentB.damageSources().generic(), 400.0F),
                    "the second parent dies and its split is cancelled by a listener");
            anomalyPhase = 2;
            await(0, () -> watchAnomaly(guard + 1));
            return;
        }
        if (!battle.finished()) {
            await(0, () -> watchAnomaly(guard + 1));
            return;
        }
        trimToNothing = null;
        check(battle.fixtureEndCode() == BattleText.End.SPLIT_UNRESOLVED,
                "two later normal releases do not wash the earlier anomaly into a victory: "
                        + battle.fixtureEndCode());
        check(battle.fixtureSplitAnomalies() == 1, "with the anomaly still recorded once");
        purgeAll();
    }

    /** Fixture lever: keep no child at all for one parent, i.e. a normal release with nothing born. */
    private static UUID trimToNothing;

    // ------------------------------------------------------------------ B4: the retake after a refusal

    private static int retakePhase;
    private static int retakeAdmittedMark;
    private static int retakeZoneRadius;
    private static UUID retakeChildId;

    /**
     * B4: a refused child that could not even be placed outside stays suppressed in place, and once the terrain
     * obstacle in front of it is gone the ordinary retry takes it in - one admission, no battlefield growth.
     */
    private static void splitRetakenThroughRetry() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        splitParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        MagmaCube witness = spawn(EntityType.MAGMA_CUBE, 4, 0, 4, 1);
        check(BattleManager.start(owner, true), "the retake encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(splitParent.getUUID()) != null, "the parent is on the roster");
        check(battle.fixtureEnemy(witness.getUUID()) != null, "and a tiny witness keeps the encounter alive");
        retakeZoneRadius = battle.zone().radius();
        retakeAdmittedMark = battle.fixtureSplitAdmitted();
        retakeChildId = null;
        retakePhase = 0;
        check(splitParent.hurt(splitParent.damageSources().generic(), 400.0F), "the parent dies");
        await(0, () -> watchRetake(0));
    }

    private static void watchRetake(int guard) {
        if (guard > 260) {
            check(false, "the retake never settled: pending=" + battle.fixtureSplitPending()
                    + " admitted=" + (battle.fixtureSplitAdmitted() - retakeAdmittedMark)
                    + " held=" + battle.heldCount());
            return;
        }
        if (retakePhase == 0) {
            // Seal the chamber through the whole vertical range of the bounded deployment search, and make every
            // candidate the bounded zone-out search would try unstandable: the child has nowhere to go at all.
            for (int x = -10; x <= 2; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 0; y <= 8; y++) block(x, y, z, Blocks.STONE);
                }
            }
            int cleared = blockZoneOutRing(battle.zone().center(), battle.zone().radius(),
                    net.minecraft.util.Mth.floor(splitParent.getY()));
            check(cleared > 0, "the zone-out candidates really had ground to remove: " + cleared);
            retakePhase = 1;
            await(0, () -> watchRetake(guard + 1));
            return;
        }
        if (retakePhase == 1) {
            List<Slime> children = allFamily(splitParent).stream()
                    .filter(child -> child.getSize() == 2).toList();
            if (battle.splitPending() || children.isEmpty()) {
                await(0, () -> watchRetake(guard + 1));
                return;
            }
            check(battle.fixtureSplitAdmitted() == retakeAdmittedMark,
                    "no child was taken while its body had nowhere legal to stand");
            check(battle.hostileCount() == 1, "the witness is still the whole roster: " + battle.hostileCount());
            for (Slime child : children) {
                check(battle.fixtureHoldsSplitChild(child), "the refused child is held by the exclusion");
                check(child.isAlive() && !child.isRemoved(), "and stays alive in the world");
                check(!child.isNoAi(), "with its own AI flag, never a permanent no-AI");
                check(!battle.isParticipant(child), "and with no roster slot");
                net.minecraft.core.BlockPos cell = child.blockPosition();
                check(battle.zone().contains(new GridPos(cell.getX(), cell.getY(), cell.getZ())),
                        "with no exit at all it is only suppressed where it really is, not moved anywhere: " + cell);
                if (retakeChildId == null) retakeChildId = child.getUUID();
            }
            // Now remove the terrain obstacle in front of it: the bounded search has a legal target again.
            for (int x = -10; x <= 2; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 0; y <= 8; y++) block(x, y, z, Blocks.AIR);
                }
            }
            retakePhase = 2;
            await(0, () -> watchRetake(guard + 1));
            return;
        }
        long taken = allFamily(splitParent).stream()
                .filter(child -> child.getSize() == 2 && battle.fixtureEnemy(child.getUUID()) != null).count();
        if (taken == 0) {
            await(0, () -> watchRetake(guard + 1));
            return;
        }
        check(taken >= 1, "the ordinary retry really took the refused child back in: " + taken);
        check(battle.zone().radius() == retakeZoneRadius,
                "and never grew the battlefield after the child: " + battle.zone().radius()
                        + " vs " + retakeZoneRadius);
        check(battle.fixtureSplitAdmitted() - retakeAdmittedMark == taken,
                "each retaken child is one admission: " + (battle.fixtureSplitAdmitted() - retakeAdmittedMark));
        for (Slime child : allFamily(splitParent)) {
            ServerBattle.EnemyView view = battle.fixtureEnemy(child.getUUID());
            if (view == null) continue;
            check(!battle.fixtureHoldsSplitChild(child), "the exclusion released it");
            check(battle.fixtureSide(child.getUUID()) == BattleSide.HOSTILE, "with the parent's own side");
            check(view.ap() == 100, "and its own initial pool, once: " + view.ap());
            check(view.paidActions() == 0, "and no paid action");
        }
        check(battle.hostileCount() == 1 + (int) taken, "the roster holds the witness and the retaken children: "
                + battle.hostileCount());
        battle.finish("fixture retake done");
        purgeAll();
    }

    /** Make every candidate the bounded zone-out search walks unstandable, so the "no exit" branch is provable. */
    private static int blockZoneOutRing(GridPos center, int zoneRadius, int sourceY) {
        if (center == null) return 0;
        int start = Math.max(1, zoneRadius) + 3;
        int end = start + 24;
        int cleared = 0;
        for (int radius = start; radius <= end; radius++) {
            for (int index = 0; index < 16; index++) {
                double angle = (Math.PI * 2.0D) * index / 16.0D;
                int x = center.x() + (int) Math.round(Math.cos(angle) * radius);
                int z = center.z() + (int) Math.round(Math.sin(angle) * radius);
                for (int delta = 0; delta <= 6; delta++) {
                    for (int sign : delta == 0 ? new int[]{0} : new int[]{1, -1}) {
                        int y = sourceY + delta * sign;
                        net.minecraft.core.BlockPos support = new net.minecraft.core.BlockPos(x, y - 1, z);
                        if (!level.getBlockState(support).isAir()) {
                            level.setBlock(support, Blocks.AIR.defaultBlockState(), 2);
                            cleared++;
                        }
                    }
                }
            }
        }
        return cleared;
    }

    // ------------------------------------------------------------------ B5: the real payment, the acting death, the magma

    private static Slime paymentParent;
    private static int paymentApBefore;
    private static int paymentPaidBefore;
    private static long paymentEpoch;
    private static int paymentQuote;
    private static int paymentDurabilityBefore;
    private static int paymentDurabilityAfter;
    private static net.minecraft.world.item.ItemStack paymentOriginalHand =
            net.minecraft.world.item.ItemStack.EMPTY.copy();

    /**
     * B5: a really paid lethal melee through the encounter's own intent entry - the payment happens once, and the
     * split that the same hit creates does not refund or repeat it.
     *
     * <p>The member holds a real damageable native weapon for this case, so the settle is decidable on the three
     * numbers that belong to it: the pool loses exactly the frozen quote the encounter published before the intent,
     * the paid counter moves once, and the weapon loses exactly one point of durability through the native
     * {@code Player#attack} call - which no split wait may cancel or redo afterwards. The attack speed and damage
     * attributes stay fixture preconditions and are restored afterwards, exactly like the original hand stack.</p>
     */
    private static void splitLethalAttackKeepsPayment() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.hurtTime = 0;
        double attackSpeed = owner.getAttribute(Attributes.ATTACK_SPEED).getBaseValue();
        double attackDamage = owner.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue();
        owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100.0D);
        owner.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20.0D);
        paymentOriginalHand = owner.getMainHandItem().copy();
        // A real native sword: its durability is spent by the vanilla player attack, so a completed action can be
        // told apart from a cancelled or repeated one without inventing a counter here.
        net.minecraft.world.item.ItemStack sword =
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD);
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sword);
        try {
            // Adjacent to the founder's own cell, and narrow enough to stand there beside the player.
            paymentParent = spawn(EntityType.SLIME, 15, 0, 8, 2, false, false);
            check(BattleManager.start(owner, true), "the payment encounter starts with the player acting first");
            battle = BattleManager.current();
            check(battle.isPlayerTurnNow(owner), "the member really has the turn for its own lethal hit");
            ServerBattle.EnemyView admitted = battle.fixtureEnemy(paymentParent.getUUID());
            check(admitted != null, "the size 2 parent is on the roster");
            GridPos ownerCell = battle.currentActorCell(owner);
            check(ownerCell != null && admitted != null
                            && Math.abs(ownerCell.x() - admitted.cell().x())
                            + Math.abs(ownerCell.z() - admitted.cell().z()) == 1
                            && Math.abs(ownerCell.y() - admitted.cell().y()) <= 1,
                    "and really stands one cell from the member: " + (admitted == null ? null : admitted.cell())
                            + " vs " + ownerCell);
            check(owner.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SWORD)
                            && owner.getMainHandItem().isDamageableItem(),
                    "the member really holds a damageable native weapon for this hit");
            paymentDurabilityBefore = owner.getMainHandItem().getDamageValue();
            paymentApBefore = battle.currentAp(owner);
            paymentPaidBefore = battle.fixtureMemberPaid(owner);
            paymentEpoch = battle.turnEpoch();
            // The quote the encounter publishes before the intent is the one the commit must settle at.
            paymentQuote = battle.quoteAttackCost();
            check(paymentQuote > 0 && paymentQuote <= paymentApBefore,
                    "the frozen quote is payable before the intent: " + paymentQuote + " of " + paymentApBefore);
            battle.tryAttack(owner,
                    com.tactical.neoforge.battle.multi.ActionStamp.of(battle.fixtureSessionId(), battle.turnEpoch()),
                    paymentParent.getUUID());
            check(paymentParent.isDeadOrDying(),
                    "the member's own stamped melee really killed the parent with its native attack");
            check(battle.splitPending(), "and the same hit already owes the split");
            check(battle.fixtureMemberPaid(owner) == paymentPaidBefore + 1,
                    "the action is paid exactly once: " + paymentPaidBefore + " -> "
                            + battle.fixtureMemberPaid(owner));
            check(battle.currentAp(owner) == paymentApBefore - paymentQuote,
                    "and its pool lost exactly the frozen quote: " + paymentApBefore + " - " + paymentQuote
                            + " vs " + battle.currentAp(owner));
            paymentDurabilityAfter = owner.getMainHandItem().getDamageValue();
            check(paymentDurabilityAfter == paymentDurabilityBefore + 1,
                    "the native attack spent exactly one point of the weapon's real durability: "
                            + paymentDurabilityBefore + " -> " + paymentDurabilityAfter);
            check(battle.turnEpoch() == paymentEpoch, "without advancing the member's own turn");
        } finally {
            owner.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(attackSpeed);
            owner.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(attackDamage);
        }
        paymentApBefore = battle.currentAp(owner);
        paymentPaidBefore = battle.fixtureMemberPaid(owner);
        await(0, () -> watchPayment(0));
    }

    private static void watchPayment(int guard) {
        if (guard > 160) {
            check(false, "the paid kill's split never settled: pending=" + battle.fixtureSplitPending());
            endPaymentCase();
            return;
        }
        if (battle.splitPending()) {
            check(battle.isBusy(), "the barrier is up for the whole wait");
            await(0, () -> watchPayment(guard + 1));
            return;
        }
        check(battle.turnEpoch() == paymentEpoch, "the member keeps its own turn across the split");
        check(battle.currentAp(owner) == paymentApBefore,
                "and its remaining pool is never restored: " + battle.currentAp(owner) + " vs " + paymentApBefore);
        check(battle.fixtureMemberPaid(owner) == paymentPaidBefore, "and its paid count is unchanged");
        check(owner.getMainHandItem().getDamageValue() == paymentDurabilityAfter,
                "and the weapon's durability is neither re-spent nor refunded by the split: "
                        + owner.getMainHandItem().getDamageValue() + " vs " + paymentDurabilityAfter);
        check(owner.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SWORD),
                "the weapon survived the action intact");
        check(!battle.isBusy(), "and it may act again");
        endPaymentCase();
    }

    /** Give the member its original hand stack back and leave a clean arena for the next case. */
    private static void endPaymentCase() {
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, paymentOriginalHand);
        paymentOriginalHand = net.minecraft.world.item.ItemStack.EMPTY.copy();
        purgeAll();
    }

    private static Slime actingParent;
    private static UUID actingParentId;
    private static long actingEpoch;
    private static int actingGuard;

    /**
     * B5: the acting parent dies on its own turn, its hand-over is blocked by the owed split, and the turn advances
     * exactly once when the wait ends.
     */
    private static void splitActingParentDiesOnce() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        actingParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        spawn(EntityType.ZOMBIE, 4, 0, 4, 1);
        check(BattleManager.start(owner, true), "the acting-death encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(actingParent.getUUID()) != null, "the parent is on the roster");
        actingParentId = actingParent.getUUID();
        actingGuard = 0;
        await(0, () -> watchActingTurn(0));
    }

    private static void watchActingTurn(int guard) {
        if (guard > 200) {
            check(false, "the acting parent never got its own turn: current=" + battle.currentActorId());
            return;
        }
        if (actingParentId.equals(battle.currentActorId()) && !battle.isBusy()) {
            actingEpoch = battle.turnEpoch();
            check(actingParent != null && actingParent.hurt(actingParent.damageSources().generic(), 400.0F),
                    "the acting parent really dies inside its own turn");
            check(battle.splitPending(), "and its split is owed at once");
            check(battle.turnEpoch() == actingEpoch, "so the hand-over it triggers is deferred, not performed");
            await(0, () -> watchActingHandOver(0));
            return;
        }
        if (battle.isPlayerTurnNow(owner)) {
            battle.endPlayerTurn(owner,
                    com.tactical.neoforge.battle.multi.ActionStamp.of(battle.fixtureSessionId(), battle.turnEpoch()));
        }
        await(0, () -> watchActingTurn(guard + 1));
    }

    private static void watchActingHandOver(int guard) {
        if (guard > 160) {
            check(false, "the acting death never resolved: pending=" + battle.fixtureSplitPending()
                    + " epoch=" + battle.turnEpoch() + " from " + actingEpoch);
            return;
        }
        if (battle.splitPending()) {
            check(battle.turnEpoch() == actingEpoch, "no hand-over happens while the split is owed");
            await(0, () -> watchActingHandOver(guard + 1));
            return;
        }
        check(battle.turnEpoch() == actingEpoch + 1,
                "exactly one hand-over after the wait: " + actingEpoch + " -> " + battle.turnEpoch());
        check(!actingParentId.equals(battle.currentActorId()), "and the dead parent no longer acts");
        await(2, SlimeSelfTest::finishActingCase);
    }

    private static void finishActingCase() {
        check(battle.finished() || battle.turnEpoch() <= actingEpoch + 1,
                "the turn did not advance a second time: " + battle.turnEpoch());
        purgeAll();
    }

    /**
     * B5: a magma cube really splits natively too - its own type, half the size, fire immunity and its own pool.
     */
    private static void magmaCubeNativeSplit() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        MagmaCube parent = spawn(EntityType.MAGMA_CUBE, -6, 0, 0, 2, false, false);
        check(BattleManager.start(owner, true), "the magma encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(parent.getUUID()) != null, "the size 2 magma cube is on the roster");
        check(parent.fireImmune(), "and is fire immune, exactly as vanilla makes it");
        splitParent = parent;
        splitDeathPos = parent.position();
        int bornMark = battle.fixtureSplitBorn();
        int admittedMark = battle.fixtureSplitAdmitted();
        check(parent.hurt(parent.damageSources().generic(), 400.0F), "the magma cube really dies");
        check(battle.splitPending(), "and its split is owed");
        await(0, () -> watchMagmaSplit(0, bornMark, admittedMark));
    }

    private static void watchMagmaSplit(int guard, int bornMark, int admittedMark) {
        if (guard > 160) {
            check(false, "the magma split never settled: pending=" + battle.fixtureSplitPending());
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchMagmaSplit(guard + 1, bornMark, admittedMark));
            return;
        }
        List<Slime> children = allFamily(splitParent);
        check(!children.isEmpty(), "the magma cube really produced native children");
        check(battle.fixtureSplitBorn() - bornMark == children.size(),
                "one birth record per child: " + (battle.fixtureSplitBorn() - bornMark));
        for (Slime child : children) {
            check(child.getType() == EntityType.MAGMA_CUBE, "every child stays a magma cube");
            check(child.getSize() == 1, "at half the parent's size: " + child.getSize());
            check(child.fireImmune(), "and keeps its native fire immunity");
            ServerBattle.EnemyView view = battle.fixtureEnemy(child.getUUID());
            if (view == null) continue;
            check(view.ap() == 100, "with its own initial pool: " + view.ap());
            check(battle.fixtureSide(child.getUUID()) == BattleSide.HOSTILE, "and the parent's side");
            check(Boolean.FALSE.equals(battle.fixtureOriginalNoAi(child.getUUID())),
                    "and its real AI flag, never the temporary separation");
        }
        check(battle.fixtureSplitAdmitted() - admittedMark == children.size(),
                "each magma child was admitted exactly once");
        purgeAll();
    }

    // ------------------------------------------------------------------ B5 (review): the child's first own turn

    private static Slime firstTurnParent;
    private static Slime firstTurnChild;
    private static int firstTurnPool;

    /**
     * B5 (review): a real admitted child's first own turn adds no recovery.
     *
     * <p>A newborn's pool sits on its cap, so an unwanted extra recovery would simply be clamped away and prove
     * nothing. The fixture therefore reads the independent initial pool of one really admitted child first, then
     * hands that exact body a pool with genuine headroom through the ordinary fixture setter and lets the turn come
     * to it: what the pool holds at the first tick of its own turn is what it was given, before it plans or pays
     * anything.</p>
     */
    private static void splitChildFirstOwnTurn() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        firstTurnParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the first-own-turn encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(firstTurnParent.getUUID()) != null, "the parent is on the roster");
        check(firstTurnParent.hurt(firstTurnParent.damageSources().generic(), 400.0F), "the parent really dies");
        check(battle.splitPending(), "and its split is owed at once");
        await(0, () -> watchFirstTurnChildren(0));
    }

    private static void watchFirstTurnChildren(int guard) {
        if (guard > 240) {
            check(false, "the split never produced an admitted child: pending=" + battle.fixtureSplitPending()
                    + " born=" + battle.fixtureSplitBorn());
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchFirstTurnChildren(guard + 1));
            return;
        }
        List<Slime> admitted = allFamily(firstTurnParent).stream()
                .filter(child -> battle.fixtureEnemy(child.getUUID()) != null).toList();
        if (admitted.isEmpty()) {
            check(false, "no child of the split was admitted, so no first own turn can be measured");
            return;
        }
        firstTurnChild = admitted.get(0);
        ServerBattle.EnemyView view = battle.fixtureEnemy(firstTurnChild.getUUID());
        check(view != null && view.ap() == 100,
                "the real admitted child starts on its own initial pool, never the parent's: "
                        + (view == null ? -1 : view.ap()));
        check(view != null && view.paidActions() == 0, "with no paid action yet");
        firstTurnPool = 40;
        check(view != null && firstTurnPool < view.maxAp(),
                "fixture precondition: the chosen pool leaves real headroom below the cap, so an unwanted "
                        + "recovery could not be clamped away: " + firstTurnPool + " of "
                        + (view == null ? -1 : view.maxAp()));
        check(battle.fixtureEnemyPool(firstTurnChild.getUUID(), firstTurnPool),
                "the fixture hands that exact child a pool with headroom");
        await(0, () -> watchFirstOwnTurn(0));
    }

    private static void watchFirstOwnTurn(int guard) {
        if (guard > 400) {
            check(false, "the child never got its own first turn: actor=" + battle.currentActorId()
                    + " epoch=" + battle.turnEpoch());
            return;
        }
        check(!battle.finished(), "the encounter stays open while the child's own turn is still to come");
        if (battle.isActorTurn(firstTurnChild)) {
            ServerBattle.EnemyView view = battle.fixtureEnemy(firstTurnChild.getUUID());
            check(view != null, "the child is still the body this encounter holds");
            check(view != null && view.ap() == firstTurnPool,
                    "its first own turn added no recovery to the pool it was given: "
                            + (view == null ? -1 : view.ap()) + " vs " + firstTurnPool);
            check(view != null && view.paidActions() == 0,
                    "and it has neither planned nor paid an action at that moment: "
                            + (view == null ? -1 : view.paidActions()));
            check(firstTurnChild.getUUID().equals(battle.currentActorId()),
                    "the actor really is that exact child: " + battle.currentActorId());
            await(1, SlimeSelfTest::finishFirstOwnTurnCase);
            return;
        }
        if (battle.isPlayerTurnNow(owner)) battle.endPlayerTurn();
        await(0, () -> watchFirstOwnTurn(guard + 1));
    }

    private static void finishFirstOwnTurnCase() {
        purgeAll();
    }

    // ------------------------------------------------------------------ B2 (review): the parent's UUID is not its identity

    private static Slime substituteParent;

    /**
     * B2 (review): a same-UUID substitute for the <em>parent</em> never takes over a wait that belongs to the exact
     * body the encounter held.
     *
     * <p>A respawn or a third-party re-add may carry a UUID an owed split already names. Refusing that substitute is
     * the safe answer: the original wait keeps its own body, the substitute gets no record of its own, and the real
     * split still happens through vanilla's own removal.</p>
     */
    private static void splitParentSubstituteRefused() {
        purgeAll();
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
        substituteParent = spawn(EntityType.SLIME, -6, 0, 0, 4, false, false);
        check(BattleManager.start(owner, true), "the substitute encounter starts");
        battle = BattleManager.current();
        check(battle.fixtureEnemy(substituteParent.getUUID()) != null, "the parent is on the roster");
        check(substituteParent.hurt(substituteParent.damageSources().generic(), 400.0F),
                "the parent really dies, so a split is owed");
        check(battle.splitPending(), "and the wait belongs to that exact body");
        UUID owed = substituteParent.getUUID();
        int bornMark = battle.fixtureSplitBorn();
        Slime substitute = EntityType.SLIME.create(level);
        check(substitute != null, "a second body of the same type really exists");
        if (substitute != null) {
            substitute.setPos(substituteParent.position());
            substitute.setSize(4, true);
            substitute.setUUID(owed);
            check(!battle.fixtureSplitWait(substitute, BattleSide.HOSTILE),
                    "a different object carrying the same UUID is refused instead of taking the wait over");
        }
        check(battle.fixtureSplitPending() == 1,
                "so exactly one wait is still owed: " + battle.fixtureSplitPending());
        check(battle.fixtureSplitWaiting().contains(owed) && battle.fixtureSplitWaiting().size() == 1,
                "and it is still the original body's: " + battle.fixtureSplitWaiting());
        check(battle.fixtureSplitBorn() == bornMark, "the substitute produced no birth record");
        await(0, () -> watchSubstituteSplit(0, bornMark));
    }

    private static void watchSubstituteSplit(int guard, int bornMark) {
        if (guard > 200) {
            check(false, "the original split never settled after the substitute was refused: pending="
                    + battle.fixtureSplitPending() + " born=" + (battle.fixtureSplitBorn() - bornMark));
            return;
        }
        if (battle.splitPending()) {
            await(0, () -> watchSubstituteSplit(guard + 1, bornMark));
            return;
        }
        int born = battle.fixtureSplitBorn() - bornMark;
        List<Slime> children = allFamily(substituteParent).stream()
                .filter(child -> battle.fixtureEnemy(child.getUUID()) != null).toList();
        check(born >= 2 && born <= 4,
                "the original wait still produced vanilla's own children: " + born);
        check(children.size() == born && battle.fixtureSplitAdmitted() > 0,
                "each a world-registered, admitted body: " + children.size() + " of " + born);
        check(battle.fixtureSplitPending() == 0, "and the wait closed normally, without an exception");
        purgeAll();
    }

    /** Every live body of this family in the fixture's own area, including ones the exclusion moved out. */
    private static List<Slime> allFamily(Entity except) {
        return level.getEntitiesOfClass(Slime.class,
                new net.minecraft.world.phys.AABB(
                        new Vec3(origin.getX() - 96.0D, origin.getY() - 48.0D, origin.getZ() - 96.0D),
                        new Vec3(origin.getX() + 96.0D, origin.getY() + 56.0D, origin.getZ() + 96.0D)),
                slime -> slime != except && slime.isAlive() && !slime.isRemoved());
    }

    /** Every live slime near one position, one named body excluded. */
    private static List<Slime> slimesNear(Vec3 at, double radius, Entity except) {
        return level.getEntitiesOfClass(Slime.class, new net.minecraft.world.phys.AABB(at, at).inflate(radius),
                slime -> slime != except && slime.isAlive() && !slime.isRemoved() && slime.level() == level);
    }

    /** Experience orbs standing on the measuring platform; used only to prove births add none. */
    private static int countOrbs() {
        return level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,
                new net.minecraft.world.phys.AABB(
                        new Vec3(origin.getX() - 24.0D, origin.getY() - 8.0D, origin.getZ() - 16.0D),
                        new Vec3(origin.getX() + 24.0D, origin.getY() + 12.0D, origin.getZ() + 16.0D))).size();
    }

    /**
     * Fixture lever: refuse the next real split event of the named parent, or keep only one of its children.
     *
     * <p>Exactly what any third-party listener may do, and the reason the lifecycle must believe the event
     * rather than its own count.</p>
     */
    @SubscribeEvent
    public static void onSplit(net.neoforged.neoforge.event.entity.living.MobSplitEvent event) {
        if (!enabled) return;
        UUID parent = event.getParent() == null ? null : event.getParent().getUUID();
        if (parent == null) return;
        if (parent.equals(splitCancelParent)) {
            event.setCanceled(true);
            splitCancelled++;
            return;
        }
        if (parent.equals(splitTrimParent) && event.getChildren().size() > 1) {
            while (event.getChildren().size() > 1) event.getChildren().remove(event.getChildren().size() - 1);
            splitTrimmed++;
        }
        if (parent.equals(trimToNothing)) {
            // A perfectly normal release that happens to keep no child at all.
            event.getChildren().clear();
            splitTrimmed++;
        }
        if (parent.equals(duplicateParentId) && event.getChildren().size() >= 2) {
            // What any third-party listener may do, and the reason a UUID is not an identity: two distinct
            // children of the final list end up sharing one UUID, so only one of them can be registered.
            Slime first = null;
            for (Mob child : event.getChildren()) {
                if (!(child instanceof Slime slime)) continue;
                if (first == null) {
                    first = slime;
                } else if (slime != first) {
                    slime.setUUID(first.getUUID());
                    duplicateSecondChild = slime;
                }
            }
            duplicateFirstChild = first;
        }
    }

    /** Fixture lever: refuse the joins a split is producing right now, exactly as the world may. */
    @SubscribeEvent
    public static void onSplitJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!enabled || refuseSplitJoins <= 0) return;
        if (!(event.getEntity() instanceof Slime)) return;
        if (SlimeSplitLifecycle.scopeDepth() == 0) return;
        event.setCanceled(true);
        refuseSplitJoins--;
        refusedSplitJoins++;
    }

    // ------------------------------------------------------------------ harness

    private static void setup(MinecraftServer server) {
        serverRef = server;
        BattleManager.setAutoTrigger(false);
        server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setDayTime(18000);
        BlockPos spawn = level.getSharedSpawnPos();
        origin = new BlockPos(spawn.getX() + 320, 80, spawn.getZ() + 320);
        for (int x = (origin.getX() - 16) >> 4; x <= (origin.getX() + 16) >> 4; x++) {
            for (int z = (origin.getZ() - 16) >> 4; z <= (origin.getZ() + 16) >> 4; z++) {
                level.setChunkForced(x, z, true);
            }
        }
        for (int x = -20; x <= 20; x++) {
            for (int z = -12; z <= 12; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 10; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        GameProfile profile = new GameProfile(
                UUID.nameUUIDFromBytes("tactical-slime-fixture".getBytes(StandardCharsets.UTF_8)), "SlimeOwner");
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
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        owner.setHealth(1000);
        owner.setGameMode(GameType.SURVIVAL);
        level.addNewPlayer(owner);
        damageReadyTick = ticks + 200;

        bigSlime = spawn(EntityType.SLIME, -4, 0, 0, 4);
        midSlime = spawn(EntityType.SLIME, -12, 0, 2, 2);
        tinySlime = spawn(EntityType.SLIME, -6, 0, 2, 1);
        smallMagma = spawn(EntityType.MAGMA_CUBE, -8, 0, 0, 2);
        tinyMagma = spawn(EntityType.MAGMA_CUBE, -14, 0, 0, 1);
        bigMagma = spawn(EntityType.MAGMA_CUBE, -10, 0, 0, 4);
        zombie = spawn(EntityType.ZOMBIE, 3, 0, 5, 1);
        bystander = spawn(EntityType.COW, 7, 0, 7, 1);
    }

    /**
     * The first precondition of this fixture that its own world does not satisfy yet, or {@code null} when the
     * world is real enough to ask the stages' questions.
     *
     * <p>Both halves are read from the live server, not assumed: the chunk has to be entity loaded and entity
     * ticking (that is what puts a body into the index every {@code getEntities} query reads), and every fixture
     * body has to be findable through that same query at its own position. The floor cell is read back too, so a
     * world that silently refused the fixture's blocks is reported as such instead of as a geometry failure.</p>
     */
    private static String worldNotReady() {
        if (level == null || origin == null) return "the fixture has no level or origin yet";
        int listed = 0;
        int missing = 0;
        String first = null;
        StringBuilder all = new StringBuilder();
        for (Entity body : new ArrayList<>(spawned)) {
            if (body.isRemoved()) continue;
            BlockPos at = body.blockPosition();
            long chunk = net.minecraft.world.level.ChunkPos.asLong(at);
            if (!level.areEntitiesLoaded(chunk)) {
                return "the chunk " + new net.minecraft.world.level.ChunkPos(at) + " of "
                        + body.getType().toShortString() + " #" + body.getId() + " is not entity loaded";
            }
            if (!level.isPositionEntityTicking(at)) {
                return "the chunk " + new net.minecraft.world.level.ChunkPos(at) + " of "
                        + body.getType().toShortString() + " #" + body.getId() + " is not entity ticking";
            }
            // The identity predicate is what proves the same object is listed; the first argument of this query is
            // the body every caller wants excluded, so it must not be the body being looked for.
            boolean found = !level.getEntities((Entity) null, body.getBoundingBox().inflate(0.01D),
                    other -> other == body).isEmpty();
            if (found) {
                listed++;
            } else {
                missing++;
                if (first == null) {
                    first = body.getType().toShortString() + " #" + body.getId() + " at " + body.position()
                            + " (removed=" + body.isRemoved() + ", added=" + body.isAddedToLevel()
                            + ", by id=" + (level.getEntity(body.getId()) == body) + ")";
                }
                all.append(' ').append(body.getId());
            }
        }
        if (missing > 0) {
            List<Entity> around = level.getEntities((Entity) null,
                    net.minecraft.world.phys.AABB.unitCubeFromLowerCorner(Vec3.atLowerCornerOf(origin))
                            .inflate(24.0D, 12.0D, 24.0D),
                    e -> true);
            return "the world lists " + listed + " of " + (listed + missing) + " fixture bodies; first missing "
                    + first + "; missing ids" + all + "; entities the index holds in the fixture area: "
                    + around.size() + " " + around.stream().map(e -> e.getType().toShortString() + "#" + e.getId())
                    .toList();
        }
        BlockPos floor = origin.offset(0, -1, 0);
        if (!level.getBlockState(floor).is(Blocks.STONE)) {
            return "the fixture floor at " + floor.toShortString() + " does not read back as stone";
        }
        if (!level.getBlockState(origin).isAir()) {
            return "the cell above the fixture floor at " + origin.toShortString() + " is not air";
        }
        return null;
    }

    /**
     * Spawn one native body with an explicit size.
     *
     * <p>The size is set before the entity ever joins the world: a summon with NBT does not run
     * {@code finalizeSpawn}, and the native {@code setSize(size, true)} is the only way to give the body its real
     * dimensions, health and attack value. Nothing here is production behaviour - it is a fixture precondition.
     * </p>
     */
    private static <T extends Entity> T spawn(EntityType<T> type, int dx, int dy, int dz, int size) {
        return spawn(type, dx, dy, dz, size, true, true);
    }

    /**
     * The same, with the two borrowed control flags set explicitly.
     *
     * <p>Every other stage wants the flags an admitted unit holds anyway, but a stage that proves the original
     * flags are given back needs bodies that really started as {@code false} - a fixture that only ever spawns
     * {@code true} can never show that a false original comes back.</p>
     */
    private static <T extends Entity> T spawn(EntityType<T> type, int dx, int dy, int dz, int size,
                                              boolean noAi, boolean persistent) {
        T entity = type.create(level);
        check(entity != null, "the native " + type.toShortString() + " was created");
        entity.setPos(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
        if (entity instanceof Slime slime) slime.setSize(size, true);
        if (entity instanceof Mob mob) {
            mob.setNoAi(noAi);
            if (persistent) mob.setPersistenceRequired();
            else ((com.tactical.neoforge.mixin.BattleMobPersistenceAccess) mob)
                    .tactical$persistenceRequired(false);
        }
        level.addFreshEntity(entity);
        spawned.add(entity);
        return entity;
    }

    private static Vec3 feet(int dx, int dy, int dz) {
        return new Vec3(origin.getX() + dx + 0.5D, origin.getY() + dy, origin.getZ() + dz + 0.5D);
    }

    private static GridPos cell(int dx, int dy, int dz) {
        return new GridPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
    }

    private static void block(int dx, int dy, int dz, Block block) {
        level.setBlock(new BlockPos(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz),
                block.defaultBlockState(), 2);
    }

    /** Remove every fixture body except the two the current stage names; foreign hostiles are never admitted. */
    /**
     * Put the measuring area back to a bare platform.
     *
     * <p>Every stage builds what it needs and nothing it does not: a block left over from an earlier stage would
     * otherwise sit inside a body the next stage is measuring and turn a product answer into a fixture accident.
     * </p>
     */
    private static void clearArena() {
        for (int x = -20; x <= 20; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 0; y < 6; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
    }

    /** Remove every fixture body and every block: the stages that measure one body alone start from this. */
    private static void purgeAll() {
        if (battle != null && !battle.finished()) battle.finish("fixture stage change");
        for (Entity entity : List.copyOf(spawned)) {
            if (entity instanceof TacticalSlimeAccess access) access.tactical$setPoseHandler(null);
            entity.discard();
        }
        spawned.clear();
        // Bodies vanilla created during a native split were never in the fixture's own list, so a stage that
        // measures a fresh family must clean the whole area and not just the ids it spawned itself.
        purgeFamilyBodies();
        purgeOrbs();
        clearArena();
    }

    /** Every slime and magma cube standing in the measuring area, the split's own children included. */
    private static void purgeFamilyBodies() {
        for (Slime slime : level.getEntitiesOfClass(Slime.class,
                new net.minecraft.world.phys.AABB(
                        new Vec3(origin.getX() - 44.0D, origin.getY() - 24.0D, origin.getZ() - 44.0D),
                        new Vec3(origin.getX() + 44.0D, origin.getY() + 32.0D, origin.getZ() + 44.0D)))) {
            if (slime instanceof TacticalSlimeAccess access) access.tactical$setPoseHandler(null);
            slime.discard();
        }
    }

    /**
     * Every experience orb lying in the measuring area.
     *
     * <p>An orb dropped by an earlier stage - or left in this throwaway world by an earlier run, since the fixture
     * reuses its own saved world - keeps drifting around the arena and would silently move the "a birth drops no
     * experience" baseline. They are cleared with the bodies at a stage boundary, never inside a stage, so a stage
     * still measures every orb its own action produced.</p>
     */
    private static void purgeOrbs() {
        for (Entity orb : level.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class,
                new net.minecraft.world.phys.AABB(
                        new Vec3(origin.getX() - 24.0D, origin.getY() - 8.0D, origin.getZ() - 16.0D),
                        new Vec3(origin.getX() + 24.0D, origin.getY() + 12.0D, origin.getZ() + 16.0D)))) {
            orb.discard();
        }
    }

    private static void discardAll() {
        purgeAll();
        for (Entity entity : List.copyOf(spawned)) {
            if (entity instanceof TacticalSlimeAccess access) access.tactical$setPoseHandler(null);
            entity.discard();
        }
        spawned.clear();
        bigSlime = spawn(EntityType.SLIME, -4, 0, 0, 4);
        midSlime = spawn(EntityType.SLIME, -12, 0, 2, 2);
        tinySlime = spawn(EntityType.SLIME, -6, 0, 2, 1);
        smallMagma = spawn(EntityType.MAGMA_CUBE, -8, 0, 0, 2);
        tinyMagma = spawn(EntityType.MAGMA_CUBE, -14, 0, 0, 1);
        bigMagma = spawn(EntityType.MAGMA_CUBE, -10, 0, 0, 4);
        zombie = spawn(EntityType.ZOMBIE, 3, 0, 5, 1);
        // Out of the measuring corridor: a hop really may not pass through another body, and the geometry
        // stages would only be measuring that rule by accident if the owner stood in the arc.
        owner.setPos(origin.getX() + 14.5D, origin.getY(), origin.getZ() + 8.5D);
        owner.setHealth(owner.getMaxHealth());
    }

    private static void purgeForeignMonsters() {
        for (Entity entity : level.getEntitiesOfClass(Mob.class,
                new net.minecraft.world.phys.AABB(origin).inflate(48),
                mob -> mob instanceof net.minecraft.world.entity.monster.Enemy && !spawned.contains(mob))) {
            entity.discard();
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            String detail = wire == null || wire.log.isEmpty() ? "" : " (journal: " + wire.log + ")";
            Tactical.LOGGER.error("[Tactical] SLIME [{}] FAIL {}{}", checks + 1, label, detail);
            throw new AssertionError(label + detail);
        }
        checks++;
        Tactical.LOGGER.info("[Tactical] SLIME [{}] PASS {}", checks, label);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        for (Entity entity : spawned) {
            if (entity instanceof TacticalSlimeAccess access) access.tactical$setPoseHandler(null);
            entity.discard();
        }
        spawned.clear();
        if (owner != null && !owner.isRemoved()) {
            level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
        if (wire != null && wire.channel != null) wire.channel.finishAndReleaseAll();
        BattleEffects.clear();
        cancelNativeContact = false;
        server.halt(false);
    }

    /** The journal tail, so a refused action names its own reason in a failure message. */
    private static final class Wire extends Connection {
        io.netty.channel.embedded.EmbeddedChannel channel;
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
