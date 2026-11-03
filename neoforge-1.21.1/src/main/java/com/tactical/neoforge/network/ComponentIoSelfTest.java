package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.function.Consumer;

/**
 * Disposable dedicated-server fixture for the native component frame: {@code -Dtactical.selftest=component_io}.
 *
 * <p>The unit tests can only drive the frame with a plain payload codec, because building the native
 * component codec initializes {@code Style -> HoverEvent -> ItemStack} and NeoForge's {@code FeatureFlags}
 * need FML's mod list. This fixture runs the same entry points inside a real FML server, on the server's
 * own registry access, so the native round-trip — key and argument structure, registry-dependent hover,
 * two consecutive frames, and the malformed-frame refusals — is actually executed:</p>
 *
 * <p>Every item logs one {@code COMPONENT_IO [n] PASS/FAIL <name>} line, the run ends with a single
 * {@code PASSED}/{@code FAILED} marker, and a failure exits the server non-zero instead of leaving a green
 * Gradle task behind. Nothing here touches battles, the HUD, players or a network channel.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class ComponentIoSelfTest {

    private static final String CHINESE = "对僵尸造成 3 点伤害";
    private static final String UNICODE = "⚔ ünïcode 与 emoji 🗡";
    /** A literal that only looks like a translation key: it must never be treated as one. */
    private static final String KEY_LOOKING = "tactical.skill.backstab.name";
    private static final String DAMAGE_KEY = "tactical.journal.damage";
    private static final String ENTITY_KEY = "entity.minecraft.zombie";

    private static int checks;
    private static int failures;

    private ComponentIoSelfTest() {
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!"component_io".equals(System.getProperty("tactical.selftest", ""))) return;
        MinecraftServer server = event.getServer();
        checks = 0;
        failures = 0;
        Tactical.LOGGER.info("[Tactical] COMPONENT_IO beginning: frame limit {} bytes, NBT quota {} bytes",
                ComponentIO.MAX_FRAME_BYTES, ComponentIO.MAX_NBT_BYTES);
        try {
            run(server);
        } catch (Throwable failure) {
            Tactical.LOGGER.error("[Tactical] COMPONENT_IO SELFTEST FAILED: fixture crashed after {} checks",
                    checks, failure);
            halt(server, 1);
            return;
        }
        if (failures != 0) {
            Tactical.LOGGER.error("[Tactical] COMPONENT_IO SELFTEST FAILED: {} of {} checks failed", failures, checks);
            halt(server, 1);
            return;
        }
        Tactical.LOGGER.info("[Tactical] COMPONENT_IO SELFTEST PASSED: all {} checks on the native component codec", checks);
        halt(server, 0);
    }

    private static void run(MinecraftServer server) {
        check("literal chinese keeps its text", () -> {
            RoundTrip trip = roundTrip(server, Component.literal(CHINESE));
            assertText(trip.decoded, CHINESE);
            if (trip.leftover != 0) throw new AssertionError(trip.leftover + " bytes were left after the frame");
        });
        check("literal unicode keeps its text", () -> assertText(roundTrip(server, Component.literal(UNICODE)).decoded, UNICODE));
        check("empty literal stays empty text", () -> assertText(roundTrip(server, Component.literal("")).decoded, ""));

        check("key-looking literal is never looked up as a key", () -> {
            Component decoded = roundTrip(server, Component.literal(KEY_LOOKING)).decoded;
            assertText(decoded, KEY_LOOKING);
            if (decoded.getContents() instanceof TranslatableContents) {
                throw new AssertionError("a literal that merely looks like a key was turned into a translation");
            }
        });

        check("translatable key travels unresolved", () -> {
            Component decoded = roundTrip(server, Component.translatable(DAMAGE_KEY)).decoded;
            TranslatableContents contents = translatable(decoded);
            assertEquals(DAMAGE_KEY, contents.getKey());
            if (contents.getFallback() != null) throw new AssertionError("unexpected fallback " + contents.getFallback());
            // Resolution happens only when a display asks for a string; the note records what the server
            // itself would print, which is why nothing may pre-resolve here.
            Tactical.LOGGER.info("[Tactical] COMPONENT_IO note: key={} resolves to '{}' only at getString()",
                    contents.getKey(), decoded.getString());
        });

        check("nested translatable argument stays translatable", () -> {
            // A translatable cannot be collapsed into text, so it must come back as a component that still
            // carries its own key: each client resolves it in its own language.
            Component nested = Component.translatable(ENTITY_KEY);
            Object[] args = translatable(
                    roundTrip(server, Component.translatable(DAMAGE_KEY, nested, 3)).decoded).getArgs();
            assertEquals(2, args.length);
            if (!(args[0] instanceof Component argument)) {
                throw new AssertionError("the nested component was flattened into " + args[0].getClass().getName());
            }
            if (!(argument.getContents() instanceof TranslatableContents nestedContents)) {
                throw new AssertionError("the nested component lost its translation: " + argument.getContents());
            }
            assertEquals(ENTITY_KEY, nestedContents.getKey());
            Tactical.LOGGER.info("[Tactical] COMPONENT_IO note: nested argument class={} key={}, second argument class={}",
                    args[0].getClass().getName(), nestedContents.getKey(), args[1].getClass().getName());
        });

        check("nested pure literal argument is canonically collapsed to text", () -> {
            // The native argument codec collapses a plain literal component with no style and no siblings
            // into a string. That is the native canonicalisation, not a loss of localisation: only a
            // plain-text value can be collapsed, and the text itself has to survive unchanged.
            Object[] args = translatable(
                    roundTrip(server, Component.translatable(DAMAGE_KEY, Component.literal(CHINESE))).decoded).getArgs();
            assertEquals(1, args.length);
            if (args[0] instanceof String text) {
                assertEquals(CHINESE, text);
                Tactical.LOGGER.info("[Tactical] COMPONENT_IO note: nested pure literal came back as {}", args[0].getClass().getName());
            } else {
                throw new AssertionError("the nested pure literal is no longer the native canonical form: "
                        + args[0].getClass().getName());
            }
        });

        check("numeric argument keeps its value", () -> {
            Object[] args = translatable(roundTrip(server, Component.translatable(DAMAGE_KEY, 3)).decoded).getArgs();
            assertEquals(1, args.length);
            if (!(args[0] instanceof Number number)) throw new AssertionError("the number became " + args[0]);
            assertEquals(3, number.intValue());
        });

        check("two consecutive frames keep their own component", () -> {
            withBuffer(server, buffer -> {
                ComponentIO.write(buffer, Component.literal(CHINESE));
                ComponentIO.write(buffer, Component.translatable(DAMAGE_KEY, 7));
                assertText(ComponentIO.read(buffer), CHINESE);
                TranslatableContents second = translatable(ComponentIO.read(buffer));
                assertEquals(DAMAGE_KEY, second.getKey());
                assertEquals(7, ((Number) second.getArgs()[0]).intValue());
                if (buffer.readableBytes() != 0) throw new AssertionError("frames were not consumed exactly");
            });
        });

        check("style survives", () -> {
            Component original = Component.literal("风格").withStyle(Style.EMPTY.withBold(true).withColor(0xFFAA00));
            Component decoded = roundTrip(server, original).decoded;
            if (!decoded.getStyle().isBold()) throw new AssertionError("bold was lost");
            if (decoded.getStyle().getColor() == null || decoded.getStyle().getColor().getValue() != 0xFFAA00) {
                throw new AssertionError("colour was lost");
            }
        });

        check("item hover keeps its registry-resolved stack", () -> {
            ItemStack stack = new ItemStack(Items.STONE, 5);
            Component original = Component.literal("物品")
                    .withStyle(Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM,
                            new HoverEvent.ItemStackInfo(stack))));
            Component decoded = roundTrip(server, original).decoded;
            HoverEvent hover = decoded.getStyle().getHoverEvent();
            if (hover == null) throw new AssertionError("the hover was lost");
            if (hover.getAction() != HoverEvent.Action.SHOW_ITEM) throw new AssertionError("hover action changed");
            ItemStack shown = hover.getValue(HoverEvent.Action.SHOW_ITEM).getItemStack();
            if (shown.getItem() != Items.STONE) throw new AssertionError("hover item is " + shown.getItem());
            assertEquals(5, shown.getCount());
            // The same component cannot be built without a registry context; record which side needs it.
            Tactical.LOGGER.info("[Tactical] COMPONENT_IO note: item hover encoded on the server registry access; "
                    + "empty-context encode {}", emptyContextOutcome(original));
        });

        check("a payload just inside the frame limit still round-trips", () -> {
            String near = "a".repeat(8000);
            assertText(roundTrip(server, Component.literal(near)).decoded, near);
        });

        check("over-sized component is refused without writing a frame", () -> withBuffer(server, buffer -> {
            expectFrameException("over-size component",
                    () -> ComponentIO.write(buffer, Component.literal("对".repeat(4000))));
            if (buffer.readableBytes() != 0) throw new AssertionError("a refused component left a length prefix behind");
        }));

        check("malformed payload is refused", () -> withBuffer(server, buffer -> {
            buffer.writeVarInt(4);
            buffer.writeBytes(new byte[]{0x7F, 0x7F, 0x7F, 0x7F});
            FrameFailure failure = expectFrameException("malformed payload", () -> ComponentIO.read(buffer));
            if (failure.thrown.getCause() == null) throw new AssertionError("the codec failure was not kept as the cause");
        }));

        check("trailing bytes inside a frame are refused", () -> {
            withBuffer(server, frame -> withBuffer(server, padded -> {
                ComponentIO.write(frame, Component.literal(CHINESE));
                int payload = frame.readVarInt();
                padded.writeVarInt(payload + 1);
                padded.writeBytes(frame, frame.readerIndex(), payload);
                padded.writeByte(0x00);
                FrameFailure failure = expectFrameException("trailing bytes", () -> ComponentIO.read(padded));
                if (failure.thrown.getCause() != null) {
                    throw new AssertionError("trailing bytes should be refused by the frame, not by the codec");
                }
                if (!String.valueOf(failure.thrown.getMessage()).contains("trailing")) {
                    throw new AssertionError("unexpected message: " + failure.thrown.getMessage());
                }
            }));
        });

        check("a negative declared length is refused", () -> withBuffer(server, buffer -> {
            buffer.writeVarInt(-3);
            buffer.writeBytes(new byte[8]);
            expectFrameException("negative length", () -> ComponentIO.read(buffer));
            if (buffer.readableBytes() != 8) throw new AssertionError("more than the length was consumed");
        }));
    }

    /** Encodes and decodes one component on the real server buffers and reports what was left unread. */
    private static RoundTrip roundTrip(MinecraftServer server, Component original) {
        RoundTrip result = new RoundTrip();
        withBuffer(server, buffer -> {
            ComponentIO.write(buffer, original);
            result.encodedBytes = buffer.readableBytes();
            result.decoded = ComponentIO.read(buffer);
            result.leftover = buffer.readableBytes();
            result.identical = original.equals(result.decoded);
            Tactical.LOGGER.info("[Tactical] COMPONENT_IO note: {} bytes on the wire, identical object shape={}",
                    result.encodedBytes, result.identical);
        });
        if (result.decoded == null) throw new AssertionError("nothing decoded");
        return result;
    }

    private static String emptyContextOutcome(Component component) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER);
        try {
            ComponentIO.write(buffer, component);
            ComponentIO.read(buffer);
            return "also succeeded without registries";
        } catch (RuntimeException refused) {
            return "refused without registries (" + refused.getClass().getSimpleName() + ")";
        } finally {
            buffer.release();
        }
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

    private static void assertText(Component decoded, String expected) {
        if (!(decoded.getContents() instanceof PlainTextContents contents)) {
            throw new AssertionError("expected literal text, got " + decoded.getContents().getClass().getName());
        }
        assertEquals(expected, contents.text());
    }

    private static TranslatableContents translatable(Component component) {
        if (!(component.getContents() instanceof TranslatableContents contents)) {
            throw new AssertionError("expected a translation, got " + component.getContents().getClass().getName());
        }
        return contents;
    }

    private static FrameFailure expectFrameException(String what, Runnable body) {
        try {
            body.run();
        } catch (ComponentIO.FrameException expected) {
            return new FrameFailure(expected);
        }
        throw new AssertionError(what + " was accepted");
    }

    private static void check(String name, Runnable body) {
        checks++;
        try {
            body.run();
            Tactical.LOGGER.info("[Tactical] COMPONENT_IO [{}] PASS {}", checks, name);
        } catch (Throwable failure) {
            failures++;
            Tactical.LOGGER.error("[Tactical] COMPONENT_IO [{}] FAIL {}: {}", checks, name, failure.toString());
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    /**
     * Stops the fixture server. {@code halt(true)} would join the current (server) thread and never
     * return, so the graceful flag stays false and a failure exits the JVM non-zero immediately.
     */
    private static void halt(MinecraftServer server, int code) {
        server.halt(false);
        if (code != 0) Runtime.getRuntime().halt(code);
    }

    private static final class RoundTrip {
        private Component decoded;
        private int encodedBytes;
        private int leftover;
        private boolean identical;
    }

    private record FrameFailure(ComponentIO.FrameException thrown) {
    }
}
