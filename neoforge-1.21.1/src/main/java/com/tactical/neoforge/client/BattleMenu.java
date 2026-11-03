package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.command.BattleMenuScreen;
import com.tactical.neoforge.network.FleePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Mouse-driven command panel shown automatically at the start of each player's turn. It lives on the
 * right edge of the screen so the battlefield stays visible behind it.
 *
 * <p>The panel itself is native ({@link BattleMenuScreen}); this class keeps the lifecycle the old
 * AUI page had - when to open, when to close, and which screen it may close - and forwards the eight
 * actions to their existing entry points.</p>
 */
public final class BattleMenu {

    private static boolean dismissed;
    private static boolean pendingReopen;
    private static int lastAction = -1;
    private static int lastTurn = -1;
    private static int lastWindowWidth = -1;
    private static int lastWindowHeight = -1;

    private BattleMenu() {
    }

    /** Called every client tick; opens the panel once per decision point. */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active) {
            closeOwnScreen(minecraft);
            reset();
            return;
        }
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (lastWindowWidth != -1 && (width != lastWindowWidth || height != lastWindowHeight)) {
            closeOwnScreen(minecraft);
            BattleClientState.mode = BattleMode.NONE;
            dismissed = false;
        }
        lastWindowWidth = width;
        lastWindowHeight = height;
        if (lastTurn != BattleClientState.round) {
            lastTurn = BattleClientState.round;
            dismissed = false;
        }
        if (!commandTurn()) {
            closeOwnScreen(minecraft);
            dismissed = false;
            pendingReopen = false;
            lastAction = BattleClientState.actionCount;
            return;
        }
        // The native panel reads the client state every frame, so no per-tick refill is needed.
        if (owns(minecraft.screen)) return;
        if (minecraft.screen != null) return;
        if (BattleClientState.commandMoving()) {
            pendingReopen = true;
            return;
        }
        if (pendingReopen) {
            pendingReopen = false;
            dismissed = false;
        }
        if (lastAction != BattleClientState.actionCount) {
            lastAction = BattleClientState.actionCount;
            dismissed = false;
        }
        if (!dismissed) {
            open();
        }
    }

    /**
     * True while this client has something to command: its own body's turn, or the turn of the companion the
     * projection says it controls.
     *
     * <p>Only the command surface reads this. Every privileged path (inventory, growth, blocks, fleeing)
     * keeps its own strict {@link BattleClientState#isPlayerTurn()} gate, so a companion turn can never
     * become a player turn.</p>
     */
    public static boolean commandTurn() {
        return BattleClientState.isPlayerTurn() || BattleClientState.isCommandingCompanion();
    }

    /**
     * True only for the screen this class owns.
     *
     * <p>Every tactical page keeps the battlefield visible, so "close the tactical screen" would also
     * close the nearby list, the journal or any other page the player opened for a reason of their
     * own. The battle menu may only ever close itself.</p>
     */
    public static boolean owns(Screen screen) {
        return screen instanceof com.tactical.neoforge.client.ui.screen.NativeTacticalScreen nativeScreen
                && ownsId(nativeScreen.screenId());
    }

    /**
     * The identity comparison behind {@link #owns}, kept pure so the ownership rule can be tested
     * headlessly. Only the command panel carries this id; every other tactical screen must survive a
     * battle tick that decides the menu is no longer wanted.
     */
    public static boolean ownsId(String id) {
        return BattleMenuScreen.SCREEN_ID.equals(id);
    }

    /** Close the battle menu if - and only if - it is the screen currently open. */
    private static void closeOwnScreen(Minecraft minecraft) {
        if (!owns(minecraft.screen)) return;
        minecraft.setScreen(null);
    }

    public static void reset() {
        dismissed = false;
        pendingReopen = false;
        lastAction = -1;
        lastTurn = -1;
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!BattleClientState.active || minecraft.screen != null) {
            return;
        }
        dismissed = true;
        // Opening the panel always drops any pending world action.
        BattleClientState.mode = BattleMode.NONE;
        BattleClientState.blockEntry = false;
        BattleClientState.selectedSlot = -1;
        minecraft.setScreen(new BattleMenuScreen());
    }

    /** Escape, the close button, or a cleared popover layer: the panel stays dismissed for this turn. */
    public static void dismiss() {
        Minecraft minecraft = Minecraft.getInstance();
        dismissed = true;
        BattleClientState.mode = BattleMode.NONE;
        BattleClientState.blockEntry = false;
        minecraft.setScreen(null);
    }

    /**
     * Runs one of the eight panel actions through the same entry points the AUI page used.
     *
     * <p>The AUI page was replaced by the aim/skill/facing screens in the same dispatch, which meant
     * those screens accepted "a tactical screen is already open". The native panel closes itself
     * first, exactly like the pre-existing defend and end-turn paths, so each target screen still
     * sees the screen state it expects.</p>
     */
    public static void activate(String action) {
        Minecraft minecraft = Minecraft.getInstance();
        // The character page is a view and not an action: it spends nothing, the server is not asked to take a
        // turn, and it therefore may not be swallowed by the busy gate below. Reading the page is allowed in
        // any phase, including while every other command is waiting for a forced fall to land.
        if ("character".equals(action)) {
            BattleClientState.mode = BattleMode.NONE;
            com.tactical.neoforge.client.ui.character.CharacterScreen.open();
            return;
        }
        // A forced fall is a hard barrier for commands, not only for clicks: the server runs nothing until
        // every falling unit has landed, so opening an aim page here would only produce a rejection line
        // after the player had already spent the click.
        if (BattleClientState.terrainFalling) return;
        switch (action) {
            case "move" -> {
                minecraft.setScreen(null);
                BattleClientState.mode = BattleMode.MOVE;
                BattleAim.open();
            }
            case "skill" -> {
                minecraft.setScreen(null);
                SkillMenu.open();
            }
            case "attack" -> {
                minecraft.setScreen(null);
                BattleClientState.mode = BattleMode.ATTACK;
                BattleAim.open();
            }
            case "tool" -> {
                minecraft.setScreen(null);
                BattleTool.open();
            }
            case "block" -> {
                minecraft.setScreen(null);
                BattleTool.openBlock();
            }
            case "defend" -> {
                minecraft.setScreen(null);
                BattleClientState.mode = BattleMode.NONE;
                BattleFacing.openDefend();
            }
            case "end" -> {
                minecraft.setScreen(null);
                BattleFacing.open();
            }
            case "flee" -> flee(minecraft);
            default -> {
            }
        }
    }

    private static void flee(Minecraft minecraft) {
        var stamp = BattleClientState.stamp();
        if (stamp == null) return;
        PacketDistributor.sendToServer(new FleePacket(stamp));
        BattleClientState.mode = BattleMode.NONE;
        minecraft.setScreen(null);
    }
}
