package com.tactical.neoforge.network;

import com.tactical.core.progression.CharacterStat;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * "Send me my character page." The server answers the connection's own player; a payload can never name
 * anyone else, and it carries no trusted number.
 *
 * <p>{@code openScreen} only states the client's intent (the bound key or the command was used). It travels
 * back in the answer so the client can tell an answer to an open request from an ordinary refresh.</p>
 */
public record CharacterRequestPacket(int requestId, boolean openScreen) implements CustomPacketPayload {

    public static final Type<CharacterRequestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "character_request"));
    public static final StreamCodec<FriendlyByteBuf, CharacterRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.requestId);
                buffer.writeBoolean(packet.openScreen);
            },
            buffer -> new CharacterRequestPacket(buffer.readVarInt(), buffer.readBoolean()));

    public CharacterRequestPacket {
        if (requestId < 0) throw new IllegalArgumentException("Invalid character request id");
    }

    /** Convenience for the ordinary "open the page" intent. */
    public static CharacterRequestPacket open(int requestId) { return new CharacterRequestPacket(requestId, true); }

    /** Convenience for a plain refresh that must not try to open anything. */
    public static CharacterRequestPacket refresh(int requestId) { return new CharacterRequestPacket(requestId, false); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
