package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * "Let me join that encounter" from the nearby list (direct opt-in).
 *
 * <p>Only the target session id is carried. Identity, dimension, distance, capacity and eligibility
 * are re-checked on the server from the real connection, so a forged payload can at most ask to join
 * an encounter it would be refused anyway.</p>
 */
public record JoinBattleIntentPacket(UUID sessionId) implements CustomPacketPayload {
    public static final Type<JoinBattleIntentPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "join_battle_intent"));
    public static final StreamCodec<FriendlyByteBuf, JoinBattleIntentPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, packet) -> buffer.writeUUID(packet.sessionId()),
                    buffer -> new JoinBattleIntentPacket(buffer.readUUID()));

    public JoinBattleIntentPacket {
        Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
