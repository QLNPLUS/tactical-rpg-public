package com.tactical.core.grid;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Offers an ordinary detour and a distinct adjacent downhill leap; never trusts a client path. */
public final class MovementRoutes {
    public record Options(List<GridPos> walk, List<GridPos> drop) {
        public Options { walk=List.copyOf(walk);drop=List.copyOf(drop); }
        public boolean choice() { return !walk.isEmpty() && !drop.isEmpty(); }
    }
    private MovementRoutes() {}
    public static Options plan(GridNavigator navigator, GridPos start, GridPos target,
                               Predicate<GridPos> passable, BiPredicate<GridPos,GridPos> canDrop,
                               int budget) {
        return plan(navigator, start, target, passable, canDrop, budget, (from, to) -> true);
    }

    public static Options plan(GridNavigator navigator, GridPos start, GridPos target,
                               Predicate<GridPos> passable, BiPredicate<GridPos,GridPos> canDrop,
                               int budget, BiPredicate<GridPos,GridPos> edgeAllowed) {
        boolean downhill = target.y() < start.y();
        boolean drop = downhill && canDrop.test(start,target) && passable.test(target);
        List<GridPos> walk = navigator.path(start,target,passable,8192,false,
                (from,to) -> !(downhill && from.equals(start) && to.equals(target))
                        && edgeAllowed.test(from, to));
        if(walk.size()>budget) walk=List.of();
        return new Options(walk,drop && budget>=1 ? List.of(target):List.of());
    }
}
