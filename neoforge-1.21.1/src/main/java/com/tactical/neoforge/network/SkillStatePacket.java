package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillDefinition;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SkillStatePacket(UUID session, ResourceLocation dimension, boolean active, long revision,
                               double mp, double maximum, double recovery, List<SkillView> skills,
                               List<Integer> friendlyIds, long membershipServerEpoch, long membershipGeneration)
        implements CustomPacketPayload, MemberScoped {

    /** Unstamped generation: the network layer stamps each recipient's own membership. */
    public SkillStatePacket(UUID session, ResourceLocation dimension, boolean active, long revision,
                            double mp, double maximum, double recovery, List<SkillView> skills,
                            List<Integer> friendlyIds) {
        this(session, dimension, active, revision, mp, maximum, recovery, skills, friendlyIds, 0L, 0L);
    }

    @Override
    public UUID membershipSession() {
        return session;
    }

    @Override
    public SkillStatePacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new SkillStatePacket(session, dimension, active, revision, mp, maximum, recovery, skills,
                friendlyIds, serverEpoch, generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    public static final Type<SkillStatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_state"));
    public static final StreamCodec<FriendlyByteBuf, SkillStatePacket> STREAM_CODEC = StreamCodec.of(SkillStatePacket::encode, SkillStatePacket::decode);
    public SkillStatePacket {
        skills=List.copyOf(skills); friendlyIds=List.copyOf(friendlyIds);
        SkillPacketIO.finiteResource(mp); SkillPacketIO.finiteResource(maximum); SkillPacketIO.finiteResource(recovery);
        if(session==null||dimension==null||mp>maximum||skills.size()>256||friendlyIds.size()>4096||(!active&&!skills.isEmpty()))
            throw new IllegalArgumentException("Invalid skill state");
    }
    private static void encode(FriendlyByteBuf b, SkillStatePacket p) {
        b.writeUUID(p.session); b.writeResourceLocation(p.dimension); b.writeBoolean(p.active); b.writeLong(p.revision);
        b.writeDouble(p.mp); b.writeDouble(p.maximum); b.writeDouble(p.recovery); b.writeVarInt(p.skills.size());
        for(SkillView v:p.skills) {
            b.writeResourceLocation(v.id()); b.writeUtf(v.name(),128); b.writeUtf(v.description(),2048);
            b.writeVarInt(v.level()); b.writeVarInt(v.maxLevel()); b.writeEnum(v.aim()); b.writeBoolean(v.area());
            b.writeBoolean(v.targetCosts()); b.writeBoolean(v.ready()); b.writeUtf(v.reason(),512);
            b.writeVarInt(v.apCost()); b.writeDouble(v.mpCost()); b.writeVarInt(v.cooldown());
        }
        b.writeVarInt(p.friendlyIds.size()); for(int id:p.friendlyIds)b.writeVarInt(id);
        b.writeVarLong(p.membershipServerEpoch); b.writeVarLong(p.membershipGeneration);
    }
    private static SkillStatePacket decode(FriendlyByteBuf b) {
        UUID session=b.readUUID(); ResourceLocation dimension=b.readResourceLocation(); boolean active=b.readBoolean(); long revision=b.readLong();
        double mp=b.readDouble(),maximum=b.readDouble(),recovery=b.readDouble();
        int count=SkillPacketIO.size(b,256); List<SkillView> skills=new ArrayList<>(count);
        for(int i=0;i<count;i++)skills.add(new SkillView(b.readResourceLocation(),b.readUtf(128),b.readUtf(2048),
                b.readVarInt(),b.readVarInt(),b.readEnum(SkillDefinition.Aim.class),b.readBoolean(),b.readBoolean(),b.readBoolean(),
                b.readUtf(512),b.readVarInt(),b.readDouble(),b.readVarInt()));
        int size=SkillPacketIO.size(b,4096); List<Integer> friendly=new ArrayList<>(size);
        for(int i=0;i<size;i++)friendly.add(b.readVarInt());
        long epoch=b.readVarLong(), generation=b.readVarLong();
        return new SkillStatePacket(session,dimension,active,revision,mp,maximum,recovery,skills,friendly,epoch,generation);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
