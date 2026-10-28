package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
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
        return BattleInputPolicy.owns(BattleClientState.active, screen == null
                ? BattleInputPolicy.Surface.WORLD : screen instanceof TacticalScreen
                ? BattleInputPolicy.Surface.TACTICAL : screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? BattleInputPolicy.Surface.CONTAINER : screen instanceof net.minecraft.client.gui.screens.ChatScreen
                ? BattleInputPolicy.Surface.CHAT : BattleInputPolicy.Surface.OTHER);
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

    public static boolean scroll(double delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!owns(mc.screen) || mc.player == null) return false;
        if (BattleLog.scrollAt(BattleUiRegions.cursor(),delta)) return true;
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
                || (owns(null)&&BattleUiRegions.overUi()))) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!owns(mc.screen)) return;
        if(event.getButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT && BattleUiRegions.hotbarSlot()>=0) {
            if(event.getAction()==GLFW.GLFW_PRESS) selectSlot(BattleUiRegions.hotbarSlot());
            event.setCanceled(true);return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && DungeonsInputBridge.enabled()) {
            if (DungeonsInputBridge.middleButton(event.getAction() != GLFW.GLFW_RELEASE)) event.setCanceled(true);
        } else if (DungeonsInputBridge.middleHeld() && !BattleUiRegions.overUi()) {
            // Do not let a drag chord turn into an aim attack or a context menu.
            event.setCanceled(true);
        }
    }
}
