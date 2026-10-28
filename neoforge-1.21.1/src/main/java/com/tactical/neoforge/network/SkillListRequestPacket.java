package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The server chooses the owner and resolves all current grants; no client skill list is accepted. */
public record SkillListRequestPacket(ActionStamp stamp) implements CustomPacketPayload {
    public static final Type<SkillListRequestPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"skill_list_request"));
    public static final StreamCodec<FriendlyByteBuf,SkillListRequestPacket> STREAM_CODEC=StreamCodec.of((b,p)->StampIO.write(b,p.stamp),b->new SkillListRequestPacket(StampIO.read(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
