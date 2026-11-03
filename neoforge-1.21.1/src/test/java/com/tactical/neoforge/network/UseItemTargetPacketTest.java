package com.tactical.neoforge.network;

import com.tactical.neoforge.battle.multi.ActionStamp;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The use-item intent gained an optional entity target, which is a wire change of its own: the bulk of
 * this test is that both forms round-trip and that a peer speaking the old layout is refused by the
 * protocol number instead of having its bytes misread.
 */
class UseItemTargetPacketTest {

    private static final long EPOCH = 7L;

    private static ActionStamp stamp() {
        return new ActionStamp(UUID.fromString("00000000-0000-0000-0000-0000000000aa"), EPOCH);
    }

    @Test
    void theSelfUseFormKeepsItsMeaning() {
        UseItemPacket packet = new UseItemPacket(stamp(), 3);
        assertNull(packet.target(), "the two-argument form means 'use it on the actor'");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UseItemPacket.STREAM_CODEC.encode(buffer, packet);
            UseItemPacket decoded = UseItemPacket.STREAM_CODEC.decode(buffer);
            assertEquals(packet, decoded);
            assertNull(decoded.target());
            assertEquals(0, buffer.readableBytes(), "nothing is written past the payload");
        } finally {
            buffer.release();
        }
    }

    @Test
    void anEntityTargetSurvivesTheWire() {
        UUID target = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef");
        UseItemPacket packet = new UseItemPacket(stamp(), 5, target);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            UseItemPacket.STREAM_CODEC.encode(buffer, packet);
            UseItemPacket decoded = UseItemPacket.STREAM_CODEC.decode(buffer);
            assertEquals(packet, decoded);
            assertEquals(target, decoded.target());
            assertEquals(5, decoded.slot());
            assertEquals(stamp(), decoded.stamp());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void aTruncatedOrOldPayloadIsRefusedInsteadOfGuessed() {
        // Protocol 8's layout ended right after the slot: a decoder that invented a target would be
        // silently wrong, so reading past the end must fail loudly.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StampIO.write(buffer, stamp());
            buffer.writeVarInt(2);
            assertThrows(IndexOutOfBoundsException.class, () -> UseItemPacket.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void theProtocolNumberCarriesEveryWireChangeOfThisChannel() {
        // Protocol 9 was this packet's own bump (the optional entity target), protocol 10 the battle
        // snapshot's (native component names and bounded slices), 11 the break-block intent with its own
        // price and 12 the companion command projection in the snapshot. This channel keeps one number for
        // all of them.
        assertEquals("12", TacticalNetwork.PROTOCOL,
                "the main payload layout changed again: protocol 11 -> 12");
        assertNotEquals(SkillNetwork.PROTOCOL, TacticalNetwork.PROTOCOL,
                "the skill channel did not change and keeps its own number");
        assertNotEquals(BattleLogNetwork.PROTOCOL, TacticalNetwork.PROTOCOL,
                "the journal channel did not change and keeps its own number");
    }
}
