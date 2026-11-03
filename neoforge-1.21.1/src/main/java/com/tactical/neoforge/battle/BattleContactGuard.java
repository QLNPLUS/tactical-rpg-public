package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;

/**
 * The one place that decides whether a full encounter may keep a hostile away from its members.
 *
 * <p>The damage gate already refuses a refused mob's hits, but damage is not contact: an
 * unowned hostile can still shove a participant through ordinary entity collision, and a mob
 * without a target can do it while merely wandering. Two callers share this decision - the
 * target-change event (no re-locking) and the narrow entity-push guard (no displacement) - so
 * they can never disagree about which mobs are being held out.</p>
 *
 * <p>Every check is per pair and per encounter: participants of any encounter, players and any
 * unit owned elsewhere are never affected, and nothing here disables collision, knockback or AI
 * in general.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattleContactGuard {

    private BattleContactGuard() {
    }

    /**
     * The encounter that must keep {@code mob} away from {@code player}, or null when the pair is
     * not an isolation pair.
     */
    public static ServerBattle isolationFor(ServerPlayer player, Mob mob) {
        if (player == null || mob == null) return null;
        ServerBattle battle = BattleManager.containing(player);
        if (battle == null || battle.finished()) return null;
        // A participant is handled by the encounter's own rules, not by isolation.
        if (battle.isParticipant(mob)) return null;
        // Another encounter's unit is never ours to push or move.
        if (BattleManager.ownerOf(mob.getUUID()) != null) return null;
        if (mob.level() != battle.level()) return null;
        return battle.protectsFromContact(mob) ? battle : null;
    }

    /** Narrow collision rule: both directions of a participant/held-out-hostile pair are blocked. */
    public static boolean blocksPush(Entity first, Entity second) {
        if (first == null || second == null || first == second) return false;
        if (first.level() != second.level()) return false;
        if (first instanceof ServerPlayer player && second instanceof Mob mob) {
            return isolationFor(player, mob) != null;
        }
        if (second instanceof ServerPlayer player && first instanceof Mob mob) {
            return isolationFor(player, mob) != null;
        }
        return false;
    }

    /** No re-locking while the encounter is full; a participant's own target is never touched. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (!(target instanceof ServerPlayer player) || !(event.getEntity() instanceof Mob mob)) return;
        if (isolationFor(player, mob) == null) return;
        event.setCanceled(true);
        // Cancel alone would leave the Brain memory that produced the attempt; clear only this
        // member's relationship, so an unrelated player's aggro stays exactly as it was.
        AggroCleanup.clear(mob, player.getUUID());
    }

    /** True when the mob is hostile to the player side at all; used by diagnostics and tests. */
    public static boolean hostile(Mob mob) {
        return mob instanceof Enemy;
    }
}
