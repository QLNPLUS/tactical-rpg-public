package com.tactical.neoforge.battle;

import com.tactical.core.grid.GridPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Shulker;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The landing gate a native shulker relocation answers to, at the exact post-event / pre-side-effect seam.
 *
 * <p>{@code Shulker.teleportSomewhere} fires the cancelable {@code EnderEntity} event, lets <em>every</em>
 * listener re-point the destination, and only then calls {@code setAttachFace}, plays the sound and calls
 * {@code setPos}. Three facts follow from that order, and each of them decides where this gate has to live:</p>
 *
 * <ul>
 *   <li>a check made before the event validates a point a later listener may already have replaced, so an event
 *       priority - even {@code HIGHEST} - is not enough: {@code NORMAL} and {@code LOW} listeners run
 *       afterwards and can still re-point the destination;</li>
 *   <li>a check made after the event but after {@code setPos} is too late, because the body has already moved and
 *       "repairing" it is just a second, unearned displacement;</li>
 *   <li>the face written by vanilla comes from the <em>pre-event</em> candidate, so a re-pointed destination
 *       would be glued to a face that was never proved there.</li>
 * </ul>
 *
 * <p>So the gate is consulted by the mixin immediately after {@code EventHooks.onEnderTeleport} returns - all
 * listeners have run, nothing has moved yet - and it hands the validated face back for the mixin to write before
 * {@code setPos}. A relocation this encounter refuses is cancelled there, which is the only place that costs
 * nothing and moves nothing.</p>
 *
 * <p>Every authorized relocation uses this one gate: the own-turn maneuver and the native reaction inside
 * {@code hurt}/{@code hitByShulkerBullet} alike. A shulker with no armed request is vanilla's, untouched.</p>
 */
public final class ShulkerLandingGate {

    /** The encounter's own answer about the cell the event finally names. */
    public interface Gate {
        /**
         * @return the face to glue the body to, or {@code null} when this landing is refused
         */
        @javax.annotation.Nullable
        Direction tactical$mayLand(Shulker shulker, GridPos finalCell);
    }

    private static final ThreadLocal<Map<UUID, Request>> ARMED = new ThreadLocal<>();

    private static final class Request {
        final Shulker shulker;
        final Gate gate;
        Direction validatedFace;

        Request(Shulker shulker, Gate gate) {
            this.shulker = shulker;
            this.gate = gate;
        }
    }

    private ShulkerLandingGate() {}

    /** Arm the gate for exactly one native relocation of that body. */
    public static void arm(Shulker shulker, Gate gate) {
        if (shulker == null || gate == null) return;
        Map<UUID, Request> armed = ARMED.get();
        if (armed == null) {
            armed = new HashMap<>();
            ARMED.set(armed);
        }
        armed.put(shulker.getUUID(), new Request(shulker, gate));
    }

    /** Disarm this body; safe to call when nothing was armed. */
    public static void disarm(Shulker shulker) {
        Map<UUID, Request> armed = ARMED.get();
        if (armed == null) return;
        if (shulker == null) armed.clear();
        else armed.remove(shulker.getUUID());
        if (armed.isEmpty()) ARMED.remove();
    }

    /** Disarm everything on this thread: encounter teardown only. */
    public static void disarmAll() {
        ARMED.remove();
    }

    /**
     * Judge the point the event finally names, after every listener ran and before vanilla touches the body.
     *
     * <p>Cancels the event when the landing is refused, which is exactly the state vanilla already handles: its
     * own code nulls the face for a cancelled event and therefore never calls {@code setPos}.</p>
     */
    public static void validateFinal(Shulker shulker, EntityTeleportEvent.EnderEntity event) {
        if (shulker == null || event == null) return;
        Request request = request(shulker);
        if (request == null) return;
        request.validatedFace = null;
        if (event.isCanceled()) return;
        double x = event.getTargetX();
        double y = event.getTargetY();
        double z = event.getTargetZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            event.setCanceled(true);
            return;
        }
        // The exact quantisation vanilla is about to apply, so the validated cell is the used cell.
        BlockPos used = BlockPos.containing(x, y, z);
        Direction face = request.gate.tactical$mayLand(shulker,
                new GridPos(used.getX(), used.getY(), used.getZ()));
        if (face == null) {
            event.setCanceled(true);
            return;
        }
        request.validatedFace = face;
    }

    /**
     * The face the mixin must write instead of the pre-event one, consumed once per native call.
     *
     * @return the validated face, or {@code null} when vanilla's own face stays as it is
     */
    @javax.annotation.Nullable
    public static Direction takeValidatedFace(Shulker shulker) {
        Request request = request(shulker);
        if (request == null || request.validatedFace == null) return null;
        Direction face = request.validatedFace;
        request.validatedFace = null;
        return face;
    }

    /** The armed request for this body, if any. */
    @javax.annotation.Nullable
    private static Request request(Shulker shulker) {
        Map<UUID, Request> armed = ARMED.get();
        if (armed == null || shulker == null) return null;
        Request request = armed.get(shulker.getUUID());
        return request != null && request.shulker == shulker ? request : null;
    }

    /** Quantised cell helper kept next to the gate so the used cell is computed one way only. */
    public static GridPos cellOf(double x, double y, double z) {
        return new GridPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
    }
}
