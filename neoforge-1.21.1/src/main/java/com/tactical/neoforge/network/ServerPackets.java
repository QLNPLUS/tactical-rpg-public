package com.tactical.neoforge.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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

    private ServerPackets() { }

    /** True when this recipient has a live transport that can carry a payload. */
    public static boolean canReceive(ServerPlayer recipient) {
        if (recipient == null || recipient.connection == null) return false;
        var connection = recipient.connection.getConnection();
        return connection != null && connection.channel() != null;
    }

    /** Transport plus negotiated channel support; never used to authorize an intent. */
    public static boolean supports(ServerPlayer recipient, CustomPacketPayload.Type<?> type) {
        return canReceive(recipient) && type != null && recipient.connection.hasChannel(type);
    }

    /** Best-effort push: silently skipped for a connection that cannot carry packets. */
    public static boolean send(ServerPlayer recipient, CustomPacketPayload packet) {
        CustomPacketPayload stamped = stamp(recipient, packet);
        if (stamped == null || !supports(recipient, stamped.type())) return false;
        PacketDistributor.sendToPlayer(recipient, stamped);
        return true;
    }

    /** Required push: same rules, but the caller wants to know whether it was delivered. */
    public static void sendUncheckedType(ServerPlayer recipient, CustomPacketPayload packet) {
        CustomPacketPayload stamped = stamp(recipient, packet);
        if (stamped == null) return;
        // Capture before the transport check: a fixture needs to see what the server *attempted* to
        // publish to each recipient, which is exactly what a rollback has to correct.
        capture(recipient, stamped);
        if (!canReceive(recipient)) return;
        PacketDistributor.sendToPlayer(recipient, stamped);
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
