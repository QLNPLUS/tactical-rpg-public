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
        // A release over the HUD finishes a HUD gesture, never a battlefield one: the journal, the
        // roster and the rail are interface, and a press there was already swallowed.
        if(com.tactical.neoforge.client.ui.hud.NativeHud.overUi(guiX,guiY)) return;
        event.setCanceled(true);
        // A companion turn is a command turn too: its move/attack aiming is driven by the very same world
        // clicks, while every privileged path keeps its own strict player-turn gate.
        if (!BattleMenu.commandTurn()) return;
        if (event.getButton() == 1 || BattleClientState.mode == BattleMode.NONE) {
            BattleMenu.open();
        } else {
            BattleAim.execute(mc, BattleAim.pickAtCursor());
        }
    }
}
