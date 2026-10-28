package com.tactical.neoforge.battle;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side turn gate for <em>vanilla</em> inventory and container packets.
 *
 * <p>A waiting member must not be able to change authoritative item state through
 * {@code ServerboundContainerClick}, container buttons, the recipe book, creative slot
 * writes, vanilla drops or the off-hand swap. Client-side gating (hiding the E screen) is
 * not proof: a waiting player can keep a container open from before the battle, or send
 * the packet directly.</p>
 *
 * <p>Only a member whose own turn it is, with nothing animating, may mutate items; the
 * player is then re-synchronized from the authoritative menu so a rejected click cannot
 * duplicate or lose a stack. Players outside any encounter are never affected.</p>
 */
public final class VanillaInventoryGuard {

    private VanillaInventoryGuard() { }

    /**
     * Pure decision so the rule itself is unit-testable without a server.
     *
     * @param member  this player participates in an encounter
     * @param ownTurn the encounter says this player holds the current turn and is not busy
     * @param finished the encounter has already finished
     */
    public static boolean allowed(boolean member, boolean ownTurn, boolean finished) {
        if (!member) return true;      // outsiders keep full vanilla behaviour
        if (finished) return true;     // the encounter is over, nothing left to protect
        return ownTurn;
    }

    /**
     * Which vanilla player actions move items and must therefore pass the turn gate.
     * Block breaking and mining are intentionally excluded: they are not item transport.
     */
    public static boolean guardsVanillaAction(
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action action) {
        if (action == null) return false;
        return switch (action) {
            case DROP_ITEM, DROP_ALL_ITEMS, SWAP_ITEM_WITH_OFFHAND -> true;
            default -> false;
        };
    }

    /** True when this player's vanilla item mutation must be rejected right now. */
    public static boolean denied(ServerPlayer player) {
        if (player == null || player.level().isClientSide) return false;
        ServerBattle battle = BattleManager.findFor(player.getUUID());
        if (battle == null || !battle.isMember(player)) return false;
        return !allowed(true, battle.isPlayerTurnNow(player), battle.finished());
    }

    /**
     * Reject the packet and restore the client's view from authoritative state.
     *
     * @return true when the caller must cancel the vanilla handler
     */
    public static boolean denyAndResync(ServerPlayer player) {
        if (!denied(player)) return false;
        resync(player);
        return true;
    }

    /**
     * Re-send the open menu and the inventory so a client-side predicted click cannot stay
     * applied after the server refused it. Nothing is dropped, nothing is duplicated.
     */
    public static void resync(ServerPlayer player) {
        if (player == null || player.connection == null) return;
        player.containerMenu.broadcastFullState();
        if (player.containerMenu != player.inventoryMenu) player.inventoryMenu.broadcastFullState();
        player.inventoryMenu.broadcastChanges();
    }
}
