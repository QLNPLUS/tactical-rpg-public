package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Drop the stack in one slot, or swap it with the main/off hand. */
public record InventoryActionPacket(ActionStamp stamp, int slot, int action) implements CustomPacketPayload {

    public static final int DROP = 0;
    public static final int SWAP_MAIN = 1;
    public static final int SWAP_OFF = 2;

    public static final Type<InventoryActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "inventory_action"));

    public static final StreamCodec<FriendlyByteBuf, InventoryActionPacket> STREAM_CODEC =
            StreamCodec.of(InventoryActionPacket::encode, InventoryActionPacket::decode);

    private static void encode(FriendlyByteBuf buffer, InventoryActionPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.slot);
        buffer.writeVarInt(packet.action);
    }

    private static InventoryActionPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new InventoryActionPacket(stamp, buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
