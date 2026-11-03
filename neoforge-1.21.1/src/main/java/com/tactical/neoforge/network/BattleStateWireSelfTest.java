package com.tactical.neoforge.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleText;
import com.tactical.neoforge.battle.multi.BattleEligibility;
import com.tactical.neoforge.client.BattleMembership;
import com.tactical.neoforge.client.BattleStateAssembler;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disposable dedicated-server fixture for the parted battle state and the discovery reasons:
 * {@code -Dtactical.selftest=battle_state_wire}.
 *
 * <p>Every name in a snapshot is a native component, and a component codec only exists inside a booted
 * game, so the real {@link BattleStatePacket#STREAM_CODEC} is driven here on the server's own registry
 * access:</p>
 *
 * <ol>
 *   <li>one small snapshot is one slice: every scalar, every per-person array, the participant triples,
 *       the session/epoch stamp and the three names survive the real codec;</li>
 *   <li>a snapshot whose body is far above one payload is cut into slices, the slices join back into
 *       exactly the encoded bytes, and the whole state (roster order, AP, reachable set) is unchanged -
 *       which is also what proves two members' own snapshots stay their own;</li>
 *   <li>the largest name set (256 rows at the frame limit) still reassembles and keeps every name;</li>
 *   <li>hostile bodies are refused before an allocation: a negative/over-limit reachable count, a declared
 *       length over the body budget, and a name that does not fit a frame; an encode that fails leaves not
 *       one byte in the caller's buffer;</li>
 *   <li>a slice whose layout disagrees with its own header is refused by the packet itself;</li>
 *   <li>the discovery payloads keep a refusal as structure, the list wording differs from the invitation
 *       wording, and the manager stays the player's literal name;</li>
 *   <li>the shipped language tables resolve the same snapshot differently - a same-runtime two-language
 *       resolver, not two clients on one server.</li>
 * </ol>
 *
 * <p>Every item logs one {@code BATTLE_STATE_WIRE [n] PASS/FAIL <name>} line, the run ends with a single
 * {@code PASSED}/{@code FAILED} marker, and a failure exits the server non-zero. It touches no battle
 * state and starts no client.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleStateWireSelfTest {

    private static final UUID SESSION = new UUID(0x5354415445L, 0x5752454CL);
    /** The companion this fixture projects, distinct from the session and the actor. */
    private static final UUID COMPANION = new UUID(0x434F4D50414EL, 0x494F4EL);
    private static final long EPOCH = 9182L;
    private static final long GENERATION = 12L;
    private static final long PUBLICATION = 4321L;

    private static int checks;
    private static int failures;
    /** Publications handed to the real reassembler; every snapshot needs its own, strictly increasing. */
    private static long publications;

    private BattleStateWireSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!"battle_state_wire".equals(System.getProperty("tactical.selftest", ""))) return;
        MinecraftServer server = event.getServer();
        checks = 0;
        failures = 0;
        Tactical.LOGGER.info("[Tactical] BATTLE_STATE_WIRE beginning: tactical protocol {}, body budget {} bytes, "
                        + "slice {} bytes, at most {} slices", TacticalNetwork.PROTOCOL,
                BattleStatePacket.MAX_BODY_BYTES, BattleStateFragmentPacket.MAX_FRAGMENT_BYTES,
                BattleStateFragmentPacket.MAX_PARTS);
        try {
            protocolChecks();
            singleSliceChecks(server);
            crossSliceChecks(server);
            fullNameSetChecks(server);
            boundChecks(server);
            reassemblyBindingChecks(server);
            fittedNameChecks(server);
            atomicEncodeChecks(server);
            sliceLayoutChecks(server);
            discoveryChecks(server);
            languageChecks();
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] BATTLE_STATE_WIRE SELFTEST FAILED: crashed after {} checks", checks, failure);
            halt(server, 1);
            return;
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] BATTLE_STATE_WIRE SELFTEST FAILED: {} of {} checks failed", failures, checks);
            halt(server, 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] BATTLE_STATE_WIRE SELFTEST PASSED: all {} checks on the parted state, "
                + "its bounds, the discovery reasons and the two-language resolver", checks);
        halt(server, 0);
    }

    // ---- 1. the channel version -------------------------------------------------------------

    private static void protocolChecks() {
        check("the shared channel publishes protocol 12", () -> {
            require("12".equals(TacticalNetwork.PROTOCOL),
                    "the companion command projection is a wire change: 11 -> 12");
            require("4".equals(SkillNetwork.PROTOCOL), "the skill channel is untouched");
            require("2".equals(BattleLogNetwork.PROTOCOL), "the journal channel is untouched");
        });
    }

    // ---- 2. one small snapshot is one slice -------------------------------------------------

    private static void singleSliceChecks(MinecraftServer server) {
        check("one snapshot keeps every name, every per-person array and its stamp across the wire", () -> {
            List<Component> order = new ArrayList<>();
            order.add(Component.translatable("entity.minecraft.zombie"));
            order.add(Component.literal("tactical.journal.damage"));
            BattleStatePacket packet = snapshot(0, 1, new int[]{0, 99, 0}, order, new int[]{100, 240},
                    new int[]{72, 12}, new int[]{72, 20, 20, 91, 12, 20});
            BattleStatePacket decoded = roundTrip(server, packet);
            require(decoded.active() && decoded.phase() == packet.phase() && decoded.round() == packet.round(),
                    "the lifecycle fields are unchanged");
            require(decoded.ap() == packet.ap() && decoded.maxAp() == packet.maxAp()
                    && decoded.moveCost() == packet.moveCost(), "the private AP quote is unchanged");
            require(decoded.breakCost() == packet.breakCost() && decoded.breakCost() == 13,
                    "the break price is its own field and survives the wire");
            require(decoded.placeCost() == 10 && decoded.itemCost() == 8 && decoded.attackCost() == 10,
                    "the older quotes keep their own values beside it");
            require(Arrays.equals(decoded.reachable(), packet.reachable()), "the reachable set is unchanged");
            require(Arrays.equals(decoded.gauges(), packet.gauges()), "the gauges are unchanged");
            require(Arrays.equals(decoded.turnEntityIds(), packet.turnEntityIds()), "the per-person ids are unchanged");
            require(Arrays.equals(decoded.participants(), packet.participants()), "the participant triples are unchanged");
            require(decoded.sessionId().equals(SESSION) && decoded.turnEpoch() == 7L
                    && decoded.membershipServerEpoch() == EPOCH
                    && decoded.membershipGeneration() == GENERATION, "the stamps are unchanged");
            require(decoded.order().get(1).getContents() instanceof PlainTextContents literal
                    && "tactical.journal.damage".equals(literal.text()),
                    "a custom name that looks like a key is still text");
            require(decoded.weapon().getContents() instanceof TranslatableContents emptyHand
                    && BattleText.KEY_EMPTY_HAND.equals(emptyHand.getKey()),
                    "an empty hand travels as its own key, not as a frozen word");
            require(decoded.actor().getContents() instanceof TranslatableContents actorKey
                    && "entity.minecraft.zombie".equals(actorKey.getKey()),
                    "an unnamed mob's name stays its registry key");
            int parts = BattleStateFragmentPacket.partsFor(wire(server, packet).length);
            require(parts == 1, "a small snapshot is one slice, not a fragmentation in name only: " + parts);
        });

        check("the command projection survives the wire, and a non-commanding recipient gets none", () -> {
            BattleStatePacket.Command command = new BattleStatePacket.Command(true, COMPANION, 913,
                    Component.translatable("entity.minecraft.wolf"), 20, 40, 88, 150, 12, 13, 50, 2, true, false,
                    0, 1, Component.translatable(BattleText.KEY_NATURAL_WEAPON),
                    com.tactical.neoforge.api.companion.CompanionAbility.all(), 3, 70, -4);
            BattleStatePacket packet = withCommand(snapshot(0, 1, new int[]{3, 70, -4},
                    List.of(Component.translatable("entity.minecraft.wolf")), new int[]{100}, new int[]{913},
                    new int[]{913, 20, 40}), command);
            BattleStatePacket decoded = roundTrip(server, packet);
            BattleStatePacket.Command read = decoded.command();
            require(read.commanding() && COMPANION.equals(read.companionId()) && read.companionEntityId() == 913,
                    "the commanded companion keeps its identity across the wire");
            require(read.hp() == 20 && read.maxHp() == 40 && read.ap() == 88 && read.maxAp() == 150
                    && read.moveCost() == 12 && read.attackCost() == 13 && read.apRecovery() == 50
                    && read.paidActions() == 2, "its own numbers travel as its own block");
            require(read.defending() && !read.moving() && read.weaponKind() == 0 && read.weaponRange() == 1,
                    "its guard, its movement state and its natural weapon travel too");
            require(read.abilities() == com.tactical.neoforge.api.companion.CompanionAbility.all(),
                    "the order set is the server's, not the client's guess");
            require(read.cellX() == 3 && read.cellY() == 70 && read.cellZ() == -4,
                    "its authoritative feet cell travels with it");
            require(read.weapon().getContents() instanceof TranslatableContents natural
                    && BattleText.KEY_NATURAL_WEAPON.equals(natural.getKey()),
                    "an empty-handed companion's weapon stays a key, so each client words it itself");
            // The player's own block is untouched by the projection.
            require(decoded.ap() == packet.ap() && decoded.playerX() == packet.playerX()
                    && decoded.playerZ() == packet.playerZ(),
                    "the owner's own resources and cell are unchanged by the companion block");
            BattleStatePacket plain = withCommand(snapshot(0, 1, new int[]{0, 99, 0},
                    List.of(Component.translatable("entity.minecraft.zombie")), new int[]{100}, new int[]{72},
                    new int[]{72, 20, 20}), BattleStatePacket.Command.NONE);
            require(!roundTrip(server, plain).command().commanding(),
                    "a recipient that commands nothing gets no projection at all");
        });

        check("the forced-fall flag travels with the snapshot, so the panel never guesses", () -> {
            BattleStatePacket falling = snapshot(0, 1, new int[]{0, 99, 0},
                    List.of(Component.translatable("entity.minecraft.zombie")), new int[]{100}, new int[]{72},
                    new int[]{72, 20, 20}, true);
            require(roundTrip(server, falling).terrainFalling(),
                    "a waiting encounter is published as waiting");
            BattleStatePacket landed = snapshot(0, 1, new int[]{0, 99, 0},
                    List.of(Component.translatable("entity.minecraft.zombie")), new int[]{100}, new int[]{72},
                    new int[]{72, 20, 20}, false);
            require(!roundTrip(server, landed).terrainFalling(),
                    "and the same snapshot clears it once the units have landed");
        });
    }

    // ---- 3. a body larger than one payload --------------------------------------------------

    private static void crossSliceChecks(MinecraftServer server) {
        check("a body above one payload is cut into slices that join back to the same state", () -> {
            int[] reachable = new int[120_000];
            for (int index = 0; index < reachable.length; index++) reachable[index] = (index % 60) - 30;
            List<Component> order = new ArrayList<>();
            for (int index = 0; index < 4; index++) order.add(Component.literal("unit-" + index));
            BattleStatePacket packet = snapshot(1, 3, reachable, order, new int[]{10, 20, 30, 40},
                    new int[]{72, 12, 91, 33}, new int[]{72, 20, 20, 91, 12, 20, 33, 5, 5});
            byte[] wire = wire(server, packet);
            int parts = BattleStateFragmentPacket.partsFor(wire.length);
            require(parts > 1, "this body must really be cut: " + parts + " slices for " + wire.length + " bytes");
            for (int index = 0; index < parts; index++) {
                BattleStateFragmentPacket slice = part(wire, parts, index, 1L, packet.active());
                require(slice.payload().length == BattleStateFragmentPacket.bytesOf(wire.length, index),
                        "slice " + index + " covers exactly its own range");
                require(BattleStateFragmentPacket.offsetOf(index) == index * BattleStateFragmentPacket.MAX_FRAGMENT_BYTES,
                        "slice " + index + " starts at its own offset");
            }
            List<BattleStatePacket> applied = publish(server, packet);
            require(applied.size() == 1, "the real reassembler applied the snapshot exactly once: " + applied.size());
            BattleStatePacket decoded = applied.getFirst();
            require(Arrays.equals(decoded.reachable(), packet.reachable()),
                    "the whole reachable set survives, none of it truncated: " + decoded.reachable().length);
            require(Arrays.equals(decoded.turnEntityIds(), packet.turnEntityIds())
                    && Arrays.equals(decoded.gauges(), packet.gauges()), "the per-person arrays survive intact");
            require(decoded.order().get(2).getString().equals("unit-2"),
                    "the roster order keeps its index after reassembly");
            require(decoded.ap() == packet.ap() && decoded.paidActionCount() == packet.paidActionCount(),
                    "AP is one value of one snapshot, never a mixture");
        });

        check("two members' snapshots stay their own after reassembly", () -> {
            List<Component> order = List.of(Component.literal("AAA"), Component.literal("BBB"));
            BattleStatePacket first = snapshot(2, 1, new int[]{0, 99, 0}, order, new int[]{1, 2},
                    new int[]{72, 12}, new int[]{72, 20, 20});
            BattleStatePacket second = snapshot(3, 9, new int[]{5, 99, 5, 6, 99, 6}, order, new int[]{3, 4},
                    new int[]{72, 12}, new int[]{72, 9, 20});
            BattleStatePacket firstDecoded = roundTrip(server, first);
            BattleStatePacket secondDecoded = roundTrip(server, second);
            require(firstDecoded.reachable().length == 3 && secondDecoded.reachable().length == 6,
                    "each recipient keeps its own reachable set");
            require(firstDecoded.participants()[1] == 20 && secondDecoded.participants()[1] == 9,
                    "each recipient keeps its own health mirror");
            require(firstDecoded.paidActionCount() == 2 && secondDecoded.paidActionCount() == 3,
                    "each recipient keeps its own paid-action counter");
            require(firstDecoded.actionCount() == 1 && secondDecoded.actionCount() == 9,
                    "each recipient keeps its own action count");
        });
    }

    // ---- 4. the largest name set ------------------------------------------------------------

    private static void fullNameSetChecks(MinecraftServer server) {
        check("the largest name set reassembles and keeps every name", () -> {
            String text = "名".repeat(2_000);
            List<Component> order = new ArrayList<>();
            for (int index = 0; index < BattleStatePacket.MAX_PER_PERSON; index++) {
                order.add(Component.literal(text + index));
            }
            int[] ids = new int[order.size()];
            int[] gauges = new int[order.size()];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = index;
                gauges[index] = index * 10;
            }
            BattleStatePacket packet = new BattleStatePacket(true, 2, 5, 100, 150, 10, 0, 99, 0,
                    new int[]{0, 99, 0}, Component.translatable("entity.minecraft.zombie"), order, 0,
                    Component.translatable(BattleText.KEY_EMPTY_HAND), 0, 0, 0, 99, 0, 24, false, 72,
                    new int[]{72, 20, 20}, gauges, 4, false, ids, 10, 8, 10, 10, 50, 2, false,SESSION, 7L, EPOCH, GENERATION);
            byte[] wire = wire(server, packet);
            int parts = BattleStateFragmentPacket.partsFor(wire.length);
            require(parts > 1, "a full name set does not fit one slice: " + parts);
            List<BattleStatePacket> applied = publish(server, packet);
            require(applied.size() == 1, "the largest name set was applied exactly once");
            BattleStatePacket decoded = applied.getFirst();
            require(decoded.order().size() == BattleStatePacket.MAX_PER_PERSON,
                    "every turn-order row arrives: " + decoded.order().size());
            for (int index = 0; index < decoded.order().size(); index++) {
                if (!decoded.order().get(index).getString().equals(text + index)) {
                    throw new AssertionError("row " + index + " changed shape across the wire");
                }
            }
            require(decoded.order().get(255).getContents() instanceof PlainTextContents,
                    "a long literal name stays literal");
            Tactical.LOGGER.info("[Tactical] BATTLE_STATE_WIRE largest name set: {} bytes in {} slices",
                    wire.length, parts);
        });
    }

    // ---- 5. refusals before an allocation ---------------------------------------------------

    private static void boundChecks(MinecraftServer server) {
        check("a hostile reachable count is refused before anything is allocated", () -> {
            expectRefused("negative reachable count", hostileFrame(server, -1));
            expectRefused("reachable count above the zone ceiling",
                    hostileFrame(server, BattleStatePacket.MAX_REACHABLE_COORDS + 1));
        });

        check("a declared body length above the budget is refused before a slice is taken", () -> {
            FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
            try {
                frame.writeVarInt(BattleStatePacket.MAX_BODY_BYTES + 1);
                expectRefused("over-budget declared body length", context(server, frame));
            } finally {
                frame.release();
            }
        });

        check("a name that cannot fit a frame is refused, and no half packet is left behind", () -> {
            List<Component> order = List.of(Component.literal("x".repeat(9_000)));
            BattleStatePacket packet = new BattleStatePacket(true, 2, 5, 100, 150, 10, 0, 99, 0,
                    new int[]{0, 99, 0}, Component.literal("actor"), order, 0, Component.literal("bow"),
                    0, 0, 0, 99, 0, 24, false, 72, new int[]{72, 20, 20}, new int[]{1}, 1, false,
                    new int[]{72}, 10, 8, 10, 10, 50, 0, false,SESSION, 7L, EPOCH, GENERATION);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                int before = buffer.writerIndex();
                boolean refused = false;
                try {
                    buffer.writeByte(0x5A); // a marker the failed frame must not disturb
                    before = buffer.writerIndex();
                    BattleStatePacket.STREAM_CODEC.encode(
                            new RegistryFriendlyByteBuf(buffer, server.registryAccess(), ConnectionType.OTHER), packet);
                } catch (RuntimeException expected) {
                    refused = true;
                }
                require(refused, "an over-long name must refuse the snapshot");
                require(buffer.writerIndex() == before, "a refused encode leaves no half packet");
                require(buffer.getByte(before - 1) == 0x5A, "the caller's own bytes are untouched");
            } finally {
                buffer.release();
            }
        });

        check("a body whose counts lie about the remaining bytes is refused", () -> {
            FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
            FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
            try {
                body.writeBoolean(true);
                for (int index = 0; index < 8; index++) body.writeVarInt(0);
                body.writeVarInt(3); // three coordinates that are not there
                int size = body.readableBytes();
                frame.writeVarInt(size);
                frame.writeBytes(body, body.readerIndex(), size);
                expectRefused("reachable count larger than the remaining bytes", context(server, frame));
            } finally {
                body.release();
                frame.release();
            }
        });
    }

    // ---- 5b. what the reassembler itself binds ----------------------------------------------

    private static void reassemblyBindingChecks(MinecraftServer server) {
        check("a settled publication is applied once and never again", () -> {
            List<Component> order = List.of(Component.literal("AAA"));
            BattleStatePacket packet = snapshot(0, 1, new int[]{0, 99, 0}, order, new int[]{1}, new int[]{72},
                    new int[]{72, 20, 20});
            List<BattleStatePacket> applied = new ArrayList<>();
            BattleMembership.reset();
            BattleStateAssembler.reset();
            BattleStateAssembler.decoderForTesting((wire, length) ->
                    BattleStatePacket.fromWireBytes(server.registryAccess(), ConnectionType.OTHER, wire, length));
            BattleStateAssembler.sinkForTesting(snapshot -> {
                applied.add(snapshot);
                return true;
            });
            try {
                byte[] wire = wire(server, packet);
                int parts = BattleStateFragmentPacket.partsFor(wire.length);
                long publication = ++publications;
                BattleStateAssembler.Result first =
                        BattleStateAssembler.accept(part(wire, parts, 0, publication, true));
                require(first == BattleStateAssembler.Result.APPLIED, "a new publication is applied: " + first);
                BattleStateAssembler.Result replay =
                        BattleStateAssembler.accept(part(wire, parts, 0, publication, true));
                require(replay == BattleStateAssembler.Result.REFUSED,
                        "a full replay of a settled publication is refused: " + replay);
                require(applied.size() == 1, "the replay was not applied twice: " + applied.size());
                BattleStateAssembler.Result older =
                        BattleStateAssembler.accept(part(wire, parts, 0, publication - 1, true));
                require(older == BattleStateAssembler.Result.REFUSED, "an older publication is refused too");
            } finally {
                BattleStateAssembler.decoderForTesting(null);
                BattleStateAssembler.sinkForTesting(null);
                BattleStateAssembler.reset();
                BattleMembership.reset();
            }
        });

        check("a body that disagrees with its parts is decoded but not applied", () -> {
            // The parts claim the current membership; the encoded body claims an older generation.
            BattleStatePacket other = new BattleStatePacket(true, 2, 5, 100, 150, 10, 0, 99, 0,
                    new int[]{0, 99, 0}, Component.translatable("entity.minecraft.zombie"),
                    List.of(Component.literal("AAA")), 0, Component.translatable(BattleText.KEY_EMPTY_HAND),
                    0, 0, 0, 99, 0, 24, false, 72, new int[]{72, 20, 20}, new int[]{1}, 1, false,
                    new int[]{72}, 10, 8, 10, 10, 50, 0, false,SESSION, 7L, EPOCH, GENERATION - 1);
            List<BattleStatePacket> applied = new ArrayList<>();
            BattleMembership.reset();
            BattleStateAssembler.reset();
            BattleStateAssembler.decoderForTesting((wire, length) ->
                    BattleStatePacket.fromWireBytes(server.registryAccess(), ConnectionType.OTHER, wire, length));
            BattleStateAssembler.sinkForTesting(snapshot -> {
                applied.add(snapshot);
                return true;
            });
            try {
                byte[] wire = wire(server, other);
                int parts = BattleStateFragmentPacket.partsFor(wire.length);
                long publication = ++publications;
                BattleStateAssembler.Result result = null;
                for (int index = 0; index < parts; index++) {
                    // The header carries the current generation, the body carries the older one.
                    result = BattleStateAssembler.accept(part(wire, parts, index, publication, true));
                }
                require(result == BattleStateAssembler.Result.DECODED_NOT_APPLIED,
                        "the mismatched body was decoded and refused, not applied: " + result);
                require(applied.isEmpty(), "nothing reached the client");
            } finally {
                BattleStateAssembler.decoderForTesting(null);
                BattleStateAssembler.sinkForTesting(null);
                BattleStateAssembler.reset();
                BattleMembership.reset();
            }
        });

        check("a part of a membership this client cannot accept is refused before any allocation", () -> {
            List<Component> order = List.of(Component.literal("AAA"));
            BattleStatePacket packet = snapshot(0, 1, new int[]{0, 99, 0}, order, new int[]{1}, new int[]{72},
                    new int[]{72, 20, 20});
            BattleMembership.reset();
            BattleStateAssembler.reset();
            BattleStateAssembler.decoderForTesting((wire, length) ->
                    BattleStatePacket.fromWireBytes(server.registryAccess(), ConnectionType.OTHER, wire, length));
            BattleStateAssembler.sinkForTesting(snapshot -> true);
            try {
                // Latch a later generation first, then offer an older one with a bigger publication.
                BattleMembership.transition(EPOCH, GENERATION, SESSION, true);
                byte[] wire = wire(server, packet);
                int parts = BattleStateFragmentPacket.partsFor(wire.length);
                long publication = ++publications;
                BattleStateFragmentPacket stale = new BattleStateFragmentPacket(publication, wire.length, parts, 0,
                        true, Arrays.copyOfRange(wire, 0, BattleStateFragmentPacket.bytesOf(wire.length, 0)),
                        SESSION, EPOCH, GENERATION - 1);
                BattleStateAssembler.Result result = BattleStateAssembler.accept(stale);
                require(result == BattleStateAssembler.Result.REFUSED, "an older generation is refused: " + result);
                require(!BattleStateAssembler.holding(), "nothing was allocated for it");
                // The same generation claiming another encounter is a contradiction, not a new snapshot.
                BattleStateFragmentPacket otherSession = new BattleStateFragmentPacket(publication + 1, wire.length,
                        parts, 0, true, Arrays.copyOfRange(wire, 0, BattleStateFragmentPacket.bytesOf(wire.length, 0)),
                        java.util.UUID.randomUUID(), EPOCH, GENERATION);
                require(BattleStateAssembler.accept(otherSession) == BattleStateAssembler.Result.REFUSED,
                        "the same generation with another encounter is refused");
                require(!BattleStateAssembler.holding(), "and still nothing was allocated");
            } finally {
                BattleStateAssembler.decoderForTesting(null);
                BattleStateAssembler.sinkForTesting(null);
                BattleStateAssembler.reset();
                BattleMembership.reset();
            }
        });
    }

    // ---- 5c. the producer's own deep snapshot ------------------------------------------------

    private static void fittedNameChecks(MinecraftServer server) {
        check("a name is never trusted by shape: the frame decides, and the copy is independent", () -> {
            // A short key with an enormous fallback must not slip through as "obviously small".
            Component hugeFallback = Component.translatableWithFallback("tactical.state.weapon.empty",
                    "x".repeat(9_000));
            Component fallback = Component.translatable("entity.minecraft.zombie");
            Component fittedHuge = BattleText.fitted(hugeFallback, fallback);
            require(BattleLogPacket.verifiedSnapshot(fittedHuge, server.registryAccess()) != null,
                    "the chosen alternative really fits a frame");

            // A style can carry large insertion/click data, which the shape test could not see.
            Component styled = Component.literal("blade").withStyle(net.minecraft.network.chat.Style.EMPTY
                    .withInsertion("y".repeat(9_000)));
            Component fittedStyled = BattleText.fitted(styled, fallback);
            require(BattleLogPacket.verifiedSnapshot(fittedStyled, server.registryAccess()) != null,
                    "a huge style also falls back to a line that fits");

            // A normal literal is kept, with its own text.
            Component literal = Component.literal("阿强的剑");
            Component fittedLiteral = BattleText.fitted(literal, fallback);
            require(fittedLiteral.getString().equals("阿强的剑"), "a normal custom name is kept as itself");

            // A nested translation keeps its structure: the argument is still a component.
            Component nested = BattleText.translatable(BattleText.KEY_AI_CREEPER_PREPARE,
                    Component.translatable("entity.minecraft.creeper"));
            Component fittedNested = BattleText.fitted(nested, fallback);
            require(fittedNested.getContents() instanceof TranslatableContents outer
                            && BattleText.KEY_AI_CREEPER_PREPARE.equals(outer.getKey())
                            && outer.getArgs().length == 1
                            && outer.getArgs()[0] instanceof Component argument
                            && argument.getContents() instanceof TranslatableContents inner
                            && "entity.minecraft.creeper".equals(inner.getKey()),
                    "the nested argument is still a key, not a flattened string");

            // The published name is a copy: mutating the original afterwards cannot change it.
            var mutableArgument = Component.literal("原");
            var mutable = Component.translatable(BattleText.KEY_AI_CREEPER_PREPARE, mutableArgument);
            Component stored = BattleText.fitted(mutable, fallback);
            String before = stored.getString();
            mutableArgument.append("被改了");
            ((net.minecraft.network.chat.MutableComponent) mutable).append("也改了");
            require(stored.getString().equals(before),
                    "the stored name did not follow the original component: " + stored.getString());
        });
    }

    // ---- 5d. the two small packets commit once ----------------------------------------------

    private static void atomicEncodeChecks(MinecraftServer server) {
        check("a list whose last reason cannot fit is refused without a half packet", () -> {
            NearbyBattlesPacket packet = new NearbyBattlesPacket(9L, List.of(
                    new NearbyBattlesPacket.Entry(UUID.randomUUID(), "A", 1, 1, 4, true, Component.empty()),
                    new NearbyBattlesPacket.Entry(UUID.randomUUID(), "B", 2, 1, 4, true, Component.empty()),
                    new NearbyBattlesPacket.Entry(UUID.randomUUID(), "C", 3, 1, 4, true, Component.empty()),
                    new NearbyBattlesPacket.Entry(UUID.randomUUID(), "D", 4, 1, 4, false,
                            Component.literal("z".repeat(9_000)))));
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    server.registryAccess(), ConnectionType.OTHER);
            try {
                buffer.writeByte(0x5A);
                int before = buffer.writerIndex();
                boolean refused = false;
                try {
                    NearbyBattlesPacket.STREAM_CODEC.encode(buffer, packet);
                } catch (RuntimeException expected) {
                    refused = true;
                }
                require(refused, "an over-long reason must refuse the whole list");
                require(buffer.writerIndex() == before, "no request id or row reached the buffer");
            } finally {
                buffer.release();
            }
        });

        check("a join answer whose message cannot fit is refused without a half packet", () -> {
            JoinBattleResultPacket packet = new JoinBattleResultPacket(UUID.randomUUID(),
                    BattleEligibility.Denial.BATTLE_FULL, Component.literal("w".repeat(9_000)));
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                    server.registryAccess(), ConnectionType.OTHER);
            try {
                buffer.writeByte(0x5A);
                int before = buffer.writerIndex();
                boolean refused = false;
                try {
                    JoinBattleResultPacket.STREAM_CODEC.encode(buffer, packet);
                } catch (RuntimeException expected) {
                    refused = true;
                }
                require(refused, "an over-long message must refuse the answer");
                require(buffer.writerIndex() == before, "no session id or code reached the buffer");
            } finally {
                buffer.release();
            }
        });

        check("a buffer too small for the answer is refused before its first byte", () -> {
            JoinBattleResultPacket packet = new JoinBattleResultPacket(UUID.randomUUID(),
                    BattleEligibility.Denial.NONE, BattleText.denial(BattleEligibility.Denial.NONE));
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(8, 8),
                    server.registryAccess(), ConnectionType.OTHER);
            try {
                buffer.writeByte(0x5A);
                buffer.writeByte(0x5A);
                int before = buffer.writerIndex();
                boolean refused = false;
                try {
                    JoinBattleResultPacket.STREAM_CODEC.encode(buffer, packet);
                } catch (RuntimeException expected) {
                    refused = true;
                }
                require(refused, "a legal answer cannot fit a 8-byte buffer");
                require(buffer.writerIndex() == before, "the caller's own bytes are untouched");
            } finally {
                buffer.release();
            }
        });

        check("the two small packets still round-trip when they fit", () -> {
            UUID session = UUID.randomUUID();
            NearbyBattlesPacket list = new NearbyBattlesPacket(12L, List.of(
                    new NearbyBattlesPacket.Entry(session, "TacticalA", 3, 1, 4, true, Component.empty())));
            JoinBattleResultPacket answer = new JoinBattleResultPacket(session,
                    BattleEligibility.Denial.BATTLE_FULL, BattleText.denial(BattleEligibility.Denial.BATTLE_FULL));
            require(roundTrip(server, list).entries().getFirst().reason().getString().isEmpty(),
                    "the empty reason survives the round trip");
            require(roundTrip(server, answer).denial() == BattleEligibility.Denial.BATTLE_FULL,
                    "the answer survives the round trip");
        });
    }

    // ---- 6. one slice's own layout ----------------------------------------------------------

    private static void sliceLayoutChecks(MinecraftServer server) {
        check("a slice that disagrees with its own header is refused", () -> {
            expectIllegal("zero-length body", () -> new BattleStateFragmentPacket(PUBLICATION, 0, 0, 0, true,
                    new byte[0], SESSION, EPOCH, GENERATION));
            expectIllegal("body above the budget", () -> new BattleStateFragmentPacket(PUBLICATION,
                    BattleStatePacket.MAX_FRAMED_BYTES + 1, 1, 0, true, new byte[16], SESSION, EPOCH, GENERATION));
            expectIllegal("wrong part count", () -> new BattleStateFragmentPacket(PUBLICATION,
                    BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1, 1, 0, true,
                    new byte[BattleStateFragmentPacket.MAX_FRAGMENT_BYTES], SESSION, EPOCH, GENERATION));
            expectIllegal("index outside the snapshot", () -> new BattleStateFragmentPacket(PUBLICATION,
                    BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1, 2, 2, true,
                    new byte[1], SESSION, EPOCH, GENERATION));
            expectIllegal("slice longer than its range", () -> new BattleStateFragmentPacket(PUBLICATION,
                    BattleStateFragmentPacket.MAX_FRAGMENT_BYTES + 1, 2, 1, true,
                    new byte[2], SESSION, EPOCH, GENERATION));
            expectIllegal("negative publication", () -> new BattleStateFragmentPacket(-1L,
                    BattleStateFragmentPacket.MAX_FRAGMENT_BYTES, 1, 0, true,
                    new byte[BattleStateFragmentPacket.MAX_FRAGMENT_BYTES], SESSION, EPOCH, GENERATION));
            // A short index is one byte; a hand-written frame cannot claim a longer one.
            // expectRefused releases the buffer it is given, so this one is not released again here.
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeVarLong(PUBLICATION);
            buffer.writeVarInt(10);
            buffer.writeVarInt(1);
            buffer.writeVarInt(0);
            buffer.writeBoolean(true);
            buffer.writeBoolean(true);
            buffer.writeUUID(SESSION);
            buffer.writeVarLong(EPOCH);
            buffer.writeVarLong(GENERATION);
            buffer.writeVarInt(10);
            expectRefused("declared slice longer than the frame", buffer);
        });
    }

    // ---- 7. the discovery reasons -----------------------------------------------------------

    private static void discoveryChecks(MinecraftServer server) {
        check("a nearby refusal stays structure and the manager stays the player's own name", () -> {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            Component safeBoundary = BattleText.listDenial(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY);
            NearbyBattlesPacket packet = new NearbyBattlesPacket(11L, List.of(
                    new NearbyBattlesPacket.Entry(first, "TacticalA", 12, 2, 4, true, Component.empty()),
                    new NearbyBattlesPacket.Entry(second, "TacticalB", 40, 4, 4, false, safeBoundary)));
            NearbyBattlesPacket decoded = roundTrip(server, packet);
            require(decoded.requestId() == 11L && decoded.entries().size() == 2, "the list keeps its shape");
            require(decoded.entries().get(1).reason().getContents() instanceof TranslatableContents key
                    && BattleText.KEY_LIST_REFUSAL_NOT_A_SAFE_BOUNDARY.equals(key.getKey()),
                    "the refusal travels as its own key");
            require("TacticalB".equals(decoded.entries().get(1).manager()),
                    "the manager is a player's literal name, never a key");
            require(decoded.entries().get(0).joinable() && !decoded.entries().get(1).joinable(),
                    "the joinable flag is unchanged");
        });

        check("a join answer carries its denial code and its own structured wording", () -> {
            UUID session = UUID.randomUUID();
            JoinBattleResultPacket refused = new JoinBattleResultPacket(session,
                    BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY,
                    BattleText.denial(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY));
            JoinBattleResultPacket decoded = roundTrip(server, refused);
            require(decoded.sessionId().equals(session), "the answer still names the asked encounter");
            require(decoded.denial() == BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY, "the code survives");
            require(!decoded.joined() && decoded.message().getContents() instanceof TranslatableContents key
                    && BattleText.KEY_DENIAL_NOT_A_SAFE_BOUNDARY.equals(key.getKey()),
                    "the invitation wording is the one the server chose");
            for (BattleEligibility.Denial denial : BattleEligibility.Denial.values()) {
                JoinBattleResultPacket one = roundTrip(server,
                        new JoinBattleResultPacket(session, denial, BattleText.denial(denial)));
                require(one.denial() == denial, "every denial keeps its own code: " + denial);
            }
        });
    }

    // ---- 8. the same snapshot in two languages ----------------------------------------------

    private static void languageChecks() {
        Map<String, String> english = langTable("en_us");
        Map<String, String> chinese = langTable("zh_cn");
        check("the same snapshot reads in the client's own language, and text stays text", () -> {
            Component actor = Component.translatable("entity.minecraft.zombie");
            Component custom = Component.literal("阿强的剑");
            Component weapon = Component.translatable(BattleText.KEY_EMPTY_HAND);
            // A vanilla registry key is not in this mod's tables, so what is proved for the mob is that it
            // is still a key - the client's own language pack, not the server, resolves it.
            require(actor.getContents() instanceof TranslatableContents key
                    && "entity.minecraft.zombie".equals(key.getKey()),
                    "the mob's name is still its registry key after the round trip");
            String enWeapon = resolve(english, weapon);
            String zhWeapon = resolve(chinese, weapon);
            require(!enWeapon.equals(zhWeapon), "the empty-hand key was not translated: " + enWeapon);
            require("Empty hand".equals(enWeapon) && "空手".equals(zhWeapon),
                    "unexpected wording: " + enWeapon + " / " + zhWeapon);
            require(resolve(english, custom).equals(resolve(chinese, custom))
                    && resolve(english, custom).equals("阿强的剑"),
                    "a custom name reads the same in both languages");
            Component warning = BattleText.translatable(BattleText.KEY_AI_CREEPER_PREPARE, custom);
            String enWarning = resolve(english, warning);
            String zhWarning = resolve(chinese, warning);
            require(!enWarning.equals(zhWarning), "the AI warning was not translated: " + enWarning);
            require(enWarning.contains("阿强的剑") && zhWarning.contains("阿强的剑"),
                    "the name argument stays the same text in both languages: " + enWarning + " / " + zhWarning);
        });

        check("a list refusal and an invitation refusal are two different lines in both languages", () -> {
            Component list = BattleText.listDenial(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY);
            Component invite = BattleText.denial(BattleEligibility.Denial.NOT_A_SAFE_BOUNDARY);
            require(!resolve(english, list).equals(resolve(english, invite)), "English must distinguish them");
            require(!resolve(chinese, list).equals(resolve(chinese, invite)), "Chinese must distinguish them");
        });
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static BattleStatePacket snapshot(int paidActions, int actionCount, int[] reachable,
                                              List<Component> order, int[] gauges, int[] ids, int[] participants) {
        return snapshot(paidActions, actionCount, reachable, order, gauges, ids, participants, false);
    }

    /** The same fixture with the encounter's own falling flag set, so the wire carries it both ways. */
    private static BattleStatePacket snapshot(int paidActions, int actionCount, int[] reachable,
                                              List<Component> order, int[] gauges, int[] ids, int[] participants,
                                              boolean falling) {
        return new BattleStatePacket(true, 2, 5, 100, 150, 10, 0, 99, 0, reachable,
                Component.translatable("entity.minecraft.zombie"), order, 0,
                Component.translatable(BattleText.KEY_EMPTY_HAND), 0, 0, 0, 99, 0, 24, false, 72,
                participants, gauges, actionCount, false, ids, 10, 8, 10, 13, 50, paidActions, falling, SESSION, 7L,
                EPOCH, GENERATION);
    }

    /** The same fixture snapshot, carrying one command projection. */
    private static BattleStatePacket withCommand(BattleStatePacket base, BattleStatePacket.Command command) {
        return new BattleStatePacket(base.active(), base.phase(), base.round(), base.ap(), base.maxAp(),
                base.moveCost(), base.playerX(), base.playerY(), base.playerZ(), base.reachable(), base.actor(),
                base.order(), base.cursor(), base.weapon(), base.weaponKind(), base.weaponRange(), base.centerX(),
                base.centerY(), base.centerZ(), base.zoneRadius(), base.moving(), base.actorEntityId(),
                base.participants(), base.gauges(), base.actionCount(), base.defending(), base.turnEntityIds(),
                base.attackCost(), base.itemCost(), base.placeCost(), base.breakCost(), base.apRecovery(),
                base.paidActionCount(), base.terrainFalling(), base.sessionId(), base.turnEpoch(),
                base.membershipServerEpoch(), base.membershipGeneration(), command);
    }

    private static byte[] wire(MinecraftServer server, BattleStatePacket packet) {
        return BattleStatePacket.wireBytes(server.registryAccess(), ConnectionType.OTHER, packet);
    }

    private static BattleStatePacket roundTrip(MinecraftServer server, BattleStatePacket packet) {
        return roundTripWire(server, wire(server, packet));
    }

    private static BattleStatePacket roundTripWire(MinecraftServer server, byte[] wire) {
        return BattleStatePacket.fromWireBytes(server.registryAccess(), ConnectionType.OTHER, wire, wire.length);
    }

    /**
     * Publish one snapshot through the real slices and the real client reassembler, on the server.
     *
     * <p>The decoder seam is the real native body codec on the server's registry access and the sink
     * records what would have been applied, so the reassembler's own membership preview, ordering and
     * header/body binding checks run on genuinely encoded bytes - not on hand-assembled arrays.</p>
     */
    private static List<BattleStatePacket> publish(MinecraftServer server, BattleStatePacket packet) {
        List<BattleStatePacket> applied = new ArrayList<>();
        BattleMembership.reset();
        BattleStateAssembler.reset();
        BattleStateAssembler.decoderForTesting((wire, length) ->
                BattleStatePacket.fromWireBytes(server.registryAccess(), ConnectionType.OTHER, wire, length));
        BattleStateAssembler.sinkForTesting(snapshot -> {
            applied.add(snapshot);
            return true;
        });
        try {
            byte[] wire = wire(server, packet);
            int parts = BattleStateFragmentPacket.partsFor(wire.length);
            long publication = ++publications;
            for (int index = 0; index < parts; index++) {
                int offset = BattleStateFragmentPacket.offsetOf(index);
                BattleStateAssembler.accept(part(wire, parts, index, publication, packet.active()));
            }
        } finally {
            BattleStateAssembler.decoderForTesting(null);
            BattleStateAssembler.sinkForTesting(null);
            BattleStateAssembler.reset();
        }
        return applied;
    }

    private static BattleStateFragmentPacket part(byte[] wire, int parts, int index, long publication,
                                                  boolean active) {
        int offset = BattleStateFragmentPacket.offsetOf(index);
        byte[] slice = Arrays.copyOfRange(wire, offset,
                offset + BattleStateFragmentPacket.bytesOf(wire.length, index));
        return new BattleStateFragmentPacket(publication, wire.length, parts, index, active, slice,
                SESSION, EPOCH, GENERATION);
    }

    private static NearbyBattlesPacket roundTrip(MinecraftServer server, NearbyBattlesPacket packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                server.registryAccess(), ConnectionType.OTHER);
        try {
            NearbyBattlesPacket.STREAM_CODEC.encode(buffer, packet);
            NearbyBattlesPacket decoded = NearbyBattlesPacket.STREAM_CODEC.decode(buffer);
            require(buffer.readableBytes() == 0, "the nearby list consumes exactly what it wrote");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static JoinBattleResultPacket roundTrip(MinecraftServer server, JoinBattleResultPacket packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                server.registryAccess(), ConnectionType.OTHER);
        try {
            JoinBattleResultPacket.STREAM_CODEC.encode(buffer, packet);
            JoinBattleResultPacket decoded = JoinBattleResultPacket.STREAM_CODEC.decode(buffer);
            require(buffer.readableBytes() == 0, "the join answer consumes exactly what it wrote");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    /** A body that is only a header and one hostile count: the count check runs before any allocation. */
    private static RegistryFriendlyByteBuf hostileFrame(MinecraftServer server, int declaredReachable) {
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf frame = new FriendlyByteBuf(Unpooled.buffer());
        try {
            body.writeBoolean(true);
            for (int index = 0; index < 8; index++) body.writeVarInt(0);
            body.writeVarInt(declaredReachable);
            int size = body.readableBytes();
            frame.writeVarInt(size);
            frame.writeBytes(body, body.readerIndex(), size);
            return context(server, frame);
        } finally {
            body.release();
        }
    }

    private static RegistryFriendlyByteBuf context(MinecraftServer server, FriendlyByteBuf frame) {
        byte[] bytes = new byte[frame.readableBytes()];
        frame.getBytes(frame.readerIndex(), bytes);
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), server.registryAccess(),
                ConnectionType.OTHER);
    }

    private static void expectRefused(String what, RegistryFriendlyByteBuf buffer) {
        boolean refused = false;
        try {
            BattleStatePacket.STREAM_CODEC.decode(buffer);
        } catch (RuntimeException expected) {
            refused = true;
        } finally {
            buffer.release();
        }
        require(refused, what + " must be refused");
    }

    private static void expectRefused(String what, FriendlyByteBuf buffer) {
        boolean refused = false;
        try {
            BattleStateFragmentPacket.STREAM_CODEC.decode(buffer);
        } catch (RuntimeException expected) {
            refused = true;
        } finally {
            buffer.release();
        }
        require(refused, what + " must be refused");
    }

    private static void expectIllegal(String what, Runnable builder) {
        boolean refused = false;
        try {
            builder.run();
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        require(refused, what + " must be refused");
    }

    private static void check(String name, Runnable body) {
        checks++;
        try {
            body.run();
            Tactical.LOGGER.info("[Tactical] BATTLE_STATE_WIRE [{}] PASS {}", checks, name);
        } catch (Throwable failure) {
            failures++;
            Tactical.LOGGER.error("[Tactical] BATTLE_STATE_WIRE [{}] FAIL {}: {}", checks, name, failure.toString());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void halt(MinecraftServer server, int code) {
        try {
            server.halt(false);
        } finally {
            Runtime.getRuntime().halt(code);
        }
    }

    private static Map<String, String> langTable(String file) {
        Map<String, String> table = new LinkedHashMap<>(Language.getInstance().getLanguageData());
        String path = "/assets/tactical/lang/" + file + ".json";
        try (InputStream input = BattleStateWireSelfTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing language resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                table.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
        if (!table.containsKey(BattleText.KEY_LIST_REFUSAL_NOT_A_SAFE_BOUNDARY)) {
            throw new AssertionError(path + " did not contribute this step's keys");
        }
        return table;
    }

    /** Resolves one component exactly as a display would: the language is swapped, the component is not. */
    private static String resolve(Map<String, String> table, Component text) {
        Language original = Language.getInstance();
        Language.inject(language(table));
        try {
            return text.getString();
        } finally {
            Language.inject(original);
        }
    }

    private static Language language(Map<String, String> table) {
        return new Language() {
            @Override
            public String getOrDefault(String key, String fallback) {
                return table.getOrDefault(key, fallback);
            }

            @Override
            public boolean has(String key) {
                return table.containsKey(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public net.minecraft.util.FormattedCharSequence getVisualOrder(
                    net.minecraft.network.chat.FormattedText text) {
                return sink -> false;
            }
        };
    }
}
