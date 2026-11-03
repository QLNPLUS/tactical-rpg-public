package com.tactical.neoforge.network;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.skill.SkillText;
import com.tactical.neoforge.skill.SkillTarget;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.connection.ConnectionType;

/** Shared strict wire bounds for skill-only payloads. */
final class SkillPacketIO {
    static final int MAX_SKILLS = 256, MAX_TILES = 4096, MAX_TARGETS = 4096;
    /**
     * Skills in one state page. A page needs room for three component frames per skill, so this keeps one
     * full page well inside {@link SkillStatePacket#MAX_BODY_BYTES} even when every frame sits at
     * {@link ComponentIO#MAX_FRAME_BYTES} (32 x ~24.6 KiB is the measured worst case).
     */
    static final int MAX_PER_PAGE = 32;
    /** Page ceiling: a state is never allowed to become an unbounded stream of pages. */
    static final int MAX_PAGES = 256;
    /**
     * Capacity of the scratch buffer used to measure one component frame: the payload limit plus the
     * largest VarInt length prefix that can precede it, so a payload of exactly the limit is measurable
     * rather than refused by its own prefix.
     */
    private static final int MAX_FRAME_WIRE_BYTES = ComponentIO.MAX_FRAME_BYTES + 5;

    private SkillPacketIO() { }

    static int size(FriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Skill packet collection exceeds limit");
        return size;
    }

    /**
     * How many pages one complete state occupies when every page may hold at most
     * {@link #MAX_PER_PAGE} skills.
     */
    static int minimumPages(boolean active, int skills) {
        if (!active) return 1;
        return Math.max(1, (skills + MAX_PER_PAGE - 1) / MAX_PER_PAGE);
    }

    /**
     * The value of {@code text} as it would actually travel, or {@code null} when it cannot.
     *
     * <p>The same two-limit discipline the journal rows use: the real frame writer proves the sender's
     * wire limit, and the real frame reader proves the receiver's heap quota and also yields a deep copy,
     * so a mutable component is never kept for a later send. Encoding successfully alone is not enough.
     * </p>
     */
    static Component verified(Component text, RegistryAccess registries) {
        if (text == null || registries == null) return null;
        RegistryFriendlyByteBuf probe = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, MAX_FRAME_WIRE_BYTES), registries, ConnectionType.OTHER);
        try {
            ComponentIO.write(probe, text);
            return ComponentIO.read(probe);
        } catch (RuntimeException refused) {
            return null;
        } finally {
            probe.release();
        }
    }

    static void target(FriendlyByteBuf buffer, SkillTarget target) {
        buffer.writeBoolean(target.entity() != null);
        if (target.entity() != null) buffer.writeUUID(target.entity());
        else {
            buffer.writeVarInt(target.ground().x()); buffer.writeVarInt(target.ground().y()); buffer.writeVarInt(target.ground().z());
        }
        buffer.writeFloat(target.yaw());
    }
    static SkillTarget target(FriendlyByteBuf buffer) {
        if (buffer.readBoolean()) return new SkillTarget(buffer.readUUID(), null, buffer.readFloat());
        GridPos cell = new GridPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        if (Math.abs((long) cell.x()) > 30_000_000 || Math.abs((long) cell.z()) > 30_000_000
                || Math.abs((long) cell.y()) > 1_000_000) throw new IllegalArgumentException("Invalid skill anchor");
        return SkillTarget.ground(cell, buffer.readFloat());
    }
    static void finiteResource(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid skill resource");
    }

    /** The stable short line used when one skill text cannot fit a frame. */
    static Component tooLong() {
        return SkillText.translatable(SkillText.KEY_TEXT_TOO_LONG);
    }
}
