package com.tactical.neoforge.network;

import com.tactical.neoforge.skill.SkillDefinition;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The independent skill channel carries the membership stamp, which is a wire change of its own.
 *
 * <p>It therefore has its own version, bumped 2 to 3, so a peer that still speaks the old layout is
 * refused by name instead of depending on the main protocol number.</p>
 */
class SkillProtocolVersionTest {

    @Test
    void theSkillChannelHasItsOwnBumpedVersion() {
        assertEquals("3", SkillNetwork.PROTOCOL,
                "the membership stamp changed SkillStatePacket on the wire: skill protocol 2 -> 3");
        assertNotEquals(TacticalNetwork.PROTOCOL, SkillNetwork.PROTOCOL,
                "the two channels are versioned independently on purpose");
    }

    @Test
    void theMembershipStampSurvivesTheSkillCodec() {
        UUID session = UUID.randomUUID();
        SkillView view = new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "strike"),
                "斩击", "一次近身攻击", 1, 3, SkillDefinition.Aim.ENTITY, false, true, true, "", 8, 2.0D, 0);
        SkillStatePacket packet = new SkillStatePacket(session,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), true, 7L,
                40.0D, 60.0D, 1.5D, List.of(view), List.of(12), 4242L, 9L);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SkillStatePacket.STREAM_CODEC.encode(buffer, packet);
            SkillStatePacket decoded = SkillStatePacket.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes(), "the stamp is part of the frame, not trailing garbage");
            assertEquals(4242L, decoded.membershipServerEpoch());
            assertEquals(9L, decoded.membershipGeneration());
            assertEquals(7L, decoded.revision(), "the state revision stays a separate number");
            assertEquals(session, decoded.membershipSession());
        } finally {
            buffer.release();
        }
    }

    @Test
    void anUnstampedSkillStateCanNeverAuthorizeDisplay() {
        SkillStatePacket legacy = new SkillStatePacket(UUID.randomUUID(),
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), false, 1L,
                0.0D, 0.0D, 0.0D, List.of(), List.of());
        assertEquals(0L, legacy.membershipGeneration(), "an unstamped payload carries generation 0");
        assertFalse(legacy.membershipServerEpoch() > 0L);
        assertTrue(legacy.membershipSession() != null, "it still names its encounter, but that is not authority");
    }
}
