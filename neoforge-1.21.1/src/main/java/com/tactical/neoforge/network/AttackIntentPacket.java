package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Client asks to attack one battle enemy. */
public record AttackIntentPacket(ActionStamp stamp, UUID target) implements CustomPacketPayload {

    public static final Type<AttackIntentPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "attack_intent"));

    public static final StreamCodec<FriendlyByteBuf, AttackIntentPacket> STREAM_CODEC =
            StreamCodec.of(AttackIntentPacket::encode, AttackIntentPacket::decode);

    private static void encode(FriendlyByteBuf buffer, AttackIntentPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeUUID(packet.target);
    }

    private static AttackIntentPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        return new AttackIntentPacket(stamp, buffer.readUUID());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
