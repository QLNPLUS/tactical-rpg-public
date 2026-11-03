package com.tactical.neoforge.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.connection.ConnectionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame helper is the only place where server-authored text crosses the wire as structure, so these
 * tests hold it to three things the later batches depend on: a frame cannot be over-sized or read past its
 * own bounds, a frame leaves the caller's buffer exactly at the next frame, and the caller's registry
 * context reaches the payload codec.
 *
 * <p>The frame mechanics are driven here with a plain UTF-8 payload codec, because the native component
 * codec cannot run in this unit-test task: building it initializes {@code Style -> HoverEvent ->
 * ItemStack} and therefore the real item/block registries, and NeoForge's bootstrapped
 * {@code FeatureFlags} needs FML's mod list, which a plain JUnit JVM does not have. The real component
 * round-trip lives in the dedicated-server fixture {@link ComponentIoSelfTest}
 * ({@code -Dtactical.selftest=component_io}); everything the frame itself guarantees is covered here.</p>
 */
class ComponentIOTest {

    private static final int LIMIT = ComponentIO.MAX_FRAME_BYTES;
    private static final String CHINESE = "对僵尸造成 3 点伤害";
    /** A literal that only looks like a translation key: it must never be treated as one. */
    private static final String KEY_LOOKING = "tactical.skill.backstab.name";

    private static final StreamCodec<RegistryFriendlyByteBuf, String> TEXT =
            StreamCodec.of((buffer, value) -> buffer.writeUtf(value), buffer -> buffer.readUtf());

    /** Reports the registry context the payload codec was handed, which is what must not be lost. */
    private static final StreamCodec<RegistryFriendlyByteBuf, RegistryAccess> CONTEXT = StreamCodec.of(
            (buffer, value) -> buffer.writeByte(1),
            buffer -> {
                buffer.readByte();
                return buffer.registryAccess();
            });

    private static final StreamCodec<RegistryFriendlyByteBuf, ConnectionType> CONNECTION = StreamCodec.of(
            (buffer, value) -> buffer.writeByte(1),
            buffer -> {
                buffer.readByte();
                return buffer.getConnectionType();
            });

    private static RegistryFriendlyByteBuf newBuffer() {
        return newBuffer(RegistryAccess.EMPTY);
    }

    private static RegistryFriendlyByteBuf newBuffer(RegistryAccess.Frozen access) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), access, ConnectionType.OTHER);
    }

    @Test
    void payloadsCrossTheWireVerbatim() {
        for (String text : new String[]{CHINESE, KEY_LOOKING, "", "  ", "emoji ⚔ 与 ünïcode"}) {
            RegistryFriendlyByteBuf buffer = newBuffer();
            try {
                ComponentIO.writeFramed(buffer, text, TEXT, LIMIT);
                assertEquals(text, ComponentIO.readFramed(buffer, TEXT, LIMIT));
                assertEquals(0, buffer.readableBytes(), "the frame is exactly as long as it declares");
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void twoFramesStayIsolated() {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            ComponentIO.writeFramed(buffer, CHINESE, TEXT, LIMIT);
            ComponentIO.writeFramed(buffer, "second frame", TEXT, LIMIT);
            assertEquals(CHINESE, ComponentIO.readFramed(buffer, TEXT, LIMIT));
            assertEquals("second frame", ComponentIO.readFramed(buffer, TEXT, LIMIT));
            assertEquals(0, buffer.readableBytes(), "the second frame ends where its payload ends");
        } finally {
            buffer.release();
        }
    }

    @Test
    void readingNeverReleasesTheCallersBuffer() {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            ComponentIO.writeFramed(buffer, CHINESE, TEXT, LIMIT);
            assertEquals(1, buffer.refCnt(), "writing borrows the caller's buffer");
            ComponentIO.readFramed(buffer, TEXT, LIMIT);
            assertEquals(1, buffer.refCnt(), "reading borrows a slice and never releases the parent");
            ComponentIO.writeFramed(buffer, "again", TEXT, LIMIT);
            assertEquals("again", ComponentIO.readFramed(buffer, TEXT, LIMIT),
                    "the parent is still usable after a frame was decoded from it");
        } finally {
            buffer.release();
        }
    }

    @Test
    void theFrameKeepsTheCallersRegistryContext() {
        RegistryAccess.Frozen access = new DistinctAccess(RegistryAccess.EMPTY);
        RegistryFriendlyByteBuf buffer = newBuffer(access);
        try {
            ComponentIO.writeFramed(buffer, access, CONTEXT, LIMIT);
            assertSame(access, ComponentIO.readFramed(buffer, CONTEXT, LIMIT),
                    "the payload codec runs on the caller's registry context, not on an empty one");
            ComponentIO.writeFramed(buffer, ConnectionType.OTHER, CONNECTION, LIMIT);
            assertEquals(ConnectionType.OTHER, ComponentIO.readFramed(buffer, CONNECTION, LIMIT),
                    "the frame wrapper keeps the caller's connection type too");
        } finally {
            buffer.release();
        }
    }

    @Test
    void aMalformedLengthIsRefusedBeforeAnySliceIsTaken() {
        assertLengthRefused(-1, "negative length");
        assertLengthRefused(LIMIT + 1, "length over the frame limit");

        RegistryFriendlyByteBuf truncated = newBuffer();
        try {
            truncated.writeVarInt(64);
            truncated.writeBytes(new byte[8]);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.readFramed(truncated, TEXT, LIMIT));
            assertTrue(failure.getMessage().contains("only 8 remain"), failure.getMessage());
            assertNull(failure.getCause(), "the declared length is refused before the codec is reached");
        } finally {
            truncated.release();
        }
    }

    @Test
    void aBadOrPaddedPayloadIsRefused() {
        RegistryFriendlyByteBuf garbage = newBuffer();
        try {
            // A one-byte payload that claims a 127-byte string: the codec cannot finish it.
            garbage.writeVarInt(1);
            garbage.writeByte(0x7F);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.readFramed(garbage, TEXT, LIMIT));
            assertNotNull(failure.getCause(), "a payload the codec rejects keeps the decoder failure as its cause");
        } finally {
            garbage.release();
        }

        RegistryFriendlyByteBuf frame = newBuffer();
        RegistryFriendlyByteBuf padded = newBuffer();
        try {
            ComponentIO.writeFramed(frame, "padded", TEXT, LIMIT);
            int payload = frame.readVarInt();
            padded.writeVarInt(payload + 1);
            padded.writeBytes(frame, frame.readerIndex(), payload);
            padded.writeByte(0x00);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.readFramed(padded, TEXT, LIMIT));
            assertTrue(failure.getMessage().contains("trailing"), failure.getMessage());
            assertNull(failure.getCause(), "trailing bytes are refused by the frame itself, not by the codec");
        } finally {
            padded.release();
            frame.release();
        }
    }

    @Test
    void anOverSizedPayloadIsRefusedWithoutWritingAnything() {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            String huge = "对".repeat(4_000);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.writeFramed(buffer, huge, TEXT, LIMIT));
            assertNotNull(failure.getCause(), "the underlying encoder failure is kept as the cause");
            assertTrue(failure.getMessage().contains(String.valueOf(LIMIT)), failure.getMessage());
            assertEquals(0, buffer.readableBytes(), "a refused payload leaves no half frame behind");
        } finally {
            buffer.release();
        }
    }

    @Test
    void theFrameLimitCountsWireBytesAndIsInclusive() {
        int previous = -1;
        boolean foundBoundary = false;
        for (int chars = 8_000; chars <= 8_400; chars++) {
            int payload;
            try {
                payload = payloadSize(chars);
            } catch (ComponentIO.FrameException refused) {
                assertEquals(LIMIT, previous, "the largest accepted frame is exactly the limit, so the limit itself is inside");
                assertNotNull(refused.getCause(), "the over-size refusal keeps the encoder failure as its cause");
                foundBoundary = true;
                break;
            }
            assertTrue(payload <= LIMIT, "an accepted frame never exceeds the limit");
            previous = payload;
        }
        assertTrue(foundBoundary, "the scan has to reach the first refused size above the limit");
    }

    @Test
    void nullIsRefused() {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            assertThrows(ComponentIO.FrameException.class, () -> ComponentIO.writeFramed(buffer, null, TEXT, LIMIT));
            assertEquals(0, buffer.readableBytes(), "a missing payload is not silently written as empty text");
            assertThrows(ComponentIO.FrameException.class, () -> ComponentIO.readFramed(null, TEXT, LIMIT));
            assertThrows(ComponentIO.FrameException.class, () -> ComponentIO.readFramed(buffer, null, LIMIT));
        } finally {
            buffer.release();
        }
    }

    @Test
    void theComponentEntryPointsRefuseBadInputBeforeTheCodecRuns() {
        // The checks below all fire ahead of the native codec, which is why they can run without a game.
        assertEquals(8_192, LIMIT, "the journalled component frame stays at 8 KiB of wire bytes");
        assertEquals(32_768L, ComponentIO.MAX_NBT_BYTES, "the NBT reader keeps a small heap quota");
        assertTrue(ComponentIO.MAX_NBT_BYTES < 2_097_152L, "never the vanilla 2 MiB quota, and never unlimited");

        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            assertThrows(ComponentIO.FrameException.class, () -> ComponentIO.write(buffer, null));
            assertEquals(0, buffer.readableBytes(), "a missing component leaves no half frame behind");
            assertThrows(ComponentIO.FrameException.class, () -> ComponentIO.read(null));

            buffer.writeVarInt(-3);
            buffer.writeBytes(new byte[8]);
            ComponentIO.FrameException negative = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.read(buffer));
            assertTrue(negative.getMessage().contains("Negative"), negative.getMessage());
            assertEquals(8, buffer.readableBytes(), "only the length itself is consumed");
        } finally {
            buffer.release();
        }

        RegistryFriendlyByteBuf oversize = newBuffer();
        try {
            oversize.writeVarInt(LIMIT + 1);
            oversize.writeBytes(new byte[4]);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.read(oversize));
            assertNotNull(failure.getMessage());
            assertEquals(4, oversize.readableBytes(), "an over-limit declaration never authorizes a read of the payload");
        } finally {
            oversize.release();
        }
    }

    @Test
    void aTargetBufferWithTooLittleCapacityLeavesNoFrameBehind() {
        // A caller's buffer can be at its capacity limit; the length prefix must not be written first.
        RegistryFriendlyByteBuf target = new RegistryFriendlyByteBuf(
                Unpooled.buffer(8, 32), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            ComponentIO.FrameException tooBig = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.writeFramed(target, "x".repeat(40), TEXT, LIMIT));
            assertNull(tooBig.getCause(), "the capacity check refuses before anything is written");
            assertEquals(0, target.readableBytes(), "no length prefix is left behind");

            ComponentIO.writeFramed(target, "kept", TEXT, LIMIT);
            int kept = target.readableBytes();
            assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.writeFramed(target, "y".repeat(40), TEXT, LIMIT));
            assertEquals(kept, target.readableBytes(), "an earlier frame is not damaged by the refusal");
            assertEquals("kept", ComponentIO.readFramed(target, TEXT, LIMIT));
        } finally {
            target.release();
        }
    }

    private static void assertLengthRefused(int declared, String why) {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            buffer.writeVarInt(declared);
            buffer.writeBytes(new byte[16]);
            ComponentIO.FrameException failure = assertThrows(ComponentIO.FrameException.class,
                    () -> ComponentIO.readFramed(buffer, TEXT, LIMIT), why);
            assertNull(failure.getCause(), "the length itself is rejected, before the codec is reached: " + why);
            assertEquals(16, buffer.readableBytes(), "a rejected length consumes nothing past itself");
        } finally {
            buffer.release();
        }
    }

    private static int payloadSize(int literalChars) {
        RegistryFriendlyByteBuf buffer = newBuffer();
        try {
            ComponentIO.writeFramed(buffer, "a".repeat(literalChars), TEXT, LIMIT);
            int declared = buffer.readVarInt();
            assertEquals(declared, buffer.readableBytes(), "the declared payload length matches the bytes written");
            return declared;
        } finally {
            buffer.release();
        }
    }

    /** An access that is only distinguishable from {@link RegistryAccess#EMPTY} by identity. */
    private static final class DistinctAccess implements RegistryAccess.Frozen {
        private final RegistryAccess.Frozen delegate;

        private DistinctAccess(RegistryAccess.Frozen delegate) {
            this.delegate = delegate;
        }

        @Override
        public <E> java.util.Optional<net.minecraft.core.Registry<E>> registry(
                net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends E>> key) {
            return delegate.registry(key);
        }

        @Override
        public java.util.stream.Stream<RegistryAccess.RegistryEntry<?>> registries() {
            return delegate.registries();
        }

        @Override
        public RegistryAccess.Frozen freeze() {
            return this;
        }
    }
}
