package com.tactical.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dependency gate that replaced the page-structure test.
 *
 * <p>Stage D removed the bundled UI framework: every screen, panel and HUD is drawn by this mod's own
 * native toolkit. These checks fail if the dependency — or a page source, a mixin config or a
 * metadata requirement belonging to it — comes back, so "no AUI" is a build-time fact rather than a
 * memory. They are static packaging checks only: real rendering and real input are accepted in
 * runClient, and nothing here proves a frame was drawn.</p>
 */
class NativeUiOnlyTest {

    /** Entry types of the retired dependency; a classpath still carrying it fails here. */
    private static final String[] RETIRED_TYPES = {
            "com.sighs.apricityui.ApricityUI",
            "com.sighs.apricityui.init.Document",
            "com.sighs.apricityui.screen.ApricityContainerScreen",
    };

    /** Native types the client cannot work without; a missing one fails at startup, not here. */
    private static final String[] NATIVE_TYPES = {
            "com.tactical.neoforge.client.ui.screen.NativeTacticalScreen",
            "com.tactical.neoforge.client.ui.screen.TacticalSurface",
            "com.tactical.neoforge.client.ui.hud.NativeHud",
            "com.tactical.neoforge.client.ui.command.BattleMenuScreen",
            "com.tactical.neoforge.client.ui.panel.NativePanelScreen",
            "com.tactical.neoforge.client.UiZoom",
            "com.tactical.neoforge.client.CssScale",
    };

    private static boolean classpathHas(String type) {
        try {
            Class.forName(type, false, NativeUiOnlyTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    @Test
    void theRetiredUiDependencyIsNotOnTheClasspath() {
        for (String type : RETIRED_TYPES) {
            assertFalse(classpathHas(type), type + " must not be on the compile or runtime classpath");
        }
        assertNull(getClass().getResource("/assets/apricityui"),
                "no page source may be packaged under assets/apricityui");
    }

    @Test
    void everyNativeTypeIsPresent() {
        for (String type : NATIVE_TYPES) {
            assertTrue(classpathHas(type), type + " is required by the native interface");
        }
    }

    @Test
    void theMixinConfigOfTheRetiredDependencyIsGone() {
        assertNull(getClass().getResource("/tactical.ui.mixins.json"),
                "the retired mixin config must not be packaged");
        String metadata = resource("/META-INF/neoforge.mods.toml");
        assertFalse(metadata.contains("tactical.ui.mixins.json"),
                "the mod metadata must not declare the retired mixin config");
        for (String kept : new String[]{"tactical.camera.mixins.json", "tactical.effects.mixins.json",
                "tactical.creeper.mixins.json", "tactical.server.mixins.json",
                "tactical.dungeons_input.mixins.json"}) {
            assertTrue(metadata.contains(kept), "the mod metadata must still declare " + kept);
        }
    }

    @Test
    void theMetadataRequiresNoUiDependency() {
        String metadata = resource("/META-INF/neoforge.mods.toml");
        assertFalse(metadata.toLowerCase(java.util.Locale.ROOT).contains("apricity"),
                "the mod metadata must not require the retired client dependency");
        // The optional camera dependency is a real, separate mod and stays.
        assertTrue(metadata.contains("dungeons_iso"), "the optional camera dependency must stay");
        assertTrue(metadata.contains("type = \"required\""), "neoforge/minecraft stay required");
    }

    @Test
    void noProductionSourceImportsTheRetiredDependency() throws IOException {
        byte[] needle = "com.sighs.apricityui".getBytes(StandardCharsets.US_ASCII);
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!Files.isReadable(file)) continue;
                    // Bytes, not text: the resource tree also holds binary assets.
                    if (indexOf(Files.readAllBytes(file), needle) >= 0) offenders.add(file.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "production sources still reference the retired UI dependency: " + offenders);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) continue outer;
            }
            return start;
        }
        return -1;
    }

    private String resource(String path) {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "missing packaged resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new AssertionError("cannot read packaged resource " + path, error);
        }
    }
}
