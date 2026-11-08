package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The one bounded short leap the two vanilla spider types may spend an own turn on.
 *
 * <p>This is a turn-based adaptation of the native leap, never a claim to copy vanilla's per-tick
 * dynamics: a ground take-off, a fixed arc of {@link #TICKS} server ticks whose top is one block above the
 * higher end of the leap, and a real floor landing. Everything here is geometry and a proposal - no entity is
 * moved, nothing is charged and no world state is written; the encounter commits at its own single point.</p>
 *
 * <p>The proposal is deliberately finite: at most {@link #MAX_LANDINGS} real landing positions are ever
 * resolved, and only one proposal is kept per own turn, so planning and committing re-check the same
 * immutable answer instead of scanning the world again. Every leg of the arc is proven against the live world
 * before the body is allowed onto it: the whole body is loaded, inside the battlefield, free of blocks,
 * liquids, hazards and other bodies, and the landing itself is a real legal floor node from which the locked
 * opponent is really reachable with the native attack box.</p>
 */
public final class SpiderLeap {

    /** Fixed animation length of one leap, in server ticks: the ruleset constant, never a local copy. */
    public static final int TICKS = com.tactical.core.combat.BattleRules.SPIDER_LEAP_TICKS;
    /** How many real landing positions one proposal may ever resolve: the whole search budget. */
    public static final int MAX_LANDINGS = 16;
    /** The real distance to the locked opponent that makes a leap worth considering at all. */
    public static final int MIN_RANGE = 2;
    public static final int MAX_RANGE = 4;
    /** The real horizontal displacement one leap may cover, independent of the foot-cell lattice. */
    public static final double MAX_HORIZONTAL = 2.5D;
    /** The arc never rises more than this above the higher end of the leap. */
    private static final double APEX_RISE = 1.0D;
    /** The horizontal cell lattice a landing may sit on, in Manhattan steps of the take-off cell. */
    private static final int MAX_CELL_STEPS = 2;
    /** The tolerance that keeps a point exactly on a cell boundary in the cell it started in. */
    private static final double EPSILON = 1.0E-7D;

    /**
     * One immutable leap: where the body takes off, the real floor node it lands on and how long it is in the air.
     *
     * <p>Immutable on purpose: the answer is cached once per own turn and then re-proved against the live world
     * by every tick that really animates it, so a later tick can never quietly re-plan the destination.</p>
     */
    public record Proposal(SpiderSurface.Node from, SpiderSurface.Node landing, int ticks) {

        public Proposal {
            Objects.requireNonNull(from, "take-off node");
            Objects.requireNonNull(landing, "landing node");
            if (from.onWall()) throw new IllegalArgumentException("A short leap takes off from a floor");
            if (landing.onWall()) throw new IllegalArgumentException("A short leap lands on a floor");
            if (ticks <= 0) throw new IllegalArgumentException("A leap needs a positive animation length");
        }

        /** The height of the arc's top: one block above the higher end of the leap. */
        public double apexY(Vec3 start) {
            return Math.max(start.y, landing.feet().y) + APEX_RISE;
        }

        /**
         * The height of the arc at one fraction of the leap.
         *
         * <p>Two half parabolas that meet at the declared apex: the first half leaves the take-off exactly and
         * reaches {@link #apexY} at the midpoint, the second leaves that same apex and lands exactly. Both
         * halves have zero slope at the midpoint, so the arc is smooth there, and neither half can ever exceed
         * the apex - which is what makes "one block above the higher end" the real maximum instead of a value
         * that happens to coincide with one sample.</p>
         */
        public double height(Vec3 start, double fraction) {
            double top = apexY(start);
            double t = Math.max(0.0D, Math.min(1.0D, fraction));
            if (t <= 0.5D) {
                double back = 1.0D - 2.0D * t;
                return top - (top - start.y) * back * back;
            }
            double forward = 2.0D * t - 1.0D;
            return top - (top - landing.feet().y) * forward * forward;
        }

        /**
         * The real feet position this leap passes through at one frame, {@code 0} at take-off and
         * {@code ticks} at the landing.
         *
         * <p>The horizontal part is the straight segment between the two real feet positions and the vertical
         * part is {@link #height}: the tick that is animated, the tick that was swept and the tick that was
         * proved clear are always the same point, and the highest point of the flight is the frozen apex.</p>
         */
        public Vec3 point(Vec3 start, int frame) {
            int tick = Math.max(0, Math.min(ticks, frame));
            if (tick == 0) return start;
            if (tick == ticks) return landing.feet();
            double fraction = tick / (double) ticks;
            double x = start.x + (landing.feet().x - start.x) * fraction;
            double z = start.z + (landing.feet().z - start.z) * fraction;
            return new Vec3(x, height(start, fraction), z);
        }

        /** The cell the real foot point occupies at one frame. */
        public GridPos footCell(Vec3 start, int frame) {
            Vec3 point = point(start, frame);
            return new GridPos(net.minecraft.util.Mth.floor(point.x),
                    net.minecraft.util.Mth.floor(point.y + EPSILON), net.minecraft.util.Mth.floor(point.z));
        }
    }

    private SpiderLeap() { }

    /**
     * The real 3D distance between two bodies' feet, the measure vanilla's own leap goal uses.
     *
     * <p>Measured from the actor's real feet to the opponent's real position, so a target two cells away on
     * the same floor and one five blocks higher are not the same leap: only the real distance inside
     * {@link #MIN_RANGE}..{@link #MAX_RANGE} is a leap at all.</p>
     */
    public static double rangeTo(LivingEntity actor, LivingEntity target) {
        if (actor == null || target == null) return Double.NaN;
        return Math.sqrt(actor.distanceToSqr(target.getX(), target.getY(), target.getZ()));
    }

    /** Whether that real 3D distance really sits inside the leap band. */
    public static boolean inRange(LivingEntity actor, LivingEntity target) {
        double range = rangeTo(actor, target);
        return range >= MIN_RANGE && range <= MAX_RANGE;
    }

    /**
     * Every cell the real foot point passes through on one animated leg must be free of the roster.
     *
     * <p>Deliberately only the foot path: a 1.4 wide body's own voxels always reach into the neighbouring
     * column, so blocking every cell the whole box touches would refuse the legal landing right beside the
     * opponent. The whole body against other real bodies stays with the swept-box test; this gate is what
     * stops a leap from crossing a cell another participant authoritatively stands in.</p>
     */
    public static boolean footPathClear(Vec3 from, Vec3 to, Predicate<GridPos> occupied) {
        if (occupied == null) return true;
        if (from == null || to == null) return false;
        // Fixed-distance samples can miss a cell crossed only briefly near a corner. Partition at every
        // coordinate boundary instead; each open interval belongs to exactly one authoritative foot cell.
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0D);
        cuts.add(1.0D);
        double[][] axes = {{from.x, to.x}, {from.y + EPSILON, to.y + EPSILON}, {from.z, to.z}};
        for (double[] axis : axes) {
            double delta = axis[1] - axis[0];
            // A single validated animation leg is much smaller; reject unbounded or non-finite inputs.
            if (!Double.isFinite(delta) || Math.abs(delta) > 16.0D
                    || Math.abs(axis[0]) > Integer.MAX_VALUE - 32.0D
                    || Math.abs(axis[1]) > Integer.MAX_VALUE - 32.0D) return false;
            if (delta == 0.0D) continue;
            int last = net.minecraft.util.Mth.floor(Math.max(axis[0], axis[1]));
            for (int boundary = net.minecraft.util.Mth.floor(Math.min(axis[0], axis[1])) + 1;
                 boundary <= last; boundary++) {
                double at = (boundary - axis[0]) / delta;
                if (at > 0.0D && at < 1.0D) cuts.add(at);
            }
        }
        cuts.sort(Double::compare);
        for (int index = 0; index < cuts.size(); index++) {
            double at = cuts.get(index);
            for (double sample : index + 1 < cuts.size()
                    ? new double[]{at, (at + cuts.get(index + 1)) * 0.5D} : new double[]{at}) {
                Vec3 point = from.lerp(to, sample);
                GridPos cell = new GridPos(net.minecraft.util.Mth.floor(point.x),
                        net.minecraft.util.Mth.floor(point.y + EPSILON), net.minecraft.util.Mth.floor(point.z));
                if (occupied.test(cell)) return false;
            }
        }
        return true;
    }

    /**
     * The one bounded leap proposal for this body right now, or empty when no real landing qualifies.
     *
     * <p>Asked with the encounter's own gates - {@code occupied} answers for the roster and every other body,
     * {@code zone} for the battlefield, {@code canBite} for the shared melee gate the landing has to open - so
     * a proposal can never name a landing the commit would then refuse.</p>
     */
    public static Optional<Proposal> propose(ServerLevel level, LivingEntity actor, SpiderSurface.Node from,
                                             LivingEntity target, Predicate<GridPos> occupied,
                                             Predicate<GridPos> zone, Predicate<SpiderSurface.Node> canBite) {
        if (level == null || actor == null || from == null || target == null) return Optional.empty();
        // Vanilla leaps from the ground only, and so does this rule.
        if (from.onWall()) return Optional.empty();
        if (!target.isAlive() || target.isRemoved() || target.level() != level) return Optional.empty();
        Vec3 start = actor.position();
        // The native leap goal's own band is a real 3D distance, not a horizontal one.
        if (!inRange(actor, target)) return Optional.empty();
        GridPos base = from.cell();
        List<GridPos> candidates = new ArrayList<>();
        for (int dy : new int[]{0, 1, -1}) {
            for (int dx = -MAX_CELL_STEPS; dx <= MAX_CELL_STEPS; dx++) {
                for (int dz = -MAX_CELL_STEPS; dz <= MAX_CELL_STEPS; dz++) {
                    int steps = Math.abs(dx) + Math.abs(dz);
                    if (steps < 1 || steps > MAX_CELL_STEPS) continue;
                    candidates.add(base.offset(dx, dy, dz));
                }
            }
        }
        GridPos targetCell = new GridPos(net.minecraft.util.Mth.floor(target.getX()),
                net.minecraft.util.Mth.floor(target.getY()), net.minecraft.util.Mth.floor(target.getZ()));
        // Deterministic and biased towards the opponent: the cheapest landing that really works is found
        // first, and the same world always answers with the same proposal.
        candidates.sort(Comparator
                .comparingInt((GridPos cell) -> manhattan(cell, targetCell))
                .thenComparingInt(GridPos::y)
                .thenComparingInt(GridPos::x)
                .thenComparingInt(GridPos::z));
        int examined = 0;
        for (GridPos cell : candidates) {
            if (examined >= MAX_LANDINGS) break;
            if (zone != null && !zone.test(cell)) continue;
            if (occupied != null && occupied.test(cell)) continue;
            if (Math.abs(cell.y() - base.y()) > 1) continue;
            examined++;
            Optional<SpiderSurface.Node> landing = SpiderSurface.groundNode(level, actor, cell);
            if (landing.isEmpty()) continue;
            Vec3 feet = landing.get().feet();
            if (Math.hypot(feet.x - start.x, feet.z - start.z) > MAX_HORIZONTAL) continue;
            if (canBite != null && !canBite.test(landing.get())) continue;
            Proposal proposal = new Proposal(from, landing.get(), TICKS);
            if (!arcHolds(level, actor, start, proposal, occupied, zone)) continue;
            return Optional.of(proposal);
        }
        return Optional.empty();
    }

    /**
     * Whether the whole animated arc is really clear for this body.
     *
     * <p>Every frame pair is proved as one full-body sweep, not just its endpoints: a leap over a ledge, a
     * fence or a pool must not pass through it between two samples. Loaded chunks, the battlefield and every
     * other real body are checked on the same pass, so a leap is never a way to cross unloaded ground or to
     * pass through somebody. The roster is asked for the cells the real foot point passes through and for the
     * landing cell - never for every cell the whole box touches, which would refuse the legal landing next to
     * the opponent.</p>
     */
    public static boolean arcHolds(ServerLevel level, LivingEntity actor, Vec3 start, Proposal proposal,
                                   Predicate<GridPos> occupied, Predicate<GridPos> zone) {
        if (level == null || actor == null || start == null || proposal == null) return false;
        if (!SpiderSurface.isLegal(level, actor, proposal.from())) return false;
        if (!SpiderSurface.isLegal(level, actor, proposal.landing())) return false;
        if (zone != null && !SpiderSurface.bodyInsideZone(actor, proposal.landing(), zone)) return false;
        if (occupied != null && occupied.test(proposal.landing().cell())) return false;
        Vec3 previous = start;
        for (int frame = 1; frame <= proposal.ticks(); frame++) {
            Vec3 next = proposal.point(start, frame);
            if (!sliceClear(level, actor, previous, next, zone)) return false;
            if (!footPathClear(previous, next, occupied)) return false;
            previous = next;
        }
        return true;
    }

    /**
     * Whether one animated leg is really clear: the whole body swept between the two points, with nothing
     * solid, no liquid at all, no hazard, nobody else in the way and every touched cell inside the battlefield.
     *
     * <p>The liquid half is asked here on purpose and not taken from the amphibious sweep, which is designed to
     * let a drowned swim: a spider that is not allowed to stand in water is not allowed to pass through it.</p>
     */
    public static boolean sliceClear(ServerLevel level, LivingEntity actor, Vec3 from, Vec3 to,
                                     Predicate<GridPos> zone) {
        if (level == null || actor == null || from == null || to == null) return false;
        AABB box = SpiderSurface.bodyAt(actor, from).minmax(SpiderSurface.bodyAt(actor, to));
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(box)) return false;
        if (!AmphibiousMovement.loaded(level, box)) return false;
        if (!level.noCollision(actor, box)) return false;
        if (AmphibiousMovement.overlapsBody(level, actor, box)) return false;
        if (AmphibiousMovement.hazardous(level, box)) return false;
        if (wetted(level, box)) return false;
        if (zone == null) return true;
        for (BlockPos pos : BlockPos.betweenClosed(floor(box.minX), floor(box.minY), floor(box.minZ),
                floor(box.maxX), floor(box.maxY), floor(box.maxZ))) {
            if (!zone.test(new GridPos(pos.getX(), pos.getY(), pos.getZ()))) return false;
        }
        return true;
    }

    /** Any liquid at all refuses the swept body, exactly as it refuses a floor position. */
    private static boolean wetted(ServerLevel level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(floor(box.minX), floor(box.minY), floor(box.minZ),
                floor(box.maxX), floor(box.maxY), floor(box.maxZ))) {
            if (!level.getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private static int floor(double value) {
        return net.minecraft.util.Mth.floor(value + 1.0E-7D);
    }

    private static int manhattan(GridPos from, GridPos to) {
        return Math.abs(from.x() - to.x()) + Math.abs(from.y() - to.y()) + Math.abs(from.z() - to.z());
    }
}
