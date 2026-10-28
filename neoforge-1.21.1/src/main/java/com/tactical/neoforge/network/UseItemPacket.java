package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client asks the server to use one inventory item. */
public record UseItemPacket(ActionStamp stamp, int slot) implements CustomPacketPayload {

    public static final Type<UseItemPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "use_item"));

    public static final StreamCodec<FriendlyByteBuf, UseItemPacket> STREAM_CODEC =
            StreamCodec.of(UseItemPacket::encode, UseItemPacket::decode);

    private static void encode(FriendlyByteBuf buffer, UseItemPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.slot);
    }

    private static UseItemPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new UseItemPacket(stamp, buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
