package com.tactical.neoforge.battle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.BattleLogState;
import com.tactical.neoforge.network.BattleLogNetwork;
import com.tactical.neoforge.network.BattleLogPacket;
import com.tactical.neoforge.network.ServerPackets;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Disposable dedicated-server fixture for the journal snapshot: {@code -Dtactical.selftest=battle_log}.
 *
 * <p>The native component codec only exists inside a booted game (see {@code ComponentIoSelfTest}), so the
 * journal packet - which is a list of components - can only be driven here. It runs in two phases:</p>
 *
 * <ol>
 *   <li><b>Wire phase</b> (at {@code ServerStartedEvent}): the real {@link BattleLogPacket#STREAM_CODEC} on
 *       the server's own registry access. A row's structure survives the wire; two consecutive snapshots
 *       stay separate; the 64-row worst case stays inside the packet budget; a declared body length, row
 *       count, corrupted component frame or trailing byte is refused; the membership stamp survives; and
 *       the display point resolves one already-decoded row in either shipped language, while a literal name
 *       that merely looks like a key is never translated.</li>
 *   <li><b>Producer phase</b> (20 ticks later, once the level is ticking): the <em>real</em> producer and
 *       the <em>real</em> published snapshot. Journals are written through
 *       {@link BattleJournal#record(String, Component)} / {@code finish}, the resulting snapshot is taken
 *       from the fixture-only publication capture and decoded back through the same codec, so what is
 *       checked is what a client would actually receive. This phase covers the deep-snapshot contract (no
 *       segment or nested argument is lost, and a later mutation cannot rewrite a stored row), the two
 *       limits of one row (a payload of exactly the frame limit is accepted; a row that overflows the wire
 *       or that encodes but would not decode becomes the short localised fallback), and one real damage and
 *       heal event from a one-player/one-mob encounter with no client.</li>
 * </ol>
 *
 * <p>The two-language check is a <em>same-runtime two-language resolver</em>, not two clients on one
 * server: the dedicated server only ships the vanilla {@code en_us} table, so vanilla keys resolve to that
 * same default in both runs, while this mod's own wording differs. A real two-client check belongs to the
 * later batch. Every item logs one {@code BATTLE_LOG [n] PASS/FAIL <name>} line, the run ends with a single
 * {@code PASSED}/{@code FAILED} marker, and a failure exits the server non-zero.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleLogSelfTest {

    private static final UUID SESSION = new UUID(0x4A4F55524EL, 0x4C54455354L);
    private static final String ENTITY_KEY = "entity.minecraft.zombie";
    private static final String KEY_LOOKING = "tactical.journal.damage";
    private static final String CHINESE_LITERAL = "第三方自定义名";
    private static final long EPOCH = 4242L;
    private static final long GENERATION = 9L;
    /** The frame payload limit published by the wire helper, mirrored for the fixture's own arithmetic. */
    private static final int MAX_FRAME_PAYLOAD = 8192;
    /**
     * Payload of an empty literal, from 4a's own measurement: that frame was 4 wire bytes, i.e. a one-byte
     * VarInt prefix plus a 3-byte payload. It is the anchor that turns a measured one-row body into a row
     * payload; the check below re-anchors it against 4a's independent 8000-character measurement.
     */
    private static final int EMPTY_LITERAL_PAYLOAD = 3;
    /** 4a measured an 8000-character literal at 8005 wire bytes, i.e. a 2-byte prefix plus this payload. */
    private static final int REFERENCE_8000_PAYLOAD = 8003;
    private static final int TICK_DELAY = 20;

    private static int checks;
    private static int failures;
    private static boolean phasePending;
    private static int ticks;

    private BattleLogSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!"battle_log".equals(System.getProperty("tactical.selftest", ""))) return;
        MinecraftServer server = event.getServer();
        checks = 0;
        failures = 0;
        ticks = 0;
        phasePending = true;
        Tactical.LOGGER.info("[Tactical] BATTLE_LOG beginning: journal protocol {}, packet budget {} bytes, "
                        + "history cap {} rows",
                BattleLogNetwork.PROTOCOL, BattleLogPacket.MAX_BODY_BYTES, BattleLogPacket.MAX_HISTORY);
        Language original = Language.getInstance();
        try {
            wireChecks(server);
            boundChecks(server);
            languageChecks(server);
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] BATTLE_LOG SELFTEST FAILED: wire phase crashed after {} checks",
                    checks, failure);
            phasePending = false;
            halt(server, 1);
            return;
        } finally {
            // The server keeps its own table; the fixture never leaves a swapped language behind.
            Language.inject(original);
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] BATTLE_LOG SELFTEST FAILED: {} of {} wire checks failed",
                    failures, checks);
            phasePending = false;
            halt(server, 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] BATTLE_LOG wire phase passed ({} checks); producer phase follows",
                checks);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!phasePending || ++ticks != TICK_DELAY) return;
        phasePending = false;
        MinecraftServer server = event.getServer();
        try {
            producerChecks(server);
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] BATTLE_LOG SELFTEST FAILED: producer phase crashed after {} checks",
                    checks, failure);
            halt(server, 1);
            return;
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] BATTLE_LOG SELFTEST FAILED: {} of {} checks failed", failures, checks);
            halt(server, 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] BATTLE_LOG SELFTEST PASSED: all {} checks on the journal wire, the "
                + "producer snapshot and the display resolver", checks);
        halt(server, 0);
    }

    // ---- 1. what a row carries -------------------------------------------------------------

    private static void wireChecks(MinecraftServer server) {
        check("literal row keeps its text verbatim", () -> {
            BattleLogPacket decoded = roundTrip(server, packet(BattleLogPacket.START, 1,
                    List.of(row(1, "battle", Component.literal(CHINESE_LITERAL)))));
            Component text = decoded.history().getFirst().text();
            assertText(text, CHINESE_LITERAL);
            assertEquals("battle", decoded.history().getFirst().category());
        });

        check("keyed row keeps its key and typed arguments", () -> {
            Component row = Component.translatable(BattleJournal.KEY_DAMAGE,
                    Component.translatable(ENTITY_KEY), "3.0",
                    Component.translatable(BattleJournal.KEY_SOURCE_FALL), "12.0", "20.0");
            Component text = roundTrip(server, packet(BattleLogPacket.UPDATE, 1,
                    List.of(row(1, "damage", row)))).history().getFirst().text();
            TranslatableContents contents = translatable(text);
            assertEquals(BattleJournal.KEY_DAMAGE, contents.getKey());
            Object[] args = contents.getArgs();
            assertEquals(5, args.length);
            // A nested translatable cannot be collapsed, so it must come back as its own key.
            if (!(args[0] instanceof Component victim)) {
                throw new AssertionError("the nested entity name was flattened into " + args[0].getClass().getName());
            }
            assertEquals(ENTITY_KEY, translatable(victim).getKey());
            if (!(args[2] instanceof Component source)) {
                throw new AssertionError("the nested source was flattened into " + args[2].getClass().getName());
            }
            assertEquals(BattleJournal.KEY_SOURCE_FALL, translatable(source).getKey());
            assertEquals("3.0", args[1]);
            assertEquals("12.0", args[3]);
            assertEquals("20.0", args[4]);
        });

        check("numeric argument keeps its value", () -> {
            Component row = Component.translatable(BattleJournal.KEY_EFFECT_GAINED,
                    Component.translatable(ENTITY_KEY), Component.translatable("effect.minecraft.poison"), 3,
                    Component.translatable(BattleJournal.KEY_DURATION_SECONDS, 12));
            Object[] args = translatable(roundTrip(server, packet(BattleLogPacket.UPDATE, 1,
                    List.of(row(1, "effect", row)))).history().getFirst().text()).getArgs();
            assertEquals(4, args.length);
            if (!(args[2] instanceof Number level)) throw new AssertionError("the level became " + args[2]);
            assertEquals(3, level.intValue());
            if (!(args[3] instanceof Component duration)) {
                throw new AssertionError("the duration was flattened into " + args[3].getClass().getName());
            }
            Object seconds = translatable(duration).getArgs()[0];
            if (!(seconds instanceof Number secondCount)) {
                throw new AssertionError("the seconds became " + seconds);
            }
            assertEquals(12, secondCount.intValue());
        });

        check("two consecutive snapshots stay separate", () -> {
            BattleLogPacket first = packet(BattleLogPacket.START, 2,
                    List.of(row(1, "battle", Component.translatable(BattleJournal.KEY_START)),
                            row(2, "damage", Component.literal("首条"))));
            BattleLogPacket second = packet(BattleLogPacket.UPDATE, 3,
                    List.of(row(1, "battle", Component.translatable(BattleJournal.KEY_START)),
                            row(2, "damage", Component.literal("首条")),
                            row(3, "heal", Component.translatable(BattleJournal.KEY_HEAL,
                                    Component.translatable(ENTITY_KEY), "4.0"))));
            withBuffer(server, buffer -> {
                BattleLogPacket.STREAM_CODEC.encode(buffer, first);
                BattleLogPacket.STREAM_CODEC.encode(buffer, second);
                BattleLogPacket one = BattleLogPacket.STREAM_CODEC.decode(buffer);
                BattleLogPacket two = BattleLogPacket.STREAM_CODEC.decode(buffer);
                assertEquals(2L, one.sequence());
                assertEquals(BattleLogPacket.START, one.lifecycle());
                assertEquals(2, one.history().size());
                assertEquals(3L, two.sequence());
                assertEquals(BattleLogPacket.UPDATE, two.lifecycle());
                assertEquals(3, two.history().size());
                if (buffer.readableBytes() != 0) throw new AssertionError("snapshots were not consumed exactly");
            });
        });

        check("membership stamp survives the codec", () -> {
            BattleLogPacket decoded = roundTrip(server, new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 1,
                    List.of(row(1, "battle", Component.translatable(BattleJournal.KEY_START))), EPOCH, GENERATION));
            assertEquals(SESSION, decoded.battleId());
            assertEquals(EPOCH, decoded.membershipServerEpoch());
            assertEquals(GENERATION, decoded.membershipGeneration());
        });
    }

    // ---- 2. the packet's own bounds --------------------------------------------------------

    private static void boundChecks(MinecraftServer server) {
        check("the 64-row worst case stays inside the packet budget and the real payload limit", () -> {
            List<BattleLogPacket.Entry> rows = new ArrayList<>(BattleLogPacket.MAX_HISTORY);
            for (int index = 1; index <= BattleLogPacket.MAX_HISTORY; index++) {
                rows.add(row(index, "damage", Component.literal("a".repeat(8000))));
            }
            BattleLogPacket worst = new BattleLogPacket(SESSION, BattleLogPacket.UPDATE,
                    BattleLogPacket.MAX_HISTORY, rows, EPOCH, GENERATION);
            withBuffer(server, buffer -> {
                BattleLogPacket.STREAM_CODEC.encode(buffer, worst);
                int encoded = buffer.readableBytes();
                Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: 64 rows of ~8000-byte literals encoded to "
                        + "{} bytes (body budget {}, clientbound custom payload limit 1048576)", encoded,
                        BattleLogPacket.MAX_BODY_BYTES);
                if (encoded > BattleLogPacket.MAX_BODY_BYTES) {
                    throw new AssertionError(encoded + " bytes exceed the packet budget");
                }
                if (encoded >= 1048576) {
                    throw new AssertionError(encoded + " bytes would be refused by the clientbound payload limit");
                }
                BattleLogPacket decoded = BattleLogPacket.STREAM_CODEC.decode(buffer);
                assertEquals(BattleLogPacket.MAX_HISTORY, decoded.history().size());
                assertEquals(8000, plainText(decoded.history().getLast().text()).text().length());
                assertEquals((long) BattleLogPacket.MAX_HISTORY, decoded.history().getLast().sequence());
            });
        });

        check("a declared body over the budget is refused before allocating", () -> withBuffer(server, buffer -> {
            buffer.writeVarInt(BattleLogPacket.MAX_BODY_BYTES + 1);
            buffer.writeBytes(new byte[8]);
            IllegalArgumentException failure = expectRefused("over-budget body",
                    () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
            if (!String.valueOf(failure.getMessage()).contains("budget")) {
                throw new AssertionError("unexpected message: " + failure.getMessage());
            }
            if (buffer.readableBytes() != 8) throw new AssertionError("more than the declared length was consumed");
        }));

        check("a negative body length is refused", () -> withBuffer(server, buffer -> {
            buffer.writeVarInt(-5);
            buffer.writeBytes(new byte[8]);
            expectRefused("negative body length", () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
            if (buffer.readableBytes() != 8) throw new AssertionError("more than the length was consumed");
        }));

        check("a body longer than the remaining bytes is refused", () -> withBuffer(server, buffer -> {
            buffer.writeVarInt(64);
            buffer.writeBytes(new byte[8]);
            expectRefused("truncated body", () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
        }));

        check("more than 64 rows are refused", () -> withBuffer(server, buffer -> {
            RegistryFriendlyByteBuf body = body(server);
            try {
                body.writeUUID(SESSION);
                body.writeVarInt(BattleLogPacket.UPDATE);
                body.writeVarLong(65L);
                body.writeVarInt(BattleLogPacket.MAX_HISTORY + 1);
                writeBody(buffer, body);
            } finally {
                body.release();
            }
            IllegalArgumentException failure = expectRefused("too many rows",
                    () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
            if (!String.valueOf(failure.getMessage()).contains("history size")) {
                throw new AssertionError("unexpected message: " + failure.getMessage());
            }
        }));

        check("a corrupted component frame is refused", () -> withBuffer(server, buffer -> {
            RegistryFriendlyByteBuf body = body(server);
            try {
                body.writeUUID(SESSION);
                body.writeVarInt(BattleLogPacket.UPDATE);
                body.writeVarLong(1L);
                body.writeVarInt(1);
                body.writeVarLong(1L);
                body.writeUtf("battle", BattleLogPacket.MAX_CATEGORY);
                body.writeVarInt(4);
                body.writeBytes(new byte[]{0x7F, 0x7F, 0x7F, 0x7F});
                writeBody(buffer, body);
            } finally {
                body.release();
            }
            IllegalArgumentException failure = expectRefused("corrupted component frame",
                    () -> BattleLogPacket.STREAM_CODEC.decode(buffer));
            if (failure.getCause() == null) throw new AssertionError("the frame failure was not kept as the cause");
        }));

        check("a trailing byte inside the body is refused", () -> {
            BattleLogPacket valid = packet(BattleLogPacket.UPDATE, 1,
                    List.of(row(1, "battle", Component.literal("尾随检查"))));
            withBuffer(server, encoded -> withBuffer(server, padded -> {
                BattleLogPacket.STREAM_CODEC.encode(encoded, valid);
                int size = encoded.readVarInt();
                padded.writeVarInt(size + 1);
                padded.writeBytes(encoded, encoded.readerIndex(), size);
                padded.writeByte(0x00);
                IllegalArgumentException failure = expectRefused("trailing byte",
                        () -> BattleLogPacket.STREAM_CODEC.decode(padded));
                if (!String.valueOf(failure.getMessage()).contains("trailing")) {
                    throw new AssertionError("unexpected message: " + failure.getMessage());
                }
            }));
        });

        check("an oversize row is refused by the verified snapshot", () -> {
            if (BattleLogPacket.verifiedSnapshot(Component.literal("正常长度的日志"), server.registryAccess()) == null) {
                throw new AssertionError("a normal row was refused");
            }
            if (BattleLogPacket.verifiedSnapshot(Component.literal("对".repeat(4000)), server.registryAccess()) != null) {
                throw new AssertionError("an oversize row was accepted");
            }
            if (BattleLogPacket.verifiedSnapshot(Component.literal("x"), null) != null) {
                throw new AssertionError("a missing registry context must not produce a snapshot");
            }
        });
    }

    // ---- 3. the display point resolves in the client's language ----------------------------

    private static void languageChecks(MinecraftServer server) {
        Map<String, String> english = langTable("en_us");
        Map<String, String> chinese = langTable("zh_cn");

        check("both shipped language tables carry every journal key", () -> {
            for (String key : journalKeys()) {
                if (!english.containsKey(key)) throw new AssertionError("en_us is missing " + key);
                if (!chinese.containsKey(key)) throw new AssertionError("zh_cn is missing " + key);
            }
        });

        Component row = Component.translatable(BattleJournal.KEY_DAMAGE,
                Component.translatable(ENTITY_KEY), "3.0",
                Component.translatable(BattleJournal.KEY_SOURCE_FALL), "12.0", "20.0");
        Component decoded = roundTrip(server, packet(BattleLogPacket.UPDATE, 1,
                List.of(row(1, "damage", row)))).history().getFirst().text();

        check("the same decoded row reads differently in the two languages", () -> {
            String en = resolve(english, decoded);
            String zh = resolve(chinese, decoded);
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: en_us='{}' zh_cn='{}'", en, zh);
            if (en.equals(zh)) throw new AssertionError("both languages produced the same text: " + en);
            if (!en.contains("damage") || !en.contains("HP")) {
                throw new AssertionError("the english row lost its template: " + en);
            }
            if (!zh.contains("受到") || !zh.contains("伤害") || !zh.contains("HP")) {
                throw new AssertionError("the chinese row lost its template: " + zh);
            }
            // Nothing was resolved on the server: the stored row is still the unresolved key.
            assertEquals(BattleJournal.KEY_DAMAGE, translatable(decoded).getKey());
        });

        check("a literal name that looks like a key is never translated", () -> {
            Component literal = roundTrip(server, packet(BattleLogPacket.UPDATE, 1,
                    List.of(row(1, "damage", Component.literal(KEY_LOOKING))))).history().getFirst().text();
            assertEquals(KEY_LOOKING, resolve(english, literal));
            assertEquals(KEY_LOOKING, resolve(chinese, literal));
        });

        check("the retained history re-displays after a language switch", () -> {
            BattleLogPacket incoming = roundTrip(server, new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 2,
                    List.of(row(1, "damage", row),
                            row(2, "heal", Component.translatable(BattleJournal.KEY_HEAL,
                                    Component.translatable(ENTITY_KEY), "4.0"))), EPOCH, GENERATION));
            BattleLogState state = new BattleLogState();
            if (!state.accept(incoming, 0L)) throw new AssertionError("the snapshot was refused");
            List<String> englishRows = resolveAll(english, state.retainedHistory());
            List<String> chineseRows = resolveAll(chinese, state.retainedHistory());
            if (!englishRows.get(0).contains("damage")) {
                throw new AssertionError("the retained row did not read as english: " + englishRows.get(0));
            }
            if (!chineseRows.get(0).contains("受到")) {
                throw new AssertionError("the retained row did not read as chinese: " + chineseRows.get(0));
            }
            if (!chineseRows.get(1).contains("恢复")) {
                throw new AssertionError("the second retained row did not read as chinese: " + chineseRows.get(1));
            }
            // The switch changes only the reading: the retained structure and its order are untouched.
            assertEquals(2, state.retainedHistory().size());
            assertEquals(2L, state.sequence());
        });
    }

    // ---- 4. the real producer, and what it really published -------------------------------

    private static void producerChecks(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        ServerPlayer owner = new FakePlayer(level, new GameProfile(
                UUID.nameUUIDFromBytes("tactical-journal-test".getBytes(StandardCharsets.UTF_8)), "JournalTest"));
        owner.setPos(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D);
        Map<String, String> english = langTable("en_us");
        Map<String, String> chinese = langTable("zh_cn");
        int fixedBody = bodyBytes(server, Component.literal(""))
                - EMPTY_LITERAL_PAYLOAD - VarInt.getByteSize(EMPTY_LITERAL_PAYLOAD);
        Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: one-row body overhead is {} bytes, so a row's "
                + "component frame is the only variable part", fixedBody);

        check("a row built from an empty root keeps every segment", () -> {
            BattleJournal journal = new BattleJournal(owner);
            ServerPackets.startCapture();
            try {
                Component text = Component.empty().append(Component.literal("前")).append(Component.literal("后"));
                journal.record("battle", text);
                BattleLogPacket published = published(server, owner, journal.sessionId());
                BattleLogPacket.Entry stored = published.history().getLast();
                assertEquals("前后", stored.text().getString());
                Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: stored sectioned row kept {} sibling(s)",
                        stored.text().getSiblings().size());
            } finally {
                ServerPackets.stopCapture();
            }
        });

        check("a stored row cannot be rewritten by mutating its nested argument", () -> {
            BattleJournal journal = new BattleJournal(owner);
            MutableComponent nested = Component.literal("僵尸");
            Component text = Component.translatable(BattleJournal.KEY_DAMAGE, nested, "3.0",
                    Component.translatable(BattleJournal.KEY_SOURCE_FALL), "12.0", "20.0");
            ServerPackets.startCapture();
            BattleLogPacket published;
            try {
                journal.record("damage", text);
                // The producer keeps its own component and edits it afterwards; the stored row must not move.
                nested.append(Component.literal("改名后"));
                published = published(server, owner, journal.sessionId());
            } finally {
                ServerPackets.stopCapture();
            }
            Component stored = published.history().getLast().text();
            TranslatableContents contents = translatable(stored);
            assertEquals(BattleJournal.KEY_DAMAGE, contents.getKey());
            // The native codec canonicalises a plain literal argument into a string; either form is fine,
            // what matters is that the stored value no longer tracks the producer's component.
            Object argument = contents.getArgs()[0];
            String rendered = argument instanceof Component component ? component.getString() : String.valueOf(argument);
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: nested argument came back as {} -> '{}'",
                    argument.getClass().getName(), rendered);
            assertEquals("僵尸", rendered);
            if (rendered.contains("改名后")) {
                throw new AssertionError("the stored row shares the producer's mutable component");
            }
            assertTrue(resolve(english, stored).contains("took"), "the stored row still resolves");
        });

        check("a row of exactly the frame payload limit is accepted", () -> {
            // Cross-anchor the calibration: 4a measured 8000 characters at 8005 wire bytes.
            int reference = rowPayload(server, Component.literal("a".repeat(8000)), fixedBody);
            assertEquals(REFERENCE_8000_PAYLOAD, reference,
                    "the derived payload must agree with 4a's 8005-byte measurement");
            // The literal overhead is derived from real measurements, not assumed: one character is one byte.
            int literalOverhead = rowPayload(server, Component.literal("a"), fixedBody) - 1;
            int chars = MAX_FRAME_PAYLOAD - literalOverhead;
            Component atLimit = Component.literal("a".repeat(chars));
            int payload = rowPayload(server, atLimit, fixedBody);
            assertEquals(MAX_FRAME_PAYLOAD, payload,
                    "the fixture must land exactly on the payload limit, measured in bytes");
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: payload limit is reached by {} characters "
                    + "(literal overhead {} bytes)", chars, literalOverhead);
            if (BattleLogPacket.verifiedSnapshot(atLimit, server.registryAccess()) == null) {
                throw new AssertionError("a row of exactly " + payload + " payload bytes was refused");
            }
            Component overLimit = Component.literal("a".repeat(chars + 1));
            expectRefused("a row one byte over the frame limit", () -> bodyBytes(server, overLimit));
            if (BattleLogPacket.verifiedSnapshot(overLimit, server.registryAccess()) != null) {
                throw new AssertionError("a row of " + (payload + 1) + " payload bytes was accepted");
            }
            BattleJournal journal = new BattleJournal(owner);
            ServerPackets.startCapture();
            try {
                journal.record("test", atLimit);
                journal.record("test", overLimit);
                journal.record("test", Component.literal("正常一行"));
                BattleLogPacket published = published(server, owner, journal.sessionId());
                List<BattleLogPacket.Entry> history = published.history();
                assertEquals(3, history.size());
                assertEquals(chars, plainText(history.get(0).text()).text().length());
                assertEquals(BattleJournal.KEY_TOO_LONG, translatable(history.get(1).text()).getKey());
                assertEquals("正常一行", history.get(2).text().getString());
            } finally {
                ServerPackets.stopCapture();
            }
        });

        check("a row that encodes but would not decode becomes the short fallback", () -> {
            int[] report = new int[2];
            Component heavy = heavyRow(server, fixedBody, report);
            if (heavy == null) {
                throw new AssertionError("no sibling count stayed inside the " + MAX_FRAME_PAYLOAD
                        + "-byte payload limit while exceeding the decode heap quota; the first difference is "
                        + "that the wire limit is reached first");
            }
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: {} sibling components encode to {} payload bytes "
                            + "but fail to decode: {}", report[0], report[1], decodeFailure(server, heavy));
            int payload = report[1];
            if (payload > MAX_FRAME_PAYLOAD) throw new AssertionError("the probe row is over the wire limit");
            BattleJournal journal = new BattleJournal(owner);
            ServerPackets.startCapture();
            try {
                journal.record("test", heavy);
                journal.record("test", Component.literal("重试正常"));
                BattleLogPacket published = published(server, owner, journal.sessionId());
                List<BattleLogPacket.Entry> history = published.history();
                assertEquals(2, history.size());
                assertEquals(BattleJournal.KEY_TOO_LONG, translatable(history.get(0).text()).getKey());
                assertEquals("重试正常", history.get(1).text().getString());
            } finally {
                ServerPackets.stopCapture();
            }
        });

        check("finish keeps a multi-section reason in both languages", () -> {
            BattleJournal journal = new BattleJournal(owner);
            ServerPackets.startCapture();
            try {
                Component reason = Component.literal("因由").append(Component.literal("甲"))
                        .append(Component.literal("乙"));
                journal.finish(reason);
                BattleLogPacket published = published(server, owner, journal.sessionId());
                assertEquals(BattleLogPacket.FINISH, published.lifecycle());
                Component stored = published.history().getLast().text();
                TranslatableContents contents = translatable(stored);
                assertEquals(BattleJournal.KEY_FINISH, contents.getKey());
                Component argument = (Component) contents.getArgs()[0];
                assertEquals("因由甲乙", argument.getString());
                if (!resolve(english, stored).contains("Battle ended · 因由甲乙")) {
                    throw new AssertionError("the english finish row lost the reason: " + resolve(english, stored));
                }
                if (!resolve(chinese, stored).contains("战斗结束 · 因由甲乙")) {
                    throw new AssertionError("the chinese finish row lost the reason: " + resolve(chinese, stored));
                }
            } finally {
                ServerPackets.stopCapture();
            }
        });

        check("a real damage and heal event reach the client as localised rows", () -> {
            Creeper enemy = EntityType.CREEPER.create(level);
            if (enemy == null) throw new AssertionError("the fixture could not create the mob");
            enemy.setPos(owner.getX() + 3.0D, owner.getY(), owner.getZ());
            level.addFreshEntity(enemy);
            ServerBattle battle;
            BattleLogPacket published;
            try {
                if (!BattleManager.start(owner, true)) throw new AssertionError("the encounter did not start");
                battle = BattleManager.findFor(owner.getUUID());
                if (battle == null) throw new AssertionError("the encounter is not routed to its founder");
                ServerPackets.startCapture();
                try {
                    enemy.hurt(level.damageSources().playerAttack(owner), 3.0F);
                    enemy.setHealth(enemy.getMaxHealth() - 4.0F);
                    enemy.heal(2.0F);
                    published = published(server, owner, battle.sessionId());
                } finally {
                    ServerPackets.stopCapture();
                }
            } finally {
                BattleManager.stop(owner);
                enemy.discard();
            }
            Component damage = null;
            Component heal = null;
            for (BattleLogPacket.Entry entry : published.history()) {
                if (!(entry.text().getContents() instanceof TranslatableContents contents)) continue;
                if (BattleJournal.KEY_DAMAGE.equals(contents.getKey())) damage = entry.text();
                if (BattleJournal.KEY_HEAL.equals(contents.getKey())) heal = entry.text();
            }
            if (damage == null) throw new AssertionError("no keyed damage row was published");
            if (heal == null) throw new AssertionError("no keyed heal row was published");
            Object[] damageArgs = translatable(damage).getArgs();
            assertEquals(5, damageArgs.length);
            Component victim = (Component) damageArgs[0];
            assertEquals("entity.minecraft.creeper", translatable(victim).getKey(),
                    "the victim keeps the vanilla key, so the row still reads after the mob is gone");
            // The amount is the encounter's own effective damage, not the raw hurt() argument: the combat
            // rules adjust it. What the journal promises is one fixed decimal place, not a magic value.
            assertOneDecimal(damageArgs[1], "damage amount");
            assertOneDecimal(damageArgs[3], "victim health after the hit");
            assertOneDecimal(damageArgs[4], "victim maximum health");
            // A player attack names its actor: the source argument is that actor's own text, not a template.
            if (damageArgs[2] instanceof Component source && source.getContents() instanceof TranslatableContents) {
                throw new AssertionError("an attacking player must not be reported as an environment source");
            }
            assertEquals("JournalTest", textOf(damageArgs[2]));
            Object[] healArgs = translatable(heal).getArgs();
            assertEquals(2, healArgs.length);
            assertOneDecimal(healArgs[1], "heal amount");
            String en = resolve(english, damage);
            String zh = resolve(chinese, damage);
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG note: real event en_us='{}' zh_cn='{}'", en, zh);
            if (!en.contains("took") || !en.contains("damage") || !en.contains("HP")) {
                throw new AssertionError("the real damage row lost its english template: " + en);
            }
            if (!zh.contains("受到") || !zh.contains("伤害")) {
                throw new AssertionError("the real damage row lost its chinese template: " + zh);
            }
            if (!resolve(chinese, heal).contains("恢复")) {
                throw new AssertionError("the real heal row lost its chinese template");
            }
        });
    }

    /** Wire bodies of one-row snapshots, so a row's own payload can be derived instead of assumed. */
    private static int bodyBytes(MinecraftServer server, Component row) {
        return bodyBytes(server, row, "x");
    }

    private static int bodyBytes(MinecraftServer server, Component row, String category) {
        BattleLogPacket packet = new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 1,
                List.of(row(1, category, row)));
        int[] size = new int[1];
        withBuffer(server, buffer -> {
            BattleLogPacket.STREAM_CODEC.encode(buffer, packet);
            size[0] = buffer.readVarInt();
            if (buffer.readableBytes() != size[0]) {
                throw new AssertionError("the declared body length disagrees with the bytes written");
            }
        });
        return size[0];
    }

    /** One row's component frame payload, derived from the measured body and the measured overhead. */
    private static int rowPayload(MinecraftServer server, Component row, int fixedBodyBytes) {
        int total = bodyBytes(server, row) - fixedBodyBytes;
        for (int payload = total; payload >= 0 && payload >= total - 8; payload--) {
            if (payload + VarInt.getByteSize(payload) == total) return payload;
        }
        throw new AssertionError("could not derive a payload length from " + total + " bytes");
    }

    /**
     * The smallest sectioned row that still encodes inside the wire limit but no longer decodes inside the
     * heap quota. Returns {@code null} when the wire limit is reached first, which is a finding in itself.
     */
    private static Component heavyRow(MinecraftServer server, int fixedBody, int[] report) {
        for (int count = 48; count <= 2048; count += 8) {
            Component candidate = sectioned(count);
            int payload;
            try {
                payload = rowPayload(server, candidate, fixedBody);
            } catch (RuntimeException | AssertionError overWire) {
                return null;
            }
            if (decodeFailure(server, candidate) != null) {
                report[0] = count;
                report[1] = payload;
                return candidate;
            }
        }
        return null;
    }

    private static Component sectioned(int count) {
        MutableComponent root = Component.empty();
        for (int index = 0; index < count; index++) root.append(Component.literal("a"));
        return root;
    }

    /** Encodes the row for real, then reports what the decode side refused (or {@code null}). */
    private static String decodeFailure(MinecraftServer server, Component row) {
        BattleLogPacket packet = new BattleLogPacket(SESSION, BattleLogPacket.UPDATE, 1,
                List.of(row(1, "x", row)));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                server.registryAccess(), ConnectionType.OTHER);
        try {
            BattleLogPacket.STREAM_CODEC.encode(buffer, packet);
            try {
                BattleLogPacket.STREAM_CODEC.decode(buffer);
                return null;
            } catch (RuntimeException refused) {
                return refused + " / cause=" + refused.getCause();
            }
        } finally {
            buffer.release();
        }
    }

    /** The last journal snapshot the server really published to this member, decoded through the codec. */
    private static BattleLogPacket published(MinecraftServer server, ServerPlayer recipient, UUID session) {
        BattleLogPacket last = null;
        for (CustomPacketPayload payload : ServerPackets.capturedFor(recipient.getUUID())) {
            if (payload instanceof BattleLogPacket log && session.equals(log.battleId())) last = log;
        }
        if (last == null) throw new AssertionError("no journal snapshot was published");
        return roundTrip(server, last);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static List<String> journalKeys() {
        return List.of(BattleJournal.KEY_START, BattleJournal.KEY_FINISH, BattleJournal.KEY_DAMAGE,
                BattleJournal.KEY_BLOCKED, BattleJournal.KEY_HEAL, BattleJournal.KEY_DEATH,
                BattleJournal.KEY_EFFECT_GAINED, BattleJournal.KEY_EFFECT_UPDATED,
                BattleJournal.KEY_EFFECT_REMOVED, BattleJournal.KEY_EFFECT_EXPIRED,
                BattleJournal.KEY_DURATION_INFINITE, BattleJournal.KEY_DURATION_SECONDS,
                BattleJournal.KEY_SOURCE_MAGIC, BattleJournal.KEY_SOURCE_WITHER, BattleJournal.KEY_SOURCE_FALL,
                BattleJournal.KEY_SOURCE_FIRE, BattleJournal.KEY_SOURCE_DROWN, BattleJournal.KEY_SOURCE_STARVE,
                BattleJournal.KEY_SOURCE_OTHER, BattleJournal.KEY_TOO_LONG);
    }

    /** The shipped table of one language, laid over the server's own vanilla table. */
    private static Map<String, String> langTable(String file) {
        Map<String, String> table = new LinkedHashMap<>(Language.getInstance().getLanguageData());
        String path = "/assets/tactical/lang/" + file + ".json";
        try (InputStream input = BattleLogSelfTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing language resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                table.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
        if (!table.containsKey(BattleJournal.KEY_START)) {
            throw new AssertionError(path + " did not contribute its journal keys");
        }
        return table;
    }

    /** Resolves one component exactly as a display would: the language is swapped, the row is not. */
    private static String resolve(Map<String, String> table, Component text) {
        Language original = Language.getInstance();
        Language.inject(language(table));
        try {
            return text.getString();
        } finally {
            Language.inject(original);
        }
    }

    private static List<String> resolveAll(Map<String, String> table, List<BattleLogPacket.Entry> rows) {
        List<String> resolved = new ArrayList<>(rows.size());
        for (BattleLogPacket.Entry row : rows) resolved.add(resolve(table, row.text()));
        return resolved;
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
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return sink -> false;
            }
        };
    }

    private static BattleLogPacket packet(int lifecycle, long sequence, List<BattleLogPacket.Entry> rows) {
        return new BattleLogPacket(SESSION, lifecycle, sequence, rows);
    }

    private static BattleLogPacket.Entry row(long sequence, String category, Component text) {
        return new BattleLogPacket.Entry(sequence, category, text);
    }

    private static BattleLogPacket roundTrip(MinecraftServer server, BattleLogPacket original) {
        BattleLogPacket[] decoded = new BattleLogPacket[1];
        withBuffer(server, buffer -> {
            BattleLogPacket.STREAM_CODEC.encode(buffer, original);
            decoded[0] = BattleLogPacket.STREAM_CODEC.decode(buffer);
            if (buffer.readableBytes() != 0) throw new AssertionError("the snapshot was not consumed exactly");
        });
        if (decoded[0] == null) throw new AssertionError("nothing decoded");
        return decoded[0];
    }

    private static RegistryFriendlyByteBuf body(MinecraftServer server) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess(), ConnectionType.OTHER);
    }

    /** Writes a hand-built body with its own declared length, so the decoder can be driven with bad ones. */
    private static void writeBody(RegistryFriendlyByteBuf destination, RegistryFriendlyByteBuf body) {
        int size = body.readableBytes();
        destination.writeVarInt(size);
        destination.writeBytes(body, body.readerIndex(), size);
    }

    private static void withBuffer(MinecraftServer server, Consumer<RegistryFriendlyByteBuf> body) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), server.registryAccess(), ConnectionType.OTHER);
        try {
            body.accept(buffer);
        } finally {
            buffer.release();
        }
    }

    /** A journal argument as displayed text: the codec may keep a plain literal as a string. */
    private static String textOf(Object argument) {
        if (argument instanceof Component component) return component.getString();
        if (argument == null) throw new AssertionError("a journal argument is missing");
        return String.valueOf(argument);
    }

    /** Every number the journal prints uses one fixed decimal place, independent of the language. */
    private static void assertOneDecimal(Object argument, String what) {
        String rendered = textOf(argument);
        double value;
        try {
            value = Double.parseDouble(rendered);
        } catch (NumberFormatException failure) {
            throw new AssertionError(what + " is not a number: '" + rendered + "'");
        }
        if (!rendered.equals(String.format(java.util.Locale.ROOT, "%.1f", value))) {
            throw new AssertionError(what + " is not printed with one decimal place: '" + rendered + "'");
        }
    }

    private static PlainTextContents plainText(Component component) {
        if (!(component.getContents() instanceof PlainTextContents contents)) {
            throw new AssertionError("expected literal text, got " + component.getContents().getClass().getName());
        }
        return contents;
    }

    private static void assertText(Component component, String expected) {
        assertEquals(expected, plainText(component).text());
    }

    private static TranslatableContents translatable(Component component) {
        if (!(component.getContents() instanceof TranslatableContents contents)) {
            throw new AssertionError("expected a translation, got " + component.getContents().getClass().getName());
        }
        return contents;
    }

    private static IllegalArgumentException expectRefused(String what, Runnable body) {
        try {
            body.run();
        } catch (IllegalArgumentException expected) {
            return expected;
        }
        throw new AssertionError(what + " was accepted");
    }

    private static void check(String name, Runnable body) {
        checks++;
        try {
            body.run();
            Tactical.LOGGER.info("[Tactical] BATTLE_LOG [{}] PASS {}", checks, name);
        } catch (Throwable failure) {
            failures++;
            Tactical.LOGGER.error("[Tactical] BATTLE_LOG [{}] FAIL {}: {}", checks, name, failure.toString());
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError((message == null ? "" : message + ": ")
                    + "expected " + expected + " but was " + actual);
        }
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    /** Stops the fixture server; a failure exits the JVM non-zero instead of leaving a green build. */
    private static void halt(MinecraftServer server, int code) {
        server.halt(false);
        if (code != 0) Runtime.getRuntime().halt(code);
    }
}
