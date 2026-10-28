package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Explicit facing plus AP-paid defensive stance. */
public record DefendPacket(ActionStamp stamp,int yaw) implements CustomPacketPayload {
 public DefendPacket(ActionStamp stamp){this(stamp,0);}
 public DefendPacket(int yaw){this(null,yaw);}
 public static final Type<DefendPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"defend"));
 public static final StreamCodec<FriendlyByteBuf,DefendPacket> STREAM_CODEC=StreamCodec.of(
         (buffer,packet)->{StampIO.write(buffer,packet.stamp);buffer.writeVarInt(packet.yaw);},
         (buffer)->{ActionStamp stamp=StampIO.read(buffer);return new DefendPacket(stamp,buffer.readVarInt());});
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
