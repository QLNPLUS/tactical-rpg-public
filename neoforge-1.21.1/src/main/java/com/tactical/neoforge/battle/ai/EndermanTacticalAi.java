package com.tactical.neoforge.battle.ai;

import com.tactical.core.combat.BattleRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * Enderman tactics: melee first, short walks before teleports, and a native teleport only when walking is the
 * wrong answer.
 *
 * <p>Vanilla teleports its enderman for free whenever it is hurt, rained on or lost. This adapter only decides
 * <em>whether</em> the unit wants to relocate: it never moves the entity and never prices anything itself. The
 * server resolves the landing point, re-validates it after vanilla's own {@code teleport(DDD)} event had its say,
 * and charges the frozen price only when the entity really relocated.</p>
 *
 * <p>Order of preference, straight from the approved plan: a native melee that is really legal right now; a short
 * walk while the opponent is close and the route exists; a teleport when the opponent is clearly far, when the
 * walk route does not exist, or when the unit is exposed to water or rain. One teleport per own turn, and never a
 * second one after a relocation: the follow-up may only be a melee or the end of the turn.</p>
 */
public final class EndermanTacticalAi implements TacticalMobAi {

    private final GenericMeleeAi melee = new GenericMeleeAi();
    private boolean teleportResolved;

    /** Registry factory form: the adapter takes the actor and asks the context for everything else. */
    public EndermanTacticalAi(Mob actor) {
        this();
    }

    /** Allows deterministic decision tests without a world. */
    EndermanTacticalAi() { }

    @Override
    public AiAction planTurn(AiContext context) {
        teleportResolved = false;
        return decide(context);
    }

    /** After a controlled relocation the same own turn continues, but only towards a melee or its end. */
    @Override
    public AiAction afterMovement(AiContext context) {
        // A relocation never buys a second one, and it buys no new walk either: the follow-up of this own turn
        // is a melee that is really legal right now, or the end of the turn.
        if (teleportResolved) {
            return meleeFirst(context) ? AiAction.of(AiAction.Kind.MELEE) : AiAction.endTurn();
        }
        return decide(context);
    }

    @Override
    public void onActionResolved(AiAction.Kind action) {
        // One teleport per own turn, and a relocation never buys a second one.
        if (action == AiAction.Kind.TELEPORT) teleportResolved = true;
    }

    private AiAction decide(AiContext context) {
        EndermanState state = context.enderman();
        if (state.exposed() || state.pendingEscape()) {
            // Water and rain come before any attack: punching the opponent from inside the puddle escapes
            // nothing. A dry, sheltered cell it can really walk to wins; an affordable relocation is the next
            // answer; only when neither exists does it act from where it stands - and even then it never walks
            // toward the opponent through the wet ground it is trying to leave.
            List<GridPos> dry = context.dryEscapePath();
            if (!dry.isEmpty()) return AiAction.move(dry);
            if (teleportAvailable(context, state)) return AiAction.teleport();
            if (meleeFirst(context)) return AiAction.of(AiAction.Kind.MELEE);
            return AiAction.endTurn();
        }
        if (meleeFirst(context)) return AiAction.of(AiAction.Kind.MELEE);
        if (teleportAvailable(context, state) && walkingIsWrongAnswer(context)) {
            return AiAction.teleport();
        }
        // Everything else is the ordinary approach-and-melee: the server's own terrain decides the route, and a
        // dry walking point is the server's answer as well, not something this planner invents.
        return melee.planTurn(context);
    }

    /**
     * Whether one more teleport may be attempted this own turn.
     *
     * <p>Every part is a fact from the charging state: the quota left this turn, the frozen price, the AP the unit
     * really has, and whether the world currently contains a landing point that passes all the safety gates.</p>
     */
    private boolean teleportAvailable(AiContext context, EndermanState state) {
        if (teleportResolved || !state.mayTeleport()) return false;
        int cost = context.teleportCost();
        return cost > 0 && context.ap() >= cost && context.canTeleport();
    }

    /**
     * Whether the unit should relocate instead of walking.
     *
     * <p>Clearly far (beyond the short range), or no walk route at all. The distance is real distance between
     * feet cells, and the route is the battle's own authoritative path, so "unreachable" here is the same answer
     * the movement code would give.</p>
     */
    private boolean walkingIsWrongAnswer(AiContext context) {
        GridPos target = context.targetCell();
        if (target == null) return false;
        if (distance(context.actorCell(), target) > BattleRules.ENDERMAN_SHORT_RANGE) return true;
        return context.pathToPlayer().isEmpty();
    }

    /** The native melee is legal right here: reach, sight and the price the server will really charge. */
    private static boolean meleeFirst(AiContext context) {
        GridPos target = context.targetCell();
        if (target == null) return false;
        if (context.meleeCost() <= 0 || context.ap() < context.meleeCost()) return false;
        return context.inMeleeRange(context.actorCell(), target)
                && context.hasLineOfSight(context.actorCell(), target);
    }

    private static double distance(GridPos from, GridPos to) {
        double dx = (double) from.x() - to.x();
        double dy = (double) from.y() - to.y();
        double dz = (double) from.z() - to.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
