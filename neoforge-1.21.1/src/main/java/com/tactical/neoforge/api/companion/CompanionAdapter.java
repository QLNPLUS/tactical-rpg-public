package com.tactical.neoforge.api.companion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * One kind of mob that a player may command by hand during a battle.
 *
 * <p>An adapter answers four questions and owns nothing else: which mobs it speaks for, who controls a
 * given mob right now (resolved live, never from a snapshot), whether that mob is allowed to join a battle
 * beside its controller, and which orders it understands. Recruiting, the control record, the turn queue,
 * the AP, the animation, the authority checks and the release path all stay in the battle, so a companion
 * kind cannot smuggle itself past the server rules.</p>
 *
 * <p>Adapters are registered once through
 * {@link com.tactical.neoforge.registry.companion.CompanionRegistry#doRegister(CompanionAdapter)}. The
 * registry is a static factory table: it never stores an entity, a battle, a turn or a queue.</p>
 */
public interface CompanionAdapter {

    /** Stable identity of this companion kind; used by diagnostics and by tests. */
    ResourceLocation id();

    /** True when this adapter claims the given mob as a possible companion. */
    boolean supports(Mob mob);

    /**
     * The controller's UUID right now, or {@code null} when the mob has no owner any more.
     *
     * <p>Called again on every order, so a mob that changed hands mid-battle simply stops answering to its
     * previous controller.</p>
     */
    UUID controllerId(Mob mob);

    /**
     * Whether this mob may join a battle next to that controller.
     *
     * <p>Battle-side conditions (same level, inside the zone, deployment, ownership, the shared friendly
     * slot budget) are checked by the battle itself and must not be duplicated here. Only kind-specific
     * eligibility belongs here - for the vanilla wolf that is "tamed and not ordered to sit".</p>
     */
    boolean canJoin(ServerLevel level, Mob mob, UUID controllerId);

    /** The orders this companion understands, as a {@link CompanionAbility} mask. */
    default int abilities(Mob mob) {
        return CompanionAbility.all();
    }

    /**
     * Capture whatever kind-specific presentation state control is about to change, so it can be restored on
     * release. The value is opaque to the battle and is never persisted.
     */
    default Object capturePose(Mob mob) {
        return null;
    }

    /** Put the companion into a state where an order can actually be carried out. */
    default void beginControl(Mob mob) {
    }

    /** Give the captured presentation state back. Never relocates, heals or re-tames the entity. */
    default void restorePose(Mob mob, Object pose) {
    }
}
