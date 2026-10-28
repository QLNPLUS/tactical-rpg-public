package com.tactical.neoforge;

import com.mojang.logging.LogUtils;
import com.tactical.neoforge.battle.BattleItemLock;
import com.tactical.neoforge.battle.BattleManager;
import com.tactical.neoforge.battle.CombatModifiers;
import com.tactical.neoforge.battle.ServerSelfTest;
import com.tactical.neoforge.battle.TacticalCommands;
import com.tactical.neoforge.network.TacticalNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Tactical.MOD_ID)
public final class Tactical {

    public static final String MOD_ID = "tactical";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tactical(IEventBus modEventBus, ModContainer modContainer) {
        com.tactical.neoforge.battle.TacticalAttributes.register(modEventBus);
        com.tactical.neoforge.skill.ManaAttributes.register(modEventBus);
        modEventBus.addListener(TacticalNetwork::register);
        NeoForge.EVENT_BUS.addListener(TacticalCommands::register);
        NeoForge.EVENT_BUS.addListener(BattleManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(CombatModifiers::onIncoming);
        NeoForge.EVENT_BUS.addListener(CombatModifiers::onDamage);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onUseItem);
        NeoForge.EVENT_BUS.addListener(ServerSelfTest::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerSelfTest::onServerTick);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                TacticalConfig.SPEC, "%s-common.toml".formatted(MOD_ID));
        LOGGER.info("[Tactical] bootstrap on {} (common: {})",
                FMLEnvironment.dist, com.tactical.core.combat.ApCalculator.defaultMaxAp());
    }
}
