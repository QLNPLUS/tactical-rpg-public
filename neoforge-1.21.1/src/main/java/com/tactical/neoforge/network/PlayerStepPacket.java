package com.tactical.neoforge.network;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** One complete authoritative path; an empty path cancels local interpolation. */
public record PlayerStepPacket(GridPos start, List<GridPos> path, int stepTicks, float yaw, boolean drop) implements CustomPacketPayload {
    public PlayerStepPacket(GridPos start,List<GridPos> path,int stepTicks,float yaw) { this(start,path,stepTicks,yaw,false); }
    public static final Type<PlayerStepPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "player_step"));
    public static final StreamCodec<FriendlyByteBuf, PlayerStepPacket> STREAM_CODEC =
            StreamCodec.of(PlayerStepPacket::encode, PlayerStepPacket::decode);

    public PlayerStepPacket {
        path = List.copyOf(path);
        if (path.size() > 2048 || stepTicks < 1 || stepTicks > 200) {
            throw new IllegalArgumentException("Invalid battle trajectory");
        }
    }

    private static void encode(FriendlyByteBuf buffer, PlayerStepPacket packet) {
        writeCell(buffer, packet.start);
        buffer.writeVarInt(packet.stepTicks);
        buffer.writeFloat(packet.yaw);
        buffer.writeBoolean(packet.drop);
        buffer.writeVarInt(packet.path.size());
        for (GridPos cell : packet.path) writeCell(buffer, cell);
    }

    private static PlayerStepPacket decode(FriendlyByteBuf buffer) {
        GridPos start = readCell(buffer);
        int stepTicks = buffer.readVarInt();
        float yaw = buffer.readFloat();
        boolean drop = buffer.readBoolean();
        int size = buffer.readVarInt();
        if (size < 0 || size > 2048) throw new IllegalArgumentException("Invalid trajectory length");
        List<GridPos> path = new ArrayList<>(size);
        for (int i = 0; i < size; i++) path.add(readCell(buffer));
        return new PlayerStepPacket(start, path, stepTicks, yaw, drop);
    }

    private static void writeCell(FriendlyByteBuf buffer, GridPos cell) {
        buffer.writeInt(cell.x()); buffer.writeInt(cell.y()); buffer.writeInt(cell.z());
    }

    private static GridPos readCell(FriendlyByteBuf buffer) {
        return new GridPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
