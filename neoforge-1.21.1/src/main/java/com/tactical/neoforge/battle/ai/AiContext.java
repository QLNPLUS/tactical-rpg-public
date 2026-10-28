package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.AerialMovement;
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

    void log(String message);
}
