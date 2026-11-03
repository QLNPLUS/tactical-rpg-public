package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Delivery guard for every server-to-client push owned by the battle system.
 *
 * <p>This is a <em>transport</em> check, not an authorization check: identity, membership
 * and action legality are decided by {@code ServerBattle.authorize} from the real
 * connection identity plus the session/turn stamp. Here we only ask whether a live netty
 * channel exists at all, because headless server fixtures (a fake player) have a
 * connection object whose channel is null; calling NeoForge's channel negotiation on it
 * throws. A real client always has a channel, so capability negotiation is unchanged.</p>
 */
public final class ServerPackets {

    /**
     * Ordering of whole snapshots as published to one client.
     *
     * <p>It only ever grows. A client keeps the highest one it has applied and refuses parts of anything
     * older, so a slice that arrives late can never be reassembled from a stale publication.</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong PUBLICATIONS =
            new java.util.concurrent.atomic.AtomicLong();

    private ServerPackets() { }

    /** True when this recipient has a live transport that can carry a payload. */
    public static boolean canReceive(ServerPlayer recipient) {
        if (recipient == null || recipient.connection == null) return false;
        var connection = recipient.connection.getConnection();
        return connection != null && connection.channel() != null;
    }

    /**
     * Transport plus negotiated channel support; never used to authorize an intent.
     *
     * <p>A logical battle snapshot has no payload of its own on the wire - it travels as parts - so asking
     * about its type asks about the physical one. Without that mapping a caller would get "supported"
     * for a payload that can never be sent.</p>
     */
    public static boolean supports(ServerPlayer recipient, CustomPacketPayload.Type<?> type) {
        CustomPacketPayload.Type<?> physical = type == BattleStatePacket.TYPE ? BattleStateFragmentPacket.TYPE : type;
        return canReceive(recipient) && physical != null && recipient.connection.hasChannel(physical);
    }

    /** Best-effort push: false when the recipient's channel cannot carry it. */
    public static boolean send(ServerPlayer recipient, CustomPacketPayload packet) {
        if (packet instanceof BattleStatePacket snapshot) return sendBattleState(recipient, snapshot);
        CustomPacketPayload stamped = stamp(recipient, packet);
        if (stamped == null || !supports(recipient, stamped.type())) return false;
        PacketDistributor.sendToPlayer(recipient, stamped);
        return true;
    }

    /** Required push: same rules, but the caller wants to know whether it was delivered. */
    public static void sendUncheckedType(ServerPlayer recipient, CustomPacketPayload packet) {
        sendUnchecked(recipient, packet);
    }

    /**
     * The same push with an answer: true only when the payload (or every part of a snapshot) really
     * reached the connection. A snapshot is cut here, because one payload cannot carry it.
     *
     * <p>The logical snapshot is still what a fixture observes: capture happens before the cut, so the
     * recorded thing stays the state that was attempted, not its transport form.</p>
     */
    public static boolean sendUnchecked(ServerPlayer recipient, CustomPacketPayload packet) {
        if (packet instanceof BattleStatePacket snapshot) return sendBattleState(recipient, snapshot);
        CustomPacketPayload stamped = stamp(recipient, packet);
        if (stamped == null) return false;
        // Capture before the transport check: a fixture needs to see what the server *attempted* to
        // publish to each recipient, which is exactly what a rollback has to correct.
        capture(recipient, stamped);
        if (!canReceive(recipient)) return false;
        PacketDistributor.sendToPlayer(recipient, stamped);
        return true;
    }

    /**
     * Publish one logical battle snapshot as bounded parts.
     *
     * <p>This is the only way a battle state reaches a client: both send APIs land here, so there is one
     * size guarantee, one ordering guarantee and one stamp per snapshot. The snapshot is stamped once,
     * encoded once, and then cut; every part carries that same stamp and the same enter/leave flag,
     * because they are copied from the stamped snapshot instead of being re-derived per part.</p>
     *
     * <p>Nothing here can fail the encounter: an unpublished snapshot costs one update, which the next
     * action repairs. The recipient boundary is unchanged - {@link #canReceive} plus channel negotiation,
     * asked about the part's physical type - so a client without this protocol never receives a part and
     * cannot half-apply a snapshot either.</p>
     *
     * @return true only when every part was handed to the connection
     */
    private static boolean sendBattleState(ServerPlayer recipient, BattleStatePacket snapshot) {
        if (recipient == null) return false;
        CustomPacketPayload stampedPayload = stamp(recipient, snapshot);
        if (!(stampedPayload instanceof BattleStatePacket stamped)) return false;
        capture(recipient, stamped);
        if (!canReceive(recipient)) return false;
        if (!supports(recipient, BattleStateFragmentPacket.TYPE)) return false;
        net.minecraft.core.RegistryAccess registries = registries(recipient);
        if (registries == null) {
            Tactical.LOGGER.warn("[Tactical] battle state was not published: no registry context");
            return false;
        }
        byte[] wire;
        try {
            // Both ends use the neutral registry context: the body is a self-contained byte array, so the
            // encode and the decode must agree with each other, not with a connection's own transport mode.
            wire = BattleStatePacket.wireBytes(registries, ConnectionType.OTHER, stamped);
        } catch (RuntimeException refused) {
            // A name that cannot travel or a body over budget: never the encounter's problem.
            Tactical.LOGGER.warn("[Tactical] battle state was not published: {}", refused.toString());
            return false;
        }
        long publication = PUBLICATIONS.incrementAndGet();
        int parts = BattleStateFragmentPacket.partsFor(wire.length);
        for (int index = 0; index < parts; index++) {
            int offset = BattleStateFragmentPacket.offsetOf(index);
            int length = BattleStateFragmentPacket.bytesOf(wire.length, index);
            byte[] slice = java.util.Arrays.copyOfRange(wire, offset, offset + length);
            BattleStateFragmentPacket part = new BattleStateFragmentPacket(publication, wire.length, parts,
                    index, stamped.active(), slice, stamped.sessionId(), stamped.membershipServerEpoch(),
                    stamped.membershipGeneration());
            PacketDistributor.sendToPlayer(recipient, part);
        }
        return true;
    }

    /** The server's own registry context; a snapshot names entities and items, so it needs one. */
    private static net.minecraft.core.RegistryAccess registries(ServerPlayer recipient) {
        if (recipient != null && recipient.server != null) return recipient.server.registryAccess();
        if (recipient != null && recipient.level() != null) return recipient.level().registryAccess();
        return null;
    }

    /**
     * Bind a display payload to the recipient's membership <em>as it is at this moment</em>.
     *
     * <p>Stamping lives in this single choke point so that no call site has to remember it. The
     * constraint this relies on is a property of the call sites, not of the API: a payload is built and
     * published synchronously on the server thread, every display cache is invalidated when a player's
     * generation changes ({@code AttackRangeService.forget}), and the client requires the encounter id
     * inside the payload to equal its current active membership. A future caller that parked a payload
     * across a membership transition and published it later would be stamped with the newer generation;
     * the encounter check still refuses a payload of another encounter, but a re-entry into the
     * <em>same</em> encounter would not be caught. Keep display payloads short-lived.</p>
     */
    private static CustomPacketPayload stamp(ServerPlayer recipient, CustomPacketPayload packet) {
        if (!(packet instanceof MemberScoped scoped)) return packet;
        if (recipient == null) return packet;
        com.tactical.neoforge.battle.multi.MembershipGenerations.Membership membership =
                com.tactical.neoforge.battle.multi.MembershipGenerations.current(recipient);
        return (CustomPacketPayload) scoped.withMembership(
                com.tactical.neoforge.battle.multi.MembershipGenerations.epoch(), membership.generation());
    }

    // ---- fixture-only publication capture ------------------------------------------------

    private static final java.util.Map<UUID, java.util.List<CustomPacketPayload>> CAPTURED =
            new java.util.LinkedHashMap<>();
    private static boolean capturing;

    /** Fixture-only: record every attempted push per recipient, in order. Never used in production. */
    public static void startCapture() {
        capturing = true;
        CAPTURED.clear();
    }

    public static void stopCapture() {
        capturing = false;
        CAPTURED.clear();
    }

    /** Fixture-only snapshot of what was attempted for one recipient, oldest first. */
    public static java.util.List<CustomPacketPayload> capturedFor(UUID playerId) {
        if (playerId == null) return java.util.List.of();
        return java.util.List.copyOf(CAPTURED.getOrDefault(playerId, java.util.List.of()));
    }

    /** True while a fixture wants to observe attempted publications. */
    public static boolean isCapturing() {
        return capturing;
    }

    /**
     * Fixture-only: record a push that this caller delivers itself, so a rollback can be judged by
     * what the client was actually told.
     */
    public static void captureAttempt(ServerPlayer recipient, CustomPacketPayload packet) {
        capture(recipient, packet);
    }

    private static void capture(ServerPlayer recipient, CustomPacketPayload packet) {
        if (!capturing || recipient == null) return;
        CAPTURED.computeIfAbsent(recipient.getUUID(), key -> new java.util.ArrayList<>()).add(packet);
    }
}
