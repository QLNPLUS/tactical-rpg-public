package com.tactical.neoforge.battle;

import com.tactical.core.combat.BreezeRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.LongJumpUtil;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.breeze.BreezeUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The one bounded long-jump proposal of one breeze own turn: where the arc really lands, and every frame of it.
 *
 * <p>Vanilla asks for a single random point behind its target, snaps it to a surface, and hands four of its five
 * angles to {@code LongJumpUtil} with the arc check switched <em>off</em> - so vanilla's own answer is a launch
 * vector nobody has proved the body can fly. A turn-based encounter cannot spend a paid manoeuvre on that, so this
 * class keeps the native habits and adds the missing proof:</p>
 *
 * <ul>
 *   <li><b>the native habit</b>: the first candidate is {@code BreezeUtil.randomPointBehindTarget}, the same
 *       {@code 4-8} blocks behind the target's head with the same {@code 90} degree spread;</li>
 *   <li><b>a bounded ladder</b>: the remaining candidates are a fixed ring behind the target at the same native
 *       distances and four angles around that heading, so at most {@link BreezeRules#JUMP_MAX_CANDIDATES}
 *       landings are ever considered and never an unbounded scan;</li>
 *   <li><b>the native solver</b>: {@code LongJumpUtil.calculateJumpVectorForAngle} with the native
 *       {@code 1.4} ceiling and the native angle list, still with its own arc check off, because this class
 *       proves the arc instead;</li>
 *   <li><b>the proof</b>: every frame of the integrated arc must hold a loaded, in-world, collision-free box
 *       inside the encounter's zone, over no cell another unit holds, and the landing itself must be a real
 *       standing spot from which the native firing band admits the locked opponent.</li>
 * </ul>
 *
 * <p>Nothing here moves an entity or charges anything: it answers with an immutable proposal that the server then
 * re-proves frame by frame before it displaces the body at all.</p>
 */
public final class BreezeJump {

    private static final double EPSILON = 1.0E-7D;

    /** The layer under the feet that is asked whether it is ground: thin enough to be the contact itself. */
    private static final double SUPPORT_CONTACT_LAYER = 1.0E-6D;

    /**
     * One proved jump: the feet it starts from, every frame of the arc, where it really lands, and the exact
     * opponent the arc was solved for.
     *
     * <p>{@code feet} and {@code cell} are the arc's own contact with the tested support plane and the floored cell
     * of that exact point - not the candidate the arc was aimed at, which is only the aim of the solve.</p>
     *
     * <p>The target is part of the proposal on purpose. A landing is only meaningful relative to the body the jump
     * exists to reach, so the frozen identity travels with the plan and every frame can be asked whether it is
     * still being flown for the body it was solved for.</p>
     */
    public record Proposal(Vec3 start, Vec3[] frames, Vec3 feet, GridPos cell, Vec3 launch, java.util.UUID targetId) {

        /** How many airborne frames this arc really flies; the landing itself is the last entry. */
        public int airborneFrames() {
            return Math.max(0, frames.length - 2);
        }

        /** The frame at that index, clamped to the arc, so a caller can never step past the landing. */
        public Vec3 frame(int index) {
            int last = frames.length - 1;
            return frames[Math.max(0, Math.min(last, index))];
        }
    }

    /** What the encounter answers; the geometry never decides anything about the battle itself. */
    public interface Gates {
        boolean insideZone(GridPos cell);

        boolean cellFree(GridPos cell);

        /** True when the whole body at those feet stays inside the battlefield. */
        boolean bodyInsideZone(AABB box);
    }

    private BreezeJump() {}

    /**
     * Resolve the one landing of this own turn, or {@code null} when no proved arc exists.
     *
     * <p>Cached by the caller for its own turn: asking twice must never scan the world twice, and a refused
     * answer is an answer too - it is not retried per tick.</p>
     */
    @javax.annotation.Nullable
    public static Proposal resolve(ServerLevel level, Breeze breeze, LivingEntity target, RandomSource random,
                                   Gates gates) {
        if (level == null || breeze == null || target == null || random == null || gates == null) return null;
        if (target.level() != level || !target.isAlive() || target.isRemoved()) return null;
        List<Vec3> candidates = candidates(breeze, target, random);
        int[] angles = BreezeRules.jumpAngles();
        List<Integer> order = new ArrayList<>(angles.length);
        for (int index = 0; index < angles.length; index++) order.add(angles[index]);
        java.util.Collections.shuffle(order, new java.util.Random(random.nextLong()));
        for (Vec3 requested : candidates) {
            BlockPos surface = snapToSurface(breeze, requested);
            if (surface == null) continue;
            GridPos cell = new GridPos(surface.getX(), surface.getY(), surface.getZ());
            if (!gates.insideZone(cell) || !gates.cellFree(cell)) continue;
            // The candidate is only the aim of the solve: it has to be a spot this ruleset could stand on, and the
            // flight then has to really reach ground of its own accord. When it does not, this candidate is refused
            // and the next one is tried - the body is never scaled, sped up or snapped onto the aim instead.
            Vec3 plane = Vec3.atBottomCenterOf(surface);
            if (!boxIsReal(level, breeze, plane, gates)) continue;
            for (int angle : order) {
                Optional<Vec3> solved = LongJumpUtil.calculateJumpVectorForAngle(breeze, plane,
                        BreezeRules.JUMP_MAX_VELOCITY, angle, false);
                if (solved.isEmpty()) continue;
                Proposal proposal = fly(level, breeze, breeze.position(), plane, solved.get(), gates,
                        target.getUUID());
                if (proposal == null) continue;
                // A jump exists to buy one shot. The ground the arc really reached therefore has to admit the
                // locked opponent: a landing that is not a legal near-target situation is refused here, before
                // anything is charged, instead of being flown and then discovering there is nothing to shoot at.
                if (!BreezeRules.withinShotBand(proposal.feet().distanceToSqr(target.position()))) continue;
                return proposal;
            }
        }
        return null;
    }

    /**
     * Every candidate this own turn may look at, native habit first.
     *
     * <p>The first entry is the exact native answer, so a battle that looks like vanilla's own situation lands
     * where vanilla would have landed. The rest is a fixed, deterministic ring: four headings around the
     * target's back at four of the native distances, never a random walk over the whole neighbourhood.</p>
     */
    private static List<Vec3> candidates(Breeze breeze, LivingEntity target, RandomSource random) {
        List<Vec3> result = new ArrayList<>(BreezeRules.JUMP_MAX_CANDIDATES);
        // The exact native answer first, so a battle that looks like vanilla's own situation lands where vanilla
        // would have landed.
        result.add(BreezeUtil.randomPointBehindTarget(target, random));
        // Then the two native rings, in a fixed order: a few points still behind the target, and a few points in
        // the middle of the distance - the second is vanilla's own {@code Slide.randomPointInMiddleCircle} habit,
        // and it is what makes a jump from beyond the firing band land inside it instead of overshooting.
        float behind = target.yHeadRot + 180.0F;
        float[] behindOffsets = {-45.0F, 0.0F, 45.0F};
        double[] distances = {BreezeRules.JUMP_BEHIND_MIN, 6.0D, BreezeRules.JUMP_BEHIND_MAX};
        for (float offset : behindOffsets) {
            for (double distance : distances) {
                if (result.size() >= BreezeRules.JUMP_MAX_CANDIDATES) return result;
                result.add(target.position().add(
                        Vec3.directionFromRotation(0.0F, behind + offset).scale(distance)));
            }
        }
        Vec3 toTarget = new Vec3(target.getX() - breeze.getX(), 0.0D, target.getZ() - breeze.getZ());
        if (toTarget.lengthSqr() < 1.0E-6D) return result;
        Vec3 forward = toTarget.normalize();
        Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
        double[] middleShortBy = {BreezeRules.JUMP_BEHIND_MAX, 6.0D, BreezeRules.JUMP_BEHIND_MIN};
        double[] sideways = {-2.0D, 0.0D, 2.0D};
        for (double shortBy : middleShortBy) {
            for (double offset : sideways) {
                if (result.size() >= BreezeRules.JUMP_MAX_CANDIDATES) return result;
                result.add(target.position().subtract(forward.scale(shortBy)).add(side.scale(offset)));
            }
        }
        return result;
    }

    /** Vanilla's own {@code snapToSurface}: the first block under, or else above, that point. */
    @javax.annotation.Nullable
    private static BlockPos snapToSurface(LivingEntity body, Vec3 point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
            return null;
        }
        HitResult below = body.level().clip(new ClipContext(point, point.relative(Direction.DOWN, 10.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        if (below.getType() == HitResult.Type.BLOCK) return BlockPos.containing(below.getLocation()).above();
        HitResult above = body.level().clip(new ClipContext(point, point.relative(Direction.UP, 10.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body));
        return above.getType() == HitResult.Type.BLOCK ? BlockPos.containing(above.getLocation()).above() : null;
    }

    /**
     * Integrate one native launch vector into frames and prove every leg of them, up to its own real contact.
     *
     * <p>The vector is vanilla's; what is added is the body-by-body proof vanilla skips with its {@code false}
     * argument. Each leg is proved as a <em>swept</em> whole-body segment, not only as a destination box, so a thin
     * obstruction between two frames is caught. A candidate whose first leg already hits something is refused
     * here, before anything is charged - which is the difference between "the world moved" and "the plan was never
     * legal".</p>
     *
     * <p>The arc ends where the arc itself reaches the tested support plane, never where the candidate asked it
     * to. The native solve aims half a block short and hands back a {@code 0.95}-scaled vector, so the flight
     * really lands short of the point it was aimed at; jumping from the last integrated frame onto the candidate
     * would be a teleport with a real solve in front of it. The contact is therefore the exact intersection of
     * that same per-tick leg with the plane, and it is kept only when the plane there is real support for the
     * whole footprint, the cell it lands in is free and inside the battlefield, and every leg including the last
     * is collision free.</p>
     */
    @javax.annotation.Nullable
    private static Proposal fly(ServerLevel level, Breeze breeze, Vec3 start, Vec3 plane, Vec3 launch,
                                Gates gates, java.util.UUID targetId) {
        List<Vec3> frames = new ArrayList<>();
        frames.add(start);
        double[] position = {start.x, start.y, start.z};
        double[] velocity = {launch.x, launch.y, launch.z};
        double gravity = breeze.getGravity();
        int budget = BreezeRules.JUMP_MAX_TICKS;
        for (int frame = 0; frame < budget; frame++) {
            Vec3 from = frames.get(frames.size() - 1);
            double[] next = BreezeRules.stepAirborne(position, velocity, gravity);
            Vec3 point = new Vec3(next[0], next[1], next[2]);
            if (point.y <= plane.y + EPSILON) {
                // Descent: the contact is this very leg's intersection with the tested support plane.
                double span = from.y - point.y;
                if (!(span > EPSILON) || !(from.y > plane.y + EPSILON)) return null;
                double lambda = (from.y - plane.y) / span;
                if (!(lambda > 0.0D) || lambda > 1.0D + EPSILON) return null;
                Vec3 contact = new Vec3(from.x + (point.x - from.x) * lambda, plane.y,
                        from.z + (point.z - from.z) * lambda);
                if (!legHolds(level, breeze, from, contact, gates)) return null;
                // The candidate is only an aim hint: the body really lands where the arc really meets the ground,
                // so that spot - not the candidate centre - has to answer the real-support question.
                if (!contactIsReal(level, breeze, contact, gates)) return null;
                frames.add(contact);
                if (frames.size() < 3) return null;
                return new Proposal(start, frames.toArray(new Vec3[0]), contact, cellOf(contact), launch, targetId);
            }
            if (!legHolds(level, breeze, frames.get(frames.size() - 1), point, gates)) return null;
            frames.add(point);
            position = new double[]{next[0], next[1], next[2]};
            velocity = new double[]{next[3], next[4], next[5]};
            if (BreezeRules.airborneFinished(velocity, frame + 1, budget)) return null;
        }
        return null;
    }

    /**
     * Whether that exact contact point is standing on real ground for this whole body.
     *
     * <p>Asked of the native collision shapes with the entity's own context, not of a blur-away block column:
     * the thin layer immediately under the feet has to be something the body really stands on. A shallow partial
     * block (a slab top, a stair tread) counts exactly at the height its own shape reaches; anything that is
     * merely near, such as a floor half a block lower, does not, so an unproved fraction is refused instead of
     * being turned into a hang or a snap.</p>
     */
    private static boolean contactIsReal(ServerLevel level, Breeze breeze, Vec3 feet, Gates gates) {
        AABB box = BodyGeometry.bodyAt(breeze, feet);
        if (box == null) return false;
        GridPos cell = cellOf(feet);
        if (!gates.insideZone(cell) || !gates.cellFree(cell)) return false;
        AABB layer = new AABB(box.minX, feet.y - SUPPORT_CONTACT_LAYER, box.minZ,
                box.maxX, feet.y, box.maxZ);
        return level.getBlockCollisions(breeze, layer).iterator().hasNext();
    }

    /**
     * One flown leg: the whole swept body stays loaded, in the world, inside the battlefield, collision free, over
     * no cell another unit holds, and through no other real body.
     *
     * <p>Asked for the launch legs, for every committed frame and again for the landing, so a world that changed
     * after the proposal can stop the jump exactly where it really is.</p>
     *
     * <p>The body test is not the block test. {@code level.noCollision} only knows what the world says is solid,
     * and an ordinary living body - a bystander, another encounter's unit, a body this encounter never rostered -
     * is not a collidable block; its own cell reservation is only kept for this encounter's roster. The swept box
     * is therefore also asked for any real body in it, through the same live gate ground movement already uses, so
     * a jump can never pass through somebody the grid never knew about.</p>
     */
    private static boolean legHolds(ServerLevel level, Breeze breeze, Vec3 from, Vec3 to, Gates gates) {
        if (from == null || to == null) return false;
        AABB swept = BodyGeometry.swept(breeze, from, to);
        if (swept == null) return false;
        if (swept.minY < level.getMinBuildHeight() || swept.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(swept)) return false;
        if (!BodyGeometry.loaded(level, swept)) return false;
        if (!level.noCollision(breeze, swept)) return false;
        if (AmphibiousMovement.overlapsBody(level, breeze, swept)) return false;
        if (!gates.bodyInsideZone(swept)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(swept.minX - EPSILON), Mth.floor(swept.minY - EPSILON),
                Mth.floor(swept.minZ - EPSILON), Mth.floor(swept.maxX + EPSILON), Mth.floor(swept.maxY + EPSILON),
                Mth.floor(swept.maxZ + EPSILON))) {
            if (!gates.cellFree(new GridPos(pos.getX(), pos.getY(), pos.getZ()))) return false;
        }
        return true;
    }

    /** The whole body box with its feet at that point, from the entity's own dimensions. */
    public static AABB boxAt(Breeze breeze, Vec3 feet) {
        return breeze.getDimensions(breeze.getPose()).makeBoundingBox(feet);
    }

    /** One standing spot: the whole body box is loaded, in the world, collision free and over free cells. */
    private static boolean boxIsReal(ServerLevel level, Breeze breeze, Vec3 feet, Gates gates) {
        return legHolds(level, breeze, feet, feet, gates);
    }

    /**
     * Re-prove one already-committed leg against the live world.
     *
     * <p>Asked for every leg the body is about to fly, because the world may have changed between the proposal and
     * the frame: a block placed in the arc, a body standing in it, a chunk unloaded or a battlefield shrunk all
     * stop the jump exactly where it really is instead of letting it pass.</p>
     */
    public static boolean legStillHolds(ServerLevel level, Breeze breeze, Vec3 from, Vec3 to, Gates gates) {
        return legHolds(level, breeze, from, to, gates);
    }

    /**
     * Re-prove the one landing this arc is about to end on - the last leg <em>and</em> the real ground under it.
     *
     * <p>{@link #legStillHolds} answers whether the swept body is clear, and it cannot answer whether the ground
     * the arc was solved onto is still there: taking a floor away creates no collision at all. A cached contact
     * whose floor was removed would otherwise be flown to and counted as a landing in mid air. The final leg is
     * therefore asked both questions, at launch and again before the last frame is written.</p>
     */
    public static boolean landingStillHolds(ServerLevel level, Breeze breeze, Vec3 from, Vec3 contact, Gates gates) {
        if (level == null || breeze == null || from == null || contact == null || gates == null) return false;
        return legHolds(level, breeze, from, contact, gates) && contactIsReal(level, breeze, contact, gates);
    }

    /** The landing foot cell of one point, in the same convention the encounter's grid uses. */
    public static GridPos cellOf(Vec3 feet) {
        return new GridPos(Mth.floor(feet.x), Mth.floor(feet.y + EPSILON), Mth.floor(feet.z));
    }
}
