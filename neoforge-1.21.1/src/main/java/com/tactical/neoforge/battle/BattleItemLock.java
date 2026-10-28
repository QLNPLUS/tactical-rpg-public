package com.tactical.neoforge.battle;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Battle participants may not use items or interact with blocks directly; all
 * item and block actions go through the battle command system instead.
 */
public final class BattleItemLock {

    private BattleItemLock() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (locked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (locked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (locked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onUseItem(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof Player player && locked(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Container storage stays unreachable for a waiting member because RightClickBlock is
     * already cancelled for participants outside a scoped battle action: a chest never
     * opens, so no vanilla item move can work around battle permissions.
     */
    private static boolean locked(Player player) {
        return BattleManager.isParticipant(player) && !BattleToolUse.allowed(player);
    }
}
