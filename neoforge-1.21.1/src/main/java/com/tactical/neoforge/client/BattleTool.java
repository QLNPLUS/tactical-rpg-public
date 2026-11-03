package com.tactical.neoforge.client;

import com.tactical.neoforge.client.ui.platform.ClientText;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;

/**
 * The two main-hand commands: using an item, and acting on blocks.
 *
 * <p>They are separate entries because they are separate commands with separate prices. The item
 * command never places a block: a main-hand {@link BlockItem} is answered with a pointer to the block
 * command instead, and the server refuses a placement that arrives through the item intent. The block
 * command reads the main hand once per frame - a block places, anything else breaks - so switching what
 * is held switches the mode without leaving the page.</p>
 */
public final class BattleTool {
    private BattleTool() { }

    /** True when the main hand holds something that can be placed. */
    public static boolean holdsBlock(Minecraft minecraft) {
        return minecraft != null && minecraft.player != null
                && minecraft.player.getMainHandItem().getItem() instanceof BlockItem;
    }

    /** The item command: use the main-hand item, either on the actor or on one entity. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !BattleClientState.isPlayerTurn() || BattleClientState.moving) return;
        BattleScreenInput.syncSelectedSlot();
        BattleClientState.selectedSlot = mc.player.getInventory().selected;
        BattleClientState.blockEntry = false;
        if (holdsBlock(mc)) {
            // Placement is the block command's job now; saying so is the whole answer, and the item page
            // is not opened for an action the server would refuse.
            BattleClientState.mode = BattleMode.NONE;
            mc.player.displayClientMessage(ClientText.component(ClientText.Keys.COMMAND_ROW_TOOL_BLOCK_HINT), true);
            return;
        }
        BattleClientState.mode = BattleMode.USE_ITEM;
        BattleAim.open();
    }

    /** The block command: place the held block, or break the block under the cursor. */
    public static void openBlock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !BattleClientState.isPlayerTurn() || BattleClientState.moving) return;
        BattleScreenInput.syncSelectedSlot();
        BattleClientState.selectedSlot = mc.player.getInventory().selected;
        BattleClientState.blockEntry = true;
        BattleClientState.mode = holdsBlock(mc) ? BattleMode.PLACE : BattleMode.BREAK;
        BattleAim.open();
    }

    /**
     * Follow the main hand while the block page is open.
     *
     * <p>Only the block entry changes mode here: the item entry stays an item use for as long as it is
     * open, which is what keeps the two commands from turning into each other mid-click.</p>
     */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !BattleAim.isOpen() || !BattleClientState.blockEntry) return;
        if (BattleClientState.mode != BattleMode.PLACE && BattleClientState.mode != BattleMode.BREAK) return;
        BattleClientState.selectedSlot = mc.player.getInventory().selected;
        BattleMode before = BattleClientState.mode;
        BattleClientState.mode = holdsBlock(mc) ? BattleMode.PLACE : BattleMode.BREAK;
        if (before != BattleClientState.mode) BattleAim.refreshLabels();
    }
}
