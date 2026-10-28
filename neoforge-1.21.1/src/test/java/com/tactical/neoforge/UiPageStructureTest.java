package com.tactical.neoforge;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

/** Packaging checks only. Real input, rendering and movement are accepted in runClient. */
class UiPageStructureTest {
    private String resource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/assets/apricityui/apricity/tactical/" + path)) {
            assertNotNull(in, "missing AUI resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Theme packaging is not proof of native rendering or input acceptance. */
    @Test void journalThemeIsInlinedAndUsesMinecraftFont() throws Exception {
        for (String page : new String[]{"screens/battle_menu.html", "screens/battle_facing.html",
                "screens/battle_inventory.html", "screens/battle_aim.html", "screens/battle_skills.html",
                "screens/battle_move_choice.html", "screens/tactical_test.html", "screens/click_probe.html",
                "overlays/battle_hud.html", "overlays/battle_log.html", "overlays/battle_turn_bar.html"}) {
            String html = resource(page);
            assertTrue(html.contains("BEGIN TACTICAL JOURNAL THEME"), page + " must inline the shared theme");
            assertTrue(html.contains("data-tactical-theme=\"gilded-journal\""), page);
            assertTrue(html.contains("name=\"aui-font-mode\" content=\"mc\""), page + " must retain vanilla type");
            assertFalse(html.contains("content=\"web\""), page + " must not silently re-enable web type");
            assertTrue(html.contains("textures/journal/paper-tile.png"), page);
            assertTrue(html.contains("textures/journal/leather-frame.png"), page);
            assertFalse(html.contains("oil-art-assets"), page + " must not depend on preview assets");
            assertFalse(html.contains("dungeons-2"), page + " must not retain a conflicting old theme");
            assertFalse(html.contains("<link"), page + " keeps AUI stylesheet loading self-contained");
        }
        for (String page : new String[]{"screens/battle_menu.html", "screens/battle_facing.html",
                "screens/battle_inventory.html", "screens/battle_aim.html", "screens/battle_skills.html"}) {
            assertTrue(resource(page).contains("aui-mouse-events"), page + " must intercept mouse input");
        }
        assertTrue(resource("overlays/battle_hud.html").contains("pointer-events:none"));
        String menu = resource("screens/battle_menu.html");
        for (String id : new String[]{"opt-move", "opt-skill", "opt-attack", "opt-tool", "opt-defend", "opt-end",
                "opt-flee", "menu-close", "scale-minus", "scale-plus", "scale-reset", "hp", "mp", "recovery"}) {
            assertTrue(menu.contains("id=\"" + id + "\""), "journal lost #" + id);
        }
        String rail = resource("overlays/battle_turn_bar.html");
        assertTrue(rail.contains("charge-root") && rail.contains("turn-portrait-frame-current-hover.png"));
        assertFalse(rail.contains("#b4e8ff"), "flat blue highlight must not cover the metal");
    }

    @Test void journalSettingsAreOptInAndSealIsAnchoredToLeather() throws Exception {
        String page = resource("screens/battle_menu.html");
        assertTrue(page.contains("id=\"menu-settings\""));
        assertTrue(page.contains("aria-controls=\"journal-settings\" aria-expanded=\"false\""));
        assertTrue(page.contains("id=\"journal-settings\" aria-label=\"界面设置\" aria-hidden=\"true\""));
        assertTrue(page.contains(".journal-controls { display:none;"));
        assertTrue(page.contains(".journal-controls.is-open { display:block; }"));
        assertTrue(page.contains("textures/journal/settings-gear.png"));
        int side = page.indexOf("<aside class=\"journal-side\"");
        int seal = page.indexOf("id=\"journal-seal\"");
        assertTrue(side >= 0 && seal > side && seal < page.indexOf("</aside>", side));
        assertTrue(page.contains(".journal-seal { position:absolute; left:.785714em; bottom:-6em;"));
    }

    @Test void commandsScrollIndependentlyAndPresentationTextIsNotSelectable() throws Exception {
        String menu = resource("screens/battle_menu.html");
        assertTrue(menu.contains("id=\"journal-actions\""));
        assertTrue(menu.contains(".journal-actions { min-height:0; overflow-y:auto; overflow-x:hidden; }"));
        assertTrue(menu.contains(".journal-page { overflow-x:hidden; overflow-y:hidden; }"));
        assertTrue(menu.contains("user-select:none"));
        assertTrue(menu.contains("range-legend-toggle"));
        assertTrue(menu.contains("aria-pressed=\"false\""));
        assertTrue(menu.contains("<title>战斗指令</title>"));
        assertFalse(menu.contains("鎏金旅札"));
        String log = resource("overlays/battle_log.html");
        assertTrue(log.contains(".log-text-inner { position:relative; left:0;"));
        assertTrue(log.contains("pointer-events:none; transition:none;"));
        assertTrue(log.contains("战斗记录"));
    }

    @Test void repairedBookmarkHasNoMetalBandAndGearHasAClearHole() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(
                "/assets/apricityui/apricity/tactical/textures/journal/bookmark.png")) {
            assertNotNull(in);
            var bookmark = javax.imageio.ImageIO.read(in);
            assertEquals(80, bookmark.getWidth());
            assertEquals(260, bookmark.getHeight());
            for (int y = 20; y < 220; y++) {
                assertEquals(0, bookmark.getRGB(2, y) >>> 24, "no cropped metal outside the ribbon");
                for (int x = 24; x < 56; x++) {
                    int rgb = bookmark.getRGB(x, y);
                    int red = (rgb >>> 16) & 255, green = (rgb >>> 8) & 255;
                    assertFalse(red > 145 && green > 100, "gold band must not cross the wine leather");
                }
            }
        }
        try (InputStream in = getClass().getResourceAsStream(
                "/assets/apricityui/apricity/tactical/textures/journal/settings-gear.png")) {
            assertNotNull(in);
            var gear = javax.imageio.ImageIO.read(in);
            assertEquals(64, gear.getWidth());
            assertEquals(64, gear.getHeight());
            assertEquals(0, gear.getRGB(32, 32) >>> 24);
            for (int tooth = 0; tooth < 8; tooth++) {
                double angle = tooth * Math.PI / 4;
                int x = (int) Math.round(32 + 26 * Math.cos(angle));
                int y = (int) Math.round(32 + 26 * Math.sin(angle));
                assertTrue((gear.getRGB(x, y) >>> 24) > 0, "all eight teeth are painted");
            }
        }
    }

    @Test void goldFrameStatesHaveIdenticalClearOpeningsAndNoBakedEntity() throws Exception {
        int[] alpha = null;
        java.util.Set<Integer> stateColors = new java.util.HashSet<>();
        for (String suffix : new String[]{"", "-current", "-hover", "-current-hover"}) {
            String path = "/assets/apricityui/apricity/tactical/textures/turn-portrait-frame" + suffix + ".png";
            try (InputStream in = getClass().getResourceAsStream(path)) {
                assertNotNull(in, path);
                var image = javax.imageio.ImageIO.read(in);
                assertEquals(144, image.getWidth());
                assertEquals(168, image.getHeight());
                int[] current = new int[144 * 168];
                for (int y = 0; y < 168; y++) for (int x = 0; x < 144; x++) {
                    int rgba = image.getRGB(x, y);
                    current[y * 144 + x] = rgba >>> 24;
                    if (x >= 20 && x < 124 && y >= 20 && y < 148) assertEquals(0, rgba, "no baked entity/backdrop");
                    if (x < 2 || x >= 142 || y < 2 || y >= 166) assertEquals(0, rgba, "clear outside margin");
                }
                if (alpha == null) alpha = current;
                else assertArrayEquals(alpha, current, "state changes must not alter the aperture");
                assertEquals(255, image.getRGB(72, 10) >>> 24);
                stateColors.add(image.getRGB(72, 10));
            }
        }
        assertEquals(4, stateColors.size(), "all four frame states must remain distinguishable");
    }

    @Test void nativeInventoryLayoutIsCenteredAndUsesPlayerIndices() throws Exception {
        String page = resource("screens/battle_inventory.html");
        assertTrue(page.contains("slot > item { left:0; top:0; width:100%; height:100%"),
                "AUI 1.2.8 paints Item, so its box must fill the slot before icon scale");
        assertFalse(page.contains(".player-container { display:block"), "keep the container's native grid host");
        assertTrue(page.indexOf("slot-index=\"9\"") < page.indexOf("slot-index=\"0\""),
                "backpack 9..35 must precede hotbar 0..8");
    }

    @Test void inventoryRetainsAllMenuIndices() throws Exception {
        String page = resource("screens/battle_inventory.html");
        assertTrue(page.contains("bind=\"player\""));
        for (int i = 0; i < 36; i++) assertTrue(page.contains("slot-index=\"" + i + "\""), "slot " + i);
        assertTrue(page.contains("id=\"inv-back\""));
        assertTrue(page.contains("id=\"scale-reset\""));
        assertTrue(page.contains("id=\"inv-context\""));
    }
}
