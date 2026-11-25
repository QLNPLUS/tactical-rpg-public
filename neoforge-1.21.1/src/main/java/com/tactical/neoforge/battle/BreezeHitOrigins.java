package com.tactical.neoforge.battle;

import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;

import java.util.ArrayDeque;

/**
 * The immutable owned origin of one marked wind charge, preserved across exactly one native direct-hit call.
 *
 * <p>A wind charge's native {@code onHitEntity} hurts the body it struck and then, on that same stack, runs the
 * charge's burst. The encounter's own book-keeping can legitimately run <em>inside</em> that hurt: the locked target
 * dies or leaves the field, the firing body dies, the whole encounter ends. Any of those answers really drop the
 * charge - its mark is removed and the projectile is discarded - but discarding an entity cannot unwind a method
 * that is already executing, so the native call walks on into {@code explode} with no mark left.</p>
 *
 * <p>Without this snapshot the burst would be admitted as a <em>wild</em> charge: nothing about it would be narrowed,
 * so its push, its block callbacks and its interaction would hit whatever stood in radius three - side effects of a
 * shot the encounter had already revoked. The origin recorded here is the answer to exactly that: the mark as it
 * really was when the direct hit began, kept only for the duration of that one call and only for that one exact
 * charge object.</p>
 *
 * <p>Scope and lifetime are deliberately narrow:</p>
 * <ul>
 *   <li>identity is the charge object itself, never an entity id or a position;</li>
 *   <li>the stack is per thread and pushed before the native call, popped in a {@code finally}, so a throwing call
 *       cannot leave a token behind;</li>
 *   <li>it is not authority - {@link BreezeBurst#arm} uses it only to know that a still-executing burst belongs to
 *       this encounter, and such a burst is answered deny-all rather than with any scope that could affect a
 *       body;</li>
 *   <li>a charge this encounter never marked records nothing, so wild and foreign charges keep their native
 *       behaviour bit for bit.</li>
 * </ul>
 */
public final class BreezeHitOrigins {

    /** One exact charge inside its own native direct-hit call, with the mark it really had when that call began. */
    private record Token(BreezeWindCharge charge, BreezeWindCharges.Owner origin) {}

    /** The direct-hit tokens of the current thread, innermost last. */
    private static final ThreadLocal<ArrayDeque<Token>> TOKENS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private BreezeHitOrigins() {}

    /**
     * Snapshot the real registered ownership of that charge, before any direct side effect of the hit runs.
     *
     * <p>Called at the head of the native direct-hit callback. A charge with no mark records nothing: this is a
     * record of ownership that already existed, never a way to acquire it.</p>
     */
    public static void begin(BreezeWindCharge charge) {
        if (charge == null) return;
        BreezeWindCharges.Owner origin = BreezeWindCharges.markOf(charge);
        if (origin == null) return;
        TOKENS.get().addLast(new Token(charge, origin));
    }

    /**
     * Drop that exact token once its native call has returned, normally or by throwing.
     *
     * <p>The innermost token of this exact charge is removed, so nesting can never pop somebody else's snapshot and
     * an unmarked call removes nothing.</p>
     */
    public static void end(BreezeWindCharge charge) {
        if (charge == null) return;
        ArrayDeque<Token> tokens = TOKENS.get();
        for (java.util.Iterator<Token> iterator = tokens.descendingIterator(); iterator.hasNext(); ) {
            if (iterator.next().charge() != charge) continue;
            iterator.remove();
            break;
        }
        if (tokens.isEmpty()) TOKENS.remove();
    }

    /**
     * The owned origin this exact charge still carries because its own direct-hit call has not returned yet.
     *
     * <p>Deliberately not a lookup into the registry: a mark that still exists is read from the registry, and this
     * answers only for the window in which a mark was removed while its own call was still running.</p>
     */
    @javax.annotation.Nullable
    public static BreezeWindCharges.Owner originOf(BreezeWindCharge charge) {
        if (charge == null) return null;
        ArrayDeque<Token> tokens = TOKENS.get();
        for (java.util.Iterator<Token> iterator = tokens.descendingIterator(); iterator.hasNext(); ) {
            Token token = iterator.next();
            if (token.charge() == charge) return token.origin();
        }
        return null;
    }

    /** How many direct-hit snapshots this thread currently holds; a fixture reads it to prove nothing leaked. */
    public static int depth() {
        return TOKENS.get().size();
    }
}
