package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** An intent, not a callback: roster mutation and skill cost/effects resolve on the server. */
public record AiAction(Kind kind, List<GridPos> path, int attackIndex,
                       ResourceLocation skill, SkillTarget skillTarget, WitchBrew brew,
                       DoorRoutePlanner.Door door) {
    public enum Kind {
        MOVE, DIVE, MELEE, RANGED, SKILL, PREPARE_EXPLOSION, DETONATE, END_TURN,
        /** Drink one self-brew; ends the own turn and starts the shared drink cooldown. */
        DRINK_POTION,
        /** Throw one brew at the locked opponent; the real splash potion is created on the server. */
        THROW_POTION,
        /** Break one closed wooden door pair; ends the own turn and keeps the remaining AP. */
        BREAK_DOOR
    }

    /** Preserve existing adapters' constructors. */
    public AiAction(Kind kind, List<GridPos> path) { this(kind, path, -1, null, null, null, null); }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex) { this(kind, path, attackIndex, null, null, null, null); }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex, ResourceLocation skill, SkillTarget skillTarget) {
        this(kind, path, attackIndex, skill, skillTarget, null, null);
    }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex, ResourceLocation skill, SkillTarget skillTarget,
                    WitchBrew brew) {
        this(kind, path, attackIndex, skill, skillTarget, brew, null);
    }

    public AiAction {
        Objects.requireNonNull(kind, "kind");
        path = List.copyOf(path);
        boolean moving = kind == Kind.MOVE || kind == Kind.DIVE;
        if (moving == path.isEmpty()) throw new IllegalArgumentException("Only MOVE/DIVE have a non-empty path");
        if (kind == Kind.DIVE ? attackIndex < 0 || attackIndex >= path.size() - 1 : attackIndex != -1) {
            throw new IllegalArgumentException("DIVE requires a zero-based attack index followed by a return path");
        }
        if (kind == Kind.SKILL) {
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(skillTarget, "skillTarget");
        } else if (skill != null || skillTarget != null) {
            throw new IllegalArgumentException("Only SKILL carries skill parameters");
        }
        // The brew is a typed payload, not a free-form callback: it may only appear on the two potion
        // actions, and those may only name a brew of the kind they can really perform.
        if (kind == Kind.DRINK_POTION) {
            if (brew == null || !brew.selfDrink()) {
                throw new IllegalArgumentException("DRINK_POTION carries one self-drink brew");
            }
        } else if (kind == Kind.THROW_POTION) {
            if (brew == null || !brew.thrown()) {
                throw new IllegalArgumentException("THROW_POTION carries one thrown brew");
            }
        } else if (brew != null) {
            throw new IllegalArgumentException("Only potion actions carry a brew");
        }
        // The door is the same kind of payload: one explicit pair, and only on the break action.
        if (kind == Kind.BREAK_DOOR) {
            if (door == null) throw new IllegalArgumentException("BREAK_DOOR carries exactly one door pair");
        } else if (door != null) {
            throw new IllegalArgumentException("Only BREAK_DOOR carries a door");
        }
    }

    public static AiAction move(List<GridPos> path) { return new AiAction(Kind.MOVE, path); }
    public static AiAction dive(List<GridPos> roundTrip, int attackIndex) {
        return new AiAction(Kind.DIVE, roundTrip, attackIndex);
    }
    public static AiAction skill(ResourceLocation id, SkillTarget target) {
        return new AiAction(Kind.SKILL, List.of(), -1, id, target, null);
    }
    /** One self-drink of an explicitly chosen brew; the server re-checks cooldown, price and effect. */
    public static AiAction drink(WitchBrew brew) {
        return new AiAction(Kind.DRINK_POTION, List.of(), -1, null, null, brew);
    }
    /** One thrown potion of an explicitly chosen brew, at the opponent this turn locked. */
    public static AiAction throwPotion(WitchBrew brew) {
        return new AiAction(Kind.THROW_POTION, List.of(), -1, null, null, brew);
    }
    /** Break exactly this closed door pair; the server re-checks the world, the protection and the price. */
    public static AiAction breakDoor(DoorRoutePlanner.Door door) {
        return new AiAction(Kind.BREAK_DOOR, List.of(), -1, null, null, null, door);
    }
    public static AiAction of(Kind kind) { return new AiAction(kind, List.of()); }
    public static AiAction endTurn() { return of(Kind.END_TURN); }
}
