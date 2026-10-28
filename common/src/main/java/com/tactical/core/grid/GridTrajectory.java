package com.tactical.core.grid;

import java.util.List;

/** Tick-parametrized grid trajectory shared by the server and local-player renderer. */
public final class GridTrajectory {
    private final GridPos start;
    private final List<GridPos> path;
    private final int stepTicks;
    private final boolean drop;

    public GridTrajectory(GridPos start, List<GridPos> path, int stepTicks) {
        this(start,path,stepTicks,false);
    }
    public GridTrajectory(GridPos start, List<GridPos> path, int stepTicks, boolean drop) {
        this.drop=drop;
        this.start = start;
        this.path = List.copyOf(path);
        this.stepTicks = Math.max(1, stepTicks);
        if (drop && path.size() != 1) {
            throw new IllegalArgumentException("A drop trajectory must have exactly one landing");
        }
        GridPos previous = start;
        for (GridPos cell : path) {
            boolean validDrop=drop && previous.y()>cell.y()
                    && previous.y()-cell.y()<=16
                    && Math.abs(previous.x()-cell.x())+Math.abs(previous.z()-cell.z())==1;
            if (drop ? !validDrop : !GridMovementRules.canStep(previous, cell)) {
                throw new IllegalArgumentException("Non-adjacent trajectory step");
            }
            previous = cell;
        }
    }

    public int duration() { return path.size() * stepTicks; }

    public Position sample(double elapsedTicks) {
        if (path.isEmpty()) return center(start);
        double elapsed = Math.max(0, Math.min(duration(), elapsedTicks));
        if (drop) return DropTrajectory.sample(start,path.get(0),elapsed/stepTicks);
        int step = Math.min(path.size() - 1, (int) (elapsed / stepTicks));
        double fraction = Math.min(1, (elapsed - step * stepTicks) / stepTicks);
        GridPos from = step == 0 ? start : path.get(step - 1);
        GridPos to = path.get(step);
        return sampleStep(from, to, fraction);
    }

    /** Clears support blocks without adding jump height to an ordinary step. */
    public static Position sampleStep(GridPos from, GridPos to, double progress) {
        double t = Math.max(0, Math.min(1, progress));
        if (to.y() < from.y()) return sampleDownStep(from, to, t);
        if (to.y() > from.y()) return sampleDownStep(to, from, 1 - t);
        return new Position(from.x() + 0.5 + (to.x() - from.x()) * t,
                from.y(), from.z() + 0.5 + (to.z() - from.z()) * t);
    }

    private static Position sampleDownStep(GridPos from, GridPos to, double t) {
        double horizontal = Math.min(1, t / 0.5);
        double fall = Math.max(0, (t - 0.5) / 0.5);
        return new Position(from.x() + 0.5 + (to.x() - from.x()) * horizontal,
                from.y() + (to.y() - from.y()) * fall * fall,
                from.z() + 0.5 + (to.z() - from.z()) * horizontal);
    }

    public float yaw(double elapsedTicks) {
        if (path.isEmpty()) return 0;
        int step = Math.max(0, Math.min(path.size() - 1, (int) (elapsedTicks / stepTicks)));
        return GridMovementRules.yaw(step == 0 ? start : path.get(step - 1), path.get(step));
    }

    private static Position center(GridPos cell) {
        return new Position(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
    }

    public record Position(double x, double y, double z) { }
}
