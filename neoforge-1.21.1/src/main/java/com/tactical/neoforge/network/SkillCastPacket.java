package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record SkillCastPacket(UUID session,long turnEpoch,ResourceLocation dimension,ResourceLocation skill,SkillTarget target) implements CustomPacketPayload {
    public static final Type<SkillCastPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_cast"));
    public static final StreamCodec<FriendlyByteBuf,SkillCastPacket> STREAM_CODEC=StreamCodec.of(
            (b,p)->{b.writeUUID(p.session);b.writeVarLong(p.turnEpoch);b.writeResourceLocation(p.dimension);b.writeResourceLocation(p.skill);SkillPacketIO.target(b,p.target);},
            b->new SkillCastPacket(b.readUUID(),b.readVarLong(),b.readResourceLocation(),b.readResourceLocation(),SkillPacketIO.target(b)));
    public SkillCastPacket {if(session==null||dimension==null||skill==null||target==null)throw new IllegalArgumentException("Missing skill cast context");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
