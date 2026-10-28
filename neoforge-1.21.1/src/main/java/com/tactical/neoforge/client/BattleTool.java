package com.tactical.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/** Main-hand tools do not require a custom inventory menu or an inventory slot selection. */
public final class BattleTool {
    private BattleTool() { }
    public static void open() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||!BattleClientState.isPlayerTurn()||BattleClientState.moving) return;
        BattleScreenInput.syncSelectedSlot();
        BattleClientState.selectedSlot=mc.player.getInventory().selected;
        BattleClientState.mode=mc.player.getMainHandItem().getItem() instanceof BlockItem?BattleMode.PLACE:BattleMode.USE_ITEM;
        BattleAim.open();
    }
    public static void refresh() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||!(mc.screen instanceof BattleAim.BattleAimScreen)
                ||(BattleClientState.mode!=BattleMode.PLACE&&BattleClientState.mode!=BattleMode.USE_ITEM)) return;
        BattleClientState.selectedSlot=mc.player.getInventory().selected;
        BattleMode before=BattleClientState.mode;
        BattleClientState.mode=mc.player.getMainHandItem().getItem() instanceof BlockItem?BattleMode.PLACE:BattleMode.USE_ITEM;
        if(before!=BattleClientState.mode) BattleAim.refreshLabels();
    }
}
