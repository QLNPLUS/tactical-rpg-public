package com.tactical.neoforge.network;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.UUID;

/** Server-authored offer; choices carry its nonce, never a client-authored path or AP cost. */
public record MoveChoiceOfferPacket(UUID token, GridPos target, int walkCost, int dropCost, int dropHeight) implements CustomPacketPayload {
 public static final Type<MoveChoiceOfferPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID,"move_choice_offer"));
 public static final StreamCodec<FriendlyByteBuf,MoveChoiceOfferPacket> STREAM_CODEC=StreamCodec.of((b,p)->{
  b.writeUUID(p.token);b.writeBlockPos(new net.minecraft.core.BlockPos(p.target.x(),p.target.y(),p.target.z()));
  b.writeVarInt(p.walkCost);b.writeVarInt(p.dropCost);b.writeVarInt(p.dropHeight);
 },b->{var id=b.readUUID();var pos=b.readBlockPos();return new MoveChoiceOfferPacket(id,new GridPos(pos.getX(),pos.getY(),pos.getZ()),b.readVarInt(),b.readVarInt(),b.readVarInt());});
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
