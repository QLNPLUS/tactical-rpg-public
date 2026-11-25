package com.tactical.neoforge.battle.terrain;

import com.tactical.core.grid.GridPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which units of one encounter are falling right now, and what each of them holds.
 *
 * <p>Pure bookkeeping: no world, no entity, no packet. The encounter drives the actual descent and
 * the landing settlement; this type only answers the questions that must not be re-derived per
 * caller - is the encounter busy, which cells are already claimed by a fall in flight, and has a
 * fall outlived its deadline.</p>
 *
 * <p>A forced fall is not a paid move: nothing here charges AP, counts a successful action or
 * restores resources. It exists because the block a unit stood on is gone.</p>
 */
public final class TerrainFall {

    /** A fall that has not settled is abandoned after this many ticks; the encounter then gives up. */
    public static final int TIMEOUT_TICKS = 100;

    /**
     * The real fractional feet one <em>precise</em> fall holds.
     *
     * <p>An ordinary forced fall descends whole cells, which is right for a body that was standing on a cell
     * boundary. A body that was displaced <em>inside</em> its own cell - an accepted push that ended 0.601 above
     * the floor - was never on one, so the cell centre is not a place it can be put without inventing a move.
     * Here the lateral position is fixed for the lifetime of the fall and the height is written once per accepted
     * descent slice.</p>
     *
     * <p>World-free bookkeeping, like the rest of this type: it is a value record kept <em>inside</em> the unit,
     * so {@link TerrainFall#end}, {@link TerrainFall#clear} and the deadline drop it with the fall itself and no
     * second lifetime has to be maintained.</p>
     */
    public static final class PreciseFeet {
        private final double x;
        private final double z;
        private final double fromY;
        private double y;

        private PreciseFeet(double x, double y, double z) {
            this.x = x;
            this.z = z;
            this.fromY = y;
            this.y = y;
        }

        /** The horizontal position the fall was handed and may never change: no cell-centre snap. */
        public double x() { return x; }

        public double z() { return z; }

        /** Where the real feet were when the push was accepted; never rewritten. */
        public double fromY() { return fromY; }

        /** Where the real feet are after the last accepted slice. */
        public double y() { return y; }

        public void y(double value) { y = value; }
    }

    /** One falling unit's own progress. Cells are authoritative; nothing here is a client prediction. */
    public static final class Unit {
        private final UUID id;
        private final boolean player;
        private GridPos cell;
        private GridPos landing;
        private GridPos stepFrom;
        private GridPos stepTo;
        private GridPos origin;
        private int cells;
        private int ticks;
        private int progress;
        private int motionTicks;
        private PreciseFeet precise;

        private Unit(UUID id, boolean player, GridPos cell) {
            this.id = id;
            this.player = player;
            this.cell = cell;
            this.origin = cell;
        }

        public UUID id() { return id; }

        public boolean player() { return player; }

        /** The cell the unit's body is in right now, in grid terms. */
        public GridPos cell() { return cell; }

        public void cell(GridPos value) { cell = value; }

        /** Where this fall is expected to end; re-resolved by the encounter while it is in flight. */
        public GridPos landing() { return landing; }

        public void landing(GridPos value) { landing = value; }

        public GridPos stepFrom() { return stepFrom; }

        public GridPos stepTo() { return stepTo; }

        public GridPos origin() { return origin; }

        public int cells() { return cells; }

        public int ticks() { return ticks; }

        public int progress() { return progress; }

        public int motionTicks() { return motionTicks; }

        /**
         * The real fractional feet of a precise fall, or {@code null} for the ordinary whole-cell descent.
         *
         * <p>This is the only difference between the two: everything else - the deadline, the busy barrier, the
         * landing reservation, the origin the journal reports - stays the one forced-fall state.</p>
         */
        public PreciseFeet precise() { return precise; }

        /** Anchor this fall at the real feet it really starts from, instead of at a cell centre. */
        public PreciseFeet beginPrecise(double x, double y, double z) {
            precise = new PreciseFeet(x, y, z);
            return precise;
        }

        public void beginStep(GridPos from, GridPos to, int motion) {
            stepFrom = from;
            stepTo = to;
            progress = 0;
            motionTicks = Math.max(1, motion);
        }

        public void endStep() {
            stepFrom = null;
            stepTo = null;
            progress = 0;
        }

        public void stepLanded(GridPos arrived) {
            cell = arrived;
            cells++;
            endStep();
        }

        public int advance() {
            return ++ticks;
        }

        public void progress(int value) { progress = value; }

        public boolean expired() { return ticks >= TIMEOUT_TICKS; }

        /** 0..1 through the current one-cell descent, which is what the client's animation follows. */
        public double fraction() {
            if (stepTo == null) return 0.0D;
            return Math.min(1.0D, progress / (double) Math.max(1, motionTicks));
        }
    }

    private final Map<UUID, Unit> units = new LinkedHashMap<>();

    public boolean busy() { return !units.isEmpty(); }

    public int size() { return units.size(); }

    public Collection<Unit> units() { return List.copyOf(units.values()); }

    public List<UUID> ids() { return new ArrayList<>(units.keySet()); }

    public Unit unit(UUID id) { return id == null ? null : units.get(id); }

    public Unit begin(UUID id, boolean player, GridPos cell) {
        Unit existing = units.get(id);
        if (existing != null) return existing;
        Unit unit = new Unit(id, player, cell);
        units.put(id, unit);
        return unit;
    }

    public void end(UUID id) {
        if (id != null) units.remove(id);
    }

    public void clear() { units.clear(); }

    /**
     * Whether some other falling unit already claims {@code cell} as its landing.
     *
     * <p>Two units may fall at the same time; they may not arrive in the same cell. A unit that
     * asks about its own claim does not conflict with itself.</p>
     */
    public boolean claimedByOther(GridPos cell, UUID except) {
        if (cell == null) return false;
        for (Unit unit : units.values()) {
            if (except != null && except.equals(unit.id())) continue;
            if (cell.equals(unit.landing()) || cell.equals(unit.cell()) || cell.equals(unit.stepTo())) return true;
        }
        return false;
    }

    /** One line per tracked unit for the encounter's own error report when a fall is abandoned. */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        for (Unit unit : units.values()) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(unit.id()).append('@').append(unit.cell())
                    .append(" ticks=").append(unit.ticks())
                    .append(" cells=").append(unit.cells());
            if (unit.precise() != null) {
                builder.append(" precise=")
                        .append(String.format(java.util.Locale.ROOT, "%.4f", unit.precise().y()));
            }
        }
        return builder.toString();
    }
}
