package com.tactical.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;

/**
 * The one place where a queued clientbound payload is allowed to touch client state.
 *
 * <p>NeoForge hands a payload handler the connection it arrived on, but {@code enqueueWork} only queues
 * the task: the main thread may run it after the client has switched worlds or reconnected. The check
 * therefore has to happen <em>inside</em> the queued task, against the live client, and it can never use
 * {@code context.player()}, which resolves to whatever player is global when the task finally runs.</p>
 *
 * <p>Three facts must hold at execution time, all of them identity facts about the moment of arrival:</p>
 * <ol>
 *   <li>the client currently owns a connection (a player is installed),</li>
 *   <li>that listener is still accepting messages,</li>
 *   <li>the listener carries the very same {@link Connection} object the payload arrived on.</li>
 * </ol>
 *
 * <p>A task queued by a connection that has since been replaced fails all three. Failing closed is
 * deliberate: applying a payload to the next connection's state would corrupt it, while dropping one
 * display payload costs at most a missed update.</p>
 *
 * <p>No recovery is promised here. The normal login order suggests the player is installed before the
 * first play payload is handled, but the real first-packet timing is unverified, and there is no
 * acknowledgement or unconditional resend: entry and state changes may produce later snapshots, they
 * are not guaranteed to repair a dropped first one.</p>
 *
 * <p>The identity source is injectable so the decision can be tested without a running client; the
 * production path reads {@link Minecraft#getConnection()}.</p>
 */
public final class ClientPayloadGate {

    /** Where the live connection identity comes from. Tests replace this with a fake. */
    public interface Connections {
        /** The listener the client owns right now, or {@code null} before a player is installed. */
        Object current();

        /** The low-level connection that listener carries; compared by identity with the arrival. */
        Object connectionOf(Object listener);

        /** True while that listener still accepts messages. */
        boolean accepting(Object listener);
    }

    private static final Connections LIVE = new Connections() {
        @Override
        public Object current() {
            return Minecraft.getInstance().getConnection();
        }

        @Override
        public Object connectionOf(Object listener) {
            return listener instanceof ClientPacketListener connection ? connection.getConnection() : null;
        }

        @Override
        public boolean accepting(Object listener) {
            return listener instanceof ClientPacketListener connection && connection.isAcceptingMessages();
        }
    };

    private static volatile Connections connections = LIVE;

    private ClientPayloadGate() { }

    /** Install a fake identity source (tests). {@code null} restores the live client. */
    public static void install(Connections replacement) {
        connections = replacement == null ? LIVE : replacement;
    }

    public static void reset() {
        connections = LIVE;
    }

    /**
     * Run one payload application if, and only if, it still belongs to the live connection.
     *
     * @param arrived the connection captured by the handler before it queued the task
     * @param body    the client-side application point
     * @return true when the body ran
     */
    public static boolean apply(Object arrived, Runnable body) {
        if (body == null) return false;
        Connections source = connections;
        Object current = source.current();
        if (current == null) return false;
        if (arrived == null || source.connectionOf(current) != arrived) return false;
        if (!source.accepting(current)) return false;
        body.run();
        return true;
    }
}
