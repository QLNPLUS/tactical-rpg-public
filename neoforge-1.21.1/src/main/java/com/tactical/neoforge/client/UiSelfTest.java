package com.tactical.neoforge.client;

import com.sighs.apricityui.dom.DocumentRegistry;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.tactical.neoforge.Tactical;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game UI acceptance driver, enabled with {@code -Dtactical.selftest.ui=true}.
 *
 * <p>Drives the paths a human would otherwise click by hand and logs one
 * PASS/FAIL line per check. The decisive inventory check is hit-testing:
 * {@link ApricityContainerScreen#findSlotIndexAt} only returns an index when the
 * menu's slot geometry matches the DOM, which is exactly what fails when a
 * container is laid out without an explicit width or with a conflicting size.
 */
public final class UiSelfTest {

    private static final int SETTLE_TICKS = 20;

    /** A step waiting on the server must give up eventually rather than hang. */
    private static final int STEP_TIMEOUT_TICKS = 200;

    private int tick;
    private int step;
    private boolean finished;

    private final List<String> failures = new ArrayList<>();

    private UiSelfTest() {
    }

    public static UiSelfTest start() {
        return new UiSelfTest();
    }

    public void tick() {
        if (finished) {
            return;
        }
        tick++;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (tick < SETTLE_TICKS) {
            return;
        }
        // Each step waits for its own precondition instead of a fixed delay: the
        // server needs a round trip to spawn mobs, start the battle and push the
        // first state packet, and none of that is measurable in ticks here.
        // 'tick' restarts at 0 for every step, so it doubles as the step clock.
        if (tick > STEP_TIMEOUT_TICKS) {
            report("step" + step + ".timeout", false, "precondition never became true");
            finish();
            return;
        }
        switch (step) {
            case 0 -> {
                minecraft.player.connection.sendCommand("summon minecraft:zombie ~3 ~ ~2");
                next();
            }
            case 1 -> {
                if (tick < SETTLE_TICKS * 2) {
                    return;
                }
                minecraft.player.connection.sendCommand("tactical battle force");
                next();
            }
            case 2 -> {
                // The HUD page only exists once the battle is actually active.
                if (!BattleClientState.active) {
                    return;
                }
                report("battle.active", true, "phase entered");
                next();
            }
            case 3 -> {
                if (tick < SETTLE_TICKS) {
                    return;
                }
                BattleMenu.open();
                next();
            }
            case 4 -> {
                if (tick < SETTLE_TICKS) {
                    return;
                }
                checkScaling();
                next();
            }
            case 5 -> {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.tactical.neoforge.network.OpenInventoryPacket());
                next();
            }
            case 6 -> {
                if (tick < SETTLE_TICKS * 2) {
                    return;
                }
                checkInventory(minecraft);
                next();
            }
            default -> finish();
        }
    }

    private void next() {
        step++;
        tick = 0;
    }

    /** Ctrl+wheel must move every battle page and keep them in sync. */
    private void checkScaling() {
        double hudBefore = zoomOf("tactical/overlays/battle_hud.html");
        double menuBefore = zoomOf("tactical/screens/battle_menu.html");

        UiZoom.scale(1.0D);

        double hudAfter = zoomOf("tactical/overlays/battle_hud.html");
        double menuAfter = zoomOf("tactical/screens/battle_menu.html");

        report("zoom.hud", hudAfter > hudBefore, "%.2f -> %.2f".formatted(hudBefore, hudAfter));
        report("zoom.menu", menuAfter > menuBefore, "%.2f -> %.2f".formatted(menuBefore, menuAfter));
        report("zoom.sync", Math.abs(hudAfter - menuAfter) < 0.001D,
                "hud=%.2f menu=%.2f".formatted(hudAfter, menuAfter));

        while (UiZoom.zoom() > 1.0D) {
            UiZoom.scale(-1.0D);
        }
    }

    private double zoomOf(String path) {
        for (Document document : DocumentRegistry.getAll()) {
            if (path.equals(document.getPath())) {
                return document.getViewport().zoom();
            }
        }
        return -1.0D;
    }

    private void checkInventory(Minecraft minecraft) {
        if (!(minecraft.screen instanceof ApricityContainerScreen screen)) {
            report("inventory.screen", false,
                    "screen=" + (minecraft.screen == null ? "null" : minecraft.screen.getClass().getSimpleName()));
            return;
        }
        report("inventory.screen", true, "container screen active");

        Document document = screen.getLinkedDocument();
        report("inventory.document", document != null, document == null ? "null" : document.getPath());
        if (document == null) {
            return;
        }

        int slotElements = 0;
        for (Element element : document.getElements()) {
            if (element instanceof com.sighs.apricityui.element.Slot) {
                slotElements++;
            }
        }
        report("inventory.slots", slotElements >= 36, "slot-elements=" + slotElements);

        // Sweep the screen: every interactable slot must be reachable by a
        // pointer position, otherwise clicks land on nothing.
        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        java.util.Set<Integer> reached = new java.util.HashSet<>();
        for (int y = top; y < top + 400; y += 4) {
            for (int x = left; x < left + 640; x += 4) {
                int index = screen.findSlotIndexAt(x, y);
                if (index >= 0) {
                    reached.add(index);
                }
            }
        }
        report("inventory.hit-testing", reached.size() >= 36,
                "reachable=" + reached.size() + " guiLeft=" + left + " guiTop=" + top);
    }

    private void finish() {
        finished = true;
        if (failures.isEmpty()) {
            Tactical.LOGGER.info("[Tactical] [UITEST] RESULT PASS");
        } else {
            Tactical.LOGGER.error("[Tactical] [UITEST] RESULT FAIL - {}", String.join(" | ", failures));
        }
    }

    private void report(String name, boolean ok, String detail) {
        if (ok) {
            Tactical.LOGGER.info("[Tactical] [UITEST] PASS {} ({})", name, detail);
        } else {
            failures.add(name);
            Tactical.LOGGER.error("[Tactical] [UITEST] FAIL {} ({})", name, detail);
        }
    }
}
