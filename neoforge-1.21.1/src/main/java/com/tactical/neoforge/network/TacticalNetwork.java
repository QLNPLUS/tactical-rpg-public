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
 * <p>Protocol 12: the battle snapshot carries the bounded command projection of the companion this recipient
 * may order right now, so a client still speaking protocol 11 is refused instead of reading a body whose
 * last block is missing, and a protocol-12 client never has to guess who is acting. Everything from protocol
 * 11 stands: breaking a block is its own intent and its own price, the snapshot carries native components and
 * every action quote, it is published as bounded parts, the use-item intent carries an optional entity
 * target, every client intent carries a session/turn stamp and is resolved against the real connection
 * identity, no payload can name the actor, and the nearby-list/join payloads carry only a target session
 * id.</p>
 *
 * <p>The skill channel (protocol 4) and the journal channel (protocol 2) keep their own versions: neither
 * wire changed here, and bumping them would refuse clients for no reason.</p>
 */
public final class TacticalNetwork {

    /** Bumped 11 -> 12 for the companion command projection in the snapshot. */
    public static final String PROTOCOL = "12";

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

        // A snapshot has exactly one way onto the wire: bounded parts. The logical payload type is kept as
        // the model (and as what a fixture captures) but is deliberately not registered, so no unparted
        // snapshot path exists to be preferred over the parted one.
        registrar.playToClient(BattleStateFragmentPacket.TYPE, BattleStateFragmentPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            () -> com.tactical.neoforge.client.BattleStateAssembler.accept(packet)));
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

        registrar.playToServer(BreakBlockPacket.TYPE, BreakBlockPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.breakBlock(player, packet.stamp(), packet.slot(), packet.pos());
                    }
                }));

        registrar.playToServer(UseItemPacket.TYPE, UseItemPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BattleManager.useItem(player, packet.stamp(), packet.slot(), packet.target());
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

        // Character growth: every intent names only a request id (and, where it must, the revision the
        // client already holds). The server resolves the player from the connection and answers with the
        // authoritative snapshot, so no payload can name another actor, a price or a final attribute.
        registrar.playToServer(CharacterRequestPacket.TYPE, CharacterRequestPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                                com.tactical.neoforge.progression.ProgressionService.snapshot(player,
                                        packet.requestId(), packet.openScreen()));
                    }
                }));

        registrar.playToServer(CharacterPreviewPacket.TYPE, CharacterPreviewPacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                                com.tactical.neoforge.progression.ProgressionService.preview(player,
                                        packet.requestId(), packet.expectedRevision(), packet.increments()));
                    }
                }));

        registrar.playToServer(CharacterUpgradePacket.TYPE, CharacterUpgradePacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                                com.tactical.neoforge.progression.ProgressionService.upgrade(player,
                                        packet.requestId(), packet.expectedRevision()));
                    }
                }));

        registrar.playToServer(CharacterAllocatePacket.TYPE, CharacterAllocatePacket.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.tactical.neoforge.network.ServerPackets.sendUncheckedType(player,
                                com.tactical.neoforge.progression.ProgressionService.allocate(player,
                                        packet.requestId(), packet.expectedRevision(), packet.increments()));
                    }
                }));

        // The character snapshot is a client cache, so it goes through the same connection-generation
        // gate every other S2C payload uses; a payload of a previous connection changes nothing.
        registrar.playToClient(CharacterStatePacket.TYPE, CharacterStatePacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> com.tactical.neoforge.client.ClientPayloadGate.apply(arrived,
                            () -> com.tactical.neoforge.client.progression.CharacterClientState.accept(packet)));
                });

        Tactical.LOGGER.info("[Tactical] registered {} network payloads at protocol {}", 24, PROTOCOL);
    }
}
