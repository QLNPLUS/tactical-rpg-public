package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.battle.SpiderSurface;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** An intent, not a callback: roster mutation and skill cost/effects resolve on the server. */
public record AiAction(Kind kind, List<GridPos> path, int attackIndex,
                       ResourceLocation skill, SkillTarget skillTarget, WitchBrew brew,
                       DoorRoutePlanner.Door door, List<SpiderSurface.Node> surface,
                       List<com.tactical.neoforge.battle.SlimeGround.Node> hop) {
    public enum Kind {
        MOVE, DIVE, MELEE, RANGED, SKILL, PREPARE_EXPLOSION, DETONATE, END_TURN,
        /** Drink one self-brew; ends the own turn and starts the shared drink cooldown. */
        DRINK_POTION,
        /** Throw one brew at the locked opponent; the real splash potion is created on the server. */
        THROW_POTION,
        /**
         * Throw the trident this actor really holds in its hands at the locked opponent.
         *
         * <p>Deliberately its own kind and not {@code RANGED}: a trident is not a bow or a crossbow, it has no
         * ammunition, no charge and no reload, and its flight is the thrown projectile's own. Routing it
         * through the bow path would price it against a bow and validate it against an arrow.</p>
         */
        THROW_TRIDENT,
        /**
         * One blaze salvo at the locked opponent: the vanilla charge and up to three native fireballs.
         *
         * <p>The intent carries nothing the server would trust - the opponent, the price and the geometry are
         * re-resolved from the same state that charges the action - so a stale plan cannot fire at a target the
         * turn never locked.</p>
         */
        FIREBALL_VOLLEY,
        /** Break one closed wooden door pair; ends the own turn and keeps the remaining AP. */
        BREAK_DOOR,
        /**
         * One native enderman teleport: a relocation, not an attack.
         *
         * <p>The intent carries no destination on purpose. Vanilla resolves the landing point inside its own
         * private {@code teleport(DDD)} - after the {@code EnderEntityEvent.Teleport} listeners had their say -
         * so a destination named here would be a plan the world may already have replaced. What the server
         * commits is the point the native entry really landed on, validated before the entity is moved.</p>
         */
        TELEPORT,
        /**
         * One surface route: cells that carry their own support surface, for a spider only.
         *
         * <p>Deliberately its own kind and not {@code MOVE}: a {@link GridPos} is a floor cell, and a body that
         * holds a wall needs the surface it holds as well. Flattening those nodes into plain cells would let a
         * stale plan confront the server with a route the wall underneath no longer supports. The server
         * re-validates every node and every leg against the live world, and charges per edge exactly as an
         * ordinary move does.</p>
         */
        SURFACE_MOVE,
        /**
         * One short leap: an arc from a real floor to a real floor, for a spider only.
         *
         * <p>Deliberately its own kind and never a {@code MOVE} with a long edge: the leap is not a surface
         * route, it has one fixed arc, one fixed animation and its own price. It carries no destination at all,
         * because the proposal is the encounter's own bounded answer - a payload here would let a stale plan
         * name a landing the live world has already refused. The server re-resolves the same cached proposal,
         * re-proves every frame of the arc and charges only when the body really left the ground.</p>
         */
        SPIDER_LEAP,
        /**
         * One hop route for the slime family: real ground nodes, one horizontal cell per edge.
         *
         * <p>Deliberately its own kind and never a {@code MOVE} with a plain path: a hop is an arc, and a body
         * that is 2.08 blocks wide and tall is not a point walking between cells. The nodes carry the feet the
         * body really holds, the server re-proves every edge and every frame against the live world, and the
         * price is the ordinary per-edge move price - the animation costs nothing extra.</p>
         */
        SLIME_HOP,
        /**
         * The one native contact this family really has: {@code dealDamage}, not a melee swing.
         *
         * <p>Deliberately its own kind: routing it through {@code MELEE} would price and validate it as a
         * generic swing, let a size-1 slime hit at one point of damage, and lose the native attack box that
         * really reaches across two cells for a large body.</p>
         */
        SLIME_CONTACT
    }

    /** Preserve existing adapters' constructors. */
    public AiAction(Kind kind, List<GridPos> path) { this(kind, path, -1, null, null, null, null, null, null); }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex) { this(kind, path, attackIndex, null, null, null, null, null, null); }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex, ResourceLocation skill, SkillTarget skillTarget) {
        this(kind, path, attackIndex, skill, skillTarget, null, null, null, null);
    }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex, ResourceLocation skill, SkillTarget skillTarget,
                    WitchBrew brew) {
        this(kind, path, attackIndex, skill, skillTarget, brew, null, null, null);
    }
    public AiAction(Kind kind, List<GridPos> path, int attackIndex, ResourceLocation skill, SkillTarget skillTarget,
                    WitchBrew brew, DoorRoutePlanner.Door door) {
        this(kind, path, attackIndex, skill, skillTarget, brew, door, null, null);
    }

    public AiAction {
        Objects.requireNonNull(kind, "kind");
        path = List.copyOf(path);
        surface = surface == null ? List.of() : List.copyOf(surface);
        hop = hop == null ? List.of() : List.copyOf(hop);
        boolean moving = kind == Kind.MOVE || kind == Kind.DIVE;
        if (moving == path.isEmpty()) throw new IllegalArgumentException("Only MOVE/DIVE have a non-empty path");
        if (kind == Kind.SURFACE_MOVE ? surface.isEmpty() || !path.isEmpty() : !surface.isEmpty()) {
            throw new IllegalArgumentException("Only SURFACE_MOVE carries a non-empty surface route, and it has no plain path");
        }
        if (kind == Kind.SLIME_HOP ? hop.isEmpty() || !path.isEmpty() : !hop.isEmpty()) {
            throw new IllegalArgumentException("Only SLIME_HOP carries a non-empty hop route, and it has no plain path");
        }
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
    /** One surface route (a spider's floor/wall nodes); the server owns every gate and every edge price. */
    public static AiAction surfaceMove(List<SpiderSurface.Node> route) {
        return new AiAction(Kind.SURFACE_MOVE, List.of(), -1, null, null, null, null, route, null);
    }
    /** One salvo at the opponent this own turn locked; the server owns charge, count and price. */
    public static AiAction fireballVolley() { return of(Kind.FIREBALL_VOLLEY); }
    /** One trident throw at the opponent this own turn locked; the server owns the real entity and its price. */
    public static AiAction throwTrident() { return of(Kind.THROW_TRIDENT); }
    /** One native teleport; the server owns the landing point, the price and every safety gate. */
    public static AiAction teleport() { return of(Kind.TELEPORT); }
    /** One short leap; the server owns the proposal, the arc, the price and every safety gate. */
    public static AiAction spiderLeap() { return of(Kind.SPIDER_LEAP); }
    /** One hop route for this family; the server owns the edges, the arcs, the price and every safety gate. */
    public static AiAction slimeHop(List<com.tactical.neoforge.battle.SlimeGround.Node> route) {
        return new AiAction(Kind.SLIME_HOP, List.of(), -1, null, null, null, null, null, route);
    }
    /** One native contact at the opponent this turn locked; the server owns every gate and the real hurt result. */
    public static AiAction slimeContact() { return of(Kind.SLIME_CONTACT); }
}
