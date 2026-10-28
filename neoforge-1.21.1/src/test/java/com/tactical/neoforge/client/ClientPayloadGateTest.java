package com.tactical.neoforge.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queued-payload connection gate.
 *
 * <p>NeoForge binds a payload's {@code IPayloadContext.connection()} to the listener that received it,
 * but the main-thread task it queues is not re-checked, so a task can run after the client switched
 * worlds or reconnected. These tests drive that order explicitly: queue first, switch the connection,
 * then run the queued task. The identity source is a fake, so nothing here needs a running client.</p>
 */
class ClientPayloadGateTest {

    /** One fake client connection: an identity object plus its listener state. */
    private static final class FakeConnection {
        final String name;
        /** The low-level connection this listener carries; the gate compares it by identity. */
        FakeConnection listenerConnections;
        boolean accepting = true;

        FakeConnection(String name) {
            this.name = name;
        }
    }

    /** The fake identity source; {@code current} is swapped to simulate a reconnect. */
    private static final class FakeClient implements ClientPayloadGate.Connections {
        FakeConnection current;

        @Override
        public Object current() {
            return current;
        }

        @Override
        public Object connectionOf(Object listener) {
            return ((FakeConnection) listener).listenerConnections;
        }

        @Override
        public boolean accepting(Object listener) {
            return ((FakeConnection) listener).accepting;
        }
    }

    private final FakeClient client = new FakeClient();

    @AfterEach
    void restore() {
        ClientPayloadGate.reset();
    }

    private FakeConnection connection(String name) {
        FakeConnection connection = new FakeConnection(name);
        connection.listenerConnections = connection;
        return connection;
    }

    @Test
    void aTaskQueuedByTheOldConnectionIsRejectedWhenItRunsAfterTheSwitch() {
        FakeConnection oldConnection = connection("old");
        FakeConnection newConnection = connection("new");
        client.current = oldConnection;
        ClientPayloadGate.install(client);

        List<String> applied = new ArrayList<>();
        Runnable queuedOnOld = () -> ClientPayloadGate.apply(oldConnection, () -> applied.add("old"));

        // the client reconnects before the old task reaches the main thread
        client.current = newConnection;
        queuedOnOld.run();
        assertTrue(applied.isEmpty(), "a task of the replaced connection must not touch client state");

        // the new connection's own payload is accepted, including when it runs after the old one
        assertTrue(ClientPayloadGate.apply(newConnection, () -> applied.add("new")));
        assertEquals(List.of("new"), applied);
    }

    @Test
    void aReconnectOnTheSameServerAcceptsTheFirstPacketOfTheNewConnection() {
        FakeConnection before = connection("epoch-7");
        FakeConnection after = connection("epoch-7");
        client.current = before;
        ClientPayloadGate.install(client);
        assertTrue(ClientPayloadGate.apply(before, () -> { }));

        client.current = after; // same server, same epoch, brand new connection object
        List<String> applied = new ArrayList<>();
        assertTrue(ClientPayloadGate.apply(after, () -> applied.add("first")));
        assertEquals(List.of("first"), applied);
        assertFalse(ClientPayloadGate.apply(before, () -> applied.add("stale")), "the previous object is gone");
    }

    @Test
    void theSameLiveConnectionKeepsApplyingOnRespawn() {
        FakeConnection live = connection("live");
        client.current = live;
        ClientPayloadGate.install(client);

        List<String> applied = new ArrayList<>();
        assertTrue(ClientPayloadGate.apply(live, () -> applied.add("state")));
        assertTrue(ClientPayloadGate.apply(live, () -> applied.add("log")));
        assertEquals(List.of("state", "log"), applied);
    }

    @Test
    void noCurrentConnectionFailsClosed() {
        FakeConnection arrived = connection("old");
        client.current = null;
        ClientPayloadGate.install(client);

        List<String> applied = new ArrayList<>();
        assertFalse(ClientPayloadGate.apply(arrived, () -> applied.add("dropped")));
        assertTrue(applied.isEmpty(), "before a player is installed the payload is dropped, not applied");
    }

    @Test
    void aRetiringListenerStopsApplyingEvenOnTheSameConnectionObject() {
        FakeConnection live = connection("live");
        client.current = live;
        ClientPayloadGate.install(client);

        live.accepting = false;
        List<String> applied = new ArrayList<>();
        assertFalse(ClientPayloadGate.apply(live, () -> applied.add("late")));
        assertTrue(applied.isEmpty());
    }

    @Test
    void aNullArrivalIsNeverApplied() {
        FakeConnection live = connection("live");
        client.current = live;
        ClientPayloadGate.install(client);

        List<String> applied = new ArrayList<>();
        assertFalse(ClientPayloadGate.apply(null, () -> applied.add("anonymous")));
        assertTrue(applied.isEmpty());
    }
}
