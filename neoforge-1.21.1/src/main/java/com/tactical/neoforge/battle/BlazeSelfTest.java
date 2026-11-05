package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.TacticalConfig;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Disposable-world dedicated-server fixture for the blaze salvo: {@code -Dtactical.selftest=blaze}.
 *
 * <p>Every scenario uses a real {@link Blaze}, a real encounter and the real native fireball path, then reads the
 * authoritative server result: the vanilla charge bit with no wall-clock shortcut, three real
 * {@link SmallFireball}s six ticks apart for one paid action, a turn held until the last projectile settles, a
 * salvo that charges nothing when no fireball really joined, a salvo that pays once and refunds nothing when a
 * later shot fails, a target that leaves and takes the rest of the volley with it, bystander bodies that are left
 * untouched because the collision was refused before vanilla ran, terrain that never catches fire, and the
 * hand-back that clears the charge bit and every mark.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BlazeSelfTest {

    private static boolean enabled;
    private static int ticks;
    private static int stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer stranger;
    private static Blaze blaze;
    private static final List<Cow> plugs = new ArrayList<>();
    private static ServerBattle battle;
    private static final List<BlockPos> placed = new ArrayList<>();
    private static final List<BlockPos> forced = new ArrayList<>();

    /** Native fireball joins of the actor under test, in tick order. */
    private static final List<Integer> shotTicks = new ArrayList<>();
    private static int joinAttempts;
    private static int cancelledJoins;
    private static int thrownJoins;
    private static boolean cancelAllJoins;
    private static boolean throwOnSecond;
    private static boolean leaveMidVolley;
    private static int blockImpacts;
    private static int entityImpacts;
    private static int refusedEntityImpacts;
    private static int turnStartAp = -1;
    private static int lowestAp = Integer.MAX_VALUE;
    private static int ownerApDuringEnemyTurn = -1;
    private static String ownerHandBeforeEnemyTurn = "";
    private static boolean chargeObserved;
    private static boolean busyHeldTurn;
    private static int volleyStartTick = -1;
    private static int volleyEndTick = -1;
    private static int handOverAttempts;
    private static int turnStartApOwner = -1;
    /** Real fireball damage events that reached the member, and what one of them really was. */
    private static int fireballHits;
    private static float fireballDamage = -1.0F;
    private static float fireballApplied = -1.0F;
    private static boolean fireballSourceOwned;
    private static int fireTicksAfterHit = -1;
    private static int fireTicksAtFirstOwnStart = -1;
    private static int meleeHits;
    private static float meleeDamage = -1.0F;
    private static int burnPulses;
    private static boolean memberHealthBeforeMelee;
    private static float memberHealthBefore;
    /** Fixture-only third-party listeners: a later priority may cancel an impact the product judged legal. */
    private static boolean cancelLegalImpacts;
    private static boolean cancelEveryImpact;
    private static int cancelledLegalImpacts;
    private static int lateCancelledImpacts;
    private static boolean admissionChargeCleared;
    /**
     * Scenarios that need one real physical collision ask the blaze for more own turns instead of hoping a single
     * vanilla spread lands where the assertion needs it. Bounded, so the fixture still terminates.
     */
    private static int evidenceTurns;
    private static java.util.function.BooleanSupplier needMoreEvidence;
    private static final List<Cow> legalPlugs = new ArrayList<>();
    private static Blaze sameSideBlaze;
    /** A body that is not a LivingEntity, and one that belongs to another encounter. */
    private static net.minecraft.world.entity.Entity nonLivingBody;
    private static LivingEntity otherEncounterUnit;
    private static int nonLivingRefusals;
    private static int otherEncounterRefusals;
    private static int configuredPrice = -1;

    private BlazeSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "blaze".equals(System.getProperty("tactical.selftest"));
    }

    /** Real joins of the actor's fireballs, plus the fixture-only cancellations and injected failure. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!enabled || level == null || event.getLevel() != level) return;
        if (!(event.getEntity() instanceof SmallFireball fireball)) return;
        if (blaze == null || fireball.getOwner() != blaze) return;
        joinAttempts++;
        boolean cancel = cancelAllJoins;
        if (throwOnSecond && joinAttempts >= 2) {
            thrownJoins++;
            throw new IllegalStateException("fixture: native fireball join failed for a later salvo shot");
        }
        if (cancel) {
            cancelledJoins++;
            event.setCanceled(true);
            return;
        }
        shotTicks.add(ticks);
    }

    /**
     * Observational only: the real collision entry.
     *
     * <p>{@code receiveCanceled} is required here: the product's own rule cancels the impact, and a cancelled
     * event is not delivered to a listener that does not ask for it. Without this the fixture would read "no body
     * was ever touched" from a rule that is working exactly as intended.</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onImpact(ProjectileImpactEvent event) {
        if (!enabled || level == null) return;
        if (!(event.getProjectile() instanceof SmallFireball fireball)) return;
        if (blaze == null || fireball.getOwner() != blaze) return;
        HitResult hit = event.getRayTraceResult();
        if (hit == null) return;
        if (hit.getType() == HitResult.Type.BLOCK) {
            blockImpacts++;
            Tactical.LOGGER.info("[Tactical] blaze fixture block impact at {} canceled={}",
                    hit.getLocation(), event.isCanceled());
            if (cancelEveryImpact) {
                lateCancelledImpacts++;
                event.setCanceled(true);
                return;
            }
            require(event.isCanceled(), "a blaze fireball that really hit part of the world was refused");
            return;
        }
        if (hit.getType() == HitResult.Type.ENTITY) {
            entityImpacts++;
            Tactical.LOGGER.info("[Tactical] blaze fixture entity impact at {} on {}",
                    hit.getLocation(), hit instanceof EntityHitResult e ? e.getEntity().getType().toShortString() + "@"
                            + e.getEntity().blockPosition() : "?");
            boolean legal = battle != null && hit instanceof EntityHitResult entityHit
                    && battle.acceptsFireball(blaze.getUUID(), entityHit.getEntity());
            if (legal && cancelLegalImpacts) {
                // An unrelated listener at a later priority cancels an impact the product itself judged legal.
                // Vanilla keeps such a projectile flying, so the same fireball must stay under this encounter's
                // rule for its next collision - that is the property under test.
                lateCancelledImpacts++;
                event.setCanceled(true);
                return;
            }
            if (cancelEveryImpact) {
                // Nothing consumes the projectile: it flies until the tracker's own flight cap reclaims it.
                lateCancelledImpacts++;
                event.setCanceled(true);
                return;
            }
            if (!legal) {
                refusedEntityImpacts++;
                Entity hitBody = hit instanceof EntityHitResult entityHitResult ? entityHitResult.getEntity() : null;
                if (hitBody != null && !(hitBody instanceof LivingEntity)) nonLivingRefusals++;
                if (hitBody == otherEncounterUnit) otherEncounterRefusals++;
                require(event.isCanceled(),
                        "a forbidden body was refused at the collision entry, not repaired afterwards");
            }
        }
    }

    /** Authoritative damage the member really took, and from which native source. */
    @SubscribeEvent
    public static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!enabled || owner == null || blaze == null || event.getEntity() != owner) return;
        var source = event.getSource();
        if (source.getDirectEntity() instanceof SmallFireball fireball) {
            if (fireball.getOwner() != blaze) return;
            fireballHits++;
            // The ruleset's own positional/direction multipliers may reduce the final number; the native raw
            // direct fireball damage is what this fixture pins, and it is read from the pre-modifier value.
            fireballDamage = event.getOriginalDamage();
            fireballApplied = event.getNewDamage();
            fireballSourceOwned = source.getEntity() == blaze;
            fireTicksAfterHit = owner.getRemainingFireTicks();
            return;
        }
        // A vanilla mob attack puts the attacker in both the causing and the direct slot; a fireball does not.
        if (source.getEntity() == blaze && (source.getDirectEntity() == null || source.getDirectEntity() == blaze)) {
            meleeHits++;
            meleeDamage = event.getNewDamage();
        }
        if (source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && source.getEntity() == null) burnPulses++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        ticks++;
        try {
            require(ticks < 12_000, "blaze fixture completion deadline");
            switch (stage) {
                case 0 -> {
                    setup(event.getServer());
                    stage = 1;
                }
                case 1 -> {
                    // Group 9: nothing fires during somebody else's turn, and NoAI really stopped vanilla.
                    if (ticks < 120) return;
                    require(joinAttempts == 0, "a blaze never fires during the player's own turn");
                    beginCleanSalvo();
                    stage = 2;
                }
                case 2 -> {
                    if (advanceBlazeTurn()) return;
                    checkCleanSalvo();
                    nextScenario();
                    beginCancelledSalvo();
                    stage = 3;
                }
                case 3 -> {
                    if (advanceBlazeTurn()) return;
                    checkCancelledSalvo();
                    nextScenario();
                    beginLaterShotFailure();
                    stage = 4;
                }
                case 4 -> {
                    if (advanceBlazeTurn()) return;
                    checkLaterShotFailure();
                    nextScenario();
                    beginTargetLeaves();
                    stage = 5;
                }
                case 5 -> {
                    if (advanceBlazeTurn()) return;
                    checkTargetLeaves();
                    nextScenario();
                    beginBystanderCorridor();
                    stage = 6;
                }
                case 6 -> {
                    if (advanceBlazeTurn()) return;
                    checkBystanderCorridor();
                    nextScenario();
                    beginNarrowCorridor();
                    stage = 7;
                }
                case 7 -> {
                    if (advanceBlazeTurn()) return;
                    checkTerrain();
                    nextScenario();
                    beginPriceRamp();
                    stage = 8;
                }
                case 8 -> {
                    if (advanceBlazeTurn()) return;
                    checkFirstSalvoPrice();
                    battle.endPlayerTurn();
                    stage = 9;
                }
                case 9 -> {
                    if (advanceBlazeTurn()) return;
                    checkSecondSalvoPrice();
                    nextScenario();
                    beginLegalThenIllegal();
                    stage = 10;
                }
                case 10 -> {
                    if (advanceBlazeTurn()) return;
                    checkLegalThenIllegal();
                    nextScenario();
                    beginFlyingTimeout();
                    stage = 11;
                }
                case 11 -> {
                    if (advanceBlazeTurn()) return;
                    checkFlyingTimeout();
                    nextScenario();
                    beginChargedAdmission();
                    stage = 12;
                }
                case 12 -> {
                    if (advanceBlazeTurn()) return;
                    checkChargedAdmission();
                    nextScenario();
                    beginMelee();
                    stage = 13;
                }
                case 13 -> {
                    if (advanceBlazeTurn()) return;
                    checkMelee();
                    nextScenario();
                    beginBurning();
                    stage = 14;
                }
                case 14 -> {
                    if (advanceBlazeTurn()) return;
                    checkBurning();
                    nextScenario();
                    beginSameSide();
                    stage = 15;
                }
                case 15 -> {
                    if (advanceBlazeTurn()) return;
                    checkSameSide();
                    nextScenario();
                    beginNonLivingBody();
                    stage = 16;
                }
                case 16 -> {
                    if (advanceBlazeTurn()) return;
                    checkNonLivingBody();
                    nextScenario();
                    beginOtherEncounter();
                    stage = 17;
                }
                case 17 -> {
                    if (advanceBlazeTurn()) return;
                    checkOtherEncounter();
                    reportAndFinish(event.getServer());
                }
                default -> { }
            }
        } catch (AssertionError failure) {
            enabled = false;
            Tactical.LOGGER.error("[Tactical] BLAZE SELFTEST FAILED at stage {} tick {}: {}", stage, ticks,
                    failure.getMessage(), failure);
            finish(event.getServer());
        } catch (RuntimeException failure) {
            enabled = false;
            Tactical.LOGGER.error("[Tactical] BLAZE SELFTEST ERROR at stage {} tick {}", stage, ticks, failure);
            finish(event.getServer());
        }
    }

    // ------------------------------------------------------------------
    // scenarios
    // ------------------------------------------------------------------

    /** Groups 2/3: vanilla charge, three real fireballs, one price, the turn held until they settle. */
    private static void beginCleanSalvo() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        ownerHandBeforeEnemyTurn = owner.getMainHandItem().copy().toString();
        battle.endPlayerTurn();
    }

    private static void checkCleanSalvo() {
        int price = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        require(chargeObserved, "the vanilla charge bit was really set for at least one tick");
        require(!blaze.isOnFire(), "the charge bit is cleared when the salvo hands the turn back");
        require(shotTicks.size() == BlazeVolley.MAX_SHOTS,
                "three real fireballs were created, got " + shotTicks.size());
        require(thrownJoins == 0 && cancelledJoins == 0, "no join was cancelled in this scenario");
        for (int index = 1; index < shotTicks.size(); index++) {
            int gap = shotTicks.get(index) - shotTicks.get(index - 1);
            require(gap == BlazeVolley.SHOT_INTERVAL_TICKS,
                    "fireballs are six ticks apart, gap " + index + " was " + gap);
        }
        require(turnStartAp - lowestAp == price,
                "one salvo is one paid action of " + price + " AP (start " + turnStartAp + ", lowest "
                        + lowestAp + ")");
        require(ownerApDuringEnemyTurn == turnStartApOwner,
                "the salvo never touches the member's AP: " + turnStartApOwner + " -> " + ownerApDuringEnemyTurn);
        require(ownerHandBeforeEnemyTurn.equals(owner.getMainHandItem().toString()),
                "the salvo never touches the member's inventory");
        require(volleyStartTick > 0 && volleyEndTick > volleyStartTick, "the salvo really ran for a window");
        require(volleyEndTick - volleyStartTick <= BlazeVolley.HARD_DEADLINE_TICKS,
                "a salvo never holds the turn longer than " + BlazeVolley.HARD_DEADLINE_TICKS + " ticks, was "
                        + (volleyEndTick - volleyStartTick));
        require(busyHeldTurn, "the encounter stayed busy while real fireballs were still in flight");
        require(battle.pendingProjectiles() == 0, "every fireball settled before the turn came back");
        require(BlazeFireballs.markedFor(battle) == 0, "no marked fireball outlives its volley");
        require(journalHas(BattleText.KEY_ATTACK_ENEMY_FIREBALL), "the salvo is recorded as one paid attack");
        require(journalHas(BattleText.KEY_ENEMY_FIREBALL_CHARGE), "the vanilla charge is recorded once");
        require(!journalHas(BattleText.KEY_ENEMY_NO_FIREBALL), "no refund row was published for a real salvo");
    }

    /** Group 4 (first half): every join cancelled - no fireball, no charge, no residue, the turn returns. */
    private static void beginCancelledSalvo() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        cancelAllJoins = true;
        battle.endPlayerTurn();
    }

    private static void checkCancelledSalvo() {
        require(cancelledJoins > 0, "the fixture really cancelled the native fireball joins");
        require(shotTicks.isEmpty(), "no fireball reached the world, got " + shotTicks.size());
        require(turnStartAp == battle.actorAp(blaze),
                "a salvo that produced no fireball charges nothing (start " + turnStartAp + ", now "
                        + battle.actorAp(blaze) + ")");
        require(!blaze.isOnFire(), "the refused salvo clears its charge bit");
        require(BlazeFireballs.markedFor(battle) == 0, "a cancelled join leaves no mark behind");
        require(journalHas(BattleText.KEY_ENEMY_NO_FIREBALL), "the zero-fireball refusal is published");
    }

    /** Group 4 (second half): the first shot is real, the next join throws - pay once, refund nothing. */
    private static void beginLaterShotFailure() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        throwOnSecond = true;
        battle.endPlayerTurn();
    }

    private static void checkLaterShotFailure() {
        int price = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        require(thrownJoins >= 1, "a later join really failed");
        require(shotTicks.size() == 1, "only the first fireball exists, got " + shotTicks.size());
        require(turnStartAp - lowestAp == price,
                "a salvo that really fired keeps its frozen " + price + " (start " + turnStartAp + ", lowest "
                        + lowestAp + ")");
        require(!blaze.isOnFire(), "the failed salvo still clears its charge bit");
        require(BlazeFireballs.markedFor(battle) == 0, "the surviving fireball settled and was forgotten");
    }

    /** Group 5: the target leaves in the middle of the volley - the rest stops, nothing refunds. */
    private static void beginTargetLeaves() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        leaveMidVolley = true;
        battle.endPlayerTurn();
    }

    private static void checkTargetLeaves() {
        int price = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        require(shotTicks.size() >= 1 && shotTicks.size() < BlazeVolley.MAX_SHOTS,
                "the volley stopped early instead of firing at nobody, shots " + shotTicks.size());
        require(turnStartAp - lowestAp == price, "an already-fired salvo is not refunded");
        require(!blaze.isOnFire(), "the stopped volley clears its charge bit");
        require(BlazeFireballs.markedFor(battle) == 0, "the stopped volley leaves no mark behind");
        require(battle.finished() || battle.isPlayerTurnNow(), "the turn came back in bounded time");
    }

    /** Group 6: a plugged corridor - a real fireball reaches a real bystander and is refused there. */
    private static void beginBystanderCorridor() {
        buildCorridor(1);
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        for (int dz = -1; dz <= 1; dz++) {
            Cow cow = EntityType.COW.create(level);
            require(cow != null, "cow exists in this version");
            cow.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + dz + 0.5D);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
            cow.setHealth(100);
            require(level.addFreshEntity(cow), "the bystander is really in the world");
            cow.setNoAi(true);
            plugs.add(cow);
        }
        startBattle();
        battle.endPlayerTurn();
    }

    private static void checkBystanderCorridor() {
        require(!plugs.isEmpty(), "the corridor really had bystanders");
        for (Cow cow : plugs) {
            require(cow.getHealth() == cow.getMaxHealth(),
                    "a bystander body is never hurt by this encounter's fireballs, health " + cow.getHealth());
            // Vanilla's own "not burning" value is <= 0 (-1 before the first ignition), never a live countdown.
            require(cow.getRemainingFireTicks() <= 0,
                    "a bystander body is never ignited by this encounter's fireballs, fire ticks "
                            + cow.getRemainingFireTicks());
        }
        require(refusedEntityImpacts > 0, "at least one real fireball really reached a forbidden body"
                + diagnostics());
        require(BlazeFireballs.markedFor(battle) == 0, "every marked fireball was consumed by its own rule");
        require(noSpawnedFire(), "a refused collision never sets anything on fire");
    }

    /** Group 7: a one-wide corridor - the fireball really hits the world, and nothing catches fire. */
    private static void beginNarrowCorridor() {
        buildNarrowCorridor();
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        needMoreEvidence = () -> blockImpacts >= 1;
        evidenceTurns = 4;
        battle.endPlayerTurn();
    }

    private static void checkTerrain() {
        require(blockImpacts > 0, "at least one real fireball really hit part of the world" + diagnostics());
        require(level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING),
                "this ruleset never rewrites the mobGriefing gamerule");
        require(noSpawnedFire(), "no fire block exists anywhere this salvo could have set one");
        require(BlazeFireballs.markedFor(battle) == 0, "no marked fireball survives the salvo");
    }

    /**
     * Group 3: the price really comes from the config, and it is quoted per own turn.
     *
     * <p>The configured value is changed for this scenario only, so "the number the player reads in the config is
     * the number the server spends" is a measurement and not an assumption. The second salvo proves the other
     * half: an actor with one paid action per own turn never inherits the previous turn's surcharge.</p>
     */
    private static void beginPriceRamp() {
        configuredPrice = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        TacticalConfig.INSTANCE.blazeVolleyApCost.set(configuredPrice + 10);
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        battle.endPlayerTurn();
    }

    private static void checkFirstSalvoPrice() {
        int price = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        require(price == configuredPrice + 10, "the fixture really changed the configured salvo price");
        require(shotTicks.size() == BlazeVolley.MAX_SHOTS, "the first salvo really fired three fireballs" + diagnostics());
        require(turnStartAp - lowestAp == price,
                "the salvo costs the configured " + price + " (start " + turnStartAp + ", lowest " + lowestAp + ")"
                        + diagnostics());
        require(com.tactical.core.combat.ApCalculator.paidActionCost(price, 0) == price,
                "the first paid action of an own turn is quoted at the bare base price");
        resetTurnObservations();
    }

    private static void checkSecondSalvoPrice() {
        int price = TacticalConfig.INSTANCE.blazeVolleyApCost.get();
        require(shotTicks.size() == BlazeVolley.MAX_SHOTS, "the second salvo really fired three fireballs" + diagnostics());
        require(turnStartAp - lowestAp == price,
                "the next own turn is quoted the same base " + price + " again, with no surcharge carried over "
                        + "(start " + turnStartAp + ", lowest " + lowestAp + ")" + diagnostics());
        TacticalConfig.INSTANCE.blazeVolleyApCost.set(configuredPrice);
    }

    /**
     * A-R1-1: a legal impact is cancelled by an unrelated later listener, and the same real fireball must stay
     * under this encounter's rule for its next collision.
     *
     * <p>Licensed physical precondition: three friendly participants fill the corridor cross section, so the first
     * collision really is a legal one, and the three bodies behind the member are not participants. The spread seed
     * is fixed, so "first a legal body, then a forbidden one" is geometry and not a lucky draw.</p>
     */
    private static void beginLegalThenIllegal() {
        buildCorridor(1);
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        // The member is the legal body and he is pinned, so the first collision of a shot that stays in the
        // corridor is a legal one. The forbidden row stands 1.5 blocks behind him and fills the cross section, so a
        // fireball that survived the cancelled legal impact meets it almost immediately.
        owner.setPos(origin.getX() + 2.5D, origin.getY(), origin.getZ() + 0.5D);
        startBattle();
        for (int dz = -1; dz <= 1; dz++) plugs.add(cow(4.0D, dz));
        cancelLegalImpacts = true;
        needMoreEvidence = () -> lateCancelledImpacts >= 1 && refusedEntityImpacts >= 1;
        evidenceTurns = 8;
        battle.endPlayerTurn();
    }

    private static void checkLegalThenIllegal() {
        require(lateCancelledImpacts >= 1, "a legal impact was really cancelled by a later listener" + diagnostics());
        require(refusedEntityImpacts >= 1,
                "the same fireball was still refused when it reached a forbidden body" + diagnostics());
        require(fireballHits == 0, "no fireball damage landed while every legal impact was cancelled");
        for (Cow body : plugs) {
            require(body.getHealth() == body.getMaxHealth(),
                    "a forbidden body keeps its health: " + body.getHealth());
            require(body.getRemainingFireTicks() <= 0, "a forbidden body is never ignited");
        }
    }

    /** A-R1-2: nothing consumes the projectile, so the tracker's own flight cap reclaims it and the mark goes too. */
    private static void beginFlyingTimeout() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        startBattle();
        cancelEveryImpact = true;
        battle.endPlayerTurn();
    }

    private static void checkFlyingTimeout() {
        require(lateCancelledImpacts >= 1, "impacts were really cancelled, so the fireballs kept flying");
        require(fireballHits == 0, "a projectile whose impacts were all cancelled never hurt the member");
        require(battle.pendingProjectiles() == 0, "the tracker reclaimed every projectile" + diagnostics());
        require(BlazeFireballs.markedFor(battle) == 0, "no mark outlives the tracker's own removal" + diagnostics());
    }

    /** A-R1-4: vanilla can have the blaze charged at admission; this encounter must not inherit that pose. */
    private static void beginChargedAdmission() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        ((com.tactical.neoforge.battle.ai.TacticalBlazeAccess) blaze).tactical$setCharged(true);
        require(blaze.isOnFire(), "the fixture really left the entity charged before admission");
        startBattle();
        admissionChargeCleared = !blaze.isOnFire();
        require(admissionChargeCleared,
                "admission takes the vanilla charge bit out of the entity on the player's own turn");
        require(joinAttempts == 0, "a charged blaze fires nothing while somebody else holds the turn");
        battle.endPlayerTurn();
    }

    private static void checkChargedAdmission() {
        require(chargeObserved, "the only charge this encounter shows is its own, in its own turn");
        require(!blaze.isOnFire(), "the charge is gone again when the salvo hands the turn back");
    }

    /** A-R1-5: the native melee really runs, for its own price, and creates no fireball. */
    private static void beginMelee() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 7.5D, 0.5D);
        startBattle();
        memberHealthBefore = owner.getHealth();
        memberHealthBeforeMelee = true;
        battle.endPlayerTurn();
    }

    private static void checkMelee() {
        require(memberHealthBeforeMelee, "the melee scenario really recorded the member's health first");
        require(meleeHits >= 1, "the native melee really hit the member" + diagnostics()
                + " meleeRow=" + journalHas(BattleText.KEY_ATTACK_ENEMY_MELEE)
                + " moveRow=" + journalHas(BattleText.KEY_ENEMY_MOVE)
                + " volleyRow=" + journalHas(BattleText.KEY_ATTACK_ENEMY_FIREBALL)
                + " noFireballRow=" + journalHas(BattleText.KEY_ENEMY_NO_FIREBALL)
                + " health=" + owner.getHealth() + "/" + memberHealthBefore);
        require(meleeDamage > 0.0F, "the melee damage came from the entity's own attack, got " + meleeDamage);
        require(owner.getHealth() < memberHealthBefore,
                "the member really lost health: " + memberHealthBefore + " -> " + owner.getHealth());
        require(shotTicks.isEmpty(), "a native melee creates no fireball" + diagnostics());
    }

    /** A-R1-5: a real fireball really ignites, and the encounter's own turn clock settles it, not wall time. */
    private static void beginBurning() {
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        owner.setPos(origin.getX() + 4.5D, origin.getY(), origin.getZ() + 0.5D);
        startBattle();
        owner.clearFire();
        needMoreEvidence = () -> fireTicksAtFirstOwnStart >= 0 && owner.getRemainingFireTicks() <= 0;
        evidenceTurns = 6;
        battle.endPlayerTurn();
    }

    private static void checkBurning() {
        require(fireballHits >= 1, "a real fireball really hit the member" + diagnostics());
        require(fireballSourceOwned, "the damage source is this blaze's own fireball");
        // 5.0 is not observable here for a plain vanilla reason, not because of this mod: the damage type
        // minecraft:fireball declares "scaling": "when_caused_by_living_non_player", and this fixture runs on
        // difficulty=easy, where that vanilla scaling is amount/2 + 1 -> 3.5. The ruleset's positional damage does
        // NOT apply (PositionalCombat.eligible accepts real melee and arrows only). This fixture therefore pins
        // the native source, a real loss and a real ignition instead of claiming a fixed per-hit or salvo total.
        require(fireballDamage > 0.0F, "the fireball really removed health, got " + fireballDamage);
        require(owner.getHealth() < owner.getMaxHealth(), "the member really lost health" + diagnostics());
        Tactical.LOGGER.info("[Tactical] blaze fixture fireball damage source={} applied={} hits={}",
                fireballDamage, fireballApplied, fireballHits);
        require(fireTicksAfterHit > 0, "the hit really ignited the member, fire ticks " + fireTicksAfterHit);
        require(fireTicksAtFirstOwnStart == fireTicksAfterHit,
                "the burn did not age on wall-clock time before the member's own turn: " + fireTicksAfterHit
                        + " -> " + fireTicksAtFirstOwnStart);
        require(owner.getRemainingFireTicks() <= 0,
                "the encounter's existing own-start clock settled the ignition, fire ticks "
                        + owner.getRemainingFireTicks());
        // Recorded, not asserted as a ruleset constant: the existing clock owns how a partial cycle settles.
        Tactical.LOGGER.info("[Tactical] blaze fixture burning: afterHit={} atFirstOwnStart={} settled={} pulses={} "
                        + "hits={}", fireTicksAfterHit, fireTicksAtFirstOwnStart, owner.getRemainingFireTicks(),
                burnPulses, fireballHits);
    }

    /** A-R1-5: a same-side participant of this encounter is refused by real flight, not by a helper call. */
    private static void beginSameSide() {
        buildNarrowCorridor();
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        // A second blaze on the same side: admitted by the same encounter, never a legal target for it. It stands
        // in the one-wide corridor, so a shot that stays inside the corridor cannot miss it.
        sameSideBlaze = (Blaze) spawn(EntityType.BLAZE, 4.5D, 0.5D);
        startBattle();
        needMoreEvidence = () -> refusedEntityImpacts >= 1;
        evidenceTurns = 8;
        battle.endPlayerTurn();
    }

    private static void checkSameSide() {
        require(sameSideBlaze != null, "the same-side unit exists");
        require(battle.participants().contains(sameSideBlaze),
                "the same-side unit really is a participant of this encounter");
        require(refusedEntityImpacts >= 1,
                "a real fireball really reached the same-side unit and was refused" + diagnostics());
        require(sameSideBlaze.getHealth() == sameSideBlaze.getMaxHealth(),
                "a same-side unit keeps its health: " + sameSideBlaze.getHealth());
        require(sameSideBlaze.getRemainingFireTicks() <= 0, "a same-side unit is never ignited");
    }

    /**
     * A body that is really not a {@link LivingEntity}, and really collidable by a small fireball.
     *
     * <p>A vanilla boat passes {@code Entity#canBeHitByProjectile} (alive and pickable) and has no
     * {@code noPhysics}, which is what an end crystal cannot offer. It stands on a one-block pillar so its hitbox
     * covers the native flight line, inside a one-wide corridor so a shot cannot miss it.</p>
     */
    private static void beginNonLivingBody() {
        buildNarrowCorridor();
        set(origin.offset(4, 0, 0), true);
        blaze = (Blaze) spawn(EntityType.BLAZE, 0.5D, 0.5D);
        net.minecraft.world.entity.vehicle.Boat boat =
                (net.minecraft.world.entity.vehicle.Boat) EntityType.BOAT.create(level);
        require(boat != null, "boat exists in this version");
        boat.setPos(origin.getX() + 4.5D, origin.getY() + 1.0D, origin.getZ() + 0.5D);
        require(level.addFreshEntity(boat), "the non-living body is really in the world");
        require(boat.canBeHitByProjectile(), "the native path really allows a projectile to hit this body");
        nonLivingBody = boat;
        startBattle();
        needMoreEvidence = () -> nonLivingRefusals >= 1;
        evidenceTurns = 4;
        battle.endPlayerTurn();
    }

    private static void checkNonLivingBody() {
        require(nonLivingBody != null && !nonLivingBody.isRemoved(), "the non-living body still exists");
        require(!(nonLivingBody instanceof LivingEntity), "the refused body really is not a LivingEntity");
        require(nonLivingRefusals >= 1,
                "a real fireball really reached the non-living body and was refused" + diagnostics());
        require(!nonLivingBody.isRemoved(), "a refused collision does not remove the body");
        require(noSpawnedFire(), "a refused collision never sets anything on fire");
    }

    /**
     * A real participant of a second, non-overlapping encounter.
     *
     * <p>Two encounters cannot overlap, but a fireball can still cross the gap. The fireball here is made by the
     * same native launch the product uses; it is a fixture precondition for the isolation test, not a claim that
     * the AI attacked from outside its range.</p>
     */
    private static void beginOtherEncounter() {
        // Two battlefields cannot overlap, so the second founder stands far outside this one and needs real ground.
        for (int dx = 16; dx <= 64; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                set(origin.offset(dx, -1, dz), true);
                for (int dy = 0; dy <= 4; dy++) set(origin.offset(dx, dy, dz), false);
            }
        }
        owner.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        blaze = (Blaze) spawn(EntityType.BLAZE, 23.5D, 0.5D);
        // A corridor over the gap keeps a miss inside the test area instead of in the void.
        for (int dx = 20; dx <= 34; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                set(origin.offset(dx, dy, -2), true);
                set(origin.offset(dx, dy, 2), true);
                set(origin.offset(dx, dy, -1), false);
                set(origin.offset(dx, dy, 0), false);
                set(origin.offset(dx, dy, 1), false);
            }
            set(origin.offset(dx, 3, 0), true);
            set(origin.offset(dx, 3, 1), true);
            set(origin.offset(dx, 3, -1), true);
        }
        startBattle();
        // The second encounter's founder stands far enough that the two battlefields cannot overlap, and its unit
        // stands eight blocks from our blaze - inside the second battlefield, outside ours.
        stranger.setPos(origin.getX() + 52.5D, origin.getY(), origin.getZ() + 0.5D);
        // A wide body, so a real hit is the expected outcome rather than a lucky draw of the vanilla spread.
        Mob unit = spawn(EntityType.RAVAGER, 28.5D, 0.5D);
        otherEncounterUnit = unit;
        require(BattleManager.start(stranger, true), "the second encounter really starts");
        require(BattleManager.findFor(stranger.getUUID()) != null, "the second encounter owns its founder");
        require(BattleManager.containing(unit) != null, "the second encounter owns its own unit");
        require(BattleManager.containing(unit) != battle, "the unit belongs to the other encounter, not this one");
        require(!battle.acceptsFireball(blaze.getUUID(), unit),
                "this encounter refuses a body that is not on its own roster");
        require(blaze.distanceToSqr(unit) < 100.0D, "the unit is within a real fireball's reach");
        // Native launch by the product, aimed at the other encounter's unit, with this encounter's rule. Several
        // attempts, because the vanilla spread is the entity's own and a single shot cannot be forced onto a body.
        for (int attempt = 0; attempt < 8; attempt++) {
            require(BlazeFireballs.launch(level, blaze, unit, battle, unit.getUUID()),
                    "the native fireball really joined the world");
        }
    }

    private static void checkOtherEncounter() {
        require(otherEncounterUnit != null, "the other encounter's unit exists");
        require(BattleManager.containing(otherEncounterUnit) != battle,
                "the unit still belongs to the other encounter");
        require(!battle.acceptsFireball(blaze.getUUID(), otherEncounterUnit),
                "this encounter's own rule refuses that body, read from the real dual-encounter state");
        require(otherEncounterUnit.getHealth() == otherEncounterUnit.getMaxHealth(),
                "the other encounter's unit keeps its health: " + otherEncounterUnit.getHealth());
        require(otherEncounterUnit.getRemainingFireTicks() <= 0, "the other encounter's unit is never ignited");
        if (otherEncounterRefusals >= 1) {
            Tactical.LOGGER.info("[Tactical] blaze fixture other encounter: a real fireball reached the unit and "
                    + "was refused there (refusals={})", otherEncounterRefusals);
        } else {
            // Honest label, as agreed: the roster predicate is asserted against the real dual-encounter state, and
            // the real-collision half of the same branch is the proven bystander case.
            Tactical.LOGGER.info("[Tactical] blaze fixture other encounter: predicate-only evidence this run "
                    + "(no fireball reached the body); the real-collision half of this branch is the bystander "
                    + "case{}", diagnostics());
        }
    }

    private static Cow cow(double dx, int dz) {
        Cow cow = EntityType.COW.create(level);
        require(cow != null, "cow exists in this version");
        cow.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz + 0.5D);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        cow.setHealth(100);
        require(level.addFreshEntity(cow), "the body is really in the world");
        cow.setNoAi(true);
        return cow;
    }

    // ------------------------------------------------------------------
    // turn driving
    // ------------------------------------------------------------------

    /**
     * Drives one blaze turn to completion.
     *
     * @return true while the fixture must wait for another tick
     */
    private static boolean advanceBlazeTurn() {
        sampleActingTurn();
        if (battle == null || battle.finished()) return false;
        if (!battle.isPlayerTurnNow()) {
            if (battle.isActorTurn(blaze)) {
                if (blaze.isOnFire()) {
                    chargeObserved = true;
                    if (volleyStartTick < 0) volleyStartTick = ticks;
                }
                if (battle.pendingProjectiles() > 0) {
                    busyHeldTurn = true;
                    require(!battle.isPlayerTurnNow(),
                            "a live fireball cannot hand control back to the player early");
                }
                if (leaveMidVolley && shotTicks.size() >= 1) {
                    battle.leave(owner, BattleText.exitReason(BattleText.Exit.DEFEATED));
                }
            }
            return true;
        }
        if (volleyStartTick > 0 && volleyEndTick < 0 && !blaze.isOnFire()) volleyEndTick = ticks;
        if (fireTicksAfterHit > 0 && fireTicksAtFirstOwnStart < 0) {
            // Frozen while the encounter owns the burn: no wall-clock decay between the hit and this own start.
            fireTicksAtFirstOwnStart = owner.getRemainingFireTicks();
        }
        if (needMoreEvidence != null && !needMoreEvidence.getAsBoolean() && evidenceTurns > 0) {
            // An order that arrives before the encounter is ready is refused rather than queued, so the budget is
            // spent only by a hand-over that really happened - never by a refused attempt.
            require(++handOverAttempts <= 900, "the encounter never accepted another of this actor's own turns");
            battle.endPlayerTurn();
            if (!battle.isPlayerTurnNow()) evidenceTurns--;
            return true;
        }
        if (turnStartAp < 0) {
            // The blaze never got its own turn yet: ask again instead of reading a missing turn as a pass.
            require(++handOverAttempts <= 300, "the blaze never took its own turn");
            battle.endPlayerTurn();
            return true;
        }
        return false;
    }

    private static void sampleActingTurn() {
        if (battle == null || blaze == null || !battle.isActorTurn(blaze)) return;
        if (turnStartAp < 0) turnStartAp = battle.actorAp(blaze);
        lowestAp = Math.min(lowestAp, battle.actorAp(blaze));
        if (turnStartApOwner < 0) turnStartApOwner = battle.actorAp(owner);
        ownerApDuringEnemyTurn = battle.actorAp(owner);
        require(ownerApDuringEnemyTurn == turnStartApOwner,
                "a member's AP is constant for the whole enemy action: " + turnStartApOwner + " -> "
                        + ownerApDuringEnemyTurn);
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static void startBattle() {
        resetTurnObservations();
        ServerPackets.startCapture();
        BattleManager.reset();
        require(BattleManager.start(owner, true), "blaze encounter starts");
        battle = BattleManager.current();
        require(battle != null && battle.isPlayerTurnNow(), "the high-initiative owner acts first");
        require(battle.participants().contains(blaze), "the blaze is a real participant: roster "
                + battle.participants().stream().map(BlazeSelfTest::describe).toList());
        require(blaze.isNoAi(), "the encounter really holds the unit's own AI");
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(48))) {
            if (other != blaze && other != sameSideBlaze && other != otherEncounterUnit
                    && other instanceof net.minecraft.world.entity.monster.Enemy) other.discard();
        }
    }

    private static void nextScenario() {
        if (battle != null) battle.finish(BattleText.literal("fixture: next scenario"));
        BattleManager.reset();
        battle = null;
        if (blaze != null) {
            require(!blaze.isOnFire(), "a released blaze never keeps the charge bit");
            blaze.discard();
            blaze = null;
        }
        if (sameSideBlaze != null) {
            require(!sameSideBlaze.isOnFire(), "a released same-side blaze keeps no charge bit");
            sameSideBlaze.discard();
            sameSideBlaze = null;
        }
        if (nonLivingBody != null) {
            nonLivingBody.discard();
            nonLivingBody = null;
        }
        if (otherEncounterUnit != null) {
            otherEncounterUnit.discard();
            otherEncounterUnit = null;
        }
        for (Cow cow : plugs) if (cow != null) cow.discard();
        plugs.clear();
        for (Cow cow : legalPlugs) cow.discard();
        legalPlugs.clear();
        clearBuilt();
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        owner.clearFire();
        owner.setPos(origin.getX() + 8.5D, origin.getY(), origin.getZ() + 0.5D);
        clearWorkingBox();
        resetTurnObservations();
    }

    private static void resetTurnObservations() {
        shotTicks.clear();
        joinAttempts = 0;
        cancelledJoins = 0;
        thrownJoins = 0;
        cancelAllJoins = false;
        throwOnSecond = false;
        leaveMidVolley = false;
        cancelLegalImpacts = false;
        cancelEveryImpact = false;
        lateCancelledImpacts = 0;
        fireballHits = 0;
        fireballDamage = -1.0F;
        fireballApplied = -1.0F;
        fireballSourceOwned = false;
        fireTicksAfterHit = -1;
        fireTicksAtFirstOwnStart = -1;
        meleeHits = 0;
        meleeDamage = -1.0F;
        burnPulses = 0;
        memberHealthBeforeMelee = false;
        admissionChargeCleared = false;
        evidenceTurns = 0;
        needMoreEvidence = null;
        nonLivingRefusals = 0;
        otherEncounterRefusals = 0;
        blockImpacts = 0;
        entityImpacts = 0;
        refusedEntityImpacts = 0;
        turnStartAp = -1;
        lowestAp = Integer.MAX_VALUE;
        ownerApDuringEnemyTurn = -1;
        ownerHandBeforeEnemyTurn = "";
        chargeObserved = false;
        busyHeldTurn = false;
        volleyStartTick = -1;
        volleyEndTick = -1;
        handOverAttempts = 0;
        turnStartApOwner = -1;
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, server);
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 24);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 32) >> 4; cx <= (origin.getX() + 32) >> 4; cx++) {
            for (int cz = (origin.getZ() - 32) >> 4; cz <= (origin.getZ() + 32) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -16; x <= 16; x++) for (int z = -8; z <= 8; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int dy = 0; dy < 10; dy++) {
                level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // The run directory is machine-local and may be reused after an aborted run: start from an empty box, so a
        // leftover body can never be mistaken for a scenario's own setup.
        clearWorkingBox();
        owner = player(server, "tactical-blaze-owner", "BlazeOwner");
        stranger = player(server, "tactical-blaze-stranger", "BlazeStranger");
        stranger.setPos(origin.getX() + 30.5D, origin.getY(), origin.getZ() + 0.5D);
    }

    private static ServerPlayer player(MinecraftServer server, String key, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + 8.5D, origin.getY(), origin.getZ() + 0.5D);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.1D);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        player.setHealth(100);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        level.addNewPlayer(player);
        return player;
    }

    private static Mob spawn(EntityType<? extends Mob> type, double dx, double dz) {
        Mob mob = type.create(level);
        require(mob != null, type + " exists in this version");
        mob.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        mob.setHealth(100);
        require(level.addFreshEntity(mob), type + " registered in the real server world");
        return mob;
    }

    /**
     * A three-wide, three-high corridor with real stone walls.
     *
     * <p>The corridor is what makes "the fireball really reached a body" decidable: a bystander line fills the
     * whole cross-section, so a shot that stays inside the corridor cannot pass it. The narrow variant is one
     * cell wide, so almost every shot hits a wall instead - which is exactly the terrain case.</p>
     */
    private static void buildCorridor(int halfWidth) {
        for (int dx = 0; dx <= 12; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -halfWidth - 1; dz <= halfWidth + 1; dz++) {
                    if (Math.abs(dz) == halfWidth + 1) set(origin.offset(dx, dy, dz), true);
                }
            }
            for (int dz = -halfWidth - 1; dz <= halfWidth + 1; dz++) set(origin.offset(dx, 3, dz), true);
        }
    }

    private static void buildNarrowCorridor() {
        for (int dx = 0; dx <= 12; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                set(origin.offset(dx, dy, -1), true);
                set(origin.offset(dx, dy, 1), true);
            }
            set(origin.offset(dx, 0, 0), false);
            set(origin.offset(dx, 1, 0), false);
            set(origin.offset(dx, 2, 0), false);
            set(origin.offset(dx, 3, 0), true);
        }
    }

    private static void set(BlockPos pos, boolean stone) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
        var state = level.getBlockState(pos);
        if (stone && state.getBlock() == Blocks.STONE) return;
        if (!stone && state.isAir()) return;
        level.setBlock(pos, stone ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        placed.add(pos);
    }

    /** Remove every animal and hostile inside the working box; players and this fixture's own units are kept. */
    private static void clearWorkingBox() {
        if (level == null || origin == null) return;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin).inflate(20);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
            if (mob instanceof net.minecraft.world.entity.animal.Animal
                    || mob instanceof net.minecraft.world.entity.monster.Enemy) {
                mob.discard();
            }
        }
    }

    private static void clearBuilt() {
        for (BlockPos pos : placed) {
            if (level.getBlockState(pos).getBlock() == Blocks.STONE) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        placed.clear();
    }

    private static boolean noSpawnedFire() {
        for (int dx = -8; dx <= 14; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    if (level.getBlockState(origin.offset(dx, dy, dz)).getBlock() instanceof BaseFireBlock) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void reportAndFinish(MinecraftServer server) {
        enabled = false;
        Tactical.LOGGER.info("[Tactical] BLAZE SELFTEST PASSED: vanilla charge bit, three real small fireballs "
                + "six ticks apart for one paid action inside the 160 tick deadline, the turn held until every "
                + "projectile settled, zero fireballs and zero charge when every join was cancelled, one frozen "
                + "price and no refund when a later join failed, a stopped volley when the target left, real "
                + "bystander collisions refused before vanilla ran, real block impacts that set no fire while the "
                + "mobGriefing gamerule stayed untouched, a configured price the server really spends with no "
                + "surcharge carried between own turns, a fireball that stayed under this encounter's rule after an "
                + "unrelated listener cancelled its legal impact and was still refused at the next body, a "
                + "projectile whose impacts were all cancelled reclaimed by the tracker with its mark released, a "
                + "vanilla charge bit taken out of the entity at admission and shown only in its own turn, a native "
                + "melee that really hurt the member and created no fireball, a real ignition frozen until the "
                + "member's own start clock settled it, a same-side participant left untouched by real fireball "
                + "collisions, a real fireball fired at a body that is not a LivingEntity refused there with that "
                + "body untouched, and a real fireball crossing into a second, non-overlapping encounter refused by "
                + "the roster identity of this one");
        finish(server);
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (blaze != null) {
            if (blaze.isOnFire()) {
                Tactical.LOGGER.error("[Tactical] BLAZE SELFTEST: the released blaze kept its charge bit");
            }
            blaze.discard();
        }
        for (Cow cow : plugs) cow.discard();
        plugs.clear();
        BattleEffects.clear();
        ServerPackets.stopCapture();
        for (ServerPlayer player : List.of(owner, stranger)) {
            if (player != null && !player.isRemoved()) {
                level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            }
        }
        clearBuilt();
        for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        server.halt(false);
    }

    private static boolean journalHas(String key) {
        for (ServerPlayer recipient : List.of(owner, stranger)) {
            if (recipient == null) continue;
            for (var payload : ServerPackets.capturedFor(recipient.getUUID())) {
                if (!(payload instanceof BattleLogPacket log)) continue;
                for (BattleLogPacket.Entry entry : log.history()) {
                    if (entry.text().getContents() instanceof TranslatableContents contents
                            && key.equals(contents.getKey())) return true;
                }
            }
        }
        return false;
    }

    /** Everything an operator needs to tell "the rule held" from "nothing was ever fired". */
    private static String diagnostics() {
        return " [joins=" + joinAttempts + " shots=" + shotTicks + " cancelled=" + cancelledJoins
                + " thrown=" + thrownJoins + " blockImpacts=" + blockImpacts + " entityImpacts=" + entityImpacts
                + " refusedImpacts=" + refusedEntityImpacts + " nonLivingRefusals=" + nonLivingRefusals
                + " otherEncounterRefusals=" + otherEncounterRefusals + " charge=" + chargeObserved
                + " ap=" + turnStartAp + "->" + lowestAp + " blaze=" + describe(blaze) + "]";
    }

    private static String describe(LivingEntity entity) {
        if (entity == null) return "none";
        return entity.getType().toShortString() + "#" + entity.getId() + "@" + entity.blockPosition()
                + (entity.isRemoved() ? "(removed)" : "") + (entity.isAlive() ? "" : "(dead)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
