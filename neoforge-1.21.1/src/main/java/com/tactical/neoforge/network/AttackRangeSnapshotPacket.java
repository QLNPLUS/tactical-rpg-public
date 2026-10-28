package com.tactical.neoforge.network;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.AttackRangeGeometry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Geometric terrain reach and independently validated participant targets, never interchangeable. */
public record AttackRangeSnapshotPacket(int requestId, boolean active, UUID battleId, ResourceLocation dimension,
                                       long serverTick, GridPos origin, int weaponKind, boolean truncated,
                                       List<Tile> tiles, List<Target> targets,
                                       long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {

    /** Unstamped snapshot: the network layer stamps it for the recipient. */
    public AttackRangeSnapshotPacket(int requestId, boolean active, UUID battleId, ResourceLocation dimension,
                                     long serverTick, GridPos origin, int weaponKind, boolean truncated,
                                     List<Tile> tiles, List<Target> targets) {
        this(requestId, active, battleId, dimension, serverTick, origin, weaponKind, truncated, tiles, targets,
                0L, 0L);
    }

    @Override
    public UUID membershipSession() {
        return battleId;
    }

    @Override
    public AttackRangeSnapshotPacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new AttackRangeSnapshotPacket(requestId, active, battleId, dimension, serverTick, origin,
                weaponKind, truncated, tiles, targets, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    public static final int MAX_TARGETS = 4096;
    public static final Type<AttackRangeSnapshotPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "attack_range_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, AttackRangeSnapshotPacket> STREAM_CODEC =
            StreamCodec.of(AttackRangeSnapshotPacket::encode, AttackRangeSnapshotPacket::decode);

    /** A potential standing cell and its native supporting collision-shape top. */
    public record Tile(GridPos cell, double surfaceY) {
        public Tile {
            Objects.requireNonNull(cell);
            if (!Double.isFinite(surfaceY) || surfaceY < cell.y() - 1D || surfaceY > cell.y() + 1D)
                throw new IllegalArgumentException("Invalid attack tile surface");
        }
    }

    public record Target(int entityId, UUID uuid) {
        public Target { Objects.requireNonNull(uuid); }
    }

    public AttackRangeSnapshotPacket {
        Objects.requireNonNull(battleId);
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(origin);
        tiles = List.copyOf(tiles);
        targets = List.copyOf(targets);
        if (weaponKind < 0 || weaponKind > 2 || tiles.size() > AttackRangeGeometry.MAX_TILES
                || targets.size() > MAX_TARGETS || (!active && (!tiles.isEmpty() || !targets.isEmpty())))
            throw new IllegalArgumentException("Invalid attack preview snapshot");
        for (Tile tile : tiles) {
            GridPos cell = tile.cell();
            if (Math.abs((long) cell.x() - origin.x()) > AttackRangeGeometry.MAX_RADIUS
                    || Math.abs((long) cell.z() - origin.z()) > AttackRangeGeometry.MAX_RADIUS
                    || Math.abs((long) cell.y() - origin.y()) > AttackRangeGeometry.MAX_ELEVATION)
                throw new IllegalArgumentException("Attack preview tile exceeds bounds");
        }
    }

    public AttackRangeSnapshotPacket forRequest(int id) {
        return new AttackRangeSnapshotPacket(id, active, battleId, dimension, serverTick, origin, weaponKind,
                truncated, tiles, targets, membershipServerEpoch, membershipGeneration);
    }

    private static void encode(FriendlyByteBuf buffer, AttackRangeSnapshotPacket packet) {
        buffer.writeVarInt(packet.requestId());
        buffer.writeBoolean(packet.active());
        buffer.writeUUID(packet.battleId());
        buffer.writeResourceLocation(packet.dimension());
        buffer.writeLong(packet.serverTick());
        writeCell(buffer, packet.origin());
        buffer.writeVarInt(packet.weaponKind());
        buffer.writeBoolean(packet.truncated());
        buffer.writeVarInt(packet.tiles().size());
        for (Tile tile : packet.tiles()) {
            writeCell(buffer, tile.cell());
            buffer.writeDouble(tile.surfaceY());
        }
        buffer.writeVarInt(packet.targets().size());
        for (Target target : packet.targets()) {
            buffer.writeVarInt(target.entityId());
            buffer.writeUUID(target.uuid());
        }
        buffer.writeVarLong(packet.membershipServerEpoch);
        buffer.writeVarLong(packet.membershipGeneration);
    }

    private static AttackRangeSnapshotPacket decode(FriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        boolean active = buffer.readBoolean();
        UUID battle = buffer.readUUID();
        ResourceLocation dimension = buffer.readResourceLocation();
        long tick = buffer.readLong();
        GridPos origin = readCell(buffer);
        int kind = buffer.readVarInt();
        boolean truncated = buffer.readBoolean();
        int size = boundedSize(buffer, AttackRangeGeometry.MAX_TILES);
        List<Tile> tiles = new ArrayList<>(size);
        for (int i = 0; i < size; i++) tiles.add(new Tile(readCell(buffer), buffer.readDouble()));
        int count = boundedSize(buffer, MAX_TARGETS);
        List<Target> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) targets.add(new Target(buffer.readVarInt(), buffer.readUUID()));
        long epoch = buffer.readVarLong();
        long generation = buffer.readVarLong();
        return new AttackRangeSnapshotPacket(id, active, battle, dimension, tick, origin, kind, truncated, tiles,
                targets, epoch, generation);
    }

    private static int boundedSize(FriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Attack preview collection exceeds limit");
        return size;
    }

    private static void writeCell(FriendlyByteBuf buffer, GridPos cell) {
        buffer.writeVarInt(cell.x());
        buffer.writeVarInt(cell.y());
        buffer.writeVarInt(cell.z());
    }

    private static GridPos readCell(FriendlyByteBuf buffer) {
        return new GridPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
