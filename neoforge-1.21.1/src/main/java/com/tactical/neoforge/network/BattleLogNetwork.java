package com.tactical.neoforge.network;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.BattleLog;
import com.tactical.neoforge.client.ClientPayloadGate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Independent clientbound-only registration; client text can never become a journal event. */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class BattleLogNetwork {
    private BattleLogNetwork() { }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(BattleLogPacket.TYPE, BattleLogPacket.STREAM_CODEC,
                (packet, context) -> {
                    Object arrived = context.connection();
                    context.enqueueWork(() -> ClientPayloadGate.apply(arrived, () -> BattleLog.accept(packet)));
                });
    }
}
