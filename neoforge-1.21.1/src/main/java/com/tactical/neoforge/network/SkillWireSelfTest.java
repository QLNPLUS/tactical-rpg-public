package com.tactical.neoforge.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.skill.SkillDefinition;
import com.tactical.neoforge.skill.SkillRegistry;
import com.tactical.neoforge.skill.SkillTarget;
import com.tactical.neoforge.skill.SkillText;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disposable dedicated-server fixture for the paged skill state: {@code -Dtactical.selftest=skill_wire}.
 *
 * <p>The native component codec only exists inside a booted game (see {@code ComponentIoSelfTest}), so a
 * skill page - which carries three component frames per skill - can only be driven here. Everything below
 * uses the real {@link SkillStatePacket#STREAM_CODEC} on the server's own registry access:</p>
 *
 * <ol>
 *   <li>one page round-trips: a keyed name with a nested argument, a literal description with newlines and
 *       Unicode, a keyed reason with typed arguments, every scalar and the membership stamp;</li>
 *   <li>a legitimate 256-skill state is paged, and the whole list arrives: the page count is the minimum,
 *       no page holds more than the per-page limit, every page's <em>measured</em> body stays inside the
 *       page budget and far below the 1 MiB clientbound ceiling, and every skill id and long Unicode
 *       description comes back identically;</li>
 *   <li>malformed input is refused before allocation: an over-budget declared length, a length larger than
 *       the remaining bytes, a truncated frame and a trailing byte inside a frame;</li>
 *   <li>a text that cannot fit one component frame is refused as a field while the verify-and-replace path
 *       has a usable stable line, and a page that carries the unverified text cannot be encoded at all;</li>
 *   <li>one preview response round-trips with a keyed reason and its tiles/targets;</li>
 *   <li>the shipped language tables resolve the same components differently, while a definition without an
 *       explicit key reads identically in both languages and a missing key falls back to the authored text.
 *       This is a <em>same-runtime two-language resolver</em>, not two clients on one server.</li>
 * </ol>
 *
 * <p>Every item logs one {@code SKILL_WIRE [n] PASS/FAIL <name>} line, the run ends with a single
 * {@code PASSED}/{@code FAILED} marker, and a failure exits the server non-zero. It touches no battle
 * state and starts no client.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class SkillWireSelfTest {

    private static final UUID SESSION = new UUID(0x534B494C4CL, 0x5749524534L);
    private static final long EPOCH = 4242L;
    private static final long GENERATION = 9L;
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final String LONG_DESCRIPTION = longDescription();
    /** One clientbound custom payload is capped at 1 MiB; a page must stay below that, not at it. */
    private static final int CLIENTBOUND_CEILING = 1024 * 1024;

    private static int checks;
    private static int failures;

    private SkillWireSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!"skill_wire".equals(System.getProperty("tactical.selftest", ""))) return;
        MinecraftServer server = event.getServer();
        checks = 0;
        failures = 0;
        Tactical.LOGGER.info("[Tactical] SKILL_WIRE beginning: skill protocol {}, page budget {} bytes, at most {} "
                        + "skills per page", SkillNetwork.PROTOCOL, SkillStatePacket.MAX_BODY_BYTES,
                SkillPacketIO.MAX_PER_PAGE);
        try {
            protocolChecks();
            roundTripChecks(server);
            fullListChecks(server);
            emptyStateChecks(server);
            malformedChecks(server);
            oversizedTextChecks(server);
            previewChecks(server);
            languageChecks();
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] SKILL_WIRE SELFTEST FAILED: crashed after {} checks", checks, failure);
            halt(server, 1);
            return;
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] SKILL_WIRE SELFTEST FAILED: {} of {} checks failed", failures, checks);
            halt(server, 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] SKILL_WIRE SELFTEST PASSED: all {} checks on the paged skill state, the "
                + "page limits, the preview reason and the two-language resolver", checks);
        halt(server, 0);
    }

    // ---- 1. the channel version -------------------------------------------------------------

    private static void protocolChecks() {
        check("the skill channel publishes protocol 4", () -> {
            require("4".equals(SkillNetwork.PROTOCOL), "the paged state is a wire change: 3 -> 4");
            require(!SkillNetwork.PROTOCOL.equals(TacticalNetwork.PROTOCOL), "channels stay independent");
            require(!SkillNetwork.PROTOCOL.equals(BattleLogNetwork.PROTOCOL), "channels stay independent");
        });
    }

    // ---- 2. one page over the real codec ----------------------------------------------------

    private static void roundTripChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("one page keeps every text and every scalar across the wire", () -> {
            Component name = Component.translatableWithFallback("tactical.skill.backstab.name", "背刺");
            Component description = Component.literal("第三方自定义描述\n第二行：保留换行与中文");
            Component reason = SkillText.translatable(SkillText.KEY_REASON_COOLDOWN, Integer.toString(2));
            SkillView view = new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "backstab"), name,
                    description, 2, 5, SkillDefinition.Aim.ENTITY, false, false, false, reason, 15, 10.0D, 1);
            SkillStatePacket page = new SkillStatePacket(SESSION, OVERWORLD, true, 7L, 3L, 0, 1, 1,
                    40.0D, 60.0D, 1.5D, List.of(view), List.of(12, 13)).withMembership(EPOCH, GENERATION);
            SkillStatePacket decoded = roundTrip(server, page);
            require(decoded.equals(page), "the whole page - including every component - survives unchanged");
            require(decoded.membershipServerEpoch() == EPOCH && decoded.membershipGeneration() == GENERATION,
                    "the membership stamp is part of the frame");
            TranslatableContents nameContents = translatable(decoded.skills().getFirst().name());
            require("tactical.skill.backstab.name".equals(nameContents.getKey()), "the explicit name key stays a key");
            require("背刺".equals(nameContents.getFallback()), "the authored Chinese line is still the fallback");
            require(decoded.skills().getFirst().description().getString().equals("第三方自定义描述\n第二行：保留换行与中文"),
                    "a literal description keeps its newline and its Unicode");
            require(decoded.skills().getFirst().reason().getContents() instanceof TranslatableContents,
                    "the reason stays a translation key, not a finished sentence");
            require(decoded.skills().getFirst().apCost() == 15 && Math.abs(decoded.skills().getFirst().mpCost() - 10.0D) < 1.0E-9
                    && decoded.skills().getFirst().cooldown() == 1, "costs and cooldown are unchanged");
        });

        check("a preview response keeps its keyed reason and its tiles", () -> {
            Component reason = SkillText.translatable(SkillText.KEY_REJECT_TOO_MANY_TARGETS);
            UUID targetId = new UUID(0x5A4D42L, 0x4945L);
            SkillPreviewResponsePacket packet = new SkillPreviewResponsePacket(SESSION, OVERWORLD, 11, 7L, 1234L,
                    ResourceLocation.fromNamespaceAndPath("tactical", "backstab"), SkillTarget.entity(targetId),
                    false, reason, 0, 0.0D, new int[]{1, 64, 2}, new int[]{5});
            SkillPreviewResponsePacket decoded = roundTrip(server, packet);
            require(decoded.target().entity().equals(targetId), "the entity target identity survives");
            require(translatable(decoded.reason()).getKey().equals(SkillText.KEY_REJECT_TOO_MANY_TARGETS),
                    "the preview reason is still a key");
            require(decoded.tiles().length == 3 && decoded.affected().length == 1, "tiles and targets survive");
            require(decoded.requestId() == 11 && decoded.revision() == 7L, "the request/revision pair survives");
            require(decoded.target().entity() != null, "the entity target identity survives");
        });
    }

    // ---- 3. one legitimate full list, paged ------------------------------------------------

    private static void fullListChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("256 legitimate skills are paged and every one of them arrives", () -> {
            List<SkillView> views = new ArrayList<>(SkillPacketIO.MAX_SKILLS);
            for (int index = 0; index < SkillPacketIO.MAX_SKILLS; index++) {
                views.add(new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "skill_" + index),
                        Component.translatableWithFallback("tactical.skill.fixture.name", "技能" + index),
                        Component.literal(LONG_DESCRIPTION),
                        1 + index % 5, 5, SkillDefinition.Aim.values()[index % 3], index % 2 == 0, index % 2 == 1,
                        index % 4 != 0,
                        SkillText.translatable(SkillText.KEY_REASON_COOLDOWN, Integer.toString(index % 7)),
                        5 + index % 10, 1.0D + index % 3, index % 4));
            }
            SkillSnapshot snapshot = new SkillSnapshot(true, 40.0D, 60.0D, 1.5D, views, friendlyIds(64));
            List<SkillStatePacket> pages = SkillStatePacket.pack(SESSION, OVERWORLD, snapshot, 9L, 4L, registries);
            require(pages.size() == SkillPacketIO.minimumPages(true, SkillPacketIO.MAX_SKILLS),
                    "the measured page budget never splits a legitimate list further: " + pages.size() + " pages");
            require(pages.size() == 8, "256 skills occupy 8 pages of at most 32");
            int total = 0;
            int largestBody = 0;
            int totalBody = 0;
            for (int index = 0; index < pages.size(); index++) {
                SkillStatePacket page = pages.get(index);
                require(page.pageIndex() == index && page.pageCount() == pages.size(), "page headers are consistent");
                require(page.skills().size() <= SkillPacketIO.MAX_PER_PAGE, "a page never exceeds the per-page limit");
                require(page.totalSkillCount() == SkillPacketIO.MAX_SKILLS, "every page announces the whole list");
                int body = measure(page, registries);
                largestBody = Math.max(largestBody, body);
                totalBody += body;
                require(body <= SkillStatePacket.MAX_BODY_BYTES, "page " + index + " body is " + body + " bytes");
                require(body < CLIENTBOUND_CEILING, "page " + index + " stays below the 1 MiB clientbound ceiling");
                total += page.skills().size();
            }
            require(total == SkillPacketIO.MAX_SKILLS, "no skill was dropped while paging");
            require(LONG_DESCRIPTION.length() == 2048, "the fixture description is the legal 2048-unit maximum");
            require(totalBody > CLIENTBOUND_CEILING, "the whole state is larger than one 1 MiB packet: "
                    + totalBody + " bytes, so paging is not cosmetic");
            require(largestBody < CLIENTBOUND_CEILING, "while every single page stays below the 1 MiB ceiling");
            Tactical.LOGGER.info("[Tactical] SKILL_WIRE 256-skill state with 2048-unit descriptions: {} pages, total "
                    + "{} bytes, largest measured page body {} bytes (budget {}, per-page limit {} skills)",
                    pages.size(), totalBody, largestBody, SkillStatePacket.MAX_BODY_BYTES, SkillPacketIO.MAX_PER_PAGE);
            LinkedHashSet<ResourceLocation> seen = new LinkedHashSet<>();
            int longTexts = 0;
            for (SkillStatePacket page : pages) {
                SkillStatePacket decoded = roundTrip(server, page);
                require(decoded.equals(page), "every page survives the wire unchanged");
                for (SkillView view : decoded.skills()) {
                    require(seen.add(view.id()), "a skill is not repeated across pages: " + view.id());
                    require(view.description().getString().equals(LONG_DESCRIPTION),
                            "the maximum-length description of " + view.id() + " arrives whole, not shortened");
                    require(!(view.description().getContents() instanceof TranslatableContents),
                            "no field was replaced by the stable short line: " + view.id());
                    longTexts++;
                }
            }
            require(longTexts == SkillPacketIO.MAX_SKILLS, "all 256 long descriptions were decoded");
            require(seen.size() == SkillPacketIO.MAX_SKILLS, "256 unique skill ids");
        });
    }

    // ---- 3b. the legitimate empty active state ---------------------------------------------

    private static void emptyStateChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("an active state without skills is one page that still carries MP and allies", () -> {
            SkillSnapshot empty = new SkillSnapshot(true, 40.0D, 60.0D, 1.5D, List.of(), friendlyIds(3));
            List<SkillStatePacket> pages = SkillStatePacket.pack(SESSION, OVERWORLD, empty, 2L, 1L, registries);
            require(pages.size() == 1, "an empty state is a single page, not zero pages");
            SkillStatePacket page = pages.getFirst();
            require(page.active() && page.skills().isEmpty() && page.totalSkillCount() == 0,
                    "the empty list travels as an active page with no skills");
            SkillStatePacket decoded = roundTrip(server, page);
            require(decoded.equals(page), "the empty page survives the wire");
            require(Math.abs(decoded.mp() - 40.0D) < 1.0E-9 && Math.abs(decoded.maximum() - 60.0D) < 1.0E-9,
                    "a member without skills still receives its resources");
            require(decoded.friendlyIds().equals(friendlyIds(3)), "and its allied identity");
            require(decoded.snapshot().active(), "it is not the inactive contract");
        });
    }

    // ---- 4. malformed pages -----------------------------------------------------------------

    private static void malformedChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("a declared page length above the budget is refused before allocation", () -> {
            RegistryFriendlyByteBuf buffer = buffer(registries, 4096);
            try {
                buffer.writeVarInt(SkillStatePacket.MAX_BODY_BYTES + 1);
                expectRefused(() -> SkillStatePacket.STREAM_CODEC.decode(buffer), "an over-budget declared length");
            } finally {
                buffer.release();
            }
        });

        check("a declared page length larger than the frame is refused", () -> {
            RegistryFriendlyByteBuf buffer = buffer(registries, 4096);
            try {
                buffer.writeVarInt(4096);
                buffer.writeByte(0);
                expectRefused(() -> SkillStatePacket.STREAM_CODEC.decode(buffer), "a length larger than the remaining bytes");
            } finally {
                buffer.release();
            }
        });

        check("a trailing byte inside one page is refused", () -> {
            SkillStatePacket page = singlePage(new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "one"),
                    Component.literal("技能"), Component.literal("说明"), 1, 1, SkillDefinition.Aim.SELF, false,
                    false, true, SkillText.none(), 0, 0.0D, 0));
            byte[] body = bodyBytes(server, page);
            // Declare one byte more than the body actually carries: the frame now has a trailing byte.
            RegistryFriendlyByteBuf malformed = buffer(registries, 64 * 1024);
            try {
                malformed.writeVarInt(body.length + 1);
                malformed.writeBytes(body);
                malformed.writeByte(0);
                expectRefused(() -> SkillStatePacket.STREAM_CODEC.decode(malformed), "a trailing byte inside a page");
            } finally {
                malformed.release();
            }
        });

        check("a truncated page is refused", () -> {
            SkillStatePacket page = singlePage(new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "two"),
                    Component.literal("技能"), Component.literal("说明"), 1, 1, SkillDefinition.Aim.SELF, false,
                    false, true, SkillText.none(), 0, 0.0D, 0));
            RegistryFriendlyByteBuf full = buffer(registries, 2 * 1024 * 1024);
            try {
                SkillStatePacket.STREAM_CODEC.encode(full, page);
                int size = full.readableBytes();
                RegistryFriendlyByteBuf truncated = buffer(registries, 2 * 1024 * 1024);
                try {
                    truncated.writeBytes(full, full.readerIndex(), size - 4);
                    expectRefused(() -> SkillStatePacket.STREAM_CODEC.decode(truncated), "a truncated page");
                } finally {
                    truncated.release();
                }
            } finally {
                full.release();
            }
        });
    }

    // ---- 5. a text that cannot fit one frame ------------------------------------------------

    private static void oversizedTextChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("an over-long text is refused as a field and replaced by a stable line", () -> {
            StringBuilder huge = new StringBuilder();
            while (huge.length() < 9000) huge.append('描');
            require(SkillPacketIO.verified(Component.literal(huge.toString()), registries) == null,
                    "9000 characters do not fit one component frame");
            require(SkillPacketIO.verified(Component.literal("短说明"), registries) != null,
                    "a normal text is accepted");
            Component fallback = SkillPacketIO.verified(SkillPacketIO.tooLong(), registries);
            require(fallback != null, "the stable short line is itself publishable");
            SkillView view = new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "huge"),
                    Component.literal("技能"), fallback, 1, 1, SkillDefinition.Aim.SELF, false, false, true,
                    SkillText.none(), 0, 0.0D, 0);
            require(roundTrip(server, singlePage(view)).skills().getFirst().description().getContents()
                            instanceof TranslatableContents, "the replacement line still reaches the client as a key");
        });

        check("a page that carries an unverified text cannot be encoded at all", () -> {
            StringBuilder huge = new StringBuilder();
            while (huge.length() < 9000) huge.append('描');
            SkillView oversized = new SkillView(ResourceLocation.fromNamespaceAndPath("tactical", "huge2"),
                    Component.literal(huge.toString()), Component.literal("说明"), 1, 1, SkillDefinition.Aim.SELF,
                    false, false, true, SkillText.none(), 0, 0.0D, 0);
            RegistryFriendlyByteBuf buffer = buffer(registries, 2 * 1024 * 1024);
            try {
                expectRefused(() -> SkillStatePacket.STREAM_CODEC.encode(buffer, singlePage(oversized)),
                        "an over-long component frame");
                require(buffer.readableBytes() == 0, "nothing of a refused page reaches the buffer");
            } finally {
                buffer.release();
            }
        });
    }

    // ---- 6. the preview reason --------------------------------------------------------------

    private static void previewChecks(MinecraftServer server) {
        RegistryAccess registries = server.registryAccess();
        check("an over-long preview reason is replaced instead of refusing the response", () -> {
            StringBuilder huge = new StringBuilder();
            while (huge.length() < 9000) huge.append('描');
            require(SkillPacketIO.verified(Component.literal(huge.toString()), registries) == null,
                    "the raw reason cannot be published");
            Component replacement = SkillPacketIO.verified(SkillPacketIO.tooLong(), registries);
            require(replacement != null, "the stable line can be published instead");
            SkillPreviewResponsePacket packet = new SkillPreviewResponsePacket(SESSION, OVERWORLD, 3, 1L, 10L,
                    ResourceLocation.fromNamespaceAndPath("tactical", "backstab"),
                    SkillTarget.ground(new com.tactical.core.grid.GridPos(0, 64, 0), 0F), false, replacement, 0, 0.0D,
                    new int[0], new int[0]);
            require(roundTrip(server, packet).reason().getContents() instanceof TranslatableContents,
                    "the replacement survives the wire as a key");
        });
    }

    // ---- 7. the two shipped languages -------------------------------------------------------

    private static void languageChecks() {
        Map<String, String> english = langTable("en_us");
        Map<String, String> chinese = langTable("zh_cn");

        check("the builtin skill reads differently in each shipped language", () -> {
            SkillDefinition backstab = SkillRegistry.get(ResourceLocation.fromNamespaceAndPath("tactical", "backstab"));
            require(backstab != null, "the builtin skill is registered");
            String en = resolve(english, backstab.displayName());
            String zh = resolve(chinese, backstab.displayName());
            require(!en.equals(zh), "the keyed name was not translated: " + en);
            require("Backstab".equals(en) && "背刺".equals(zh), "unexpected wording: " + en + " / " + zh);
            String enDescription = resolve(english, backstab.displayDescription());
            String zhDescription = resolve(chinese, backstab.displayDescription());
            require(!enDescription.equals(zhDescription), "the keyed description was not translated");
            require(zhDescription.startsWith("从背后发"), "the Chinese description reads as authored");
        });

        check("a definition without an explicit key reads the same in both languages", () -> {
            SkillDefinition plain = SkillDefinition.builder(ResourceLocation.fromNamespaceAndPath("thirdparty", "plain"))
                    .name("第三方技能").description("第三方说明").build();
            String en = resolve(english, plain.displayName());
            String zh = resolve(chinese, plain.displayName());
            require(en.equals(zh) && en.equals("第三方技能"), "a literal was translated: " + en + " / " + zh);
            require(resolve(english, plain.displayDescription()).equals("第三方说明"),
                    "a key-shaped literal is never looked up either");
        });

        check("a missing key falls back to the authored letter", () -> {
            SkillDefinition orphan = SkillDefinition.builder(ResourceLocation.fromNamespaceAndPath("thirdparty", "orphan"))
                    .name("自定义名").nameKey("thirdparty.skill.missing").build();
            require(resolve(english, orphan.displayName()).equals("自定义名"),
                    "an unsupported key falls back to the authored text");
            require(resolve(chinese, orphan.displayName()).equals("自定义名"),
                    "and the fallback does not depend on the language");
        });

        check("every skill refusal reads differently in the two shipped languages", () -> {
            List<String> keys = skillKeys();
            require(keys.size() > 40, "the skill wording is read from its own constants");
            for (String key : keys) {
                require(english.containsKey(key), "en_us is missing " + key);
                require(chinese.containsKey(key), "zh_cn is missing " + key);
                Component text = SkillText.translatable(key, "1", "2");
                String en = resolve(english, text);
                String zh = resolve(chinese, text);
                require(!en.startsWith(key), key + " was not translated in en_us");
                require(!zh.startsWith(key), key + " was not translated in zh_cn");
            }
        });
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static SkillStatePacket singlePage(SkillView view) {
        return new SkillStatePacket(SESSION, OVERWORLD, true, 1L, 1L, 0, 1, 1, 10.0D, 20.0D, 1.0D, List.of(view),
                List.of()).withMembership(EPOCH, GENERATION);
    }

    private static int measure(SkillStatePacket page, RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = buffer(registries, 2 * CLIENTBOUND_CEILING);
        try {
            SkillStatePacket.STREAM_CODEC.encode(buffer, page);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    /** The encoded body of one page, without its own length prefix. */
    private static byte[] bodyBytes(MinecraftServer server, SkillStatePacket page) {
        RegistryFriendlyByteBuf buffer = buffer(server.registryAccess(), 2 * CLIENTBOUND_CEILING);
        try {
            SkillStatePacket.STREAM_CODEC.encode(buffer, page);
            // The leading VarInt is the body length; its own size then yields the body's start.
            int prefix = 0;
            int bodyLength = 0;
            int shift = 0;
            for (int index = 0; ; index++) {
                byte current = buffer.getByte(index);
                bodyLength |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    prefix = index + 1;
                    break;
                }
                shift += 7;
            }
            byte[] body = new byte[bodyLength];
            buffer.getBytes(prefix, body);
            return body;
        } finally {
            buffer.release();
        }
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> T roundTrip(
            MinecraftServer server, T packet) {
        RegistryFriendlyByteBuf buffer = buffer(server.registryAccess(), 2 * CLIENTBOUND_CEILING);
        try {
            @SuppressWarnings("unchecked")
            net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec =
                    (net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T>) codec(packet);
            codec.encode(buffer, packet);
            int size = buffer.readableBytes();
            T decoded = codec.decode(buffer);
            require(buffer.readableBytes() == 0, "the frame ends exactly at its declared length");
            require(size > 0, "the frame is never empty");
            return decoded;
        } finally {
            buffer.release();
        }
    }

    private static net.minecraft.network.codec.StreamCodec<?, ?> codec(
            net.minecraft.network.protocol.common.custom.CustomPacketPayload packet) {
        if (packet instanceof SkillStatePacket) return SkillStatePacket.STREAM_CODEC;
        if (packet instanceof SkillPreviewResponsePacket) return SkillPreviewResponsePacket.STREAM_CODEC;
        throw new IllegalArgumentException("Unexpected payload " + packet.getClass().getName());
    }

    private static RegistryFriendlyByteBuf buffer(RegistryAccess registries, int capacity) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(256, capacity), registries, ConnectionType.OTHER);
    }

    private static TranslatableContents translatable(Component component) {
        if (component.getContents() instanceof TranslatableContents contents) return contents;
        throw new AssertionError("expected a translation key, got " + component.getContents().getClass().getName());
    }

    private static List<Integer> friendlyIds(int count) {
        List<Integer> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(1000 + index);
        return ids;
    }

    /**
     * The longest description a definition may legally carry: exactly 2048 UTF-16 units, so it is a
     * maximum-length legal text and not an over-limit one.
     *
     * <p>It also contains a newline and one non-BMP character (a surrogate pair - written as an escape so
     * this source file stays ASCII-free of emoji), because both are part of the contract this step must not
     * break. 2045 CJK units (3 wire bytes each) + one newline + one surrogate pair (2 units, 4 bytes) =
     * 2048 UTF-16 units, about 6140 UTF-8 bytes - inside one component frame, and 256 of them are far more
     * than one 1 MiB clientbound packet.</p>
     */
    private static String longDescription() {
        StringBuilder text = new StringBuilder(2048);
        while (text.length() < 2045) text.append("第三方长描述·保留标点与中文");
        text.setLength(2045);
        text.append('\n');
        text.append(Character.toChars(0x1D11E));
        return text.toString();
    }

    private static List<String> skillKeys() {
        List<String> keys = new ArrayList<>();
        for (java.lang.reflect.Field field : SkillText.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                keys.add((String) field.get(null));
            } catch (IllegalAccessException inaccessible) {
                throw new AssertionError(inaccessible);
            }
        }
        return keys;
    }

    private static Map<String, String> langTable(String file) {
        Map<String, String> table = new LinkedHashMap<>(Language.getInstance().getLanguageData());
        String path = "/assets/tactical/lang/" + file + ".json";
        try (InputStream input = SkillWireSelfTest.class.getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("missing language resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                table.put(entry.getKey(), entry.getValue().getAsString());
            }
        } catch (IOException failure) {
            throw new AssertionError("unreadable language resource " + path, failure);
        }
        if (!table.containsKey(SkillText.KEY_REJECT_OUT_OF_RANGE)) {
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
            public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text) {
                return sink -> false;
            }
        };
    }

    // ---- assertion plumbing ----------------------------------------------------------------

    private interface Check {
        void run() throws Exception;
    }

    private static void check(String name, Check check) {
        int number = ++checks;
        try {
            check.run();
            Tactical.LOGGER.info("[Tactical] SKILL_WIRE [{}] PASS {}", number, name);
        } catch (Throwable failure) {
            failures++;
            Tactical.LOGGER.error("[Tactical] SKILL_WIRE [{}] FAIL {}: {}", number, name, failure.toString(), failure);
        }
    }

    private interface Refused {
        void run() throws Exception;
    }

    private static void expectRefused(Refused action, String what) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        } catch (Exception other) {
            throw new AssertionError(what + " failed with an unexpected exception", other);
        }
        throw new AssertionError(what + " was accepted");
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
}
