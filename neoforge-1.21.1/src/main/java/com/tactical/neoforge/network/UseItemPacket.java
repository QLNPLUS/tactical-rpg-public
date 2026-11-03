package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client asks the server to use one inventory item.
 *
 * <p>The optional target is the stable id of the entity the item should be used on, which is how the HUD
 * row and the world entity click express "use this on that one". A null target keeps the original
 * meaning: the item is used on the actor itself. The server never trusts the hand snapshot, the slot or
 * the target from the wire; it re-reads them against the real player and the real entity.</p>
 */
public record UseItemPacket(ActionStamp stamp, int slot, UUID target) implements CustomPacketPayload {

    public static final Type<UseItemPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "use_item"));

    public static final StreamCodec<FriendlyByteBuf, UseItemPacket> STREAM_CODEC =
            StreamCodec.of(UseItemPacket::encode, UseItemPacket::decode);

    /** The original two-argument form: no entity target, so the item is used on the actor. */
    public UseItemPacket(ActionStamp stamp, int slot) {
        this(stamp, slot, null);
    }

    private static void encode(FriendlyByteBuf buffer, UseItemPacket packet) {
        StampIO.write(buffer, packet.stamp);
        buffer.writeVarInt(packet.slot);
        buffer.writeNullable(packet.target, (buf, id) -> buf.writeUUID(id));
    }

    private static UseItemPacket decode(FriendlyByteBuf buffer) {
        ActionStamp stamp = StampIO.read(buffer);
        int slot = buffer.readVarInt();
        return new UseItemPacket(stamp, slot, buffer.readNullable(buf -> buf.readUUID()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
