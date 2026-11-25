package com.tactical.neoforge.battle;

import com.tactical.core.combat.BreezeRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The narrow bridge around <em>one</em> marked breeze wind charge explosion.
 *
 * <p>Vanilla's burst is a real explosion: it carries radius, falloff, exposure and blast resistance, it decides
 * which blocks it interacts with, and it writes a velocity change into every accepted body. Four things about
 * that are this encounter's business, and all four are done here rather than by rewriting any explosion:</p>
 *
 * <ul>
 *   <li><b>who may be affected</b> - a body of the firing breeze's own side, a bystander who is not in this
 *       encounter at all, and another encounter's member are refused <em>before</em> the native damage, the
 *       enchantment effects, the velocity write and {@code onExplosionHit} run. The refusal is made at vanilla's
 *       own per-entity gate, so no later listener can put such a body back into the burst by refilling a list;</li>
 *   <li><b>which blocks it may touch</b> - a charge this encounter fired interacts with no block at all. The
 *       whole native block-interaction branch is closed by emptying the explosion's own block list before the
 *       native callbacks run, and that list is also what the client packet carries, so no client can replay a
 *       trigger the server refused. The interaction kind and the gust particles stay exactly vanilla's, because
 *       they are what the burst looks and sounds like;</li>
 *   <li><b>who really moves</b> - the displacement a body really accepted is read from the body's own setter
 *       result. A body whose setter does nothing (a shulker's is a no-op) therefore keeps a real zero and is never
 *       given a manufactured push. An accepted delta is carried out of vanilla's physics and into the encounter's
 *       one validated motion owner, so the body is moved exactly once, over a swept, loaded, occupied-cell-free
 *       segment;</li>
 *   <li><b>one physics owner for a controlled player</b> - vanilla also sends its own knockback to the client of
 *       every affected player. For a member the encounter already moved, that packet vector is removed before the
 *       packets are sent, so the client is not told to fly somewhere the server never agreed to.</li>
 * </ul>
 *
 * <p><b>Scope and identity.</b> The arming is a per-thread stack of frames, not one global slot: each frame is one
 * exact marked charge, the one exact {@link Explosion} that charge really produced, and that burst's own captured
 * data. Every question below is answered for an exact {@code Explosion} object and refuses when the frame does not
 * name it, so a nested explosion - a wild charge, another mod, another encounter, a deliberate nested marked burst -
 * never inherits the outer filter, and the outer frame keeps its own data across it. The frame is pushed and popped
 * in a {@code try/finally} around the charge's own {@code explode} call, so a throwing native call cannot leave an
 * armed token behind.</p>
 */
public final class BreezeBurst {

    /** What the encounter answers while its own marked charge is bursting. */
    public interface Scope {
        /** True when this burst must not touch that body at all. */
        boolean protects(LivingEntity body);

        /**
         * Take one displacement the body really accepted.
         *
         * @return true when this body's movement is the encounter's now, so vanilla's own write must be undone
         */
        boolean capture(LivingEntity body, Vec3 accepted);

        /** Move every captured body through the encounter's single motion owner, once each. */
        void settle();
    }

    /** One armed marked burst: the exact charge, the exact explosion it produced, and this burst's own data. */
    private static final class Frame {
        final BreezeWindCharge charge;
        final Scope scope;
        /** Bound on first sight of the explosion that carries this exact charge as its direct source. */
        Explosion explosion;
        final List<LivingEntity> bodies = new ArrayList<>();
        final List<Vec3> deltas = new ArrayList<>();
        final Set<UUID> movedPlayers = new HashSet<>();
        /** Real {@code BlockState.onExplosionHit} invocations this burst really reached, for the fixture only. */
        int blockCallbacks;

        Frame(BreezeWindCharge charge, Scope scope) {
            this.charge = charge;
            this.scope = scope;
        }
    }

    /**
     * The armed frames of the current thread, innermost last.
     *
     * <p>One thread per server tick is a fact of this game, but a thread-local stack is still the right shape: a
     * nested explosion runs on the same thread and pushes and pops its own frame, and no other thread can ever see
     * this encounter's arming.</p>
     */
    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * How many real block callbacks the most recently disarmed marked burst reached.
     *
     * <p>Recorded because a closed block-interaction branch is otherwise only observable as an absence; the
     * dedicated-server fixture reads it to prove the count is zero for a marked burst while the unmarked control's
     * same burst really reaches its blocks.</p>
     */
    private static volatile int lastBlockCallbacks;

    private BreezeBurst() {}

    /**
     * Arm this burst for exactly one charge, or answer {@code null} when it is not ours.
     *
     * <p>The owner mark is the whole admission test: a charge this encounter never fired has no mark, so nothing
     * here can be armed for it, and the narrowing below never applies to it.</p>
     */
    @javax.annotation.Nullable
    public static Scope arm(BreezeWindCharge charge) {
        if (charge == null || charge.level().isClientSide) return null;
        BreezeWindCharges.Owner owner = BreezeWindCharges.markOf(charge);
        Scope scope;
        if (owner != null) {
            // A marked charge is ALWAYS armed, even when its own source is no longer valid. The native direct-hit
            // callback can forget, discard or unmark the charge on the same stack and still continue into its burst,
            // so returning null here would hand that still-executing owned burst back to the world as a wild one - a
            // real push and a real TRIGGER_BLOCK from a shot this encounter refused to answer for. An invalid owned
            // source therefore gets a deny-all frame for its exact explosion instead: every body is refused, the block
            // interaction stays closed, and the frame is still the one this charge's own call produced.
            ServerBattle battle = BattleManager.findById(owner.sessionId());
            scope = battle == null || battle.finished() ? null : battle.windBurst(charge, owner);
            if (scope == null) scope = denyAll();
        } else {
            // The registry mark is gone while this exact charge is still inside its own native direct-hit call: the
            // encounter's own cleanup really ran there (a locked target that died or left, a firing body that died,
            // an encounter that ended). A discarded projectile cannot unwind that method, so the native call walks on
            // into the burst after the encounter revoked the shot. The origin snapshot taken before the hurt is the
            // only thing that still knows this burst is ours, and what it buys is exactly one thing: this burst is
            // never wild. It is answered deny-all for its own exact explosion, so a revoked shot can neither hurt nor
            // push a body nor touch a block.
            if (BreezeHitOrigins.originOf(charge) == null) return null;
            scope = denyAll();
        }
        FRAMES.get().addLast(new Frame(charge, scope));
        return scope;
    }

    /**
     * The deny-all answer for one marked burst whose own source is no longer valid.
     *
     * <p>It protects every body and captures nothing: an owned burst never becomes a wild one just because the
     * encounter that fired it stopped being answerable. It is still bound to the exact charge and the exact
     * explosion, so it can never narrow anything else.</p>
     */
    private static Scope denyAll() {
        return new Scope() {
            @Override public boolean protects(LivingEntity body) {
                return true;
            }

            @Override public boolean capture(LivingEntity body, Vec3 accepted) {
                return false;
            }

            @Override public void settle() {
            }
        };
    }

    /**
     * Disarm after that same native call: the captured bodies are moved once, by their own encounter.
     *
     * <p>The frame of this exact charge is removed wherever it sits (it is the innermost one whenever the native
     * call is properly nested) and only then settled, so the frames of everything that was nested inside it are
     * already gone and the outer frame - if any - is untouched and keeps its own data.</p>
     */
    public static void disarm(BreezeWindCharge charge) {
        if (charge == null) return;
        ArrayDeque<Frame> frames = FRAMES.get();
        Frame taken = null;
        for (java.util.Iterator<Frame> iterator = frames.descendingIterator(); iterator.hasNext(); ) {
            Frame candidate = iterator.next();
            if (candidate.charge != charge) continue;
            iterator.remove();
            taken = candidate;
            break;
        }
        if (frames.isEmpty()) FRAMES.remove();
        if (taken == null) return;
        lastBlockCallbacks = taken.blockCallbacks;
        try {
            taken.scope.settle();
        } finally {
            taken.bodies.clear();
            taken.deltas.clear();
            taken.movedPlayers.clear();
        }
    }

    /**
     * Bind the frame to the one explosion that really carries its charge as the direct source.
     *
     * <p>Called at the head of the native {@code Explosion.explode} call: from here on, this exact object is the
     * only explosion this frame answers for. An explosion whose direct source is a different charge, or no charge,
     * binds nothing and therefore stays completely native.</p>
     */
    public static void begin(Explosion explosion) {
        if (explosion == null) return;
        Frame frame = current();
        if (frame == null) return;
        if (explosion.getDirectSourceEntity() != frame.charge) return;
        if (frame.explosion == null) frame.explosion = explosion;
    }

    /** True while a marked charge of some encounter is really bursting right now. */
    public static boolean armed() {
        return current() != null;
    }

    /** Whether this exact explosion must leave that body completely alone. */
    public static boolean protects(Explosion explosion, Entity entity) {
        Frame frame = frameOf(explosion);
        if (frame == null || entity == null) return false;
        if (!(entity instanceof LivingEntity body)) return false;
        return frame.scope.protects(body);
    }

    /**
     * Take one displacement the body really accepted, read from the body's own setter result.
     *
     * <p>The caller runs vanilla's write first and then asks here, so the accepted change is always
     * {@code after - before} of the real entity, never the vector vanilla meant to write. A body whose setter does
     * nothing - a shulker's is a no-op - therefore answers an exactly zero accepted change and is never captured,
     * which is what keeps a real immovable body immovable instead of manufacturing a push for it.</p>
     *
     * @return true when the encounter owns this body's movement now, so the caller must write the previous
     *         velocity back and let the encounter's driver perform the one real displacement
     */
    public static boolean take(Explosion explosion, LivingEntity body, Vec3 accepted) {
        Frame frame = frameOf(explosion);
        if (frame == null || body == null) return false;
        if (frame.scope.protects(body)) return true;
        if (accepted == null) return false;
        if (!BreezeRules.burstDisplaces(accepted.x, accepted.y, accepted.z)) return false;
        if (!frame.scope.capture(body, accepted)) return false;
        frame.bodies.add(body);
        frame.deltas.add(accepted);
        if (body instanceof Player player) frame.movedPlayers.add(player.getUUID());
        return true;
    }

    /** The bodies captured so far by the current burst, in encounter order. */
    public static List<LivingEntity> captured() {
        Frame frame = current();
        return frame == null ? List.of() : List.copyOf(frame.bodies);
    }

    /** The accepted delta of the captured body at that index in the current burst. */
    public static Vec3 capturedDelta(int index) {
        Frame frame = current();
        if (frame == null) throw new IllegalStateException("no marked burst is armed");
        return frame.deltas.get(index);
    }

    /** Every player whose own packet vector this burst must consume before the packets are sent. */
    public static Set<UUID> capturedPlayers() {
        Frame frame = current();
        return frame == null ? Set.of() : Set.copyOf(frame.movedPlayers);
    }

    /**
     * Remove the client knockback of every player this encounter already moved, before the packets are sent.
     *
     * <p>Vanilla fills {@code Explosion.hitPlayers} while it writes the velocity and reads that map when it builds
     * the explosion packet. A controlled member's velocity is applied by this ruleset's own driver at a real,
     * proved position, so telling the client to also apply the raw vector would move that body twice. Only the
     * captured members are removed; every other player keeps vanilla's knockback exactly.</p>
     */
    public static void consumePackets(Explosion explosion) {
        Frame frame = frameOf(explosion);
        if (frame == null || frame.movedPlayers.isEmpty()) return;
        Set<UUID> owned = frame.movedPlayers;
        explosion.getHitPlayers().keySet().removeIf(player -> owned.contains(player.getUUID()));
    }

    /**
     * Whether this exact explosion must touch no block at all.
     *
     * <p>Asked at the head of the native {@code finalizeExplosion}, which is the one place the block callbacks and
     * the client packet's block list are built from: emptying {@code toBlow} there closes every
     * {@code BlockState.onExplosionHit} - including a block or mod that never asks
     * {@code canTriggerBlocks} - and leaves the interaction kind and the particle choice exactly as they were.</p>
     */
    public static boolean suppressesBlocks(Explosion explosion) {
        return frameOf(explosion) != null;
    }

    /** Count one real block callback this burst really reached; only ever called for an armed burst. */
    public static void noteBlockCallback(Explosion explosion) {
        Frame frame = frameOf(explosion);
        if (frame != null) frame.blockCallbacks++;
    }

    /** How many real block callbacks the last disarmed marked burst reached. */
    public static int lastBlockCallbacks() {
        return lastBlockCallbacks;
    }

    /** The innermost armed frame of this thread, or {@code null}. */
    @javax.annotation.Nullable
    private static Frame current() {
        ArrayDeque<Frame> frames = FRAMES.get();
        return frames.isEmpty() ? null : frames.peekLast();
    }

    /**
     * The armed frame that owns exactly this explosion, or {@code null}.
     *
     * <p>Both halves are required: the frame must be the innermost one (so an outer burst's frame is never used
     * while an inner burst is running) and it must already be bound to this exact object. A frame that has not been
     * bound yet is bound here on first sight, which is what lets the entity gate of the very same native call ask
     * this question before {@code Explosion.explode} had a chance to run - the charge's own direct source is the
     * only explosion this frame can ever claim.</p>
     */
    @javax.annotation.Nullable
    private static Frame frameOf(Explosion explosion) {
        if (explosion == null) return null;
        Frame frame = current();
        if (frame == null) return null;
        if (explosion.getDirectSourceEntity() != frame.charge) return null;
        if (frame.explosion == null) frame.explosion = explosion;
        return frame.explosion == explosion ? frame : null;
    }
}
