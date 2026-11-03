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
        // Companion kinds are a plain factory table, not a registry: the built-in wolf adapter is installed
        // exactly once here, and an add-on may register its own before a battle ever runs.
        com.tactical.neoforge.registry.companion.CompanionRegistry.registerDefaults();
        com.tactical.neoforge.skill.ManaAttributes.register(modEventBus);
        // Character growth owns its own registration entry point: the attribute, the lifecycle listeners
        // and the skill formula variables are all installed exactly once, from here.
        com.tactical.neoforge.progression.ProgressionHandler.register(modEventBus);
        modEventBus.addListener(TacticalNetwork::register);
        NeoForge.EVENT_BUS.addListener(TacticalCommands::register);
        NeoForge.EVENT_BUS.addListener(BattleManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(CombatModifiers::onIncoming);
        NeoForge.EVENT_BUS.addListener(CombatModifiers::onDamage);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onUseItem);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(BattleItemLock::onEntityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(ServerSelfTest::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerSelfTest::onServerTick);
        // Companion fixture: a real server drives the production recruitment, order and release paths.
        NeoForge.EVENT_BUS.addListener(com.tactical.neoforge.battle.CompanionSelfTest::started);
        NeoForge.EVENT_BUS.addListener(com.tactical.neoforge.battle.CompanionSelfTest::tick);
        // Fixture-only entity type for the entity_item server self test: registered only while that
        // fixture runs, so a normal run and every release never see it.
        com.tactical.neoforge.battle.EntityItemSelfTest.registerFixtureType(modEventBus);
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                TacticalConfig.SPEC, "%s-common.toml".formatted(MOD_ID));
        LOGGER.info("[Tactical] bootstrap on {} (common: {})",
                FMLEnvironment.dist, com.tactical.core.combat.ApCalculator.defaultMaxAp());
    }
}
