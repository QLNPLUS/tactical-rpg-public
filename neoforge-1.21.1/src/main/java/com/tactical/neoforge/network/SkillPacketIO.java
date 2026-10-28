package com.tactical.neoforge.network;

import com.tactical.core.grid.GridPos;
import com.tactical.neoforge.skill.SkillTarget;
import net.minecraft.network.FriendlyByteBuf;

/** Shared strict wire bounds for skill-only payloads. */
final class SkillPacketIO {
    static final int MAX_SKILLS = 256, MAX_TILES = 4096, MAX_TARGETS = 4096;
    private SkillPacketIO() { }
    static int size(FriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Skill packet collection exceeds limit");
        return size;
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
}
