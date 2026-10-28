package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** -1 cancel, 0 walk, 1 direct drop. Server revalidates the stored offer and geometry. */
public record MoveChoicePacket(ActionStamp stamp,UUID token,int mode) implements CustomPacketPayload {
 public static final Type<MoveChoicePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"move_choice"));
 public static final StreamCodec<FriendlyByteBuf,MoveChoicePacket> STREAM_CODEC=StreamCodec.of((b,p)->{StampIO.write(b,p.stamp);b.writeUUID(p.token);b.writeVarInt(p.mode);},b->new MoveChoicePacket(StampIO.read(b),b.readUUID(),b.readVarInt()));
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
