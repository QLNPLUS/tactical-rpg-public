package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * Answer to one {@link JoinBattleIntentPacket}: accepted, or the exact refusal reason.
 *
 * <p>The client refreshes the nearby list after a refusal and never guesses another encounter,
 * so a stale row, a full roster or a lost race cannot silently move the player somewhere else.</p>
 *
 * <p>The message is structure: the refusal code already travels in {@link #denial}, and the wording is
 * carried as its own translation key so the receiving client resolves it in its own language instead of
 * reading a sentence the server assembled in one language. It is not reconstructed on the client either:
 * the list context has its own wording, so the server decides which line belongs to this answer.</p>
 */
public record JoinBattleResultPacket(UUID sessionId, BattleEligibility.Denial denial, Component message)
        implements CustomPacketPayload {
    public static final Type<JoinBattleResultPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "join_battle_result"));
    /** Ceiling of one answer: a session id, the code and one component frame ({@code 16 + 5 + 8197}). */
    public static final int MAX_BODY_BYTES = 9 * 1024;
    public static final StreamCodec<RegistryFriendlyByteBuf, JoinBattleResultPacket> STREAM_CODEC =
            StreamCodec.of(JoinBattleResultPacket::encode, JoinBattleResultPacket::decode);

    public JoinBattleResultPacket {
        Objects.requireNonNull(sessionId, "sessionId");
        denial = denial == null ? BattleEligibility.Denial.UNRESOLVED : denial;
        message = message == null ? Component.empty() : message;
    }

    /** Compatibility form: a caller that still holds finished text keeps it as literal text. */
    public JoinBattleResultPacket(UUID sessionId, BattleEligibility.Denial denial, String message) {
        this(sessionId, denial, message == null || message.isEmpty()
                ? Component.empty() : Component.literal(message));
    }

    public boolean joined() { return denial == BattleEligibility.Denial.NONE; }

    /** One bounded body, committed once, so a message that cannot fit leaves no half answer behind. */
    private static void encode(RegistryFriendlyByteBuf buffer, JoinBattleResultPacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing join buffer");
        if (packet == null) throw new IllegalArgumentException("Missing join answer");
        ByteBuf body = Unpooled.buffer(64, MAX_BODY_BYTES);
        try {
            try {
                RegistryFriendlyByteBuf framed = new RegistryFriendlyByteBuf(body, buffer.registryAccess(),
                        buffer.getConnectionType());
                framed.writeUUID(packet.sessionId());
                framed.writeVarInt(packet.denial().ordinal());
                ComponentIO.write(framed, packet.message());
            } catch (RuntimeException refused) {
                throw new IllegalArgumentException("Join answer does not fit the " + MAX_BODY_BYTES
                        + "-byte body budget", refused);
            }
            int size = body.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                throw new IllegalArgumentException("Join answer of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(body, body.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("Join answer could not be written to the buffer", failure);
            }
        } finally {
            body.release();
        }
    }

    private static JoinBattleResultPacket decode(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing join buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Join answer length is unreadable", failure);
        }
        if (length < 0 || length > MAX_BODY_BYTES) throw new IllegalArgumentException("Join answer exceeds the body budget");
        if (length > buffer.readableBytes()) throw new IllegalArgumentException("Join answer declares more bytes than remain");
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(buffer.readSlice(length),
                buffer.registryAccess(), buffer.getConnectionType());
        UUID session = body.readUUID();
        int ordinal = body.readVarInt();
        BattleEligibility.Denial[] values = BattleEligibility.Denial.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid denial");
        JoinBattleResultPacket packet = new JoinBattleResultPacket(session, values[ordinal], ComponentIO.read(body));
        int trailing = body.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("Join answer carries " + trailing + " trailing bytes");
        return packet;
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
