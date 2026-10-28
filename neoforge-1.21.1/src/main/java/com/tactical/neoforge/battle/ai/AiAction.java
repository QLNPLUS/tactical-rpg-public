package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** An intent, not a callback: roster mutation and skill cost/effects resolve on the server. */
public record AiAction(Kind kind, List<GridPos> path, int attackIndex,
                       ResourceLocation skill, SkillTarget skillTarget) {
    public enum Kind { MOVE, DIVE, MELEE, RANGED, SKILL, PREPARE_EXPLOSION, DETONATE, END_TURN }

    /** Preserve existing adapters' constructors. */
    public AiAction(Kind kind, List<GridPos> path) { this(kind, path, -1, null, null); }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex) { this(kind, path, attackIndex, null, null); }

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
    }

    public static AiAction move(List<GridPos> path) { return new AiAction(Kind.MOVE, path); }
    public static AiAction dive(List<GridPos> roundTrip, int attackIndex) {
        return new AiAction(Kind.DIVE, roundTrip, attackIndex);
    }
    public static AiAction skill(ResourceLocation id, SkillTarget target) {
        return new AiAction(Kind.SKILL, List.of(), -1, id, target);
    }
    public static AiAction of(Kind kind) { return new AiAction(kind, List.of()); }
    public static AiAction endTurn() { return of(Kind.END_TURN); }
}
