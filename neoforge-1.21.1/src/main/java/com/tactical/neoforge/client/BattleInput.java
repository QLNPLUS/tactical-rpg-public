package com.tactical.neoforge.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

/** Battle input never falls back to the player-facing vanilla hit result. */
public final class BattleInput {
    private BattleInput() {}
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!BattleClientState.active) return;
        var input = event.getInput();
        input.leftImpulse = input.forwardImpulse = 0;
        input.jumping = input.shiftKeyDown = input.up = input.down = input.left = input.right = false;
    }
    private static boolean releasedForBattle;
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (BattleClientState.active && mc.player != null) {
            releasedForBattle = true;
            // Screen closure must not briefly turn the cursor into camera-look input.
            if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        } else if (releasedForBattle) {
            releasedForBattle = false;
            if (mc.player != null && mc.screen == null && mc.isWindowActive()) mc.mouseHandler.grabMouse();
        }
    }
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.isCanceled() || DungeonsInputBridge.middleHeld()) return;
        if (!BattleClientState.active || event.getAction() != 1 || mc.screen != null
                || mc.player == null || mc.level == null) return;
        if (event.getButton() != 0 && event.getButton() != 1) return;
        double guiX=mc.mouseHandler.xpos()*mc.getWindow().getGuiScaledWidth()/Math.max(1,mc.getWindow().getScreenWidth());
        double guiY=mc.mouseHandler.ypos()*mc.getWindow().getGuiScaledHeight()/Math.max(1,mc.getWindow().getScreenHeight());
        if(BattleLog.interceptsAt(new com.sighs.apricityui.layout.Position((float)guiX,(float)guiY))) return;
        event.setCanceled(true);
        if (!BattleClientState.isPlayerTurn()) return;
        if (event.getButton() == 1 || BattleClientState.mode == BattleMode.NONE) {
            BattleMenu.open();
        } else {
            BattleAim.execute(mc, BattleAim.pickAtCursor());
        }
    }
}
