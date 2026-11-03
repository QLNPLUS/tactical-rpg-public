package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.ui.hud.NativeHud;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/** Global battle controls; deliberately never handles chat or vanilla container screens. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class BattleScreenInput {
    private BattleScreenInput() {}

    public static boolean owns(Screen screen) {
        return BattleInputPolicy.owns(BattleClientState.active, surface(screen));
    }

    /** The one place that classifies what the pointer is looking at while the battle runs. */
    static BattleInputPolicy.Surface surface(Screen screen) {
        return screen == null ? BattleInputPolicy.Surface.WORLD
                : screen instanceof TacticalSurface ? BattleInputPolicy.Surface.TACTICAL
                : screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? BattleInputPolicy.Surface.CONTAINER
                : screen instanceof net.minecraft.client.gui.screens.ChatScreen
                ? BattleInputPolicy.Surface.CHAT : BattleInputPolicy.Surface.OTHER;
    }

    @SubscribeEvent
    public static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft mc=Minecraft.getInstance();
        if(!mc.isWindowActive()||!owns(mc.screen)) DungeonsInputBridge.cancelGesture();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        if (handleKey(event.getScreen(), event.getKeyCode(), event.getScanCode())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        // InputEvent.Key follows screen dispatch. Only handle the no-screen path here.
        if (mc.screen == null && event.getAction() == GLFW.GLFW_PRESS) {
            handleKey(null, event.getKey(), event.getScanCode());
        }
    }

    public static boolean handleKey(Screen screen, int key, int scan) {
        Minecraft mc = Minecraft.getInstance();
        // Escape cancels a size drag first: the preference captured at the press comes back and the
        // release that follows is swallowed, so a cancelled drag cannot become an attack or a toggle.
        if (key == GLFW.GLFW_KEY_ESCAPE && NativeHud.dragging()) {
            NativeHud.cancelDrag(mc);
            return true;
        }
        // The character page is modal and consumes every key itself: digits, the inventory key and every
        // other binding are swallowed there, so cancelling this event would rob the page of its own key.
        if (com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) return false;
        if (!owns(screen) || mc.player == null) return false;
        if (mc.options.keyInventory.matches(key, scan)) {
            // Drain the vanilla click queue before any legacy TacticalClient E loop runs.
            while (mc.options.keyInventory.consumeClick()) { }
            // A waiting player must not reach vanilla inventory/container item moves; only
            // the player whose own turn it is may open the screen and act through it.
            if (!com.tactical.neoforge.client.BattleClientState.isPlayerTurn()) return true;
            mc.setScreen(new InventoryScreen(mc.player));
            return true;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(key, scan)) {
                selectSlot(slot);
                return true;
            }
        }
        return false;
    }

    public static void selectSlot(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || slot < 0 || slot > 8) return;
        mc.player.getInventory().selected = slot;
        syncSelectedSlot();
        // No battle mode/selectedSlot mutation: choosing a tool does not leave aim/move/facing.
    }

    /** Parent attack entry points must call this immediately before their attack payload. */
    public static void syncSelectedSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selected));
        }
    }

    /**
     * The one wheel chain: journal history, then the open page's scrollable list, then the hotbar,
     * then the world.
     *
     * <p>It runs once per event, from the {@code ScreenEvent.MouseScrolled.Pre} listener for screens
     * and from the world scroll event otherwise. A screen's own {@code mouseScrolled} must not call
     * it a second time: a consumed event is canceled there, so that path never runs.</p>
     */
    public static boolean scroll(double delta) {
        Minecraft mc = Minecraft.getInstance();
        // The character page owns the wheel wherever the pointer is, in and out of battle: one notch goes to
        // its own table, and nothing reaches the HUD lists, the hotbar or the camera behind it.
        if (com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) {
            if (mc.screen instanceof NativeTacticalScreen page) {
                double[] cursor = BattleUiRegions.cursorGui();
                page.scrollAt(cursor[0], cursor[1], delta);
            }
            return true;
        }
        if (!owns(mc.screen) || mc.player == null) return false;
        // An open modal owns the wheel completely. This is asked before the HUD's lists, before the
        // page's own list, before the hotbar and before the camera, so a notch during colour editing
        // cannot change anything behind the popover.
        if (mc.screen instanceof NativeTacticalScreen modal && modal.consumesWheel()) return true;
        double[] cursor = BattleUiRegions.cursorGui();
        if (NativeHud.scroll(cursor[0], cursor[1], delta)) return true;
        if (mc.screen instanceof NativeTacticalScreen nativeScreen
                && nativeScreen.scrollAt(cursor[0], cursor[1], delta)) return true;
        if (BattleUiRegions.hotbarSlot()>=0) {
            if(delta!=0)selectSlot(BattleInputPolicy.scrollSlot(mc.player.getInventory().selected,delta));
            return true;
        }
        if (BattleUiRegions.overUi()) return false;
        if (DungeonsInputBridge.enabled() && DungeonsInputBridge.physicalMiddleHeld()) {
            // A UI-origin held middle button must not become camera OR hotbar input.
            if (DungeonsInputBridge.middleHeld()) DungeonsInputBridge.scroll(delta);
        } else if (delta != 0) {
            selectSlot(BattleInputPolicy.scrollSlot(mc.player.getInventory().selected, delta));
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onWorldScroll(InputEvent.MouseScrollingEvent event) {
        if (Minecraft.getInstance().screen == null && (scroll(event.getScrollDeltaY())
                || (owns(null)&&BattleUiRegions.overUi()))) {
            WheelTrace.notch("world-event", event.getScrollDeltaY(), "consumed");
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!owns(mc.screen)) return;
        // The HUD always gets the release half of a gesture it started, before the hotbar or anything
        // else claims it: a press on a roster row or a size handle whose pointer ended over a hotbar slot
        // must end here, not hang until the next press.
        // The character page is the top layer under the pointer: the press is delivered to the screen itself,
        // the HUD and the hotbar are not offered it, and a camera gesture cannot start behind the page.
        if (com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) event.setCanceled(true);
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_RELEASE) {
            double[] cursor = BattleUiRegions.cursorGui();
            if (NativeHud.release(cursor[0], cursor[1], event.getButton())) {
                event.setCanceled(true);
                return;
            }
        }
        // A visible HUD control is the upper layer under the pointer, so it keeps the press even when
        // the vanilla hotbar band also covers that point: an expanded journal's grip sits in the quick
        // bar's area, and its drag must start instead of selecting a slot somewhere behind it. Without
        // an open page this event is the only delivery path; with one, the page hands the press to the
        // HUD itself, so consuming it here would drop it.
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT && event.getAction() == GLFW.GLFW_PRESS) {
            double[] cursor = BattleUiRegions.cursorGui();
            BattleInputPolicy.PressOwner owner =
                    BattleInputPolicy.pressOwner(NativeHud.claimsPress(cursor[0], cursor[1]), surface(mc.screen));
            if (owner == BattleInputPolicy.PressOwner.HUD) {
                NativeHud.click(cursor[0], cursor[1], event.getButton());
                event.setCanceled(true);
                return;
            }
            if (owner == BattleInputPolicy.PressOwner.PAGE) return;
        }
        if(event.getButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT && BattleUiRegions.hotbarSlot()>=0) {
            if(event.getAction()==GLFW.GLFW_PRESS) selectSlot(BattleUiRegions.hotbarSlot());
            event.setCanceled(true);return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && DungeonsInputBridge.enabled()) {
            if (DungeonsInputBridge.middleButton(event.getAction() != GLFW.GLFW_RELEASE)) event.setCanceled(true);
            return;
        }
        if (DungeonsInputBridge.middleHeld()) {
            // Do not let a drag chord turn into an aim attack or a context menu.
            event.setCanceled(true);
            return;
        }
        // Without a screen this listener is the only delivery path, so the rest of the HUD - a roster
        // row, the rail, a journal entry - is offered the press here, before the world branch in
        // BattleInput, or that click would open the command panel or attack whatever stands under it.
        // With a tactical screen open the page already offered the press above, so it is not repeated.
        if (event.getAction() == GLFW.GLFW_PRESS) {
            double[] cursor = BattleUiRegions.cursorGui();
            if (mc.screen == null && NativeHud.click(cursor[0], cursor[1], event.getButton())) {
                event.setCanceled(true);
            }
        }
    }
}
