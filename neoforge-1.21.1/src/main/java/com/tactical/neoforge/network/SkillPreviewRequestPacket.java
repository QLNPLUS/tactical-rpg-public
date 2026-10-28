package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

public record SkillPreviewRequestPacket(UUID session,long turnEpoch,ResourceLocation dimension,int requestId,
                                        ResourceLocation skill,SkillTarget target) implements CustomPacketPayload {
    public static final Type<SkillPreviewRequestPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_preview_request"));
    public static final StreamCodec<FriendlyByteBuf,SkillPreviewRequestPacket> STREAM_CODEC=StreamCodec.of(
            (b,p)->{b.writeUUID(p.session);b.writeVarLong(p.turnEpoch);b.writeResourceLocation(p.dimension);b.writeVarInt(p.requestId);b.writeResourceLocation(p.skill);SkillPacketIO.target(b,p.target);},
            b->new SkillPreviewRequestPacket(b.readUUID(),b.readVarLong(),b.readResourceLocation(),b.readVarInt(),b.readResourceLocation(),SkillPacketIO.target(b)));
    public SkillPreviewRequestPacket {if(session==null||dimension==null||skill==null||target==null)throw new IllegalArgumentException("Missing skill preview context");}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
