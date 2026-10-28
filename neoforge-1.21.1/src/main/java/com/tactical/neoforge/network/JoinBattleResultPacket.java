package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import net.minecraft.network.FriendlyByteBuf;
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
 */
public record JoinBattleResultPacket(UUID sessionId, BattleEligibility.Denial denial, String message)
        implements CustomPacketPayload {
    public static final Type<JoinBattleResultPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "join_battle_result"));
    public static final int MAX_MESSAGE = 64;
    public static final StreamCodec<FriendlyByteBuf, JoinBattleResultPacket> STREAM_CODEC =
            StreamCodec.of(JoinBattleResultPacket::encode, JoinBattleResultPacket::decode);

    public JoinBattleResultPacket {
        Objects.requireNonNull(sessionId, "sessionId");
        denial = denial == null ? BattleEligibility.Denial.UNRESOLVED : denial;
        if (message == null) message = "";
        if (message.length() > MAX_MESSAGE) message = message.substring(0, MAX_MESSAGE);
    }

    public boolean joined() { return denial == BattleEligibility.Denial.NONE; }

    private static void encode(FriendlyByteBuf buffer, JoinBattleResultPacket packet) {
        buffer.writeUUID(packet.sessionId());
        buffer.writeVarInt(packet.denial().ordinal());
        buffer.writeUtf(packet.message(), MAX_MESSAGE);
    }

    private static JoinBattleResultPacket decode(FriendlyByteBuf buffer) {
        UUID session = buffer.readUUID();
        int ordinal = buffer.readVarInt();
        BattleEligibility.Denial[] values = BattleEligibility.Denial.values();
        if (ordinal < 0 || ordinal >= values.length) throw new IllegalArgumentException("Invalid denial");
        return new JoinBattleResultPacket(session, values[ordinal], buffer.readUtf(MAX_MESSAGE));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
