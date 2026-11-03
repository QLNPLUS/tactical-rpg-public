package com.tactical.neoforge.network;

import com.tactical.neoforge.battle.BattleJournal;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.network.connection.ConnectionType;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The journal channel's own wire contract, as far as it can be pinned without a booted game.
 *
 * <p>A row's text became a native component, which is a wire change of its own; the channel therefore has
 * its own protocol version and is deliberately kept apart from the main and skill channels. The component
 * codec itself only exists inside a running game, so the round-trip lives in the dedicated
 * {@code serverBattleLogTest} fixture - what is checked here is the version, the packet-wide budget, the
 * row contract and the refusals that happen before any component is decoded.</p>
 */
class BattleLogProtocolTest {

    private static final UUID SESSION = UUID.randomUUID();

    @Test
    void theJournalChannelHasItsOwnBumpedVersion() {
        assertEquals("2", BattleLogNetwork.PROTOCOL,
                "journal rows became native components on the wire: journal protocol 1 -> 2");
        assertNotEquals(TacticalNetwork.PROTOCOL, BattleLogNetwork.PROTOCOL,
                "the three channels are versioned independently on purpose");
        assertNotEquals(SkillNetwork.PROTOCOL, BattleLogNetwork.PROTOCOL);
        assertEquals("12", TacticalNetwork.PROTOCOL, "an unrelated channel keeps its own version");
        assertEquals("4", SkillNetwork.PROTOCOL, "an unrelated channel keeps its own version");
    }

    @Test
    void thePacketBudgetCoversTheWorstLegitimateSnapshot() {
        int worstRow = ComponentIO.MAX_FRAME_BYTES + 64; // frame prefix + longest category + sequences
        int worst = BattleLogPacket.MAX_HISTORY * worstRow + 64; // plus header and membership stamp
        assertTrue(BattleLogPacket.MAX_BODY_BYTES > worst,
                "the budget must not refuse a legitimate 64-row snapshot: " + BattleLogPacket.MAX_BODY_BYTES
                        + " <= " + worst);
        assertTrue(BattleLogPacket.MAX_BODY_BYTES < 1048576,
                "the budget must stay below the clientbound custom payload limit of this installation");
    }

    @Test
    void aLiteralRowIsStoredAsLiteralAndNeverAsAKey() {
        BattleLogPacket.Entry entry = new BattleLogPacket.Entry(1, "damage", BattleJournal.KEY_DAMAGE);
        PlainTextContents contents = (PlainTextContents) entry.text().getContents();
        assertEquals(BattleJournal.KEY_DAMAGE, contents.text(),
                "the compatibility form is literal text, not a translation key");
    }

    @Test
    void aComponentRowKeepsItsStructure() {
        Component row = Component.translatable(BattleJournal.KEY_DAMAGE,
                Component.translatable("entity.minecraft.zombie"), "3.0",
                Component.translatable(BattleJournal.KEY_SOURCE_FALL), "12.0", "20.0");
        BattleLogPacket.Entry entry = new BattleLogPacket.Entry(1, "damage", row);
        TranslatableContents contents = (TranslatableContents) entry.text().getContents();
        assertEquals(BattleJournal.KEY_DAMAGE, contents.getKey());
        assertEquals(5, contents.getArgs().length);
        assertTrue(contents.getArgs()[0] instanceof Component, "the nested entity name must stay a component");
        assertTrue(contents.getArgs()[2] instanceof Component, "the nested source must stay a component");
        assertEquals("3.0", contents.getArgs()[1]);
    }

    @Test
    void invalidRowsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new BattleLogPacket.Entry(0, "damage", "text"), "a row needs a positive sequence");
        assertThrows(IllegalArgumentException.class,
                () -> new BattleLogPacket.Entry(1, "damage", "x".repeat(BattleLogPacket.MAX_TEXT + 1)),
                "the literal compatibility form keeps its character limit");
        assertThrows(IllegalArgumentException.class,
                () -> new BattleLogPacket.Entry(1, "c".repeat(BattleLogPacket.MAX_CATEGORY + 1), "text"));
        assertThrows(NullPointerException.class,
                () -> new BattleLogPacket.Entry(1, "damage", (Component) null));
        assertThrows(NullPointerException.class,
                () -> new BattleLogPacket.Entry(1, "damage", (String) null));
    }

    @Test
    void theSameComponentIsKeptByReferenceNotResolved() {
        Component row = Component.translatable(BattleJournal.KEY_START);
        BattleLogPacket.Entry entry = new BattleLogPacket.Entry(1, "battle", row);
        assertSame(row, entry.text(), "the row stores the component it was given, it does not pre-resolve it");
    }

    @Test
    void anOverBudgetBodyIsRefusedBeforeAnyAllocation() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            buffer.writeVarInt(BattleLogPacket.MAX_BODY_BYTES + 1);
            buffer.writeBytes(new byte[8]);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
            assertTrue(failure.getMessage().contains("budget"), "unexpected message: " + failure.getMessage());
            assertEquals(8, buffer.readableBytes(), "only the declared length may be consumed");
        } finally {
            buffer.release();
        }
    }

    @Test
    void aNegativeOrTruncatedBodyIsRefused() {
        RegistryFriendlyByteBuf negative = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            negative.writeVarInt(-1);
            negative.writeBytes(new byte[4]);
            assertThrows(IllegalArgumentException.class, () -> BattleLogPacket.STREAM_CODEC.decode(negative));
            assertEquals(4, negative.readableBytes(), "only the declared length may be consumed");
        } finally {
            negative.release();
        }
        RegistryFriendlyByteBuf truncated = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            truncated.writeVarInt(64);
            truncated.writeBytes(new byte[4]);
            assertThrows(IllegalArgumentException.class, () -> BattleLogPacket.STREAM_CODEC.decode(truncated));
        } finally {
            truncated.release();
        }
    }

    @Test
    void aSnapshotWithoutARegistryContextCannotBeMeasured() {
        assertFalse(BattleLogPacket.fitsComponentFrame(Component.literal("日志"), null),
                "a missing registry context must not be reported as fitting");
        assertFalse(BattleLogPacket.fitsComponentFrame(null, RegistryAccess.EMPTY));
    }

    @Test
    void theSnapshotKeepsItsOrderingAndLifecycleRules() {
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 2,
                List.of(entry(2), entry(1))));
        assertThrows(IllegalArgumentException.class, () -> new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 3,
                List.of(entry(1), entry(2))), "the snapshot must include its latest event");
        BattleLogPacket packet = new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 2, List.of(entry(1), entry(2)));
        assertEquals(0L, packet.membershipGeneration(), "an unstamped snapshot carries generation 0");
        BattleLogPacket stamped = packet.withMembership(4242L, 9L);
        assertEquals(4242L, stamped.membershipServerEpoch());
        assertEquals(9L, stamped.membershipGeneration());
        assertSame(packet, packet.withMembership(0L, 0L), "an unchanged stamp is not a new snapshot");
    }

    private static BattleLogPacket.Entry entry(long sequence) {
        return new BattleLogPacket.Entry(sequence, "action", Component.translatable(BattleJournal.KEY_START));
    }
}
