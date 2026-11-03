package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.screen.TacticalSurface;
import com.tactical.neoforge.client.ui.skill.SkillMenuScreen;
import net.minecraft.client.Minecraft;

/**
 * Combat skills: a native list of everything the character and its gear provide.
 *
 * <p>The page shows MP, the recovery line, the server's per-skill reason and cooldown, and hands a
 * usable skill over to the aiming page, which is where the target is chosen and the cast packet is
 * sent. This class only owns the lifecycle and the "back to the command panel" path.</p>
 */
public final class SkillMenu {

    private SkillMenu() {
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleMenu.commandTurn() || BattleClientState.commandMoving()
                || minecraft.screen != null && !(minecraft.screen instanceof TacticalSurface)) return;
        // Opening the list always drops a previous selection: the aiming page must not inherit one.
        SkillClientState.cancelSelection();
        // A companion has no skills this version can show or cast, so the page is a static empty state: no
        // owner skill request is sent and no owner MP is read. The list still opens, because the order exists.
        if (!BattleClientState.isCommandingCompanion()) SkillClientState.request();
        CommandPageGuard.bind();
        minecraft.setScreen(new SkillMenuScreen());
    }

    public static boolean isOpen() {
        return Minecraft.getInstance().screen instanceof SkillMenuScreen;
    }

    public static void back() {
        Minecraft minecraft = Minecraft.getInstance();
        SkillClientState.cancelSelection();
        minecraft.setScreen(null);
        BattleMenu.open();
    }

    /** The native page reads live state every frame, so the tick hook only keeps the lifecycle honest. */
    public static void tick() {
        if (!BattleMenu.commandTurn() || BattleClientState.commandMoving() || !CommandPageGuard.matches()) {
            if (isOpen()) back();
        }
    }
}
