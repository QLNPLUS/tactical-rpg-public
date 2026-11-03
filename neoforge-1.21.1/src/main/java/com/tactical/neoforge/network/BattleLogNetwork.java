package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.BattleLog;
import com.tactical.neoforge.client.ClientPayloadGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Independent clientbound-only registration; client text can never become a journal event.
 *
 * <p>The journal owns its own protocol version, so a wire change here is not hidden behind another
 * channel's number: a row now travels as a native component instead of a server-rendered string, which
 * makes protocol 1 and protocol 2 clients mutually incompatible on this channel. Both ends have to be
 * updated together - there is no negotiation and no compatibility shim.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BattleLogNetwork {
    /** Server 1 -> 2: journal rows became native components; requires the same version on both ends. */
    public static final String PROTOCOL = "2";

    private BattleLogNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).playToClient(BattleLogPacket.TYPE, BattleLogPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> ClientPayloadGate.apply(arrived, () -> BattleLog.accept(packet)));
                });
    }
}
