package com.tactical.neoforge.battle.ai;

import com.tactical.core.grid.GridPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Shared AP-limited approach along the battle's authoritative terrain path. */
final class AiApproach {
    private AiApproach() { }

    static AiAction towardPlayer(AiContext context, Predicate<GridPos> stop) {
        if (context.moveCost() <= 0 || context.ap() < context.moveCost()) return AiAction.endTurn();
        int budget = context.ap() / context.moveCost();
        List<GridPos> steps = new ArrayList<>();
        for (GridPos cell : context.pathToPlayer()) {
            if (cell.equals(context.targetCell()) || context.occupied(cell) || steps.size() >= budget) break;
            steps.add(cell);
            if (stop.test(cell)) break;
        }
        return steps.isEmpty() ? AiAction.endTurn() : AiAction.move(steps);
    }
}
