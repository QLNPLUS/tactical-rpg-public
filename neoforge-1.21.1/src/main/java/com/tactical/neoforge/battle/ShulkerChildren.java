package com.tactical.neoforge.battle;

import net.minecraft.world.entity.monster.Shulker;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one place a native-born shulker child is noticed, and the scope it is noticed in.
 *
 * <p>Vanilla's replication lives inside {@code Shulker.hitByShulkerBullet}: when a same-family bullet really hit
 * an open shell, vanilla relocates the parent and then, by its own {@code level.random} roll, creates a child at
 * the position the parent left, copies its colour and calls {@code level.addFreshEntity}. That code path is
 * worth keeping exactly as it is - it is the only place that knows the native roll and the native origin - so
 * this class creates, re-rolls, moves and deletes nothing.</p>
 *
 * <p>Two earlier mistakes are repaired here:</p>
 * <ul>
 *   <li>a level-join event fires <em>before</em> the entity is really registered and may still be cancelled, so
 *       a report made from a join could name a child that never existed. The observation point is now the
 *       {@code addFreshEntity} call itself, read after it returned {@code true} - the world's own receipt;</li>
 *   <li>the window used to be opened only around an own-turn relocation, which a native reaction never runs.
 *       The window is now held for the whole synchronous scope of the native hit ({@code hurt} entry to
 *       {@code hurt} return), so a reaction born child is observed exactly like an own-turn one.</li>
 * </ul>
 *
 * <p>No window, no report: a wild shulker replicating, or a child another mod added by other means, is never
 * touched.</p>
 */
public final class ShulkerChildren {

    private static final ThreadLocal<Deque<Window>> OPEN = new ThreadLocal<>();

    private record Window(Shulker parent, ShulkerHandler handler) {}

    private ShulkerChildren() {}

    /** Open one native-hit window for that exact parent; always paired with {@link #close(Shulker)}. */
    public static void open(Shulker parent, ShulkerHandler handler) {
        if (parent == null || handler == null) return;
        Deque<Window> stack = OPEN.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            OPEN.set(stack);
        }
        stack.push(new Window(parent, handler));
    }

    /** Close the window of that parent; safe to call when none was opened. */
    public static void close(Shulker parent) {
        Deque<Window> stack = OPEN.get();
        if (stack == null) return;
        if (parent == null) stack.clear();
        else stack.removeIf(window -> window.parent == parent);
        if (stack.isEmpty()) OPEN.remove();
    }

    /** Close every window on this thread: encounter teardown only. */
    public static void closeAll() {
        OPEN.remove();
    }

    /**
     * Report a child vanilla itself really registered, inside an authorized native hit of that exact parent.
     *
     * <p>Called from the mixin right after the native {@code addFreshEntity} answered {@code true}: the identity
     * check is the parent this window belongs to, so a child some unrelated path created during the same call is
     * never adopted.</p>
     *
     * @return true when this report was consumed by a window of that parent
     */
    public static boolean report(Shulker parent, Shulker child) {
        if (parent == null || child == null || parent == child) return false;
        Deque<Window> stack = OPEN.get();
        if (stack == null || stack.isEmpty()) return false;
        for (Window window : stack) {
            if (window.parent != parent) continue;
            window.handler.tactical$onNativeChild(parent, child);
            return true;
        }
        return false;
    }
}
