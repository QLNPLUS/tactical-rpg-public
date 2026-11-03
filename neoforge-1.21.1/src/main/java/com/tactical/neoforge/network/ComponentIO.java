package com.tactical.neoforge.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Bounded wire form of a native {@link Component}.
 *
 * <p>A player-visible text that is authored on the server has to reach each client as structure, not as a
 * finished sentence, so that two players on the same server can read it in their own language. This
 * helper is the single place where such a component crosses the wire: the native component codec does the
 * encoding on the sender's registry context, and every client resolves it in its own language. Nothing
 * here turns a component into a string.</p>
 *
 * <p>Framing: a VarInt payload length followed by exactly that many payload bytes. The declared length is
 * checked before a slice is taken, so a hostile declaration can neither allocate nor be trusted, and the
 * payload is decoded from a bounded slice of the caller's buffer, so one frame never reads into its
 * neighbour. Every frame must be consumed completely: bytes left over inside a frame are a corrupt
 * payload, not padding.</p>
 *
 * <p>The encoder writes into a scratch buffer capped at the frame limit and released in a
 * {@code finally} block, so an over-sized component is refused before a single byte reaches the
 * caller's buffer. The decoder borrows a slice of the caller's buffer and never releases it.</p>
 *
 * <p>The frame mechanics are the part that can be attacked with a hand-written payload, so they are kept
 * free of the component codec and are exercised directly by the unit tests with a plain payload codec.
 * The component codec itself can only be driven inside a bootstrapped game: building it initializes
 * {@code Style -> HoverEvent -> ItemStack}, which needs the real item/block registries.</p>
 */
final class ComponentIO {

    /**
     * Largest payload this helper writes or accepts, in wire bytes (not characters). A UTF-8 Chinese
     * character costs three bytes here. A component's nested arguments travel inside the same frame, as
     * part of the native NBT; this limit applies to the whole component, not to each nested piece.
     */
    static final int MAX_FRAME_BYTES = 8192;

    /**
     * Heap the native NBT reader may spend on one frame. It stays far below the 2 MiB the vanilla stream
     * codec allows and never uses the trusted/unlimited accounter, while the native depth limit (512) is
     * untouched. It is a second, independent limit: a component can fit {@link #MAX_FRAME_BYTES} of wire
     * bytes and still be refused by this quota, so neither limit is a promise about the other.
     */
    static final long MAX_NBT_BYTES = 32768L;

    private static final StreamCodec<RegistryFriendlyByteBuf, Component> CODEC =
            ByteBufCodecs.fromCodecWithRegistries(ComponentSerialization.CODEC,
                    () -> NbtAccounter.create(MAX_NBT_BYTES));

    private ComponentIO() {
    }

    /** Writes {@code component} as one length-prefixed frame, or refuses without writing anything. */
    static void write(RegistryFriendlyByteBuf buffer, Component component) {
        writeFramed(buffer, component, CODEC, MAX_FRAME_BYTES);
    }

    /** Reads exactly one frame written by {@link #write} from the front of {@code buffer}. */
    static Component read(RegistryFriendlyByteBuf buffer) {
        return readFramed(buffer, CODEC, MAX_FRAME_BYTES);
    }

    /** Frame mechanics without the component codec; the payload codec writes one self-delimiting value. */
    static <T> void writeFramed(RegistryFriendlyByteBuf buffer, T value,
                                StreamCodec<RegistryFriendlyByteBuf, T> codec, int maxFrameBytes) {
        if (buffer == null) throw new FrameException("Missing frame buffer", null);
        if (value == null) throw new FrameException("Missing payload to write", null);
        if (codec == null) throw new FrameException("Missing payload codec", null);
        ByteBuf scratch = Unpooled.buffer(256, maxFrameBytes);
        try {
            try {
                codec.encode(frame(buffer, scratch), value);
            } catch (RuntimeException failure) {
                // The scratch buffer is capped at the frame limit, so an over-long payload fails here
                // instead of growing without bound.
                throw new FrameException("Payload does not fit the " + maxFrameBytes + "-byte frame limit", failure);
            }
            int size = scratch.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            // Reserve the whole frame in the caller's buffer first, so a too-small buffer is refused
            // before its first byte instead of leaving a length prefix without a payload.
            if (buffer.maxWritableBytes() < written) {
                throw new FrameException("Frame of " + written + " bytes does not fit the remaining capacity of the buffer", null);
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(scratch, scratch.readerIndex(), size);
            } catch (RuntimeException failure) {
                // Nothing of this frame stays readable even if the buffer misbehaves after the check.
                buffer.writerIndex(start);
                throw new FrameException("Frame could not be written to the buffer", failure);
            }
        } finally {
            scratch.release();
        }
    }

    /** Reads exactly one frame written by {@link #writeFramed} from the front of {@code buffer}. */
    static <T> T readFramed(RegistryFriendlyByteBuf buffer, StreamCodec<RegistryFriendlyByteBuf, T> codec,
                            int maxFrameBytes) {
        if (buffer == null) throw new FrameException("Missing frame buffer", null);
        if (codec == null) throw new FrameException("Missing payload codec", null);
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new FrameException("Frame length is unreadable", failure);
        }
        if (length < 0) throw new FrameException("Negative frame length " + length, null);
        if (length > maxFrameBytes) {
            throw new FrameException("Frame length " + length + " exceeds the " + maxFrameBytes + "-byte limit", null);
        }
        int available = buffer.readableBytes();
        if (length > available) {
            throw new FrameException("Frame declares " + length + " bytes but only " + available + " remain", null);
        }
        RegistryFriendlyByteBuf frame = frame(buffer, buffer.readSlice(length));
        T value;
        try {
            value = codec.decode(frame);
        } catch (RuntimeException failure) {
            throw new FrameException("Frame could not be decoded", failure);
        }
        int trailing = frame.readableBytes();
        if (trailing != 0) throw new FrameException("Frame carries " + trailing + " trailing bytes", null);
        return value;
    }

    /**
     * Wraps {@code bytes} in a buffer that keeps the caller's registry context, so a payload that names
     * registered things still resolves. The wrapper shares the caller's reference count: it is borrowed,
     * never released.
     */
    private static RegistryFriendlyByteBuf frame(RegistryFriendlyByteBuf context, ByteBuf bytes) {
        return new RegistryFriendlyByteBuf(bytes, context.registryAccess(), context.getConnectionType());
    }

    /** A malformed or over-sized frame; the underlying codec failure is kept as the cause. */
    static final class FrameException extends IllegalArgumentException {
        FrameException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
