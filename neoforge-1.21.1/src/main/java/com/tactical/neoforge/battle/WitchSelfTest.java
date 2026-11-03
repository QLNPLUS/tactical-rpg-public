package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.ai.TacticalWitchAccess;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Disposable-world dedicated-server fixture for the witch: {@code -Dtactical.selftest=witch}.
 *
 * <p>Everything here reads the authoritative server result of real entities in a real encounter: whether a
 * drink in progress settled while the battle owned her, how many AP one drink really took and how many own
 * turns the shared cooldown held it, what a real thrown bottle did to real health and real effects, and that
 * the same bottle left every entity outside this encounter's opposed side untouched.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class WitchSelfTest {
    private static boolean enabled;
    private static int ticks, stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static Witch witch;
    private static Pillager raider;
    private static Cow bystander;
    private static ServerBattle battle;
    private static final List<BlockPos> forced = new ArrayList<>();
    private static final List<BlockPos> placed = new ArrayList<>();

    /** Own-turn bookkeeping read from the queue, never from wall-clock time. */
    private static int witchTurnsSeen;
    private static int checkedTurns;
    private static boolean witchTurnOpen;
    private static int turnEndedAt = -1;
    private static int turnStartAp;
    private static float ownerHpAtStart;
    private static float raiderHpAtStart;
    private static float bystanderHpAtStart;
    private static float witchHpAtStart;
    private static long turnEpoch = -1L;
    private static long previousTurnEpoch = -1L;
    private static int freezeUntil;
    private static boolean cancelPotionJoins;
    private static boolean debuffsPending;
    private static int restoredUsingTime = -1;
    private static int rolledBackCountdown = -1;
    private static ItemStack heldPotionBefore = ItemStack.EMPTY;
    private static PoseMutation poseMutation = PoseMutation.NONE;
    private static boolean poseMutated;
    private static final List<ItemStack> droppedStacks = new ArrayList<>();

    /** What the fixture does to the acting witch once her presented drink is really running. */
    private enum PoseMutation { NONE, KILL, SWAP_HAND }
    private static boolean cancelHeals;
    private static boolean throwOnPotionJoin;
    private static int potionJoinThrows;
    private static int refusalRowsAtLastCheck;
    // Presentation observations across real ticks.
    private static int poseTicks;
    private static boolean poseHandHeldPotion;
    private static boolean poseActorStable;
    private static boolean poseEpochStable;
    private static boolean poseChargedEarly;
    private static long poseEpoch = -1L;
    private static int poseStartAp;
    private static java.util.UUID poseActor;
    private static int cancelledPotions;

    private WitchSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "witch".equals(System.getProperty("tactical.selftest"));
        ticks = stage = 0;
        resetObservations();
    }

    /** Cancels this encounter's bottle joins only, so "nothing was created" can be proven to cost nothing. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPotionJoin(EntityJoinLevelEvent event) {
        if (!enabled || event.getLevel() != level) return;
        if (!(event.getEntity() instanceof ThrownPotion potion)) return;
        if (potion.getOwner() != witch) return;
        var contents = potion.getItem().get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        Tactical.LOGGER.info("[Tactical] witch fixture bottle joined: potion={} marked={} at {}",
                contents == null ? "none" : contents.potion().map(holder -> holder.unwrapKey().orElseThrow().location())
                        .orElse(null), BattlePotionSplash.marked(potion), potion.position());
        if (throwOnPotionJoin) {
            // A real creation callback can fail: the marker must not leak and the action must not be charged.
            potionJoinThrows++;
            throw new IllegalStateException("fixture: native potion creation callback failed");
        }
        if (!cancelPotionJoins) return;
        cancelledPotions++;
        event.setCanceled(true);
    }

    /** What vanilla's own death pass really dropped for the tracked witch. */
    @SubscribeEvent
    public static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (!enabled || witch == null || event.getEntity() != witch) return;
        for (var drop : event.getDrops()) droppedStacks.add(drop.getItem().copy());
    }

    /** Refuses the real instant heal of the tracked witch, so "the dose did not land" becomes observable. */
    @SubscribeEvent
    public static void onHeal(net.neoforged.neoforge.event.entity.living.LivingHealEvent event) {
        if (!enabled || !cancelHeals) return;
        if (event.getEntity() != witch) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onImpact(net.neoforged.neoforge.event.entity.ProjectileImpactEvent event) {
        if (!enabled || !(event.getProjectile() instanceof ThrownPotion potion)) return;
        if (potion.getOwner() != witch) return;
        Tactical.LOGGER.info("[Tactical] witch fixture bottle impact: {} at {} victim {}", event.getRayTraceResult().getType(),
                event.getRayTraceResult().getLocation(),
                event.getRayTraceResult() instanceof net.minecraft.world.phys.EntityHitResult hit
                        ? hit.getEntity() : "none");
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 12000, "witch fixture completion deadline");
            switch (stage) {
                case 0 -> {
                    setup(event.getServer());
                    stage = 1;
                }
                case 1 -> {
                    if (ticks < 100) return;
                    beginCarriedDrink();
                    stage = 2;
                }
                case 2 -> {
                    if (ticks < freezeUntil) return;
                    checkCarriedDrinkNeverSettledByItself();
                    battle.endPlayerTurn();
                    stage = 3;
                }
                case 3 -> {
                    if (runningWitchTurn()) return;
                    checkCarriedDrinkPaidExactlyOnce();
                    nextScenario(true);
                    beginCooldownTimeline();
                    stage = 4;
                }
                case 4 -> {
                    if (runningWitchTurn()) return;
                    checkDrinkTurn(1, 25, 10.0F);
                    stage = 5;
                }
                case 5 -> {
                    if (runningWitchTurn()) return;
                    checkDrinkTurn(2, 0, 10.0F);
                    stage = 6;
                }
                case 6 -> {
                    if (runningWitchTurn()) return;
                    checkDrinkTurn(3, 0, 10.0F);
                    stage = 7;
                }
                case 7 -> {
                    if (runningWitchTurn()) return;
                    checkDrinkTurn(4, 25, 14.0F);
                    nextScenario(false);
                    beginPeriodicSplash();
                    stage = 8;
                }
                case 8 -> {
                    if (runningWitchTurn()) return;
                    checkPeriodicSplashIsolated();
                    nextScenario(false);
                    beginInstantSplash();
                    stage = 9;
                }
                case 9 -> {
                    if (runningWitchTurn()) return;
                    checkInstantSplashIsolated();
                    nextScenario(false);
                    beginCancelledBottle();
                    stage = 10;
                }
                case 10 -> {
                    if (runningWitchTurn()) return;
                    checkCancelledBottleCostsNothing();
                    nextScenario(false);
                    beginUnpaidExit();
                    stage = 11;
                }
                case 11 -> {
                    checkUnpaidExitRestoredTheOriginalDrink();
                    nextScenario(false);
                    beginEquipmentAndPose();
                    stage = 12;
                }
                case 12 -> {
                    if (runningWitchTurn()) {
                        observePose();
                        return;
                    }
                    checkEquipmentAndPose();
                    nextScenario(false);
                    beginCancelledHeal();
                    stage = 13;
                }
                case 13 -> {
                    if (runningWitchTurn()) return;
                    checkCancelledHealIsNotCharged();
                    cancelHeals = false;
                    stage = 14;
                }
                case 14 -> {
                    if (runningWitchTurn()) return;
                    checkTheRefusedDrinkStartedNoCooldown();
                    nextScenario(false);
                    beginFullHealthPending();
                    stage = 15;
                }
                case 15 -> {
                    if (runningWitchTurn()) return;
                    checkFullHealthPendingIsNotCharged();
                    battle.finish("witch fixture: normal exit after an unused carried drink");
                    stage = 16;
                }
                case 16 -> {
                    checkUnusedCarriedDrinkCameBack();
                    nextScenario(false);
                    beginThrowJoinFailure();
                    stage = 17;
                }
                case 17 -> {
                    if (runningWitchTurn()) return;
                    checkThrowJoinFailureCostsNothing();
                    nextScenario(false);
                    beginHeldPotionNotDrinking();
                    stage = 18;
                }
                case 18 -> {
                    if (runningWitchTurn()) return;
                    checkHeldPotionNotDrinking();
                    nextScenario(false);
                    beginAdmissionRollback();
                    stage = 19;
                }
                case 19 -> {
                    checkAdmissionRollbackGaveTheDrinkBack();
                    nextScenario(false);
                    beginDeathDuringPose();
                    stage = 20;
                }
                case 20 -> {
                    if (runningWitchTurn()) {
                        observePose();
                        mutatePose();
                        return;
                    }
                    checkDeathDuringPoseKeepsTheRealItem();
                    nextScenario(false);
                    beginSwapDuringPose();
                    stage = 21;
                }
                case 21 -> {
                    if (runningWitchTurn()) {
                        observePose();
                        mutatePose();
                        return;
                    }
                    checkSwappedHandIsNotOverwritten();
                    Tactical.LOGGER.info("[Tactical] WITCH AI SELFTEST PASSED: a drink already in progress at "
                            + "admission never settled on wall-clock time while the battle owned her and was charged "
                            + "exactly once, through a real multi-tick vanilla drinking pose that gave the borrowed "
                            + "hand item back; the shared cooldown allowed her 1st own turn, refused the 2nd and 3rd "
                            + "and allowed the 4th with real AP and real health; a cancelled heal and a carried "
                            + "drink with nothing left to give were both refused without AP, paid action or cooldown; "
                            + "a real splash bottle delivered native slowness and instant harming to the opposed "
                            + "member only, leaving a same-side participant and an outside living entity untouched; "
                            + "a bottle whose join was cancelled or threw cost no AP, left no marker and no pending "
                            + "action; a witch holding an ordinary item kept it through admission, her drink and her "
                            + "exit; an unfinished drink was given back unchanged on a normal exit and on a failed "
                            + "admission while a paid one never was; a death in the middle of the pose kept the "
                            + "real borrowed item on her instead of the temporary bottle and never dropped that "
                            + "bottle; and an external replacement of the hand slot kept the new item and "
                            + "re-created nothing");
                    finish(event.getServer());
                }
                default -> throw new AssertionError("unknown fixture stage " + stage);
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] WITCH AI SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            finish(event.getServer());
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    // ------------------------------------------------------------------
    // scenarios
    // ------------------------------------------------------------------

    /**
     * A witch admitted while the vanilla drink is already running.
     *
     * <p>Vanilla would settle that bottle from her own {@code aiStep} countdown within one tick, so the question
     * this scenario answers is whether the battle really took the drink away instead: a long wall-clock wait on
     * somebody else's turn must produce no health and no effect.</p>
     */
    private static void beginCarriedDrink() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        ItemStack bottle = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        witch.setUsingItem(true);
        ((TacticalWitchAccess) witch).tactical$restoreDrink(
                new TacticalWitchAccess.WitchDrink(bottle, 1, true, true));
        require(witch.isDrinkingPotion(), "the witch really is mid-drink before admission");
        startBattle();
        freezeUntil = ticks + 220;
    }

    private static void checkCarriedDrinkNeverSettledByItself() {
        require(witch.isAlive(), "the witch survived her own frozen drink");
        require(witch.getHealth() == 12.0F,
                "no free heal from the frozen drink, health " + witch.getHealth());
        require(!witch.hasEffect(MobEffects.HEAL), "the instant heal never fired by itself");
        require(!witch.isDrinkingPotion(), "the vanilla drink flag was taken out of the entity");
        require(witch.getMainHandItem().isEmpty(), "the bottle was taken out of her hand, not drunk from it");
        require(witch.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("drinking")) == null,
                "vanilla's drinking slowdown is gone while the battle owns her");
    }

    /** The carried-over drink is honoured first, and it costs exactly one drink once. */
    private static void checkCarriedDrinkPaidExactlyOnce() {
        require(witchTurnsSeen >= 1, "the witch really had her own turn");
        require(turnStartAp - battle.actorAp(witch) == 25,
                "the carried drink costs its frozen 25 AP once (start " + turnStartAp
                        + ", after " + battle.actorAp(witch) + ")");
        require(witch.getHealth() == 16.0F,
                "the paid healing dose really landed, health " + witch.getHealth());
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 1,
                "exactly one paid drink row was published, got " + journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK));
        require(witch.getMainHandItem().isEmpty(), "the paid bottle is consumed");
        require(!witch.isDrinkingPotion(), "no second dose is left in progress");
        require(!battle.isBusy(), "the turn ended without leaving the encounter busy");
        checkedTurns = witchTurnsSeen;
    }

    /**
     * Her own turns 1..4 in a sealed cell eleven blocks from the only opponent.
     *
     * <p>Eleven blocks is outside the ten-block throwing range and not beyond the eleven-block swiftness rule,
     * so the only action she can take is a drink: every AP change in this scenario is a drink or nothing at all.
     * </p>
     */
    private static void beginCooldownTimeline() {
        witch = spawn(EntityType.WITCH, 11.5D, 0.5D);
        witch.setHealth(6.0F);
        owner.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        startBattle();
        GridPos cell = battle.currentActorCell(witch);
        require(cell != null, "the witch really has a cell");
        // Seal her in place: the point is which own turn she may drink on, not how she walks.
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                wall(new BlockPos(cell.x() + step[0], cell.y() + dy, cell.z() + step[1]));
            }
        }
    }

    /**
     * One of her own turns, judged by the two things only a real drink can change: her own AP and her health.
     *
     * <p>A journal row count is deliberately not the evidence here. The owner is given a very high charge rate
     * by this fixture, so many of his turns pass between two of hers; the journal keeps a rolling window, and
     * the rows of an earlier drink legitimately fall out of it. Her AP and her health cannot be produced by
     * anything but a settled drink, so they carry the timeline on their own.</p>
     */
    private static void checkDrinkTurn(int turn, int expectedSpent, float expectedHealth) {
        require(witchTurnsSeen == turn, "expected own turn " + turn + ", observed " + witchTurnsSeen);
        require(turnEpoch > previousTurnEpoch,
                "own turn " + turn + " is a later queue epoch than the previous one (" + previousTurnEpoch
                        + " -> " + turnEpoch + "), so no wall-clock time re-armed it");
        int spent = turnStartAp - battle.actorAp(witch);
        require(spent == expectedSpent, "own turn " + turn + " spends " + expectedSpent + " AP, spent " + spent);
        require(Math.abs(witch.getHealth() - expectedHealth) < 0.01F,
                "own turn " + turn + " leaves health " + expectedHealth + ", observed " + witch.getHealth());
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_THROW) == 0,
                "she cannot throw from outside range, rows "
                        + journalCount(BattleText.KEY_ATTACK_ENEMY_THROW));
        require(!battle.isBusy(), "own turn " + turn + " ended without a busy encounter");
        checkedTurns = witchTurnsSeen;
    }

    /** A real slowness bottle: the opposed member is slowed, a same-side participant and a bystander are not. */
    private static void beginPeriodicSplash() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        withNeighbours();
        startBattle();
        require(participants().contains(raider), "the same-side raider is really a participant of this encounter");
        require(!participants().contains(bystander), "the cow is really outside this encounter");
    }

    private static void checkPeriodicSplashIsolated() {
        require(witchTurnsSeen >= 1, "the witch really had her own turn");
        require(turnStartAp - battle.actorAp(witch) == 15,
                "one bottle costs its 15 AP quote (start " + turnStartAp + ", after "
                        + battle.actorAp(witch) + ")");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_THROW) == 1,
                "exactly one paid throw row was published");
        int expected = nativeDuration(Potions.SLOWNESS);
        require(duration(owner, MobEffects.MOVEMENT_SLOWDOWN) == expected,
                "the real bottle delivered native slowness " + expected + ", observed "
                        + duration(owner, MobEffects.MOVEMENT_SLOWDOWN));
        // A player's own health may drift up by a fraction from ordinary regeneration; what a slowness
        // bottle must never do is take real damage away.
        require(owner.getHealth() >= ownerHpAtStart - 0.001F, "a slowness bottle deals no damage: start "
                + ownerHpAtStart + ", now " + owner.getHealth() + ", effects " + effects(owner));
        require(!raider.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "a same-side participant next to the impact was not slowed by this encounter's bottle");
        require(!bystander.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                "a living entity outside this encounter was not slowed by its bottle");
        require(raider.getHealth() == raiderHpAtStart, "the same-side participant lost no health");
        require(bystander.getHealth() == bystanderHpAtStart, "the outside entity lost no health");
        require(battle.pendingProjectiles() == 0, "the bottle settled before the turn returned");
        require(!battle.isBusy(), "the turn ended without leaving the encounter busy");
        checkedTurns = witchTurnsSeen;
    }

    /** A real harming bottle: the opposed member loses health, nobody else does. */
    private static void beginInstantSplash() {
        // Six blocks: inside the comfortable band, so the whole AP change of this turn is the bottle itself.
        witch = spawn(EntityType.WITCH, 6.5D, 0.5D);
        withNeighbours();
        startBattle();
        // The debuffs are planted at the start of her own turn, not here: this fixture's owner takes many own
        // turns before hers, and a slow effect handed out at battle start would legitimately expire first.
        debuffsPending = true;
    }

    private static void checkInstantSplashIsolated() {
        require(witchTurnsSeen >= 1, "the witch really had her own turn");
        require(turnStartAp - battle.actorAp(witch) == 15, "one bottle still costs 15 AP");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_THROW) == 1, "exactly one paid throw row was published");
        float loss = ownerHpAtStart - owner.getHealth();
        require(loss > 2.0F && loss <= 6.0F,
                "the instant bottle really hurt the opposed member by several points, loss " + loss
                        + " (start " + ownerHpAtStart + ", now " + owner.getHealth() + ")");
        require(raider.getHealth() == raiderHpAtStart,
                "the same-side participant next to the impact lost no health (was " + raiderHpAtStart
                        + ", now " + raider.getHealth() + ")");
        require(bystander.getHealth() == bystanderHpAtStart, "the outside entity lost no health");
        require(!raider.hasEffect(MobEffects.POISON), "no periodic effect leaked onto the same-side participant");
        require(!bystander.hasEffect(MobEffects.POISON), "no periodic effect leaked onto the outside entity");
        require(battle.pendingProjectiles() == 0, "the bottle settled before the turn returned");
        checkedTurns = witchTurnsSeen;
    }

    /** A bottle whose join is cancelled never existed, so it can cost nothing. */
    private static void beginCancelledBottle() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        startBattle();
        cancelPotionJoins = true;
    }

    private static void checkCancelledBottleCostsNothing() {
        require(witchTurnsSeen >= 1, "the witch really had her own turn");
        require(cancelledPotions > 0, "the native throw really tried to add a bottle (" + cancelledPotions + ")");
        require(turnStartAp - battle.actorAp(witch) == 0,
                "a bottle that never joined costs no AP (start " + turnStartAp + ", after "
                        + battle.actorAp(witch) + ")");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_THROW) == 0, "no paid throw row was published");
        require(journalCount(BattleText.KEY_ENEMY_NO_THROW) == 1,
                "the refusal is recorded as a no-throw row, got " + journalCount(BattleText.KEY_ENEMY_NO_THROW));
        require(battle.pendingProjectiles() == 0, "nothing is left pending");
        require(!battle.isBusy(), "nothing is left busy");
        require(!owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "a bottle that never existed changed nothing");
        require(owner.getHealth() >= ownerHpAtStart - 0.001F, "and dealt no damage");
        checkedTurns = witchTurnsSeen;
    }

    /** A witch admitted mid-drink that the encounter never charges for must get that drink back. */
    private static void beginUnpaidExit() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        ItemStack bottle = PotionContents.createItemStack(Items.POTION, Potions.FIRE_RESISTANCE);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        ((TacticalWitchAccess) witch).tactical$restoreDrink(
                new TacticalWitchAccess.WitchDrink(bottle, 7, true, true));
        startBattle();
        require(!witch.isDrinkingPotion(), "the drink is frozen while the encounter owns her");
        // A normal exit before her own turn: nothing was ever charged for this drink.
        battle.finish("witch fixture: normal exit before her own turn");
        // Read in the same tick, before vanilla resumes the countdown of the drink it just got back.
        TacticalWitchAccess access = (TacticalWitchAccess) witch;
        require(access.tactical$isDrinking(), "the vanilla drinking flag came back");
        require(witch.getMainHandItem().is(Items.POTION), "the same bottle came back, got " + witch.getMainHandItem());
        require(access.tactical$drinkCountdown() == 7, "the same countdown came back, got "
                + access.tactical$drinkCountdown());
        restoredUsingTime = access.tactical$drinkCountdown();
    }

    private static void checkUnpaidExitRestoredTheOriginalDrink() {
        require(battle == null || battle.finished(), "the encounter really ended");
        require(witch.isAlive(), "the witch survived the encounter");
        TacticalWitchAccess access = (TacticalWitchAccess) witch;
        require(access.tactical$isDrinking(), "vanilla really resumed its own drink instead of a frozen pose");
        require(witch.getMainHandItem().is(Items.POTION), "the same bottle is still in her hands");
        require(access.tactical$drinkCountdown() <= restoredUsingTime,
                "vanilla continues that countdown itself (from " + restoredUsingTime + " to "
                        + access.tactical$drinkCountdown() + "), which is what makes it a real unfinished action");
        require(witch.getHealth() == 12.0F, "the drink that never happened healed nothing");
    }

    /**
     * R1-4/R1-2: a witch holding an ordinary item drinks through a real multi-tick vanilla pose.
     *
     * <p>What this pins: admission does not touch an unrelated main-hand item, the drink is a presentation that
     * really spans several ticks with the same actor and the same epoch, nothing is charged while it runs, and
     * when it settles the borrowed hand comes back.</p>
     */
    private static void beginEquipmentAndPose() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        witch.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        startBattle();
        require(witch.getMainHandItem().is(Items.IRON_AXE),
                "admission left the ordinary main-hand item alone, got " + witch.getMainHandItem());
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(),
                "holding an axe is not a drink in progress");
    }

    /** One tick of the presented drink, recording what the entity and the encounter really show. */
    private static void observePose() {
        if (witch == null || !((TacticalWitchAccess) witch).tactical$isDrinking()) return;
        poseTicks++;
        poseHandHeldPotion |= witch.getMainHandItem().is(Items.POTION);
        if (poseActor == null) {
            poseActor = witch.getUUID();
            poseEpoch = battle.turnEpoch();
            poseStartAp = battle.actorAp(witch);
        }
        poseActorStable = poseActorStable && witch.getUUID().equals(battle.currentActorId());
        poseEpochStable = poseEpochStable && battle.turnEpoch() == poseEpoch;
        poseChargedEarly = poseChargedEarly || battle.actorAp(witch) != poseStartAp;
        // The turn may not move on while the pose runs; the fixture is not driving anyone else.
        require(!battle.isPlayerTurnNow(), "a presented drink keeps the turn");
    }

    private static void checkEquipmentAndPose() {
        require(poseTicks >= 2, "the drink really spanned more than one server tick, ticks " + poseTicks);
        require(poseHandHeldPotion, "the real bottle was in her hand during the pose");
        require(poseActorStable, "she stayed the actor for the whole pose");
        require(poseEpochStable, "the pose never crossed into another queue epoch");
        require(!poseChargedEarly, "nothing was charged while the pose was still running");
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(), "the pose ended");
        require(witch.getMainHandItem().is(Items.IRON_AXE),
                "the borrowed hand came back as the axe, got " + witch.getMainHandItem());
        require(turnStartAp - battle.actorAp(witch) == 25, "the settled drink costs its 25 AP once (start "
                + turnStartAp + ", after " + battle.actorAp(witch) + ")");
        require(witch.getHealth() == 16.0F, "the healing dose really landed, health " + witch.getHealth());
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 1, "exactly one paid drink row");
        require(battle.pendingProjectiles() == 0 && !battle.isBusy(), "the turn ended without leftovers");
    }

    /** R1-1: a real instant heal that the server refuses is not a drink. */
    private static void beginCancelledHeal() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        startBattle();
        cancelHeals = true;
        refusalRowsAtLastCheck = journalCount(BattleText.KEY_ENEMY_DRINK_REFUSED);
    }

    private static void checkCancelledHealIsNotCharged() {
        require(witchTurnsSeen >= 1, "she really had her own turn");
        require(journalCount(BattleText.KEY_ENEMY_DRINK_REFUSED) == refusalRowsAtLastCheck + 1,
                "the refused dose is recorded as a refusal row, got "
                        + journalCount(BattleText.KEY_ENEMY_DRINK_REFUSED) + " from " + refusalRowsAtLastCheck);
        require(turnStartAp - battle.actorAp(witch) == 0,
                "a heal that never landed costs no AP (start " + turnStartAp + ", after "
                        + battle.actorAp(witch) + ")");
        require(witch.getHealth() == 12.0F, "the cancelled heal really did not land, health " + witch.getHealth());
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 0, "no paid drink row was published");
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(), "the pose ended even though nothing landed");
        require(witch.getMainHandItem().isEmpty(), "the borrowed (empty) hand is empty again");
        require(!battle.isBusy(), "nothing is left busy");
        checkedTurns = witchTurnsSeen;
    }

    /** R1-1: because nothing was charged, the shared cooldown never started. */
    private static void checkTheRefusedDrinkStartedNoCooldown() {
        require(witchTurnsSeen == 2, "her next own turn really came, turns " + witchTurnsSeen);
        require(turnStartAp - battle.actorAp(witch) == 25,
                "the refused drink started no cooldown, so this turn pays 25 AP (start " + turnStartAp
                        + ", after " + battle.actorAp(witch) + ")");
        require(witch.getHealth() == 16.0F, "and the second, allowed dose really landed, health "
                + witch.getHealth());
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 1, "exactly one paid drink row now");
        checkedTurns = witchTurnsSeen;
    }

    /** R1-1: a carried-over drink with nothing left to give is not force-drunk. */
    private static void beginFullHealthPending() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(witch.getMaxHealth());
        ItemStack bottle = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        ((TacticalWitchAccess) witch).tactical$restoreDrink(
                new TacticalWitchAccess.WitchDrink(bottle, 20, true, true));
        startBattle();
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(), "the carried drink is frozen");
    }

    private static void checkFullHealthPendingIsNotCharged() {
        require(witchTurnsSeen >= 1, "she really had her own turn");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 0,
                "a carried drink at full health is not force-drunk");
        require(turnStartAp - battle.actorAp(witch) != 25, "and it costs no drink price (start " + turnStartAp
                + ", after " + battle.actorAp(witch) + ")");
        require(witch.getHealth() == witch.getMaxHealth(), "nothing was healed because nothing was missing");
        checkedTurns = witchTurnsSeen;
    }

    /** The dropped drink is still her unfinished action, so a normal exit gives it back. */
    private static void checkUnusedCarriedDrinkCameBack() {
        TacticalWitchAccess access = (TacticalWitchAccess) witch;
        require(access.tactical$isDrinking(), "the unused carried drink came back as a real drinking action");
        require(witch.getMainHandItem().is(Items.POTION), "with its own bottle");
        require(witch.getHealth() == witch.getMaxHealth(), "and it healed nothing on the way out");
    }

    /** R1-3: a creation callback that throws is a refused bottle, not a leaked marker. */
    private static void beginThrowJoinFailure() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        startBattle();
        throwOnPotionJoin = true;
    }

    private static void checkThrowJoinFailureCostsNothing() {
        require(witchTurnsSeen >= 1, "she really had her own turn");
        require(potionJoinThrows > 0, "the native creation callback really threw (" + potionJoinThrows + ")");
        require(turnStartAp - battle.actorAp(witch) == 0,
                "a bottle that never joined costs no AP (start " + turnStartAp + ", after "
                        + battle.actorAp(witch) + ")");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_THROW) == 0, "no paid throw row was published");
        require(journalCount(BattleText.KEY_ENEMY_NO_THROW) == 1,
                "the refusal is recorded as a no-throw row, got " + journalCount(BattleText.KEY_ENEMY_NO_THROW));
        require(battle.pendingProjectiles() == 0, "nothing is left pending");
        require(!battle.isBusy(), "the encounter is not stuck");
        require(!owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "and nothing was applied");
        checkedTurns = witchTurnsSeen;
    }

    /**
     * R1-2: merely holding a potion is not a drink in progress.
     *
     * <p>The old behaviour renamed any held bottle into a frozen drink and emptied the hand. Here she really
     * holds a healing bottle and is <em>not</em> drinking, so admission has to leave the bottle exactly where it
     * is, keep her drink flag false, and never turn item type into a started action.</p>
     */
    private static void beginHeldPotionNotDrinking() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(witch.getMaxHealth());
        ItemStack bottle = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        require(!witch.isDrinkingPotion(), "the fixture witch is only holding the bottle");
        startBattle();
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(),
                "holding a bottle is not a drink: no frozen action was invented");
        require(witch.getMainHandItem().is(Items.POTION),
                "and the held bottle was not emptied by admission, got " + witch.getMainHandItem());
        heldPotionBefore = witch.getMainHandItem().copy();
    }

    private static void checkHeldPotionNotDrinking() {
        require(witchTurnsSeen >= 1, "she really had her own turn");
        require(journalCount(BattleText.KEY_ATTACK_ENEMY_DRINK) == 0,
                "an item she merely held was never drunk as a carried action");
        require(witch.getHealth() == witch.getMaxHealth(), "and nothing healed her");
        require(witch.getMainHandItem().is(Items.POTION),
                "the held bottle is still there after her turn, got " + witch.getMainHandItem());
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(), "and it still is not a drink");
        checkedTurns = witchTurnsSeen;
    }

    /** R1-2: a failed admission gives the frozen drink back and releases the claim. */
    private static void beginAdmissionRollback() {
        ServerBattle.injectFault(ServerBattle.FaultPoint.ADMISSION);
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        ItemStack bottle = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        witch.setItemSlot(EquipmentSlot.MAINHAND, bottle);
        ((TacticalWitchAccess) witch).tactical$restoreDrink(
                new TacticalWitchAccess.WitchDrink(bottle, 12, true, true));
        require(witch.isDrinkingPotion(), "the witch is mid-drink before the failed admission");
        ServerPackets.startCapture();
        BattleManager.reset();
        require(!BattleManager.start(owner, true), "the injected admission fault refuses this encounter");
        battle = BattleManager.current();
        // Read in this same tick: once she is uncontrolled again, vanilla resumes that countdown itself.
        rolledBackCountdown = ((TacticalWitchAccess) witch).tactical$drinkCountdown();
    }

    private static void checkAdmissionRollbackGaveTheDrinkBack() {
        require(ServerBattle.pendingFaults() == 0, "the injected fault was really consumed");
        require(BattleManager.ownerOf(witch.getUUID()) == null, "the failed admission released its claim");
        TacticalWitchAccess access = (TacticalWitchAccess) witch;
        require(access.tactical$isDrinking(), "the drink that was taken is a real drinking action again");
        require(witch.getMainHandItem().is(Items.POTION), "with its own bottle, got " + witch.getMainHandItem());
        require(rolledBackCountdown == 12, "the same countdown came back on rollback, got "
                + rolledBackCountdown);
        require(access.tactical$drinkCountdown() <= rolledBackCountdown,
                "vanilla continues it once she is free again (from " + rolledBackCountdown + " to "
                        + access.tactical$drinkCountdown() + ")");
        require(!access.tactical$isControlled(), "the rolled-back witch is not left under battle control");
    }

    /**
     * The real item has to survive a death in the middle of the presented drink.
     *
     * <p>Vanilla's death pass drops whatever is in that hand. Without a restore before it, the temporary bottle
     * would be the only drop and the borrowed item would be gone.</p>
     */
    private static void beginDeathDuringPose() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        witch.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        startBattle();
        require(witch.getMainHandItem().is(Items.IRON_AXE), "the real item is in her hand before the drink");
        poseMutation = PoseMutation.KILL;
    }

    /** Applies this scenario's one mid-pose mutation, after the pose has really run for a couple of ticks. */
    private static void mutatePose() {
        if (poseMutation == PoseMutation.NONE || poseMutated) return;
        if (!((TacticalWitchAccess) witch).tactical$isDrinking() || poseTicks < 2) return;
        poseMutated = true;
        switch (poseMutation) {
            case KILL -> witch.kill();
            // A legitimate external callback puts something else in that slot while the pose is running.
            case SWAP_HAND -> witch.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
            default -> { }
        }
    }

    private static void checkDeathDuringPoseKeepsTheRealItem() {
        require(poseMutated, "she really died while the drink was being presented");
        require(poseTicks >= 2, "the pose was really running before the death, ticks " + poseTicks);
        require(witch.isDeadOrDying() || !witch.isAlive(), "she is really dead");
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(),
                "no drink presentation is left on a corpse");
        // A witch does not drop equipment in vanilla (LivingEntity#dropEquipment is empty), so the item that
        // matters is the one still on her: it has to be the real one, not the borrowed bottle.
        require(witch.getMainHandItem().is(Items.IRON_AXE),
                "the real item came back into her hand before the death pass, got "
                        + witch.getMainHandItem());
        require(droppedStacks.stream().noneMatch(stack -> stack.is(Items.POTION)),
                "the temporary bottle never became a world item, drops " + describeDrops());
        require(droppedStacks.stream().filter(stack -> stack.is(Items.IRON_AXE)).count() <= 1,
                "and it was not also re-created as a second copy, drops " + describeDrops());
        checkedTurns = witchTurnsSeen;
    }

    /**
     * An external replacement of that hand slot is never overwritten, and the borrowed item is not duplicated
     * back into the world.
     */
    private static void beginSwapDuringPose() {
        witch = spawn(EntityType.WITCH, 8.5D, 0.5D);
        witch.setHealth(12.0F);
        witch.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        startBattle();
        poseMutation = PoseMutation.SWAP_HAND;
    }

    private static void checkSwappedHandIsNotOverwritten() {
        require(poseMutated, "the hand really was replaced while the drink was being presented");
        require(!((TacticalWitchAccess) witch).tactical$isDrinking(), "the pose ended on its own");
        require(witch.getMainHandItem().is(Items.DIAMOND_SWORD),
                "the externally placed item is still there, got " + witch.getMainHandItem());
        require(witch.getOffhandItem().isEmpty(), "and the borrowed item was not pushed into another slot");
        require(witch.getHealth() == 16.0F, "the dose that was already paid for still landed, health "
                + witch.getHealth());
        require(turnStartAp - battle.actorAp(witch) == 25, "and it costs its one drink price");
        require(droppedStacks.stream().noneMatch(stack -> stack.is(Items.IRON_AXE)),
                "the borrowed item was never re-created, drops " + describeDrops());
        checkedTurns = witchTurnsSeen;
    }

    private static String describeDrops() {
        return droppedStacks.stream().map(ItemStack::toString).toList().toString();
    }

    // ------------------------------------------------------------------
    // turn driving
    // ------------------------------------------------------------------

    /** @return true while the fixture must wait for another tick */
    private static boolean runningWitchTurn() {
        sampleWitchTurn();
        if (battle.finished()) return false;
        if (witchTurnsSeen <= checkedTurns) {
            // Her turn has not started yet, so the owner hands over.
            if (battle.isPlayerTurnNow()) battle.endPlayerTurn();
            return true;
        }
        if (!battle.isPlayerTurnNow()) return true;
        // A short settle window: an instant effect really ticks on the entity's own next tick.
        if (turnEndedAt < 0) turnEndedAt = ticks;
        return ticks - turnEndedAt < 6;
    }

    private static void sampleWitchTurn() {
        if (battle == null || witch == null) return;
        boolean acting = battle.isActorTurn(witch);
        if (acting && !witchTurnOpen) {
            witchTurnOpen = true;
            witchTurnsSeen++;
            turnEndedAt = -1;
            turnStartAp = battle.actorAp(witch);
            plantDebuffsIfPending();
            previousTurnEpoch = turnEpoch;
            turnEpoch = battle.turnEpoch();
            witchHpAtStart = witch.getHealth();
            ownerHpAtStart = owner.getHealth();
            raiderHpAtStart = raider == null ? -1.0F : raider.getHealth();
            bystanderHpAtStart = bystander == null ? -1.0F : bystander.getHealth();
        } else if (!acting) {
            witchTurnOpen = false;
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    /**
     * Puts every debuff on the locked opponent before she plans, so the deterministic order really reaches the
     * instant brew on this own turn instead of racing an effect that expired while other units acted.
     */
    private static void plantDebuffsIfPending() {
        if (!debuffsPending) return;
        debuffsPending = false;
        for (Holder<MobEffect> effect : List.of(MobEffects.MOVEMENT_SLOWDOWN, MobEffects.POISON, MobEffects.WEAKNESS)) {
            require(owner.addEffect(new MobEffectInstance(effect, BattleEffects.TICKS_PER_OWN_CYCLE * 2, 0)),
                    "the target really carries " + effect + " before the throw");
        }
    }

    private static void withNeighbours() {
        owner.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        // A real same-side participant, one block from the impact: its own side's bottle must not affect it.
        raider = spawn(EntityType.PILLAGER, 1.5D, .5D);
        // A real living entity that is not part of this encounter at all.
        bystander = spawn(EntityType.COW, -1.5D, .5D);
    }

    private static void startBattle() {
        resetObservations();
        ServerPackets.startCapture();
        BattleManager.reset();
        require(BattleManager.start(owner, true), "witch encounter starts");
        battle = BattleManager.current();
        require(battle != null && battle.isPlayerTurnNow(), "the high-initiative owner acts first");
        require(witch != null && battle.participants().contains(witch),
                "the witch is really a participant: " + describe(witch) + " roster "
                        + battle.participants().stream().map(WitchSelfTest::describe).toList());
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        ownerHpAtStart = owner.getHealth();
    }

    private static List<LivingEntity> participants() {
        return battle == null ? List.of() : battle.participants();
    }

    private static void nextScenario(boolean paidDrinkMustNotReturn) {
        // Checked before anything can clean the unit up: a drink this encounter paid for is never recreated.
        if (paidDrinkMustNotReturn && witch != null && witch.isAlive()) {
            require(witch.getMainHandItem().isEmpty(),
                    "a drink that was really paid for never comes back as a bottle, hand "
                            + witch.getMainHandItem());
        }
        BattleManager.reset();
        battle = null;
        cancelPotionJoins = false;
        if (witch != null) witch.discard();
        if (raider != null) raider.discard();
        if (bystander != null) bystander.discard();
        witch = null;
        raider = null;
        bystander = null;
        for (BlockPos cell : placed) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        if (owner != null) {
            owner.setHealth(owner.getMaxHealth());
            owner.invulnerableTime = 0;
            owner.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
            for (Holder<MobEffect> effect : List.of(MobEffects.MOVEMENT_SLOWDOWN, MobEffects.POISON,
                    MobEffects.WEAKNESS, MobEffects.HEAL)) {
                owner.removeEffect(effect);
            }
        }
        resetObservations();
    }

    private static void resetObservations() {
        witchTurnsSeen = 0;
        checkedTurns = 0;
        witchTurnOpen = false;
        turnEndedAt = -1;
        turnStartAp = -1;
        ownerHpAtStart = -1.0F;
        raiderHpAtStart = -1.0F;
        bystanderHpAtStart = -1.0F;
        witchHpAtStart = -1.0F;
        turnEpoch = -1L;
        previousTurnEpoch = -1L;
        cancelledPotions = 0;
        debuffsPending = false;
        poseMutation = PoseMutation.NONE;
        poseMutated = false;
        droppedStacks.clear();
        cancelHeals = false;
        throwOnPotionJoin = false;
        potionJoinThrows = 0;
        refusalRowsAtLastCheck = 0;
        poseTicks = 0;
        poseHandHeldPotion = false;
        poseActorStable = true;
        poseEpochStable = true;
        poseChargedEarly = false;
        poseEpoch = -1L;
        poseActor = null;
        poseStartAp = -1;
    }

    private static <T extends Mob> T spawn(EntityType<T> type, double dx, double dz) {
        T mob = type.create(level);
        require(mob != null, type + " exists in this version");
        mob.setPos(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        require(level.addFreshEntity(mob), type + " registered in the real server world");
        return mob;
    }

    private static void wall(BlockPos cell) {
        level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        placed.add(cell);
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 10, spawn.getY() + 24);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 24) >> 4; cx <= (origin.getX() + 24) >> 4; cx++) {
            for (int cz = (origin.getZ() - 24) >> 4; cz <= (origin.getZ() + 24) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -30; x <= 30; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 6; dy++) level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        owner = player(server, "tactical-witch-owner", "WitchOwner");
    }

    private static ServerPlayer player(MinecraftServer server, String key, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        player.setHealth(100);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        level.addNewPlayer(player);
        return player;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (witch != null) witch.discard();
        if (raider != null) raider.discard();
        if (bystander != null) bystander.discard();
        for (BlockPos cell : placed) if (level != null) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        if (owner != null && !owner.isRemoved()) {
            level.removePlayerImmediately(owner, Entity.RemovalReason.DISCARDED);
        }
        ServerPackets.stopCapture();
        if (level != null) for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static int nativeDuration(Holder<net.minecraft.world.item.alchemy.Potion> potion) {
        List<MobEffectInstance> effects = potion.value().getEffects();
        return effects.isEmpty() ? 0 : effects.get(0).getDuration();
    }

    private static int duration(LivingEntity actor, Holder<MobEffect> effect) {
        MobEffectInstance instance = actor.getEffect(effect);
        return instance == null ? 0 : instance.getDuration();
    }

    /** One structure row count, read from the latest journal snapshot really published to the owner. */
    private static int journalCount(String key) {
        if (owner == null) return 0;
        BattleLogPacket latest = null;
        for (var payload : ServerPackets.capturedFor(owner.getUUID())) {
            if (payload instanceof BattleLogPacket log) latest = log;
        }
        if (latest == null) return 0;
        int count = 0;
        for (BattleLogPacket.Entry entry : latest.history()) {
            if (entry.text().getContents() instanceof TranslatableContents contents && key.equals(contents.getKey())) {
                count++;
            }
        }
        return count;
    }

    private static String effects(LivingEntity actor) {
        return actor.getActiveEffects().stream()
                .map(instance -> instance.getEffect().getRegisteredName() + ":" + instance.getDuration())
                .toList().toString();
    }

    private static String describe(LivingEntity entity) {
        if (entity == null) return "none";
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + "#" + entity.getId()
                + "@" + entity.blockPosition();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
