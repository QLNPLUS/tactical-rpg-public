package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.client.ui.debug.DevProbe;
import com.tactical.neoforge.client.ui.screen.NativeTacticalScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client bootstrap. Kept separate from {@link Tactical} so the dedicated server
 * never class-loads client-only types.
 *
 * <p>Every tactical screen, panel and HUD is drawn by this mod's own native toolkit; no page is
 * registered with a UI dependency, so this class only wires the native listeners, the key mapping
 * and the development probe.</p>
 */
@EventBusSubscriber(modid = Tactical.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TacticalClient {

    private static net.minecraft.client.KeyMapping menuKey;
    private static net.minecraft.client.KeyMapping characterKey;

    private TacticalClient() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        menuKey = new net.minecraft.client.KeyMapping(
                "key.tactical.menu", org.lwjgl.glfw.GLFW.GLFW_KEY_B, "key.categories.tactical");
        // The character page is bound to K and stays rebindable like every other key of this mod.
        characterKey = new net.minecraft.client.KeyMapping(
                "key.tactical.character", org.lwjgl.glfw.GLFW.GLFW_KEY_K, "key.categories.tactical");
        event.register(menuKey);
        event.register(characterKey);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.addListener(TacticalClient::onClientTick);
        // A tactical page swallows world key input (passEvents is false), so the character key also needs a real
        // screen-key route: it opens only from one of this mod's own tactical pages, never from a container or
        // the chat, and it stays a rebindable mapping rather than a hard-coded key code.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,
                TacticalClient::onTacticalScreenKey);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, WheelGuard::onScroll);
        NeoForge.EVENT_BUS.addListener(BattleInput::onMouseButton);
        NeoForge.EVENT_BUS.addListener(BattleInput::onMovementInput);
        NeoForge.EVENT_BUS.addListener(BattleFacing::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, BattleFacing::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(GridRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HealthBarRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(TargetHighlightRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(VanillaHudGuard::onRenderGuiLayer);
        // Development-only, read-only layout probe; inert unless the run sets -Dtactical.devprobe.port.
        DevProbe.startIfConfigured();
    }

    /** Keeps the probe honest: no native tactical screen on screen means the report is "null". */
    private static void publishProbeScreen() {
        if (!DevProbe.enabled()) return;
        boolean nativeScreen = net.minecraft.client.Minecraft.getInstance().screen
                instanceof NativeTacticalScreen;
        if (!nativeScreen) {
            DevProbe.publishClosed();
        }
        // The game's own screens answer from their own widgets, so a driver can click them for real.
        DevProbe.publishActiveScreen(!nativeScreen
                && net.minecraft.client.Minecraft.getInstance().screen != null);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        publishProbeScreen();
        BattleTool.refresh();
        BattleHud.tick();
        BattleMenu.tick();
        BattleFacing.tick();
        BattleAim.tickBinding();
        MoveChoiceScreen.tickOpenScreen();
        CameraDirector.tick();
        BattleInput.onClientTick();
        animateMover();
        BattleDiscoveryClientState.tick();
        NearbyBattlesScreen.tickOpenScreen();
        if (menuKey != null && menuKey.consumeClick()
                && !com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) {
            // In a battle B keeps the original command menu; outside one it opens the nearby list.
            if (BattleDiscoveryClientState.bOpensBattleMenu()) BattleMenu.open();
            else NearbyBattlesScreen.open();
        }
        tickCharacterPage();
        if (Boolean.getBoolean("tactical.selftest.battle")) {
            runBattleSelfTest();
        }
    }

    /**
     * The character page's two entry points and its only bridge to the answers.
     *
     * <p>The bound key opens the page when no other screen owns input - the world, or one of this mod's own
     * tactical pages - and never while a container or the chat is open. The server's own open intent (the
     * command answers with {@code openScreen}) arrives through the client state cache, so the page is opened
     * by exactly one of those two paths and never twice. While the page is open every new cached answer is
     * handed to it once, which is how a snapshot, a preview or a refusal becomes visible without the network
     * handler knowing about a screen.</p>
     */
    private static void tickCharacterPage() {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) return;
        // With no screen open the mapping's click is the only route; inside one of this mod's tactical pages the
        // real screen-key listener above handles it, so the two paths can never open the page twice.
        if (minecraft.screen == null && characterKey != null && characterKey.consumeClick()
                && !com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) {
            com.tactical.neoforge.client.ui.character.CharacterScreen.open();
            return;
        }
        if (!com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()
                && com.tactical.neoforge.client.progression.CharacterClientState.takeOpenRequest()
                && canOpenCharacterPage(minecraft)) {
            com.tactical.neoforge.client.ui.character.CharacterScreen.open();
            return;
        }
        com.tactical.neoforge.client.ui.character.CharacterScreen.pump();
    }

    private static void onTacticalScreenKey(net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (characterKey == null || characterKey.isUnbound()) return;
        var screen = event.getScreen();
        if (!(screen instanceof com.tactical.neoforge.client.ui.screen.TacticalSurface)) return;
        if (com.tactical.neoforge.client.ui.character.CharacterScreen.isOpen()) return;
        if (!characterKey.matches(event.getKeyCode(), event.getScanCode())) return;
        // The mapping's click queue is drained so the tick path cannot open the page a second time.
        while (characterKey.consumeClick()) { }
        com.tactical.neoforge.client.ui.character.CharacterScreen.open();
        event.setCanceled(true);
    }

    /** The world or one of this mod's own tactical pages, never a container or the chat. */
    private static boolean canOpenCharacterPage(net.minecraft.client.Minecraft minecraft) {
        if (minecraft.screen == null) return true;
        return minecraft.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                ? false
                : minecraft.screen instanceof com.tactical.neoforge.client.ui.screen.TacticalSurface;
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
}
