package com.tactical.neoforge.battle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
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
     * A participant may not reach an entity with a raw vanilla right click either: that would be an item
     * or an entity menu interaction outside the AP-checked battle scope. The scoped entity use opens the
     * door for exactly one call, because the lock asks the same {@link BattleToolUse} scope the battle
     * action established before it ran the native interaction.
     */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (locked(event.getEntity()) || controlledCompanionTouched(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (locked(event.getEntity()) || controlledCompanionTouched(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    /**
     * A recruited companion may not be changed by a raw vanilla right click, whoever clicks it.
     *
     * <p>Feeding, collar dyeing, equipment swapping and "sit" are exactly the free changes the five orders
     * replaced, so they are refused for the unit that is under control - by a participant, because the battle
     * action is the only path, and by a bystander, because the role of that unit is not theirs to change. The
     * paid, AP-checked entity use the battle itself performs opens the door through
     * {@link BattleToolUse#allowed(Player)}, so the legitimate path keeps working. A wolf outside any
     * encounter is never affected.</p>
     */
    private static boolean controlledCompanionTouched(Player player, Entity target) {
        if (player == null || !(target instanceof Mob mob)) return false;
        ServerBattle battle = BattleManager.containing(mob);
        if (battle == null || !battle.isCompanion(mob)) return false;
        return !BattleToolUse.allowed(player);
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
