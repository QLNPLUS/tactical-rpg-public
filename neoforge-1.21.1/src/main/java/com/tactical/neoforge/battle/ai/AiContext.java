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
