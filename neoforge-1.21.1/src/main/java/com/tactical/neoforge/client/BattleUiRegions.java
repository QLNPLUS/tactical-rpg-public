package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.hud.NativeHud;
import com.tactical.neoforge.client.ui.layout.Point;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import net.minecraft.client.Minecraft;

/**
 * Where the interface owns the pointer: camera gestures belong to the world only outside it.
 *
 * <p>One GUI-pixel cursor and one native layout snapshot answer every question here. The raw mouse
 * position is converted exactly once, at this boundary, so no caller can mix window, framebuffer and
 * GUI coordinates. The battle HUD and every tactical screen answer from their own native layout, and
 * nothing here consults a UI dependency.</p>
 */
public final class BattleUiRegions {

    /** Vanilla hotbar/health/hunger band that no panel may cover. */
    private static final int HOTBAR_GUARD_WIDTH = 230;
    private static final int HOTBAR_GUARD_HEIGHT = 65;

    private BattleUiRegions() {
    }

    /** The pointer in GUI pixels: the one conversion from the window's physical position. */
    public static double[] cursorGui() {
        Minecraft minecraft = Minecraft.getInstance();
        int guiWidth = Math.max(1, minecraft.getWindow().getGuiScaledWidth());
        int guiHeight = Math.max(1, minecraft.getWindow().getGuiScaledHeight());
        return new double[]{
                minecraft.mouseHandler.xpos() * guiWidth / Math.max(1, minecraft.getWindow().getScreenWidth()),
                minecraft.mouseHandler.ypos() * guiHeight / Math.max(1, minecraft.getWindow().getScreenHeight())};
    }

    /** The same pointer as a value type, for the layout and probe code that takes one. */
    public static Point cursor() {
        double[] cursor = cursorGui();
        return Point.of(cursor[0], cursor[1]);
    }

    /** The hotbar slot under the pointer, or -1 when the pointer is elsewhere or the GUI is hidden. */
    public static int hotbarSlot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) return -1;
        double[] cursor = cursorGui();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (cursor[1] < height - 22 || cursor[1] > height) return -1;
        if (cursor[0] < width / 2.0F - 90 || cursor[0] >= width / 2.0F + 90) return -1;
        return (int) ((cursor[0] - (width / 2.0F - 90)) / 20);
    }

    /** True when the interface, not the battlefield, owns the pointer at this position. */
    public static boolean overUi() {
        Minecraft minecraft = Minecraft.getInstance();
        double[] cursor = cursorGui();
        if (NativeHud.overUi(cursor[0], cursor[1])) return true;
        // A native tactical screen answers from its own layout snapshot: only its panels own the
        // pointer, the rest of the screen keeps belonging to the world.
        if (minecraft.screen instanceof NativeTacticalScreen nativeScreen
                && nativeScreen.overUi(cursor[0], cursor[1])) return true;
        if (minecraft.screen != null && !(minecraft.screen instanceof TacticalSurface)) return true;
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        return !minecraft.options.hideGui
                && cursor[0] >= width / 2.0F - HOTBAR_GUARD_WIDTH / 2.0F
                && cursor[0] <= width / 2.0F + HOTBAR_GUARD_WIDTH / 2.0F
                && cursor[1] >= height - HOTBAR_GUARD_HEIGHT;
    }
}
