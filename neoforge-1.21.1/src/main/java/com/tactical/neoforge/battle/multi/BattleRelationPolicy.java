package com.tactical.neoforge.battle.multi;

import com.tactical.neoforge.battle.BattleSide;

import java.util.Objects;
import java.util.UUID;

/**
 * Single place that answers "are these two actors friendly or opposed".
 *
 * <p>Action control (whose turn it is, is this connection a member, is a stamp still
 * live) and participation eligibility are deliberately <em>not</em> part of this
 * policy: they are authority checks, not affiliation. Enemy target selection, skill
 * target validation and friendly-fire suppression all route through this one strategy.</p>
 *
 * <p>The authoritative question carries both identities, because a later faction or
 * PvP rule set needs to resolve an actor's actual allegiance instead of only the
 * encounter's default side. The default implementation ignores the identities, so the
 * approved co-operative PvE behaviour is unchanged; a future policy can look the UUID up
 * in its own registry without touching any call site.</p>
 */
public interface BattleRelationPolicy {

    enum Relation {
        /** Same side: cannot be attacked, may be healed/buffed by a friendly skill. */
        ALLY,
        /** Opposing sides: legal attack target. */
        OPPOSED,
        /** Unrelated/unknown actor: neither a target nor a friendly receiver. */
        UNRELATED
    }

    /**
     * Identity-bearing reference to one actor.
     *
     * @param entityId    stable UUID of the actor inside the encounter
     * @param defaultSide the side this encounter assigned by default
     */
    record CombatantRef(UUID entityId, BattleSide defaultSide) {
        public CombatantRef {
            Objects.requireNonNull(entityId, "entityId");
        }

        /** Side-only view used where no entity identity exists (tests, display code). */
        public static CombatantRef sideOnly(BattleSide side) {
            return new CombatantRef(new UUID(0L, side == null ? 0L : side.ordinal() + 1L), side);
        }
    }

    /** Authoritative relation between two identified actors. */
    Relation relationOf(CombatantRef self, CombatantRef other);

    /** Convenience for callers that only know the sides; delegates to the identity form. */
    default Relation relationOf(BattleSide self, BattleSide other) {
        return relationOf(CombatantRef.sideOnly(self), CombatantRef.sideOnly(other));
    }

    default boolean allied(CombatantRef self, CombatantRef other) {
        return relationOf(self, other) == Relation.ALLY;
    }

    default boolean opposed(CombatantRef self, CombatantRef other) {
        return relationOf(self, other) == Relation.OPPOSED;
    }

    default boolean allied(BattleSide self, BattleSide other) {
        return relationOf(self, other) == Relation.ALLY;
    }

    default boolean opposed(BattleSide self, BattleSide other) {
        return relationOf(self, other) == Relation.OPPOSED;
    }

    /** Default co-operative PvE relations; replaces the policy to add factions later. */
    static BattleRelationPolicy pve() {
        return Pve.INSTANCE;
    }

    final class Pve implements BattleRelationPolicy {
        static final Pve INSTANCE = new Pve();

        private Pve() { }

        @Override
        public Relation relationOf(CombatantRef self, CombatantRef other) {
            if (self == null || other == null) return Relation.UNRELATED;
            BattleSide mine = self.defaultSide(), theirs = other.defaultSide();
            if (mine == null || theirs == null) return Relation.UNRELATED;
            return mine == theirs ? Relation.ALLY : Relation.OPPOSED;
        }
    }
}
