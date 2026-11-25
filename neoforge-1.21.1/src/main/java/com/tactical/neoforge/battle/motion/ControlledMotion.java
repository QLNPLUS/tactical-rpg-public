package com.tactical.neoforge.battle.motion;

import com.tactical.core.combat.MotionRules;
import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The encounter's own hand on a body: one controlled rise, and one accepted displacement, and nothing else.
 *
 * <p>This is not a movement framework. It exists because two vanilla writes move a body on wall-clock ticks, and
 * a turn-based encounter has to answer them on its own terms:</p>
 *
 * <ul>
 *   <li><b>Rise</b> - a body that still carries vanilla levitation at its own turn start rises once, by
 *       {@link MotionRules#levitationRiseBlocks(int)} blocks, and is then <em>held</em> where it really is until
 *       the effect is gone or the unit leaves. Held is not flying: the body keeps its ordinary cell, its ordinary
 *       pathing and its ordinary support rules; it simply is not allowed to drift or fall while the native effect
 *       is what keeps it up. A hold <b>renews</b>: the next own turn of a body that is still floating rises again
 *       from the feet it really has, instead of being suppressed by its own earlier hold.</li>
 *   <li><b>Impulse</b> - one displacement vanilla already accepted (an explosion's own knockback write, an
 *       opening shell's push) is carried to the last real point the whole body may occupy. The vector is
 *       vanilla's, never manufactured or scaled; what this driver adds is the proof that every step of the way is
 *       a real place, so a body is never walked through a wall and then relabelled as standing inside.</li>
 * </ul>
 *
 * <p>Two rules about who is in charge, because they are what makes a push survive the other grid rules:</p>
 * <ul>
 *   <li>a <b>stable hold does not block commands</b>: a floating unit is still a unit whose turn it is. Only a
 *       finite transit is busy;</li>
 *   <li>an accepted impulse is applied <b>here and only here</b> - one physics owner - and the authoritative cell
 *       is updated with it, so the grid pin and the shot reconciliation agree with the body instead of dragging
 *       it back.</li>
 * </ul>
 *
 * <p>Everything is bounded and unit-scoped: a run never touches another unit, never charges AP, never advances
 * the queue, and is released in one place when its unit leaves, dies or the encounter ends.</p>
 */
public final class ControlledMotion {

    /** What the driver needs from the encounter, and nothing more. */
    public interface Seat {
        ServerLevel level();

        /** Whole-cell membership in this encounter's battlefield. */
        boolean insideZone(GridPos cell);

        /** True when that cell is free for this body - every other participant and its reservation counts. */
        boolean cellFree(GridPos cell, UUID bodyId);

        /**
         * True when no other live participant body and no reservation shares any cell of that box.
         *
         * <p>Asked for the <b>whole swept box</b> of one displacement, not only for its destination cell: a body
         * that is one cell wide can pass a neighbour halfway through a segment and still end on a free cell.</p>
         *
         * @param exceptBody a body that is allowed to be inside the box - the one whose own opening caused the
         *                   displacement. {@code null} when nothing is exempt.
         */
        boolean sweepFree(UUID bodyId, AABB box, UUID exceptBody);

        /** Make the encounter's authoritative record agree with where the body really is. */
        void settled(LivingEntity body, UUID bodyId, GridPos cell, Vec3 feet);

        /** The run was released without a settlement; the encounter keeps its last authoritative cell. */
        void released(LivingEntity body, UUID bodyId);

        /** Say that a held body's own frame has been written, so its client follows it too. */
        void frameWritten(LivingEntity body, UUID bodyId, Vec3 feet);
    }

    private final Seat seat;
    private final Map<UUID, Lease> leases = new LinkedHashMap<>();

    public ControlledMotion(Seat seat) {
        this.seat = seat;
    }

    /** One stable hold, keyed by the body it owns. */
    private static final class Lease {
        final UUID id;
        final LivingEntity body;
        final boolean player;
        /** The real feet this hold is anchored to; rewritten when the body is legitimately displaced. */
        Vec3 anchor;
        final GridPos originCell;
        /** The height the hold keeps. */
        double heldY;

        Lease(UUID id, LivingEntity body, boolean player, Vec3 originFeet, GridPos originCell) {
            this.id = id;
            this.body = body;
            this.player = player;
            this.anchor = originFeet;
            this.originCell = originCell;
            this.heldY = originFeet.y;
        }

        /** Move this hold's anchor to where the body legally is now, instead of dragging it back to the old one. */
        void rebase(Vec3 feet) {
            this.anchor = feet;
            this.heldY = feet.y;
        }
    }

    /**
     * True while a finite transit really owns the encounter.
     *
     * <p>Deliberately <b>not</b> "any body is owned": a stable levitation hold is a state, not an action in
     * progress, and answering busy for it made every command of every participant wait on a floating unit's own
     * clock. A hold is reported by {@link #holding(UUID)} instead.</p>
     */
    public boolean busy() {
        return false;
    }

    /** True when this exact body is held up by a native cause this encounter is honouring. */
    public boolean holding(UUID id) {
        return id != null && leases.containsKey(id);
    }

    /** True when this exact body is currently owned, so no other rule may pin, snap or drop it. */
    public boolean owns(UUID id) {
        return id != null && leases.containsKey(id);
    }

    public boolean ownsAny(boolean player) {
        for (Lease lease : leases.values()) if (lease.player == player) return true;
        return false;
    }

    /**
     * The feet this body must be held at right now, or {@code null} when vanilla physics owns its motion.
     *
     * <p>A risen body is held exactly: the encounter's pin uses this answer instead of the cell centre, which is
     * what lets a held body stay between two cells without ever becoming a flying one.</p>
     */
    public Vec3 heldFeet(UUID id) {
        Lease lease = leases.get(id);
        if (lease == null) return null;
        return new Vec3(lease.anchor.x, lease.heldY, lease.anchor.z);
    }

    /** The height a risen body is being held at, or {@code null}. */
    public Double heldY(UUID id) {
        Lease lease = leases.get(id);
        return lease == null ? null : lease.heldY;
    }

    /**
     * One controlled rise: the single upward relocation of this own turn, which renews an existing hold.
     *
     * <p>Every gate is decided before the body moves: the native effect must still be on the body, the whole body
     * must fit at every block of the way, the destination cell must be inside this battlefield and free, and the
     * destination must be a loaded chunk. A refused rise changes nothing and costs nothing - the caller keeps the
     * unit where it really stands, and the ordinary unsupported-unit rule decides what happens next.</p>
     *
     * <p>A prior hold of the same body is <b>not</b> a reason to refuse: it is the previous own cycle's rise, and
     * the rule is one rise per own turn for as long as the native effect lasts.</p>
     *
     * @return true when the body was really raised
     */
    public boolean beginRise(LivingEntity body, boolean player, int amplifier) {
        if (body == null || body.level() != seat.level()) return false;
        UUID id = body.getUUID();
        if (!body.isAlive() || body.isRemoved()) return false;
        ServerLevel level = seat.level();
        Vec3 from = body.position();
        GridPos fromCell = new GridPos(Mth.floor(from.x), Mth.floor(from.y + 1.0E-7D), Mth.floor(from.z));
        if (!seat.insideZone(fromCell)) return false;
        int wanted = MotionRules.levitationRiseBlocks(amplifier);
        int free = freeBlocksAbove(body, level, from, wanted);
        double targetY = MotionRules.levitationTargetFeet(from.y, wanted, free);
        // Free space is measured in whole blocks above the body: the rise either really climbs or it does not
        // happen, so a body under a low ceiling is never nudged into the ceiling by a fraction of a block.
        if (targetY <= from.y + 1.0E-7D) return false;
        // The climb is a displacement like any other, so it is proved the same way: every step of the whole
        // segment - not only the final cell - has to be a real place for the real body, which is what catches a
        // thin obstruction the body would pass on the way up.
        if (!segmentIsReal(body, level, from, new Vec3(from.x, targetY, from.z), id, null)) return false;
        GridPos targetCell = new GridPos(fromCell.x(), Mth.floor(targetY + 1.0E-7D), fromCell.z());
        if (!seat.insideZone(targetCell) || !seat.cellFree(targetCell, id)) return false;
        Lease lease = leases.get(id);
        if (lease == null || lease.body != body) {
            lease = new Lease(id, body, player, from, fromCell);
            leases.put(id, lease);
        } else if (lease.anchor.distanceToSqr(from) > 1.0E-6D) {
            // The body was legitimately moved since the previous cycle: the hold follows it instead of pulling it
            // back to the anchor it used to have.
            lease.rebase(from);
        }
        lease.heldY = targetY;
        body.setPos(from.x, targetY, from.z);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        GridPos landed = new GridPos(Mth.floor(from.x), Mth.floor(targetY + 1.0E-7D), Mth.floor(from.z));
        seat.settled(body, id, landed, body.position());
        seat.frameWritten(body, id, body.position());
        return true;
    }

    /** How many whole blocks above this body's current head are really free, up to {@code wanted}. */
    private static int freeBlocksAbove(LivingEntity body, ServerLevel level, Vec3 feet, int wanted) {
        AABB box = body.getBoundingBox();
        int free = 0;
        for (int step = 1; step <= wanted; step++) {
            Vec3 probeFeet = feet.add(0.0D, step, 0.0D);
            if (!level.isLoaded(BlockPos.containing(probeFeet))) break;
            AABB probe = box.move(0.0D, step, 0.0D);
            if (!level.noCollision(body, probe)) break;
            free = step;
        }
        return free;
    }

    /**
     * Adopt one displacement vanilla already accepted, and move the body there exactly once.
     *
     * <p>Every step of the segment is proved before the body is written: the swept whole-body box has to stay in
     * loaded columns, inside the world border, free of collisions, and inside this battlefield, and no cell it
     * touches may belong to another participant. The push is then clipped to the <b>last real point</b> - never
     * "move it out and label the cell it left", and never "rewind it to where it started", both of which would
     * make this encounter's own record a lie about a body the player can see.</p>
     *
     * <p>The vector itself is vanilla's: an immovable body answers no motion, and a body vanilla refused to move
     * is never adopted at all.</p>
     *
     * @return true when the body really moved
     */
    public boolean applyImpulse(LivingEntity body, boolean player, Vec3 displacement) {
        return applyImpulse(body, player, displacement, null);
    }

    /**
     * The same displacement, with the body whose own action caused it exempted from the sweep.
     *
     * <p>An opening shell displaces whatever stands in its opening, and that box is measured from the shell
     * itself - so the shell is legitimately inside it. Exempting exactly that one body is what lets a legal escape
     * push be proved at all; every other live body and every reservation still counts for the whole segment, and
     * nothing about body collision is switched off.</p>
     */
    public boolean applyImpulse(LivingEntity body, boolean player, Vec3 displacement,
                                @javax.annotation.Nullable UUID exceptBody) {
        if (body == null || displacement == null || body.level() != seat.level()) return false;
        if (!body.isAlive() || body.isRemoved()) return false;
        UUID id = body.getUUID();
        ServerLevel level = seat.level();
        Vec3 from = body.position();
        GridPos fromCell = new GridPos(Mth.floor(from.x), Mth.floor(from.y + 1.0E-7D), Mth.floor(from.z));
        if (!seat.insideZone(fromCell)) return false;
        if (displacement.lengthSqr() < 1.0E-8D) return false;
        Vec3 clipped = null;
        for (double fraction : MotionRules.impulseClipFractions(MotionRules.IMPULSE_CLIP_SAMPLES)) {
            if (fraction <= 0.0D) break;
            Vec3 candidate = from.add(displacement.scale(fraction));
            if (!segmentIsReal(body, level, from, candidate, id, exceptBody)) continue;
            clipped = candidate;
            break;
        }
        if (clipped == null) return false;
        // A real displacement supersedes a levitation hold's own anchor: the hold is released here and the next
        // own turn rises from wherever the body really ended up.
        release(id);
        body.setPos(clipped.x, clipped.y, clipped.z);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        GridPos landed = new GridPos(Mth.floor(clipped.x), Mth.floor(clipped.y + 1.0E-7D), Mth.floor(clipped.z));
        seat.settled(body, id, landed, body.position());
        seat.frameWritten(body, id, body.position());
        return true;
    }

    /**
     * Whether one whole-body displacement would be a real move.
     *
     * <p>Five facts, all asked of the <b>swept</b> box rather than of the result: the build height, the world
     * border, every column loaded, no block collision, this battlefield - and no other live participant body or
     * reservation anywhere along the way. A body is not its destination cell, and a segment is not its endpoint.</p>
     */
    private boolean segmentIsReal(LivingEntity body, ServerLevel level, Vec3 from, Vec3 to,
                                 UUID bodyId, @javax.annotation.Nullable UUID exceptBody) {
        AABB swept = com.tactical.neoforge.battle.BodyGeometry.swept(body, from, to);
        if (swept == null) return false;
        if (swept.minY < level.getMinBuildHeight() || swept.maxY > level.getMaxBuildHeight()) return false;
        if (!level.getWorldBorder().isWithinBounds(swept)) return false;
        if (!com.tactical.neoforge.battle.BodyGeometry.loaded(level, swept)) return false;
        if (level.getBlockCollisions(body, swept).iterator().hasNext()) return false;
        // An opening shell already overlaps the body's origin. Exempt that exact initiator here too,
        // not only in the roster check below; all other native collidable entities remain obstacles.
        for (net.minecraft.world.entity.Entity other : level.getEntities(body, swept,
                entity -> !entity.isSpectator() && body.canCollideWith(entity))) {
            if (exceptBody == null || !exceptBody.equals(other.getUUID())) return false;
        }
        if (!com.tactical.neoforge.battle.BodyGeometry.boxInsideZone(swept, seat::insideZone)) return false;
        return seat.sweepFree(bodyId, swept, exceptBody);
    }

    /**
     * One tick of every held body.
     *
     * <p>A rise only has to be held: the height is written back, motion zeroed and the fall distance cleared, so
     * vanilla gravity cannot lower a body the native effect is keeping up. An impulse needs nothing here - it is
     * applied as one already-validated displacement and its cell is authoritative from that moment.</p>
     *
     * @return true when some authoritative state changed
     */
    public boolean tick() {
        if (leases.isEmpty()) return false;
        boolean changed = false;
        List<UUID> finished = new ArrayList<>();
        for (Lease lease : List.copyOf(leases.values())) {
            LivingEntity body = lease.body;
            if (!body.isAlive() || body.isRemoved() || body.level() != seat.level()) {
                finished.add(lease.id);
                changed = true;
                continue;
            }
            Vec3 held = new Vec3(lease.anchor.x, lease.heldY, lease.anchor.z);
            if (body.position().distanceToSqr(held) > 1.0E-6D) {
                body.setPos(held.x, held.y, held.z);
                seat.frameWritten(body, lease.id, held);
                changed = true;
            }
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
        }
        for (UUID id : finished) leases.remove(id);
        return changed;
    }

    /** Release one body, keeping the encounter's last authoritative record. */
    public void release(UUID id) {
        Lease lease = leases.remove(id);
        if (lease != null) seat.released(lease.body, id);
    }

    /** Release everything: end of encounter, or a unit that left the roster. */
    public void clear() {
        for (Lease lease : leases.values()) seat.released(lease.body, lease.id);
        leases.clear();
    }

    /** Diagnostic form, used by the dedicated-server fixture and by a refusal log. */
    public String describe() {
        if (leases.isEmpty()) return "none";
        StringBuilder builder = new StringBuilder();
        for (Lease lease : leases.values()) {
            if (builder.length() > 0) builder.append(',');
            builder.append("rise:").append(lease.id)
                    .append('@').append(String.format(java.util.Locale.ROOT, "%.3f", lease.heldY));
        }
        return builder.toString();
    }
}
