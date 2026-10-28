package com.tactical.neoforge.network;

import com.tactical.neoforge.battle.multi.ActionStamp;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire form of the session/turn authority token carried by every client intent.
 * The server never derives an actor from a payload; it only uses this to reject a
 * request from an old session or an old turn generation.
 */
final class StampIO {

    private StampIO() { }

    static void write(FriendlyByteBuf buffer, ActionStamp stamp) {
        if (stamp == null) throw new IllegalArgumentException("Missing action stamp");
        buffer.writeUUID(stamp.sessionId());
        buffer.writeVarLong(stamp.turnEpoch());
    }

    static ActionStamp read(FriendlyByteBuf buffer) {
        return new ActionStamp(buffer.readUUID(), buffer.readVarLong());
    }
}
