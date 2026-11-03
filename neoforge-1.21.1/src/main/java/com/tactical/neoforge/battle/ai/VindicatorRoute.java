package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

import java.util.List;
import java.util.Objects;

/**
 * One read-only snapshot of where a door-breaking melee attacker can really go this own turn.
 *
 * <p>The server answers both route questions from the same authoritative terrain it will charge and move on,
 * so the tactics never guess about walls, doors or the battle zone. Two searches are kept apart on purpose:</p>
 * <ul>
 *   <li>{@link #open} - a complete route that needs no door broken. Whenever it exists it is used, even when
 *       this turn's AP cannot walk all of it yet.</li>
 *   <li>{@link #throughDoors} - only asked when the open route is definitively absent. A route that crosses
 *       closed wooden doors is not a licence to break one: the turn walks to the first door it would cross and
 *       breaks that one only.</li>
 * </ul>
 *
 * <p>{@code breakAllowed} is the encounter-level permission (difficulty and the mob-griefing rule); every
 * per-block rule, the cancellable entity-break event and the real world state are re-checked at the moment of
 * the break, where a refusal costs nothing.</p>
 *
 * @param open          complete route that crosses no closed door
 * @param throughDoors  complete route that may cross closed wooden doors
 * @param breakAllowed  this encounter currently lets this actor break a wooden door at all
 * @param breakCost     already-quoted AP price of one door break; 0 when it cannot pay for one
 */
public record VindicatorRoute(DoorRoutePlanner.Route open, DoorRoutePlanner.Route throughDoors,
                              boolean breakAllowed, int breakCost) {
    /** An actor with no door problem at all: every route question answers "nothing here". */
    public static final VindicatorRoute NONE =
            new VindicatorRoute(DoorRoutePlanner.Route.UNREACHABLE, DoorRoutePlanner.Route.UNREACHABLE, false, 0);

    public VindicatorRoute {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(throughDoors, "throughDoors");
    }

    /** True when a complete route needs no door broken. */
    public boolean openRoute() { return open.found(); }

    /**
     * The closed door this actor is standing right in front of on a complete door route, or {@code null}.
     *
     * <p>"In front of" is positional: the actor's own cell is the last cell before the door on the route the
     * server found. A door elsewhere on the route is not breakable yet - the turn has to walk up to it first,
     * which is exactly the approved "pay the real steps, then break at the door" order.</p>
     */
    public DoorRoutePlanner.Door doorAhead(GridPos actorCell) {
        if (actorCell == null || !throughDoors.found()) return null;
        DoorRoutePlanner.Door door = throughDoors.firstDoor();
        if (door == null) return null;
        List<GridPos> path = throughDoors.path();
        int index = path.indexOf(door.lower());
        if (index < 0) return null;
        GridPos front = index == 0 ? actorCell : path.get(index - 1);
        return front.equals(actorCell) ? door : null;
    }

    /** The number of real steps from the actor's cell to the front of that door, or -1 when there is none. */
    public int stepsToFirstDoor() {
        if (!throughDoors.found()) return -1;
        DoorRoutePlanner.Door door = throughDoors.firstDoor();
        if (door == null) return -1;
        int index = throughDoors.path().indexOf(door.lower());
        return index < 0 ? -1 : index;
    }
}
