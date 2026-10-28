package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.battle.BattleManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Packet registration and handlers.
 *
 * <p>Protocol 8: every client intent carries a session/turn stamp and is resolved against
 * the real connection identity. No payload can name the actor, and the nearby-list/join
 * payloads carry only a target session id. An old client that still speaks protocol 7 is
 * refused instead of silently mixing.</p>
 */
public final class TacticalNetwork {

    /** Bumped 7 -> 8 for nearby discovery and direct opt-in joining. */
    public static final String PROTOCOL = "8";

    private TacticalNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);

        registrar.playToClient(MoveChoiceOfferPacket.TYPE,MoveChoiceOfferPacket.STREAM_CODEC,
                (packet,context)->{
                    Object arrived = context.connection();
                    context.enqueueWork(()->com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            ()->com.tactical.neoforge.client.MoveChoiceScreen.open(packet)));
                });
        registrar.playToServer(MoveChoicePacket.TYPE,MoveChoicePacket.STREAM_CODEC,
                (packet,context)->context.enqueueWork(()->{
                    if(context.player() instanceof ServerPlayer player)
                        BattleManager.chooseMove(player,packet.stamp(),packet.token(),packet.mode());
                }));

        registrar.playToClient(BattleStatePacket.TYPE, BattleStatePacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            () -> com.tactical.neoforge.client.BattleClientLifecycle.accept(packet)));
                });

        registrar.playToServer(MoveIntentPacket.TYPE, MoveIntentPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.move(player, packet.stamp(), packet.x(), packet.y(), packet.z());
                    }
                }));

        registrar.playToServer(AttackIntentPacket.TYPE, AttackIntentPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.attack(player, packet.stamp(), packet.target());
                    }
                }));

        registrar.playToServer(EndTurnPacket.TYPE, EndTurnPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.endTurn(player, packet.stamp());
                    }
                }));

        registrar.playToServer(FleePacket.TYPE, FleePacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.flee(player, packet.stamp());
                    }
                }));

        registrar.playToServer(DefendPacket.TYPE, DefendPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.defend(player, packet.stamp(), packet.yaw());
                    }
                }));

        registrar.playToServer(PlaceBlockPacket.TYPE, PlaceBlockPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.placeBlock(player, packet.stamp(), packet.slot(), packet.pos(), packet.face(), packet.hit());
                    }
                }));

        registrar.playToServer(UseItemPacket.TYPE, UseItemPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.useItem(player, packet.stamp(), packet.slot());
                    }
                }));

        registrar.playToServer(EndTurnFacingPacket.TYPE, EndTurnFacingPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.endTurnFacing(player, packet.stamp(), packet.yaw());
                    }
                }));

        registrar.playToServer(SwapSlotsPacket.TYPE, SwapSlotsPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.swapSlots(player, packet.stamp(), packet.first(), packet.second());
                    }
                }));

        registrar.playToServer(InventoryActionPacket.TYPE, InventoryActionPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.inventoryAction(player, packet.stamp(), packet.slot(), packet.action());
                    }
                }));

        registrar.playToServer(OpenInventoryPacket.TYPE, OpenInventoryPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.openInventory(player);
                    }
                }));

        // Nearby discovery: the player is the connection, never a payload field.
        registrar.playToServer(NearbyBattlesRequestPacket.TYPE, NearbyBattlesRequestPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.battle.BattleDiscovery.request(player, packet.requestId());
                    }
                }));

        registrar.playToClient(NearbyBattlesPacket.TYPE, NearbyBattlesPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            () -> com.tactical.neoforge.client.BattleDiscoveryClientState.accept(packet)));
                });

        // Direct opt-in join: only the target session travels, every rule is re-checked server side.
        registrar.playToServer(JoinBattleIntentPacket.TYPE, JoinBattleIntentPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    // The service answers and resends in the authoritative order; nothing is trusted here.
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.battle.BattleDiscovery.join(player, packet.sessionId());
                    }
                }));

        registrar.playToClient(JoinBattleResultPacket.TYPE, JoinBattleResultPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            () -> com.tactical.neoforge.client.BattleDiscoveryClientState.accept(packet)));
                });

        Tactical.LOGGER.info("[Tactical] registered {} network payloads at protocol {}", 16, PROTOCOL);
    }
}
