package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.function.Supplier;

/**
 * A scoped receipt for one native entity interaction ({@code Player.interactOn}).
 *
 * <p>The native call has two independent answers: the {@link InteractionResult} the entity and the item
 * produced, and whether a {@link PlayerInteractEvent.EntityInteract} listener cancelled the interaction.
 * A cancelled event can still hand back {@code SUCCESS} or {@code CONSUME} - the event's cancellation
 * result is whatever the cancelling listener set - so the result alone must never decide that an action
 * was really performed.</p>
 *
 * <p>The listener therefore records the exact event instance the scoped call posted, and the caller reads
 * its final {@code isCanceled()} after the native call returned. Nothing global is observed: an attempt
 * only exists on the thread that is running the scoped interaction, for that actor and that target.</p>
 *
 * <p>The listener is registered at {@link EventPriority#LOWEST} <em>with</em>
 * {@code receiveCanceled = true}. The bus annotation defaults that flag to false (verified against the
 * installed bus), and a listener that does not receive already cancelled events would never see the
 * event a higher-priority listener cancelled - the receipt would stay empty and a cancelled interaction
 * whose cancellation result was SUCCESS would be committed.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class NativeEntityUseReceipt {

    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    /**
     * Development-only fault injection: {@code -Dtactical.dev.cancelEntityInteract=success} makes an
     * ordinary-priority listener cancel the scoped entity interaction with that cancellation result, so a
     * real run can prove the receipt sees the cancellation instead of only proving a boolean.
     */
    private static final String DEV_CANCEL_PROPERTY = "tactical.dev.cancelEntityInteract";

    private static final class Attempt {
        private final Player actor;
        private final Entity target;
        private final InteractionHand hand;
        private PlayerInteractEvent.EntityInteract event;

        private Attempt(Player actor, Entity target, InteractionHand hand) {
            this.actor = actor;
            this.target = target;
            this.hand = hand;
        }
    }

    private NativeEntityUseReceipt() {
    }

    /** The result of one scoped interaction, with the event it posted (null when none was posted). */
    public record Outcome(InteractionResult result, PlayerInteractEvent.EntityInteract event) {

        /** True when a listener cancelled the interaction, whatever result it returned. */
        public boolean cancelled() {
            return event != null && event.isCanceled();
        }

        /**
         * True only when the native call reported an action and no listener cancelled it: the single
         * condition under which the battle may commit AP, escalation and the item change.
         */
        public boolean committed() {
            return commits(result, cancelled());
        }
    }

    /**
     * The commit rule on its own: a cancelled interaction spends nothing, however tempting its result
     * looks. A cancelling listener may set {@code SUCCESS} or {@code CONSUME} as the cancellation result,
     * so {@code consumesAction} by itself is never enough.
     */
    public static boolean commits(InteractionResult result, boolean cancelled) {
        return result != null && result.consumesAction() && !cancelled;
    }

    public static Outcome run(Player actor, Entity target, InteractionHand hand,
                              Supplier<InteractionResult> interaction) {
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt(actor, target, hand);
        CURRENT.set(attempt);
        try {
            InteractionResult result = interaction.get();
            return new Outcome(result, attempt.event);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** The final state of the one event this scoped interaction posted, cancelled or not. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void entityInteract(PlayerInteractEvent.EntityInteract event) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || event.getEntity() != attempt.actor || event.getTarget() != attempt.target
                || event.getHand() != attempt.hand) {
            return;
        }
        attempt.event = event;
    }

    /**
     * Development-only cancellation, at an ordinary priority so it provably runs before the receipt: the
     * event it cancels still reaches the LOWEST listener, which is the behaviour under test.
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void devCancelEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Attempt attempt = CURRENT.get();
        if (attempt == null || event.getEntity() != attempt.actor || event.getTarget() != attempt.target
                || event.getHand() != attempt.hand) {
            return;
        }
        String wanted = System.getProperty(DEV_CANCEL_PROPERTY, "");
        InteractionResult result = switch (wanted.toLowerCase(java.util.Locale.ROOT)) {
            case "success" -> InteractionResult.SUCCESS;
            case "consume" -> InteractionResult.CONSUME;
            default -> null;
        };
        if (result == null) return;
        event.setCancellationResult(result);
        event.setCanceled(true);
    }
}
