package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.mixin.GuardianNativeInvoker;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Guardian;

/**
 * The exact native attack state one guardian had before this encounter borrowed it, and how to give it back.
 *
 * <p>Three facts, all read from the entity's own native state rather than from a mirror: the {@code data_moving}
 * bit, the raw synched attack-target id the client draws a beam from, and the goal's own charge counter. A body that
 * was already charging when it joined must not be left looking as if it never had, and a rolled-back admission must
 * be exactly the creature it was - including a moving bit that really was {@code true}.</p>
 */
public final class GuardianNativePose {

    private GuardianNativePose() { }

    /** What that body's own native attack state was: nothing here is guessed from the mob's target. */
    public record Snapshot(boolean moving, int attackTargetId, int chargeProgress) { }

    public static Snapshot snapshot(Guardian guardian) {
        if (guardian == null) return null;
        return new Snapshot(guardian.isMoving(), rawAttackTargetId(guardian), chargeProgress(guardian));
    }

    /** The captured goal's charge counter, so a snapshot is taken from the real object. */
    public static int chargeProgress(Guardian guardian) {
        Goal goal = capturedGoal(guardian);
        return goal == null ? Integer.MIN_VALUE : NativeBeamBridge.chargeProgress(goal);
    }

    /** The raw {@code DATA_ID_ATTACK_TARGET} value, exactly as the client would read it. */
    public static int rawAttackTargetId(Guardian guardian) {
        if (guardian == null) return 0;
        return guardian.getEntityData().get(GuardianNativeInvoker.tactical$attackTargetData());
    }

    /**
     * Give back exactly the values that were saved, and nothing else.
     *
     * <p>Idempotent, and it never substitutes one fact for another: the moving bit is written to the saved value in
     * both directions (a body that really was moving stays moving), the raw attack-target id comes straight from the
     * data accessor instead of being guessed from the mob's target, and the goal's own charge counter is put back when
     * this mod can reach that goal. Each write is isolated, so a throwing listener cannot silently leave a
     * half-written pose behind.</p>
     */
    public static void restore(Guardian guardian, Snapshot snapshot) {
        if (guardian == null || snapshot == null) return;
        try {
            ((GuardianNativeInvoker) guardian).tactical$setActiveAttackTarget(snapshot.attackTargetId());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] could not restore the guardian attack target for {}",
                    guardian.getUUID(), failure);
        }
        try {
            GuardianMotion.moving(guardian, snapshot.moving());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] could not restore the guardian moving bit for {}",
                    guardian.getUUID(), failure);
        }
        try {
            NativeBeamBridge.restoreChargeProgress(capturedGoal(guardian), snapshot.chargeProgress());
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] could not restore the guardian charge counter for {}",
                    guardian.getUUID(), failure);
        }
    }

    /**
     * Take a body that vanilla was already mid-attack with out of that old attack.
     *
     * <p>This is what a successful takeover does: the old unpaid charge is ended through the same native goal object,
     * and its synched attack target is cleared, so nothing from before this encounter keeps drawing a beam on
     * somebody else's turn. No goal is left started and no price is invented for the old charge.</p>
     */
    public static void clearOldAttack(Guardian guardian) {
        if (guardian == null) return;
        Goal goal = NativeBeamBridge.installedGoal(guardian);
        if (goal != null) {
            try {
                NativeBeamBridge.stop(guardian, goal);
            } catch (RuntimeException failure) {
                Tactical.LOGGER.error("[Tactical] the native goal of {} failed to stop cleanly",
                        guardian.getUUID(), failure);
            }
        }
        // The presentation is this encounter's to clear even when the goal could not be reached: a stale synched
        // attack target would keep a client drawing a beam nothing is charging any more.
        try {
            ((GuardianNativeInvoker) guardian).tactical$setActiveAttackTarget(0);
        } catch (RuntimeException failure) {
            Tactical.LOGGER.error("[Tactical] could not clear the synched attack target of {}",
                    guardian.getUUID(), failure);
        }
    }

    /** The goal this body's own constructor created, whether or not it is still in the selector. */
    private static Goal capturedGoal(Guardian guardian) {
        return guardian instanceof TacticalGuardianAccess access ? access.tactical$attackGoal() : null;
    }
}
