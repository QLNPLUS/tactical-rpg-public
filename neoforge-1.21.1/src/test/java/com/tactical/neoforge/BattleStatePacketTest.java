package com.tactical.neoforge;

import com.tactical.core.combat.BattlePhase;
import com.tactical.neoforge.network.BattleStateFragmentPacket;
import com.tactical.neoforge.network.BattleStatePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state snapshot's model and its bounds, as far as they can be pinned without a booted game.
 *
 * <p>The actor, the turn order and the weapon are native components now, and a component codec only exists
 * inside a running game, so the wire round-trip of this packet lives in the {@code battle_state_wire}
 * runtime fixture. What is decided before any byte is written - the model, the per-person correlation and
 * the counts that must be refused before an allocation - is pinned here.</p>
 */
class BattleStatePacketTest {

    private static final long SERVER = 4242L;

    private static BattleStatePacket packet(List<Component> order, int[] gauges, int[] ids, int[] participants,
                                            int[] reachable) {
        return new BattleStatePacket(true, 2, 7, 35, 150, 12, 0, 99, 0, reachable,
                Component.translatable("entity.minecraft.zombie"), order, 0, Component.literal("Bow"), 1, 16,
                0, 99, 0, 24, false, 72, participants, gauges, 3, false, ids,
                19, 12, 15, 11, 70, 2, false,java.util.UUID.randomUUID(), 41L, SERVER, 9L);
    }

    @Test
    void inactiveSnapshotHasNoStalePortraitOrHealthIds() {
        BattleStatePacket packet = BattleStatePacket.inactive();
        assertFalse(packet.active());
        assertEquals(0, packet.turnEntityIds().length);
        assertEquals(0, packet.participants().length);
        assertNull(packet.sessionId());
        assertEquals(0L, packet.turnEpoch());
        assertTrue(packet.order().isEmpty());
        assertEquals("", packet.actor().getString());
    }

    /**
     * The names travel as the structure the server sent, and the compatibility form is text.
     *
     * <p>A key is kept as a key (so each client reads it in its own language) and the old string form is
     * stored as a literal, so a name that merely looks like a key is never translated.</p>
     */
    @Test
    void namesKeepTheirStructureAndTheCompatibilityFormStaysLiteral() {
        List<Component> order = new ArrayList<>();
        order.add(Component.translatable("entity.minecraft.zombie"));
        order.add(Component.literal("tactical.journal.damage"));
        BattleStatePacket keyed = packet(order, new int[]{100, 24}, new int[]{72, 12},
                new int[]{72, 100, 100}, new int[]{0, 99, 0});
        assertTrue(keyed.actor().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                "an unnamed mob keeps its registry key");
        assertEquals("tactical.journal.damage", ((PlainTextContents) keyed.order().get(1).getContents()).text(),
                "a custom name that looks like a key stays text");

        BattleStatePacket legacy = new BattleStatePacket(true, 2, 7, 35, 150, 12, 0, 99, 0, new int[]{0, 99, 0},
                "zombie", List.of("zombie", "player"), 7, "bow", 1, 16, 0, 99, 0, 24, false, 72,
                new int[]{12, 100, 100}, new int[]{100, 24}, 3, false, new int[]{72, 12});
        assertEquals("zombie", ((PlainTextContents) legacy.actor().getContents()).text(),
                "the compatibility constructor names its actor as text, not as a key");
        assertEquals("bow", ((PlainTextContents) legacy.weapon().getContents()).text());
        assertEquals(2, legacy.order().size());
    }

    /** The order names, the gauges and the per-person ids describe the same people, so they must agree. */
    @Test
    void perPersonArraysMustAgreeWithTheTurnOrder() {
        List<Component> two = List.of(Component.literal("a"), Component.literal("b"));
        assertThrows(IllegalArgumentException.class,
                () -> packet(two, new int[]{50}, new int[]{72, 12}, new int[0], new int[0]),
                "three names with two ids is a contradiction");
        assertThrows(IllegalArgumentException.class,
                () -> packet(two, new int[]{50, 60}, new int[]{72, 12, 13}, new int[0], new int[0]),
                "the gauges must describe the same people as the ids");
        // A legacy snapshot that leaves the optional arrays empty is still accepted.
        assertEquals(2, packet(two, new int[0], new int[0], new int[0], new int[0]).order().size());
    }

    /** Participants are whole {@code (entityId, hp, maxHp)} triples, and a reachable set is whole cells. */
    @Test
    void participantAndReachableArraysAreWholeUnits() {
        assertThrows(IllegalArgumentException.class,
                () -> packet(List.of(), new int[0], new int[0], new int[]{72, 100}, new int[0]),
                "half a participant triple is refused");
        assertThrows(IllegalArgumentException.class,
                () -> packet(List.of(), new int[0], new int[0], new int[0], new int[]{0, 99}),
                "half a reachable cell is refused");
        assertEquals(1, packet(List.of(), new int[0], new int[0], new int[]{72, 100, 100},
                new int[]{0, 99, 0}).participants().length / 3, "one whole triple is one participant");
    }

    /** The legacy 256 per-person limit is still the network limit, on both sides. */
    @Test
    void thePerPersonLimitStillApplies() {
        List<Component> tooMany = new ArrayList<>();
        for (int index = 0; index <= BattleStatePacket.MAX_PER_PERSON; index++) {
            tooMany.add(Component.literal("r" + index));
        }
        assertThrows(IllegalArgumentException.class,
                () -> packet(tooMany, new int[0], new int[0], new int[0], new int[0]));
        int[] tooManyIds = new int[BattleStatePacket.MAX_PER_PERSON + 1];
        assertThrows(IllegalArgumentException.class,
                () -> packet(List.of(), new int[0], tooManyIds, new int[0], new int[0]));
    }

    /**
     * The break price is a quote of its own.
     *
     * <p>Breaking a block is a separate command from placing one and from using an item, so the snapshot
     * carries its own price instead of borrowing either of them; a client that only knew the older three
     * quotes cannot silently price a break at the placement price.</p>
     */
    @Test
    void theBreakPriceIsItsOwnQuote() {
        BattleStatePacket packet = packet(List.of(), new int[0], new int[0], new int[0], new int[0]);
        assertEquals(11, packet.breakCost(), "the break price travels as its own field");
        assertEquals(15, packet.placeCost(), "the placement price is not reused for breaking");
        assertEquals(12, packet.itemCost());
        assertEquals(19, packet.attackCost());
        // The compatibility form still names the first version's own defaults, now including a break price.
        BattleStatePacket legacy = new BattleStatePacket(true, 2, 7, 35, 150, 12, 0, 99, 0, new int[]{0, 99, 0},
                "zombie", List.of("zombie"), 7, "bow", 1, 16, 0, 99, 0, 24, false, 72,
                new int[0], new int[0], 3, false, new int[0]);
        assertEquals(10, legacy.breakCost(), "a snapshot without quotes still has a usable break price");
        assertEquals(0, BattleStatePacket.inactive().breakCost(), "an ended encounter quotes nothing");
    }

    /**
     * The transport bounds are the ones the report promised: a 129<sup>3</sup> zone of coordinates and a
     * body budget above the worst legal body, cut into slices of at most 64 KiB.
     */
    @Test
    void theTransportBoundsCoverTheLargestConfiguredZone() {
        assertEquals(129 * 129 * 129 * 3, BattleStatePacket.MAX_REACHABLE_COORDS);
        assertEquals(36 * 1024 * 1024, BattleStatePacket.MAX_BODY_BYTES);
        assertEquals(64 * 1024, BattleStateFragmentPacket.MAX_FRAGMENT_BYTES);
        int coordinates = 129 * 129 * 129 * 3 * 5;
        int names = 256 * 8192;
        assertTrue(BattleStatePacket.MAX_BODY_BYTES > coordinates,
                "the body budget must hold the largest reachable coordinate set");
        assertTrue(BattleStatePacket.MAX_BODY_BYTES > coordinates + names,
                "the budget has room for the names as well, not only for the coordinates");
        assertEquals(1, BattleStateFragmentPacket.partsFor(1));
        assertEquals(1, BattleStateFragmentPacket.partsFor(BattleStateFragmentPacket.MAX_FRAGMENT_BYTES));
        assertEquals(2, BattleStateFragmentPacket.partsFor(BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1));
        assertEquals(BattleStateFragmentPacket.MAX_FRAGMENT_BYTES,
                BattleStateFragmentPacket.bytesOf(BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1, 0));
        assertEquals(1, BattleStateFragmentPacket.bytesOf(BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1, 1));
        assertTrue(BattleStateFragmentPacket.MAX_PARTS >= BattleStateFragmentPacket.partsFor(
                BattleStatePacket.MAX_FRAMED_BYTES));
    }

    /** The exit snapshot keeps naming its encounter, so it cannot erase a newer one. */
    @Test
    void theInstrumentedExitStillNamesItsSession() {
        java.util.UUID session = java.util.UUID.randomUUID();
        BattleStatePacket exit = BattleStatePacket.inactive(session);
        assertEquals(BattlePhase.INACTIVE.ordinal(), exit.phase());
        assertEquals(session, exit.sessionId());
        assertEquals(session, exit.membershipSession());
    }
}
