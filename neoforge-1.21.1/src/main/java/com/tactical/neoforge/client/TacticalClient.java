package com.tactical.neoforge.client;

import com.mojang.brigadier.Command;
import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.parser.HTML;
import com.tactical.core.combat.ApCalculator;
import com.tactical.neoforge.Tactical;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Client bootstrap. Kept separate from {@link Tactical} so the dedicated server
 * never class-loads ApricityUI types.
 */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TacticalClient {

    private static final String PAGE_TEST_UI = "tactical/screens/tactical_test.html";
    private static final String PAGE_HUD = "tactical/overlays/battle_hud.html";
    private static final String PAGE_MENU = "tactical/screens/battle_menu.html";

    private static net.minecraft.client.KeyMapping menuKey;

    private TacticalClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        menuKey = new net.minecraft.client.KeyMapping(
                "key.tactical.menu", org.lwjgl.glfw.GLFW.GLFW_KEY_B, "key.categories.tactical");
        event.register(menuKey);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        registerTemplate(PAGE_TEST_UI);
        registerTemplate(PAGE_HUD);
        registerTemplate(TurnChargeHud.PAGE);
        registerTemplate(PAGE_MENU);
        registerTemplate("tactical/screens/battle_inventory.html");
        registerTemplate(BattleFacing.PAGE);
        registerTemplate(BattleAim.PAGE);
        registerTemplate(MoveChoiceScreen.PAGE);
        registerTemplate(SkillMenu.PAGE);
        registerTemplate(NearbyBattlesScreen.PAGE);
        registerTemplate(NearbyHint.PAGE);

        NeoForge.EVENT_BUS.addListener(TacticalClient::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(TacticalClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, WheelGuard::onScroll);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, InventoryUi::onClick);
        NeoForge.EVENT_BUS.addListener(BattleInput::onMouseButton);
        NeoForge.EVENT_BUS.addListener(BattleInput::onMovementInput);
        NeoForge.EVENT_BUS.addListener(BattleFacing::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, BattleFacing::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(GridRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HealthBarRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(TargetHighlightRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(VanillaHudGuard::onRenderGuiLayer);

        if (Boolean.getBoolean("tactical.selftest")) {
            event.enqueueWork(TacticalClient::runSelfTest);
        }
    }

    private static void runSelfTest() {
        Document document = openTestUi();
        Tactical.LOGGER.info("[Tactical] selftest document={}", document != null ? document.getUuid() : "null");
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        BattleTool.refresh();
        BattleHud.tick();
        BattleMenu.tick();
        BattleFacing.tick();
        MoveChoiceScreen.tickOpenScreen();
        CameraDirector.tick();
        BattleInput.onClientTick();
        WheelGuard.tick();
        InventoryUi.tick();
        UiZoom.tick();
        animateMover();
        BattleDiscoveryClientState.tick();
        NearbyBattlesScreen.tickOpenScreen();
        NearbyHint.tick();
        if (menuKey != null && menuKey.consumeClick()) {
            // In a battle B keeps the original command menu; outside one it opens the nearby list.
            if (BattleDiscoveryClientState.bOpensBattleMenu()) BattleMenu.open();
            else NearbyBattlesScreen.open();
        }
        if (Boolean.getBoolean("tactical.selftest.battle")) {
            runBattleSelfTest();
        }
        if (Boolean.getBoolean("tactical.selftest.ui")) {
            if (uiSelfTest == null) {
                uiSelfTest = UiSelfTest.start();
            }
            uiSelfTest.tick();
        }
    }

    /** Teleported grid steps do not produce vanilla walking, so drive the limb swing here. */
    private static void animateMover() {
        if (!BattleClientState.active || !BattleClientState.moving || BattleClientState.actorEntityId < 0) {
            return;
        }
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var entity = minecraft.level.getEntity(BattleClientState.actorEntityId);
        if (entity == minecraft.player && PlayerStepAnimation.interpolatedPosition(1.0F) == null) return;
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            living.walkAnimation.update(0.65F, 0.4F);
        }
    }

    private static boolean battleTestStarted;
    private static UiSelfTest uiSelfTest;
    private static boolean battleTestForced;
    private static boolean battleTestLogged;
    private static boolean battleTestMoveSent;
    private static boolean battleTestTurnEnded;
    private static boolean battleTestEnded;
    private static int battleTestTicks;

    private static void runBattleSelfTest() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        if (!battleTestStarted) {
            battleTestStarted = true;
            battleTestTicks = 0;
            minecraft.player.connection.sendCommand("summon zombie ~3 ~ ~");
            Tactical.LOGGER.info("[Tactical] selftest: summoning zombie");
            return;
        }

        battleTestTicks++;
        if (!battleTestForced && battleTestTicks > 40) {
            battleTestForced = true;
            minecraft.player.connection.sendCommand("tactical battle force");
            Tactical.LOGGER.info("[Tactical] selftest: forcing battle");
        }
        if (BattleClientState.active && !battleTestLogged) {
            battleTestLogged = true;
            Tactical.LOGGER.info(
                    "[Tactical] selftest battle active: ap={}/{} move={} reachable={} weapon={} kind={} order={}",
                    BattleClientState.ap, BattleClientState.maxAp, BattleClientState.moveCost,
                    BattleClientState.reachable.size(), BattleClientState.weapon,
                    BattleClientState.weaponKind, BattleClientState.order);
        }
        if (battleTestLogged && !battleTestMoveSent && !BattleClientState.reachable.isEmpty()) {
            battleTestMoveSent = true;
            var target = BattleClientState.reachable.iterator().next();
            var stamp = BattleClientState.stamp();
            if (stamp != null) PacketDistributor.sendToServer(
                    new com.tactical.neoforge.network.MoveIntentPacket(stamp, target.x(), target.y(), target.z()));
            Tactical.LOGGER.info("[Tactical] selftest move -> {}", target);
        }
        if (battleTestMoveSent && !battleTestTurnEnded && battleTestTicks > 200) {
            battleTestTurnEnded = true;
            var endStamp = BattleClientState.stamp();
            if (endStamp != null) PacketDistributor.sendToServer(new com.tactical.neoforge.network.EndTurnPacket(endStamp));
            Tactical.LOGGER.info("[Tactical] selftest end turn requested");
        }
        if (battleTestTurnEnded && battleTestTicks % 20 == 0) {
            Tactical.LOGGER.info("[Tactical] selftest state: active={} phase={} actor={} ap={} reachable={}",
                    BattleClientState.active, BattleClientState.phase, BattleClientState.actor,
                    BattleClientState.ap, BattleClientState.reachable.size());
        }
        if (battleTestLogged && !BattleClientState.active && !battleTestEnded) {
            battleTestEnded = true;
            Tactical.LOGGER.info("[Tactical] selftest battle ended");
        }
    }

    private static void registerTemplate(String path) {
        String resource = "/assets/apricityui/apricity/" + path;
        try (InputStream stream = TacticalClient.class.getResourceAsStream(resource)) {
            if (stream == null) {
                Tactical.LOGGER.warn("[Tactical] missing bundled page {}", resource);
                return;
            }
            String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            HTML.putTemple(path, html);
            Tactical.LOGGER.info("[Tactical] registered AUI page {}", path);
        } catch (IOException e) {
            Tactical.LOGGER.error("[Tactical] failed to read bundled page {}", resource, e);
        }
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tacticalui")
                .executes(context -> {
                    openTestUi();
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static Document openTestUi() {
        ApricityUI.removeDocument(PAGE_TEST_UI);
        Document document = ApricityUI.createDocument(PAGE_TEST_UI);
        if (document == null) {
            Tactical.LOGGER.warn("[Tactical] page {} was not registered", PAGE_TEST_UI);
            return null;
        }
        Element status = document.getElementById("status");
        if (status != null) {
            status.setTextContent("Java 绑定成功 · AP " + ApCalculator.defaultMaxAp());
        }
        Element ping = document.getElementById("ping");
        if (ping != null) {
            ping.addEventListener("click", ignored -> {
                if (status != null) {
                    status.setTextContent("按钮回调可用");
                }
            });
        }
        return document;
    }
}
