package com.tactical.neoforge.battle;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Online-player lifecycle is recipient scoped, never a server-wide battle reset. */
@EventBusSubscriber(modid = Tactical.MOD_ID)
public final class BattlePlayerLifecycle {
    private BattlePlayerLifecycle() { }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // Only this player leaves; everybody else keeps the encounter.
        if (event.getEntity() instanceof ServerPlayer player) {
            BattleManager.leave(player, "player disconnected");
            // The connection is gone, so its published membership ends here; the generation counter is
            // kept, which is what lets the next connection's transitions stay above it.
            com.tactical.neoforge.battle.multi.MembershipGenerations.forget(player.getUUID());
            com.tactical.neoforge.battle.multi.PendingBootstraps.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer replacement)) return;
        ServerBattle battle = BattleManager.findFor(replacement.getUUID());
        // Respawn replaces the ServerPlayer instance. Match UUID only at this explicit boundary;
        // ordinary actions/logout retain exact identity checks to reject stale player objects.
        // Only the stale member record is released; a reconnect never restores membership.
        BattleManager.releaseReplacedMember(battle, replacement, "player respawned");
        syncInactiveUnlessMember(replacement);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) syncInactiveUnlessMember(player);
    }

    private static void syncInactiveUnlessMember(ServerPlayer recipient) {
        ServerBattle battle = BattleManager.findFor(recipient.getUUID());
        if (battle != null && battle.isMember(recipient)) return;
        // Login and respawn are a membership boundary of their own: publish an exit transition so the
        // client clears its display at a generation above anything it may still hold, and never joins
        // automatically because membership is not restored.
        com.tactical.neoforge.battle.multi.MembershipGenerations.deactivate(recipient, null);
        com.tactical.neoforge.battle.AttackRangeService.forget(recipient);
        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(recipient, BattleStatePacket.inactive());
    }

}
