package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client asks the server to break one block with the main hand.
 *
 * <p>It is its own intent, never a flag on the placement or the item payload: one payload keeps one
 * meaning, so an audited action cannot be re-read as a different one. The body is the smallest one
 * that lets the server re-derive everything itself - a session/turn stamp, the hotbar slot the
 * client claims to hold, and the block it points at. No actor, no reach, no tool, no price: those
 * are resolved from the real {@code ServerPlayer} on the server side.</p>
 */
public record BreakBlockPacket(ActionStamp stamp, int slot, BlockPos pos) implements CustomPacketPayload {

    /** The hotbar slots a player can really hold; anything else is malformed rather than merely wrong. */
    private static final int HOTBAR_SLOTS = 9;

    public static final Type<BreakBlockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "break_block"));

    public static final StreamCodec<FriendlyByteBuf, BreakBlockPacket> STREAM_CODEC =
            StreamCodec.of(BreakBlockPacket::encode, BreakBlockPacket::decode);

    public BreakBlockPacket {
        if (pos == null) throw new IllegalArgumentException("Missing break target");
    }

    private static void encode(FriendlyByteBuf buffer, BreakBlockPacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing break buffer");
        if (packet == null || packet.stamp() == null) throw new IllegalArgumentException("Missing break intent");
        StampIO.write(buffer, packet.stamp());
        buffer.writeVarInt(packet.slot());
        buffer.writeBlockPos(packet.pos());
    }

    private static BreakBlockPacket decode(FriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing break buffer");
        ActionStamp stamp = StampIO.read(buffer);
        int slot = buffer.readVarInt();
        if (slot < -1 || slot >= HOTBAR_SLOTS) throw new IllegalArgumentException("Invalid break slot");
        return new BreakBlockPacket(stamp, slot, buffer.readBlockPos());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
