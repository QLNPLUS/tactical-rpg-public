package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full replacement snapshot: every controlled creeper, including every prepared one. */
public record CreeperTelegraphPacket(boolean active, UUID battleId, ResourceLocation dimension,
                                    List<CreeperWarning> creepers,
                                    long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {

    /** Unstamped snapshot: the network layer stamps it for the recipient. */
    public CreeperTelegraphPacket(boolean active, UUID battleId, ResourceLocation dimension,
                                  List<CreeperWarning> creepers) {
        this(active, battleId, dimension, creepers, 0L, 0L);
    }

    @Override
    public UUID membershipSession() {
        return battleId;
    }

    @Override
    public CreeperTelegraphPacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new CreeperTelegraphPacket(active, battleId, dimension, creepers, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    public static final int MAX_CREEPERS = 4096;
    public static final Type<CreeperTelegraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "creeper_telegraph"));
    public static final StreamCodec<FriendlyByteBuf, CreeperTelegraphPacket> STREAM_CODEC =
            StreamCodec.of(CreeperTelegraphPacket::encode, CreeperTelegraphPacket::decode);

    /** Radius is the conservative 2*vanilla explosion strength, already accounting for powered. */
    public record CreeperWarning(int entityId, UUID uuid, boolean preparing,
                                 double x, double y, double z, double radius) {
        public CreeperWarning {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Double.isFinite(radius) || radius < 0 || radius > 1024) {
                throw new IllegalArgumentException("Invalid creeper warning geometry");
            }
        }
    }

    public CreeperTelegraphPacket {
        creepers = List.copyOf(creepers);
        if (creepers.size() > MAX_CREEPERS || (!active && !creepers.isEmpty())) {
            throw new IllegalArgumentException("Invalid creeper snapshot size");
        }
    }

    public static CreeperTelegraphPacket empty(UUID battleId, ResourceLocation dimension) {
        return new CreeperTelegraphPacket(false, battleId, dimension, List.of());
    }

    private static void encode(FriendlyByteBuf buffer, CreeperTelegraphPacket packet) {
        buffer.writeBoolean(packet.active);
        buffer.writeUUID(packet.battleId);
        buffer.writeResourceLocation(packet.dimension);
        buffer.writeVarInt(packet.creepers.size());
        for (CreeperWarning creeper : packet.creepers) {
            buffer.writeVarInt(creeper.entityId);
            buffer.writeUUID(creeper.uuid);
            buffer.writeBoolean(creeper.preparing);
            buffer.writeDouble(creeper.x);
            buffer.writeDouble(creeper.y);
            buffer.writeDouble(creeper.z);
            buffer.writeDouble(creeper.radius);
        }
        buffer.writeVarLong(packet.membershipServerEpoch);
        buffer.writeVarLong(packet.membershipGeneration);
    }

    private static CreeperTelegraphPacket decode(FriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        UUID battle = buffer.readUUID();
        ResourceLocation dimension = buffer.readResourceLocation();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_CREEPERS) throw new IllegalArgumentException("Too many creeper warnings");
        List<CreeperWarning> warnings = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            warnings.add(new CreeperWarning(buffer.readVarInt(), buffer.readUUID(), buffer.readBoolean(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
        }
        long epoch = buffer.readVarLong();
        long generation = buffer.readVarLong();
        return new CreeperTelegraphPacket(active, battle, dimension, warnings, epoch, generation);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
