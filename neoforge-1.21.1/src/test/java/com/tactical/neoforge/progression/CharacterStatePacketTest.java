package com.tactical.neoforge.progression;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.network.CharacterStatePacket;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model of the character snapshot, as far as it can be pinned without a booted game.
 *
 * <p>A component codec only exists inside a running game, so the wire round-trip of this packet is driven by
 * the {@code progression} server fixture. What is decided before any byte is written - the array contract,
 * the bounds, and the fact that no caller can reach the stored arrays - is pinned here.</p>
 */
class CharacterStatePacketTest {

    private static final UUID OWNER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    static CharacterStatePacket packet(ProgressionReason reason) {
        return new CharacterStatePacket(OWNER, 7, true, true, true, true, reason.name(), Component.translatable(reason.key()),
                12L, 3, 100, 99, new double[6], 1, 1, 2, 0, 350L, 200L, new int[6], new int[]{12, 10, 10, 10, 10, 12},
                new double[]{24, 110, 104, 5.5, 1.02, 1.04}, new int[6], false, new double[]{24, 110, 104, 5.5, 1.02, 1.04});
    }

    @Test void arraysAreAlwaysSixAndNeverSharedWithTheCaller() {
        int[] invested = {1, 0, 0, 0, 0, 1};
        CharacterStatePacket packet = new CharacterStatePacket(OWNER, 1, false, true, true, true, ProgressionReason.OK.name(),
                Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, invested, new int[6],
                new double[6], new int[6], false, new double[6]);
        invested[0] = 99;
        assertEquals(1, packet.invested()[0]);
        int[] read = packet.invested();
        read[0] = 42;
        assertEquals(1, packet.invested()[0]);
    }

    @Test void wrongArrayLengthsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[5],
                new int[6], new double[6], new int[6], false, new double[6]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], new double[5], new int[6], false, new double[6]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, null));
    }

    @Test void impossibleNumbersAreRefused() {
        // A missing owner is a programming error, not a bad value: the constructor refuses it the way every
        // other required reference is refused. Everything else below is a value out of range.
        assertThrows(NullPointerException.class, () -> new CharacterStatePacket(null, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, -1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), -1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]));
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, -1L, 100L, new int[6],
                new int[6], new double[6], new int[6], false, new double[6]));
        double[] broken = new double[6];
        broken[2] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6],
                new int[6], broken, new int[6], false, new double[6]));
        int[] negative = new int[6];
        negative[4] = -1;
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, negative,
                new int[6], new double[6], new int[6], false, new double[6]));
        // A negative per-level grant is not a legal rule set either.
        assertThrows(IllegalArgumentException.class, () -> new CharacterStatePacket(OWNER, 1, false, true, true, true,
                ProgressionReason.OK.name(), Component.empty(), 1L, 2, 100, 99, new double[6], -1, 1, 1, 0, 0L, 100L,
                new int[6], new int[6], new double[6], new int[6], false, new double[6]));
    }

    @Test void thePointGrantAndCoefficientsTravelWithEveryAnswer() {
        CharacterStatePacket packet = packet(ProgressionReason.OK);
        assertEquals(1, packet.pointsPerLevel());
        assertEquals(1, packet.nextLevelPoints());
        double[] coefficients = packet.coefficients();
        assertEquals(6, coefficients.length);
        coefficients[0] = 99.0D;
        assertEquals(0.0D, packet.coefficients()[0], "the coefficient array is copied in and out");
    }

    @Test void refusalCodeAndTextTravelTogether() {
        CharacterStatePacket refused = packet(ProgressionReason.INSUFFICIENT_XP);
        assertFalse(refused.accepted());
        assertEquals(ProgressionReason.INSUFFICIENT_XP, refused.refusal());
        assertEquals("tactical.progression.reason.insufficient_xp", ProgressionReason.INSUFFICIENT_XP.key());
        assertTrue(packet(ProgressionReason.OK).accepted());
    }

    @Test void anUnknownCodeIsTreatedAsAFailureRatherThanSuccess() {
        CharacterStatePacket unknown = new CharacterStatePacket(OWNER, 1, false, true, true, true, "SOMETHING_NEWER",
                Component.empty(), 1L, 2, 100, 99, new double[6], 1, 1, 1, 0, 0L, 100L, new int[6], new int[6], new double[6],
                new int[6], false, new double[6]);
        assertEquals(ProgressionReason.SERVER_ERROR, unknown.refusal());
        assertFalse(unknown.accepted());
    }

    @Test void replayingOneStateForAnotherRequestKeepsEveryNumber() {
        CharacterStatePacket original = packet(ProgressionReason.OK);
        CharacterStatePacket echoed = original.withRequest(9, false);
        assertEquals(9, echoed.requestId());
        assertFalse(echoed.openScreen());
        assertEquals(original.revision(), echoed.revision());
        assertEquals(original.level(), echoed.level());
        assertArrayEquals(original.derived(), echoed.derived());
        assertNotEquals(original.requestId(), echoed.requestId());
    }

    @Test void statOrderIsTheFrozenContract() {
        assertEquals(0, CharacterStat.VIGOR.index());
        assertEquals(1, CharacterStat.MIND.index());
        assertEquals(2, CharacterStat.ENDURANCE.index());
        assertEquals(3, CharacterStat.STRENGTH.index());
        assertEquals(4, CharacterStat.DEXTERITY.index());
        assertEquals(5, CharacterStat.INTELLIGENCE.index());
        assertEquals(6, CharacterStat.COUNT);
        assertEquals(CharacterStat.DEXTERITY, CharacterStat.of(4));
        assertThrows(IllegalArgumentException.class, () -> CharacterStat.of(6));
        assertThrows(IllegalArgumentException.class, () -> CharacterStat.of(-1));
    }
}
