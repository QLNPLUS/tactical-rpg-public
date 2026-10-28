package com.tactical.neoforge.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.screen.ApricityContainerScreen;
import com.tactical.neoforge.network.InventoryActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;
import java.util.WeakHashMap;

/** Native menu pickup/drag stays vanilla; an empty cursor's right click opens item actions. */
public final class InventoryUi {
    private static final Map<Document, Long> WIRED = new WeakHashMap<>();
    private static int context = -1;
    private InventoryUi() {}
    private static boolean owns(ApricityContainerScreen s) {
        return s.getLinkedDocument() != null
                && "tactical/screens/battle_inventory.html".equals(s.getLinkedDocument().getPath());
    }
    public static void onClick(ScreenEvent.MouseButtonPressed.Pre e) {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ApricityContainerScreen s) || !owns(s) || mc.player == null) return;
        var menu = mc.player.containerMenu;
        if (e.getButton() != 1 || !menu.getCarried().isEmpty()) return;
        int index = s.findSlotIndexAt(e.getMouseX(), e.getMouseY());
        if (index < 0 || index >= menu.slots.size()) return;
        var slot = menu.slots.get(index);
        if (slot.container != mc.player.getInventory() || slot.getItem().isEmpty()) return;
        e.setCanceled(true);
        context = slot.getContainerSlot();
        Document d = s.getLinkedDocument();
        Element box = d.getElementById("inv-context");
        if (box == null) return;
        d.getElementById("context-title").setTextContent(slot.getItem().getHoverName().getString());
        d.getElementById("ctx-use").setTextContent(slot.getItem().getItem() instanceof BlockItem ? "放置" : "使用");
        box.setInlineStyleProperty("display", "block");
    }
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ApricityContainerScreen s) || !owns(s)) { context = -1; return; }
        if (!BattleClientState.isPlayerTurn() || BattleClientState.moving) {
            if (mc.player != null) mc.player.closeContainer();
            mc.setScreen(null);
            context = -1;
            return;
        }
        Document d = s.getLinkedDocument(); long gen = d.getRefreshGeneration();
        if (WIRED.getOrDefault(d, -1L) == gen) return;
        WIRED.put(d, gen);
        wire(d, "ctx-cancel", () -> hide(d));
        wire(d, "ctx-main", () -> action(d, InventoryActionPacket.SWAP_MAIN));
        wire(d, "ctx-off", () -> action(d, InventoryActionPacket.SWAP_OFF));
        wire(d, "ctx-drop", () -> action(d, InventoryActionPacket.DROP));
        wire(d, "ctx-use", () -> {
            if (mc.player == null || context < 0 || context >= 36 || !BattleClientState.isPlayerTurn()) return;
            int slot = context;
            boolean block = mc.player.getInventory().getItem(slot).getItem() instanceof BlockItem;
            hide(d);
            mc.player.closeContainer(); mc.setScreen(null);
            BattleClientState.selectedSlot = slot;
            BattleClientState.mode = block ? BattleMode.PLACE : BattleMode.USE_ITEM;
            BattleAim.open();
        });
    }
    private static void action(Document d, int action) {
        if (context < 0 || context >= 36) return;
        var stamp = BattleClientState.stamp();
        if (stamp == null) return;
        PacketDistributor.sendToServer(new InventoryActionPacket(stamp, context, action)); hide(d);
    }
    private static void hide(Document d) {
        context = -1;
        Element box = d.getElementById("inv-context");
        if (box != null) box.setInlineStyleProperty("display", "none");
    }
    private static void wire(Document d, String id, Runnable action) {
        Element e = d.getElementById(id);
        if (e != null) e.addEventListener("click", ev -> {
            ev.preventDefault(); ev.stopPropagation(); Minecraft.getInstance().tell(action);
        });
    }
}
