package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Guardian;

import java.util.Objects;

/**
 * The narrow bridge to the attack goal a guardian <em>really</em> has installed, plus the receipt for its two hits.
 *
 * <p>{@code Guardian.registerGoals} creates one {@code Guardian.GuardianAttackGoal} and adds it to the goal
 * selector. That object is the creature's own charged beam: its {@code start()} pre-heats {@code attackTime} at
 * {@code -10} and looks at its target, its {@code tick()} keeps the target, stops navigation and looks, sets the
 * synched attack target and broadcasts event {@code 21} when the count reaches zero, and at
 * {@code getAttackDuration()} it really calls {@code indirectMagic} hurt and {@code doHurtTarget} in that order.
 * Nothing of that is re-implemented here.</p>
 *
 * <p>What this bridge does is exactly three things vanilla's selector would otherwise do: drive that one goal's
 * {@code start()}, {@code tick()} and {@code stop()} for a body this encounter holds, and report back whether each
 * of the two native hurt calls really landed. It deliberately does not tick the selector, does not remove the goal,
 * does not open {@code NoAI} or {@code customServerAiStep}, and never writes a damage number.</p>
 *
 * <p><b>Identity is binding, not advice.</b> A goal can be removed from the selector or replaced by a newly
 * constructed one, and a body's target can be changed by an event. The caller therefore hands this bridge the exact
 * goal object it launched, every driven tick proves that object is still in that body's selector, and every report
 * must name the very actor, goal and target this drive was opened for. Two guardians aiming at the same body, a
 * nested goal tick inside a damage listener, or a look-alike goal object can therefore never fill this receipt.</p>
 *
 * <p>The receipt is a nested-safe scope opened for exactly one {@code tick()} call and cleared in a
 * {@code finally}. A first段 that landed and a second段 that threw still report that first hit - including the case
 * where the physical part's inner {@code hurt} returned true and only the post-attack effects threw afterwards.</p>
 */
public final class NativeBeamBridge {

    /** What one driven goal tick really did: whether it reached its two native calls, and whether either landed. */
    public record Receipt(boolean attempted, boolean magic, boolean melee) {
        /** True when at least one of the two native calls reported a real hit. */
        public boolean hurt() { return magic || melee; }
    }

    /** The live native drive whose result is being awaited, one per thread and never nested. */
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private static final class Attempt {
        final Guardian guardian;
        final Goal goal;
        final LivingEntity target;
        boolean attempted;
        boolean magic;
        boolean melee;
        boolean physicalCall;
        boolean closed;

        private Attempt(Guardian guardian, Goal goal, LivingEntity target) {
            this.guardian = guardian;
            this.goal = goal;
            this.target = target;
        }
    }

    private NativeBeamBridge() { }

    /**
     * The native attack goal that exact body really has installed right now, or null.
     *
     * <p>The object is captured where vanilla creates it - inside the goal's own constructor - and this answer also
     * proves it is still the object in that body's own selector. A goal that was removed, replaced by a freshly
     * constructed one, or never created at all answers null, so a running action can never keep driving an object
     * the body no longer owns.</p>
     */
    public static Goal installedGoal(Guardian guardian) {
        if (guardian == null) return null;
        if (!(guardian instanceof TacticalGuardianAccess access)) return null;
        Goal goal = access.tactical$attackGoal();
        if (goal == null || !access.tactical$attackGoalInstalled()) return null;
        return goal;
    }

    /** Whether that exact goal object is still the one installed in that body's own selector. */
    public static boolean stillInstalled(Guardian guardian, Goal goal) {
        return guardian != null && goal != null && installedGoal(guardian) == goal;
    }

    /**
     * How far that goal's own charge has advanced, read from the object itself.
     *
     * <p>Vanilla's {@code attackTime} is the whole pre-heat/charge arithmetic, and an admission that borrows the body
     * has to be able to give it back exactly as it was: a body taken over mid-charge must not be left looking like it
     * had never charged.</p>
     */
    public static int chargeProgress(Goal goal) {
        return goal instanceof GuardianAttackGoalAccess access ? access.tactical$attackTime() : Integer.MIN_VALUE;
    }

    /** Put that goal's own charge arithmetic back to a saved value, when this mod can reach it at all. */
    public static boolean restoreChargeProgress(Goal goal, int attackTime) {
        if (!(goal instanceof GuardianAttackGoalAccess access) || attackTime == Integer.MIN_VALUE) return false;
        access.tactical$attackTime(attackTime);
        return true;
    }

    /**
     * Point that body at this exact opponent and start its own goal: the pre-heat, the look, the stopped
     * navigation and the impulse are all vanilla's.
     *
     * @return true when the body really holds that target afterwards, the goal is still installed and it was started
     */
    public static boolean start(Guardian guardian, Goal goal, LivingEntity target) {
        Objects.requireNonNull(guardian, "beam actor");
        Objects.requireNonNull(target, "beam target");
        if (!stillInstalled(guardian, goal)) return false;
        guardian.setTarget(target);
        // A target change is an event: a listener may cancel it or name another body. Starting the goal on a target
        // the body does not really hold would charge for a beam pointed at somebody else.
        if (guardian.getTarget() != target) return false;
        goal.start();
        // A start callback may itself change the target or the selector; the launch only counts if the same object
        // is still installed and still pointed at this opponent.
        return stillInstalled(guardian, goal) && guardian.getTarget() == target;
    }

    /**
     * Advance that goal by exactly one server tick and report the real outcome of its two native calls.
     *
     * <p>The receipt scope is opened before the call and closed in a {@code finally}, so a thrown native failure is
     * reported to the caller through the returned receipt rather than swallowed: a first段 that landed before the
     * throw still counts as a hit and still owes exactly one price. A goal that is no longer installed is never
     * ticked at all, so a replaced object cannot fire on this turn's behalf.</p>
     */
    public static Receipt tick(Guardian guardian, Goal goal, LivingEntity target) {
        Objects.requireNonNull(guardian, "beam actor");
        Objects.requireNonNull(target, "beam target");
        if (!stillInstalled(guardian, goal)) return new Receipt(false, false, false);
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt(guardian, goal, target);
        CURRENT.set(attempt);
        try {
            goal.tick();
        } catch (RuntimeException failure) {
            // The goal throws only out of its own settlement branch (a native call or one of its listeners). That
            // branch has already been reached, so it must count as the one settlement point: retrying the tick on the
            // next server tick would let the same charge repeat its damage, and dropping it would lose the price of a
            // hit that really happened. Whatever the wrapped calls reported before the throw is kept.
            attempt.attempted = true;
            Tactical.LOGGER.error("[Tactical] native guardian beam failed for {} at {} (magic={}, melee={})",
                    guardian.getUUID(), guardian.position(), attempt.magic, attempt.melee, failure);
        } finally {
            attempt.closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
        return new Receipt(attempt.attempted, attempt.magic, attempt.melee);
    }

    /**
     * Let the native goal end that body's attack, exactly as the selector would.
     *
     * <p>Vanilla's own {@code stop()} clears the synched attack target, clears the target and triggers the stroll
     * goal, so a finished beam is not left pointing at anything. It is called on the exact object this action
     * launched, never on a look-alike looked up later.</p>
     */
    public static void stop(Guardian guardian, Goal goal) {
        if (goal == null) return;
        goal.stop();
    }

    /**
     * Mixin entry point for the {@code indirectMagic} hurt call inside the goal's own {@code tick}.
     *
     * <p>Anything outside an open scope - a wild guardian's own AI, another encounter's body, a thorn reaction - is
     * ignored here and keeps whatever vanilla decided. Inside a scope, all three identities have to match.</p>
     */
    public static void reportMagic(Guardian actor, Goal goal, LivingEntity receiver, boolean hurt) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.closed) return;
        if (!bound(attempt, actor, goal, receiver)) return;
        attempt.attempted = true;
        if (hurt) attempt.magic = true;
    }

    /**
     * Mixin entry point for the native {@code doHurtTarget} call inside the same goal tick.
     *
     * <p>It reports that the call was made even when it returned false: a settlement point that really ran is what
     * ends the beam, whether or not the target could be hurt.</p>
     */
    public static void reportMelee(Guardian actor, Goal goal, Entity receiver, boolean hurt) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.closed) return;
        if (!bound(attempt, actor, goal, receiver)) return;
        attempt.attempted = true;
        if (hurt) attempt.melee = true;
    }

        /** Arm the inner-hurt receipt only for this goal's actual physical call, never for a side attack. */
        public static boolean callPhysical(Guardian actor, Goal goal, Entity receiver,
                                           java.util.function.BooleanSupplier nativeCall) {
            Attempt attempt = CURRENT.get();
            if (attempt == null) return nativeCall.getAsBoolean();
            if (attempt.closed || !bound(attempt, actor, goal, receiver)) {
                CURRENT.remove();
                try {
                    return nativeCall.getAsBoolean();
                } finally {
                    CURRENT.set(attempt);
                }
            }
            boolean previous = attempt.physicalCall;
            attempt.physicalCall = true;
            try {
                return nativeCall.getAsBoolean();
            } finally {
                attempt.physicalCall = previous;
            }
        }

    /**
     * Mixin entry point for the {@code hurt} call <em>inside</em> {@code Mob.doHurtTarget}.
     *
     * <p>This is the physical part's real return. {@code doHurtTarget} can throw in its enchantment post-effects
     * after that inner hit already landed; the outer wrapper would then never see a return at all, and the hit would
     * be lost. Only the physical call of the authorised attack is touched: no behaviour is changed, the wrapper
     * returns exactly what the original returned, and anything outside this drive is ignored.</p>
     */
    public static void reportInnerHurt(Mob attacker, Entity receiver, boolean hurt) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || attempt.closed) return;
        if (!attempt.physicalCall) return;
        if (!bound(attempt, attempt.guardian, attempt.goal, receiver)) return;
        if (attempt.guardian != attacker) return;
        attempt.attempted = true;
        if (hurt) attempt.melee = true;
    }

    private static boolean bound(Attempt attempt, Guardian actor, Goal goal, Entity receiver) {
        return actor != null && actor == attempt.guardian
                && goal != null && goal == attempt.goal
                && receiver == attempt.target;
    }

    /** Whether one of this mod's guardian goal drives is running right now, for diagnostics only. */
    public static boolean inScope() {
        Attempt attempt = CURRENT.get();
        return attempt != null && !attempt.closed;
    }
}
