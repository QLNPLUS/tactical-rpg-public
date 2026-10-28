package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Client asks the server to place one block from an inventory slot. */
public record PlaceBlockPacket(ActionStamp stamp, int slot, BlockPos pos, Direction face, Vec3 hit) implements CustomPacketPayload {

    public static final Type<PlaceBlockPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "place_block"));

    public static final StreamCodec<FriendlyByteBuf, PlaceBlockPacket> STREAM_CODEC =
            StreamCodec.of(PlaceBlockPacket::encode, PlaceBlockPacket::decode);

    private static void encode(FriendlyByteBuf buffer, PlaceBlockPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.slot);
        buffer.writeBlockPos(packet.pos);
        buffer.writeEnum(packet.face);
        buffer.writeDouble(packet.hit.x);
        buffer.writeDouble(packet.hit.y);
        buffer.writeDouble(packet.hit.z);
    }

    private static PlaceBlockPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new PlaceBlockPacket(stamp, buffer.readVarInt(), buffer.readBlockPos(),
                buffer.readEnum(Direction.class), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
