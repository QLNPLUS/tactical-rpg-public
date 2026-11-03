package com.tactical.neoforge.network;

import com.tactical.core.progression.CharacterStat;
import com.tactical.core.progression.ProgressionReason;
import com.tactical.neoforge.Tactical;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * The authoritative character state of exactly one player, answered only to that player.
 *
 * <p>Everything the page needs is here as the server's own numbers: the level and its cap, the historical
 * grant, the unspent balance, the six invested points, the six displayed values, the actual derived values
 * read back from the player's real attributes, the spendable vanilla XP, the price of the next level, the
 * revision a request has to echo, and whether changing anything is allowed right now. A client never
 * computes a price or a derived value from this packet; it renders what arrived.</p>
 *
 * <p>Array layout is the frozen stat order (see {@link CharacterStat}); every array is exactly six entries,
 * copied on the way in and on the way out. {@code derived} holds, in that same order: max health, max MP, the
 * <em>actual maximum AP</em> the {@code tactical:max_ap} attribute produces (150 at its baseline, 153 after
 * one endurance point - the internal percentage stays internal), attack damage, the turn charge multiplier
 * and the skill damage multiplier.</p>
 *
 * <p>{@code pointsPerLevel} is what one level-up grants under the current rules and {@code nextLevelPoints}
 * is what the next confirmation would grant; both are the server's numbers, so a page never hard-codes "+1".
 * {@code coefficients} are the current per-point bonuses, for display only: no client may price anything
 * from them.</p>
 *
 * <p>When the server refuses an intent it answers with this packet as well: the state is the current
 * authoritative one, {@code reasonCode} is the stable refusal code and {@code reason} is its translatable
 * text. A refusal therefore always refreshes the page instead of leaving the client waiting.</p>
 *
 * <p>{@code syncAvailable} separates "these numbers are facts" from "the request was answered". A committed
 * transaction whose live numbers could not be read back still travels with the revision and the stored
 * points it knows, but with {@code syncAvailable=false}: the client must clear its pending wait, must not
 * render the experience or the derived values as real, and must ask for a fresh snapshot instead of
 * replaying anything.</p>
 *
 * <p>{@code draftIncrements}/{@code draftDerived} are the optional draft answer of a preview: the echoed
 * increments and the derived values they would produce. With no draft, {@code draftValid} is false and the
 * draft arrays carry zeros.</p>
 */
public record CharacterStatePacket(UUID owner, int requestId, boolean openScreen, boolean profileValid,
                                   boolean canMutate, boolean syncAvailable, String reasonCode, Component reason,
                                   long revision,
                                   int level, int maxLevel, int statCap, double[] coefficients, int pointsPerLevel,
                                   int nextLevelPoints, int granted, int unspent, long xpAvailable,
                                   long xpToNextLevel, int[] invested, int[] display,
                                   double[] derived, int[] draftIncrements, boolean draftValid,
                                   double[] draftDerived) implements CustomPacketPayload {

    /** Longest refusal code accepted from the wire; the enum names are far shorter. */
    public static final int MAX_CODE_LENGTH = 64;
    /** Largest level/point value a snapshot may carry; the storage format has the same ceiling. */
    public static final int MAX_VALUE = 1_000_000;

    public static final Type<CharacterStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "character_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CharacterStatePacket> STREAM_CODEC =
            StreamCodec.of(CharacterStatePacket::encode, CharacterStatePacket::decode);

    public CharacterStatePacket {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reason, "reason");
        reasonCode = reasonCode == null ? ProgressionReason.OK.name() : reasonCode;
        if (reasonCode.isEmpty() || reasonCode.length() > MAX_CODE_LENGTH)
            throw new IllegalArgumentException("Invalid character state reason code");
        coefficients = exact(coefficients, "coefficients");
        invested = exact(invested, "invested");
        display = exact(display, "display");
        derived = exact(derived, "derived");
        draftIncrements = exact(draftIncrements, "draft increments");
        draftDerived = exact(draftDerived, "draft derived");
        if (requestId < 0) throw new IllegalArgumentException("Invalid character request id");
        if (revision < 0) throw new IllegalArgumentException("Invalid character revision");
        if (level < 0 || level > MAX_VALUE) throw new IllegalArgumentException("Invalid character level");
        if (maxLevel < 1 || maxLevel > MAX_VALUE) throw new IllegalArgumentException("Invalid character level cap");
        if (statCap < 0 || statCap > MAX_VALUE) throw new IllegalArgumentException("Invalid character stat cap");
        if (pointsPerLevel < 0 || pointsPerLevel > MAX_VALUE || nextLevelPoints < 0 || nextLevelPoints > MAX_VALUE)
            throw new IllegalArgumentException("Invalid character point grant");
        if (granted < 0 || granted > MAX_VALUE || unspent < 0 || unspent > MAX_VALUE)
            throw new IllegalArgumentException("Invalid character point ledger");
        if (xpAvailable < 0 || xpToNextLevel < 0) throw new IllegalArgumentException("Invalid character xp");
        for (int value : invested) {
            if (value < 0) throw new IllegalArgumentException("Negative invested point");
        }
        for (int value : draftIncrements) {
            if (value < 0) throw new IllegalArgumentException("Negative draft increment");
        }
        for (double value : derived) requireFinite(value);
        for (double value : draftDerived) requireFinite(value);
        for (double value : coefficients) requireFinite(value);
    }

    private static int[] exact(int[] values, String name) {
        if (values == null || values.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("A character state needs exactly " + CharacterStat.COUNT + " " + name);
        return values.clone();
    }

    private static double[] exact(double[] values, String name) {
        if (values == null || values.length != CharacterStat.COUNT)
            throw new IllegalArgumentException("A character state needs exactly " + CharacterStat.COUNT + " " + name);
        return values.clone();
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite derived value");
    }

    @Override public int[] invested() { return invested.clone(); }
    @Override public double[] coefficients() { return coefficients.clone(); }
    @Override public int[] display() { return display.clone(); }
    @Override public double[] derived() { return derived.clone(); }
    @Override public int[] draftIncrements() { return draftIncrements.clone(); }
    @Override public double[] draftDerived() { return draftDerived.clone(); }

    /** The refusal code as an enum when it is one this version knows. */
    public ProgressionReason refusal() {
        try {
            return ProgressionReason.valueOf(reasonCode);
        } catch (IllegalArgumentException unknown) {
            return ProgressionReason.SERVER_ERROR;
        }
    }

    /** True when this answer applied the request it answers. */
    public boolean accepted() { return refusal().accepted(); }

    /** A copy of this state with a different correlation id, used when one state answers several requests. */
    public CharacterStatePacket withRequest(int requestId, boolean openScreen) {
        return new CharacterStatePacket(owner, requestId, openScreen, profileValid, canMutate, syncAvailable,
                reasonCode, reason,
                revision, level, maxLevel, statCap, coefficients, pointsPerLevel, nextLevelPoints, granted, unspent,
                xpAvailable, xpToNextLevel, invested, display, derived, draftIncrements, draftValid, draftDerived);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CharacterStatePacket packet) {
        buffer.writeUUID(packet.owner);
        buffer.writeVarInt(packet.requestId);
        buffer.writeBoolean(packet.openScreen);
        buffer.writeBoolean(packet.profileValid);
        buffer.writeBoolean(packet.canMutate);
        buffer.writeBoolean(packet.syncAvailable);
        buffer.writeUtf(packet.reasonCode, MAX_CODE_LENGTH);
        ComponentIO.write(buffer, packet.reason);
        buffer.writeVarLong(packet.revision);
        buffer.writeVarInt(packet.level);
        buffer.writeVarInt(packet.maxLevel);
        buffer.writeVarInt(packet.statCap);
        for (double value : packet.coefficients) buffer.writeDouble(value);
        buffer.writeVarInt(packet.pointsPerLevel);
        buffer.writeVarInt(packet.nextLevelPoints);
        buffer.writeVarInt(packet.granted);
        buffer.writeVarInt(packet.unspent);
        buffer.writeVarLong(packet.xpAvailable);
        buffer.writeVarLong(packet.xpToNextLevel);
        writeInts(buffer, packet.invested);
        writeInts(buffer, packet.display);
        for (double value : packet.derived) buffer.writeDouble(value);
        writeInts(buffer, packet.draftIncrements);
        buffer.writeBoolean(packet.draftValid);
        for (double value : packet.draftDerived) buffer.writeDouble(value);
    }

    private static void writeInts(RegistryFriendlyByteBuf buffer, int[] values) {
        buffer.writeVarInt(values.length);
        for (int value : values) buffer.writeVarInt(value);
    }

    private static int[] readInts(RegistryFriendlyByteBuf buffer, String name) {
        int size = buffer.readVarInt();
        if (size != CharacterStat.COUNT)
            throw new IllegalArgumentException("Character state " + name + " carries " + size + " entries");
        int[] values = new int[size];
        for (int index = 0; index < size; index++) values[index] = buffer.readVarInt();
        return values;
    }

    private static double[] readDoubles(RegistryFriendlyByteBuf buffer) {
        double[] values = new double[CharacterStat.COUNT];
        for (int index = 0; index < values.length; index++) values[index] = buffer.readDouble();
        return values;
    }

    private static CharacterStatePacket decode(RegistryFriendlyByteBuf buffer) {
        UUID owner = buffer.readUUID();
        int requestId = buffer.readVarInt();
        boolean openScreen = buffer.readBoolean();
        boolean profileValid = buffer.readBoolean();
        boolean canMutate = buffer.readBoolean();
        boolean syncAvailable = buffer.readBoolean();
        String reasonCode = buffer.readUtf(MAX_CODE_LENGTH);
        Component reason = ComponentIO.read(buffer);
        long revision = buffer.readVarLong();
        int level = buffer.readVarInt();
        int maxLevel = buffer.readVarInt();
        int statCap = buffer.readVarInt();
        double[] coefficients = readDoubles(buffer);
        int pointsPerLevel = buffer.readVarInt();
        int nextLevelPoints = buffer.readVarInt();
        int granted = buffer.readVarInt();
        int unspent = buffer.readVarInt();
        long xpAvailable = buffer.readVarLong();
        long xpToNextLevel = buffer.readVarLong();
        int[] invested = readInts(buffer, "invested");
        int[] display = readInts(buffer, "display");
        double[] derived = readDoubles(buffer);
        int[] draftIncrements = readInts(buffer, "draft increments");
        boolean draftValid = buffer.readBoolean();
        double[] draftDerived = readDoubles(buffer);
        if (buffer.readableBytes() != 0)
            throw new IllegalArgumentException("Character state carries " + buffer.readableBytes() + " trailing bytes");
        return new CharacterStatePacket(owner, requestId, openScreen, profileValid, canMutate, syncAvailable,
                reasonCode, reason,
                revision, level, maxLevel, statCap, coefficients, pointsPerLevel, nextLevelPoints, granted, unspent,
                xpAvailable, xpToNextLevel, invested, display, derived, draftIncrements, draftValid, draftDerived);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
