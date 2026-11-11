package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.AerialMovement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.resources.ResourceLocation;
import com.tactical.neoforge.skill.SkillAvailability;
import com.tactical.neoforge.skill.SkillTarget;

import java.util.List;

/** Server-only view of one acting participant. All paths exclude the starting cell. */
public interface AiContext {
    Mob actor();
    GridPos actorCell();
    /** Legacy name: the primary opponent's cell, including a hostile for friendly actors. */
    GridPos playerCell();
    /** Primary opponent; legacy third-party contexts may not expose an entity. */
    default LivingEntity target() { return null; }
    default GridPos targetCell() { return playerCell(); }
    /** Immutable read-only availability snapshot. Only registered adapters choose skills. */
    default List<SkillAvailability> availableSkills() { return List.of(); }
    /** Server runtime validates availability, target and cost; false commits nothing. */
    default boolean castSkill(ResourceLocation id, SkillTarget target) { return false; }
    int ap();
    int moveCost();

    /** Movement policy does not opt other flying entity types into Phantom dive tactics. */
    default boolean flying() { return AerialMovement.supports(actor()); }
    default int meleeCost() { return com.tactical.core.combat.ApCalculator.defaultMeleeCost(); }
    /** Full-body, swept-segment, zone and occupancy checked 26-neighbour flight edges. */
    default List<GridPos> airNeighbors(GridPos from) { return List.of(); }
    default List<GridPos> airPath(GridPos from, GridPos to, int budget) {
        return AerialMovement.findPath(from, to, budget, this::airNeighbors);
    }

    /** Legacy name: shortest path to the primary opponent, optionally ending at its occupied cell. */
    List<GridPos> pathToPlayer();
    /** Terrain/zone-validated neighbours; occupancy is checked separately. */
    List<GridPos> standableNeighbors(GridPos from);
    boolean occupied(GridPos cell);
    boolean inMeleeRange(GridPos from, GridPos target);
    boolean hasLineOfSight(GridPos from, GridPos target);

    /** Legacy name: native melee against target(), including combat hooks and AP accounting. */
    void attackPlayer();

    /** Authoritative range/world/ballistic check from a hypothetical feet cell; independent of facing. */
    default boolean canRangedAttack(GridPos from) { return false; }
    /** Legacy name: native ranged attack against target(); the battle accounts for the shot AP. */
    default void rangedAttackPlayer() { }
    default int rangedCost() { return com.tactical.core.combat.BattleRules.BOW_COST; }

    /**
     * Everything the potion tactics need, read once from the same authoritative state the action is charged
     * from. A context without potions (an old adapter, a fixture) answers {@link WitchState#NONE}, which makes
     * every potion tactic decline instead of inventing a free drink.
     */
    default WitchState witch() { return WitchState.NONE; }

    /** Authoritative legality of a thrown potion from a hypothetical feet cell; independent of facing. */
    default boolean canThrowFrom(GridPos from) { return false; }

    /**
     * Quoted price of one trident throw this own turn, or 0 for an actor that has no trident in its hands.
     *
     * <p>Read from the same server state that charges the action, so a planner can never validate a throw
     * the execution path then prices differently or refuses because the hand is already empty.</p>
     */
    default int tridentCost() { return 0; }

    /**
     * Authoritative legality of one trident throw from a hypothetical feet cell.
     *
     * <p>Uses the entity's real spawn point and the native arc that will really exist, so a cell whose throw
     * would be blocked by cover is refused here instead of being paid for and missed.</p>
     */
    default boolean canThrowTridentFrom(GridPos from) { return false; }

    /** One trident throw as this own turn's main action; false when it was refused and nothing was charged. */
    default boolean throwTrident() { return false; }

    /**
     * Everything the enderman tactics need, read from the same state that charges the action.
     *
     * <p>A context without an enderman (an old adapter, a fixture) answers {@link EndermanState#NONE}, which
     * makes every teleport tactic decline instead of inventing a free relocation.</p>
     */
    default EndermanState enderman() { return EndermanState.NONE; }

    /** Frozen price of one teleport this turn, or 0 for an actor that cannot teleport at all. */
    default int teleportCost() { return 0; }

    /**
     * Authoritative answer to "is there a landing point this turn can legally use".
     *
     * <p>Resolved from the live world with the same gates the commit re-checks: loaded columns, a real support,
     * three blocks of body room, no water or danger, no occupied cell and the whole body inside the zone. The
     * preferred answer to {@link #exposed()} is a dry, sheltered cell.</p>
     */
    default boolean canTeleport() { return false; }

    /**
     * A short walk out of water or rain that this own turn can really pay for, or an empty list when there is
     * none inside the encounter.
     *
     * <p>The answer is a route over the same terrain the movement code validates, ending on a cell whose real
     * feet point is neither in water nor in rain. Walking toward the opponent is deliberately not this answer:
     * a unit that is wet has to leave the wet cell, not approach through it.</p>
     */
    default List<GridPos> dryEscapePath() { return List.of(); }

    /**
     * One native teleport as this own turn's action.
     *
     * <p>The server owns the one commit point: it re-validates the landing point after the native event had its
     * say, drives vanilla's own {@code teleport(DDD)} entry, and charges only when the entity really moved and
     * the resulting cell is legal. A refused teleport charges nothing.</p>
     *
     * @return true when the entity really relocated and the action was paid for
     */
    default boolean teleport() { return false; }

    /**
     * Drink one self-brew as this own turn's main action.
     *
     * <p>The server owns the one commit point: it re-checks the cooldown, the frozen price and whether the
     * brew really took effect, and only a real settlement charges AP, the paid-action count and the cooldown.
     * A refused drink changes nothing.</p>
     *
     * @return true when the brew really took effect and was paid for
     */
    default boolean drinkPotion(WitchBrew brew) { return false; }

    /**
     * Create one real vanilla splash potion of that brew and throw it at the locked opponent.
     *
     * @return true when a real projectile was created and the action was paid for
     */
    default boolean throwPotion(WitchBrew brew) { return false; }

    /** Forget a carried-over drink that this turn could not pay for; it never takes effect. */
    default void clearPendingDrink() { }

    /**
     * The surface node this actor really holds right now, or null for an actor with no surface policy.
     *
     * <p>A node is not a cell: it carries the feet the body really has and, on a wall, which side the wall is
     * on. Only the exact two vanilla spider types answer here; every other actor, and every fixture context
     * written against an older version, answers null and keeps the plain four-way rule.</p>
     */
    default com.tactical.neoforge.battle.SpiderSurface.Node surfaceHere() { return null; }

    /**
     * Every surface node this body may step to, in one bounded pass, with the same occupancy and zone gates
     * the encounter's own movement uses. Empty for an actor with no surface policy.
     */
    default List<com.tactical.neoforge.battle.SpiderSurface.Node> surfaceNeighbours(
            com.tactical.neoforge.battle.SpiderSurface.Node from) {
        return List.of();
    }

    /** Whether that node is still a legal standing position in the live world. */
    default boolean surfaceLegal(com.tactical.neoforge.battle.SpiderSurface.Node node) { return false; }

    /**
     * Whether the actor's <em>real</em> native attack box at that node's feet reaches the locked opponent.
     *
     * <p>Asked separately from {@link #inMeleeRange} because the two are different facts: grid adjacency is a
     * movement rule, and a short body's box does not reach a whole block up even though the cell is adjacent.</p>
     */
    default boolean surfaceMeleeReach(com.tactical.neoforge.battle.SpiderSurface.Node node) { return false; }

    /** Whether the whole body - not only its feet cell - stays inside the battlefield at that node. */
    default boolean surfaceInsideZone(com.tactical.neoforge.battle.SpiderSurface.Node node) { return false; }

    /**
     * Whether that whole route still holds in the live world: every node legal, every leg swept, every cell
     * free and inside the battlefield.
     *
     * <p>Asked once against the same state the commit charges from, so a plan built a moment ago cannot walk a
     * wall that has already been broken.</p>
     */
    default boolean surfaceRouteHolds(List<com.tactical.neoforge.battle.SpiderSurface.Node> route) { return false; }

    /**
     * How many surface edges this actor may still walk this own turn while keeping one melee affordable.
     *
     * <p>Read from the same AP and price the commit charges, so a planner can never propose a route the
     * execution path then refuses halfway.</p>
     */
    default int surfaceEdgeBudget() { return 0; }

    /**
     * Whether this actor may spend this own turn on one short leap right now.
     *
     * <p>True only when every gate really holds at the same moment: the exact body is on a legal floor and not
     * already able to bite, this own turn has not leaped yet, the frozen price is affordable, and the one
     * bounded proposal of this turn names a landing the live world still carries and from which the locked
     * opponent is really reachable. The answer is cached once per own turn, so asking it never re-scans the
     * world; the commit re-proves the same proposal instead of widening the search.</p>
     */
    default boolean canLeap() { return false; }

    /** Quoted price of one short leap this own turn, or 0 for an actor that cannot leap at all. */
    default int leapCost() { return 0; }

    /**
     * Quoted price of one blaze salvo this own turn, or 0 for an actor with no native fireball at all.
     *
     * <p>Read from the same server state that charges the action, so a planner can never validate a salvo the
     * execution path then prices differently.</p>
     */
    default int fireballCost() { return com.tactical.core.combat.BattleRules.BLAZE_VOLLEY_COST; }

    /** Authoritative legality of one native salvo from a hypothetical feet cell; independent of facing. */
    default boolean canFireballFrom(GridPos from) { return false; }

    /**
     * Whether the entity's own reach and line of sight allow a native melee from that cell.
     *
     * <p>Grid adjacency is a movement fact, not a reach fact: an aerial actor is cube-adjacent to cells its body
     * cannot touch. An adapter whose movement is not ground adjacency answers with the entity's real distance
     * gate instead of inheriting a longer reach from the grid.</p>
     */
    default boolean inNativeMeleeReach(GridPos from) { return false; }

    /** One native salvo as this own turn's main action; false when it was refused and nothing was charged. */
    default boolean fireballVolley() { return false; }

    /**
     * Everything the door-breaking melee tactics need, read once from the same authoritative terrain the
     * action will be charged and moved on. A context that cannot break doors answers
     * {@link VindicatorRoute#NONE}, which makes the door tactic decline instead of inventing a free break.
     */
    default VindicatorRoute vindicator() { return VindicatorRoute.NONE; }

    /**
     * Break one closed wooden door pair as this own turn's main action.
     *
     * <p>The server owns the one commit point: it re-checks that the same legal pair is still there and still
     * closed, the real vanilla protection chain, the frozen price, the actor's standing in front of it and the
     * remaining world state, and only a real removal charges AP and the paid-action count. Any refusal leaves
     * the door and the price untouched.</p>
     *
     * @return true when the door was really removed and the action was paid for
     */
    default boolean breakDoor(DoorRoutePlanner.Door door) { return false; }

    /**
     * Whether this context carries the slime family's own movement and contact policy at all.
     *
     * <p>Asked before any of the answers below, because "no node right now" and "this context knows nothing
     * about slimes" are different facts. A context that does not carry the policy - a third-party adapter, an
     * older fixture - answers false, and the adapter then falls back to its complete legacy behaviour instead
     * of pretending a body has no position.</p>
     */
    default boolean slimeContext() { return false; }

    /** The real ground node this body holds right now, or null when the encounter cannot prove one. */
    default com.tactical.neoforge.battle.SlimeGround.Node slimeHere() { return null; }

    /**
     * Every real ground node this body may hop to in one bounded pass, with the same occupancy, body and zone
     * gates the encounter's own movement uses.
     */
    default List<com.tactical.neoforge.battle.SlimeGround.Node> slimeNeighbours(
            com.tactical.neoforge.battle.SlimeGround.Node from) {
        return List.of();
    }

    /**
     * Whether that whole route still holds in the live world: every node a real stand, every edge a real
     * provable arc, the whole body inside the battlefield and no cell another unit holds.
     */
    default boolean slimeRouteHolds(List<com.tactical.neoforge.battle.SlimeGround.Node> route) { return false; }

    /**
     * How many hop edges this actor may still pay for this own turn while keeping one native contact
     * affordable; zero for an actor that can never reach the opponent.
     */
    default int slimeEdgeBudget() { return 0; }

    /**
     * Whether this actor's native attack box at that node really reaches the locked opponent, with a clear
     * line of sight from its real eye.
     *
     * <p>Asked separately from {@link #inMeleeRange} on purpose: grid adjacency is a movement rule, and a
     * large body's box really spans about two cells while a tiny body's spans less than one.</p>
     */
    default boolean slimeContactReaches(com.tactical.neoforge.battle.SlimeGround.Node node) { return false; }

    /**
     * This turn's one bounded hop route, or an empty list when no legal progress exists.
     *
     * <p>Resolved from the live world with the same gates the commit re-checks, and cached for this own turn,
     * so asking twice never re-scans. The answer may be a prefix that closes in without arriving - a legal step
     * forward is better than standing still - and it never contains the opponent's own cell.</p>
     */
    default List<com.tactical.neoforge.battle.SlimeGround.Node> slimeApproach() { return List.of(); }

    /** Quoted price of one native contact this own turn, or 0 for a body that cannot damage anything. */
    default int slimeContactCost() { return 0; }

    /**
     * One native contact as this own turn's main action.
     *
     * <p>The server owns the one commit point: it re-proves the locked opponent, the real attack box, the real
     * line of sight and the frozen price, drives vanilla's own {@code dealDamage}, and charges only when the
     * very {@code hurt} call inside it reported a real hit. A refused, cancelled or tiny-body contact charges
     * nothing.</p>
     *
     * @return true when the native call really hurt the target and the action was paid for
     */
    default boolean slimeContact() { return false; }

    /**
     * The legacy text outlet: this mod keeps it as literal text, never as a translation key.
     *
     * <p>A third-party adapter that only knows how to write a finished sentence keeps working, and its
     * sentence is shown exactly as authored. Nothing here is guessed to be a key.</p>
     */
    void log(String message);

    /**
     * The structured outlet: a row that is still structure, so every client reads it in its own language.
     *
     * <p>Built-in adapters prefer this. A context that does not carry player-visible rows to a journal - an
     * old or fixture context - answers {@code false} instead of pretending, and the caller then records the
     * event in the server log only: turning it into text just to fall back would publish one language as if
     * it were the server's own wording.</p>
     *
     * @return true when the structure was published to players
     */
    default boolean logLocalized(Component text) { return false; }
}
