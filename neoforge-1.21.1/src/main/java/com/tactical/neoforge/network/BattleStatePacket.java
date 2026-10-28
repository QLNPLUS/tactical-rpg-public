package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Full battle state snapshot, server to client. */
public record BattleStatePacket(
        boolean active,
        int phase,
        int round,
        int ap,
        int maxAp,
        int moveCost,
        int playerX,
        int playerY,
        int playerZ,
        int[] reachable,
        String actor,
        List<String> order,
        int cursor,
        String weapon,
        int weaponKind,
        int weaponRange,
        int centerX,
        int centerY,
        int centerZ,
        int zoneRadius,
        boolean moving,
        int actorEntityId,
        int[] participants,
        int[] gauges,
        int actionCount,
        boolean defending,
        int[] turnEntityIds,
        int attackCost,
        int itemCost,
        int placeCost,
        int apRecovery,
        int paidActionCount,
        java.util.UUID sessionId,
        long turnEpoch,
        long membershipServerEpoch,
        long membershipGeneration
) implements CustomPacketPayload, MemberScoped {

    /** Source-compatible constructor for snapshots that do not supply action quotes. */
    public BattleStatePacket(boolean active,int phase,int round,int ap,int maxAp,int moveCost,
        int playerX,int playerY,int playerZ,int[] reachable,String actor,List<String> order,int cursor,
        String weapon,int weaponKind,int weaponRange,int centerX,int centerY,int centerZ,int zoneRadius,
        boolean moving,int actorEntityId,int[] participants,int[] gauges,int actionCount,boolean defending,int[] turnEntityIds) {
        this(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,actor,order,cursor,
            weapon,weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,moving,actorEntityId,
            participants,gauges,actionCount,defending,turnEntityIds,10,8,10,50,0,null,0L,0L,0L);
    }

    public BattleStatePacket(boolean active,int phase,int round,int ap,int maxAp,int moveCost,
        int playerX,int playerY,int playerZ,int[] reachable,String actor,List<String> order,int cursor,
        String weapon,int weaponKind,int weaponRange,int centerX,int centerY,int centerZ,int zoneRadius,
        boolean moving,int actorEntityId,int[] participants,int[] gauges,int actionCount,boolean defending) {
        this(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,actor,order,cursor,
            weapon,weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,moving,actorEntityId,
            participants,gauges,actionCount,defending,new int[0]);
    }

    public static final Type<BattleStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_state"));

    public static final StreamCodec<FriendlyByteBuf, BattleStatePacket> STREAM_CODEC =
            StreamCodec.of(BattleStatePacket::encode, BattleStatePacket::decode);

    /** No entity identity or state survives a local player's death/respawn boundary. */
    public static BattleStatePacket inactive() {
        return new BattleStatePacket(false, com.tactical.core.combat.BattlePhase.INACTIVE.ordinal(),
                1, 0, 0, 0, 0, 0, 0, new int[0], "", List.of(), 0, "", 0, 0,
                0, 0, 0, 0, false, -1, new int[0], new int[0], 0, false, new int[0], 0, 0, 0, 0, 0, null, 0L, 0L, 0L);
    }

    /**
     * Clear that names the encounter it belongs to.
     *
     * <p>A player who has just left one encounter and joined another must not have the old
     * encounter's clear erase the new one, so the client only applies this when it is still showing
     * that same session. A clear with no session id (login/respawn resync) stays unconditional.</p>
     */
    public static BattleStatePacket inactive(UUID session) {
        return new BattleStatePacket(false, com.tactical.core.combat.BattlePhase.INACTIVE.ordinal(),
                1, 0, 0, 0, 0, 0, 0, new int[0], "", List.of(), 0, "", 0, 0,
                0, 0, 0, 0, false, -1, new int[0], new int[0], 0, false, new int[0], 0, 0, 0, 0, 0,
                session, 0L, 0L, 0L);
    }

    private static void encode(FriendlyByteBuf buffer, BattleStatePacket packet) {
        buffer.writeBoolean(packet.active);
        buffer.writeVarInt(packet.phase);
        buffer.writeVarInt(packet.round);
        buffer.writeVarInt(packet.ap);
        buffer.writeVarInt(packet.maxAp);
        buffer.writeVarInt(packet.moveCost);
        buffer.writeVarInt(packet.playerX);
        buffer.writeVarInt(packet.playerY);
        buffer.writeVarInt(packet.playerZ);
        buffer.writeVarInt(packet.reachable.length);
        for (int value : packet.reachable) {
            buffer.writeVarInt(value);
        }
        buffer.writeUtf(packet.actor, 256);
        buffer.writeVarInt(packet.order.size());
        for (String name : packet.order) {
            buffer.writeUtf(name, 256);
        }
        buffer.writeVarInt(packet.cursor);
        buffer.writeUtf(packet.weapon, 128);
        buffer.writeVarInt(packet.weaponKind);
        buffer.writeVarInt(packet.weaponRange);
        buffer.writeVarInt(packet.centerX);
        buffer.writeVarInt(packet.centerY);
        buffer.writeVarInt(packet.centerZ);
        buffer.writeVarInt(packet.zoneRadius);
        buffer.writeBoolean(packet.moving);
        buffer.writeVarInt(packet.actorEntityId);
        buffer.writeVarInt(packet.participants.length);
        for (int value : packet.participants) {
            buffer.writeVarInt(value);
        }
        buffer.writeVarInt(packet.gauges.length);
        for (int value : packet.gauges) {
            buffer.writeVarInt(value);
        }
        buffer.writeVarInt(packet.actionCount);
        buffer.writeBoolean(packet.defending);
        buffer.writeVarInt(packet.turnEntityIds.length);
        for(int id:packet.turnEntityIds) buffer.writeVarInt(id);
        buffer.writeVarInt(packet.attackCost);
        buffer.writeVarInt(packet.itemCost);
        buffer.writeVarInt(packet.placeCost);
        buffer.writeVarInt(packet.apRecovery);
        buffer.writeVarInt(packet.paidActionCount);
        // Session binding: a client echoes this UUID plus the turn epoch in every intent.
        buffer.writeBoolean(packet.sessionId != null);
        if (packet.sessionId != null) buffer.writeUUID(packet.sessionId);
        buffer.writeVarLong(packet.turnEpoch);
        // Membership stamp: the recipient's generation at the moment this payload was sent, plus the
        // server instance it belongs to, so a restarted server is not mistaken for a stale packet.
        buffer.writeVarLong(packet.membershipServerEpoch);
        buffer.writeVarLong(packet.membershipGeneration);
    }

    private static BattleStatePacket decode(FriendlyByteBuf buffer) {
        boolean active = buffer.readBoolean();
        int phase = buffer.readVarInt();
        int round = buffer.readVarInt();
        int ap = buffer.readVarInt();
        int maxAp = buffer.readVarInt();
        int moveCost = buffer.readVarInt();
        int playerX = buffer.readVarInt();
        int playerY = buffer.readVarInt();
        int playerZ = buffer.readVarInt();
        int reachableCount = buffer.readVarInt();
        int[] reachable = new int[reachableCount];
        for (int i = 0; i < reachableCount; i++) {
            reachable[i] = buffer.readVarInt();
        }
        String actor = buffer.readUtf(256);
        int orderSize = buffer.readVarInt();
        List<String> order = new ArrayList<>(orderSize);
        for (int i = 0; i < orderSize; i++) {
            order.add(buffer.readUtf(256));
        }
        int cursor = buffer.readVarInt();
        String weapon = buffer.readUtf(128);
        int weaponKind = buffer.readVarInt();
        int weaponRange = buffer.readVarInt();
        int centerX = buffer.readVarInt();
        int centerY = buffer.readVarInt();
        int centerZ = buffer.readVarInt();
        int zoneRadius = buffer.readVarInt();
        boolean moving = buffer.readBoolean();
        int actorEntityId = buffer.readVarInt();
        int participantCount = buffer.readVarInt();
        int[] participants = new int[participantCount];
        for (int i = 0; i < participantCount; i++) {
            participants[i] = buffer.readVarInt();
        }
        int gaugeCount = buffer.readVarInt();
        int[] gauges = new int[gaugeCount];
        for (int i = 0; i < gaugeCount; i++) {
            gauges[i] = buffer.readVarInt();
        }
        int actionCount = buffer.readVarInt();
        boolean defending = buffer.readBoolean();
        int idsCount=buffer.readVarInt();
        if(idsCount<0||idsCount>256) throw new IllegalArgumentException("Invalid charge order size");
        int[] turnEntityIds=new int[idsCount];for(int i=0;i<idsCount;i++)turnEntityIds[i]=buffer.readVarInt();
        int attackCost=readNonnegative(buffer),itemCost=readNonnegative(buffer),placeCost=readNonnegative(buffer);
        int recovery=readNonnegative(buffer),paidActions=readNonnegative(buffer);
        java.util.UUID sessionId=buffer.readBoolean()?buffer.readUUID():null;
        long turnEpoch=buffer.readVarLong();
        long membershipServerEpoch=buffer.readVarLong();
        long membershipGeneration=buffer.readVarLong();
        return new BattleStatePacket(active, phase, round, ap, maxAp, moveCost,
                playerX, playerY, playerZ, reachable, actor, order, cursor,
                weapon, weaponKind, weaponRange, centerX, centerY, centerZ, zoneRadius,
                moving, actorEntityId, participants, gauges, actionCount, defending,turnEntityIds,
                attackCost,itemCost,placeCost,recovery,paidActions,sessionId,turnEpoch,
                membershipServerEpoch,membershipGeneration);
    }

    private static int readNonnegative(FriendlyByteBuf buffer) {
        int value=buffer.readVarInt();
        if(value<0)throw new IllegalArgumentException("Negative AP quote");
        return value;
    }

    @Override
    public UUID membershipSession() {
        return sessionId;
    }

    @Override
    public BattleStatePacket withMembership(long serverEpoch, long generation) {
        if (serverEpoch == membershipServerEpoch && generation == membershipGeneration) return this;
        return new BattleStatePacket(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,
                actor,order,cursor,weapon,weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,moving,
                actorEntityId,participants,gauges,actionCount,defending,turnEntityIds,attackCost,itemCost,
                placeCost,apRecovery,paidActionCount,sessionId,turnEpoch,serverEpoch,generation);
    }

    @Override
    public long membershipServerEpoch() {
        return membershipServerEpoch;
    }

    @Override
    public long membershipGeneration() {
        return membershipGeneration;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
