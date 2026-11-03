package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Bogged;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Disposable-world dedicated-server fixture for the ranged mob adapters:
 * {@code -Dtactical.selftest=ranged_mobs}.
 *
 * <p>Every scenario uses real entities, a real battle and the real vanilla item paths, then reads the
 * authoritative server result: the whole crossbow salvo inside one turn epoch for one paid action, the turn
 * held until every real projectile settles, an existing real charge being used instead of reloaded, an
 * unsupported rocket refused while staying loaded and unpaid, native tipped arrows, a cancelled join that
 * gives back everything it took, the last point of weapon durability still costing the quoted action, mixed
 * hands firing the weapon its own adapter declares, real piercing planning, and a locked opponent that is
 * neither silently swapped nor retried more than once.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class RangedMobAiSelfTest {
    /** What the fixture does to the world once the acting pillager really started walking. */
    private enum MoveMutation { NONE, SECOND_BECOMES_NEARER, LEAVE_LOCKED_ONLY, LEAVE_EVERY_MEMBER }

    private static boolean enabled;
    private static int ticks, stage;
    private static ServerLevel level;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer second;
    private static Pillager pillager;
    private static Stray stray;
    private static Bogged bogged;
    private static ArmorStand armorStand;
    private static ServerBattle battle;
    private static final List<BlockPos> forced = new ArrayList<>();
    private static final List<BlockPos> placed = new ArrayList<>();

    /** Authoritative observations of the scenario that is running. */
    private static final Map<UUID, Integer> arrowCount = new HashMap<>();
    private static final Map<UUID, Set<Long>> arrowEpochs = new HashMap<>();
    private static final Map<UUID, Double> arrowSpeed = new HashMap<>();
    private static final Map<UUID, Integer> arrowPierce = new HashMap<>();
    private static int ownerHits;
    private static int secondHits;
    private static int cancelledJoins;
    private static int slowAtHit;
    private static int poisonAtHit;
    private static long ownTurnEpoch = -1L;
    private static int turnStartAp;
    private static int lowestAp;
    private static int ownerApBeforeEnemyTurn;
    private static int ownerArrowsBeforeEnemyTurn;
    private static boolean busyHeldTurn;
    private static int handOverAttempts;
    private static final Set<String> observedActors = new LinkedHashSet<>();

    private static GridPos plannerCell;
    private static boolean piercingBlockedWithoutEnchant;
    private static boolean piercingOpenWithEnchant;
    /** Makes the second real arrow join throw, so an earlier salvo projectile is already in the world. */
    private static boolean throwOnSecondJoin;
    private static int arrowJoinAttempts;
    private static int joinThrows;
    private static boolean cancelArrowJoins;
    private static MoveMutation moveMutation = MoveMutation.NONE;
    private static boolean mutationApplied;

    private RangedMobAiSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "ranged_mobs".equals(System.getProperty("tactical.selftest"));
        ticks = stage = 0;
        resetObservations();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onArrowJoin(EntityJoinLevelEvent event) {
        if (!enabled || event.getLevel() != level || !(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof Mob shooter) || !counted(shooter)) return;
        if (throwOnSecondJoin && shooter == pillager) {
            // A real salvo can fail after an earlier projectile already joined. The first join must stay a real
            // projectile, and the later failure must not refund the action or the load.
            if (++arrowJoinAttempts >= 2) {
                joinThrows++;
                throw new IllegalStateException("fixture: native fire callback failed for a later salvo projectile");
            }
        }
        if (cancelArrowJoins && shooter == pillager) {
            // Vanilla already cleared the charged projectiles and spent durability by now: the server has to
            // give that back when no projectile reaches the world at all.
            cancelledJoins++;
            event.setCanceled(true);
            return;
        }
        arrowCount.merge(shooter.getUUID(), 1, Integer::sum);
        arrowEpochs.computeIfAbsent(shooter.getUUID(), id -> new LinkedHashSet<>())
                .add(battle == null ? -1L : battle.turnEpoch());
        // Read at join so the value describes the launch, not a tick of physics later.
        arrowSpeed.putIfAbsent(shooter.getUUID(), arrow.getDeltaMovement().length());
        arrowPierce.putIfAbsent(shooter.getUUID(), (int) arrow.getPierceLevel());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent.Post event) {
        if (!enabled || event.getNewDamage() <= 0) return;
        if (event.getEntity() != owner && event.getEntity() != second) return;
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow)) return;
        if (!(arrow.getOwner() instanceof Mob shooter) || !counted(shooter)) return;
        if (event.getEntity() == owner) ownerHits++;
        else secondHits++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 6000, "ranged mob fixture completion deadline");
            if (ownerHits > 0 && slowAtHit < 0) {
                // What the native arrow really delivered, read once the arrow's own post-hurt step has run.
                slowAtHit = duration(owner, MobEffects.MOVEMENT_SLOWDOWN);
                poisonAtHit = duration(owner, MobEffects.POISON);
            }
            switch (stage) {
                case 0 -> {
                    setup(event.getServer());
                    stage = 1;
                }
                case 1 -> {
                    if (ticks < 100) return;
                    beginEmptyCrossbowSalvo();
                    stage = 2;
                }
                case 2 -> {
                    if (advancePillagerTurn()) return;
                    checkEmptyCrossbowSalvo();
                    nextScenario(pillager);
                    beginPrechargedSalvo();
                    stage = 3;
                }
                case 3 -> {
                    if (advancePillagerTurn()) return;
                    checkPrechargedSalvo();
                    nextScenario(pillager);
                    beginRocketScenario();
                    stage = 4;
                }
                case 4 -> {
                    if (advancePillagerTurn()) return;
                    checkRocketRefusal();
                    nextScenario(pillager);
                    beginCancelledJoinScenario(true);
                    stage = 5;
                }
                case 5 -> {
                    if (advancePillagerTurn()) return;
                    checkCancelledJoinGaveEverythingBack(true);
                    nextScenario(pillager);
                    beginCancelledJoinScenario(false);
                    stage = 6;
                }
                case 6 -> {
                    if (advancePillagerTurn()) return;
                    checkCancelledJoinGaveEverythingBack(false);
                    nextScenario(pillager);
                    beginLastDurabilityShot();
                    stage = 7;
                }
                case 7 -> {
                    if (advancePillagerTurn()) return;
                    checkLastDurabilityStillCostsTheFullQuote();
                    nextScenario(pillager);
                    beginMixedHandsPillager();
                    stage = 8;
                }
                case 8 -> {
                    if (advancePillagerTurn()) return;
                    checkMixedHands("pillager", 20, true);
                    nextScenario(pillager);
                    beginMixedHandsStray();
                    stage = 9;
                }
                case 9 -> {
                    if (advancePillagerTurn()) return;
                    checkMixedHands("stray", 15, false);
                    nextScenario(stray);
                    stray = null;
                    beginPiercingScenario(false);
                    stage = 10;
                }
                case 10 -> {
                    if (advancePillagerTurn()) return;
                    checkPiercingNegativeControl();
                    nextScenario(pillager);
                    beginPiercingScenario(true);
                    stage = 11;
                }
                case 11 -> {
                    if (advancePillagerTurn()) return;
                    checkPiercingRealShot();
                    nextScenario(pillager);
                    beginLockVersusNearerTarget();
                    stage = 12;
                }
                case 12 -> {
                    if (advancePillagerTurn()) return;
                    checkLockedTargetNotSwapped();
                    nextScenario(pillager);
                    beginLockLostScenario(MoveMutation.LEAVE_LOCKED_ONLY);
                    stage = 13;
                }
                case 13 -> {
                    if (advancePillagerTurn()) return;
                    checkOneBoundedReplan();
                    nextScenario(pillager);
                    beginLockLostScenario(MoveMutation.LEAVE_EVERY_MEMBER);
                    stage = 14;
                }
                case 14 -> {
                    if (advancePillagerTurn()) return;
                    checkNoTargetEndsSafely();
                    nextScenario(pillager);
                    beginTippedArrow(EntityType.STRAY);
                    stage = 15;
                }
                case 15 -> {
                    if (!battle.isPlayerTurnNow()) return;
                    if (ownerHits == 0) { handOverTurn(); return; }
                    checkTippedArrow("stray", MobEffects.MOVEMENT_SLOWDOWN, 600, slowAtHit);
                    nextScenario(stray);
                    stray = null;
                    beginTippedArrow(EntityType.BOGGED);
                    stage = 16;
                }
                case 16 -> {
                    if (!battle.isPlayerTurnNow()) return;
                    if (ownerHits == 0) { handOverTurn(); return; }
                    checkTippedArrow("bogged", MobEffects.POISON, 100, poisonAtHit);
                    nextScenario(bogged);
                    beginThrowingSalvo();
                    stage = 17;
                }
                case 17 -> {
                    if (advancePillagerTurn()) return;
                    checkLaterSalvoFailureIsNotARefund();
                    Tactical.LOGGER.info("[Tactical] RANGED MOB AI SELFTEST PASSED: "
                            + "multishot salvo loaded and fired inside one turn epoch for one 20 AP action with the turn "
                            + "held until every projectile settled and no player ammunition or AP consumed; pre-charged "
                            + "real projectiles used instead of reloaded; loaded rocket refused, kept charged and unpaid; "
                            + "a cancelled arrow join returns hands, ammunition, charge and pose without charging; the last "
                            + "durability point still commits the frozen 20; mixed hands fire the weapon the adapter declares "
                            + "from the hand that really holds it; real piercing plans and lands where a non-piercing shot "
                            + "cannot; a locked opponent is not swapped for a nearer one, a lost lock replans exactly once and "
                            + "a turn with no legal opponent ends safely; a salvo whose later projectile join fails after "
                            + "the first one really joined still pays its frozen 20, keeps the ammunition and charge it "
                            + "spent, and resolves its surviving projectile; native stray slowness 600 and bogged poison "
                            + "100 arrows land and stay under the affected actor's own-turn effect clock");
                    finish(event.getServer());
                }
                default -> throw new AssertionError("unknown fixture stage " + stage);
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] RANGED MOB AI SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    // ------------------------------------------------------------------
    // scenario setup
    // ------------------------------------------------------------------

    private static void beginEmptyCrossbowSalvo() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        // Real multishot on the real weapon: the load must draw three arrows, not a fixed pile.
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment(Enchantments.MULTISHOT), 1);
        crossbow.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        require(!CrossbowItem.isCharged(crossbow), "the pillager starts with an empty crossbow");
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        startBattle();
        ownerArrowsBeforeEnemyTurn = owner.getOffhandItem().getCount();
        battle.endPlayerTurn();
    }

    private static void beginPrechargedSalvo() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES, chargedArrows(3));
        require(CrossbowItem.isCharged(crossbow), "the pre-charged crossbow is really charged");
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        startBattle();
        battle.endPlayerTurn();
    }

    private static void beginRocketScenario() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.set(DataComponents.CHARGED_PROJECTILES,
                ChargedProjectiles.of(List.of(new ItemStack(Items.FIREWORK_ROCKET))));
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        startBattle();
        battle.endPlayerTurn();
    }

    /** R1-1: an empty crossbow with real off-hand ammunition, and every real arrow join cancelled. */
    private static void beginCancelledJoinScenario(boolean emptyCrossbow) {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        if (!emptyCrossbow) crossbow.set(DataComponents.CHARGED_PROJECTILES, chargedArrows(3));
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        // Real ammunition in the other hand: vanilla really splits it during the load.
        pillager.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 8));
        startBattle();
        cancelArrowJoins = true;
        battle.endPlayerTurn();
    }

    /** R1-1: the crossbow has one durability point left, and the real shot breaks it. */
    private static void beginLastDurabilityShot() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        crossbow.setDamageValue(crossbow.getMaxDamage() - 1);
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        startBattle();
        battle.endPlayerTurn();
    }

    /** R1-2: a bow in the main hand, a crossbow in the off hand. The crossbow is what really fires. */
    private static void beginMixedHandsPillager() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        pillager.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.CROSSBOW));
        startBattle();
        battle.endPlayerTurn();
    }

    /** R1-2: a crossbow in the main hand, a bow in the off hand. The bow is what really fires. */
    private static void beginMixedHandsStray() {
        stray = (Stray) spawn(EntityType.STRAY, 8.5, 0.5);
        stray.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        stray.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BOW));
        startBattle();
        battle.endPlayerTurn();
    }

    /** R1-3: a wall with one two-block opening, filled by an armor stand on the line to the target. */
    private static void beginPiercingScenario(boolean piercing) {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        if (piercing) {
            ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            enchantments.set(enchantment(Enchantments.PIERCING), 1);
            crossbow.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        }
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        buildPiercingTunnel();
        armorStand = EntityType.ARMOR_STAND.create(level);
        require(armorStand != null, "armor stand exists in this version");
        // Right next to the target: every cell on the shooter's side has the blocking entity on its line,
        // including the shooter's own cell if it walks all the way up to it.
        armorStand.setPos(origin.getX() + 1.5, origin.getY(), origin.getZ() + .5);
        armorStand.setNoGravity(true);
        // Deliberately hurt-able: an invulnerable hit deflects the real arrow, which is a different rule than
        // piercing and would make this scenario assert the wrong thing. One arrow cannot destroy it.
        require(level.addFreshEntity(armorStand), "the obstructing armor stand is really in the world");
        startBattle();
        // Recorded before the turn runs, from the shooter's own cell: the real line is the same for both counts.
        piercingBlockedWithoutEnchant = !canPlan(plannerCell, 0);
        piercingOpenWithEnchant = canPlan(plannerCell, 1);
        battle.endPlayerTurn();
    }

    /**
     * A sealed one-cell tunnel with the shooter on one side and the target on the other.
     *
     * <p>The question here is whether the same real line can be planned with and without the weapon's own
     * piercing, so the geometry has to leave exactly one cross-section open and fill it with the blocking
     * entity - no lob over the top, no walk around the side. The tunnel seals both ends, so walking cannot
     * create a second line either.</p>
     */
    private static void buildPiercingTunnel() {
        for (int dx = -6; dx <= 10; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                wall(origin.offset(dx, dy, -1));
                wall(origin.offset(dx, dy, 1));
            }
            wall(origin.offset(dx, 2, 0));
        }
        for (int dy = 0; dy <= 2; dy++) {
            wall(origin.offset(-6, dy, 0));
            wall(origin.offset(10, dy, 0));
        }
    }

    private static void wall(BlockPos cell) {
        level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        placed.add(cell);
    }

    /** R1-4: the pillager starts out of range, so its own turn really locks a target and then walks. */
    private static void beginLockVersusNearerTarget() {
        beginTwoPlayerLockScenario(MoveMutation.SECOND_BECOMES_NEARER, 17.5, 20.5);
    }

    private static void beginLockLostScenario(MoveMutation mutation) {
        beginTwoPlayerLockScenario(mutation, 17.5, 19.5);
    }

    private static void beginTwoPlayerLockScenario(MoveMutation mutation, double firstDistance, double secondDistance) {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 0.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        owner.setPos(origin.getX() + firstDistance, origin.getY(), origin.getZ() + .5);
        second.setPos(origin.getX() + secondDistance, origin.getY(), origin.getZ() + .5);
        startBattle();
        com.tactical.neoforge.battle.multi.BattleEligibility.Denial denial = battle.joinDirect(second);
        require(denial == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the second player really joins the encounter: " + denial);
        require(battle.memberCount() == 2, "both players are members before the enemy turn");
        moveMutation = mutation;
        battle.endPlayerTurn();
    }

    /**
     * R1 follow-up: the first salvo projectile really joins, a later join throws.
     *
     * <p>This is the shape the refund rule has to survive: an arrow is already in the world when the native
     * callback fails, so the action is a real shot - it keeps its frozen price, keeps the ammunition and charge
     * the load really spent, and its pending projectile must still resolve and release the turn.</p>
     */
    private static void beginThrowingSalvo() {
        pillager = (Pillager) spawn(EntityType.PILLAGER, 8.5, 0.5);
        ItemStack crossbow = new ItemStack(Items.CROSSBOW);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(enchantment(Enchantments.MULTISHOT), 1);
        crossbow.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        pillager.setItemSlot(EquipmentSlot.MAINHAND, crossbow);
        pillager.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 8));
        startBattle();
        throwOnSecondJoin = true;
        battle.endPlayerTurn();
    }

    private static void beginTippedArrow(EntityType<? extends Mob> type) {
        Mob shooter = spawn(type, 8.5, 0.5);
        if (shooter instanceof Stray value) stray = value;
        if (shooter instanceof Bogged value) bogged = value;
        // Vanilla spawn equipment, stated explicitly so the fixture never depends on a random spawn roll.
        shooter.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        startBattle();
        battle.endPlayerTurn();
    }

    // ------------------------------------------------------------------
    // assertions
    // ------------------------------------------------------------------

    private static void checkEmptyCrossbowSalvo() {
        require(sampled(), "the pillager's own turn was observed");
        int arrows = arrowCount.getOrDefault(pillager.getUUID(), 0);
        Set<Long> epochs = arrowEpochs.getOrDefault(pillager.getUUID(), Set.of());
        require(arrows == 3, "a real multishot crossbow salvo fires three actual projectiles, got " + arrows);
        require(epochs.size() == 1 && epochs.contains(ownTurnEpoch),
                "the whole salvo belongs to the pillager's own turn epoch " + ownTurnEpoch + ", got " + epochs);
        require(busyHeldTurn, "a pending salvo held the turn instead of handing control back early");
        require(!CrossbowItem.isCharged(pillager.getMainHandItem()),
                "the fired salvo consumed the real charged projectiles");
        require(turnStartAp - lowestAp == 20,
                "the salvo costs exactly one crossbow action of 20 AP (start " + turnStartAp
                        + ", lowest " + lowestAp + ")");
        require(ownerHits >= 1, "a real crossbow arrow hit the actual owner");
        require(owner.getHealth() < owner.getMaxHealth(), "arrow damage is real authoritative health loss");
        require(battle.pendingProjectiles() == 0, "the player turn resumed only after every projectile settled");
        require(owner.getOffhandItem().getCount() == ownerArrowsBeforeEnemyTurn,
                "the monster drew no ammunition from the player's inventory");
        require(battle.actorAp(owner) == ownerApBeforeEnemyTurn,
                "the enemy's action never touched the other participant's AP");
        require(crossbowClass(pillager), "the salvo really flew at the crossbow arrow power, not on bow numbers");
    }

    private static void checkPrechargedSalvo() {
        require(sampled(), "the pillager's own turn was observed");
        int arrows = arrowCount.getOrDefault(pillager.getUUID(), 0);
        require(arrows == 3, "the three really charged arrows are used instead of one reloaded arrow, got " + arrows);
        require(arrowEpochs.getOrDefault(pillager.getUUID(), Set.of()).contains(ownTurnEpoch),
                "the pre-charged salvo also fires inside the pillager's own turn epoch");
        require(turnStartAp - lowestAp == 20, "a pre-charged salvo still costs one crossbow action of 20 AP");
        require(!CrossbowItem.isCharged(pillager.getMainHandItem()), "the pre-charged projectiles were really fired");
        require(ownerHits >= 1, "a pre-charged salvo really hits");
    }

    private static void checkRocketRefusal() {
        require(sampled(), "the pillager's own turn was observed");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 0,
                "an unsupported crossbow rocket is never fired as if it were an arrow");
        require(CrossbowItem.isCharged(pillager.getMainHandItem()),
                "the refused rocket stays really loaded instead of being cleared or converted");
        require(turnStartAp - lowestAp == 0, "a refused rocket shot costs no AP (spent "
                + (turnStartAp - lowestAp) + ")");
        require(journalHas(BattleText.KEY_ENEMY_ROCKET_UNSUPPORTED),
                "the refusal is recorded as the rocket-unsupported structure row");
    }

    /**
     * R1-1: no projectile reached the world, so the whole load/fire transaction has to be given back.
     *
     * <p>Vanilla already cleared the charged projectiles and split the off-hand ammunition before the join
     * was cancelled; the assertion is that the actor is back to exactly its pre-turn hands and paid nothing.</p>
     */
    private static void checkCancelledJoinGaveEverythingBack(boolean emptiedCrossbow) {
        require(sampled(), "the pillager's own turn was observed");
        require(cancelledJoins > 0, "the native shot really tried to add a projectile (cancelled "
                + cancelledJoins + ")");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 0, "no arrow reached the world");
        require(turnStartAp - lowestAp == 0, "a shot with no projectile costs no AP (spent "
                + (turnStartAp - lowestAp) + ")");
        ItemStack weapon = pillager.getMainHandItem();
        require(weapon.is(Items.CROSSBOW), "the crossbow is still the held weapon");
        require(weapon.getDamageValue() == 0, "the cancelled shot spent no durability (damage "
                + weapon.getDamageValue() + ")");
        if (emptiedCrossbow) {
            require(!CrossbowItem.isCharged(weapon), "the cancelled load left no charged projectiles behind");
            require(pillager.getOffhandItem().getCount() == 8,
                    "the ammunition the load really split was given back (offhand "
                            + pillager.getOffhandItem().getCount() + ")");
        } else {
            require(CrossbowItem.isCharged(weapon), "the already charged crossbow keeps its real load");
            require(chargedCount(weapon) == 3, "all three charged arrows are still there, got "
                    + chargedCount(weapon));
            require(pillager.getOffhandItem().getCount() == 8,
                    "a pre-charged shot drew nothing from the ammunition stack");
        }
        require(journalHas(BattleText.KEY_ENEMY_NO_SHOT),
                "the attempt is recorded as a no-shot instead of a paid action");
    }

    /** R1-1: the quote is frozen before the fire, so a shot that destroys the weapon still costs its price. */
    private static void checkLastDurabilityStillCostsTheFullQuote() {
        require(sampled(), "the pillager's own turn was observed");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) >= 1, "the last-durability shot really fired");
        require(pillager.getMainHandItem().isEmpty() || pillager.getMainHandItem().getDamageValue()
                        >= pillager.getMainHandItem().getMaxDamage() - 1,
                "the crossbow really broke on that shot");
        require(turnStartAp - lowestAp == 20,
                "the frozen quote is committed even though the weapon is gone (start " + turnStartAp
                        + ", lowest " + lowestAp + ")");
    }

    /**
     * R1-2: mixed hands fire the weapon the adapter declares, from the hand that really holds it, and the
     * other hand's weapon is neither consumed nor damaged.
     */
    private static void checkMixedHands(String name, int expectedCost, boolean crossbowExpected) {
        Mob shooter = "pillager".equals(name) ? pillager : stray;
        require(sampled(), name + "'s own turn was observed");
        int arrows = arrowCount.getOrDefault(shooter.getUUID(), 0);
        require(arrows == 1, name + " fires exactly one real projectile, got " + arrows);
        require(turnStartAp - lowestAp == expectedCost,
                name + " pays its own " + expectedCost + " AP quote (start " + turnStartAp
                        + ", lowest " + lowestAp + ")");
        Double speed = arrowSpeed.get(shooter.getUUID());
        require(speed != null, name + " projectile speed was observed at join");
        if (crossbowExpected) {
            require(speed > 2.0, name + " really fired the crossbow from the off hand (launch speed " + speed + ")");
            require(shooter.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).is(Items.BOW)
                            && shooter.getMainHandItem().getDamageValue() == 0,
                    name + " left the main-hand bow untouched");
            require(ownerHits >= 1, name + " crossbow arrow really hits");
        } else {
            require(speed < 2.0, name + " really fired the bow from the off hand (launch speed " + speed + ")");
            require(shooter.getMainHandItem().is(Items.CROSSBOW)
                            && shooter.getMainHandItem().getDamageValue() == 0,
                    name + " left the main-hand crossbow untouched");
            require(ownerHits >= 1, name + " bow arrow really hits");
            require(slowAtHit == 600 || duration(owner, MobEffects.MOVEMENT_SLOWDOWN) == 600,
                    name + " bow arrow still carries its native tipped effect");
        }
    }

    /** R1-3: without piercing the same real line cannot be planned at all. */
    private static void checkPiercingNegativeControl() {
        require(piercingBlockedWithoutEnchant,
                "a non-piercing shot cannot be planned through the blocking entity");
        require(piercingOpenWithEnchant,
                "the same real line is legal once one entity may be pierced");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 0,
                "the AI fired nothing through a line it cannot plan");
    }

    /** R1-3: with real piercing the shot is planned, lands, and the real arrow carries that level. */
    private static void checkPiercingRealShot() {
        require(sampled(), "the pillager's own turn was observed");
        require(piercingOpenWithEnchant, "the real piercing line stays legal");
        int arrows = arrowCount.getOrDefault(pillager.getUUID(), 0);
        require(arrows == 1, "one real piercing arrow is fired, got " + arrows);
        require(arrowPierce.getOrDefault(pillager.getUUID(), 0) == 1,
                "the real arrow really carries piercing level 1, got "
                        + arrowPierce.getOrDefault(pillager.getUUID(), 0));
        require(ownerHits >= 1, "the piercing arrow passed the blocking entity and hit the real owner");
        require(turnStartAp - lowestAp == 20, "the piercing salvo still costs one 20 AP action");
    }

    /** R1-4: a locked opponent is not replaced by a nearer one. */
    private static void checkLockedTargetNotSwapped() {
        require(sampled(), "the pillager's own turn was observed");
        require(mutationApplied, "the second player really became closer after the lock was taken");
        require(ownerHits >= 1, "the action still resolved against the locked player");
        require(secondHits == 0, "the nearer player was never attacked (" + secondHits + " hits)");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 1, "exactly one arrow was fired");
        require(journalHas(BattleText.KEY_ATTACK_ENEMY_RANGED), "the shot is recorded as one paid ranged attack");
    }

    /** R1-4: a lost lock allows exactly one bounded replan onto a legal opponent. */
    private static void checkOneBoundedReplan() {
        require(sampled(), "the pillager's own turn was observed");
        require(mutationApplied, "the locked player really left the encounter during the turn");
        require(journalHas(BattleText.KEY_ENEMY_RETARGET),
                "the lost lock is recorded as exactly one retarget row");
        require(secondHits >= 1, "the single replan really found and hit the remaining legal opponent");
        require(ownerHits == 0, "the player that left was not hit");
        require(!battle.isBusy(), "the turn ended without leaving the encounter busy");
    }

    /** R1-4: with no legal opponent left the turn ends safely instead of shooting at nothing. */
    private static void checkNoTargetEndsSafely() {
        require(mutationApplied, "every member really left during the enemy turn");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 0, "no arrow was invented");
        require(!battle.isBusy(), "nothing is left marked as moving, pending or falling");
        require(battle.pendingProjectiles() == 0, "no projectile is left pending");
        require(journalHas(BattleText.KEY_ENEMY_RETARGET) || battle.finished(),
                "the turn either ended on its own or the encounter closed with no participants");
    }

    /**
     * R1 follow-up: a later salvo failure is not a refund, because a real projectile already exists.
     *
     * <p>What is asserted: the failure really happened, only the first arrow is real, the frozen 20 is still
     * committed once, the load is not rolled back (the crossbow stays empty and the ammunition it really split
     * stays spent), the surviving projectile was tracked as pending, resolved, and hit.</p>
     */
    private static void checkLaterSalvoFailureIsNotARefund() {
        require(sampled(), "the pillager's own turn was observed");
        require(joinThrows == 1, "a later salvo projectile join really failed (throws " + joinThrows + ")");
        require(arrowCount.getOrDefault(pillager.getUUID(), 0) == 1,
                "exactly the first projectile reached the world, got "
                        + arrowCount.getOrDefault(pillager.getUUID(), 0));
        require(turnStartAp - lowestAp == 20,
                "a salvo that really fired keeps its frozen 20 AP (start " + turnStartAp
                        + ", lowest " + lowestAp + ")");
        require(!CrossbowItem.isCharged(pillager.getMainHandItem()),
                "the load was not rolled back: the fired charge is really gone");
        require(pillager.getOffhandItem().getCount() == 7,
                "the ammunition the load really spent stays spent, offhand "
                        + pillager.getOffhandItem().getCount());
        require(busyHeldTurn, "the surviving projectile was tracked as pending and held the turn");
        require(battle.pendingProjectiles() == 0, "the surviving projectile settled before the turn returned");
        require(ownerHits >= 1, "the surviving projectile really hit the owner");
        require(journalHas(BattleText.KEY_ATTACK_ENEMY_RANGED),
                "the salvo is recorded as one paid ranged attack");
        require(!journalHas(BattleText.KEY_ENEMY_NO_SHOT), "no refund row was published for a real shot");
    }

    private static void checkTippedArrow(String name, Holder<MobEffect> effect, int nativeDuration, int observedAtHit) {
        Mob shooter = "stray".equals(name) ? stray : bogged;
        int arrows = arrowCount.getOrDefault(shooter.getUUID(), 0);
        require(arrows == 1, name + " fires exactly one real arrow in its own turn, got " + arrows);
        require(ownerHits >= 1, name + " arrow really hits the owner");
        require(observedAtHit == nativeDuration, name + " arrow carries its native duration " + nativeDuration
                + ", observed " + observedAtHit);
        require(duration(owner, effect) == nativeDuration,
                name + " effect is still exactly " + nativeDuration + " on the affected actor's own turn, so wall "
                        + "ticks did not age it (got " + duration(owner, effect) + ")");
    }

    // ------------------------------------------------------------------
    // turn driving
    // ------------------------------------------------------------------

    /**
     * Drives one enemy turn to completion.
     *
     * @return true while the fixture must wait for another tick
     */
    private static boolean advancePillagerTurn() {
        sampleActingTurn();
        // A closed encounter has no player turn to return to: the scenario's turn is over, not still running.
        // This is the fixture's own stop condition - nothing in the product is asked to revive an empty battle.
        if (battle.finished()) return false;
        if (!battle.isPlayerTurnNow()) {
            applyMoveMutation();
            return true;
        }
        if (!done()) {
            handOverTurn();
            return true;
        }
        return false;
    }

    private static boolean done() {
        return armed() == null || sampled();
    }

    /**
     * Applies this scenario's world change exactly once, after the acting pillager really started walking.
     *
     * <p>Walking is the only window between "the turn locked an opponent" and "the action resolves", so the
     * mutation cannot be mistaken for something planning already saw.</p>
     */
    private static void applyMoveMutation() {
        if (moveMutation == MoveMutation.NONE) return;
        if (!battle.isBusy() || !battle.isActorTurn(pillager)) return;
        if (mutationApplied && moveMutation != MoveMutation.SECOND_BECOMES_NEARER) return;
        mutationApplied = true;
        switch (moveMutation) {
            // Stay adjacent for every tick of the walk, so the nearer player is really nearer when the shot resolves.
            case SECOND_BECOMES_NEARER -> second.setPos(pillager.getX() + 1.0D, pillager.getY(), pillager.getZ() + 1.0D);
            case LEAVE_LOCKED_ONLY -> battle.leave(owner, BattleText.exitReason(BattleText.Exit.DEFEATED));
            case LEAVE_EVERY_MEMBER -> {
                battle.leave(owner, BattleText.exitReason(BattleText.Exit.DEFEATED));
                if (!battle.finished()) battle.leave(second, BattleText.exitReason(BattleText.Exit.DEFEATED));
            }
            default -> { }
        }
    }

    private static Mob armed() {
        if (pillager != null) return pillager;
        if (stray != null) return stray;
        return bogged;
    }

    private static void sampleActingTurn() {
        if (battle == null) return;
        UUID actorId = battle.currentActorId();
        observedActors.add(actorId == null ? "none" : actorId.toString());
        Mob actor = armed();
        if (actor != null && actor.getUUID().equals(actorId)) {
            int ap = battle.actorAp(actor);
            if (turnStartAp < 0) {
                turnStartAp = ap;
                ownerApBeforeEnemyTurn = battle.actorAp(owner);
            }
            if (ownTurnEpoch < 0) ownTurnEpoch = battle.turnEpoch();
            lowestAp = Math.min(lowestAp, ap);
            if (battle.pendingProjectiles() > 0) {
                busyHeldTurn = true;
                require(!battle.isPlayerTurnNow(), "a pending salvo cannot hand control back to the player early");
            }
        }
    }

    private static boolean sampled() { return turnStartAp >= 0 && lowestAp != Integer.MAX_VALUE; }

    /**
     * Hands the turn back to the AI, retrying while the owner still holds it.
     *
     * <p>An order that arrives before the encounter is ready is refused rather than queued, so the fixture asks
     * again instead of treating one refused hand-over as a missing enemy turn.</p>
     */
    private static void handOverTurn() {
        Mob armed = pillager != null ? pillager : stray != null ? stray : bogged;
        require(++handOverAttempts <= 300, "the armed mob never took its own turn: observed actors "
                + observedActors + ", expected " + (armed == null ? "none" : armed.getUUID().toString()));
        battle.endPlayerTurn();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static void startBattle() {
        resetObservations();
        // Fixture-only publication capture: a headless connection cannot carry a payload, so the rows this
        // encounter really published are read back from the capture instead of from the wire.
        ServerPackets.startCapture();
        BattleManager.reset();
        require(BattleManager.start(owner, true), "ranged mob encounter starts");
        battle = BattleManager.current();
        require(battle != null && battle.isPlayerTurnNow(), "the high-initiative owner acts first");
        Mob armed = pillager != null ? pillager : stray != null ? stray : bogged;
        require(armed != null && battle.participants().contains(armed),
                "the armed mob is really a participant of this encounter: armed=" + describe(armed)
                        + " roster=" + battle.participants().stream().map(RangedMobAiSelfTest::describe).toList());
        plannerCell = battle.currentActorCell(armed);
        owner.setHealth(owner.getMaxHealth());
        owner.invulnerableTime = 0;
        ownerApBeforeEnemyTurn = battle.actorAp(owner);
        plannerCell = battle.currentActorCell(armed);
        for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(32))) {
            if (other != pillager && other != stray && other != bogged
                    && other instanceof net.minecraft.world.entity.monster.Enemy) other.discard();
        }
    }

    private static void nextScenario(Mob enemy) {
        BattleManager.reset();
        battle = null;
        cancelArrowJoins = false;
        throwOnSecondJoin = false;
        moveMutation = MoveMutation.NONE;
        mutationApplied = false;
        if (enemy != null) enemy.discard();
        // The fields describe the scenario that is running: a stale one would answer for the next scenario.
        pillager = null;
        stray = null;
        bogged = null;
        if (armorStand != null) {
            armorStand.discard();
            armorStand = null;
        }
        for (BlockPos cell : placed) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        if (second != null) {
            second.setHealth(second.getMaxHealth());
            second.invulnerableTime = 0;
            second.setPos(origin.getX() + 25.5D, origin.getY(), origin.getZ() + .5);
        }
        if (owner != null) {
            owner.setHealth(owner.getMaxHealth());
            owner.invulnerableTime = 0;
            owner.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
            if (owner.hasEffect(MobEffects.MOVEMENT_SLOWDOWN)) owner.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
            if (owner.hasEffect(MobEffects.POISON)) owner.removeEffect(MobEffects.POISON);
        }
        resetObservations();
    }

    private static void resetObservations() {
        arrowCount.clear();
        arrowEpochs.clear();
        arrowSpeed.clear();
        arrowPierce.clear();
        ownerHits = 0;
        secondHits = 0;
        cancelledJoins = 0;
        arrowJoinAttempts = 0;
        joinThrows = 0;
        slowAtHit = -1;
        poisonAtHit = -1;
        ownTurnEpoch = -1L;
        turnStartAp = -1;
        lowestAp = Integer.MAX_VALUE;
        busyHeldTurn = false;
        handOverAttempts = 0;
        observedActors.clear();
        plannerCell = null;
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

    private static ChargedProjectiles chargedArrows(int count) {
        List<ItemStack> arrows = new ArrayList<>();
        for (int i = 0; i < count; i++) arrows.add(new ItemStack(Items.ARROW));
        return ChargedProjectiles.of(arrows);
    }

    private static int chargedCount(ItemStack weapon) {
        ChargedProjectiles charged = weapon.get(DataComponents.CHARGED_PROJECTILES);
        return charged == null ? 0 : charged.getItems().size();
    }

    private static boolean canPlan(GridPos from, int pierce) {
        if (from == null || owner == null) return false;
        return BallisticShot.plan(level, pillager, from, owner, com.tactical.core.combat.RangedWeapon.CROSSBOW,
                pierce) != null;
    }

    private static boolean crossbowClass(Mob shooter) {
        Double speed = arrowSpeed.get(shooter.getUUID());
        return speed != null && speed > 2.0;
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 10, spawn.getY() + 20);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 24) >> 4; cx <= (origin.getX() + 24) >> 4; cx++) {
            for (int cz = (origin.getZ() - 24) >> 4; cz <= (origin.getZ() + 24) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -30; x <= 30; x++) for (int z = -8; z <= 8; z++) {
            level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int dy = 0; dy < 6; dy++) level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
        }
        owner = player(server, "tactical-ranged-mobs-owner", "RangedMobOwner");
        second = player(server, "tactical-ranged-mobs-second", "RangedMobSecond");
        second.setPos(origin.getX() + 25.5D, origin.getY(), origin.getZ() + .5);
    }

    private static ServerPlayer player(MinecraftServer server, String key, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        // A headless connection object: the encounter authorizes and publishes exactly as it does for a client,
        // and the fixture-only capture records what it really published.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        player.setHealth(100);
        // A melee item and real ammunition the monsters must never borrow, even though the arrows exist here.
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 16));
        level.addNewPlayer(player);
        return player;
    }

    private static void finish(MinecraftServer server) {
        enabled = false;
        BattleManager.reset();
        if (pillager != null) pillager.discard();
        if (stray != null) stray.discard();
        if (bogged != null) bogged.discard();
        if (armorStand != null) armorStand.discard();
        for (BlockPos cell : placed) if (level != null) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        for (ServerPlayer player : List.of(owner, second)) {
            if (player != null && !player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        ServerPackets.stopCapture();
        if (level != null) for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        BattleEffects.clear();
        server.halt(false);
    }

    private static String describe(LivingEntity entity) {
        if (entity == null) return "none";
        return entity.getType().toShortString() + "#" + entity.getId() + "@" + entity.blockPosition()
                + (entity.isRemoved() ? "(removed)" : "") + (entity.isAlive() ? "" : "(dead)");
    }

    private static boolean counted(Mob mob) {
        return mob == pillager || mob == stray || mob == bogged;
    }

    private static Holder<Enchantment> enchantment(net.minecraft.resources.ResourceKey<Enchantment> key) {
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
    }

    private static int duration(LivingEntity actor, Holder<MobEffect> effect) {
        MobEffectInstance instance = actor.getEffect(effect);
        return instance == null ? 0 : instance.getDuration();
    }

    /** The journal rows the server really published, read through the fixture capture. */
    private static boolean journalHas(String key) {
        // Both fixture players are recipients: a member that left the encounter stops receiving rows, and the
        // other one still does.
        for (ServerPlayer recipient : List.of(owner, second)) {
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
