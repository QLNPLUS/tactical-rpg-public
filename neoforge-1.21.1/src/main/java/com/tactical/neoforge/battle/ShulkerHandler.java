package com.tactical.neoforge.battle;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

/**
 * The decisions a controlled shulker's native code asks this encounter for, and nothing else.
 *
 * <p>Vanilla keeps three behaviours of its own that a turn-based encounter has to route:</p>
 * <ul>
 *   <li>the shell opening pushes every nearby body along the opening direction - including this encounter's own
 *       allies, bystanders and another encounter's members, which vanilla would never have near it;</li>
 *   <li>a shulker whose support is gone re-glues itself to the first face it finds, which can be outside the
 *       battlefield;</li>
 *   <li>its relocation refuses to run at all while {@code NoAI} is set, and that flag stays set for a controlled
 *       unit - that one is answered by {@link ShulkerNative}'s one-call permission instead of a handler flag,
 *       because it must be consumed by exactly one call and never be a lasting state.</li>
 * </ul>
 *
 * <p>A shulker in a battle answers through this seam. A wild shulker has no handler and keeps every vanilla
 * behaviour untouched. Nothing here charges anything: each answer is a decision about this encounter's own state,
 * and the price is charged at the encounter's single commit point.</p>
 */
public interface ShulkerHandler {

    /**
     * Whether this call dealt with the shell's own push of that body.
     *
     * <p>An answer of {@code true} means the displacement is the encounter's now - either because it was refused,
     * or because the encounter adopted vanilla's accepted vector through its validated handoff - so vanilla's own
     * {@code move} must not run a second time. An answer of {@code false} leaves the body to vanilla.</p>
     */
    boolean tactical$push(Entity pushed, MoverType type, Vec3 motion);

    /** Whether the native support-loss rule may re-glue this body to that cell and face. */
    boolean tactical$mayChangeAttachment(Shulker shulker, net.minecraft.core.BlockPos cell, Direction face);

    /** A native replication really registered a child during this encounter's own window. */
    void tactical$onNativeChild(Shulker parent, Shulker child);
}
