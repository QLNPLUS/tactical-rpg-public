package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full battle state snapshot, server to client.
 *
 * <p>The actor, the turn order and the held weapon travel as native {@link Component}s, not as text the
 * server already resolved: an unnamed mob keeps its registry key and is read in the receiving player's own
 * language, while a profile name, a renamed item or a custom name stays exactly the text somebody typed.
 * Nothing on the producing side calls {@code getString()} to build them.</p>
 *
 * <p>One snapshot is <em>not</em> assumed to fit one message. The reachable set alone is a geometric
 * quantity, and the full logical state of a large configured zone is far above any single payload limit,
 * so this record is the <em>logical</em> model and its wire form is a bounded body that
 * {@link BattleStateFragmentPacket} carries in slices. {@link #STREAM_CODEC} is that whole-body codec: the
 * producer measures with it, the receiver's reassembler decodes exactly one reassembled body with it, and
 * the unit tests drive it directly. A body is never sent as one payload.</p>
 *
 * <p>Every count inside the body is checked before anything is allocated: the two per-person arrays keep
 * the legacy 256 limit, the reachable set is bounded by the built-in zone geometry (the largest radius and
 * half height this mod can be configured with, which is a <em>network</em> ceiling and never changes what
 * the game itself offers), and the participant list is counted in whole {@code (entityId, hp, maxHp)}
 * triples. A declared count also has to fit the bytes that remain in the body, so a hostile header cannot
 * ask for an allocation it did not pay for.</p>
 */
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
        Component actor,
        List<Component> order,
        int cursor,
        Component weapon,
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
        int breakCost,
        int apRecovery,
        int paidActionCount,
        /**
         * True while this encounter is holding units in a forced fall.
         *
         * <p>It is the authoritative "wait for the units to land" state: the commands are refused with it
         * and the aiming page shows its own waiting line from it. The last gameplay field before the
         * stamps, so a snapshot reads as "what the encounter is doing, then which encounter it is".</p>
         */
        boolean terrainFalling,
        java.util.UUID sessionId,
        long turnEpoch,
        long membershipServerEpoch,
        long membershipGeneration,
        /**
         * The bounded command projection for this one recipient: the companion it may order right now, with
         * the companion's own resources, costs, guard, abilities and feet cell.
         *
         * <p>It is a separate block on purpose. The player's own cell and resources stay exactly where they
         * were, so nothing in this payload can be read as "the player is standing on the wolf", and a
         * recipient who is not commanding anything gets {@link Command#NONE} - never somebody else's
         * projection. At most one reachable set is ever on the wire: while commanding, the {@link #reachable}
         * array carries the companion's cells.</p>
         */
        Command command
) implements CustomPacketPayload, MemberScoped {

    /**
     * One recipient's commandable companion.
     *
     * <p>Everything here is server-produced and re-validated on every intent; the client only displays it
     * and names the order it wants. {@code abilities} is a {@code CompanionAbility} mask, so a companion that
     * offers fewer orders cannot be offered them by a stale client either.</p>
     */
    public record Command(
            boolean commanding,
            java.util.UUID companionId,
            int companionEntityId,
            Component name,
            int hp,
            int maxHp,
            int ap,
            int maxAp,
            int moveCost,
            int attackCost,
            int apRecovery,
            int paidActions,
            boolean defending,
            boolean moving,
            int weaponKind,
            int weaponRange,
            Component weapon,
            int abilities,
            int cellX,
            int cellY,
            int cellZ
    ) {
        /** The projection of a recipient that is not commanding a companion. */
        public static final Command NONE = new Command(false, null, -1, Component.empty(), 0, 0, 0, 0, 0, 0, 0, 0,
                false, false, 0, 0, Component.empty(), 0, 0, 0, 0);

        public Command {
            name = name == null ? Component.empty() : name;
            weapon = weapon == null ? Component.empty() : weapon;
            if (!commanding) {
                companionId = null;
                companionEntityId = -1;
                abilities = 0;
            }
        }

        /** True when this order is offered by the companion right now. */
        public boolean allows(int abilityOrdinal) {
            return commanding && (abilities & com.tactical.neoforge.api.companion.CompanionAbility.maskOf(abilityOrdinal)) != 0;
        }
    }

    /**
     * Ceiling of one logical state body, in wire bytes.
     *
     * <p>It is set from the largest zone this mod can be configured with: a 129<sup>3</sup> box of cells,
     * three coordinates each, five bytes per coordinate ({@code 129^3 * 3 * 5} = 32.2 MB), plus 256 names
     * of at most {@link ComponentIO#MAX_FRAME_BYTES} and the scalar fields. It is a network bound only: the
     * game's own zone, path and AP rules are untouched, and no reachable cell is ever dropped.</p>
     */
    public static final int MAX_BODY_BYTES = 36 * 1024 * 1024;

    /**
     * Ceiling of one framed body: the body plus the largest VarInt length prefix that can precede it.
     *
     * <p>The transport slices exactly this framed form, so the reassembler can hand its joined bytes to
     * {@link #STREAM_CODEC} without a second framing path.</p>
     */
    public static final int MAX_FRAMED_BYTES = MAX_BODY_BYTES + 5;

    /** The legacy per-person limit of the turn order arrays, kept as the network limit. */
    public static final int MAX_PER_PERSON = 256;

    /**
     * Largest reachable coordinate count one body may declare.
     *
     * <p>Three coordinates per cell for every cell of the largest configurable zone (radius and half height
     * both 64, so a 129<sup>3</sup> box). This is a transport ceiling, not a rule: it neither shrinks the
     * offered reachable set nor changes any configuration value.</p>
     */
    public static final int MAX_REACHABLE_COORDS = 129 * 129 * 129 * 3;

    /**
     * Largest participant array one body may declare, in whole triples.
     *
     * <p>The roster limit and the hostile cap say nothing about how many entities one encounter may hold
     * (the roster also carries non-hostile mobs), so this is an explicit transport ceiling rather than a
     * capacity rule.</p>
     */
    public static final int MAX_PARTICIPANT_INTS = 3 * 4096;

    public BattleStatePacket {
        actor = actor == null ? Component.empty() : actor;
        weapon = weapon == null ? Component.empty() : weapon;
        command = command == null ? Command.NONE : command;
        order = order == null ? List.of() : List.copyOf(order);
        if (order.size() > MAX_PER_PERSON) throw new IllegalArgumentException("Too many turn-order rows");
        if (turnEntityIds != null) {
            if (turnEntityIds.length > MAX_PER_PERSON) throw new IllegalArgumentException("Invalid charge order size");
            if (gauges != null && gauges.length > MAX_PER_PERSON) throw new IllegalArgumentException("Invalid gauge size");
            // When both per-person arrays are present they describe the same people, and the order names
            // them. A legacy snapshot may leave them empty; nothing may disagree.
            if (turnEntityIds.length > 0 && gauges != null && gauges.length > 0 && gauges.length != turnEntityIds.length) {
                throw new IllegalArgumentException("Per-person arrays disagree");
            }
            if (turnEntityIds.length > 0 && !order.isEmpty() && turnEntityIds.length != order.size()) {
                throw new IllegalArgumentException("Turn order names disagree with the per-person ids");
            }
            if (gauges != null && gauges.length > 0 && !order.isEmpty() && gauges.length != order.size()) {
                throw new IllegalArgumentException("Turn order names disagree with the gauges");
            }
        }
        if (participants != null && participants.length % 3 != 0) {
            throw new IllegalArgumentException("Participant array is not a whole number of triples");
        }
        if (reachable != null && reachable.length % 3 != 0) {
            throw new IllegalArgumentException("Reachable array is not a whole number of cells");
        }
    }

    /**
     * Source-compatible form for producers that carry no command projection.
     *
     * <p>The projection is a separate block, so a snapshot of a plain encounter is exactly the array of
     * fields it always was plus {@link Command#NONE}.</p>
     */
    public BattleStatePacket(boolean active,int phase,int round,int ap,int maxAp,int moveCost,
        int playerX,int playerY,int playerZ,int[] reachable,Component actor,List<Component> order,int cursor,
        Component weapon,int weaponKind,int weaponRange,int centerX,int centerY,int centerZ,int zoneRadius,
        boolean moving,int actorEntityId,int[] participants,int[] gauges,int actionCount,boolean defending,
        int[] turnEntityIds,int attackCost,int itemCost,int placeCost,int breakCost,int apRecovery,
        int paidActionCount,boolean terrainFalling,java.util.UUID sessionId,long turnEpoch,
        long membershipServerEpoch,long membershipGeneration) {
        this(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,actor,order,cursor,weapon,
            weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,moving,actorEntityId,participants,gauges,
            actionCount,defending,turnEntityIds,attackCost,itemCost,placeCost,breakCost,apRecovery,paidActionCount,
            terrainFalling,sessionId,turnEpoch,membershipServerEpoch,membershipGeneration,Command.NONE);
    }

    /** Source-compatible constructor for snapshots that do not supply action quotes. */
    public BattleStatePacket(boolean active,int phase,int round,int ap,int maxAp,int moveCost,
        int playerX,int playerY,int playerZ,int[] reachable,String actor,List<String> order,int cursor,
        String weapon,int weaponKind,int weaponRange,int centerX,int centerY,int centerZ,int zoneRadius,
        boolean moving,int actorEntityId,int[] participants,int[] gauges,int actionCount,boolean defending,int[] turnEntityIds) {
        this(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,literal(actor),
            literals(order),cursor,literal(weapon),weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,
            moving,actorEntityId,participants,gauges,actionCount,defending,turnEntityIds,10,8,10,10,50,0,false,null,0L,0L,0L);
    }

    public BattleStatePacket(boolean active,int phase,int round,int ap,int maxAp,int moveCost,
        int playerX,int playerY,int playerZ,int[] reachable,String actor,List<String> order,int cursor,
        String weapon,int weaponKind,int weaponRange,int centerX,int centerY,int centerZ,int zoneRadius,
        boolean moving,int actorEntityId,int[] participants,int[] gauges,int actionCount,boolean defending) {
        this(active,phase,round,ap,maxAp,moveCost,playerX,playerY,playerZ,reachable,literal(actor),
            literals(order),cursor,literal(weapon),weaponKind,weaponRange,centerX,centerY,centerZ,zoneRadius,
            moving,actorEntityId,participants,gauges,actionCount,defending,new int[0],10,8,10,10,50,0,false,null,0L,0L,0L);
    }

    /**
     * The compatibility form: a string is text, never a translation key.
     *
     * <p>A caller that still holds finished text (a fixture, a third-party producer) keeps working, and its
     * text is not silently translated just because it looks like a key.</p>
     */
    private static Component literal(String text) {
        return text == null ? Component.empty() : Component.literal(text);
    }

    private static List<Component> literals(List<String> texts) {
        if (texts == null) return List.of();
        List<Component> converted = new ArrayList<>(texts.size());
        for (String text : texts) converted.add(literal(text));
        return List.copyOf(converted);
    }

    public static final Type<BattleStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tactical.MOD_ID, "battle_state"));

    /** The whole-body codec: bounded, but never a transport of its own (see the class comment). */
    public static final StreamCodec<RegistryFriendlyByteBuf, BattleStatePacket> STREAM_CODEC =
            StreamCodec.of(BattleStatePacket::encode, BattleStatePacket::decode);

    /** No entity identity or state survives a local player's death/respawn boundary. */
    public static BattleStatePacket inactive() {
        return new BattleStatePacket(false, com.tactical.core.combat.BattlePhase.INACTIVE.ordinal(),
                1, 0, 0, 0, 0, 0, 0, new int[0], Component.empty(), List.of(), 0, Component.empty(), 0, 0,
                0, 0, 0, 0, false, -1, new int[0], new int[0], 0, false, new int[0], 0, 0, 0, 0, 0, 0, false, null, 0L, 0L, 0L);
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
                1, 0, 0, 0, 0, 0, 0, new int[0], Component.empty(), List.of(), 0, Component.empty(), 0, 0,
                0, 0, 0, 0, false, -1, new int[0], new int[0], 0, false, new int[0], 0, 0, 0, 0, 0, 0, false,
                session, 0L, 0L, 0L);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, BattleStatePacket packet) {
        if (buffer == null) throw new IllegalArgumentException("Missing state buffer");
        if (packet == null) throw new IllegalArgumentException("Missing state snapshot");
        ByteBuf body = Unpooled.buffer(256, MAX_BODY_BYTES);
        try {
            try {
                writeBody(new RegistryFriendlyByteBuf(body, buffer.registryAccess(), buffer.getConnectionType()), packet);
            } catch (RuntimeException failure) {
                // A name frame, a count or the body budget refused it: nothing of this snapshot may reach
                // the connection, not even its length prefix.
                throw new IllegalArgumentException("Battle state does not fit the " + MAX_BODY_BYTES
                        + "-byte body budget", failure);
            }
            int size = body.readableBytes();
            int written = VarInt.getByteSize(size) + size;
            if (buffer.maxWritableBytes() < written) {
                throw new IllegalArgumentException("Battle state of " + written
                        + " bytes does not fit the remaining capacity of the buffer");
            }
            int start = buffer.writerIndex();
            try {
                buffer.writeVarInt(size);
                buffer.writeBytes(body, body.readerIndex(), size);
            } catch (RuntimeException failure) {
                buffer.writerIndex(start);
                throw new IllegalArgumentException("Battle state could not be written to the buffer", failure);
            }
        } finally {
            body.release();
        }
    }

    private static void writeBody(RegistryFriendlyByteBuf body, BattleStatePacket packet) {
        body.writeBoolean(packet.active);
        body.writeVarInt(packet.phase);
        body.writeVarInt(packet.round);
        body.writeVarInt(packet.ap);
        body.writeVarInt(packet.maxAp);
        body.writeVarInt(packet.moveCost);
        body.writeVarInt(packet.playerX);
        body.writeVarInt(packet.playerY);
        body.writeVarInt(packet.playerZ);
        body.writeVarInt(packet.reachable.length);
        for (int value : packet.reachable) body.writeVarInt(value);
        ComponentIO.write(body, packet.actor);
        body.writeVarInt(packet.order.size());
        for (Component name : packet.order) ComponentIO.write(body, name);
        body.writeVarInt(packet.cursor);
        ComponentIO.write(body, packet.weapon);
        body.writeVarInt(packet.weaponKind);
        body.writeVarInt(packet.weaponRange);
        body.writeVarInt(packet.centerX);
        body.writeVarInt(packet.centerY);
        body.writeVarInt(packet.centerZ);
        body.writeVarInt(packet.zoneRadius);
        body.writeBoolean(packet.moving);
        body.writeVarInt(packet.actorEntityId);
        body.writeVarInt(packet.participants.length);
        for (int value : packet.participants) body.writeVarInt(value);
        body.writeVarInt(packet.gauges.length);
        for (int value : packet.gauges) body.writeVarInt(value);
        body.writeVarInt(packet.actionCount);
        body.writeBoolean(packet.defending);
        body.writeVarInt(packet.turnEntityIds.length);
        for (int id : packet.turnEntityIds) body.writeVarInt(id);
        body.writeVarInt(packet.attackCost);
        body.writeVarInt(packet.itemCost);
        body.writeVarInt(packet.placeCost);
        body.writeVarInt(packet.breakCost);
        body.writeVarInt(packet.apRecovery);
        body.writeVarInt(packet.paidActionCount);
        // One boolean follows the quotes: the encounter's own busy state, before its identifiers.
        body.writeBoolean(packet.terrainFalling);
        // Session binding: a client echoes this UUID plus the turn epoch in every intent.
        body.writeBoolean(packet.sessionId != null);
        if (packet.sessionId != null) body.writeUUID(packet.sessionId);
        body.writeVarLong(packet.turnEpoch);
        // Membership stamp: the recipient's generation at the moment this payload was sent, plus the
        // server instance it belongs to, so a restarted server is not mistaken for a stale packet.
        body.writeVarLong(packet.membershipServerEpoch);
        body.writeVarLong(packet.membershipGeneration);
        writeCommand(body, packet.command);
    }

    /** The command projection, always written last so every earlier field keeps its offset. */
    private static void writeCommand(RegistryFriendlyByteBuf body, Command command) {
        Command value = command == null ? Command.NONE : command;
        body.writeBoolean(value.commanding());
        if (!value.commanding()) return;
        body.writeBoolean(value.companionId() != null);
        if (value.companionId() != null) body.writeUUID(value.companionId());
        body.writeVarInt(value.companionEntityId());
        ComponentIO.write(body, value.name());
        body.writeVarInt(Math.max(0, value.hp()));
        body.writeVarInt(Math.max(0, value.maxHp()));
        body.writeVarInt(Math.max(0, value.ap()));
        body.writeVarInt(Math.max(0, value.maxAp()));
        body.writeVarInt(Math.max(0, value.moveCost()));
        body.writeVarInt(Math.max(0, value.attackCost()));
        body.writeVarInt(Math.max(0, value.apRecovery()));
        body.writeVarInt(Math.max(0, value.paidActions()));
        body.writeBoolean(value.defending());
        body.writeBoolean(value.moving());
        body.writeVarInt(value.weaponKind());
        body.writeVarInt(Math.max(0, value.weaponRange()));
        ComponentIO.write(body, value.weapon());
        body.writeVarInt(value.abilities());
        body.writeVarInt(value.cellX());
        body.writeVarInt(value.cellY());
        body.writeVarInt(value.cellZ());
    }

    private static Command readCommand(RegistryFriendlyByteBuf body) {
        if (!body.readBoolean()) return Command.NONE;
        java.util.UUID id = body.readBoolean() ? body.readUUID() : null;
        int entityId = body.readVarInt();
        Component name = ComponentIO.read(body);
        int hp = readNonnegative(body);
        int maxHp = readNonnegative(body);
        int ap = readNonnegative(body);
        int maxAp = readNonnegative(body);
        int moveCost = readNonnegative(body);
        int attackCost = readNonnegative(body);
        int recovery = readNonnegative(body);
        int paidActions = readNonnegative(body);
        boolean defending = body.readBoolean();
        boolean moving = body.readBoolean();
        int weaponKind = body.readVarInt();
        int weaponRange = readNonnegative(body);
        Component weapon = ComponentIO.read(body);
        int abilities = body.readVarInt();
        int cellX = body.readVarInt();
        int cellY = body.readVarInt();
        int cellZ = body.readVarInt();
        return new Command(true, id, entityId, name, hp, maxHp, ap, maxAp, moveCost, attackCost, recovery,
                paidActions, defending, moving, weaponKind, weaponRange, weapon, abilities, cellX, cellY, cellZ);
    }

    private static BattleStatePacket decode(RegistryFriendlyByteBuf buffer) {
        if (buffer == null) throw new IllegalArgumentException("Missing state buffer");
        int length;
        try {
            length = buffer.readVarInt();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Battle state length is unreadable", failure);
        }
        if (length < 0) throw new IllegalArgumentException("Negative battle state length");
        if (length > MAX_BODY_BYTES) throw new IllegalArgumentException("Battle state exceeds the body budget");
        int available = buffer.readableBytes();
        if (length > available) throw new IllegalArgumentException("Battle state declares more bytes than remain");
        RegistryFriendlyByteBuf body = new RegistryFriendlyByteBuf(buffer.readSlice(length),
                buffer.registryAccess(), buffer.getConnectionType());
        BattleStatePacket packet = readBody(body);
        int trailing = body.readableBytes();
        if (trailing != 0) throw new IllegalArgumentException("Battle state carries " + trailing + " trailing bytes");
        return packet;
    }

    private static BattleStatePacket readBody(RegistryFriendlyByteBuf body) {
        boolean active = body.readBoolean();
        int phase = body.readVarInt();
        int round = body.readVarInt();
        int ap = body.readVarInt();
        int maxAp = body.readVarInt();
        int moveCost = body.readVarInt();
        int playerX = body.readVarInt();
        int playerY = body.readVarInt();
        int playerZ = body.readVarInt();
        int reachableCount = count(body, MAX_REACHABLE_COORDS, "reachable");
        if (reachableCount % 3 != 0) throw new IllegalArgumentException("Reachable count is not a whole number of cells");
        int[] reachable = new int[reachableCount];
        for (int i = 0; i < reachableCount; i++) reachable[i] = body.readVarInt();
        Component actor = ComponentIO.read(body);
        int orderSize = count(body, MAX_PER_PERSON, "turn order");
        List<Component> order = new ArrayList<>(orderSize);
        for (int i = 0; i < orderSize; i++) order.add(ComponentIO.read(body));
        int cursor = body.readVarInt();
        Component weapon = ComponentIO.read(body);
        int weaponKind = body.readVarInt();
        int weaponRange = body.readVarInt();
        int centerX = body.readVarInt();
        int centerY = body.readVarInt();
        int centerZ = body.readVarInt();
        int zoneRadius = body.readVarInt();
        boolean moving = body.readBoolean();
        int actorEntityId = body.readVarInt();
        int participantCount = count(body, MAX_PARTICIPANT_INTS, "participants");
        if (participantCount % 3 != 0) throw new IllegalArgumentException("Participant count is not a whole number of triples");
        int[] participants = new int[participantCount];
        for (int i = 0; i < participantCount; i++) participants[i] = body.readVarInt();
        int gaugeCount = count(body, MAX_PER_PERSON, "gauges");
        int[] gauges = new int[gaugeCount];
        for (int i = 0; i < gaugeCount; i++) gauges[i] = body.readVarInt();
        int actionCount = body.readVarInt();
        boolean defending = body.readBoolean();
        int idsCount = count(body, MAX_PER_PERSON, "charge order");
        int[] turnEntityIds = new int[idsCount];
        for (int i = 0; i < idsCount; i++) turnEntityIds[i] = body.readVarInt();
        int attackCost = readNonnegative(body);
        int itemCost = readNonnegative(body);
        int placeCost = readNonnegative(body);
        int breakCost = readNonnegative(body);
        int recovery = readNonnegative(body);
        int paidActions = readNonnegative(body);
        boolean terrainFalling = body.readBoolean();
        java.util.UUID sessionId = body.readBoolean() ? body.readUUID() : null;
        long turnEpoch = body.readVarLong();
        long membershipServerEpoch = body.readVarLong();
        long membershipGeneration = body.readVarLong();
        Command command = readCommand(body);
        return new BattleStatePacket(active, phase, round, ap, maxAp, moveCost,
                playerX, playerY, playerZ, reachable, actor, order, cursor,
                weapon, weaponKind, weaponRange, centerX, centerY, centerZ, zoneRadius,
                moving, actorEntityId, participants, gauges, actionCount, defending, turnEntityIds,
                attackCost, itemCost, placeCost, breakCost, recovery, paidActions, terrainFalling,
                sessionId, turnEpoch,
                membershipServerEpoch, membershipGeneration, command);
    }

    /**
     * One declared count, checked before anything is allocated for it.
     *
     * <p>Every element of every collection here costs at least one wire byte, so a count larger than the
     * bytes left in the body is a lie and is refused instead of being trusted.</p>
     */
    private static int count(RegistryFriendlyByteBuf body, int maximum, String what) {
        int declared = body.readVarInt();
        if (declared < 0 || declared > maximum) throw new IllegalArgumentException("Invalid " + what + " count");
        if (declared > body.readableBytes()) throw new IllegalArgumentException(what + " count exceeds the remaining bytes");
        return declared;
    }

    private static int readNonnegative(RegistryFriendlyByteBuf buffer) {
        int value=buffer.readVarInt();
        if(value<0)throw new IllegalArgumentException("Negative AP quote");
        return value;
    }

    /**
     * The framed wire form of one snapshot, ready to be sliced.
     *
     * <p>This is the same byte sequence {@link #STREAM_CODEC} writes, produced here so the splitter and the
     * reassembler cannot drift apart: whatever is cut is exactly what the codec decodes once joined. A
     * snapshot that does not fit the body budget fails here, before any slice exists.</p>
     */
    public static byte[] wireBytes(net.minecraft.core.RegistryAccess registries,
                                   net.neoforged.neoforge.network.connection.ConnectionType connectionType,
                                   BattleStatePacket packet) {
        if (registries == null) throw new IllegalArgumentException("Missing registry context");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, MAX_FRAMED_BYTES), registries,
                connectionType == null ? net.neoforged.neoforge.network.connection.ConnectionType.OTHER : connectionType);
        try {
            STREAM_CODEC.encode(buffer, packet);
            int size = buffer.readableBytes();
            byte[] wire = new byte[size];
            buffer.getBytes(buffer.readerIndex(), wire);
            return wire;
        } finally {
            buffer.release();
        }
    }

    /** Decode one joined framed body; the caller has already bounded {@code length}. */
    public static BattleStatePacket fromWireBytes(net.minecraft.core.RegistryAccess registries,
                                                 net.neoforged.neoforge.network.connection.ConnectionType connectionType,
                                                 byte[] wire, int length) {
        if (registries == null) throw new IllegalArgumentException("Missing registry context");
        if (wire == null || length <= 0 || length > wire.length) throw new IllegalArgumentException("Invalid joined body length");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(wire, 0, length), registries,
                connectionType == null ? net.neoforged.neoforge.network.connection.ConnectionType.OTHER : connectionType);
        try {
            BattleStatePacket packet = STREAM_CODEC.decode(buffer);
            int trailing = buffer.readableBytes();
            if (trailing != 0) throw new IllegalArgumentException("Joined battle state carries " + trailing + " trailing bytes");
            return packet;
        } finally {
            buffer.release();
        }
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
                placeCost,breakCost,apRecovery,paidActionCount,terrainFalling,sessionId,turnEpoch,serverEpoch,
                generation,command);
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
