package com.tactical.neoforge.battle;

import com.mojang.authlib.GameProfile;
import com.tactical.core.combat.ApCalculator;
import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Disposable-world dedicated-server fixture for the vindicator adapter: {@code -Dtactical.selftest=vindicator}.
 *
 * <p>Every scenario uses real entities, a real battle and the real vanilla axe, movement and removal paths,
 * then reads the authoritative server result. What is proven here, one closed item at a time:</p>
 * <ul>
 *   <li>a real axe swing hits a real member or a real controlled companion, for one quoted melee price, and
 *       never the other participant's resources;</li>
 *   <li>a <em>Johnny</em>-named vindicator still only picks this battle's players and controlled companions: the
 *       villager and the animal standing next to it are never touched;</li>
 *   <li>a closed wooden door is walked around whenever a door-free route exists;</li>
 *   <li>when the only way is through one closed oak door, the turn walks to its front, breaks that one pair for
 *       one quoted price, drops exactly one door, does not swing the axe in the same turn, and really walks
 *       through the new doorway on the next turn to hit again;</li>
 *   <li>mob griefing off, a cancelled entity break event, a door opened or replaced mid-walk, a block placed in
 *       the walked path and an undecided search each leave the door and the price alone.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class VindicatorSelfTest {
    /** What the fixture changes in the world once the acting vindicator really started walking. */
    private enum WalkChange { NONE, OPEN_DOOR, BLOCK_THE_PATH, REPLACE_DOOR_WITH_STONE }

    private static boolean enabled;
    private static int ticks, stage;
    private static ServerLevel level;
    private static MinecraftServer server;
    private static BlockPos origin;
    private static ServerPlayer owner;
    private static ServerPlayer second;
    private static Vindicator vindicator;
    private static Wolf wolf;
    private static Villager villager;
    private static Cow cow;
    private static ServerBattle battle;
    private static final List<BlockPos> forced = new ArrayList<>();
    private static final List<BlockPos> placed = new ArrayList<>();

    private static final Set<String> observedActors = new LinkedHashSet<>();
    private static int turnStartAp = -1;
    private static int handOverAttempts;
    private static boolean actorWasCurrent;
    private static int completedTurns;
    private static int ownerHits;
    private static int secondHits;
    private static int wolfHits;
    /** The other units' own AP, read once at the start of the acting vindicator's turn. */
    private static int ownerApObserved = -1;
    private static int secondApObserved = -1;
    private static int wolfApObserved = -1;
    private static double wolfHealthBefore;
    private static double ownerHealthBefore;
    private static double secondHealthBefore;
    private static int chunksBeforeTurn;

    private static WalkChange walkChange = WalkChange.NONE;
    private static boolean changeApplied;
    private static boolean cancelDoorBreak;
    private static BlockPos watchedDoorLower;
    private static int doorDropsBefore;

    private VindicatorSelfTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        enabled = "vindicator".equals(System.getProperty("tactical.selftest"));
        ticks = stage = 0;
        resetObservations();
    }

    /** The real cancellable entity-break event: a mod refusing the break must stop it and cost nothing. */
    @SubscribeEvent
    public static void onDestroyBlock(LivingDestroyBlockEvent event) {
        if (!enabled || !cancelDoorBreak) return;
        if (event.getEntity() != vindicator) return;
        if (watchedDoorLower != null && !watchedDoorLower.equals(event.getPos())) return;
        event.setCanceled(true);
    }

    /** The real damage each unit took, read from the authoritative post-damage event. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void onDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!enabled || event.getNewDamage() <= 0) return;
        LivingEntity hurt = event.getEntity();
        if (hurt == owner) ownerHits++;
        else if (hurt == second) secondHits++;
        else if (hurt == wolf) wolfHits++;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!enabled || ++ticks < 20) return;
        try {
            require(ticks < 9000, "vindicator fixture completion deadline");
            switch (stage) {
                case 0 -> {
                    setup(event.getServer());
                    stage = 1;
                }
                case 1 -> {
                    if (ticks < 100) return;
                    beginAxeOnPlayer();
                    stage = 2;
                }
                case 2 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkAxeOnPlayer();
                    nextScenario();
                    beginJohnnyAgainstWolf();
                    stage = 3;
                }
                case 3 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkJohnnyOnlyHitsTheCompanion();
                    nextScenario();
                    beginDetourScenario();
                    stage = 4;
                }
                case 4 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkDetourKeptTheDoor();
                    nextScenario();
                    beginOnlyDoorScenario();
                    stage = 5;
                }
                case 5 -> {
                    // The break turn: walk to the front of the only door and break exactly it.
                    if (advanceVindicatorTurn(1)) return;
                    checkOneDoorBreak();
                    // Hand the next own turn to the vindicator, so it can really use the new doorway.
                    handOverPlayerTurn();
                    resetTurnObservations();
                    stage = 6;
                }
                case 6 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkWalkedThroughAndHit();
                    nextScenario();
                    beginMobGriefingOffScenario();
                    stage = 7;
                }
                case 7 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkMobGriefingKeptTheDoor();
                    restoreMobGriefing();
                    nextScenario();
                    beginCancelledEventScenario();
                    stage = 8;
                }
                case 8 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkCancelledEventKeptTheDoor();
                    nextScenario();
                    beginWalkChangeScenario(WalkChange.OPEN_DOOR, 4);
                    stage = 9;
                }
                case 9 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkOpenedDoorWasNotBroken();
                    nextScenario();
                    beginWalkChangeScenario(WalkChange.BLOCK_THE_PATH, 6);
                    stage = 10;
                }
                case 10 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkBlockedPathRefundedTheUnwalkedSteps();
                    nextScenario();
                    beginWalkChangeScenario(WalkChange.REPLACE_DOOR_WITH_STONE, 6);
                    stage = 11;
                }
                case 11 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkReplacedDoorWasNotBroken();
                    nextScenario();
                    beginUndecidedSearchScenario();
                    stage = 12;
                }
                case 12 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkUndecidedSearchKeptTheDoor();
                    nextScenario();
                    beginNearestSealedScenario();
                    stage = 13;
                }
                case 13 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkNearestSealedWasLeftAlone();
                    nextScenario();
                    beginStepUpScenario();
                    stage = 14;
                }
                case 14 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkStepUpWasWalkedAndHit();
                    nextScenario();
                    beginRemovalFailureScenario();
                    stage = 15;
                }
                case 15 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkRemovalFailureLeftThePairWhole();
                    nextScenario();
                    beginChunkLoadScenario();
                    stage = 16;
                }
                case 16 -> {
                    if (advanceVindicatorTurn(1)) return;
                    checkSearchLoadedNoChunk();
                    nextScenario();
                    stage = 17;
                    Tactical.LOGGER.info("[Tactical] VINDICATOR AI SELFTEST PASSED: "
                            + "a real iron axe swing through the production turn and action outlets hit a real "
                            + "member and a real controlled companion for one quoted melee price with the other "
                            + "participant's health and AP untouched; a Johnny-named vindicator still only picked "
                            + "this battle's players and companions while the villager and animal beside it were "
                            + "never touched; a closed wooden door in an open field was walked around and left "
                            + "intact; with a sealed room the only closed oak door was reached by paying the real "
                            + "steps, broken once for one quoted price with both halves gone and exactly one door "
                            + "dropped, no axe swing in that same turn, and the next real turn walked through the "
                            + "new doorway and hit again; mob griefing off and a cancelled entity break event left "
                            + "the door and the price alone with a refusal row; a door opened mid-walk was walked "
                            + "through instead of broken; a door replaced mid-walk was not broken; a block placed "
                            + "in the walked path refunded the unwalked steps and ended the turn; an undecided "
                            + "search never broke anything; a nearer member sealed behind solid stone was left "
                            + "alone while the reachable member was hunted and really hit; the approved "
                            + "one-block step up was walked instead of being taken for a wall, and the member "
                            + "on the platform was hit; a refused removal gave the cleared half back, left the "
                            + "pair whole, dropped nothing and charged only the real step; and the route "
                            + "searches loaded no chunk");
                    finish(event.getServer());
                }
                default -> throw new AssertionError("unknown fixture stage " + stage);
            }
        } catch (RuntimeException | AssertionError failure) {
            Tactical.LOGGER.error("[Tactical] VINDICATOR AI SELFTEST FAILED at stage {} tick {}", stage, ticks, failure);
            finish(event.getServer());
            // Explicit non-zero exit: a green Gradle task must never look like a passing fixture.
            Runtime.getRuntime().halt(1);
            throw failure;
        }
    }

    // ------------------------------------------------------------------
    // scenarios
    // ------------------------------------------------------------------

    /** A real axe swing against a real member, with a second member standing elsewhere. */
    private static void beginAxeOnPlayer() {
        vindicator = vindicator(5, 0);
        owner.setPos(origin.getX() + 6.5D, origin.getY(), origin.getZ() + .5D);
        second.setPos(origin.getX() - 12.5D, origin.getY(), origin.getZ() + .5D);
        startBattle();
        require(battle.joinDirect(second) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the second real player joins the encounter");
        ownerHealthBefore = owner.getHealth();
        battle.endPlayerTurn();
    }

    /** A Johnny-named vindicator with a companion, a villager and an animal all within one cell. */
    private static void beginJohnnyAgainstWolf() {
        vindicator = vindicator(5, 0);
        vindicator.setCustomName(net.minecraft.network.chat.Component.literal("Johnny"));
        owner.setPos(origin.getX() - 10.5D, origin.getY(), origin.getZ() + .5D);
        wolf = wolf(6, 0);
        villager = (Villager) spawn(EntityType.VILLAGER, 4, 0);
        cow = (Cow) spawn(EntityType.COW, 5, 1);
        startBattle();
        // The production start path already brings the owner's own standing wolf along; the explicit entry is
        // only the fallback for a companion that turns up later, so a duplicate must not be demanded here.
        require(battle.isCompanion(wolf) || BattleParticipation.tryAddCompanion(wolf),
                "the owner's wolf really joins as a controlled companion");
        require(battle.isCompanion(wolf), "the wolf is a controlled companion of this encounter");
        wolfHealthBefore = wolf.getHealth();
        ownerHealthBefore = owner.getHealth();
        battle.endPlayerTurn();
    }

    /** One closed door in an open field: a real detour exists, so the door must survive. */
    private static void beginDetourScenario() {
        vindicator = vindicator(0, 0);
        owner.setPos(origin.getX() + 8.5D, origin.getY(), origin.getZ() + .5D);
        door(4, 0);
        startBattle();
        ownerHealthBefore = owner.getHealth();
        doorDropsBefore = doorDrops();
        battle.endPlayerTurn();
    }

    /** The approved break case: a sealed room whose only way in is one closed oak door. */
    private static void beginOnlyDoorScenario() {
        sealPlayerRoom();
        vindicator = vindicator(4, 0);
        startBattle();
        ownerHealthBefore = owner.getHealth();
        doorDropsBefore = doorDrops();
        battle.endPlayerTurn();
    }

    private static void beginMobGriefingOffScenario() {
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
        sealPlayerRoom();
        vindicator = vindicator(4, 0);
        startBattle();
        doorDropsBefore = doorDrops();
        battle.endPlayerTurn();
    }

    private static void restoreMobGriefing() {
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true, level.getServer());
    }

    private static void beginCancelledEventScenario() {
        sealPlayerRoom();
        watchedDoorLower = feet(2, 0);
        cancelDoorBreak = true;
        vindicator = vindicator(4, 0);
        startBattle();
        doorDropsBefore = doorDrops();
        battle.endPlayerTurn();
    }

    /**
     * A walk that the world changes under: the door is opened, a block appears in the walked path, or the door
     * is replaced by stone, all while the acting vindicator is really moving.
     */
    private static void beginWalkChangeScenario(WalkChange change, int startX) {
        sealPlayerRoom();
        vindicator = vindicator(startX, 0);
        startBattle();
        doorDropsBefore = doorDrops();
        walkChange = change;
        changeApplied = false;
        battle.endPlayerTurn();
    }

    /** A map the route search cannot decide: nothing is proven, so nothing may be broken. */
    private static void beginUndecidedSearchScenario() {
        sealPlayerRoom();
        ServerBattle.fixtureDoorRouteBudget(4);
        vindicator = vindicator(4, 0);
        startBattle();
        doorDropsBefore = doorDrops();
        battle.endPlayerTurn();
    }

    /**
     * The nearer opponent is walled in with solid stone and no door at all; the farther one can be reached.
     *
     * <p>This is the approved "a reachable opponent in the same pool wins" rule: hunting the nearest one
     * forever would leave the vindicator walking into a wall for the whole encounter.</p>
     */
    private static void beginNearestSealedScenario() {
        // Player one stands at cell (1, 0) inside four solid stone walls: no door, so nothing to break.
        owner.setPos(origin.getX() + 1.5D, origin.getY(), origin.getZ() + .5D);
        wall(0, 0);
        wall(1, 1);
        wall(1, -1);
        wall(2, 0);
        second.setPos(origin.getX() + 7.5D, origin.getY(), origin.getZ() + .5D);
        second.setHealth(second.getMaxHealth());
        vindicator = vindicator(0, 0);
        startBattle();
        require(battle.joinDirect(second) == com.tactical.neoforge.battle.multi.BattleEligibility.Denial.NONE,
                "the reachable second player really joins the encounter");
        ownerHealthBefore = owner.getHealth();
        secondHealthBefore = second.getHealth();
        battle.endPlayerTurn();
    }

    /** The approved one-block step up: a platform edge is not a wall. */
    private static void beginStepUpScenario() {
        for (int x = 2; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) put(feet(x, z), Blocks.STONE.defaultBlockState());
        }
        owner.setPos(origin.getX() + 4.5D, origin.getY() + 1.0D, origin.getZ() + .5D);
        vindicator = vindicator(0, 0);
        startBattle();
        ownerHealthBefore = owner.getHealth();
        battle.endPlayerTurn();
    }

    /** The real removal call refuses after one half was already cleared. */
    private static void beginRemovalFailureScenario() {
        sealPlayerRoom();
        vindicator = vindicator(4, 0);
        startBattle();
        doorDropsBefore = doorDrops();
        ServerBattle.fixtureRefuseDoorRemoval(true);
        battle.endPlayerTurn();
    }

    /** A plain break turn, measured for chunk loading: the route searches must never generate terrain. */
    private static void beginChunkLoadScenario() {
        sealPlayerRoom();
        vindicator = vindicator(4, 0);
        startBattle();
        chunksBeforeTurn = loadedChunks();
        battle.endPlayerTurn();
    }

    // ------------------------------------------------------------------
    // assertions
    // ------------------------------------------------------------------

    private static void checkAxeOnPlayer() {
        require(sampled(), "the vindicator's own turn was observed");
        require(owner.getHealth() < ownerHealthBefore,
                "the real axe swing hurt the real member (" + ownerHealthBefore + " -> " + owner.getHealth() + ")");
        require(ownerHits >= 1, "the damage really came through a melee hit on the member");
        require(spentAp() == ApCalculator.MELEE_COST,
                "one melee action costs exactly the quoted " + ApCalculator.MELEE_COST + " AP (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ")");
        require(battle.actorAp(second) == secondApObserved,
                "the other member's own AP pool was never touched (was " + secondApObserved
                        + ", now " + battle.actorAp(second) + ")");
        require(second.getHealth() == second.getMaxHealth(), "the other member was never hit");
        require(secondHits == 0, "no second member was attacked by that turn");
    }

    private static void checkJohnnyOnlyHitsTheCompanion() {
        require(sampled(), "the vindicator's own turn was observed");
        require(wolf.getHealth() < wolfHealthBefore,
                "the axe really hurt the controlled companion (" + wolfHealthBefore + " -> " + wolf.getHealth() + ")");
        require(wolfHits >= 1, "the damage really came through a melee hit on the companion");
        require(owner.getHealth() == ownerHealthBefore, "the far member was not hit instead");
        require(villager.getHealth() == villager.getMaxHealth(),
                "a Johnny name does not widen the target pool to villagers");
        require(cow.getHealth() == cow.getMaxHealth(), "nor to animals");
        require(battle.actorAp(wolf) == wolfApObserved,
                "the companion's own AP was never touched by the other side's turn (was " + wolfApObserved
                        + ", now " + battle.actorAp(wolf) + ")");
        require(spentAp() == ApCalculator.MELEE_COST,
                "the swing still costs one quoted melee action");
    }

    private static void checkDetourKeptTheDoor() {
        require(sampled(), "the vindicator's own turn was observed");
        require(isClosedDoor(4, 0), "the closed door was left exactly as it was");
        require(doorDrops() == doorDropsBefore, "no door item was created");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "no break was recorded");
        require(owner.getHealth() < ownerHealthBefore,
                "the real detour really reached the member and hit it (" + owner.getHealth() + ")");
        require(!standsAt(4, 0), "the door cell was never walked through");
    }

    private static void checkOneDoorBreak() {
        require(sampled(), "the vindicator's own turn was observed");
        require(journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "one door break was recorded (lower="
                + level.getBlockState(feet(2, 0)) + ", upper=" + level.getBlockState(feet(2, 1))
                + ", refused=" + journalHas(BattleText.KEY_ENEMY_DOOR_REFUSED)
                + ", cell=" + battle.currentActorCell(vindicator) + ")");
        require(!isClosedDoor(2, 0),
                "both halves of the pair are gone, and the pair went as one object");
        require(doorDrops() - doorDropsBefore == 1,
                "exactly one door dropped, got " + (doorDrops() - doorDropsBefore));
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        int breakQuote = ApCalculator.paidActionCost(ApCalculator.BREAK_COST, 0);
        require(spentAp() == moveCost + breakQuote,
                "one real step plus one quoted break of " + breakQuote + " AP (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ", step " + moveCost + ")");
        require(owner.getHealth() == ownerHealthBefore,
                "the break ends the turn: no free axe swing through the new doorway in the same turn");
        require(standsAt(3, 0), "the actor broke the door from its front, not from anywhere else");
    }

    private static void checkWalkedThroughAndHit() {
        require(sampled(), "the vindicator's next own turn was observed");
        require(owner.getHealth() < ownerHealthBefore,
                "the next real turn walked through the new doorway and hit the member ("
                        + ownerHealthBefore + " -> " + owner.getHealth() + ")");
        require(doorDrops() - doorDropsBefore == 1, "the second turn broke no door and dropped none");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        // The ramp is per own turn: it resets at this actor's own turn start.
        int meleeQuote = ApCalculator.paidActionCost(ApCalculator.MELEE_COST, 0);
        require(spentAp() == moveCost + meleeQuote,
                "walking one cell through the doorway plus one quoted melee action of " + meleeQuote
                        + " (start " + turnStartAp + ", now " + battle.actorAp(vindicator) + ")");
        require(standsAt(2, 0), "the actor really stands in the doorway it opened");
    }

    private static void checkMobGriefingKeptTheDoor() {
        require(sampled(), "the vindicator's own turn was observed");
        require(isClosedDoor(2, 0), "with mob griefing off the door is untouched");
        require(doorDrops() == doorDropsBefore, "and nothing dropped");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "no break was recorded");
        require(spentAp() == 0, "nothing was paid for a break (spent "
                + (spentAp()) + ")");
        require(standsAt(4, 0), "the actor did not even walk to a door it may not break");
    }

    private static void checkCancelledEventKeptTheDoor() {
        require(sampled(), "the vindicator's own turn was observed");
        require(isClosedDoor(2, 0), "the cancelled break event left the door standing");
        require(doorDrops() == doorDropsBefore, "and nothing dropped");
        require(journalHas(BattleText.KEY_ENEMY_DOOR_REFUSED), "the refusal is recorded as a door refusal row");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() == moveCost,
                "only the real step was paid, never the refused break (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ")");
        cancelDoorBreak = false;
        watchedDoorLower = null;
    }

    private static void checkOpenedDoorWasNotBroken() {
        require(sampled(), "the vindicator's own turn was observed");
        require(changeApplied, "the door really changed under the walking actor");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "an already open door is never broken");
        require(doorDrops() == doorDropsBefore, "no door item was created");
        require(owner.getHealth() == ownerHealthBefore, "an opened door is not a swing either");
        require(opened(2, 0), "the door is still there, just open");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() == moveCost,
                "only the real step was paid, never a break (start " + turnStartAp + ", now " + battle.actorAp(vindicator) + ")");
    }

    private static void checkBlockedPathRefundedTheUnwalkedSteps() {
        require(changeApplied, "a block really appeared in the walked path");
        require(journalHas(BattleText.KEY_ENEMY_ROUTE_BLOCKED), "the changed route is recorded");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "no door was broken");
        require(doorDrops() == doorDropsBefore, "no door item was created");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() == moveCost,
                "only the cell it really walked is charged, the rest was given back (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ")");
        require(!standsAt(4, 0), "the actor never stood inside the block that appeared");
    }

    private static void checkReplacedDoorWasNotBroken() {
        require(changeApplied, "the door really was replaced under the walking actor");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "a door that is no longer a door is never broken");
        require(doorDrops() == doorDropsBefore, "no door item was created");
        require(level.getBlockState(feet(2, 0)).is(Blocks.STONE), "the replacing block is still there");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() == 3 * moveCost,
                "only the three real steps were paid, never a break (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ", cell " + battle.currentActorCell(vindicator)
                        + ", drops " + (doorDrops() - doorDropsBefore)
                        + ", break=" + journalHas(BattleText.KEY_ENEMY_BREAK_DOOR)
                        + ", turns " + completedTurns + ")");
    }

    private static void checkUndecidedSearchKeptTheDoor() {
        require(isClosedDoor(2, 0), "an undecided search never breaks a door");
        require(doorDrops() == doorDropsBefore, "and nothing dropped");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "no break was recorded");
        require(sampled(), "the vindicator's own turn was observed");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() <= moveCost,
                "at most one cautious step from an undecided map (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ")");
        ServerBattle.fixtureDoorRouteBudget(0);
    }

    private static void checkNearestSealedWasLeftAlone() {
        require(sampled(), "the vindicator's own turn was observed");
        require(second.getHealth() < secondHealthBefore,
                "the reachable member was the one hunted (" + secondHealthBefore + " -> "
                        + second.getHealth() + ")");
        require(secondHits >= 1, "the action really hit the reachable member");
        require(owner.getHealth() == ownerHealthBefore && ownerHits == 0,
                "the nearer member behind solid stone was never hit");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "there was no door to break in that room");
    }

    private static void checkStepUpWasWalkedAndHit() {
        require(sampled(), "the vindicator's own turn was observed");
        require(owner.getHealth() < ownerHealthBefore,
                "the one-block step up was walked and the member on the platform was hit ("
                        + ownerHealthBefore + " -> " + owner.getHealth() + ")");
        require(ownerHits >= 1, "the damage really came through a melee hit");
        require(standsAt(3, 1, 0) || standsAt(2, 1, 0),
                "the actor really stands on the platform, cell " + battle.currentActorCell(vindicator));
        require(battle.currentActorCell(vindicator) == null
                        || battle.currentActorCell(vindicator).y() == origin.getY() + 1,
                "and it is on the upper layer, not below the platform");
    }

    private static void checkRemovalFailureLeftThePairWhole() {
        require(sampled(), "the vindicator's own turn was observed");
        require(isClosedDoor(2, 0), "a refused removal gave the cleared half back and left the pair whole");
        require(doorDrops() == doorDropsBefore, "a refused removal created no item");
        require(journalHas(BattleText.KEY_ENEMY_DOOR_REFUSED), "the refusal is recorded as a door refusal row");
        require(!journalHas(BattleText.KEY_ENEMY_BREAK_DOOR), "no break was recorded");
        int moveCost = ApCalculator.moveCost(vindicator.getAttributeValue(Attributes.MOVEMENT_SPEED));
        require(spentAp() == moveCost,
                "only the real step was paid, never the refused removal (start " + turnStartAp
                        + ", now " + battle.actorAp(vindicator) + ")");
        ServerBattle.fixtureRefuseDoorRemoval(false);
    }

    private static void checkSearchLoadedNoChunk() {
        require(sampled(), "the vindicator's own turn was observed");
        require(chunksBeforeTurn > 0, "the chunk count was readable before the turn");
        require(loadedChunks() == chunksBeforeTurn,
                "the route searches loaded no chunk (" + chunksBeforeTurn + " -> " + loadedChunks() + ")");
    }

    // ------------------------------------------------------------------
    // turn driving
    // ------------------------------------------------------------------

    /**
     * Drives the vindicator until it has really finished {@code wanted} own turns.
     *
     * @return true while the fixture must wait for another tick
     */
    private static boolean advanceVindicatorTurn(int wanted) {
        if (battle == null || battle.finished()) return false;
        boolean current = vindicator != null && battle.isActorTurn(vindicator);
        sampleActingTurn(current);
        if (current) {
            actorWasCurrent = true;
            applyWalkChange();
            return true;
        }
        if (actorWasCurrent) {
            actorWasCurrent = false;
            completedTurns++;
        }
        if (completedTurns >= wanted) return false;
        // A commanded companion never plays itself: its controller hands its slot on through the real order.
        if (wolf != null && battle.isActorTurn(wolf)) {
            battle.endPlayerTurn(owner, battle.stamp());
            return true;
        }
        if (battle.isPlayerTurnNow()) {
            handOverPlayerTurn();
            return true;
        }
        return true;
    }

    private static void sampleActingTurn(boolean current) {
        UUID actorId = battle.currentActorId();
        observedActors.add(actorId == null ? "none" : actorId.toString());
        if (!current) return;
        int ap = battle.actorAp(vindicator);
        if (turnStartAp < 0) {
            turnStartAp = ap;
            // Read the other units' own pools at this exact moment: an earlier own turn of theirs may already
            // have recovered AP, and that recovery is theirs, not something this turn may have taken.
            ownerApObserved = battle.actorAp(owner);
            secondApObserved = second != null && battle.isParticipant(second) ? battle.actorAp(second) : -1;
            wolfApObserved = wolf != null && battle.isParticipant(wolf) ? battle.actorAp(wolf) : -1;
        }
        if (battle.pendingProjectiles() > 0) {
            require(!battle.isPlayerTurnNow(), "a pending action cannot hand control back early");
        }
    }

    private static boolean sampled() { return turnStartAp >= 0; }

    /**
     * What this own turn really spent, read from the actor's own authoritative AP.
     *
     * <p>An AI turn can plan, spend and hand the turn over inside one server tick, so a per-tick low-water mark
     * would miss the spent value entirely. The actor keeps its remaining AP after the turn, and recovery is only
     * applied at the start of its next own turn, so the difference against the turn's first reading is the real
     * charge.</p>
     */
    private static int spentAp() { return turnStartAp - battle.actorAp(vindicator); }

    private static void handOverPlayerTurn() {
        require(++handOverAttempts <= 400, "the vindicator never took its own turn: observed actors "
                + observedActors + ", expected " + (vindicator == null ? "none" : vindicator.getUUID().toString()));
        battle.endPlayerTurn();
    }

    /** Applies this scenario's world change exactly once, while the acting vindicator is really walking. */
    private static void applyWalkChange() {
        if (walkChange == WalkChange.NONE || changeApplied) return;
        if (!battle.isBusy()) return;
        changeApplied = true;
        switch (walkChange) {
            case OPEN_DOOR -> setDoorOpen(2, 0, true);
            // Every block the fixture's own mutation places is registered for cleanup, so one scenario can
            // never leave a wall standing in the next scenario's corridor.
            case BLOCK_THE_PATH -> put(feet(4, 0), Blocks.STONE.defaultBlockState());
            case REPLACE_DOOR_WITH_STONE -> {
                put(feet(2, 0), Blocks.STONE.defaultBlockState());
                put(feet(2, 1), Blocks.STONE.defaultBlockState());
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private static void setup(MinecraftServer started) {
        server = started;
        level = started.overworld();
        // The approved door rule is ordinary or hard difficulty only; a dedicated test server defaults to easy.
        started.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
        BlockPos spawn = level.getSharedSpawnPos();
        int y = Math.min(level.getMaxBuildHeight() - 12, spawn.getY() + 22);
        origin = new BlockPos(spawn.getX(), y, spawn.getZ());
        for (int cx = (origin.getX() - 26) >> 4; cx <= (origin.getX() + 26) >> 4; cx++) {
            for (int cz = (origin.getZ() - 14) >> 4; cz <= (origin.getZ() + 14) >> 4; cz++) {
                if (level.setChunkForced(cx, cz, true)) forced.add(new BlockPos(cx, 0, cz));
            }
        }
        for (int x = -30; x <= 30; x++) {
            for (int z = -12; z <= 12; z++) {
                level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 6; dy++) level.setBlock(origin.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        owner = player(started, "tactical-vindicator-owner", "VindicatorOwner");
        second = player(started, "tactical-vindicator-second", "VindicatorSecond");
        second.setPos(origin.getX() - 25.5D, origin.getY(), origin.getZ() + .5D);
    }

    private static ServerPlayer player(MinecraftServer started, String key, String name) {
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), name);
        ServerPlayer player = new ServerPlayer(started, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new ServerGamePacketListenerImpl(started, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(100);
        player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.1);
        player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        player.setHealth(100);
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        level.addNewPlayer(player);
        return player;
    }

    private static Vindicator vindicator(int dx, int dz) {
        Vindicator mob = (Vindicator) spawn(EntityType.VINDICATOR, dx, dz);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        return mob;
    }

    private static Mob spawn(EntityType<? extends Mob> type, int dx, int dz) {
        Mob mob = type.create(level);
        require(mob != null, type + " exists in this version");
        mob.setPos(origin.getX() + dx + .5D, origin.getY(), origin.getZ() + dz + .5D);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        mob.setHealth(100);
        require(level.addFreshEntity(mob), type + " registered in the real server world");
        return mob;
    }

    private static Wolf wolf(int dx, int dz) {
        Wolf spawned = (Wolf) spawn(EntityType.WOLF, dx, dz);
        spawned.setTame(true, false);
        spawned.setOwnerUUID(owner.getUUID());
        spawned.setOrderedToSit(false);
        return spawned;
    }

    /**
     * A real member sealed in its own cell: three stone walls and one closed oak door.
     *
     * <p>The member stands at cell (1, 0); the door pair is (2, 0)/(2, 1); the walls close the other three
     * sides, so the door is the only way in and the only cell from which the member can be struck.</p>
     */
    private static void sealPlayerRoom() {
        owner.setPos(origin.getX() + 1.5D, origin.getY(), origin.getZ() + .5D);
        wall(0, 0);
        wall(1, 1);
        wall(1, -1);
        door(2, 0);
    }

    /** Places a fixture block and remembers it, so the next scenario starts from the same empty field. */
    private static void put(BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 3);
        placed.add(pos);
    }

    private static void wall(int dx, int dz) {
        for (int dy = 0; dy < 2; dy++) {
            BlockPos pos = feet(dx, dz).above(dy);
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            placed.add(pos);
        }
    }

    /** A real closed oak door pair, lower half first so the pair is complete and supported. */
    private static void door(int dx, int dz) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(DoorBlock.OPEN, false);
        BlockPos low = feet(dx, dz);
        level.setBlock(low, lower, 3);
        level.setBlock(low.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        placed.add(low);
        placed.add(low.above());
    }

    private static void setDoorOpen(int dx, int dz, boolean open) {
        BlockPos low = feet(dx, dz);
        for (BlockPos pos : List.of(low, low.above())) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock) level.setBlock(pos, state.setValue(DoorBlock.OPEN, open), 3);
        }
    }

    /** One closed pair: the lower half is this door and the upper half is still the same door. */
    private static boolean isClosedDoor(int dx, int dz) {
        BlockPos low = feet(dx, dz);
        BlockState state = level.getBlockState(low);
        BlockState upper = level.getBlockState(low.above());
        return state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && upper.getBlock() == state.getBlock()
                && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    private static boolean opened(int dx, int dz) {
        BlockState state = level.getBlockState(feet(dx, dz));
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
    }

    private static boolean standsAt(int dx, int dz) {
        return standsAt(dx, 0, dz);
    }

    private static boolean standsAt(int dx, int dy, int dz) {
        if (vindicator == null) return false;
        GridPos cell = battle.currentActorCell(vindicator);
        return cell != null && cell.equals(new GridPos(feet(dx, dz).getX(), feet(dx, dz).getY() + dy,
                feet(dx, dz).getZ()));
    }

    /** Real loaded-chunk count, so "the search never loaded a chunk" is a reading, not a claim. */
    private static int loadedChunks() {
        if (level.getChunkSource() instanceof net.minecraft.server.level.ServerChunkCache cache) {
            return cache.getLoadedChunksCount();
        }
        return -1;
    }

    private static int doorDrops() {
        AABB around = new AABB(origin).inflate(24.0D, 8.0D, 24.0D);
        int count = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, around)) {
            if (item.getItem().is(Items.OAK_DOOR)) count += item.getItem().getCount();
        }
        return count;
    }

    private static BlockPos feet(int dx, int dz) { return origin.offset(dx, 0, dz); }

    private static void startBattle() {
        resetObservations();
        ServerPackets.startCapture();
        BattleManager.reset();
        require(BattleManager.start(owner, true), "vindicator encounter starts");
        battle = BattleManager.current();
        require(battle != null && battle.isPlayerTurnNow(), "the high-initiative owner acts first");
        require(vindicator != null && battle.participants().contains(vindicator),
                "the vindicator is really a participant of this encounter");
        owner.invulnerableTime = 0;
        second.invulnerableTime = 0;
        // Every scenario reads the member's own health from this baseline, so it is taken with the encounter.
        ownerHealthBefore = owner.getHealth();
        for (Mob other : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(32))) {
            if (other != vindicator && other != wolf && other != villager && other != cow
                    && other instanceof net.minecraft.world.entity.monster.Enemy) other.discard();
        }
    }

    private static void nextScenario() {
        BattleManager.reset();
        battle = null;
        for (Mob mob : java.util.Arrays.asList(vindicator, wolf, villager, cow)) {
            if (mob != null && !mob.isRemoved()) mob.discard();
        }
        vindicator = null;
        wolf = null;
        villager = null;
        cow = null;
        for (BlockPos cell : placed) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        for (ItemEntity item : List.copyOf(level.getEntitiesOfClass(ItemEntity.class,
                new AABB(origin).inflate(30.0D, 10.0D, 30.0D)))) item.discard();
        if (second != null) {
            second.setHealth(second.getMaxHealth());
            second.invulnerableTime = 0;
            second.setPos(origin.getX() - 25.5D, origin.getY(), origin.getZ() + .5D);
        }
        if (owner != null) {
            owner.setHealth(owner.getMaxHealth());
            owner.invulnerableTime = 0;
            owner.setPos(origin.getX() + .5D, origin.getY(), origin.getZ() + .5D);
        }
        walkChange = WalkChange.NONE;
        changeApplied = false;
        cancelDoorBreak = false;
        watchedDoorLower = null;
        resetObservations();
    }

    private static void resetTurnObservations() {
        actorWasCurrent = false;
        completedTurns = 0;
        turnStartAp = -1;
    }

    private static void resetObservations() {
        resetTurnObservations();
        handOverAttempts = 0;
        observedActors.clear();
        ownerHits = 0;
        secondHits = 0;
        wolfHits = 0;

        ownerHealthBefore = -1.0D;
        wolfHealthBefore = -1.0D;
        secondHealthBefore = -1.0D;
        chunksBeforeTurn = -1;
        ownerApObserved = -1;
        secondApObserved = -1;
        wolfApObserved = -1;
        doorDropsBefore = 0;
    }

    private static void finish(MinecraftServer started) {
        enabled = false;
        ServerBattle.fixtureDoorRouteBudget(0);
        BattleManager.reset();
        for (Mob mob : java.util.Arrays.asList(vindicator, wolf, villager, cow)) {
            if (mob != null && !mob.isRemoved()) mob.discard();
        }
        for (BlockPos cell : placed) if (level != null) level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        placed.clear();
        for (ServerPlayer player : List.of(owner, second)) {
            if (player != null && !player.isRemoved()) level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        }
        ServerPackets.stopCapture();
        if (level != null) for (BlockPos chunk : forced) level.setChunkForced(chunk.getX(), chunk.getZ(), false);
        forced.clear();
        BattleEffects.clear();
        started.halt(false);
    }

    /** The journal rows the server really published, read through the fixture capture. */
    private static boolean journalHas(String key) {
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
