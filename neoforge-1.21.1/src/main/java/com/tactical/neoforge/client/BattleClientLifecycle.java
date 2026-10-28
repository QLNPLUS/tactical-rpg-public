package com.tactical.neoforge.client;

import com.tactical.neoforge.Tactical;
import com.tactical.neoforge.network.BattleStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Only this client's local avatar defines its lifecycle; other players' death is irrelevant. */
@EventBusSubscriber(modid = Tactical.MOD_ID, value = Dist.CLIENT)
public final class BattleClientLifecycle {
    private static LocalPlayer owner;
    private static ClientLevel level;

    private BattleClientLifecycle() { }

    /**
     * The environment facts this handler needs, injectable so the real entry can be driven headlessly.
     * Production uses the local avatar; a fixture supplies the same three answers.
     */
    public interface Environment {
        boolean ready();
        boolean alive();
        int selfEntityId();
        /** UI-only cleanup (menus, overlays, camera, own screen). A fixture provides a no-op. */
        void teardownUi();

        /** UI-only: rebuild the action-cost labels after the quotes changed. */
        void refreshActionLabels();
    }

    private static final Environment MINECRAFT_ENVIRONMENT = new Environment() {
        @Override public boolean ready() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.player != null && minecraft.level != null;
        }

        @Override public boolean alive() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft != null && minecraft.player != null && minecraft.player.isAlive();
        }

        @Override public int selfEntityId() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null || minecraft.player == null ? -1 : minecraft.player.getId();
        }

        @Override public void refreshActionLabels() {
            BattleAim.refreshLabels();
        }

        @Override public void teardownUi() {
            BattleMenu.reset();
            PlayerStepAnimation.reset();
            CameraDirector.reset();
            BattleHud.close();
            Minecraft minecraft = Minecraft.getInstance();
            // Do not close DeathScreen, pause/chat, or another mod's screen.
            if (minecraft != null && minecraft.screen instanceof TacticalScreen) minecraft.setScreen(null);
        }
    };

    private static Environment environment = MINECRAFT_ENVIRONMENT;

    /** Fixture-only: drive this handler with a stub environment. */
    public static void environmentForTesting(Environment replacement) {
        environment = replacement == null ? MINECRAFT_ENVIRONMENT : replacement;
    }

    public static void accept(BattleStatePacket packet) {
        if (packet == null) return;
        if (!packet.active()) {
            // The server's own exit transition: the only thing that may stop showing an encounter.
            BattleMembership.Decision decision = BattleClientState.applyInactive(packet);
            if (decision == null) return;
            // Only the transition itself tears the UI down. A repeated replay of the same exit is
            // idempotent, and doing it twice would close a menu or the nearby list the player just
            // opened after leaving.
            if (decision != BattleMembership.Decision.ACCEPT_TRANSITION) return;
            BattleLog.retire(packet.sessionId(), packet.membershipGeneration());
            teardownUiOnly();
            return;
        }
        if (!environment.ready() || !environment.alive()) {
            // A snapshot this client cannot display still must not survive as state. The membership is
            // not touched: only a server transition ends it, and the next snapshot (or exit) carries one.
            BattleClientState.clear();
            teardownUiOnly();
            return;
        }
        // Own identity comes from the local avatar, so another player's turn is never treated as ours.
        BattleClientState.selfEntityId = environment.selfEntityId();
        boolean quotesChanged = BattleClientState.quotesChanged(packet);
        if (BattleClientState.apply(packet)) {
            owner = Minecraft.getInstance() == null ? null : Minecraft.getInstance().player;
            level = Minecraft.getInstance() == null ? null : Minecraft.getInstance().level;
            if (quotesChanged) environment.refreshActionLabels();
            BattleDiscoveryClientState.clearRows();
        }
    }

    /** Stop rendering and drop per-encounter caches while the membership record is kept. */
    private static void teardownUiOnly() {
        environment.teardownUi();
        CreeperTelegraphClientState.clear();
        owner = null;
        level = null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || !mc.player.isAlive()
                || (owner != null && (mc.player != owner || mc.level != level))) {
            end();
        }
    }

    /**
     * Full life-cycle teardown: logout, reconnect or a fatal local change.
     *
     * <p>It also starts a new membership connection, which forgets the shown encounter but remembers
     * the highest generation, so a payload still queued from the previous connection is refused while
     * the server's next transition (a newer generation) is still accepted.</p>
     */
    public static void end() {
        BattleClientState.clear();
        BattleMembership.beginConnection();
        BattleLog.disconnect();
        // Everything UI-shaped lives behind the environment, so the state/lifecycle decisions above are
        // the real code while a fixture can drive them without a renderer.
        environment.teardownUi();
        CreeperTelegraphClientState.clear();
        BattleDiscoveryClientState.clear();
        NearbyHint.close();
        owner = null;
        level = null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) { end(); }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) { end(); }
}
