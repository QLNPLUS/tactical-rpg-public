package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The one bounded hop this ruleset animates for the slime family, and the arc it really flies.
 *
 * <p>This is a turn-based adaptation, never a claim to copy vanilla's per-tick dynamics: vanilla's slime has no
 * animation at all - it teleports a fixed distance on a random {@code jumpDelay} - so a hop here is the
 * <em>execution and presentation of one ordinary paid move</em>, one cell per edge, with its own fixed frame
 * count. Nothing here moves an entity, charges AP or writes world state; the encounter commits at its own
 * single point and asks this class the same question before every frame it really displaces.</p>
 *
 * <p>The vertical part is two half parabolas meeting at one declared apex, exactly like the spider's leap: the
 * first half leaves the take-off, the second lands, both have zero slope at the midpoint, and neither can ever
 * exceed the apex. That is what makes the declared apex the real maximum instead of a value that happens to
 * coincide with one sample, and it is the reason the block check and the displacement can call the same
 * function.</p>
 */
public final class SlimeMotion {

    /** Frames of one hop edge for a slime: this ruleset's presentation value, not a native fact. */
    public static final int SLIME_FRAMES = 8;
    /** Frames of one hop edge for a magma cube: it really is the slower jumper in vanilla. */
    public static final int MAGMA_FRAMES = 12;
    /** Default apex above the higher end of one edge. */
    public static final double APEX_RISE = 0.6D;
    /** Additional apex per size point, for a magma cube's taller, longer jump. */
    public static final double MAGMA_APEX_PER_SIZE = 0.2D;
    /**
     * The only second tier: a low hop under a ceiling that the default arc cannot clear.
     *
     * <p>There are exactly two tiers on purpose. A body that clears neither is refused that edge, because a
     * third, flatter tier would start deciding which ceilings are "almost" passable.</p>
     */
    public static final double LOW_APEX_RISE = 0.15D;

    private SlimeMotion() { }

    /**
     * One frozen leg: the two real feet points, the frame count and the apex rise that was chosen for them.
     *
     * <p>Immutable: the leg that was proved is the leg that is animated, so a later frame cannot quietly
     * re-plan a different arc through a ceiling it would not have passed.</p>
     */
    public record Leg(Vec3 start, Vec3 end, int frames, double apexRise) {

        public Leg {
            if (start == null || end == null) throw new IllegalArgumentException("A hop needs both ends");
            if (frames <= 0) throw new IllegalArgumentException("A hop needs a positive frame count");
            if (!(apexRise > 0.0D) || !Double.isFinite(apexRise)) {
                throw new IllegalArgumentException("A hop needs a positive apex rise");
            }
        }

        /** The height of the arc's top: the chosen rise above the higher end of the edge. */
        public double apexY() {
            return Math.max(start.y, end.y) + apexRise;
        }

        /**
         * The height at one fraction of the leg.
         *
         * <p>Two half parabolas meeting at the apex. At {@code fraction == 0.5} both halves answer exactly
         * {@link #apexY()}, and outside their own half neither can exceed it, so the apex is a proven maximum
         * rather than a sampled guess.</p>
         */
        public double height(double fraction) {
            double top = apexY();
            double t = Math.max(0.0D, Math.min(1.0D, fraction));
            if (t <= 0.5D) {
                double back = 1.0D - 2.0D * t;
                return top - (top - start.y) * back * back;
            }
            double forward = 2.0D * t - 1.0D;
            return top - (top - end.y) * forward * forward;
        }

        /**
         * The real feet position of one frame, {@code 0} at the take-off and {@code frames} at the landing.
         *
         * <p>Horizontal motion is the straight segment between the two real feet points; only the height comes
         * from {@link #height}. The frame that is swept and the frame that is applied are therefore the same
         * point, and the endpoints are exact instead of rounded.</p>
         */
        public Vec3 point(int frame) {
            int tick = Math.max(0, Math.min(frames, frame));
            if (tick == 0) return start;
            if (tick == frames) return end;
            double fraction = tick / (double) frames;
            double x = start.x + (end.x - start.x) * fraction;
            double z = start.z + (end.z - start.z) * fraction;
            return new Vec3(x, height(fraction), z);
        }

        /** Whether this leg used the low tier, which is the only other tier there is. */
        public boolean low() {
            return apexRise <= LOW_APEX_RISE;
        }
    }

    /** The frame count this exact body really uses; a magma cube is never animated as fast as a slime. */
    public static int framesFor(LivingEntity body) {
        return body instanceof MagmaCube ? MAGMA_FRAMES : SLIME_FRAMES;
    }

    /**
     * The apex rise this exact body uses by default.
     *
     * <p>A slime keeps {@link #APEX_RISE}; a magma cube adds its own size, because vanilla's magma cube really
     * jumps higher the bigger it is ({@code + size * 0.1} on the jump power). The value is read from the body's
     * real size attribute-free state, never from a guess about what was summoned.</p>
     */
    public static double apexRiseFor(LivingEntity body) {
        if (body instanceof MagmaCube cube) {
            return APEX_RISE + MAGMA_APEX_PER_SIZE * Math.max(0, cube.getSize());
        }
        return APEX_RISE;
    }

    /**
     * The one leg this body really flies between those two feet points, or empty when neither tier is clear.
     *
     * <p>The default tier is asked first and the low tier only if it fails, so the same world always answers
     * with the same tier. Every frame is proved as a full body sweep against the live world - loaded columns,
     * solid blocks, any liquid, hazards, other bodies and the battlefield - which is what makes a hop a legal
     * displacement rather than a teleport with a pretty curve.</p>
     *
     * @param zone every cell the body may occupy; the swept body and both endpoints must stay inside it
     */
    public static Optional<Leg> legFor(ServerLevel level, LivingEntity body, Vec3 from, Vec3 to,
                                       Predicate<GridPos> zone) {
        if (level == null || body == null || from == null || to == null) return Optional.empty();
        int frames = framesFor(body);
        for (double rise : new double[]{apexRiseFor(body), LOW_APEX_RISE}) {
            Leg leg = new Leg(from, to, frames, rise);
            if (!BodyGeometry.bodyInsideZone(body, from, zone)) continue;
            if (!BodyGeometry.bodyInsideZone(body, to, zone)) continue;
            if (!legFramesHold(level, body, leg, zone)) continue;
            return Optional.of(leg);
        }
        return Optional.empty();
    }

    /**
     * Whether every remaining frame of this exact leg is still clear.
     *
     * <p>Asked before each frame is applied, from the position the body really holds now, so a block placed in
     * the arc, a pool, an unloaded column, another body or a shrunken battlefield stops the hop where it is
     * instead of letting it finish a leg that no longer exists.</p>
     */
    public static boolean legFramesHold(ServerLevel level, LivingEntity body, Leg leg,
                                        Predicate<GridPos> zone) {
        if (level == null || body == null || leg == null) return false;
        Vec3 previous = leg.start();
        for (int frame = 1; frame <= leg.frames(); frame++) {
            Vec3 next = leg.point(frame);
            if (!BodyGeometry.sliceClear(level, body, previous, next, zone)) return false;
            previous = next;
        }
        return true;
    }

    /**
     * The frames still in front of the body, proved from where it really is.
     *
     * <p>Used per animation tick: the body may already be at a fractional point of this leg, so the proof has
     * to start from the live position instead of the take-off. Only the frames that are about to be applied are
     * asked, which is what keeps "the next frame is clear" from turning into a whole-leg scan on every tick.</p>
     */
    public static boolean nextFrameHolds(ServerLevel level, LivingEntity body, Leg leg, int nextFrame,
                                         Predicate<GridPos> zone) {
        if (level == null || body == null || leg == null) return false;
        if (nextFrame <= 0 || nextFrame > leg.frames()) return false;
        Vec3 real = body.position();
        Vec3 next = leg.point(nextFrame);
        return BodyGeometry.sliceClear(level, body, real, next, zone);
    }
}
