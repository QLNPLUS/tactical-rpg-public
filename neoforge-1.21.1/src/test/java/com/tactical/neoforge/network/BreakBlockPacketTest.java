package com.tactical.neoforge.network;

import com.tactical.neoforge.battle.multi.ActionStamp;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The break intent is its own payload, and its body is exactly what the server needs to re-derive
 * everything else: a session/turn stamp, the hotbar slot the client claims, and one block position.
 *
 * <p>Nothing an attacker could invent about the actor, the reach, the tool or the price travels here -
 * that is the point of the shape, and these checks keep the shape from quietly growing one of them.</p>
 */
class BreakBlockPacketTest {

    private static final long EPOCH = 11L;

    private static ActionStamp stamp() {
        return new ActionStamp(UUID.fromString("00000000-0000-0000-0000-0000000000bb"), EPOCH);
    }

    @Test
    void theIntentSurvivesTheWireUnchanged() {
        BreakBlockPacket packet = new BreakBlockPacket(stamp(), 4, new BlockPos(12, 64, -7));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BreakBlockPacket.STREAM_CODEC.encode(buffer, packet);
            BreakBlockPacket decoded = BreakBlockPacket.STREAM_CODEC.decode(buffer);
            assertEquals(packet, decoded);
            assertEquals(4, decoded.slot());
            assertEquals(new BlockPos(12, 64, -7), decoded.pos());
            assertEquals(stamp(), decoded.stamp());
            assertEquals(0, buffer.readableBytes(), "nothing is written past the payload");
        } finally {
            buffer.release();
        }
    }

    @Test
    void aMissingTargetIsNotAPayload() {
        assertThrows(IllegalArgumentException.class, () -> new BreakBlockPacket(stamp(), 0, null));
    }

    /** A slot outside the hotbar is malformed, not merely wrong: it is refused before any handler runs. */
    @Test
    void anImpossibleHotbarSlotIsRefusedWhileDecoding() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StampIO.write(buffer, stamp());
            buffer.writeVarInt(9);
            buffer.writeBlockPos(BlockPos.ZERO);
            assertThrows(IllegalArgumentException.class, () -> BreakBlockPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    /** The break intent and the placement intent stay two payloads: neither can be read as the other. */
    @Test
    void breakingIsNotAPlacementPayload() {
        assertEquals("tactical:break_block", BreakBlockPacket.TYPE.id().toString());
        assertEquals("tactical:place_block", PlaceBlockPacket.TYPE.id().toString());
        assertEquals("tactical:use_item", UseItemPacket.TYPE.id().toString());
    }
}
